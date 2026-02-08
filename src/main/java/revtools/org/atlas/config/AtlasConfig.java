/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Atlas configuration manager.
 * Loads from YAML with environment variable substitution.
 */
public class AtlasConfig {

    private final Map<String, Object> root;
    private final Logger logger;
    private final Path dataDirectory;

    private AtlasConfig(Map<String, Object> root, Logger logger, Path dataDirectory) {
        this.root = root;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    public static AtlasConfig load(Path dataDirectory, Logger logger) {
        try {
            Path configPath = dataDirectory.resolve("config.yml");

            // Create default config if not exists
            if (!Files.exists(configPath)) {
                Files.createDirectories(dataDirectory);
                try (InputStream is = AtlasConfig.class.getResourceAsStream("/config.yml")) {
                    if (is != null) {
                        Files.copy(is, configPath);
                        logger.info("Created default config.yml");
                    }
                }
            }

            // Load and parse
            String content = Files.readString(configPath);
            content = resolveEnvironmentVariables(content);

            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(content);

            return new AtlasConfig(root, logger, dataDirectory);

        } catch (Exception e) {
            logger.error("Failed to load config: {}", e.getMessage());
            return null;
        }
    }

    private static String resolveEnvironmentVariables(String content) {
        // Replace ${ENV_VAR} with actual values
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < content.length()) {
            if (i < content.length() - 2 && content.charAt(i) == '$' && content.charAt(i + 1) == '{') {
                int end = content.indexOf('}', i);
                if (end > i) {
                    String varName = content.substring(i + 2, end);
                    String value = System.getenv(varName);
                    if (value != null) {
                        result.append(value);
                    }
                    i = end + 1;
                    continue;
                }
            }
            result.append(content.charAt(i));
            i++;
        }
        return result.toString();
    }

    /**
     * Persist current configuration back to disk (overwrites config.yml). Backs up previous file.
     */
    public synchronized void save() {
        try {
            Path configPath = dataDirectory.resolve("config.yml");
            // Backup
            if (Files.exists(configPath)) {
                Path bak = dataDirectory.resolve("config.yml.bak");
                Files.copy(configPath, bak, StandardCopyOption.REPLACE_EXISTING);
            }

            Yaml yaml = new Yaml();
            try (BufferedWriter writer = Files.newBufferedWriter(configPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                yaml.dump(root, writer);
            }
            logger.info("Saved configuration to {}", configPath.toString());
        } catch (Exception e) {
            logger.warn("Failed to save configuration: {}", e.getMessage());
        }
    }

    // Setter helpers that update the in-memory map and persist
    public void setDashboardPort(int port) {
        // root may contain nested 'dashboard' map
        Map<String, Object> dashboard = (Map<String, Object>) root.get("dashboard");
        if (dashboard == null) {
            dashboard = new LinkedHashMap<>();
            root.put("dashboard", dashboard);
        }
        dashboard.put("port", port);
        save();
    }

    public void setDashboardBind(String bind) {
        Map<String, Object> dashboard = (Map<String, Object>) root.get("dashboard");
        if (dashboard == null) {
            dashboard = new LinkedHashMap<>();
            root.put("dashboard", dashboard);
        }
        dashboard.put("bind", bind);
        save();
    }

    public void setHealthPort(int port) {
        Map<String, Object> health = (Map<String, Object>) root.get("health");
        if (health == null) {
            health = new LinkedHashMap<>();
            root.put("health", health);
        }
        health.put("port", port);
        save();
    }

    public void setHealthBind(String bind) {
        Map<String, Object> health = (Map<String, Object>) root.get("health");
        if (health == null) {
            health = new LinkedHashMap<>();
            root.put("health", health);
        }
        health.put("bind", bind);
        save();
    }

    // Helper methods
    @SuppressWarnings("unchecked")
    private <T> T get(String path, T defaultValue) {
        String[] parts = path.split("\\.");
        Object current = root;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return defaultValue;
            }
            if (current == null) return defaultValue;
        }

