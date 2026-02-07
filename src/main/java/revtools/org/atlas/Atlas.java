/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 * https://github.com/revxiz/Velotim
 */
package revtools.org.atlas;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import revtools.org.atlas.config.AtlasConfig;
import revtools.org.atlas.config.HostingMode;
import revtools.org.atlas.dashboard.AtlasDashboardServer;
import revtools.org.atlas.drain.PlayerDrainer;
import revtools.org.atlas.environment.EnvironmentDetector;
import revtools.org.atlas.health.HealthProbeServer;
import revtools.org.atlas.kubernetes.ClusterManager;
import revtools.org.atlas.kubernetes.KubernetesClientFactory;
import revtools.org.atlas.kubernetes.PodWatcher;
import revtools.org.atlas.bridge.RedisStateStore;
import revtools.org.atlas.bridge.VelotimBridge;
import revtools.org.atlas.metrics.AtlasMetricsExporter;
import revtools.org.atlas.metrics.SlowQueryMonitor;
import revtools.org.atlas.scaling.AutoScaler;

import java.nio.file.Path;

/**
 * Atlas - Kubernetes Orchestration Plugin for Velocity
 *
 * Provides:
 * - Auto-scaling based on player count, TPS, MSPT
 * - Self-healing via K8s liveness probes
 * - Graceful player migration on scale-down
 * - Integration with Velotim for unified dashboard
 */
@Plugin(
    id = "atlas",
    name = "Atlas",
    version = "1.0",
    description = "Kubernetes orchestration for Minecraft networks",
    url = "https://github.com/revxiz/Velotim",
    authors = {"xiz"}
)
public class Atlas {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    // Configuration
    private AtlasConfig config;
    private HostingMode hostingMode;

    // Core Components
    private KubernetesClientFactory k8sClientFactory;
    private ClusterManager clusterManager;
    private PodWatcher podWatcher;
    private AutoScaler autoScaler;
    private PlayerDrainer playerDrainer;

    // State & Metrics
    private RedisStateStore stateStore;
    private AtlasMetricsExporter metricsExporter;
    private SlowQueryMonitor slowQueryMonitor;

    // Servers
    private HealthProbeServer healthProbeServer;
    private AtlasDashboardServer dashboardServer;

    // Integration
    private VelotimBridge velotimBridge;

    // State
    private volatile boolean initialized = false;
    private long startTime;

    @Inject
    public Atlas(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        startTime = System.currentTimeMillis();
        logger.info("Atlas v1.0 initializing...");

        try {
            // Detect hosting mode
            hostingMode = EnvironmentDetector.detect();
            logger.info("Detected hosting mode: {}", hostingMode);

            // Load configuration
            config = AtlasConfig.load(dataDirectory, logger);
            if (config == null) {
                logger.error("Failed to load configuration");
                return;
            }

            // Initialize Redis state store
            initializeRedis();

            // Initialize Kubernetes client (if enabled and not SHARED mode)
            if (config.isKubernetesEnabled() && hostingMode != HostingMode.SHARED) {
                initializeKubernetes();
            } else {
                logger.info("Kubernetes integration disabled (mode: {})", hostingMode);
            }

            // Initialize health probe server
            if (config.isHealthEnabled()) {
                healthProbeServer = new HealthProbeServer(
                    config.getHealthPort(),
                    config.getHealthBind(),
                    this,
                    logger
                );
                healthProbeServer.start();
                logger.info("Health probe server started on {}:{}",
                    config.getHealthBind(), config.getHealthPort());
            }

            // Initialize dashboard
            if (config.isDashboardEnabled()) {
                dashboardServer = new AtlasDashboardServer(
                    config.getDashboardPort(),
                    config.getDashboardBind(),
                    config.getDashboardAuthToken(),
                    config.getDashboardAllowedIps(),
                    this,
                    logger
                );
                dashboardServer.start();
                logger.info("Dashboard started on {}:{}",
                    config.getDashboardBind(), config.getDashboardPort());
            }

            // Initialize Velotim bridge (soft dependency)
            initializeVelotimBridge();

            // Initialize auto-scaler
            if (config.isScalingEnabled() && clusterManager != null) {
                autoScaler = new AutoScaler(config, clusterManager, stateStore, velotimBridge, logger);
                autoScaler.start();
                logger.info("Auto-scaler started with {} rules", config.getScalingRules().size());
            }

            // Initialize player drainer
            if (config.isDrainEnabled()) {
                playerDrainer = new PlayerDrainer(config, clusterManager, velotimBridge, stateStore, logger);
                playerDrainer.start();
                logger.info("Player drainer initialized");
            }

            // Initialize slow query monitor
            if (config.isSlowQueryEnabled()) {
                slowQueryMonitor = new SlowQueryMonitor(config, stateStore, logger);
                slowQueryMonitor.start();
                logger.info("Slow query monitor started (threshold: {}ms)", config.getSlowQueryThreshold());
            }

            initialized = true;
            long initTime = System.currentTimeMillis() - startTime;
            logger.info("Atlas initialized in {}ms", initTime);

            printStatus();

        } catch (Exception e) {
            logger.error("Failed to initialize Atlas", e);
        }
    }

