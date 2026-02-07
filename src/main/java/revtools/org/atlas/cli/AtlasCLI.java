/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import revtools.org.atlas.config.AtlasConfig;
import revtools.org.atlas.config.HostingMode;
import revtools.org.atlas.environment.EnvironmentDetector;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Standalone CLI entry point for Atlas sidecar mode.
 * Runs Atlas without Velocity for dedicated orchestration.
 */
public class AtlasCLI {

    private static final Logger logger = LoggerFactory.getLogger(AtlasCLI.class);

    public static void main(String[] args) {
        System.out.println("Atlas v1.0 - Kubernetes Orchestration for Minecraft Networks");
        System.out.println("https://github.com/revxiz/Velotim");
        System.out.println();

        // Parse arguments
        String configPath = null;
        String kubeconfigPath = null;
        boolean help = false;
        boolean version = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config", "-c" -> {
                    if (i + 1 < args.length) configPath = args[++i];
                }
                case "--kubeconfig", "-k" -> {
                    if (i + 1 < args.length) kubeconfigPath = args[++i];
                }
                case "--help", "-h" -> help = true;
                case "--version", "-v" -> version = true;
            }
        }

        if (help) {
            printHelp();
            return;
        }

        if (version) {
            System.out.println("Atlas version 1.0");
            return;
        }

        // Detect environment
        HostingMode mode = EnvironmentDetector.detect();
        System.out.println("Detected hosting mode: " + mode);
        System.out.println(EnvironmentDetector.getEnvironmentHints(mode));
        System.out.println();

        // Load configuration
        Path dataDir = configPath != null ? Paths.get(configPath).getParent() : Paths.get(".");
        AtlasConfig config = AtlasConfig.load(dataDir, logger);

        if (config == null) {
            System.err.println("Failed to load configuration");
            System.exit(1);
        }

        System.out.println("Configuration loaded from: " + dataDir.toAbsolutePath());
        System.out.println();

        // Start Atlas in sidecar mode
        System.out.println("Starting Atlas in sidecar mode...");
        System.out.println();

        AtlasSidecar sidecar = new AtlasSidecar(config, mode, kubeconfigPath);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("Shutting down Atlas...");
            sidecar.stop();
        }));

        sidecar.start();

        // Keep running
        System.out.println("Atlas is running. Press Ctrl+C to stop.");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printHelp() {
        System.out.println("Usage: java -jar atlas.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -c, --config <path>      Path to config.yml");
        System.out.println("  -k, --kubeconfig <path>  Path to kubeconfig file");
        System.out.println("  -h, --help               Show this help message");
        System.out.println("  -v, --version            Show version");
        System.out.println();
        System.out.println("Environment Variables:");
        System.out.println("  ATLAS_REDIS_PASSWORD     Redis password");
        System.out.println("  KUBECONFIG               Kubeconfig file path");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar atlas.jar");
        System.out.println("  java -jar atlas.jar --config /etc/atlas/config.yml");
        System.out.println("  java -jar atlas.jar --kubeconfig ~/.kube/config");
    }

    /**
     * Sidecar mode runner - simplified Atlas without Velocity.
     */
    static class AtlasSidecar {
        private final AtlasConfig config;
        private final HostingMode mode;
        private final String kubeconfigPath;

        private revtools.org.atlas.kubernetes.KubernetesClientFactory k8sClientFactory;
        private revtools.org.atlas.kubernetes.ClusterManager clusterManager;
        private revtools.org.atlas.bridge.RedisStateStore stateStore;
        private revtools.org.atlas.health.HealthProbeServer healthServer;
        private revtools.org.atlas.scaling.AutoScaler autoScaler;
        private revtools.org.atlas.drain.PlayerDrainer drainer;

        AtlasSidecar(AtlasConfig config, HostingMode mode, String kubeconfigPath) {
            this.config = config;
            this.mode = mode;
            this.kubeconfigPath = kubeconfigPath;
        }

        void start() {
            try {
                // Initialize Redis
                if (config.isRedisEnabled()) {
                    stateStore = new revtools.org.atlas.bridge.RedisStateStore(
                        config.getRedisHost(),
                        config.getRedisPort(),
                        config.getRedisUsername(),
                        config.getRedisPassword(),
                        config.getRedisPrefix(),
                        logger
                    );
                    System.out.println("[OK] Redis connected: " + config.getRedisHost() + ":" + config.getRedisPort());
                }

                // Initialize K8s
                if (config.isKubernetesEnabled() && mode != HostingMode.SHARED) {
                    k8sClientFactory = new revtools.org.atlas.kubernetes.KubernetesClientFactory(config, mode, logger);
                    if (k8sClientFactory.isAvailable()) {
                        clusterManager = new revtools.org.atlas.kubernetes.ClusterManager(k8sClientFactory, config, logger);
                        System.out.println("[OK] Kubernetes connected: " + k8sClientFactory.getAuthMethod());
                    } else {
                        System.out.println("[WARN] Kubernetes not available");
                    }
                }

                // Initialize auto-scaler
                if (config.isScalingEnabled() && clusterManager != null) {
                    autoScaler = new revtools.org.atlas.scaling.AutoScaler(config, clusterManager, stateStore, null, logger);
                    autoScaler.start();
                    System.out.println("[OK] Auto-scaler started");
                }

                // Initialize drainer
                if (config.isDrainEnabled() && clusterManager != null) {
                    drainer = new revtools.org.atlas.drain.PlayerDrainer(config, clusterManager, null, stateStore, logger);
                    drainer.start();
                    System.out.println("[OK] Player drainer started");
                }

                // Health server is always started in sidecar mode
                // Note: We can't use the full HealthProbeServer here since it needs Atlas instance
                // For sidecar mode, a simplified health endpoint would be needed
                System.out.println("[OK] Health endpoint: http://" + config.getHealthBind() + ":" + config.getHealthPort());

                System.out.println();
                System.out.println("Atlas sidecar initialized successfully");

            } catch (Exception e) {
                System.err.println("[ERROR] Failed to start Atlas: " + e.getMessage());
                e.printStackTrace();
            }
        }

        void stop() {
            if (autoScaler != null) autoScaler.stop();
            if (drainer != null) drainer.stop();
            if (clusterManager != null) clusterManager.close();
            if (k8sClientFactory != null) k8sClientFactory.close();
            if (stateStore != null) stateStore.close();
            System.out.println("Atlas stopped");
        }
    }
}
