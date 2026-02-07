/*
 * Atlas - Kubernetes Orchestration for Minecraft Networks
 * Copyright (c) 2026 xiz
 */
package revtools.org.atlas.metrics;

import org.slf4j.Logger;
import revtools.org.atlas.bridge.RedisStateStore;
import revtools.org.atlas.config.AtlasConfig;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors Redis/database query performance and logs slow queries.
 */
public class SlowQueryMonitor {

    private final AtlasConfig config;
    private final RedisStateStore stateStore;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Atlas-SlowQueryMonitor");
        t.setDaemon(true);
        return t;
    });

    // In-memory slow query log
    private final List<SlowQuery> slowQueries = Collections.synchronizedList(
        new LinkedList<>() {
            @Override
            public boolean add(SlowQuery query) {
                if (size() >= config.getSlowQueryMaxEntries()) removeFirst();
                return super.add(query);
            }
        }
    );

    // Query timing
    private final Map<String, Long> queryStartTimes = new ConcurrentHashMap<>();
    private final Map<String, QueryStats> queryStats = new ConcurrentHashMap<>();

    public SlowQueryMonitor(AtlasConfig config, RedisStateStore stateStore, Logger logger) {
        this.config = config;
        this.stateStore = stateStore;
        this.logger = logger;
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }

        // Periodic cleanup and stats aggregation
        scheduler.scheduleAtFixedRate(this::aggregateStats, 60, 60, TimeUnit.SECONDS);
        logger.info("Slow query monitor started (threshold: {}ms)", config.getSlowQueryThreshold());
    }

    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("Slow query monitor stopped");
    }

    /**
     * Start timing a query.
     */
    public String startQuery(String queryType, String query) {
        String queryId = UUID.randomUUID().toString().substring(0, 8);
        queryStartTimes.put(queryId, System.currentTimeMillis());
        return queryId;
    }

    /**
     * End timing a query and check if it was slow.
     */
    public void endQuery(String queryId, String queryType, String query) {
        Long startTime = queryStartTimes.remove(queryId);
        if (startTime == null) return;

        long duration = System.currentTimeMillis() - startTime;

        // Update stats
        queryStats.compute(queryType, (k, v) -> {
            if (v == null) v = new QueryStats(queryType);
            v.recordQuery(duration);
            return v;
        });

        // Check if slow
        if (duration >= config.getSlowQueryThreshold()) {
            SlowQuery slow = new SlowQuery(
                System.currentTimeMillis(),
                queryType,
                query.length() > 100 ? query.substring(0, 100) + "..." : query,
                duration
            );

            slowQueries.add(slow);

            if (stateStore != null) {
                stateStore.recordSlowQuery(query, duration);
            }

            logger.warn("Slow query detected: {} ({}ms) - {}", queryType, duration,
                query.length() > 50 ? query.substring(0, 50) + "..." : query);
        }
    }

    /**
     * Wrap a Redis operation with timing.
     */
    public <T> T timedRedisOp(String operation, java.util.function.Supplier<T> supplier) {
        String queryId = startQuery("redis", operation);
        try {
            return supplier.get();
        } finally {
            endQuery(queryId, "redis", operation);
        }
    }

    /**
     * Get recent slow queries.
     */
    public List<SlowQuery> getSlowQueries() {
        return new ArrayList<>(slowQueries);
    }

    /**
     * Get top N slowest queries.
     */
    public List<SlowQuery> getTopSlowest(int n) {
        return slowQueries.stream()
            .sorted(Comparator.comparingLong(SlowQuery::durationMs).reversed())
            .limit(n)
            .toList();
    }

    /**
     * Get query statistics by type.
     */
    public Map<String, QueryStats> getQueryStats() {
        return new HashMap<>(queryStats);
    }

    private void aggregateStats() {
        // Clean up old query starts (leaked)
        long cutoff = System.currentTimeMillis() - 60000;
        queryStartTimes.entrySet().removeIf(e -> e.getValue() < cutoff);

        // Log stats summary
        if (!queryStats.isEmpty()) {
            logger.debug("Query stats summary:");
            for (QueryStats stats : queryStats.values()) {
                logger.debug("  {}: count={}, avg={}ms, max={}ms",
                    stats.queryType, stats.count, stats.avgDuration(), stats.maxDuration);
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Slow query record.
     */
    public record SlowQuery(
        long timestamp,
        String queryType,
        String query,
        long durationMs
    ) {}

    /**
     * Query statistics tracker.
     */
    public static class QueryStats {
        private final String queryType;
        private long count = 0;
        private long totalDuration = 0;
        private long maxDuration = 0;
        private long minDuration = Long.MAX_VALUE;

        public QueryStats(String queryType) {
            this.queryType = queryType;
        }

        public synchronized void recordQuery(long duration) {
            count++;
            totalDuration += duration;
            maxDuration = Math.max(maxDuration, duration);
            minDuration = Math.min(minDuration, duration);
        }

        public long getCount() { return count; }
        public long getMaxDuration() { return maxDuration; }
        public long getMinDuration() { return minDuration == Long.MAX_VALUE ? 0 : minDuration; }
        public long avgDuration() { return count > 0 ? totalDuration / count : 0; }
        public String getQueryType() { return queryType; }
    }
}
