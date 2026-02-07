/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.kubernetes;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import revtools.org.atlas.config.AtlasConfig;
import revtools.org.atlas.config.HostingMode;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Factory for creating Kubernetes clients with auto-detection of authentication method.
 * Supports in-cluster (ServiceAccount) and out-of-cluster (kubeconfig) authentication.
 */
public class KubernetesClientFactory implements Closeable {

    private static final String SA_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final String SA_CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
    private static final String SA_NAMESPACE_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

    private final AtlasConfig config;
    private final HostingMode hostingMode;
    private final Logger logger;

    private KubernetesClient client;
    private String authMethod;
    private boolean available = false;

    public KubernetesClientFactory(AtlasConfig config, HostingMode hostingMode, Logger logger) {
        this.config = config;
        this.hostingMode = hostingMode;
        this.logger = logger;

        initialize();
    }

    private void initialize() {
        String strategy = config.getKubernetesAuthStrategy();

        try {
            if ("auto".equals(strategy)) {
                // Auto-detect based on environment
                if (hostingMode == HostingMode.KUBERNETES || isInCluster()) {
                    initializeInCluster();
                } else {
                    initializeKubeconfig();
                }
            } else if ("in-cluster".equals(strategy)) {
                initializeInCluster();
            } else if ("kubeconfig".equals(strategy)) {
                initializeKubeconfig();
            } else {
                logger.error("Unknown K8s auth strategy: {}", strategy);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize Kubernetes client: {}", e.getMessage());
            available = false;
        }
    }

    private boolean isInCluster() {
        return Files.exists(Path.of(SA_TOKEN_PATH));
    }

    private void initializeInCluster() {
        logger.info("Initializing Kubernetes client with in-cluster authentication");

        try {
            // Read ServiceAccount credentials
            String token = Files.readString(Path.of(SA_TOKEN_PATH)).trim();
            String namespace = Files.readString(Path.of(SA_NAMESPACE_PATH)).trim();

            // Get K8s API server from environment
            String masterUrl = String.format("https://%s:%s",
                System.getenv("KUBERNETES_SERVICE_HOST"),
                System.getenv("KUBERNETES_SERVICE_PORT")
            );

            Config k8sConfig = new ConfigBuilder()
                .withMasterUrl(masterUrl)
                .withOauthToken(token)
                .withCaCertFile(SA_CA_PATH)
                .withNamespace(namespace)
                .withConnectionTimeout(config.getK8sConnectionTimeout())
                .withRequestTimeout(config.getK8sRequestTimeout())
                .withTrustCerts(false) // Use CA cert
                .build();

            client = new KubernetesClientBuilder()
                .withConfig(k8sConfig)
                .build();

            // Test connection
            client.namespaces().list();

            authMethod = "in-cluster (ServiceAccount)";
            available = true;
            logger.info("Kubernetes client initialized: {} (namespace: {})", authMethod, namespace);

        } catch (Exception e) {
            logger.error("In-cluster authentication failed: {}", e.getMessage());
            available = false;
        }
    }

    private void initializeKubeconfig() {
        logger.info("Initializing Kubernetes client with kubeconfig");

        try {
            String kubeconfigPath = config.getKubeconfigPath();

            Config k8sConfig;
            if (kubeconfigPath != null && !kubeconfigPath.isEmpty()) {
                // Use specified kubeconfig
                k8sConfig = Config.fromKubeconfig(Files.readString(Path.of(kubeconfigPath)));
                authMethod = "kubeconfig (" + kubeconfigPath + ")";
            } else {
                // Use default locations: KUBECONFIG env or ~/.kube/config
                k8sConfig = Config.autoConfigure(null);
                authMethod = "kubeconfig (default)";
            }

            // Apply timeouts
            k8sConfig.setConnectionTimeout(config.getK8sConnectionTimeout());
            k8sConfig.setRequestTimeout(config.getK8sRequestTimeout());

            client = new KubernetesClientBuilder()
                .withConfig(k8sConfig)
                .build();

            // Test connection
            client.namespaces().list();

            available = true;
            logger.info("Kubernetes client initialized: {}", authMethod);

        } catch (Exception e) {
            logger.error("Kubeconfig authentication failed: {}", e.getMessage());
            available = false;
        }
    }

    /**
     * Get the Kubernetes client.
     */
    public KubernetesClient getClient() {
        return client;
    }

    /**
     * Check if client is available and connected.
     */
    public boolean isAvailable() {
        return available && client != null;
    }

    /**
     * Get the authentication method used.
     */
    public String getAuthMethod() {
        return authMethod;
    }

    /**
     * Get the configured namespace.
     */
    public String getNamespace() {
        return config.getKubernetesNamespace();
    }

    @Override
    public void close() {
        if (client != null) {
            try {
                client.close();
                logger.debug("Kubernetes client closed");
            } catch (Exception e) {
                logger.warn("Error closing Kubernetes client: {}", e.getMessage());
            }
        }
    }
}
