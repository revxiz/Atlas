/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import revtools.org.atlas.Atlas;
import revtools.org.atlas.drain.PlayerDrainer;
import revtools.org.atlas.kubernetes.ClusterManager;
import revtools.org.atlas.scaling.AutoScaler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Atlas Dashboard API - handles all REST endpoints.
 */
public class AtlasDashboardAPI {

    private final Atlas atlas;
    private final AtlasDashboardServer server;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().create();

    public AtlasDashboardAPI(Atlas atlas, AtlasDashboardServer server, Logger logger) {
        this.atlas = atlas;
        this.server = server;
        this.logger = logger;
    }

    public void handleRequest(HttpServerExchange exchange, String endpoint) {
        String method = exchange.getRequestMethod().toString();

        try {
            switch (endpoint) {
                // Auth
                case "auth/login" -> handleLogin(exchange);
                case "auth/check" -> handleAuthCheck(exchange);
                case "auth/logout" -> handleLogout(exchange);

                // Level 1: Global Nerve Center
                case "status" -> handleStatus(exchange);
                case "threat" -> handleThreat(exchange);
                case "incidents" -> handleIncidents(exchange);
                case "throughput" -> handleThroughput(exchange);

                // Level 2: Infrastructure
                case "nodes" -> handleNodes(exchange);
                case "scaling" -> handleScaling(exchange, method);
                case "scaling/history" -> handleScalingHistory(exchange);
                case "canary" -> handleCanary(exchange, method);
                case "drain" -> handleDrain(exchange, method);

                // Level 3: Intelligence
                case "predictions" -> handlePredictions(exchange);
                case "slowqueries" -> handleSlowQueries(exchange);
                case "reputation/geo" -> handleReputationGeo(exchange);

                // Level 4: Command & Control
                case "search" -> handleSearch(exchange);
                case "dangerous/token" -> handleDangerousToken(exchange);
                case "dangerous/execute" -> handleDangerousExecute(exchange);
                case "audit" -> handleAudit(exchange);

                default -> {
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                    sendJson(exchange, Map.of("error", "Unknown endpoint: " + endpoint));
                }
            }
        } catch (Exception e) {
            logger.error("API error on {}: {}", endpoint, e.getMessage());
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            sendJson(exchange, Map.of("error", e.getMessage()));
        }
    }

