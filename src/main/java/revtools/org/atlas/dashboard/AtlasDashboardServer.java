/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import revtools.org.atlas.Atlas;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atlas Dashboard Server - Global Nerve Center UI.
 * 
 * Four-level dashboard:
 * 1. Global Nerve Center - Threat level, incidents, throughput
 * 2. Infrastructure Map - K8s pods, scaling controls, canary status
 * 3. Intelligence Suite - Predictive analytics, slow queries, geo heatmap
 * 4. Command & Control - Player search, dangerous actions, audit log
 */
public class AtlasDashboardServer {

    private final int port;
    private final String bind;
    private final String authToken;
    private final List<String> allowedIps;
    private final Atlas atlas;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private Undertow server;
    private final AtlasDashboardAPI api;

    // Session management
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AtlasDashboardServer(int port, String bind, String authToken, 
                                 List<String> allowedIps, Atlas atlas, Logger logger) {
        this.port = port;
        this.bind = bind;
        this.authToken = authToken != null && !authToken.isEmpty() ? authToken : generateToken();
        this.allowedIps = allowedIps;
        this.atlas = atlas;
        this.logger = logger;
        this.api = new AtlasDashboardAPI(atlas, this, logger);
    }

    private String generateToken() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        logger.info("Generated dashboard auth token: {}", token);
        return token;
    }

    public void start() {
        server = Undertow.builder()
            .addHttpListener(port, bind)
            .setHandler(this::handleRequest)
            .build();

        server.start();
        logger.info("Atlas dashboard started on {}:{}", bind, port);
    }

    public void stop() {
        if (server != null) {
            server.stop();
            logger.info("Atlas dashboard stopped");
        }
    }

    private void handleRequest(HttpServerExchange exchange) {
        // CORS headers
        exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Methods"), "GET, POST, OPTIONS");
        exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Headers"), "Content-Type, X-Atlas-Auth, X-Session-Token");

        if (exchange.getRequestMethod().equalToString("OPTIONS")) {
            exchange.setStatusCode(StatusCodes.OK);
            exchange.endExchange();
            return;
        }

        String path = exchange.getRequestPath();

        try {
            // IP check for non-localhost
            if (!isAllowedIp(exchange)) {
                exchange.setStatusCode(StatusCodes.FORBIDDEN);
                sendJson(exchange, Map.of("error", "IP not allowed"));
                return;
            }

            // Route requests
            if (path.equals("/") || path.equals("/dashboard") || path.equals("/dashboard/")) {
                serveHtml(exchange);
            } else if (path.startsWith("/api/")) {
                handleApiRequest(exchange, path.substring(5));
            } else if (path.startsWith("/assets/")) {
                serveAsset(exchange, path);
            } else {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                sendJson(exchange, Map.of("error", "Not found"));
            }

        } catch (Exception e) {
            logger.error("Dashboard error: {}", e.getMessage());
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            sendJson(exchange, Map.of("error", e.getMessage()));
        }
    }

    private boolean isAllowedIp(HttpServerExchange exchange) {
        String clientIp = exchange.getSourceAddress().getAddress().getHostAddress();
        
        // Always allow localhost
        if ("127.0.0.1".equals(clientIp) || "::1".equals(clientIp) || "0:0:0:0:0:0:0:1".equals(clientIp)) {
            return true;
        }

        // If no allowlist, allow all (user's responsibility)
        if (allowedIps == null || allowedIps.isEmpty()) {
            return true;
        }

        // Check against allowlist
        for (String allowed : allowedIps) {
            if (allowed.contains("/")) {
                // CIDR notation
                if (isInCidr(clientIp, allowed)) return true;
            } else {
                if (clientIp.equals(allowed)) return true;
            }
        }

        logger.warn("Blocked dashboard access from IP: {}", clientIp);
        return false;
    }

    private boolean isInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String network = parts[0];
            int prefix = Integer.parseInt(parts[1]);
            
            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            byte[] networkBytes = InetAddress.getByName(network).getAddress();
            
            int mask = 0xFFFFFFFF << (32 - prefix);
            int ipInt = ((ipBytes[0] & 0xFF) << 24) | ((ipBytes[1] & 0xFF) << 16) | 
                        ((ipBytes[2] & 0xFF) << 8) | (ipBytes[3] & 0xFF);
            int networkInt = ((networkBytes[0] & 0xFF) << 24) | ((networkBytes[1] & 0xFF) << 16) | 
                             ((networkBytes[2] & 0xFF) << 8) | (networkBytes[3] & 0xFF);
            
            return (ipInt & mask) == (networkInt & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private void handleApiRequest(HttpServerExchange exchange, String endpoint) {
        // Auth check for non-login endpoints
        if (!endpoint.equals("auth/login") && !endpoint.equals("auth/check")) {
            if (!isAuthenticated(exchange)) {
                exchange.setStatusCode(StatusCodes.UNAUTHORIZED);
                sendJson(exchange, Map.of("error", "Unauthorized"));
                return;
            }
        }

        api.handleRequest(exchange, endpoint);
    }

    private boolean isAuthenticated(HttpServerExchange exchange) {
        String sessionToken = exchange.getRequestHeaders().getFirst("X-Session-Token");
        if (sessionToken == null) return false;
        
        Session session = sessions.get(sessionToken);
        if (session == null) return false;
        
        // Check expiry (24 hours)
        if (System.currentTimeMillis() - session.created > 86400000) {
            sessions.remove(sessionToken);
            return false;
        }
        
        return true;
    }

    public String createSession() {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(token, System.currentTimeMillis()));
        return token;
    }

    public boolean validateAuthToken(String token) {
        return authToken.equals(token);
    }

    public void invalidateSession(String token) {
        sessions.remove(token);
    }

    private void serveHtml(HttpServerExchange exchange) {
        try (InputStream is = getClass().getResourceAsStream("/dashboard/index.html")) {
            if (is != null) {
                String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
                exchange.getResponseSender().send(html);
            } else {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                sendJson(exchange, Map.of("error", "Dashboard not found"));
            }
        } catch (Exception e) {
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            sendJson(exchange, Map.of("error", e.getMessage()));
        }
    }

    private void serveAsset(HttpServerExchange exchange, String path) {
        // Security: prevent directory traversal
        if (path.contains("..")) {
            exchange.setStatusCode(StatusCodes.FORBIDDEN);
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/dashboard" + path)) {
            if (is != null) {
                byte[] content = is.readAllBytes();
                String contentType = getContentType(path);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
                exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(content));
            } else {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
            }
        } catch (Exception e) {
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
        }
    }

    private String getContentType(String path) {
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    void sendJson(HttpServerExchange exchange, Object data) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(gson.toJson(data));
    }

    record Session(String token, long created) {}
}
