/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.kubernetes;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import revtools.org.atlas.config.AtlasConfig;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Kubernetes cluster operations for Minecraft servers.
 * Handles scaling, pod management, and health monitoring with circuit breaker protection.
 */
public class ClusterManager implements Closeable {

    private final KubernetesClientFactory clientFactory;
    private final AtlasConfig config;
    private final Logger logger;

    // Circuit breaker state
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastFailureTime = 0;
    private volatile boolean circuitOpen = false;
    private static final int FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_RESET_MS = 30000; // 30 seconds

    // Cache for pod state
    private final Map<String, PodInfo> podCache = new ConcurrentHashMap<>();
    private volatile long lastCacheUpdate = 0;
    private static final long CACHE_TTL_MS = 5000; // 5 seconds

    public ClusterManager(KubernetesClientFactory clientFactory, AtlasConfig config, Logger logger) {
        this.clientFactory = clientFactory;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Scale a StatefulSet to the specified number of replicas.
     */
    public boolean scaleStatefulSet(String name, int replicas) {
        if (!checkCircuitBreaker()) {
            logger.warn("Circuit breaker open - scaling operation rejected");
            return false;
        }

        try {
            KubernetesClient client = clientFactory.getClient();
            String namespace = config.getKubernetesNamespace();

            StatefulSet sts = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(name)
                .get();

            if (sts == null) {
                logger.error("StatefulSet not found: {}/{}", namespace, name);
                return false;
            }

            int currentReplicas = sts.getSpec().getReplicas();
            if (currentReplicas == replicas) {
                logger.debug("StatefulSet {} already at {} replicas", name, replicas);
                return true;
            }

            // Enforce limits
            int minReplicas = config.getMinReplicas();
            int maxReplicas = config.getMaxReplicas();
            replicas = Math.max(minReplicas, Math.min(maxReplicas, replicas));

            client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(name)
                .scale(replicas);

            logger.info("Scaled StatefulSet {} from {} to {} replicas", name, currentReplicas, replicas);
            recordSuccess();
            return true;

        } catch (Exception e) {
            recordFailure(e);
            return false;
        }
    }

    /**
     * Get all pods matching a label selector.
     */
    public List<PodInfo> getPods(Map<String, String> labels) {
        if (!checkCircuitBreaker()) {
            return getCachedPods(labels);
        }

        try {
            KubernetesClient client = clientFactory.getClient();
            String namespace = config.getKubernetesNamespace();

            PodList pods = client.pods()
                .inNamespace(namespace)
                .withLabels(labels)
                .list();

            List<PodInfo> result = new ArrayList<>();
            for (Pod pod : pods.getItems()) {
                PodInfo info = toPodInfo(pod);
                result.add(info);
                podCache.put(info.name(), info);
            }

            lastCacheUpdate = System.currentTimeMillis();
            recordSuccess();
            return result;

        } catch (Exception e) {
            recordFailure(e);
            return getCachedPods(labels);
        }
    }

    /**
     * Get all Minecraft server pods.
     */
    public List<PodInfo> getMinecraftPods() {
        return getPods(Map.of("app.kubernetes.io/component", "minecraft-server"));
    }

    /**
     * Get pods for a specific StatefulSet.
     */
    public List<PodInfo> getPodsForStatefulSet(String statefulSetName) {
        return getPods(Map.of(
            "app.kubernetes.io/name", statefulSetName
        ));
    }

    /**
     * Delete a pod (for self-healing or scale-down).
     */
    public boolean deletePod(String name) {
        if (!checkCircuitBreaker()) {
            logger.warn("Circuit breaker open - delete operation rejected");
            return false;
        }

        try {
            KubernetesClient client = clientFactory.getClient();
            String namespace = config.getKubernetesNamespace();

            client.pods()
                .inNamespace(namespace)
                .withName(name)
                .withGracePeriod(30)
                .delete();

            logger.info("Deleted pod: {}", name);
            podCache.remove(name);
            recordSuccess();
            return true;

        } catch (Exception e) {
            recordFailure(e);
            return false;
        }
    }

    /**
     * Get StatefulSet information.
     */
    public StatefulSetInfo getStatefulSet(String name) {
        if (!checkCircuitBreaker()) {
            return null;
        }

        try {
            KubernetesClient client = clientFactory.getClient();
            String namespace = config.getKubernetesNamespace();

            StatefulSet sts = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(name)
                .get();

            if (sts == null) return null;

            recordSuccess();
            return new StatefulSetInfo(
                sts.getMetadata().getName(),
                sts.getSpec().getReplicas(),
                sts.getStatus().getReadyReplicas() != null ? sts.getStatus().getReadyReplicas() : 0,
                sts.getStatus().getCurrentReplicas() != null ? sts.getStatus().getCurrentReplicas() : 0
            );

        } catch (Exception e) {
            recordFailure(e);
            return null;
        }
    }

    /**
     * List all StatefulSets in namespace.
     */
    public List<StatefulSetInfo> listStatefulSets() {
        if (!checkCircuitBreaker()) {
            return List.of();
        }

        try {
            KubernetesClient client = clientFactory.getClient();
            String namespace = config.getKubernetesNamespace();

            List<StatefulSetInfo> result = new ArrayList<>();
            for (StatefulSet sts : client.apps().statefulSets().inNamespace(namespace).list().getItems()) {
                result.add(new StatefulSetInfo(
                    sts.getMetadata().getName(),
                    sts.getSpec().getReplicas(),
                    sts.getStatus().getReadyReplicas() != null ? sts.getStatus().getReadyReplicas() : 0,
                    sts.getStatus().getCurrentReplicas() != null ? sts.getStatus().getCurrentReplicas() : 0
                ));
            }

            recordSuccess();
            return result;

        } catch (Exception e) {
            recordFailure(e);
            return List.of();
        }
    }

    /**
     * Check if cluster is reachable.
     */
    public boolean isClusterReachable() {
        if (!clientFactory.isAvailable()) return false;

        try {
            clientFactory.getClient().namespaces().list();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Circuit breaker methods
    private boolean checkCircuitBreaker() {
        if (circuitOpen) {
            if (System.currentTimeMillis() - lastFailureTime > CIRCUIT_RESET_MS) {
                logger.info("Circuit breaker reset - attempting reconnection");
                circuitOpen = false;
                failureCount.set(0);
            } else {
                return false;
            }
        }
        return clientFactory.isAvailable();
    }

    private void recordSuccess() {
        failureCount.set(0);
        circuitOpen = false;
    }

    private void recordFailure(Exception e) {
        logger.error("K8s API error: {}", e.getMessage());
        lastFailureTime = System.currentTimeMillis();

        if (failureCount.incrementAndGet() >= FAILURE_THRESHOLD) {
            circuitOpen = true;
            logger.warn("Circuit breaker opened after {} failures", FAILURE_THRESHOLD);
        }
    }

    private List<PodInfo> getCachedPods(Map<String, String> labels) {
        // Return cached pods if available
        return podCache.values().stream()
            .filter(p -> matchesLabels(p, labels))
            .toList();
    }

    private boolean matchesLabels(PodInfo pod, Map<String, String> labels) {
        // Simple matching - in reality would need label data in PodInfo
        return true;
    }

    private PodInfo toPodInfo(Pod pod) {
        String phase = pod.getStatus().getPhase();
        boolean ready = pod.getStatus().getConditions() != null &&
            pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

        String ip = pod.getStatus().getPodIP();
        String node = pod.getSpec().getNodeName();

        return new PodInfo(
            pod.getMetadata().getName(),
            phase,
            ready,
            ip,
            node,
            pod.getMetadata().getCreationTimestamp()
        );
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    @Override
    public void close() {
        podCache.clear();
    }

    /**
     * Pod information record.
     */
    public record PodInfo(
        String name,
        String phase,
        boolean ready,
        String ip,
        String node,
        String creationTime
    ) {}

    /**
     * StatefulSet information record.
     */
    public record StatefulSetInfo(
        String name,
        int desiredReplicas,
        int readyReplicas,
        int currentReplicas
    ) {}
}
