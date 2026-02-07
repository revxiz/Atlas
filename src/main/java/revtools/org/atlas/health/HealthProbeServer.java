/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.health;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import revtools.org.atlas.Atlas;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP server for Kubernetes health probes and Prometheus metrics.
 *
 * Endpoints:
 * - /healthz - Liveness probe (is the process alive?)
 * - /readyz  - Readiness probe (can it handle traffic?)
 * - /metrics - Prometheus metrics
 */
public class HealthProbeServer {

    private final int port;
    private final String bind;
    private final Atlas atlas;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private Undertow server;

    public HealthProbeServer(int port, String bind, Atlas atlas, Logger logger) {
        this.port = port;
        this.bind = bind;
        this.atlas = atlas;
        this.logger = logger;
    }

    public void start() {
        server = Undertow.builder()
            .addHttpListener(port, bind)
            .setHandler(this::handleRequest)
            .build();

        server.start();
        logger.info("Health probe server started on {}:{}", bind, port);
    }

    public void stop() {
        if (server != null) {
            server.stop();
            logger.info("Health probe server stopped");
        }
    }

    private void handleRequest(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();

        try {
            switch (path) {
                case "/healthz" -> handleLiveness(exchange);
                case "/readyz" -> handleReadiness(exchange);
                case "/metrics" -> handleMetrics(exchange);
                case "/status" -> handleStatus(exchange);
                default -> {
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                    sendJson(exchange, Map.of("error", "Not found"));
                }
            }
        } catch (Exception e) {
            logger.error("Health endpoint error: {}", e.getMessage());
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            sendJson(exchange, Map.of("error", e.getMessage()));
        }
    }

