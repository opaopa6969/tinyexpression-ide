package org.unlaxer.tinyexpression.ide;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * MCP Streamable HTTP endpoint: POST /mcp
 * <p>
 * Implements JSON-RPC 2.0 over HTTP POST for the Model Context Protocol.
 * Handles: initialize, tools/list, tools/call, resources/list, resources/read.
 * <p>
 * Session management via mcp-session-id header (single transport per session).
 */
public class McpEndpoint extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(McpEndpoint.class.getName());
    private static final Gson GSON = new Gson();
    private static final Gson GSON_WITH_NULLS = new GsonBuilder().serializeNulls().create();

    static final String NAMESPACE = "tinyexpression-ide";
    static final String VERSION = "0.1.0";
    static final String PROTOCOL_VERSION = "2025-06-18";

    /** Maximum accepted request body size in bytes. Larger bodies are rejected
     *  with 413 before being fully buffered, to prevent unbounded memory use. */
    static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Sessions are kept while active, but abandoned sessions must not grow forever. */
    static final long SESSION_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final long SESSION_CLEANUP_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionCleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mcp-session-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void init() {
        sessionCleanup.scheduleAtFixedRate(this::removeExpiredSessions,
                SESSION_CLEANUP_INTERVAL_MILLIS, SESSION_CLEANUP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        sessionCleanup.shutdownNow();
        sessions.clear();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        removeExpiredSessions();
        touchSession(req.getHeader("mcp-session-id"));
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Content-Encoding", "identity");

        String body;
        try {
            body = readBody(req);
        } catch (RequestBodyTooLargeException e) {
            JsonObject err = makeError(null, -32603, "Request body too large (max " + MAX_BODY_BYTES + " bytes)");
            resp.setStatus(413);
            try (PrintWriter out = resp.getWriter()) {
                out.print(GSON_WITH_NULLS.toJson(err));
                out.flush();
            }
            return;
        }
        JsonObject request = GSON.fromJson(body, JsonObject.class);

        String method = getStringOrNull(request, "method");
        String id = request.has("id") && !request.get("id").isJsonNull()
                ? GSON.toJson(request.get("id"))
                : null;

        JsonObject result;
        int status = 200;

        try {
            switch (method == null ? "" : method) {
                case "initialize":
                    result = handleInitialize(req, resp, request);
                    break;
                case "notifications/initialized":
                    result = null;
                    break;
                case "tools/list":
                    result = handleToolsList();
                    break;
                case "tools/call":
                    result = handleToolsCall(request);
                    break;
                case "resources/list":
                    result = handleResourcesList();
                    break;
                case "resources/read":
                    result = handleResourcesRead(request);
                    break;
                case "ping":
                    result = new JsonObject();
                    break;
                default:
                    result = makeError(id, -32601, "Method not found: " + method);
                    status = 200;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "MCP request failed: " + method, e);
            result = makeError(id, -32603, "Internal error: " + e.getMessage());
        }

        if (result == null) {
            resp.setStatus(202);
            return;
        }

        resp.setStatus(status);
        try (PrintWriter out = resp.getWriter()) {
            // Use GSON_WITH_NULLS so that a JSON-RPC envelope carrying id: null
            // (a valid diagnostic id per JSON-RPC 2.0 §4.2) is serialized with the
            // "id" member present, instead of having it stripped by the default
            // serializeNulls=false behaviour. Other envelope members are never
            // set to null, so this only affects the id field.
            out.print(GSON_WITH_NULLS.toJson(result));
            out.flush();
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Content-Encoding", "identity");
        resp.setStatus(405);
        JsonObject err = makeError("null", -32600, "GET not supported. Use POST for JSON-RPC.");
        try (PrintWriter out = resp.getWriter()) {
            out.print(GSON_WITH_NULLS.toJson(err));
            out.flush();
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) {
        removeExpiredSessions();
        String sessionId = req.getHeader("mcp-session-id");
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        resp.setStatus(204);
    }

    // --- JSON-RPC handlers ---

    private JsonObject handleInitialize(HttpServletRequest req, HttpServletResponse resp, JsonObject request) {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, System.currentTimeMillis() + SESSION_TTL_MILLIS);
        // MCP Streamable HTTP: the server MUST return the session id via the
        // mcp-session-id response header so the client can echo it on subsequent
        // requests (and use it for DELETE to tear down the session).
        resp.setHeader("mcp-session-id", sessionId);

        JsonObject result = new JsonObject();
        result.addProperty("jsonrpc", "2.0");
        result.add("id", request.get("id"));

        JsonObject inner = new JsonObject();
        inner.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        caps.add("resources", new JsonObject());
        inner.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", NAMESPACE);
        info.addProperty("version", VERSION);
        inner.add("serverInfo", info);
        result.add("result", inner);

        return result;
    }

    private void touchSession(String sessionId) {
        if (sessionId != null) {
            sessions.computeIfPresent(sessionId,
                    (ignored, ignoredExpiry) -> System.currentTimeMillis() + SESSION_TTL_MILLIS);
        }
    }

    private void removeExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private JsonObject handleToolsList() {
        JsonObject result = new JsonObject();
        result.addProperty("jsonrpc", "2.0");
        result.add("id", new JsonPrimitive("null"));

        JsonArray tools = new JsonArray();
        tools.add(makeEvaluateTool());
        tools.add(makeValidateTool());

        JsonObject toolsObj = new JsonObject();
        toolsObj.add("tools", tools);
        result.add("result", toolsObj);
        return result;
    }

    private JsonObject makeEvaluateTool() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", "evaluate");
        tool.addProperty("description",
                "TinyExpression 式を評価する。危険度: read（状態を変更しない）。"
                + "前提: formula は必須。variables は $変数名 を置換するための文字列マップ。"
                + "注意: 現状は MVP 簡易評価器（四則演算 + 括弧 + 変数置換のみ）。"
                + "本命の TinyExpression 評価（if/match/string/boolean 等）は tinyexpr__evaluate を使用すること。");
        tool.add("inputSchema", makeEvaluateSchema());
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", true);
        annotations.addProperty("idempotentHint", true);
        annotations.addProperty("openWorldHint", false);
        tool.add("annotations", annotations);
        return tool;
    }

    private JsonObject makeEvaluateSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject formula = new JsonObject();
        formula.addProperty("type", "string");
        formula.addProperty("description", "評価する TinyExpression 式（例: 1 + $x * 2）");
        props.add("formula", formula);
        JsonObject variables = new JsonObject();
        variables.addProperty("type", "object");
        variables.addProperty("description", "変数名→値の文字列マップ（例: {\"x\": \"10\"}）。$変数名 を置換する");
        JsonObject varSchema = new JsonObject();
        varSchema.addProperty("type", "string");
        variables.add("additionalProperties", varSchema);
        props.add("variables", variables);
        JsonObject resultType = new JsonObject();
        resultType.addProperty("type", "string");
        resultType.addProperty("description", "結果型（number/string/boolean）。現状は未実装・無視される");
        props.add("resultType", resultType);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("formula");
        schema.add("required", required);
        return schema;
    }

    private JsonObject makeValidateTool() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", "validate");
        tool.addProperty("description",
                "TinyExpression 式の構文を検証する。危険度: read。"
                + "括弧のバランスと基本的な構文チェックを行う。"
                + "現状は簡易診断（括弧バランスのみ）。将来的に unlaxer__parse で本命パーサを利用予定。");
        tool.add("inputSchema", makeValidateSchema());
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", true);
        annotations.addProperty("idempotentHint", true);
        annotations.addProperty("openWorldHint", false);
        tool.add("annotations", annotations);
        return tool;
    }

    private JsonObject makeValidateSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject formula = new JsonObject();
        formula.addProperty("type", "string");
        formula.addProperty("description", "検証する TinyExpression 式");
        props.add("formula", formula);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("formula");
        schema.add("required", required);
        return schema;
    }

    private JsonObject handleToolsCall(JsonObject request) {
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        String toolName = getStringOrNull(params, "name");
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();

        JsonObject result = new JsonObject();
        result.addProperty("jsonrpc", "2.0");
        result.add("id", request.has("id") ? request.get("id") : new JsonPrimitive("null"));

        JsonObject content;

        switch (toolName == null ? "" : toolName) {
            case "evaluate":
                content = callEvaluate(args);
                break;
            case "validate":
                content = callValidate(args);
                break;
            default:
                JsonObject errObj = new JsonObject();
                errObj.addProperty("code", -32602);
                errObj.addProperty("message", "Unknown tool: " + toolName);
                result.add("error", errObj);
                return result;
        }

        result.add("result", content);
        return result;
    }

    private JsonObject callEvaluate(JsonObject args) {
        String formula = getStringOrNull(args, "formula");
        Map<String, String> variables = new LinkedHashMap<>();
        if (args.has("variables") && args.get("variables").isJsonObject()) {
            JsonObject vars = args.getAsJsonObject("variables");
            for (Map.Entry<String, JsonElement> entry : vars.entrySet()) {
                variables.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        JsonObject evalResult = new JsonObject();
        if (formula == null || formula.isBlank()) {
            evalResult.addProperty("error", "Missing 'formula' field");
            evalResult.addProperty("result", (String) null);
            return wrapToolResult(evalResult);
        }

        // Single-pass variable substitution: each $varName token is replaced
        // exactly once with its literal value, so values containing $-tokens
        // are not re-interpreted in a later pass (no double substitution).
        String substituted = VariableSubstituter.substitute(formula, variables);

        try {
            Object evalResultObj = SimpleExpressionEvaluator.evaluate(substituted);
            evalResult.addProperty("result", String.valueOf(evalResultObj));
            evalResult.addProperty("error", (String) null);
            evalResult.addProperty("formula", formula);
            evalResult.addProperty("substituted", substituted);
        } catch (Exception e) {
            evalResult.addProperty("result", (String) null);
            evalResult.addProperty("error", e.getMessage());
            evalResult.addProperty("formula", formula);
            evalResult.addProperty("substituted", substituted);
        }

        return wrapToolResult(evalResult);
    }

    private JsonObject callValidate(JsonObject args) {
        String formula = getStringOrNull(args, "formula");
        JsonObject validateResult = new JsonObject();

        if (formula == null || formula.isBlank()) {
            validateResult.addProperty("valid", false);
            JsonArray diags = new JsonArray();
            JsonObject d = new JsonObject();
            d.addProperty("severity", "Error");
            d.addProperty("message", "Missing 'formula' field");
            JsonObject range = new JsonObject();
            range.addProperty("line", 0);
            range.addProperty("character", 0);
            d.add("range", range);
            diags.add(d);
            validateResult.add("diagnostics", diags);
            validateResult.addProperty("formula", formula == null ? "" : formula);
            return wrapToolResult(validateResult);
        }

        List<JsonObject> diagnostics = validateFormula(formula);
        validateResult.addProperty("valid", diagnostics.isEmpty());
        JsonArray diagArray = new JsonArray();
        for (JsonObject d : diagnostics) {
            diagArray.add(d);
        }
        validateResult.add("diagnostics", diagArray);
        validateResult.addProperty("formula", formula);

        return wrapToolResult(validateResult);
    }

    private List<JsonObject> validateFormula(String text) {
        List<JsonObject> diagnostics = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '(') depth++;
            else if (text.charAt(i) == ')') depth--;
            if (depth < 0) {
                JsonObject d = new JsonObject();
                int[] pos = offsetToPosition(text, i);
                JsonObject range = new JsonObject();
                range.addProperty("line", pos[0]);
                range.addProperty("character", pos[1]);
                d.add("range", range);
                d.addProperty("severity", "Error");
                d.addProperty("message", "Unmatched closing parenthesis");
                diagnostics.add(d);
                return diagnostics;
            }
        }
        if (depth > 0) {
            JsonObject d = new JsonObject();
            int[] pos = offsetToPosition(text, text.length() - 1);
            JsonObject range = new JsonObject();
            range.addProperty("line", pos[0]);
            range.addProperty("character", pos[1]);
            d.add("range", range);
            d.addProperty("severity", "Error");
            d.addProperty("message", "Unmatched opening parenthesis (" + depth + " unclosed)");
            diagnostics.add(d);
        }
        return diagnostics;
    }

    private static int[] offsetToPosition(String text, int offset) {
        int line = 0, col = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }

    private JsonObject wrapToolResult(JsonObject data) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", GSON_WITH_NULLS.toJson(data));
        content.add(textContent);
        result.add("content", content);
        return result;
    }

    private JsonObject handleResourcesList() {
        JsonObject result = new JsonObject();
        result.addProperty("jsonrpc", "2.0");
        result.add("id", new JsonPrimitive("null"));

        JsonArray resources = new JsonArray();

        resources.add(makeResource(NAMESPACE + "://spec", "TinyExpression IDE バックエンド仕様", "application/json"));
        resources.add(makeResource(NAMESPACE + "://guide", "TinyExpression IDE 使い方ガイド", "text/markdown"));
        resources.add(makeResource("skill://formula-eval-workflow", "式評価ワークフロー手順（SKILL.md）", "text/markdown"));

        JsonObject resObj = new JsonObject();
        resObj.add("resources", resources);
        result.add("result", resObj);
        return result;
    }

    private JsonObject makeResource(String uri, String description, String mimeType) {
        JsonObject res = new JsonObject();
        res.addProperty("uri", uri);
        res.addProperty("name", uri);
        res.addProperty("description", description);
        res.addProperty("mimeType", mimeType);
        return res;
    }

    private JsonObject handleResourcesRead(JsonObject request) {
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        String uri = getStringOrNull(params, "uri");

        JsonObject result = new JsonObject();
        result.addProperty("jsonrpc", "2.0");
        result.add("id", request.has("id") ? request.get("id") : new JsonPrimitive("null"));

        String text;
        String mimeType;

        if (uri == null) {
            JsonObject errObj = new JsonObject();
            errObj.addProperty("code", -32602);
            errObj.addProperty("message", "Missing 'uri' parameter");
            result.add("error", errObj);
            return result;
        }

        switch (uri) {
            case "tinyexpression-ide://spec":
                text = buildSpecResource();
                mimeType = "application/json";
                break;
            case "tinyexpression-ide://guide":
                text = buildGuideResource();
                mimeType = "text/markdown";
                break;
            case "skill://formula-eval-workflow":
                text = buildSkillResource();
                mimeType = "text/markdown";
                break;
            default:
                JsonObject errObj = new JsonObject();
                errObj.addProperty("code", -32602);
                errObj.addProperty("message", "Unknown resource URI: " + uri);
                result.add("error", errObj);
                return result;
        }

        JsonObject contents = new JsonObject();
        contents.addProperty("uri", uri);
        contents.addProperty("mimeType", mimeType);
        contents.addProperty("text", text);
        JsonArray contentsArray = new JsonArray();
        contentsArray.add(contents);
        JsonObject resultObj = new JsonObject();
        resultObj.add("contents", contentsArray);
        result.add("result", resultObj);
        return result;
    }

    private String buildSpecResource() {
        JsonObject spec = new JsonObject();
        spec.addProperty("namespace", NAMESPACE);
        spec.addProperty("name", "TinyExpression IDE");
        spec.addProperty("version", VERSION);
        spec.addProperty("summary", "TinyExpression 式の評価・検証 MCP（MVP 簡易評価器）");

        JsonArray capabilities = new JsonArray();

        JsonObject eval = new JsonObject();
        eval.addProperty("kind", "tool");
        eval.addProperty("name", "evaluate");
        eval.addProperty("summary", "TinyExpression 式を評価する（MVP 簡易版: 四則演算+変数置換）");
        eval.addProperty("input", "{formula: string, variables?: {string: string}, resultType?: string}");
        eval.addProperty("output", "{result: string|null, error: string|null, formula: string, substituted: string}");
        eval.addProperty("side_effect", "read");
        eval.addProperty("long_running", false);
        eval.addProperty("dry_run", false);
        eval.addProperty("min_role", "MEMBER");
        capabilities.add(eval);

        JsonObject val = new JsonObject();
        val.addProperty("kind", "tool");
        val.addProperty("name", "validate");
        val.addProperty("summary", "TinyExpression 式の構文を検証する（括弧バランス）");
        val.addProperty("input", "{formula: string}");
        val.addProperty("output", "{valid: boolean, diagnostics: [{range, severity, message}], formula: string}");
        val.addProperty("side_effect", "read");
        val.addProperty("long_running", false);
        val.addProperty("dry_run", false);
        val.addProperty("min_role", "MEMBER");
        capabilities.add(val);

        JsonObject specRes = new JsonObject();
        specRes.addProperty("kind", "resource");
        specRes.addProperty("name", "spec");
        specRes.addProperty("summary", "バックエンド仕様");
        capabilities.add(specRes);

        JsonObject guideRes = new JsonObject();
        guideRes.addProperty("kind", "resource");
        guideRes.addProperty("name", "guide");
        guideRes.addProperty("summary", "IDE 使い方ガイド");
        capabilities.add(guideRes);

        JsonObject skillRes = new JsonObject();
        skillRes.addProperty("kind", "skill");
        skillRes.addProperty("name", "formula-eval-workflow");
        skillRes.addProperty("summary", "式評価ワークフロー手順");
        capabilities.add(skillRes);

        spec.add("capabilities", capabilities);

        JsonArray compositions = new JsonArray();
        JsonObject comp1 = new JsonObject();
        comp1.addProperty("title", "式評価前にバリデーション");
        comp1.add("flow", new JsonArray());
        comp1.getAsJsonArray("flow").add("tinyexpression-ide__validate");
        comp1.getAsJsonArray("flow").add("tinyexpression-ide__evaluate");
        comp1.addProperty("note", "validate で構文エラーを検出してから evaluate で評価する");
        compositions.add(comp1);

        JsonObject comp2 = new JsonObject();
        comp2.addProperty("title", "本命評価器との比較");
        comp2.add("flow", new JsonArray());
        comp2.getAsJsonArray("flow").add("tinyexpression-ide__evaluate");
        comp2.getAsJsonArray("flow").add("tinyexpr__evaluate");
        comp2.addProperty("note", "IDE 簡易評価器と本命 tinyexpr の結果を比較する");
        compositions.add(comp2);

        spec.add("compositions", compositions);

        JsonArray dependsOn = new JsonArray();
        JsonObject dep1 = new JsonObject();
        dep1.addProperty("namespace", "tinyexpr");
        dep1.addProperty("capability", "tinyexpr__evaluate");
        dependsOn.add(dep1);
        JsonObject dep2 = new JsonObject();
        dep2.addProperty("namespace", "unlaxer");
        dep2.addProperty("capability", "unlaxer__parse");
        dependsOn.add(dep2);
        spec.add("depends_on", dependsOn);

        spec.addProperty("health", "/healthz");
        JsonArray docs = new JsonArray();
        docs.add(NAMESPACE + "://guide");
        spec.add("docs", docs);

        return GSON.toJson(spec);
    }

    private String buildGuideResource() {
        return """
                # TinyExpression IDE — 使い方ガイド

                ## 概要

                TinyExpression 式をブラウザ上で編集・評価・診断する Web IDE の MCP バックエンド。
                現状は MVP 簡易評価器（四則演算 + 変数置換）を提供する。

                ## tools

                ### evaluate

                式を評価する。変数は `$変数名` で参照し、`variables` マップで置換する。

                入力:
                ```json
                {"formula": "1 + $x * 2", "variables": {"x": "10"}}
                ```

                出力:
                ```json
                {"result": "21", "error": null, "formula": "1 + $x * 2", "substituted": "1 + 10 * 2"}
                ```

                **注意**: 現状は四則演算（+, -, *, /）と括弧のみ。`if`/`match`/`string`/`boolean` 等は
               評価できない。本命の TinyExpression 評価には `tinyexpr__evaluate` を使用すること。

                ### validate

                式の構文を検証する。括弧のバランスをチェックする。

                入力:
                ```json
                {"formula": "(1 + 2"}
                ```

                出力:
                ```json
                {
                  "valid": false,
                  "diagnostics": [{"range": {"line": 0, "character": 5}, "severity": "Error", "message": "Unmatched opening parenthesis (1 unclosed)"}],
                  "formula": "(1 + 2"
                }
                ```

                ## 変数構文

                - `$変数名` で変数を参照（例: `$x`, `$count`, `$value1`）
                - 変数名は `[A-Za-z][A-Za-z0-9_]*` パターン
                - `variables` マップの値は文字列として置換される

                ## 組み合わせ

                1. `tinyexpression-ide__validate` で構文チェック → `tinyexpression-ide__evaluate` で評価
                2. `tinyexpression-ide__evaluate`（簡易）と `tinyexpr__evaluate`（本命）を比較
                """;
    }

    private String buildSkillResource() {
        return """
                ---
                name: formula-eval-workflow
                description: 式に含まれる $variableName を自動検出し、変数マップを組み立てて evaluate に渡す手順
                volta:
                  version: 1
                  namespace: tinyexpression-ide
                  locality: repo
                  applies_when:
                    - goal:
                        eq: "evaluate tinyexpression formula"
                  requires:
                    tools:
                      - tinyexpression-ide__evaluate
                  min_role: MEMBER
                  export: allowed
                ---

                # 式評価ワークフロー

                ## 前提

                - `tinyexpression-ide__evaluate` が使える状態であること

                ## 手順

                1. 評価したい TinyExpression 式を用意する（例: `1 + $x * 2 + $y`）
                2. 式から `$変数名` を全て抽出する
                   - 正規表現: `\\$([A-Za-z][A-Za-z0-9_]*)`
                   - 例: `1 + $x * 2 + $y` → `["x", "y"]`
                3. 各変数の値を文字列で用意する（例: `{"x": "10", "y": "5"}`）
                4. `tinyexpression-ide__evaluate` を呼ぶ:
                   ```json
                   {"formula": "1 + $x * 2 + $y", "variables": {"x": "10", "y": "5"}}
                   ```
                5. 結果の `result` が評価値、`error` が null なら成功
                6. `error` が非 null なら式を修正して再評価
                7. 事前に `tinyexpression-ide__validate` で構文チェック可能

                ## 注意

                - 現状の evaluate は四則演算のみ（MVP 簡易版）
                - 本命の TinyExpression 評価（if/match/string 等）は `tinyexpr__evaluate` を使う
                """;
    }

    // --- utils ---

    private static String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static String readBody(HttpServletRequest req) throws IOException, RequestBodyTooLargeException {
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        try (BufferedReader reader = req.getReader()) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    sb.append('\n');
                    bytes++;
                }
                bytes += line.length();
                if (bytes > MAX_BODY_BYTES) {
                    throw new RequestBodyTooLargeException();
                }
                sb.append(line);
                first = false;
            }
        }
        return sb.toString();
    }

    private JsonObject makeError(String id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        try {
            response.add("id", GSON.fromJson(id, JsonElement.class));
        } catch (Exception e) {
            response.add("id", new JsonPrimitive("null"));
        }
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }
}
