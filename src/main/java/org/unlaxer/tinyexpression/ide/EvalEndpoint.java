package org.unlaxer.tinyexpression.ide;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * REST endpoint: POST /api/eval
 * <p>
 * Request body (JSON):
 * <pre>
 * {
 *   "formula": "1 + $x * 2",
 *   "variables": { "x": "10" },
 *   "resultType": "number"   // optional: number | string | boolean
 * }
 * </pre>
 * <p>
 * Response (JSON):
 * <pre>
 * {
 *   "result": "21",
 *   "error": null,
 *   "formula": "1 + $x * 2",
 *   "substituted": "1 + 10 * 2"
 * }
 * </pre>
 * <p>
 * Evaluation is sandboxed with a 5-second timeout to guard against
 * runaway expressions.
 */
public class EvalEndpoint extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(EvalEndpoint.class.getName());
    private static final Gson GSON = new Gson();
    private static final long EVAL_TIMEOUT_SECONDS = 5;
    private static final int EVAL_POOL_SIZE = 8;
    private static final int EVAL_QUEUE_CAPACITY = 64;

    /** Maximum accepted request body size in bytes. Larger bodies are rejected
     *  with 413 before being fully buffered, to prevent unbounded memory use. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    /** Comma-separated allowed CORS origins via env TINYEXP_ALLOWED_ORIGINS.
     *  Empty/unset → "*" (backward-compatible; intended for local same-origin IDE). */
    private static final java.util.Set<String> ALLOWED_ORIGINS = parseAllowedOrigins(
        System.getenv("TINYEXP_ALLOWED_ORIGINS"));

    private static java.util.Set<String> parseAllowedOrigins(String raw) {
        if (raw == null || raw.isBlank()) return null; // null sentinel → emit "*"
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String o : raw.split(",")) {
            String t = o.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set.isEmpty() ? null : set;
    }

    private static String corsHeaderFor(String origin) {
        if (ALLOWED_ORIGINS == null) return "*";
        if (origin != null && ALLOWED_ORIGINS.contains(origin)) return origin;
        return "null"; // RFC 6454: deny cross-origin by returning opaque origin
    }

    private final ExecutorService evalPool = new ThreadPoolExecutor(
        EVAL_POOL_SIZE, EVAL_POOL_SIZE,
        60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(EVAL_QUEUE_CAPACITY),
        r -> { Thread t = new Thread(r, "eval-worker"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.CallerRunsPolicy());

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", corsHeaderFor(req.getHeader("Origin")));

        JsonObject result = new JsonObject();

        String body;
        try {
            body = readBody(req);
        } catch (RequestBodyTooLargeException e) {
            result.addProperty("error", "Request body too large (max " + MAX_BODY_BYTES + " bytes)");
            writeJson(resp, 413, result);
            return;
        }

        try {
            JsonObject request = GSON.fromJson(body, JsonObject.class);
            String formula = getStringOrNull(request, "formula");
            if (formula == null || formula.isBlank()) {
                result.addProperty("error", "Missing 'formula' field");
                writeJson(resp, 400, result);
                return;
            }

            String resultType = getStringOrNull(request, "resultType");
            if (resultType == null) resultType = "number";

            // Extract variables
            Map<String, String> variables = new LinkedHashMap<>();
            if (request.has("variables") && request.get("variables").isJsonObject()) {
                JsonObject vars = request.getAsJsonObject("variables");
                for (Map.Entry<String, JsonElement> entry : vars.entrySet()) {
                    variables.put(entry.getKey(), entry.getValue().getAsString());
                }
            }

            // Evaluate with timeout
            final String fFormula = formula;
            final String fResultType = resultType;
            final Map<String, String> fVariables = variables;

            Future<JsonObject> future = evalPool.submit(() -> evaluate(fFormula, fVariables, fResultType));

            try {
                JsonObject evalResult = future.get(EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                writeJson(resp, 200, evalResult);
                return;
            } catch (TimeoutException e) {
                future.cancel(true);
                result.addProperty("error", "Evaluation timed out after " + EVAL_TIMEOUT_SECONDS + " seconds");
                result.addProperty("result", (String) null);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                result.addProperty("error", "Evaluation error: " + cause.getMessage());
                result.addProperty("result", (String) null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.addProperty("error", "Evaluation interrupted");
                result.addProperty("result", (String) null);
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Eval request failed", e);
            result.addProperty("error", "Invalid request: " + e.getMessage());
        }

        writeJson(resp, 200, result);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", corsHeaderFor(req.getHeader("Origin")));
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setStatus(204);
    }

    /**
     * Evaluate a TinyExpression formula.
     * <p>
     * This is a simplified evaluator for the MVP. It parses the formula,
     * substitutes variables, and returns the result.
     * <p>
     * TODO: Replace with full AstEvaluatorCalculator integration when
     * tinyexpression is available as a dependency.
     */
    private JsonObject evaluate(String formula, Map<String, String> variables, String resultType) {
        JsonObject result = new JsonObject();

        // Single-pass variable substitution: each $varName token is replaced
        // exactly once with its literal value, so values containing $-tokens
        // are not re-interpreted in a later pass (no double substitution).
        String substituted = VariableSubstituter.substitute(formula, variables);

        try {
            // Attempt simple arithmetic evaluation for MVP
            // This covers basic expressions; full evaluation requires tinyexpression lib
            Object evalResult = SimpleExpressionEvaluator.evaluate(substituted);

            result.addProperty("result", String.valueOf(evalResult));
            result.addProperty("error", (String) null);
            result.addProperty("formula", formula);
            result.addProperty("substituted", substituted);

        } catch (Exception e) {
            result.addProperty("result", (String) null);
            result.addProperty("error", e.getMessage());
            result.addProperty("formula", formula);
            result.addProperty("substituted", substituted);
        }

        return result;
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static void writeJson(HttpServletResponse resp, int status, JsonObject json) throws IOException {
        resp.setStatus(status);
        try (PrintWriter out = resp.getWriter()) {
            out.print(GSON.toJson(json));
            out.flush();
        }
    }

    /** Read the request body preserving newlines (readLine() strips line
     *  endings). Rejects bodies larger than {@link #MAX_BODY_BYTES} before
     *  buffering them fully, throwing {@link RequestBodyTooLargeException}
     *  which the caller maps to a 413 response. */
    private static String readBody(HttpServletRequest req) throws IOException, RequestBodyTooLargeException {
        StringBuilder body = new StringBuilder();
        int bytes = 0;
        try (BufferedReader reader = req.getReader()) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    body.append('\n');
                    bytes++;
                }
                bytes += line.length();
                if (bytes > MAX_BODY_BYTES) {
                    throw new RequestBodyTooLargeException();
                }
                body.append(line);
                first = false;
            }
        }
        return body.toString();
    }
}
