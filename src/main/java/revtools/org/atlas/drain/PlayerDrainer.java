/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.drain;

import org.slf4j.Logger;
import revtools.org.atlas.bridge.RedisStateStore;
import revtools.org.atlas.bridge.VelotimBridge;
import revtools.org.atlas.config.AtlasConfig;
import revtools.org.atlas.kubernetes.ClusterManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages graceful player migration before pod termination.
 * State machine: ACTIVE -> DRAINING -> DRAINED -> TERMINATED
 */
public class PlayerDrainer {

    private final AtlasConfig config;
    private final ClusterManager clusterManager;
    private final VelotimBridge velotimBridge;
    private final RedisStateStore stateStore;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Atlas-PlayerDrainer");
        t.setDaemon(true);
        return t;
    });

    // Active drain operations
    private final Map<String, DrainState> drainStates = new ConcurrentHashMap<>();

    public PlayerDrainer(AtlasConfig config, ClusterManager clusterManager,
                         VelotimBridge velotimBridge, RedisStateStore stateStore, Logger logger) {
        this.config = config;
        this.clusterManager = clusterManager;
        this.velotimBridge = velotimBridge;
        this.stateStore = stateStore;
        this.logger = logger;
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }

        int interval = config.getDrainCheckInterval();
        scheduler.scheduleAtFixedRate(this::checkDrainProgress, interval, interval, TimeUnit.SECONDS);
        logger.info("Player drainer started (check interval: {}s)", interval);
    }

    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("Player drainer stopped");
    }

    /**
     * Initiate draining of a server (marks it for scale-down).
     */
    public boolean startDrain(String serverId) {
        if (drainStates.containsKey(serverId)) {
            logger.warn("Server {} is already draining", serverId);
            return false;
        }

        DrainState state = new DrainState(
            serverId,
            DrainPhase.DRAINING,
            System.currentTimeMillis(),
            0,
            getPlayerCount(serverId)
        );

        drainStates.put(serverId, state);

        // Tell Velotim to stop routing new players
        if (velotimBridge != null) {
            velotimBridge.markServerDraining(serverId);
        }

        // Record to Redis
        if (stateStore != null) {
            stateStore.recordDrainState(state);
        }

        logger.info("Started draining server: {} ({} players)", serverId, state.initialPlayers);
        return true;
    }

    /**
     * Cancel an active drain operation.
     */
    public boolean cancelDrain(String serverId) {
        DrainState state = drainStates.remove(serverId);
        if (state == null) {
            return false;
        }

        // Tell Velotim to resume routing
        if (velotimBridge != null) {
            velotimBridge.unmarkServerDraining(serverId);
        }

        // Record to Redis
        if (stateStore != null) {
            stateStore.removeDrainState(serverId);
        }

        logger.info("Cancelled drain for server: {}", serverId);
        return true;
    }

    /**
     * Drain all servers (maintenance mode).
     */
    public void drainAll() {
        logger.warn("Initiating drain of ALL servers (maintenance mode)");

        if (velotimBridge != null) {
            for (String serverId : velotimBridge.getActiveServerIds()) {
                startDrain(serverId);
            }
        }
    }

    private void checkDrainProgress() {
        if (!running.get()) {
            return;
        }

        long now = System.currentTimeMillis();
        int timeout = config.getDrainTimeout() * 1000;

        for (Map.Entry<String, DrainState> entry : drainStates.entrySet()) {
            String serverId = entry.getKey();
            DrainState state = entry.getValue();

            int currentPlayers = getPlayerCount(serverId);
            long elapsed = now - state.startTime;

            // Update state
            DrainState updatedState = new DrainState(
                serverId,
                state.phase,
                state.startTime,
                currentPlayers,
                state.initialPlayers
            );

            // Check if drained
            if (currentPlayers == 0) {
                logger.info("Server {} fully drained (took {}s)", serverId, elapsed / 1000);
                completeDrain(serverId);
                continue;
            }

            // Check timeout
            if (elapsed >= timeout) {
                if (config.isDrainForceAfterTimeout()) {
                    logger.warn("Drain timeout for {} - forcing termination ({} players remaining)",
                        serverId, currentPlayers);
                    completeDrain(serverId);
                } else {
                    logger.warn("Drain timeout for {} - waiting ({} players remaining)",
                        serverId, currentPlayers);
                }
                continue;
            }

            // Update state in Redis
            drainStates.put(serverId, updatedState);
            if (stateStore != null) {
                stateStore.recordDrainState(updatedState);
            }

            logger.debug("Drain progress: {} - {} players remaining ({}s elapsed)",
                serverId, currentPlayers, elapsed / 1000);
        }
    }

    private void completeDrain(String serverId) {
        DrainState state = drainStates.remove(serverId);
        if (state == null) return;

        // Tell Velotim to unregister
        if (velotimBridge != null) {
            velotimBridge.unregisterServer(serverId);
        }

        // Delete the pod
        if (clusterManager != null) {
            clusterManager.deletePod(serverId);
        }

        // Clean up Redis
        if (stateStore != null) {
            stateStore.removeDrainState(serverId);
        }

        logger.info("Drain complete - server {} terminated", serverId);
    }

    private int getPlayerCount(String serverId) {
        if (velotimBridge != null) {
            return velotimBridge.getPlayerCount(serverId);
        }
        return 0;
    }

    public Map<String, DrainState> getDrainStates() {
        return new HashMap<>(drainStates);
    }

    public boolean isDraining(String serverId) {
        return drainStates.containsKey(serverId);
    }

    public boolean isRunning() {
        return running.get();
    }

    public enum DrainPhase {
        ACTIVE,      // Normal operation
        DRAINING,    // No new players, waiting for existing to leave
        DRAINED,     // All players gone
        TERMINATED   // Pod deleted
    }

    public record DrainState(
        String serverId,
        DrainPhase phase,
        long startTime,
        int currentPlayers,
        int initialPlayers
    ) {}
}