    /**
     * Liveness probe - is the process running and not deadlocked?
     */
    private void handleLiveness(HttpServerExchange exchange) {
        // Basic check - if we can respond, we're alive
        exchange.setStatusCode(StatusCodes.OK);
        sendJson(exchange, Map.of(
            "status", "ok",
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Readiness probe - can we accept and process traffic?
     */
    private void handleReadiness(HttpServerExchange exchange) {
        boolean ready = atlas.isInitialized();

        // Additional checks
        if (ready) {
            // Check if critical components are working
            var clusterManager = atlas.getClusterManager();
            if (clusterManager != null && clusterManager.isCircuitOpen()) {
                ready = false;
            }
        }

        if (ready) {
            exchange.setStatusCode(StatusCodes.OK);
            sendJson(exchange, Map.of(
                "status", "ready",
                "initialized", true,
                "timestamp", System.currentTimeMillis()
            ));
        } else {
            exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
            sendJson(exchange, Map.of(
                "status", "not ready",
                "initialized", atlas.isInitialized(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Prometheus metrics endpoint.
     */
    private void handleMetrics(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");

        StringBuilder metrics = new StringBuilder();
        String prefix = atlas.getConfig().getMetricsPrefix();

        // Basic metrics
        metrics.append("# HELP ").append(prefix).append("up Atlas is running\n");
        metrics.append("# TYPE ").append(prefix).append("up gauge\n");
        metrics.append(prefix).append("up ").append(atlas.isInitialized() ? 1 : 0).append("\n\n");

        // Uptime
        long uptime = System.currentTimeMillis() - atlas.getStartTime();
        metrics.append("# HELP ").append(prefix).append("uptime_seconds Atlas uptime in seconds\n");
        metrics.append("# TYPE ").append(prefix).append("uptime_seconds gauge\n");
        metrics.append(prefix).append("uptime_seconds ").append(uptime / 1000).append("\n\n");

        // K8s circuit breaker
        var clusterManager = atlas.getClusterManager();
        if (clusterManager != null) {
            metrics.append("# HELP ").append(prefix).append("k8s_circuit_open K8s circuit breaker state\n");
            metrics.append("# TYPE ").append(prefix).append("k8s_circuit_open gauge\n");
            metrics.append(prefix).append("k8s_circuit_open ").append(clusterManager.isCircuitOpen() ? 1 : 0).append("\n\n");

            metrics.append("# HELP ").append(prefix).append("k8s_failure_count K8s API failure count\n");
            metrics.append("# TYPE ").append(prefix).append("k8s_failure_count gauge\n");
            metrics.append(prefix).append("k8s_failure_count ").append(clusterManager.getFailureCount()).append("\n\n");
        }

        // Auto-scaler
        var autoScaler = atlas.getAutoScaler();
        if (autoScaler != null) {
            int decisions = autoScaler.getDecisionHistory().size();
            metrics.append("# HELP ").append(prefix).append("scaling_decisions_total Total scaling decisions\n");
            metrics.append("# TYPE ").append(prefix).append("scaling_decisions_total counter\n");
            metrics.append(prefix).append("scaling_decisions_total ").append(decisions).append("\n\n");
        }

        // Draining servers
        var playerDrainer = atlas.getPlayerDrainer();
        if (playerDrainer != null) {
            int draining = playerDrainer.getDrainStates().size();
            metrics.append("# HELP ").append(prefix).append("servers_draining Number of servers being drained\n");
            metrics.append("# TYPE ").append(prefix).append("servers_draining gauge\n");
            metrics.append(prefix).append("servers_draining ").append(draining).append("\n\n");
        }

        // Redis connection
        var stateStore = atlas.getStateStore();
        if (stateStore != null) {
            metrics.append("# HELP ").append(prefix).append("redis_connected Redis connection state\n");
            metrics.append("# TYPE ").append(prefix).append("redis_connected gauge\n");
            metrics.append(prefix).append("redis_connected ").append(stateStore.isConnected() ? 1 : 0).append("\n\n");
        }

        exchange.getResponseSender().send(metrics.toString());
    }

    /**
     * Detailed status endpoint for debugging.
     */
    private void handleStatus(HttpServerExchange exchange) {
        Map<String, Object> status = new HashMap<>();

        status.put("version", "1.0");
        status.put("initialized", atlas.isInitialized());
        status.put("hostingMode", atlas.getHostingMode().name());
        status.put("uptimeMs", System.currentTimeMillis() - atlas.getStartTime());

        // Components
        Map<String, Object> components = new HashMap<>();
        components.put("kubernetes", atlas.getClusterManager() != null);
        components.put("autoScaler", atlas.getAutoScaler() != null && atlas.getAutoScaler().isRunning());
        components.put("playerDrainer", atlas.getPlayerDrainer() != null && atlas.getPlayerDrainer().isRunning());
        components.put("redis", atlas.getStateStore() != null && atlas.getStateStore().isConnected());
        components.put("velotimBridge", atlas.getVelotimBridge() != null && atlas.getVelotimBridge().isRunning());
        status.put("components", components);

        // K8s state
        if (atlas.getClusterManager() != null) {
            Map<String, Object> k8s = new HashMap<>();
            k8s.put("circuitOpen", atlas.getClusterManager().isCircuitOpen());
            k8s.put("failureCount", atlas.getClusterManager().getFailureCount());
            status.put("kubernetes", k8s);
        }

        // Scaling
        if (atlas.getAutoScaler() != null) {
            Map<String, Object> scaling = new HashMap<>();
            scaling.put("running", atlas.getAutoScaler().isRunning());
            scaling.put("recentDecisions", atlas.getAutoScaler().getDecisionHistory().size());
            status.put("scaling", scaling);
        }

        // Draining
        if (atlas.getPlayerDrainer() != null) {
            Map<String, Object> draining = new HashMap<>();
            draining.put("running", atlas.getPlayerDrainer().isRunning());
            draining.put("activedrains", atlas.getPlayerDrainer().getDrainStates().size());
            status.put("draining", draining);
        }

        sendJson(exchange, status);
    }

    private void sendJson(HttpServerExchange exchange, Object data) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(gson.toJson(data));
    }
}