        try {
            return (T) current;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    private String getString(String path, String defaultValue) {
        Object val = get(path, defaultValue);
        return val != null ? val.toString() : defaultValue;
    }

    private int getInt(String path, int defaultValue) {
        Object val = get(path, null);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private boolean getBoolean(String path, boolean defaultValue) {
        Object val = get(path, null);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defaultValue;
    }

    private double getDouble(String path, double defaultValue) {
        Object val = get(path, null);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(String path) {
        Object val = get(path, null);
        if (val instanceof List) {
            return ((List<?>) val).stream()
                .map(Object::toString)
                .toList();
        }
        return List.of();
    }

    // Kubernetes
    public boolean isKubernetesEnabled() { return getBoolean("kubernetes.enabled", true); }
    public String getKubernetesNamespace() { return getString("kubernetes.namespace", "minecraft"); }
    public String getKubernetesAuthStrategy() { return getString("kubernetes.auth-strategy", "auto"); }
    public String getKubeconfigPath() { return getString("kubernetes.kubeconfig-path", ""); }
    public int getK8sConnectionTimeout() { return getInt("kubernetes.connection-timeout-ms", 10000); }
    public int getK8sRequestTimeout() { return getInt("kubernetes.request-timeout-ms", 30000); }

    // Scaling
    public boolean isScalingEnabled() { return getBoolean("scaling.enabled", true); }
    public int getScalingCheckInterval() { return getInt("scaling.check-interval-seconds", 10); }
    public int getScaleUpCooldown() { return getInt("scaling.cooldown.scale-up-seconds", 30); }
    public int getScaleDownCooldown() { return getInt("scaling.cooldown.scale-down-seconds", 300); }
    public int getMinReplicas() { return getInt("scaling.limits.min-replicas", 1); }
    public int getMaxReplicas() { return getInt("scaling.limits.max-replicas", 10); }

    @SuppressWarnings("unchecked")
    public List<ScalingRule> getScalingRules() {
        List<ScalingRule> rules = new ArrayList<>();
        List<Map<String, Object>> rawRules = get("scaling.rules", new ArrayList<>());

        for (Map<String, Object> raw : rawRules) {
            try {
                ScalingRule rule = new ScalingRule(
                    (String) raw.getOrDefault("name", "unnamed"),
                    (String) raw.getOrDefault("type", "player-count"),
                    (String) raw.getOrDefault("target", "*"),
                    ((Number) raw.getOrDefault("scale-up-threshold", 80)).doubleValue(),
                    ((Number) raw.getOrDefault("scale-down-threshold", 20)).doubleValue(),
                    ((Number) raw.getOrDefault("priority", 1)).intValue()
                );
                rules.add(rule);
            } catch (Exception e) {
                logger.warn("Invalid scaling rule: {}", e.getMessage());
            }
        }

        rules.sort(Comparator.comparingInt(ScalingRule::priority));
        return rules;
    }

    // Drain
    public boolean isDrainEnabled() { return getBoolean("drain.enabled", true); }
    public int getDrainTimeout() { return getInt("drain.timeout-seconds", 300); }
    public int getDrainCheckInterval() { return getInt("drain.check-interval-seconds", 5); }
    public boolean isDrainForceAfterTimeout() { return getBoolean("drain.force-after-timeout", true); }

    // Redis
    public boolean isRedisEnabled() { return getBoolean("redis.enabled", true); }
    public String getRedisHost() { return getString("redis.host", "localhost"); }
    public int getRedisPort() { return getInt("redis.port", 6379); }
    public String getRedisUsername() { return getString("redis.username", ""); }
    public String getRedisPassword() { return getString("redis.password", ""); }
    public String getRedisPrefix() { return getString("redis.prefix", "atlas:"); }

    // Velotim
    public boolean isVelotimEnabled() { return getBoolean("velotim.enabled", true); }
    public boolean isVelotimRedisShared() { return getBoolean("velotim.redis-shared", true); }
    public String getVelotimDiscoveryPrefix() { return getString("velotim.discovery-prefix", "velotim:servers:"); }
    public boolean isVelotimDashboardSync() { return getBoolean("velotim.dashboard-sync", true); }
    public double getEmergencyZScoreThreshold() { return getDouble("velotim.emergency-zscore-threshold", 5.0); }

    // Health
    public boolean isHealthEnabled() { return getBoolean("health.enabled", true); }
    public int getHealthPort() { return getInt("health.port", 9200); }
    public String getHealthBind() { return getString("health.bind", "0.0.0.0"); }

    // Dashboard
    public boolean isDashboardEnabled() { return getBoolean("dashboard.enabled", true); }
    public int getDashboardPort() { return getInt("dashboard.port", 9300); }
    public String getDashboardBind() { return getString("dashboard.bind", "127.0.0.1"); }
    public String getDashboardAuthToken() { return getString("dashboard.auth-token", ""); }
    public List<String> getDashboardAllowedIps() { return getStringList("dashboard.allowed-ips"); }
    public int getDashboardRefreshCritical() { return getInt("dashboard.refresh.critical", 2); }
    public int getDashboardRefreshStandard() { return getInt("dashboard.refresh.standard", 10); }
    public int getDashboardRefreshSlow() { return getInt("dashboard.refresh.slow", 30); }

    // Metrics
    public boolean isMetricsEnabled() { return getBoolean("metrics.enabled", true); }
    public String getMetricsPrefix() { return getString("metrics.prefix", "atlas_"); }

    // Slow Query
    public boolean isSlowQueryEnabled() { return getBoolean("slow-query.enabled", true); }
    public int getSlowQueryThreshold() { return getInt("slow-query.threshold-ms", 100); }
    public int getSlowQueryMaxEntries() { return getInt("slow-query.max-entries", 100); }

    // Audit
    public boolean isAuditEnabled() { return getBoolean("audit.enabled", true); }
    public String getAuditLogDir() { return getString("audit.log-dir", "logs/atlas"); }
    public int getAuditRetentionDays() { return getInt("audit.retention-days", 30); }

    // Canary
    public boolean isCanaryEnabled() { return getBoolean("canary.enabled", true); }
    public int getCanaryDefaultPercentage() { return getInt("canary.default-percentage", 5); }
    public boolean isCanaryAutoRollback() { return getBoolean("canary.auto-rollback", true); }
    public int getCanaryRollbackThreshold() { return getInt("canary.rollback-threshold-errors", 10); }

    // Predictions
    public boolean isPredictionsEnabled() { return getBoolean("predictions.enabled", true); }
    public int getPredictionsHistoryHours() { return getInt("predictions.history-hours", 168); }
    public int getPredictionsForecastMinutes() { return getInt("predictions.forecast-minutes", 60); }

    /**
     * Scaling rule definition.
     */
    public record ScalingRule(
        String name,
        String type,
        String target,
        double scaleUpThreshold,
        double scaleDownThreshold,
        int priority
    ) {}
}
