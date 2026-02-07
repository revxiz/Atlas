/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.metrics;

import org.slf4j.Logger;
import revtools.org.atlas.Atlas;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exports Atlas metrics in Prometheus format.
 */
public class AtlasMetricsExporter {

    private final Atlas atlas;
    private final Logger logger;
    private final String prefix;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Atlas-MetricsExporter");
        t.setDaemon(true);
        return t;
    });

    // Counters
    private final AtomicLong scalingDecisions = new AtomicLong(0);
    private final AtomicLong drainEvents = new AtomicLong(0);
    private final AtomicLong k8sApiCalls = new AtomicLong(0);
    private final AtomicLong k8sApiErrors = new AtomicLong(0);

    public AtlasMetricsExporter(Atlas atlas, String prefix, Logger logger) {
        this.atlas = atlas;
        this.prefix = prefix;
        this.logger = logger;
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }

        // Periodic metrics collection
        scheduler.scheduleAtFixedRate(this::collectMetrics, 10, 10, TimeUnit.SECONDS);
        logger.info("Metrics exporter started");
    }

    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("Metrics exporter stopped");
    }

    private void collectMetrics() {
        // Collect from components
        if (atlas.getAutoScaler() != null) {
            scalingDecisions.set(atlas.getAutoScaler().getDecisionHistory().size());
        }

        if (atlas.getPlayerDrainer() != null) {
            drainEvents.set(atlas.getPlayerDrainer().getDrainStates().size());
        }

        if (atlas.getClusterManager() != null) {
            k8sApiErrors.set(atlas.getClusterManager().getFailureCount());
        }
    }

    /**
     * Generate Prometheus format metrics.
     */
    public String exportMetrics() {
        StringBuilder sb = new StringBuilder();

        // System metrics
        sb.append("# HELP ").append(prefix).append("up Atlas is running\n");
        sb.append("# TYPE ").append(prefix).append("up gauge\n");
        sb.append(prefix).append("up ").append(atlas.isInitialized() ? 1 : 0).append("\n\n");

        // Uptime
        long uptime = System.currentTimeMillis() - atlas.getStartTime();
        sb.append("# HELP ").append(prefix).append("uptime_seconds Uptime in seconds\n");
        sb.append("# TYPE ").append(prefix).append("uptime_seconds gauge\n");
        sb.append(prefix).append("uptime_seconds ").append(uptime / 1000).append("\n\n");

        // Scaling
        sb.append("# HELP ").append(prefix).append("scaling_decisions_total Total scaling decisions\n");
        sb.append("# TYPE ").append(prefix).append("scaling_decisions_total counter\n");
        sb.append(prefix).append("scaling_decisions_total ").append(scalingDecisions.get()).append("\n\n");

        // Drain
        sb.append("# HELP ").append(prefix).append("drain_events_active Active drain events\n");
        sb.append("# TYPE ").append(prefix).append("drain_events_active gauge\n");
        sb.append(prefix).append("drain_events_active ").append(drainEvents.get()).append("\n\n");

        // K8s API
        sb.append("# HELP ").append(prefix).append("k8s_api_calls_total Total K8s API calls\n");
        sb.append("# TYPE ").append(prefix).append("k8s_api_calls_total counter\n");
        sb.append(prefix).append("k8s_api_calls_total ").append(k8sApiCalls.get()).append("\n\n");

        sb.append("# HELP ").append(prefix).append("k8s_api_errors_total Total K8s API errors\n");
        sb.append("# TYPE ").append(prefix).append("k8s_api_errors_total counter\n");
        sb.append(prefix).append("k8s_api_errors_total ").append(k8sApiErrors.get()).append("\n\n");

        // Circuit breaker
        if (atlas.getClusterManager() != null) {
            sb.append("# HELP ").append(prefix).append("k8s_circuit_open K8s circuit breaker state\n");
            sb.append("# TYPE ").append(prefix).append("k8s_circuit_open gauge\n");
            sb.append(prefix).append("k8s_circuit_open ")
              .append(atlas.getClusterManager().isCircuitOpen() ? 1 : 0).append("\n\n");
        }

        // Redis
        if (atlas.getStateStore() != null) {
            sb.append("# HELP ").append(prefix).append("redis_connected Redis connection state\n");
            sb.append("# TYPE ").append(prefix).append("redis_connected gauge\n");
            sb.append(prefix).append("redis_connected ")
              .append(atlas.getStateStore().isConnected() ? 1 : 0).append("\n\n");
        }

        return sb.toString();
    }

    // Increment methods for tracking
    public void recordScalingDecision() {
        scalingDecisions.incrementAndGet();
    }

    public void recordK8sApiCall() {
        k8sApiCalls.incrementAndGet();
    }

    public void recordK8sApiError() {
        k8sApiErrors.incrementAndGet();
    }
}
