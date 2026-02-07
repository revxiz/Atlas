/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.config;

/**
 * Hosting environment modes with different capabilities.
 */
public enum HostingMode {
    /**
     * Full control - all ports available, full K8s access
     */
    STANDALONE,

    /**
     * Limited ports - K8s features disabled, local scheduling only
     */
    SHARED,

    /**
     * Pterodactyl panel - uses environment variables, panel API for power
     */
    PTERODACTYL,

    /**
     * Docker container - binds to 0.0.0.0, container-aware
     */
    DOCKER,

    /**
     * Kubernetes pod - in-cluster auth, full orchestration
     */
    KUBERNETES
}
