/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import revtools.org.atlas.drain.PlayerDrainer.DrainState;
import revtools.org.atlas.drain.PlayerDrainer.DrainPhase;
import revtools.org.atlas.scaling.AutoScaler.ScalingDecision;
import revtools.org.atlas.scaling.AutoScaler.ServerMetrics;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis state store for Atlas data persistence.
 * Shares Redis with Velotim for unified state management.
 */
public class RedisStateStore implements Closeable {

    private final String host;
    private final int port;
    private final String prefix;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().create();

    private JedisPool jedisPool;
    private volatile boolean connected = false;

    // Local cache for Redis failure resilience
    private final Map<String, ServerMetrics> metricsCache = new ConcurrentHashMap<>();

    public RedisStateStore(String host, int port, String username, String password,
                           String prefix, Logger logger) {
        this.host = host;
        this.port = port;
        this.prefix = prefix;
        this.logger = logger;

        initialize(username, password);
    }

    private void initialize(String username, String password) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);

        try {
            if (password != null && !password.isEmpty()) {
                if (username != null && !username.isEmpty()) {
                    jedisPool = new JedisPool(poolConfig, host, port, 2000, username, password);
                } else {
                    jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
                }
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, 2000);
            }

            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                connected = true;
            }
        } catch (Exception e) {
            logger.error("Failed to connect to Redis: {}", e.getMessage());
            connected = false;
        }
    }

    public boolean isConnected() {
        return connected && jedisPool != null && !jedisPool.isClosed();
    }

    // Scaling decisions
    public void recordScalingDecision(ScalingDecision decision) {
        if (!isConnected()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            String key = prefix + "scaling:history";
            String json = gson.toJson(decision);

            jedis.lpush(key, json);
            jedis.ltrim(key, 0, 99); // Keep last 100

            // Also publish for real-time updates
            jedis.publish(prefix + "events:scaling", json);
        } catch (Exception e) {
            logger.warn("Failed to record scaling decision: {}", e.getMessage());
        }
    }

    public List<ScalingDecision> getScalingHistory(int limit) {
        if (!isConnected()) return List.of();

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> items = jedis.lrange(prefix + "scaling:history", 0, limit - 1);
            return items.stream()
                .map(json -> gson.fromJson(json, ScalingDecision.class))
                .toList();
        } catch (Exception e) {
            logger.warn("Failed to get scaling history: {}", e.getMessage());
            return List.of();
        }
    }

    // Drain states
    public void recordDrainState(DrainState state) {
        if (!isConnected()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            String key = prefix + "drain:" + state.serverId();
            jedis.hset(key, Map.of(
                "phase", state.phase().name(),
                "startTime", String.valueOf(state.startTime()),
                "currentPlayers", String.valueOf(state.currentPlayers()),
                "initialPlayers", String.valueOf(state.initialPlayers())
            ));
            jedis.expire(key, 3600); // 1 hour TTL
        } catch (Exception e) {
            logger.warn("Failed to record drain state: {}", e.getMessage());
        }
    }

    public void removeDrainState(String serverId) {
        if (!isConnected()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(prefix + "drain:" + serverId);
        } catch (Exception e) {
            logger.warn("Failed to remove drain state: {}", e.getMessage());
        }
    }

    public Map<String, DrainState> getDrainStates() {
        if (!isConnected()) return Map.of();

        Map<String, DrainState> states = new HashMap<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys(prefix + "drain:*");
            for (String key : keys) {
                String serverId = key.substring((prefix + "drain:").length());
                Map<String, String> data = jedis.hgetAll(key);

                states.put(serverId, new DrainState(
                    serverId,
                    DrainPhase.valueOf(data.getOrDefault("phase", "ACTIVE")),
                    Long.parseLong(data.getOrDefault("startTime", "0")),
                    Integer.parseInt(data.getOrDefault("currentPlayers", "0")),
                    Integer.parseInt(data.getOrDefault("initialPlayers", "0"))
                ));
            }
        } catch (Exception e) {
            logger.warn("Failed to get drain states: {}", e.getMessage());
        }
        return states;
    }

    // Server metrics (from Velotim)
    public Map<String, ServerMetrics> getServerMetrics() {
        if (!isConnected()) return metricsCache;

        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, ServerMetrics> metrics = new HashMap<>();

            // Query Velotim's server discovery keys
            Set<String> keys = jedis.keys("velotim:servers:*");
            for (String key : keys) {
                Map<String, String> data = jedis.hgetAll(key);
                String serverId = key.substring("velotim:servers:".length());

                ServerMetrics m = new ServerMetrics(
                    serverId,
                    Integer.parseInt(data.getOrDefault("players", "0")),
                    Double.parseDouble(data.getOrDefault("tps", "20.0")),
                    Double.parseDouble(data.getOrDefault("mspt", "0"))
                );
                metrics.put(serverId, m);
                metricsCache.put(serverId, m);
            }

            return metrics;
        } catch (Exception e) {
            logger.warn("Failed to get server metrics: {}", e.getMessage());
            return metricsCache;
        }
    }

    // Pod events
    public void recordPodEvent(String action, String podName, String phase) {
        if (!isConnected()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> event = Map.of(
                "action", action,
                "pod", podName,
                "phase", phase,
                "timestamp", String.valueOf(System.currentTimeMillis())
            );

            String key = prefix + "pod:events";
            jedis.lpush(key, gson.toJson(event));
            jedis.ltrim(key, 0, 199); // Keep last 200

            jedis.publish(prefix + "events:pod", gson.toJson(event));
        } catch (Exception e) {
            logger.warn("Failed to record pod event: {}", e.getMessage());
        }
    }

    // Slow queries
    public void recordSlowQuery(String query, long durationMs) {
        if (!isConnected()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> record = Map.of(
                "query", query.length() > 100 ? query.substring(0, 100) + "..." : query,
                "duration", String.valueOf(durationMs),
                "timestamp", String.valueOf(System.currentTimeMillis())
            );

            String key = prefix + "slowqueries";
            jedis.lpush(key, gson.toJson(record));
            jedis.ltrim(key, 0, 99);
        } catch (Exception e) {
            logger.warn("Failed to record slow query: {}", e.getMessage());
        }
    }

    public List<Map<String, String>> getSlowQueries(int limit) {
        if (!isConnected()) return List.of();

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> items = jedis.lrange(prefix + "slowqueries", 0, limit - 1);
            return items.stream()
                .map(json -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> map = gson.fromJson(json, Map.class);
                    return map;
                })
                .toList();
        } catch (Exception e) {
            logger.warn("Failed to get slow queries: {}", e.getMessage());
            return List.of();
        }
    }

    // Audit log
    public void recordAuditEvent(String action, String actor, String details) {
        if (!isConnected()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> event = Map.of(
                "action", action,
                "actor", actor,
                "details", details,
                "timestamp", String.valueOf(System.currentTimeMillis())
            );

            String key = prefix + "audit";
            jedis.lpush(key, gson.toJson(event));
            jedis.ltrim(key, 0, 999); // Keep last 1000
        } catch (Exception e) {
            logger.warn("Failed to record audit event: {}", e.getMessage());
        }
    }

    public List<Map<String, String>> getAuditLog(int limit) {
        if (!isConnected()) return List.of();

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> items = jedis.lrange(prefix + "audit", 0, limit - 1);
            return items.stream()
                .map(json -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> map = gson.fromJson(json, Map.class);
                    return map;
                })
                .toList();
        } catch (Exception e) {
            logger.warn("Failed to get audit log: {}", e.getMessage());
            return List.of();
        }
    }

    // Dangerous action tokens
    public String generateDangerousActionToken(String action) {
        if (!isConnected()) return null;

        String token = UUID.randomUUID().toString().substring(0, 8);
        try (Jedis jedis = jedisPool.getResource()) {
            String key = prefix + "dangerous:token:" + token;
            jedis.hset(key, Map.of(
                "action", action,
                "created", String.valueOf(System.currentTimeMillis())
            ));
            jedis.expire(key, 30); // 30 second expiry
            return token;
        } catch (Exception e) {
            logger.warn("Failed to generate dangerous action token: {}", e.getMessage());
            return null;
        }
    }

    public boolean validateAndConsumeToken(String token, String expectedAction) {
        if (!isConnected()) return false;

        try (Jedis jedis = jedisPool.getResource()) {
            String key = prefix + "dangerous:token:" + token;
            Map<String, String> data = jedis.hgetAll(key);

            if (data.isEmpty()) return false;
            if (!expectedAction.equals(data.get("action"))) return false;

            jedis.del(key); // Consume token
            return true;
        } catch (Exception e) {
            logger.warn("Failed to validate token: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
        metricsCache.clear();
    }
}
