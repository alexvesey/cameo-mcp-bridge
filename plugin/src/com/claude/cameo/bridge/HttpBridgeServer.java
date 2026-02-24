package com.claude.cameo.bridge;

import com.claude.cameo.bridge.handlers.ContainmentTreeHandler;
import com.claude.cameo.bridge.handlers.DiagramHandler;
import com.claude.cameo.bridge.handlers.ElementMutationHandler;
import com.claude.cameo.bridge.handlers.ElementQueryHandler;
import com.claude.cameo.bridge.handlers.MacroHandler;
import com.claude.cameo.bridge.handlers.ProjectHandler;
import com.claude.cameo.bridge.handlers.RelationshipHandler;
import com.claude.cameo.bridge.handlers.SpecificationHandler;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpBridgeServer {

    private static final Logger LOG = Logger.getLogger(HttpBridgeServer.class.getName());
    private final HttpServer server;

    public HttpBridgeServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        registerHandlers();
    }

    private void registerHandlers() {
        server.createContext("/api/v1/status", this::handleStatus);
        server.createContext("/api/v1/project", new ProjectHandler());
        server.createContext("/api/v1/containment-tree", new ContainmentTreeHandler());

        // Route /elements by HTTP method and sub-path
        ElementQueryHandler queryHandler = new ElementQueryHandler();
        ElementMutationHandler mutationHandler = new ElementMutationHandler();
        SpecificationHandler specificationHandler = new SpecificationHandler();
        server.createContext("/api/v1/elements", exchange -> {
            String path = exchange.getRequestURI().getPath();
            // Route /specification sub-paths to SpecificationHandler (GET and PUT)
            if (path.contains("/specification")) {
                specificationHandler.handle(exchange);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                queryHandler.handle(exchange);
            } else {
                mutationHandler.handle(exchange);
            }
        });

        server.createContext("/api/v1/relationships", new RelationshipHandler());
        server.createContext("/api/v1/diagrams", new DiagramHandler());
        server.createContext("/api/v1/macros", new MacroHandler());
        server.createContext("/api/v1/session/reset", this::handleSessionReset);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(2);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Only GET is supported");
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");
        response.addProperty("plugin", "CameoMCPBridge");
        response.addProperty("version", "1.0.0");
        response.addProperty("port", server.getAddress().getPort());
        sendJson(exchange, 200, response);
    }

    /**
     * POST /api/v1/session/reset - Force-close any stuck SessionManager session.
     *
     * When a macro crashes mid-session, all subsequent API calls that create
     * sessions will fail with "Session is already created". This endpoint
     * cancels (or closes) the dangling session so work can continue without
     * restarting Cameo.
     */
    private void handleSessionReset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Only POST is supported");
            return;
        }

        Project project = Application.getInstance().getProject();
        if (project == null) {
            sendError(exchange, 400, "NO_PROJECT", "No project is open in Cameo");
            return;
        }

        SessionManager sm = SessionManager.getInstance();
        JsonObject response = new JsonObject();

        // Check whether a session is currently active
        if (!sm.isSessionCreated(project)) {
            response.addProperty("reset", false);
            response.addProperty("message", "No active session");
            sendJson(exchange, 200, response);
            return;
        }

        // Try cancel first (rolls back partial changes), fall back to close
        try {
            sm.cancelSession(project);
            response.addProperty("reset", true);
        } catch (Exception cancelEx) {
            LOG.log(Level.WARNING, "cancelSession failed, trying closeSession", cancelEx);
            try {
                sm.closeSession(project);
                response.addProperty("reset", true);
            } catch (Exception closeEx) {
                LOG.log(Level.SEVERE, "closeSession also failed", closeEx);
                sendError(exchange, 500, "SESSION_RESET_FAILED",
                        "cancelSession failed: " + cancelEx.getMessage()
                                + "; closeSession failed: " + closeEx.getMessage());
                return;
            }
        }

        sendJson(exchange, 200, response);
    }

    public static void sendJson(HttpExchange exchange, int status, JsonObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("error", true);
        error.addProperty("code", code);
        error.addProperty("message", message);
        sendJson(exchange, status, error);
    }
}
