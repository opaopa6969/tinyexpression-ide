package org.unlaxer.tinyexpression.ide;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import static org.junit.Assert.*;

/**
 * End-to-end tests for the MCP server: healthz, tools/list, tools/call, resources.
 * Starts a real Jetty server on a random port and tests via HTTP.
 */
public class McpEndpointTest {

    private static final Gson GSON = new Gson();
    private static final AtomicInteger PORT_SEQ = new AtomicInteger(19280);

    private Server server;
    private int port;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        port = PORT_SEQ.getAndIncrement();
        baseUrl = "http://127.0.0.1:" + port;

        WebSocketLspBridge.setServerFactory(StubLspServer::new);

        server = new Server(port);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder("eval", new EvalEndpoint()), "/api/eval");
        context.addServlet(new ServletHolder("healthz", new HealthEndpoint()), "/healthz");
        context.addServlet(new ServletHolder("mcp", new McpEndpoint()), "/mcp");

        JettyWebSocketServletContainerInitializer.configure(context,
                (servletContext, wsContainer) -> {
                    wsContainer.setMaxTextMessageSize(1024 * 1024);
                    wsContainer.addMapping("/lsp", (req, resp) -> new WebSocketLspBridge());
                });

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(false);
        resourceHandler.setWelcomeFiles(new String[]{"index.html"});
        java.io.File devDir = new java.io.File("src/main/resources/static");
        if (devDir.isDirectory()) {
            resourceHandler.setResourceBase(devDir.getAbsolutePath());
        }

        HandlerList handlers = new HandlerList();
        handlers.addHandler(resourceHandler);
        handlers.addHandler(context);
        server.setHandler(handlers);
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void testHealthz() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/healthz").openConnection();
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode());
        assertEquals("identity", conn.getHeaderField("Content-Encoding"));

        JsonObject body = readResponse(conn);
        assertTrue(body.get("ok").getAsBoolean());
        assertEquals("tinyexpression-ide", body.get("name").getAsString());
        assertEquals("0.1.0", body.get("version").getAsString());
    }

    @Test
    public void testMcpInitialize() throws Exception {
        JsonObject req = jsonRpc("initialize", 1);
        JsonObject resp = mcpPost(req);

        assertEquals("2.0", resp.get("jsonrpc").getAsString());
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
        assertEquals("tinyexpression-ide", result.getAsJsonObject("serverInfo").get("name").getAsString());
    }

    @Test
    public void testToolsList() throws Exception {
        JsonObject req = jsonRpc("tools/list", 2);
        JsonObject resp = mcpPost(req);

        JsonObject result = resp.getAsJsonObject("result");
        JsonArray tools = result.getAsJsonArray("tools");
        assertEquals(2, tools.size());

        boolean hasEval = false, hasValidate = false;
        for (JsonElement e : tools) {
            JsonObject t = e.getAsJsonObject();
            String name = t.get("name").getAsString();
            if ("evaluate".equals(name)) hasEval = true;
            if ("validate".equals(name)) hasValidate = true;
            assertTrue(t.has("annotations"));
            assertTrue(t.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
        }
        assertTrue("has evaluate", hasEval);
        assertTrue("has validate", hasValidate);
    }

    @Test
    public void testEvaluateSimple() throws Exception {
        JsonObject resp = callTool("evaluate", makeArgs("formula", "1 + 2 * 3"));
        JsonObject data = readToolContent(resp);

        assertEquals("7", data.get("result").getAsString());
        assertTrue(data.has("error"));
        assertTrue(data.get("error").isJsonNull());
        assertEquals("1 + 2 * 3", data.get("formula").getAsString());
    }

    @Test
    public void testEvaluateWithVariables() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("formula", "$x * 2 + $y");
        JsonObject vars = new JsonObject();
        vars.addProperty("x", "10");
        vars.addProperty("y", "5");
        args.add("variables", vars);

        JsonObject resp = callTool("evaluate", args);
        JsonObject data = readToolContent(resp);

        assertEquals("25", data.get("result").getAsString());
        assertTrue(data.has("error"));
        assertTrue(data.get("error").isJsonNull());
        assertEquals("10 * 2 + 5", data.get("substituted").getAsString());
    }

    @Test
    public void testEvaluateMissingFormula() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("formula", "");

        JsonObject resp = callTool("evaluate", args);
        JsonObject data = readToolContent(resp);

        assertNotNull(data.get("error"));
        assertFalse(data.get("error").isJsonNull());
    }

    @Test
    public void testEvaluateDivisionByZero() throws Exception {
        JsonObject resp = callTool("evaluate", makeArgs("formula", "1 / 0"));
        JsonObject data = readToolContent(resp);

        assertFalse(data.get("error").isJsonNull());
        assertTrue(data.get("error").getAsString().contains("Division by zero"));
    }

    @Test
    public void testValidateValid() throws Exception {
        JsonObject resp = callTool("validate", makeArgs("formula", "1 + 2 * 3"));
        JsonObject data = readToolContent(resp);

        assertTrue(data.get("valid").getAsBoolean());
        assertEquals(0, data.getAsJsonArray("diagnostics").size());
    }

    @Test
    public void testValidateUnbalancedOpen() throws Exception {
        JsonObject resp = callTool("validate", makeArgs("formula", "(1 + 2"));
        JsonObject data = readToolContent(resp);

        assertFalse(data.get("valid").getAsBoolean());
        JsonArray diags = data.getAsJsonArray("diagnostics");
        assertEquals(1, diags.size());
        assertEquals("Error", diags.get(0).getAsJsonObject().get("severity").getAsString());
        assertTrue(diags.get(0).getAsJsonObject().get("message").getAsString().contains("Unmatched opening parenthesis"));
    }

    @Test
    public void testValidateUnbalancedClose() throws Exception {
        JsonObject resp = callTool("validate", makeArgs("formula", "1 + 2)"));
        JsonObject data = readToolContent(resp);

        assertFalse(data.get("valid").getAsBoolean());
        JsonArray diags = data.getAsJsonArray("diagnostics");
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).getAsJsonObject().get("message").getAsString().contains("Unmatched closing parenthesis"));
    }

    @Test
    public void testResourcesList() throws Exception {
        JsonObject req = jsonRpc("resources/list", 3);
        JsonObject resp = mcpPost(req);

        JsonObject result = resp.getAsJsonObject("result");
        JsonArray resources = result.getAsJsonArray("resources");
        assertEquals(3, resources.size());

        boolean hasSpec = false, hasGuide = false, hasSkill = false;
        for (JsonElement e : resources) {
            String uri = e.getAsJsonObject().get("uri").getAsString();
            if ("tinyexpression-ide://spec".equals(uri)) hasSpec = true;
            if ("tinyexpression-ide://guide".equals(uri)) hasGuide = true;
            if ("skill://formula-eval-workflow".equals(uri)) hasSkill = true;
        }
        assertTrue("has spec", hasSpec);
        assertTrue("has guide", hasGuide);
        assertTrue("has skill", hasSkill);
    }

    @Test
    public void testReadSpecResource() throws Exception {
        JsonObject resp = readResource("tinyexpression-ide://spec");
        JsonObject result = resp.getAsJsonObject("result");
        JsonArray contents = result.getAsJsonArray("contents");
        assertEquals(1, contents.size());

        JsonObject c = contents.get(0).getAsJsonObject();
        assertEquals("application/json", c.get("mimeType").getAsString());

        JsonObject spec = GSON.fromJson(c.get("text").getAsString(), JsonObject.class);
        assertEquals("tinyexpression-ide", spec.get("namespace").getAsString());
        assertTrue(spec.has("capabilities"));
        assertTrue(spec.has("compositions"));
        assertTrue(spec.has("depends_on"));
    }

    @Test
    public void testReadGuideResource() throws Exception {
        JsonObject resp = readResource("tinyexpression-ide://guide");
        JsonObject result = resp.getAsJsonObject("result");
        String text = result.getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("# TinyExpression IDE"));
        assertTrue(text.contains("evaluate"));
    }

    @Test
    public void testReadSkillResource() throws Exception {
        JsonObject resp = readResource("skill://formula-eval-workflow");
        JsonObject result = resp.getAsJsonObject("result");
        String text = result.getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("name: formula-eval-workflow"));
        assertTrue(text.contains("volta:"));
        assertTrue(text.contains("tinyexpression-ide__evaluate"));
    }

    @Test
    public void testContentEncodingIdentity() throws Exception {
        JsonObject req = jsonRpc("tools/list", 1);
        HttpURLConnection conn = postMcp(req);
        assertEquals("identity", conn.getHeaderField("Content-Encoding"));
    }

    @Test
    public void testEvalApiStillWorks() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("formula", "2 + 3");
        body.add("variables", new JsonObject());
        body.addProperty("resultType", "number");

        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/eval").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, conn.getResponseCode());
        JsonObject resp = readResponse(conn);
        assertEquals("5", resp.get("result").getAsString());
    }

    // --- regressions for [glm-hunt] issues #2 / #3 / #4 ---

    /**
     * #2: Variable values containing $var tokens must be treated as literals
     * and not re-substituted in a later pass. With x="$y", y="5" the formula
     * "$x + $y" should become "$y + 5" (not "5 + 5"), and evaluating that
     * should fail because $y is left unresolved.
     */
    @Test
    public void testEvaluateNoDoubleSubstitutionApi() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("formula", "$x + $y");
        JsonObject vars = new JsonObject();
        vars.addProperty("x", "$y");
        vars.addProperty("y", "5");
        body.add("variables", vars);
        body.addProperty("resultType", "number");

        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/eval").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, conn.getResponseCode());
        JsonObject resp = readResponse(conn);
        // Value of $x ("$y") must NOT be re-substituted to "5"; $y is left as a literal.
        assertEquals("$y + 5", resp.get("substituted").getAsString());
        // The resulting formula should not evaluate cleanly.
        assertNotNull(resp.get("error"));
        assertFalse(resp.get("error").isJsonNull());
    }

    @Test
    public void testEvaluateNoDoubleSubstitutionMcp() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("formula", "$x + $y");
        JsonObject vars = new JsonObject();
        vars.addProperty("x", "$y");
        vars.addProperty("y", "5");
        args.add("variables", vars);

        JsonObject resp = callTool("evaluate", args);
        JsonObject data = readToolContent(resp);
        assertEquals("$y + 5", data.get("substituted").getAsString());
        assertNotNull(data.get("error"));
        assertFalse(data.get("error").isJsonNull());
    }

    /**
     * #2 (positive): a value containing a literal '$' that does not form a
     * known variable must be preserved verbatim (e.g. currency "$100").
     */
    @Test
    public void testEvaluateValueWithDollarLiteralPreserved() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("formula", "$x + 1");
        JsonObject vars = new JsonObject();
        vars.addProperty("x", "$100");
        args.add("variables", vars);

        JsonObject resp = callTool("evaluate", args);
        JsonObject data = readToolContent(resp);
        assertEquals("$100 + 1", data.get("substituted").getAsString());
    }

    /**
     * #3: initialize MUST return the generated session id via the
     * mcp-session-id response header (MCP Streamable HTTP).
     */
    @Test
    public void testInitializeReturnsMcpSessionIdHeader() throws Exception {
        JsonObject req = jsonRpc("initialize", 1);
        HttpURLConnection conn = postMcp(req);
        assertEquals(200, conn.getResponseCode());
        String sessionId = conn.getHeaderField("mcp-session-id");
        assertNotNull("mcp-session-id header must be present on initialize", sessionId);
        assertTrue("mcp-session-id must be a non-empty UUID-like value",
                sessionId.matches("[0-9a-fA-F-]{36}"));
    }

    /**
     * #3 (companion): the session id returned by initialize must be usable
     * to delete the session via DELETE /mcp.
     */
    @Test
    public void testDeleteSessionByMcpSessionIdHeader() throws Exception {
        JsonObject initReq = jsonRpc("initialize", 1);
        HttpURLConnection initConn = postMcp(initReq);
        String sessionId = initConn.getHeaderField("mcp-session-id");
        assertNotNull(sessionId);

        HttpURLConnection del = (HttpURLConnection) new URL(baseUrl + "/mcp").openConnection();
        del.setRequestMethod("DELETE");
        del.setRequestProperty("mcp-session-id", sessionId);
        assertEquals(204, del.getResponseCode());
    }

    /**
     * #4: a request with id: null (a valid diagnostic id per JSON-RPC 2.0
     * §4.2) MUST produce a response whose "id" member is present and null.
     * The default Gson (serializeNulls=false) strips it; we now serialize
     * envelopes with serializeNulls=true.
     */
    @Test
    public void testResponseIdNullIsPreserved() throws Exception {
        // Send the raw JSON bytes so that "id": null is actually transmitted.
        // (Gson with serializeNulls=false would strip it from the request too.)
        String body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"evaluate\",\"arguments\":{\"formula\":\"1 + 2\"}}}";

        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/mcp").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, conn.getResponseCode());
        // Read the raw body so we can assert the serialized form, not just the
        // parsed element (Gson would happily report JsonNull either way).
        String rawBody;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            rawBody = sb.toString();
        }
        assertTrue("raw response must contain \"id\":null — was: " + rawBody,
                rawBody.contains("\"id\":null"));
        JsonObject resp = GSON.fromJson(rawBody, JsonObject.class);
        assertTrue(resp.has("id"));
        assertTrue(resp.get("id").isJsonNull());
    }

    /**
     * #4 (companion): a notification (no id key at all) for
     * notifications/initialized must still return 202 with no body.
     */
    @Test
    public void testNotificationReturns202() throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("method", "notifications/initialized");
        // intentionally no "id"

        HttpURLConnection conn = postMcp(req);
        assertEquals(202, conn.getResponseCode());
        // No JSON body expected for a notification.
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            assertFalse(reader.ready());
        }
    }

    // --- helpers ---

    private JsonObject jsonRpc(String method, int id) {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("method", method);
        req.addProperty("id", id);
        return req;
    }

    private JsonObject jsonRpcWithParams(String method, int id, JsonObject params) {
        JsonObject req = jsonRpc(method, id);
        req.add("params", params);
        return req;
    }

    private HttpURLConnection postMcp(JsonObject body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/mcp").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private JsonObject mcpPost(JsonObject body) throws IOException {
        HttpURLConnection conn = postMcp(body);
        assertEquals(200, conn.getResponseCode());
        return readResponse(conn);
    }

    private JsonObject callTool(String name, JsonObject args) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", args);
        JsonObject req = jsonRpcWithParams("tools/call", 100, params);
        return mcpPost(req);
    }

    private JsonObject readResource(String uri) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        JsonObject req = jsonRpcWithParams("resources/read", 200, params);
        return mcpPost(req);
    }

    private JsonObject makeArgs(String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return args;
    }

    private JsonObject readToolContent(JsonObject response) {
        JsonObject result = response.getAsJsonObject("result");
        JsonArray content = result.getAsJsonArray("content");
        JsonObject textContent = content.get(0).getAsJsonObject();
        assertEquals("text", textContent.get("type").getAsString());
        return GSON.fromJson(textContent.get("text").getAsString(), JsonObject.class);
    }

    private JsonObject readResponse(HttpURLConnection conn) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return GSON.fromJson(sb.toString(), JsonObject.class);
        }
    }
}
