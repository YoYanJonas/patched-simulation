package org.patch.utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages task caching based on scheduler decisions
 * Handles cache hits, misses, and invalidation as determined by scheduler
 */
public class TaskCacheManager {
    private static final Logger logger = Logger.getLogger(TaskCacheManager.class.getName());

    // Cache storage
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();

    // Statistics
    private int cacheHits = 0;
    private int cacheMisses = 0;
    private int cacheInvalidations = 0;
    private int cacheStores = 0;

    // Configuration
    private final long cacheTTLMs;
    private final int maxCacheSize;

    /**
     * Constructor with default configuration
     */
    public TaskCacheManager() {
        this.cacheTTLMs = 300000; // 5 minutes default TTL
        this.maxCacheSize = 1000; // Maximum 1000 cached tasks
    }

    /**
     * Constructor with custom configuration
     * 
     * @param cacheTTLMs   Cache time-to-live in milliseconds
     * @param maxCacheSize Maximum number of cached tasks
     */
    public TaskCacheManager(long cacheTTLMs, int maxCacheSize) {
        this.cacheTTLMs = cacheTTLMs;
        this.maxCacheSize = maxCacheSize;
    }

    /**
     * Check cache status for a task
     * 
     * @param taskId The task ID to check
     * @return Cache result indicating hit/miss/invalid
     */
    public CacheResult checkCache(String taskId) {
        CacheEntry entry = cache.get(taskId);

        if (entry == null) {
            cacheMisses++;
            logger.fine("Cache miss for task " + taskId);
            return CacheResult.MISS;
        }

        // Check if cache entry is still valid
        long currentTime = System.currentTimeMillis();
        long entryTime = cacheTimestamps.getOrDefault(taskId, 0L);

        if (currentTime - entryTime > cacheTTLMs) {
            // Cache expired - invalidate
            cache.remove(taskId);
            cacheTimestamps.remove(taskId);
            cacheInvalidations++;
            logger.fine("Cache invalidated for task " + taskId + " (expired)");
            return CacheResult.HIT_INVALID;
        }

        // Cache hit - valid
        cacheHits++;
        logger.fine("Cache hit for task " + taskId);
        return CacheResult.HIT_VALID;
    }

    /**
     * Store a task result in cache
     * 
     * @param taskId The task ID
     * @param result The task result to cache
     */
    public void storeInCache(String taskId, Object result) {
        // Check cache size limit
        if (cache.size() >= maxCacheSize) {
            cleanupOldEntries();
        }

        cache.put(taskId, new CacheEntry(result, System.currentTimeMillis()));
        cacheTimestamps.put(taskId, System.currentTimeMillis());
        cacheStores++;

        logger.fine("Task " + taskId + " result stored in cache");
    }

    /**
     * Invalidate a cache entry
     * 
     * @param taskId The task ID to invalidate
     */
    public void invalidateCache(String taskId) {
        cache.remove(taskId);
        cacheTimestamps.remove(taskId);
        cacheInvalidations++;

        logger.fine("Cache invalidated for task " + taskId);
    }

    /**
     * Get a cached result
     * 
     * @param taskId The task ID
     * @return The cached result, or null if not found
     */
    public Object getCachedResult(String taskId) {
        CacheEntry entry = cache.get(taskId);
        if (entry != null) {
            return entry.getResult();
        }
        return null;
    }

    /**
     * Clear all cache entries
     */
    public void clearCache() {
        cache.clear();
        cacheTimestamps.clear();
        logger.info("Cache cleared");
    }

    /**
     * Clean up old cache entries (LRU-like cleanup)
     */
    private void cleanupOldEntries() {
        if (cache.size() < maxCacheSize) {
            return;
        }

        // Remove oldest entries (simple cleanup)
        long currentTime = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, Long> entry : cacheTimestamps.entrySet()) {
            if (currentTime - entry.getValue() > cacheTTLMs) {
                toRemove.add(entry.getKey());
            }
        }

        // Remove expired entries
        for (String taskId : toRemove) {
            cache.remove(taskId);
            cacheTimestamps.remove(taskId);
        }

        // If still over limit, remove oldest entries
        if (cache.size() >= maxCacheSize) {
            List<Map.Entry<String, Long>> sortedEntries = new ArrayList<>(cacheTimestamps.entrySet());
            sortedEntries.sort(Map.Entry.comparingByValue());

            int toRemoveCount = cache.size() - maxCacheSize + 100; // Remove 100 extra for buffer
            for (int i = 0; i < toRemoveCount && i < sortedEntries.size(); i++) {
                String taskId = sortedEntries.get(i).getKey();
                cache.remove(taskId);
                cacheTimestamps.remove(taskId);
            }
        }

        logger.info("Cache cleanup completed, removed " + toRemove.size() + " entries");
    }

    /**
     * Get cache statistics
     * 
     * @return Map of cache statistics
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", cache.size());
        stats.put("cacheHits", cacheHits);
        stats.put("cacheMisses", cacheMisses);
        stats.put("cacheInvalidations", cacheInvalidations);
        stats.put("cacheStores", cacheStores);

        // Calculate hit rate
        int totalRequests = cacheHits + cacheMisses;
        double hitRate = totalRequests > 0 ? (double) cacheHits / totalRequests : 0.0;
        stats.put("hitRate", hitRate);

        // Calculate invalidation rate
        int totalCacheOperations = cacheHits + cacheStores;
        double invalidationRate = totalCacheOperations > 0 ? (double) cacheInvalidations / totalCacheOperations : 0.0;
        stats.put("invalidationRate", invalidationRate);

        return stats;
    }

    /**
     * Cache result enumeration
     */
    public enum CacheResult {
        HIT_VALID, // Cache hit and valid
        HIT_INVALID, // Cache hit but invalid/expired
        MISS // Cache miss
    }

    /**
     * Cache entry class
     */
    private static class CacheEntry {
        private final Object result;
        private final long timestamp;

        public CacheEntry(Object result, long timestamp) {
            this.result = result;
            this.timestamp = timestamp;
        }

        public Object getResult() {
            return result;
        }

    }
}
