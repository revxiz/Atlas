/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.kubernetes;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import org.slf4j.Logger;
import revtools.org.atlas.bridge.RedisStateStore;
import revtools.org.atlas.config.AtlasConfig;

import java.io.Closeable;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches Kubernetes pod events for real-time updates.
 * Pushes state changes to Redis for dashboard integration.
 */
public class PodWatcher implements Closeable {

    private final KubernetesClientFactory clientFactory;
    private final AtlasConfig config;
    private final RedisStateStore stateStore;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Atlas-PodWatcher");
        t.setDaemon(true);
        return t;
    });

    private io.fabric8.kubernetes.client.Watch watch;
    private volatile long lastEventTime = 0;

    public PodWatcher(KubernetesClientFactory clientFactory, AtlasConfig config,
                      RedisStateStore stateStore, Logger logger) {
        this.clientFactory = clientFactory;
        this.config = config;
        this.stateStore = stateStore;
        this.logger = logger;
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }

        executor.submit(this::startWatching);
        logger.info("Pod watcher started");
    }

    private void startWatching() {
        while (running.get()) {
            try {
                KubernetesClient client = clientFactory.getClient();
                if (client == null) {
                    Thread.sleep(5000);
                    continue;
                }

                String namespace = config.getKubernetesNamespace();

                watch = client.pods()
                    .inNamespace(namespace)
                    .withLabel("app.kubernetes.io/component", "minecraft-server")
                    .watch(new Watcher<Pod>() {
                        @Override
                        public void eventReceived(Action action, Pod pod) {
                            handlePodEvent(action, pod);
                        }

                        @Override
                        public void onClose(WatcherException e) {
                            if (e != null && running.get()) {
                                logger.warn("Pod watch closed: {}", e.getMessage());
                            }
                        }
                    });

                logger.debug("Pod watch established for namespace: {}", namespace);

                // Keep running until stopped
                while (running.get() && watch != null) {
                    Thread.sleep(1000);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Pod watcher error: {}", e.getMessage());
                try {
                    Thread.sleep(5000); // Backoff before retry
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handlePodEvent(Watcher.Action action, Pod pod) {
        String podName = pod.getMetadata().getName();
        String phase = pod.getStatus().getPhase();
        lastEventTime = System.currentTimeMillis();

        switch (action) {
            case ADDED -> {
                logger.info("Pod added: {} (phase: {})", podName, phase);
                if (stateStore != null) {
                    stateStore.recordPodEvent("ADDED", podName, phase);
                }
            }
            case MODIFIED -> {
                logger.debug("Pod modified: {} (phase: {})", podName, phase);
                if (stateStore != null) {
                    stateStore.recordPodEvent("MODIFIED", podName, phase);
                }
            }
            case DELETED -> {
                logger.info("Pod deleted: {}", podName);
                if (stateStore != null) {
                    stateStore.recordPodEvent("DELETED", podName, "Terminated");
                }
            }
            case ERROR -> {
                logger.warn("Pod error event: {}", podName);
                if (stateStore != null) {
                    stateStore.recordPodEvent("ERROR", podName, phase);
                }
            }
        }
    }

    public void stop() {
        running.set(false);

        if (watch != null) {
            try {
                watch.close();
            } catch (Exception e) {
                logger.warn("Error closing pod watch: {}", e.getMessage());
            }
        }

        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("Pod watcher stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getLastEventTime() {
        return lastEventTime;
    }

    @Override
    public void close() {
        stop();
    }
}
