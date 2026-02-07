/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.environment;

import revtools.org.atlas.config.HostingMode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Auto-detects the hosting environment to configure Atlas behavior.
 */
public final class EnvironmentDetector {

    private static final String K8S_SA_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final String DOCKER_CGROUP_PATH = "/proc/1/cgroup";

    private EnvironmentDetector() {}

    /**
     * Detect the current hosting environment.
     */
    public static HostingMode detect() {
        // Check for Kubernetes (in-cluster)
        if (isKubernetes()) {
            return HostingMode.KUBERNETES;
        }

        // Check for Pterodactyl
        if (isPterodactyl()) {
            return HostingMode.PTERODACTYL;
        }

        // Check for Docker (but not K8s)
        if (isDocker()) {
            return HostingMode.DOCKER;
        }

        // Check for shared hosting indicators
        if (isSharedHosting()) {
            return HostingMode.SHARED;
        }

        // Default to standalone
        return HostingMode.STANDALONE;
    }

    /**
     * Check if running inside Kubernetes.
     */
    private static boolean isKubernetes() {
        // Check for ServiceAccount token
        if (Files.exists(Path.of(K8S_SA_TOKEN_PATH))) {
            return true;
        }

        // Check for K8s environment variables
        String k8sHost = System.getenv("KUBERNETES_SERVICE_HOST");
        return k8sHost != null && !k8sHost.isEmpty();
    }

    /**
     * Check if running in Pterodactyl panel.
     */
    private static boolean isPterodactyl() {
        // Pterodactyl sets these environment variables
        String pteroUuid = System.getenv("P_SERVER_UUID");
        String pteroLocation = System.getenv("P_SERVER_LOCATION");

        return (pteroUuid != null && !pteroUuid.isEmpty()) ||
               (pteroLocation != null && !pteroLocation.isEmpty());
    }

    /**
     * Check if running in Docker container.
     */
    private static boolean isDocker() {
        // Check for .dockerenv file
        if (new File("/.dockerenv").exists()) {
            return true;
        }

        // Check cgroup for docker/containerd
        try {
            if (Files.exists(Path.of(DOCKER_CGROUP_PATH))) {
                String cgroup = Files.readString(Path.of(DOCKER_CGROUP_PATH));
                return cgroup.contains("docker") || cgroup.contains("containerd");
            }
        } catch (Exception ignored) {}

        // Check for container environment variable
        String container = System.getenv("container");
        return "docker".equals(container) || "podman".equals(container);
    }

    /**
     * Check for shared hosting indicators.
     */
    private static boolean isSharedHosting() {
        // Common shared hosting indicators
        // Limited user permissions
        String user = System.getProperty("user.name");
        if (user != null && (user.startsWith("container") || user.matches("\\d+"))) {
            return true;
        }

        // Check for restricted port range (common in shared hosting)
        String serverPort = System.getenv("SERVER_PORT");
        if (serverPort != null) {
            try {
                int port = Integer.parseInt(serverPort);
                // Shared hosts often use high ports
                if (port > 25000) return true;
            } catch (NumberFormatException ignored) {}
        }

        return false;
    }

    /**
     * Get environment-specific configuration hints.
     */
    public static String getEnvironmentHints(HostingMode mode) {
        return switch (mode) {
            case KUBERNETES -> "Running in Kubernetes - using ServiceAccount authentication";
            case PTERODACTYL -> "Running in Pterodactyl - using environment variables for secrets";
            case DOCKER -> "Running in Docker - binding to 0.0.0.0 for container networking";
            case SHARED -> "Running in shared hosting - K8s features disabled";
            case STANDALONE -> "Running standalone - full features available";
        };
    }
}
