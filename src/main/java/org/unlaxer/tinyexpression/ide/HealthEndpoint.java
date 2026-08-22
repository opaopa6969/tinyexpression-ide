package org.unlaxer.tinyexpression.ide;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Health check endpoint: GET /healthz
 * Returns 200 with {ok: true, name, version} JSON.
 */
public class HealthEndpoint extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(HealthEndpoint.class.getName());
    private static final Gson GSON = new Gson();

    static final String NAME = "tinyexpression-ide";
    static final String VERSION = "0.1.0";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Content-Encoding", "identity");

        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.addProperty("name", NAME);
        body.addProperty("version", VERSION);

        resp.setStatus(200);
        try (PrintWriter out = resp.getWriter()) {
            out.print(GSON.toJson(body));
            out.flush();
        }
    }
}
