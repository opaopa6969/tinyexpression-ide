# TinyExpression IDE

Web-based IDE for TinyExpression formulas, built with Monaco Editor and LSP over WebSocket.

## Features (MVP)

- **Monaco Editor** with TinyExpression syntax highlighting (keywords, variables, operators, strings, comments)
- **LSP over WebSocket** providing completion, diagnostics, and hover information
- **Real-time evaluation** with variable substitution and debounced preview
- **Variable auto-detection** from `$variableName` references in the formula
- **Token railroad display** showing the structural breakdown of formulas
- **Split-pane layout** with resizable editor (left) and preview panel (right)

## Architecture

```
Browser (Monaco + LSP client)
    |
    |  WebSocket (JSON-RPC)        POST /api/eval
    |                              |
    v                              v
Jetty Server (port 8080)
    |                              |
    v                              v
WebSocketLspBridge          EvalEndpoint
    |                              |
    v                              v
StubLspServer*             SimpleExpressionEvaluator
```

*The StubLspServer will be replaced by TinyExpressionP4LanguageServerExt once wired as a dependency.

## Prerequisites

- Java 21+
- Maven 3.9+
- The `unlaxer-common` and `unlaxer-dsl` artifacts installed in local Maven repository

## Build

```bash
mvn compile
```

## Run

```bash
mvn exec:java -Dexec.mainClass=org.unlaxer.tinyexpression.ide.IdeMain
```

Or build and run the JAR:

```bash
mvn package -DskipTests
java -cp "target/classes:$(mvn dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout)" \
     org.unlaxer.tinyexpression.ide.IdeMain
```

Then open http://localhost:8080 in a browser.

### Custom port

```bash
java -cp ... org.unlaxer.tinyexpression.ide.IdeMain 9090
```

## API

### POST /api/eval

Evaluate a TinyExpression formula.

**Request:**
```json
{
  "formula": "1 + $x * 2",
  "variables": { "x": "10" },
  "resultType": "number"
}
```

**Response:**
```json
{
  "result": "21",
  "error": null,
  "formula": "1 + $x * 2",
  "substituted": "1 + 10 * 2"
}
```

### WebSocket /lsp

LSP 3.17 protocol over WebSocket. Each message is a JSON-RPC payload (no Content-Length framing on the WebSocket layer).

## Project Structure

```
src/main/java/org/unlaxer/tinyexpression/ide/
  IdeMain.java                  - Entry point, Jetty server setup
  WebSocketLspBridge.java       - WebSocket <-> LSP stdio bridge
  StubLspServer.java            - MVP LSP server (keywords, diagnostics)
  EvalEndpoint.java             - REST evaluation endpoint
  SimpleExpressionEvaluator.java - Basic arithmetic evaluator (MVP stopgap)

src/main/resources/static/
  index.html                    - Single-page IDE (Monaco + preview panels)
```

## Roadmap

1. Wire in TinyExpressionP4LanguageServerExt for full LSP features
2. Wire in AstEvaluatorCalculator for full expression evaluation
3. Match expression folding UI
4. FormulaInfo metadata form editor
5. Dependency graph visualization (D3.js)
6. Electron/Tauri desktop packaging