    private void initializeRedis() {
        if (!config.isRedisEnabled()) {
            logger.warn("Redis disabled - running in stateless mode");
            return;
        }

        try {
            stateStore = new RedisStateStore(
                config.getRedisHost(),
                config.getRedisPort(),
                config.getRedisUsername(),
                config.getRedisPassword(),
                config.getRedisPrefix(),
                logger
            );
            if (stateStore.isConnected()) {
                logger.info("Connected to Redis at {}:{}", config.getRedisHost(), config.getRedisPort());
            }
        } catch (Exception e) {
            logger.error("Failed to connect to Redis: {}", e.getMessage());
        }
    }

    private void initializeKubernetes() {
        try {
            k8sClientFactory = new KubernetesClientFactory(config, hostingMode, logger);

            if (k8sClientFactory.isAvailable()) {
                clusterManager = new ClusterManager(k8sClientFactory, config, logger);
                podWatcher = new PodWatcher(k8sClientFactory, config, stateStore, logger);
                podWatcher.start();

                logger.info("Kubernetes client initialized (namespace: {})", config.getKubernetesNamespace());
            } else {
                logger.warn("Kubernetes API not available - orchestration disabled");
            }
        } catch (Exception e) {
            logger.error("Failed to initialize Kubernetes client: {}", e.getMessage());
        }
    }

    private void initializeVelotimBridge() {
        if (!config.isVelotimEnabled()) {
            logger.info("Velotim integration disabled");
            return;
        }

        try {
            // Check if Velotim is loaded
            if (proxy.getPluginManager().getPlugin("velotim").isPresent()) {
                velotimBridge = new VelotimBridge(stateStore, config, logger);
                velotimBridge.start();
                logger.info("Velotim bridge initialized - dashboard sync enabled");
            } else {
                logger.info("Velotim not detected - running standalone");
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize Velotim bridge: {}", e.getMessage());
        }
    }

    private void printStatus() {
        logger.info("========================================");
        logger.info("Atlas v1.0 Status:");
        logger.info("  Mode:       {}", hostingMode);
        logger.info("  K8s:        {}", clusterManager != null ? "connected" : "disabled");
        logger.info("  Redis:      {}", stateStore != null && stateStore.isConnected() ? "connected" : "disabled");
        logger.info("  Scaling:    {}", autoScaler != null ? "enabled" : "disabled");
        logger.info("  Draining:   {}", playerDrainer != null ? "enabled" : "disabled");
        logger.info("  Health:     http://{}:{}/healthz", config.getHealthBind(), config.getHealthPort());
        logger.info("  Dashboard:  http://{}:{}", config.getDashboardBind(), config.getDashboardPort());
        logger.info("  Velotim:    {}", velotimBridge != null ? "integrated" : "standalone");
        logger.info("========================================");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Atlas shutting down...");

        shutdownSafely("AutoScaler", () -> { if (autoScaler != null) autoScaler.stop(); });
        shutdownSafely("PlayerDrainer", () -> { if (playerDrainer != null) playerDrainer.stop(); });
        shutdownSafely("SlowQueryMonitor", () -> { if (slowQueryMonitor != null) slowQueryMonitor.stop(); });
        shutdownSafely("PodWatcher", () -> { if (podWatcher != null) podWatcher.stop(); });
        shutdownSafely("VelotimBridge", () -> { if (velotimBridge != null) velotimBridge.stop(); });
        shutdownSafely("Dashboard", () -> { if (dashboardServer != null) dashboardServer.stop(); });
        shutdownSafely("HealthProbe", () -> { if (healthProbeServer != null) healthProbeServer.stop(); });
        shutdownSafely("ClusterManager", () -> { if (clusterManager != null) clusterManager.close(); });
        shutdownSafely("K8sClient", () -> { if (k8sClientFactory != null) k8sClientFactory.close(); });
        shutdownSafely("StateStore", () -> { if (stateStore != null) stateStore.close(); });

        logger.info("Atlas shutdown complete");
    }

    private void shutdownSafely(String component, Runnable shutdown) {
        try {
            shutdown.run();
        } catch (Exception e) {
            logger.warn("Error shutting down {}: {}", component, e.getMessage());
        }
    }

    // Public API
    public ProxyServer getProxy() { return proxy; }
    public Logger getLogger() { return logger; }
    public Path getDataDirectory() { return dataDirectory; }
    public AtlasConfig getConfig() { return config; }
    public HostingMode getHostingMode() { return hostingMode; }
    public boolean isInitialized() { return initialized; }
    public long getStartTime() { return startTime; }

    public ClusterManager getClusterManager() { return clusterManager; }
    public AutoScaler getAutoScaler() { return autoScaler; }
    public PlayerDrainer getPlayerDrainer() { return playerDrainer; }
    public RedisStateStore getStateStore() { return stateStore; }
    public VelotimBridge getVelotimBridge() { return velotimBridge; }
    public SlowQueryMonitor getSlowQueryMonitor() { return slowQueryMonitor; }
}
