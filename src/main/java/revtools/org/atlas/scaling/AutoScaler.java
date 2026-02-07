/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.scaling;

import org.slf4j.Logger;
import revtools.org.atlas.bridge.RedisStateStore;
import revtools.org.atlas.bridge.VelotimBridge;
import revtools.org.atlas.config.AtlasConfig;
import revtools.org.atlas.config.AtlasConfig.ScalingRule;
import revtools.org.atlas.kubernetes.ClusterManager;
import revtools.org.atlas.kubernetes.ClusterManager.StatefulSetInfo;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The "Brain" - makes intelligent scaling decisions based on Minecraft-specific metrics.
 * Evaluates rules for player count, TPS, MSPT, and queue depth.
 */
public class AutoScaler {

    private final AtlasConfig config;
    private final ClusterManager clusterManager;
    private final RedisStateStore stateStore;
    private final VelotimBridge velotimBridge;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Atlas-AutoScaler");
        t.setDaemon(true);
        return t;
    });

    // Cooldown tracking
    private final Map<String, Long> lastScaleUpTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastScaleDownTime = new ConcurrentHashMap<>();

    // Decision history
    private final List<ScalingDecision> decisionHistory = Collections.synchronizedList(
        new LinkedList<>() {
            @Override
            public boolean add(ScalingDecision decision) {
                if (size() >= 100) removeFirst();
                return super.add(decision);
            }
        }
    );

    public AutoScaler(AtlasConfig config, ClusterManager clusterManager,
                      RedisStateStore stateStore, VelotimBridge velotimBridge, Logger logger) {
        this.config = config;
        this.clusterManager = clusterManager;
        this.stateStore = stateStore;
        this.velotimBridge = velotimBridge;
        this.logger = logger;
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }

        int interval = config.getScalingCheckInterval();
        scheduler.scheduleAtFixedRate(this::evaluateScaling, interval, interval, TimeUnit.SECONDS);
        logger.info("Auto-scaler started (interval: {}s)", interval);
    }

    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("Auto-scaler stopped");
    }

    private void evaluateScaling() {
        if (!running.get() || clusterManager == null) {
            return;
        }

        try {
            // Get current metrics from Velotim bridge
            Map<String, ServerMetrics> serverMetrics = getServerMetrics();

            // Get StatefulSets
            List<StatefulSetInfo> statefulSets = clusterManager.listStatefulSets();

            // Evaluate each rule
            for (ScalingRule rule : config.getScalingRules()) {
                evaluateRule(rule, serverMetrics, statefulSets);
            }

        } catch (Exception e) {
            logger.error("Error in scaling evaluation: {}", e.getMessage());
        }
    }

    private void evaluateRule(ScalingRule rule, Map<String, ServerMetrics> serverMetrics,
                              List<StatefulSetInfo> statefulSets) {

        String target = rule.target();
        Pattern pattern = Pattern.compile(target.replace("*", ".*"));

        for (StatefulSetInfo sts : statefulSets) {
            if (!pattern.matcher(sts.name()).matches()) {
                continue;
            }

            double metricValue = getMetricForRule(rule, sts.name(), serverMetrics);
            ScalingAction action = determineAction(rule, metricValue, sts);

            if (action != ScalingAction.NONE) {
                executeScaling(sts.name(), action, rule, metricValue, sts.desiredReplicas());
            }
        }
    }

    private double getMetricForRule(ScalingRule rule, String stsName, Map<String, ServerMetrics> serverMetrics) {
        // Aggregate metrics for all pods in the StatefulSet
        List<ServerMetrics> matchingMetrics = serverMetrics.entrySet().stream()
            .filter(e -> e.getKey().startsWith(stsName))
            .map(Map.Entry::getValue)
            .toList();

        if (matchingMetrics.isEmpty()) {
            return 0;
        }

        return switch (rule.type()) {
            case "player-count" -> matchingMetrics.stream()
                .mapToInt(ServerMetrics::players)
                .average()
                .orElse(0);
            case "tps" -> matchingMetrics.stream()
                .mapToDouble(ServerMetrics::tps)
                .min()
                .orElse(20.0);
            case "mspt" -> matchingMetrics.stream()
                .mapToDouble(ServerMetrics::mspt)
                .max()
                .orElse(0);
            case "queue-depth" -> getQueueDepth();
            default -> 0;
        };
    }

    private ScalingAction determineAction(ScalingRule rule, double metricValue, StatefulSetInfo sts) {
        int currentReplicas = sts.desiredReplicas();
        int minReplicas = config.getMinReplicas();
        int maxReplicas = config.getMaxReplicas();

        // Check for scale up
        boolean shouldScaleUp = switch (rule.type()) {
            case "player-count", "mspt", "queue-depth" -> metricValue >= rule.scaleUpThreshold();
            case "tps" -> metricValue <= rule.scaleUpThreshold(); // Low TPS = scale up
            default -> false;
        };

        // Check for scale down
        boolean shouldScaleDown = switch (rule.type()) {
            case "player-count", "mspt", "queue-depth" -> metricValue <= rule.scaleDownThreshold();
            case "tps" -> metricValue >= 19.5; // High TPS = can scale down
            default -> false;
        };

        if (shouldScaleUp && currentReplicas < maxReplicas) {
            if (canScaleUp(sts.name())) {
                return ScalingAction.SCALE_UP;
            }
        } else if (shouldScaleDown && currentReplicas > minReplicas) {
            if (canScaleDown(sts.name())) {
                return ScalingAction.SCALE_DOWN;
            }
        }

        return ScalingAction.NONE;
    }

    private boolean canScaleUp(String target) {
        Long lastTime = lastScaleUpTime.get(target);
        if (lastTime == null) return true;
        return System.currentTimeMillis() - lastTime >= config.getScaleUpCooldown() * 1000L;
    }

    private boolean canScaleDown(String target) {
        Long lastTime = lastScaleDownTime.get(target);
        if (lastTime == null) return true;
        return System.currentTimeMillis() - lastTime >= config.getScaleDownCooldown() * 1000L;
    }

    private void executeScaling(String target, ScalingAction action, ScalingRule rule,
                                double metricValue, int currentReplicas) {
        int newReplicas = action == ScalingAction.SCALE_UP ? currentReplicas + 1 : currentReplicas - 1;

        logger.info("Scaling {} from {} to {} replicas (rule: {}, metric: {:.2f})",
            target, currentReplicas, newReplicas, rule.name(), metricValue);

        boolean success = clusterManager.scaleStatefulSet(target, newReplicas);

        ScalingDecision decision = new ScalingDecision(
            System.currentTimeMillis(),
            target,
            action,
            rule.name(),
            rule.type(),
            metricValue,
            currentReplicas,
            newReplicas,
            success
        );

        decisionHistory.add(decision);

        if (success) {
            if (action == ScalingAction.SCALE_UP) {
                lastScaleUpTime.put(target, System.currentTimeMillis());
            } else {
                lastScaleDownTime.put(target, System.currentTimeMillis());
            }

            // Record to Redis for dashboard
            if (stateStore != null) {
                stateStore.recordScalingDecision(decision);
            }

            // Notify Velotim bridge
            if (velotimBridge != null) {
                velotimBridge.notifyScalingEvent(decision);
            }
        }
    }

    private Map<String, ServerMetrics> getServerMetrics() {
        if (velotimBridge != null) {
            return velotimBridge.getServerMetrics();
        }
        // Fallback to direct Redis query
        if (stateStore != null) {
            return stateStore.getServerMetrics();
        }
        return Map.of();
    }

    private double getQueueDepth() {
        if (velotimBridge != null) {
            return velotimBridge.getQueueDepth();
        }
        return 0;
    }

    public List<ScalingDecision> getDecisionHistory() {
        return new ArrayList<>(decisionHistory);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Force an immediate scaling action (for manual override).
     */
    public boolean forceScale(String target, int replicas) {
        logger.warn("Manual scaling override: {} -> {} replicas", target, replicas);

        boolean success = clusterManager.scaleStatefulSet(target, replicas);

        ScalingDecision decision = new ScalingDecision(
            System.currentTimeMillis(),
            target,
            ScalingAction.MANUAL,
            "manual-override",
            "manual",
            0,
            -1,
            replicas,
            success
        );

        decisionHistory.add(decision);

        if (stateStore != null) {
            stateStore.recordScalingDecision(decision);
        }

        return success;
    }

    public enum ScalingAction {
        NONE,
        SCALE_UP,
        SCALE_DOWN,
        MANUAL
    }

    public record ServerMetrics(
        String serverId,
        int players,
        double tps,
        double mspt
    ) {}

    public record ScalingDecision(
        long timestamp,
        String target,
        ScalingAction action,
        String ruleName,
        String ruleType,
        double metricValue,
        int previousReplicas,
        int newReplicas,
        boolean success
    ) {}
}
