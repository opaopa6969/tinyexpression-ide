package org.unlaxer.tinyexpression.ide;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * Jetty WebSocket endpoint that bridges WebSocket messages to an in-process
 * LSP server via lsp4j's standard stdio-based launcher.
 * <p>
 * Architecture:
 * <pre>
 *   Browser (monaco-languageclient)
 *       |  WebSocket (JSON-RPC)
 *       v
 *   WebSocketLspBridge  (this class)
 *       |  PipedInputStream/PipedOutputStream
 *       v
 *   lsp4j Launcher  (reads/writes JSON-RPC on pipes)
 *       |
 *       v
 *   TinyExpressionP4LanguageServerExt  (LSP server implementation)
 * </pre>
 * <p>
 * Each WebSocket connection gets its own LSP server instance and pipe pair.
 */
@WebSocket(maxTextMessageSize = 1024 * 1024)
public class WebSocketLspBridge {

    private static final Logger LOG = Logger.getLogger(WebSocketLspBridge.class.getName());

    /** Functional interface for creating LSP server instances. */
    @FunctionalInterface
    public interface LspServerFactory {
        LanguageServer create();
    }

    private static volatile LspServerFactory serverFactory;

    /** Set the factory before starting the Jetty server. */
    public static void setServerFactory(LspServerFactory factory) {
        serverFactory = factory;
    }

    // -- per-connection state --
    private Session wsSession;
    private PipedOutputStream clientToServerOut;  // we write into this; server reads from paired input
    private PipedInputStream  serverToClientIn;   // server writes to paired output; we read from this
    private Future<Void> lspListening;
    private volatile boolean closed;

    @OnWebSocketConnect
    public void onConnect(Session session) {
        this.wsSession = session;
        LOG.info("LSP WebSocket connected: " + session.getRemoteAddress());

        try {
            // Pipes: client->server
            clientToServerOut = new PipedOutputStream();
            PipedInputStream clientToServerIn = new PipedInputStream(clientToServerOut, 1024 * 1024);

            // Pipes: server->client  (server writes here, we pump to WebSocket)
            PipedOutputStream serverToClientOut = new PipedOutputStream();
            serverToClientIn = new PipedInputStream(serverToClientOut, 1024 * 1024);

            // Create LSP server instance
            LspServerFactory factory = serverFactory;
            if (factory == null) {
                LOG.severe("No LSP server factory configured");
                session.close(1011, "Server not configured");
                return;
            }
            LanguageServer server = factory.create();

            // Wire up lsp4j launcher on the piped streams
            Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(
                    server, clientToServerIn, serverToClientOut);

            // Connect the server to the client proxy
            if (server instanceof org.eclipse.lsp4j.services.LanguageClientAware aware) {
                aware.connect(launcher.getRemoteProxy());
            }

            // Start listening (blocking in background thread)
            lspListening = launcher.startListening();

            // Pump server->client output to WebSocket in a daemon thread
            Thread pump = new Thread(() -> pumpServerToWebSocket(), "lsp-ws-pump-" + session.getRemoteAddress());
            pump.setDaemon(true);
            pump.start();

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to set up LSP pipes", e);
            session.close(1011, "Pipe setup failed");
        }
    }

    @OnWebSocketMessage
    public void onMessage(String message) {
        if (closed || clientToServerOut == null) return;

        try {
            // LSP over stdio uses Content-Length header framing
            byte[] content = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String header = "Content-Length: " + content.length + "\r\n\r\n";
            clientToServerOut.write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            clientToServerOut.write(content);
            clientToServerOut.flush();
        } catch (IOException e) {
            if (!closed) {
                LOG.log(Level.WARNING, "Error forwarding WS message to LSP", e);
            }
        }
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        LOG.info("LSP WebSocket closed: " + statusCode + " " + reason);
        cleanup();
    }

    @OnWebSocketError
    public void onError(Throwable cause) {
        if (!closed) {
            LOG.log(Level.WARNING, "LSP WebSocket error", cause);
        }
        cleanup();
    }

    private void cleanup() {
        closed = true;
        try { if (clientToServerOut != null) clientToServerOut.close(); } catch (IOException ignored) {}
        try { if (serverToClientIn != null)  serverToClientIn.close();  } catch (IOException ignored) {}
        if (lspListening != null) lspListening.cancel(true);
    }

    /**
     * Reads LSP JSON-RPC responses from the server output pipe and sends them
     * over the WebSocket. Parses Content-Length headers to frame messages.
     */
    private void pumpServerToWebSocket() {
        try {
            StringBuilder headerBuf = new StringBuilder();
            while (!closed && wsSession != null && wsSession.isOpen()) {
                // Read headers until blank line
                headerBuf.setLength(0);
                int contentLength = -1;
                while (true) {
                    String line = readLine(serverToClientIn);
                    if (line == null) return; // stream closed
                    if (line.isEmpty()) break; // end of headers
                    if (line.startsWith("Content-Length:")) {
                        contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                    }
                }
                if (contentLength < 0) continue;

                // Read exactly contentLength bytes
                byte[] body = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = serverToClientIn.read(body, read, contentLength - read);
                    if (n < 0) return;
                    read += n;
                }

                String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                if (wsSession != null && wsSession.isOpen()) {
                    wsSession.getRemote().sendString(json);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.log(Level.FINE, "Server-to-client pump ended", e);
            }
        }
    }

    /** Read a line (terminated by \r\n or \n) from the input stream. */
    private static String readLine(PipedInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                // strip trailing \r
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
                    sb.setLength(sb.length() - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
