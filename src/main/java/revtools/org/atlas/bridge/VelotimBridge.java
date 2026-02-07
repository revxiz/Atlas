/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.bridge;

import org.slf4j.Logger;
import revtools.org.atlas.config.AtlasConfig;
import revtools.org.atlas.scaling.AutoScaler.ScalingDecision;
import revtools.org.atlas.scaling.AutoScaler.ServerMetrics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridge to Velotim for integrated operations.
 * Provides server metrics, routing control, and dashboard synchronization.
 */
public class VelotimBridge {

    private final RedisStateStore stateStore;
    private final AtlasConfig config;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Atlas-VelotimBridge");
        t.setDaemon(true);
        return t;
    });

    // Cached state
    private volatile Map<String, ServerMetrics> metricsCache = new ConcurrentHashMap<>();
    private volatile Set<String> drainingServers = ConcurrentHashMap.newKeySet();
    private volatile double queueDepth = 0;

    public VelotimBridge(RedisStateStore stateStore, AtlasConfig config, Logger logger) {
        this.stateStore = stateStore;
        this.config = config;
        this.logger = logger;
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }

        // Sync metrics periodically
        scheduler.scheduleAtFixedRate(this::syncMetrics, 0, 5, TimeUnit.SECONDS);
        logger.info("Velotim bridge started");
    }

    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("Velotim bridge stopped");
    }

    private void syncMetrics() {
        if (!running.get()) return;

        try {
            // Get metrics from Velotim via Redis
            metricsCache = stateStore.getServerMetrics();

            // Get queue depth
            // This would normally query Velotim's queue state
            // For now, estimate from connection metrics

        } catch (Exception e) {
            logger.debug("Failed to sync metrics: {}", e.getMessage());
        }
    }

    /**
     * Get current server metrics from Velotim.
     */
    public Map<String, ServerMetrics> getServerMetrics() {
        return new HashMap<>(metricsCache);
    }

    /**
     * Get player count for a specific server.
     */
    public int getPlayerCount(String serverId) {
        ServerMetrics metrics = metricsCache.get(serverId);
        return metrics != null ? metrics.players() : 0;
    }

    /**
     * Get current connection queue depth.
     */
    public double getQueueDepth() {
        return queueDepth;
    }

    /**
     * Get list of active server IDs.
     */
    public Set<String> getActiveServerIds() {
        return new HashSet<>(metricsCache.keySet());
    }

    /**
     * Mark a server as draining (Velotim stops routing to it).
     */
    public void markServerDraining(String serverId) {
        drainingServers.add(serverId);

        // Set draining flag in Redis for Velotim to read
        if (stateStore != null && stateStore.isConnected()) {
            try {
                // This would set a flag that Velotim's load balancer checks
                stateStore.recordAuditEvent("SERVER_DRAIN_START", "atlas", serverId);
            } catch (Exception e) {
                logger.warn("Failed to mark server draining in Redis: {}", e.getMessage());
            }
        }

        logger.info("Marked server as draining: {}", serverId);
    }

    /**
     * Unmark a server as draining (resume routing).
     */
    public void unmarkServerDraining(String serverId) {
        drainingServers.remove(serverId);

        if (stateStore != null && stateStore.isConnected()) {
            try {
                stateStore.recordAuditEvent("SERVER_DRAIN_CANCEL", "atlas", serverId);
            } catch (Exception e) {
                logger.warn("Failed to unmark server draining: {}", e.getMessage());
            }
        }

        logger.info("Unmarked server as draining: {}", serverId);
    }

    /**
     * Check if a server is marked as draining.
     */
    public boolean isServerDraining(String serverId) {
        return drainingServers.contains(serverId);
    }

    /**
     * Unregister a server from Velotim discovery.
     */
    public void unregisterServer(String serverId) {
        drainingServers.remove(serverId);
        metricsCache.remove(serverId);

        if (stateStore != null && stateStore.isConnected()) {
            try {
                stateStore.recordAuditEvent("SERVER_UNREGISTER", "atlas", serverId);
            } catch (Exception e) {
                logger.warn("Failed to record server unregister: {}", e.getMessage());
            }
        }

        logger.info("Unregistered server: {}", serverId);
    }

    /**
     * Notify Velotim of a scaling event.
     */
    public void notifyScalingEvent(ScalingDecision decision) {
        if (stateStore != null && stateStore.isConnected()) {
            try {
                stateStore.recordAuditEvent(
                    "SCALING_" + decision.action().name(),
                    "atlas-autoscaler",
                    String.format("%s: %d -> %d (rule: %s)",
                        decision.target(),
                        decision.previousReplicas(),
                        decision.newReplicas(),
                        decision.ruleName())
                );
            } catch (Exception e) {
                logger.warn("Failed to notify scaling event: {}", e.getMessage());
            }
        }
    }

    /**
     * Get threat level from Velotim.
     */
    public String getThreatLevel() {
        // Would query Velotim's threat state from Redis
        // For now return default
        return "GREEN";
    }

    /**
     * Get active incidents from Velotim.
     */
    public List<Map<String, String>> getActiveIncidents() {
        // Would query Velotim's incident list from Redis
        return List.of();
    }

    /**
     * Get throughput metrics from Velotim.
     */
    public Map<String, Object> getThroughputMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("pps", 0);
        metrics.put("cps", 0);
        metrics.put("bandwidth", 0);

        // Would query Velotim's telemetry from Redis

        return metrics;
    }

    public boolean isRunning() {
        return running.get();
    }
}
