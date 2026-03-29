package org.unlaxer.tinyexpression.ide;

import java.net.URL;
import java.util.logging.Logger;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

/**
 * Main entry point for the TinyExpression Web IDE.
 * <p>
 * Starts a single Jetty server on port 8080 that provides:
 * <ol>
 *   <li>Static file serving (Monaco Editor HTML) from classpath:static/</li>
 *   <li>WebSocket endpoint at /lsp for LSP communication</li>
 *   <li>REST endpoint at /api/eval for formula evaluation</li>
 * </ol>
 * <p>
 * Usage: {@code java -cp ... org.unlaxer.tinyexpression.ide.IdeMain [port]}
 */
public class IdeMain {

    private static final Logger LOG = Logger.getLogger(IdeMain.class.getName());
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + ", using default " + DEFAULT_PORT);
            }
        }

        // Configure LSP server factory
        // For MVP, use a stub LSP server. Replace with TinyExpressionP4LanguageServerExt
        // once the tinyexpression-p4-lsp dependency is available.
        WebSocketLspBridge.setServerFactory(StubLspServer::new);

        Server server = new Server(port);

        // Servlet context for API and WebSocket
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Register eval REST endpoint
        context.addServlet(new ServletHolder("eval", new EvalEndpoint()), "/api/eval");

        // Register WebSocket endpoint via servlet
        JettyWebSocketServletContainerInitializer.configure(context,
                (servletContext, wsContainer) -> {
                    wsContainer.setMaxTextMessageSize(1024 * 1024);
                    wsContainer.addMapping("/lsp", (upgradeRequest, upgradeResponse) -> new WebSocketLspBridge());
                });

        // Static resource handler for HTML/JS/CSS
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(false);
        resourceHandler.setWelcomeFiles(new String[]{"index.html"});

        // Resolve static resources — try filesystem first (dev mode), then classpath
        java.io.File devDir = new java.io.File("src/main/resources/static");
        if (devDir.isDirectory()) {
            resourceHandler.setResourceBase(devDir.getAbsolutePath());
            LOG.info("Serving static files from: " + devDir.getAbsolutePath());
        } else {
            URL staticDir = IdeMain.class.getClassLoader().getResource("static");
            if (staticDir != null) {
                resourceHandler.setResourceBase(staticDir.toExternalForm());
                LOG.info("Serving static files from: " + staticDir);
            } else {
                LOG.warning("No static resources found!");
            }
        }

        // Combine handlers: static files first, then servlet context (API + WS)
        HandlerList handlers = new HandlerList();
        handlers.addHandler(resourceHandler);
        handlers.addHandler(context);

        server.setHandler(handlers);

        server.start();
        LOG.info("TinyExpression IDE started on http://localhost:" + port);
        LOG.info("  Editor:    http://localhost:" + port + "/");
        LOG.info("  LSP:       ws://localhost:" + port + "/lsp");
        LOG.info("  Eval API:  http://localhost:" + port + "/api/eval");
        server.join();
    }
}