    // Auth endpoints
    private void handleLogin(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> request = gson.fromJson(body, Map.class);
                String token = request.get("token");

                if (server.validateAuthToken(token)) {
                    String sessionToken = server.createSession();
                    sendJson(exchange, Map.of(
                        "success", true,
                        "sessionToken", sessionToken
                    ));
                } else {
                    exchange.setStatusCode(StatusCodes.UNAUTHORIZED);
                    sendJson(exchange, Map.of("success", false, "error", "Invalid token"));
                }
            } catch (Exception e) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        });
    }

    private void handleAuthCheck(HttpServerExchange exchange) {
        String sessionToken = exchange.getRequestHeaders().getFirst("X-Session-Token");
        sendJson(exchange, Map.of("authenticated", sessionToken != null));
    }

    private void handleLogout(HttpServerExchange exchange) {
        String sessionToken = exchange.getRequestHeaders().getFirst("X-Session-Token");
        if (sessionToken != null) {
            server.invalidateSession(sessionToken);
        }
        sendJson(exchange, Map.of("success", true));
    }

    // Level 1: Global Nerve Center
    private void handleStatus(HttpServerExchange exchange) {
        Map<String, Object> status = new HashMap<>();

        status.put("initialized", atlas.isInitialized());
        status.put("hostingMode", atlas.getHostingMode().name());
        status.put("uptimeMs", System.currentTimeMillis() - atlas.getStartTime());

        // Components status
        Map<String, Boolean> components = new HashMap<>();
        components.put("kubernetes", atlas.getClusterManager() != null);
        components.put("autoScaler", atlas.getAutoScaler() != null);
        components.put("playerDrainer", atlas.getPlayerDrainer() != null);
        components.put("redis", atlas.getStateStore() != null && atlas.getStateStore().isConnected());
        components.put("velotimBridge", atlas.getVelotimBridge() != null);
        status.put("components", components);

        sendJson(exchange, status);
    }

    private void handleThreat(HttpServerExchange exchange) {
        Map<String, Object> threat = new HashMap<>();

        // Get threat level from Velotim if available
        String level = "GREEN";
        double zScore = 0.0;

        if (atlas.getVelotimBridge() != null) {
            level = atlas.getVelotimBridge().getThreatLevel();
        }

        threat.put("level", level);
        threat.put("zScore", zScore);
        threat.put("timestamp", System.currentTimeMillis());

        sendJson(exchange, threat);
    }

    private void handleIncidents(HttpServerExchange exchange) {
        List<Map<String, Object>> incidents = new ArrayList<>();

        // Get from Velotim bridge
        if (atlas.getVelotimBridge() != null) {
            for (Map<String, String> incident : atlas.getVelotimBridge().getActiveIncidents()) {
                Map<String, Object> inc = new HashMap<>(incident);
                incidents.add(inc);
            }
        }

        // Add scaling incidents
        if (atlas.getAutoScaler() != null) {
            for (AutoScaler.ScalingDecision decision : atlas.getAutoScaler().getDecisionHistory()) {
                if (System.currentTimeMillis() - decision.timestamp() < 300000) { // Last 5 mins
                    incidents.add(Map.of(
                        "type", "SCALING",
                        "action", decision.action().name(),
                        "target", decision.target(),
                        "timestamp", decision.timestamp(),
                        "details", String.format("%d -> %d replicas",
                            decision.previousReplicas(), decision.newReplicas())
                    ));
                }
            }
        }

        // Sort by timestamp desc
        incidents.sort((a, b) -> Long.compare(
            (Long) b.getOrDefault("timestamp", 0L),
            (Long) a.getOrDefault("timestamp", 0L)
        ));

        sendJson(exchange, incidents);
    }

    private void handleThroughput(HttpServerExchange exchange) {
        Map<String, Object> throughput = new HashMap<>();

        if (atlas.getVelotimBridge() != null) {
            throughput = atlas.getVelotimBridge().getThroughputMetrics();
        } else {
            throughput.put("pps", 0);
            throughput.put("cps", 0);
            throughput.put("bandwidth", 0);
        }

        throughput.put("timestamp", System.currentTimeMillis());
        sendJson(exchange, throughput);
    }

    // Level 2: Infrastructure
    private void handleNodes(HttpServerExchange exchange) {
        List<Map<String, Object>> nodes = new ArrayList<>();

        ClusterManager cm = atlas.getClusterManager();
        if (cm != null) {
            for (ClusterManager.PodInfo pod : cm.getMinecraftPods()) {
                Map<String, Object> node = new HashMap<>();
                node.put("name", pod.name());
                node.put("phase", pod.phase());
                node.put("ready", pod.ready());
                node.put("ip", pod.ip());
                node.put("node", pod.node());

                // Check if draining
                PlayerDrainer drainer = atlas.getPlayerDrainer();
                if (drainer != null) {
                    node.put("draining", drainer.isDraining(pod.name()));
                    PlayerDrainer.DrainState state = drainer.getDrainStates().get(pod.name());
                    if (state != null) {
                        node.put("drainProgress", Map.of(
                            "currentPlayers", state.currentPlayers(),
                            "initialPlayers", state.initialPlayers(),
                            "startTime", state.startTime()
                        ));
                    }
                }

                // Get metrics if available
                if (atlas.getVelotimBridge() != null) {
                    var metrics = atlas.getVelotimBridge().getServerMetrics().get(pod.name());
                    if (metrics != null) {
                        node.put("players", metrics.players());
                        node.put("tps", metrics.tps());
                        node.put("mspt", metrics.mspt());
                    }
                }

                nodes.add(node);
            }
        }

        sendJson(exchange, nodes);
    }

    private void handleScaling(HttpServerExchange exchange, String method) {
        if ("GET".equals(method)) {
            Map<String, Object> scaling = new HashMap<>();

            AutoScaler scaler = atlas.getAutoScaler();
            if (scaler != null) {
                scaling.put("running", scaler.isRunning());
                scaling.put("rules", atlas.getConfig().getScalingRules().size());
                scaling.put("minReplicas", atlas.getConfig().getMinReplicas());
                scaling.put("maxReplicas", atlas.getConfig().getMaxReplicas());
                scaling.put("cooldown", Map.of(
                    "scaleUp", atlas.getConfig().getScaleUpCooldown(),
                    "scaleDown", atlas.getConfig().getScaleDownCooldown()
                ));
            } else {
                scaling.put("running", false);
            }

            // Get current replica counts
            ClusterManager cm = atlas.getClusterManager();
            if (cm != null) {
                List<Map<String, Object>> statefulSets = new ArrayList<>();
                for (ClusterManager.StatefulSetInfo sts : cm.listStatefulSets()) {
                    statefulSets.add(Map.of(
                        "name", sts.name(),
                        "desired", sts.desiredReplicas(),
                        "ready", sts.readyReplicas(),
                        "current", sts.currentReplicas()
                    ));
                }
                scaling.put("statefulSets", statefulSets);
            }

            sendJson(exchange, scaling);

        } else if ("POST".equals(method)) {
            // Force scale
            exchange.getRequestReceiver().receiveFullString((ex, body) -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> request = gson.fromJson(body, Map.class);
                    String target = (String) request.get("target");
                    int replicas = ((Number) request.get("replicas")).intValue();

                    AutoScaler scaler = atlas.getAutoScaler();
                    if (scaler == null) {
                        sendJson(exchange, Map.of("success", false, "error", "Auto-scaler not available"));
                        return;
                    }

                    boolean success = scaler.forceScale(target, replicas);

                    // Audit log
                    if (atlas.getStateStore() != null) {
                        atlas.getStateStore().recordAuditEvent(
                            "MANUAL_SCALE",
                            "dashboard",
                            target + " -> " + replicas + " replicas"
                        );
                    }

                    sendJson(exchange, Map.of("success", success));
                } catch (Exception e) {
                    sendJson(exchange, Map.of("success", false, "error", e.getMessage()));
                }
            });
        }
    }

    private void handleScalingHistory(HttpServerExchange exchange) {
        List<Map<String, Object>> history = new ArrayList<>();

        AutoScaler scaler = atlas.getAutoScaler();
        if (scaler != null) {
            for (AutoScaler.ScalingDecision decision : scaler.getDecisionHistory()) {
                history.add(Map.of(
                    "timestamp", decision.timestamp(),
                    "target", decision.target(),
                    "action", decision.action().name(),
                    "ruleName", decision.ruleName(),
                    "ruleType", decision.ruleType(),
                    "metricValue", decision.metricValue(),
                    "previousReplicas", decision.previousReplicas(),
                    "newReplicas", decision.newReplicas(),
                    "success", decision.success()
                ));
            }
        }

        sendJson(exchange, history);
    }

    private void handleCanary(HttpServerExchange exchange, String method) {
        if ("GET".equals(method)) {
            Map<String, Object> canary = new HashMap<>();
            canary.put("enabled", atlas.getConfig().isCanaryEnabled());
            canary.put("defaultPercentage", atlas.getConfig().getCanaryDefaultPercentage());
            canary.put("autoRollback", atlas.getConfig().isCanaryAutoRollback());
            canary.put("activeCanaries", List.of()); // Would be populated from state
            sendJson(exchange, canary);
        } else if ("POST".equals(method)) {
            // Create/update canary
            sendJson(exchange, Map.of("success", false, "error", "Not implemented"));
        }
    }

    private void handleDrain(HttpServerExchange exchange, String method) {
        if ("GET".equals(method)) {
            Map<String, Object> drainStatus = new HashMap<>();

            PlayerDrainer drainer = atlas.getPlayerDrainer();
            if (drainer != null) {
                drainStatus.put("running", drainer.isRunning());
                drainStatus.put("activedrains", drainer.getDrainStates());
            }

            sendJson(exchange, drainStatus);

        } else if ("POST".equals(method)) {
            exchange.getRequestReceiver().receiveFullString((ex, body) -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> request = gson.fromJson(body, Map.class);
                    String action = (String) request.get("action");
                    String serverId = (String) request.get("serverId");

                    PlayerDrainer drainer = atlas.getPlayerDrainer();
                    if (drainer == null) {
                        sendJson(exchange, Map.of("success", false, "error", "Drainer not available"));
                        return;
                    }

                    boolean success = switch (action) {
                        case "start" -> drainer.startDrain(serverId);
                        case "cancel" -> drainer.cancelDrain(serverId);
                        case "drainAll" -> { drainer.drainAll(); yield true; }
                        default -> false;
                    };

                    if (atlas.getStateStore() != null) {
                        atlas.getStateStore().recordAuditEvent(
                            "DRAIN_" + action.toUpperCase(),
                            "dashboard",
                            serverId != null ? serverId : "all"
                        );
                    }

                    sendJson(exchange, Map.of("success", success));
                } catch (Exception e) {
                    sendJson(exchange, Map.of("success", false, "error", e.getMessage()));
                }
            });
        }
    }

    // Level 3: Intelligence
    private void handlePredictions(HttpServerExchange exchange) {
        Map<String, Object> predictions = new HashMap<>();

        // Generate sample prediction data (would be from actual ML model)
        List<Map<String, Object>> dataPoints = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (int i = -60; i <= 60; i += 5) {
            Map<String, Object> point = new HashMap<>();
            point.put("timestamp", now + (i * 60000));
            point.put("actual", i <= 0 ? (int)(50 + Math.random() * 30) : null);
            point.put("predicted", (int)(55 + Math.sin(i * 0.1) * 20 + Math.random() * 5));
            dataPoints.add(point);
        }

        predictions.put("dataPoints", dataPoints);
        predictions.put("forecastMinutes", atlas.getConfig().getPredictionsForecastMinutes());

        sendJson(exchange, predictions);
    }

    private void handleSlowQueries(HttpServerExchange exchange) {
        List<Map<String, Object>> queries = new ArrayList<>();

        var monitor = atlas.getSlowQueryMonitor();
        if (monitor != null) {
            for (var query : monitor.getTopSlowest(10)) {
                queries.add(Map.of(
                    "timestamp", query.timestamp(),
                    "queryType", query.queryType(),
                    "query", query.query(),
                    "durationMs", query.durationMs()
                ));
            }
        }

        sendJson(exchange, queries);
    }

    private void handleReputationGeo(HttpServerExchange exchange) {
        // Geographic reputation data for heatmap
        Map<String, Object> geoData = new HashMap<>();

        // Sample data by region
        List<Map<String, Object>> regions = List.of(
            Map.of("code", "NA", "name", "North America", "trusted", 450, "suspicious", 12, "lat", 40.0, "lng", -100.0),
            Map.of("code", "EU", "name", "Europe", "trusted", 320, "suspicious", 8, "lat", 50.0, "lng", 10.0),
            Map.of("code", "AS", "name", "Asia", "trusted", 180, "suspicious", 25, "lat", 35.0, "lng", 105.0),
            Map.of("code", "SA", "name", "South America", "trusted", 85, "suspicious", 5, "lat", -15.0, "lng", -60.0),
            Map.of("code", "OC", "name", "Oceania", "trusted", 45, "suspicious", 2, "lat", -25.0, "lng", 135.0)
        );

        geoData.put("regions", regions);
        geoData.put("timestamp", System.currentTimeMillis());

        sendJson(exchange, geoData);
    }

    // Level 4: Command & Control
    private void handleSearch(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> request = gson.fromJson(body, Map.class);
                String query = request.get("query");

                // Search would integrate with Velotim's PlayerProfiler
                List<Map<String, Object>> results = new ArrayList<>();

                // Mock results
                results.add(Map.of(
                    "name", query,
                    "uuid", UUID.randomUUID().toString(),
                    "reputation", 750,
                    "lastSeen", System.currentTimeMillis() - 3600000,
                    "server", "lobby-1"
                ));

                sendJson(exchange, Map.of("results", results, "total", results.size()));
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        });
    }

    private void handleDangerousToken(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> request = gson.fromJson(body, Map.class);
                String action = request.get("action");

                if (atlas.getStateStore() == null) {
                    sendJson(exchange, Map.of("error", "State store not available"));
                    return;
                }

                String token = atlas.getStateStore().generateDangerousActionToken(action);
                if (token != null) {
                    sendJson(exchange, Map.of("token", token, "expiresIn", 30));
                } else {
                    sendJson(exchange, Map.of("error", "Failed to generate token"));
                }
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        });
    }

    private void handleDangerousExecute(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> request = gson.fromJson(body, Map.class);
                String action = request.get("action");
                String token = request.get("token");
                String confirmation = request.get("confirmation");

                // Validate token
                if (atlas.getStateStore() == null ||
                    !atlas.getStateStore().validateAndConsumeToken(token, action)) {
                    exchange.setStatusCode(StatusCodes.FORBIDDEN);
                    sendJson(exchange, Map.of("success", false, "error", "Invalid or expired token"));
                    return;
                }

                // Validate confirmation phrase
                String expectedPhrase = "CONFIRM " + action.toUpperCase();
                if (!expectedPhrase.equals(confirmation)) {
                    sendJson(exchange, Map.of("success", false, "error", "Invalid confirmation phrase"));
                    return;
                }

                // Execute action
                boolean success = executeDangerousAction(action);

                // Audit log
                atlas.getStateStore().recordAuditEvent(
                    "DANGEROUS_ACTION",
                    "dashboard",
                    action + " - " + (success ? "SUCCESS" : "FAILED")
                );

                sendJson(exchange, Map.of("success", success));
            } catch (Exception e) {
                sendJson(exchange, Map.of("success", false, "error", e.getMessage()));
            }
        });
    }

    private boolean executeDangerousAction(String action) {
        return switch (action) {
            case "RESET_BASELINE" -> {
                logger.warn("Executing dangerous action: RESET_BASELINE");
                // Would reset traffic baseline
                yield true;
            }
            case "CLEAR_CACHE" -> {
                logger.warn("Executing dangerous action: CLEAR_CACHE");
                // Would clear all caches
                yield true;
            }
            case "EMERGENCY_LOCKDOWN" -> {
                logger.warn("Executing dangerous action: EMERGENCY_LOCKDOWN");
                // Would enable emergency mode
                if (atlas.getPlayerDrainer() != null) {
                    atlas.getPlayerDrainer().drainAll();
                }
                yield true;
            }
            default -> false;
        };
    }

    private void handleAudit(HttpServerExchange exchange) {
        String limitParam = exchange.getQueryParameters().getOrDefault("limit",
            new ArrayDeque<>(List.of("100"))).getFirst();
        int limit = Integer.parseInt(limitParam);

        List<Map<String, String>> auditLog = List.of();
        if (atlas.getStateStore() != null) {
            auditLog = atlas.getStateStore().getAuditLog(limit);
        }

        sendJson(exchange, auditLog);
    }

    private void sendJson(HttpServerExchange exchange, Object data) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(gson.toJson(data));
    }
}
