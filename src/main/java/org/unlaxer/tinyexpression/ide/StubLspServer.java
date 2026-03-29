package org.unlaxer.tinyexpression.ide;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub LSP server for the MVP.
 * <p>
 * Provides basic completion (keywords + variables) and simple diagnostics
 * (unbalanced parentheses, unknown variable patterns).
 * <p>
 * This will be replaced by the full TinyExpressionP4LanguageServerExt
 * once it is wired in as a dependency.
 */
public class StubLspServer implements LanguageServer, LanguageClientAware {

    private LanguageClient client;
    private final Map<String, String> openDocuments = new ConcurrentHashMap<>();

    private static final List<String> KEYWORDS = List.of(
            "if", "else", "match", "default",
            "var", "variable", "as",
            "number", "string", "boolean", "object", "float",
            "set", "not", "exists", "description", "call",
            "true", "false", "null",
            "and", "or"
    );

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setTextDocumentSync(TextDocumentSyncKind.Full);
        caps.setCompletionProvider(new CompletionOptions(false, List.of("$", ".")));
        caps.setHoverProvider(true);
        return CompletableFuture.completedFuture(new InitializeResult(caps));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // no-op
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {

            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
                String uri = params.getTextDocument().getUri();
                String text = params.getTextDocument().getText();
                openDocuments.put(uri, text);
                publishDiagnostics(uri, text);
            }

            @Override
            public void didChange(DidChangeTextDocumentParams params) {
                String uri = params.getTextDocument().getUri();
                if (!params.getContentChanges().isEmpty()) {
                    String text = params.getContentChanges().get(0).getText();
                    openDocuments.put(uri, text);
                    publishDiagnostics(uri, text);
                }
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
                openDocuments.remove(params.getTextDocument().getUri());
            }

            @Override
            public void didSave(DidSaveTextDocumentParams params) {
                // no-op
            }

            @Override
            public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
                List<CompletionItem> items = new ArrayList<>();

                // Keyword completions
                for (String kw : KEYWORDS) {
                    CompletionItem item = new CompletionItem(kw);
                    item.setKind(CompletionItemKind.Keyword);
                    item.setDetail("TinyExpression keyword");
                    items.add(item);
                }

                // Variable placeholder
                CompletionItem varItem = new CompletionItem("$variableName");
                varItem.setKind(CompletionItemKind.Variable);
                varItem.setDetail("Variable reference");
                varItem.setInsertText("$");
                items.add(varItem);

                return CompletableFuture.completedFuture(Either.forLeft(items));
            }

            @Override
            public CompletableFuture<Hover> hover(HoverParams params) {
                String uri = params.getTextDocument().getUri();
                String text = openDocuments.get(uri);
                if (text == null) {
                    return CompletableFuture.completedFuture(null);
                }

                // Simple hover: show the word under cursor
                Position pos = params.getPosition();
                String[] lines = text.split("\n", -1);
                if (pos.getLine() >= lines.length) {
                    return CompletableFuture.completedFuture(null);
                }
                String line = lines[pos.getLine()];
                String word = getWordAt(line, pos.getCharacter());

                if (word != null && !word.isEmpty()) {
                    MarkupContent content = new MarkupContent();
                    content.setKind("markdown");
                    if (word.startsWith("$")) {
                        content.setValue("**Variable**: `" + word + "`");
                    } else if (KEYWORDS.contains(word)) {
                        content.setValue("**Keyword**: `" + word + "`");
                    } else {
                        content.setValue("`" + word + "`");
                    }
                    return CompletableFuture.completedFuture(new Hover(content));
                }

                return CompletableFuture.completedFuture(null);
            }
        };
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public void didChangeConfiguration(DidChangeConfigurationParams params) {}

            @Override
            public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}
        };
    }

    private void publishDiagnostics(String uri, String text) {
        if (client == null) return;

        List<Diagnostic> diagnostics = new ArrayList<>();

        // Check unbalanced parentheses
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '(') depth++;
            else if (text.charAt(i) == ')') depth--;
            if (depth < 0) {
                Position pos = offsetToPosition(text, i);
                Diagnostic d = new Diagnostic();
                d.setRange(new Range(pos, new Position(pos.getLine(), pos.getCharacter() + 1)));
                d.setSeverity(DiagnosticSeverity.Error);
                d.setMessage("Unmatched closing parenthesis");
                d.setSource("tinyexpression-ide");
                diagnostics.add(d);
                break;
            }
        }
        if (depth > 0) {
            Position pos = offsetToPosition(text, text.length() - 1);
            Diagnostic d = new Diagnostic();
            d.setRange(new Range(pos, new Position(pos.getLine(), pos.getCharacter() + 1)));
            d.setSeverity(DiagnosticSeverity.Error);
            d.setMessage("Unmatched opening parenthesis (" + depth + " unclosed)");
            d.setSource("tinyexpression-ide");
            diagnostics.add(d);
        }

        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    }

    private static Position offsetToPosition(String text, int offset) {
        int line = 0, col = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new Position(line, col);
    }

    private static String getWordAt(String line, int col) {
        if (col >= line.length()) col = line.length() - 1;
        if (col < 0) return null;

        int start = col;
        while (start > 0 && isWordChar(line.charAt(start - 1))) start--;
        int end = col;
        while (end < line.length() && isWordChar(line.charAt(end))) end++;

        if (start == end) return null;
        return line.substring(start, end);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
