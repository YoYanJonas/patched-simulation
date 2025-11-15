package models

import (
	"crypto/sha256"
	"fmt"
	"sync"
	"time"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
)

// TaskCacheEntry represents a cached task (minimal memory structure)
// NOTE: CacheKey (TaskId) is stored as map key, not duplicated here
type TaskCacheEntry struct {
	FirstSeen int64 `json:"first_seen"` // Unix timestamp (not time.Time to save 16 bytes)
	SeenCount int   `json:"seen_count"` // Task frequency (for this specific task instance)
	// Total: 16 bytes per entry (excluding map key overhead)
}

// TaskCacheManager manages task caching with dual-key system:
// - CacheKey (TaskId): Unique per task instance, used for cache lookup
// - Fingerprint: Pattern-based, used for RL state only
type TaskCacheManager struct {
	mu            sync.RWMutex
	entries       map[string]*TaskCacheEntry // cacheKey (TaskId) -> entry
	config        config.CachingConfig
	totalTasks    int64
	repeatedTasks int64
	cacheHits     int64
	cacheMisses   int64
	
	// Periodic cleanup
	cleanupTicker *time.Ticker
	cleanupStop   chan struct{}
}

// NewTaskCacheManager creates a new cache manager
func NewTaskCacheManager(cfg config.CachingConfig) *TaskCacheManager {
	return &TaskCacheManager{
		entries: make(map[string]*TaskCacheEntry),
		config:  cfg,
	}
}

// GenerateCacheKey generates a unique cache key for a task instance
// Returns TaskId which is guaranteed unique per task instance in both iFogSim and server
func (tcm *TaskCacheManager) GenerateCacheKey(task *pb.Task) string {
	if task == nil {
		return ""
	}
	// TaskId is unique per task instance (based on CloudSim's cloudletId counter)
	return task.TaskId
}

// GenerateTaskFingerprint creates a pattern-based fingerprint for a task
// NOTE: This is used for RL state features only, NOT for cache lookup
// Excludes TaskId to allow pattern matching (similar tasks share same fingerprint)
func (tcm *TaskCacheManager) GenerateTaskFingerprint(task *pb.Task) string {
	
	if task == nil {
		return ""
	}

	// Create fingerprint: task_name + task_type + cpu + memory + priority
	// DO NOT include task_id - we want to identify similar tasks, not unique instances
	// DO NOT include execution_time - always 0 for new tasks, doesn't help
	data := fmt.Sprintf("%s_%d_%d_%d_%d",
		task.TaskName,           // Tuple type (e.g., "sensor_data")
		task.TaskType,           // TaskType enum (COMPUTE, IO, etc.)
		task.CpuRequirement,     // CPU units
		task.MemoryRequirement,  // Memory in MB
		task.Priority)           // Task priority (1-10)

	hash := sha256.Sum256([]byte(data))
	fingerprint := fmt.Sprintf("%x", hash)[:16] // Use first 16 chars
	
	return fingerprint
}

// ProcessTask processes a task and returns cache decision
func (tcm *TaskCacheManager) ProcessTask(task *pb.Task) (bool, string, pb.CacheAction) {
	
	if !tcm.config.Enabled {
		return false, "", pb.CacheAction_CACHE_ACTION_NONE
	}

	tcm.mu.Lock()
	defer func() {
		tcm.mu.Unlock()
	}()

	// Generate cache key (unique per task instance)
	cacheKey := tcm.GenerateCacheKey(task)
	if cacheKey == "" {
		return false, "", pb.CacheAction_CACHE_ACTION_NONE
	}

	tcm.totalTasks++
	now := time.Now()

	entry, exists := tcm.entries[cacheKey]
	if !exists {
		// First time seeing this task instance
		tcm.entries[cacheKey] = &TaskCacheEntry{
			FirstSeen: now.Unix(),
			SeenCount: 1,
		}
		tcm.cacheMisses++
		return false, cacheKey, pb.CacheAction_CACHE_ACTION_STORE
	}

	// Task instance seen before - update SeenCount (for this specific task)
	entry.SeenCount++
	tcm.repeatedTasks++

	// Check if cache is still valid (check time since FIRST SEEN)
	firstSeenTime := time.Unix(entry.FirstSeen, 0)
	timeSinceFirstSeen := now.Sub(firstSeenTime)
	cacheTTL := time.Duration(tcm.config.CacheTTLHours) * time.Hour
	
	if timeSinceFirstSeen > cacheTTL {
		// Cache expired - invalidate
		logger.GetLogger().Infof("[CACHE-EXPIRE] Cache expired for cacheKey %s after %v (TTL=%v)",
			cacheKey, timeSinceFirstSeen, cacheTTL)
		return false, cacheKey, pb.CacheAction_CACHE_ACTION_INVALIDATE
	}

	// Cache hit - use cached result
	tcm.cacheHits++
	return true, cacheKey, pb.CacheAction_CACHE_ACTION_USE
}

// GetRepeatedTaskRatio returns the ratio of repeated tasks
func (tcm *TaskCacheManager) GetRepeatedTaskRatio() float64 {
	tcm.mu.RLock()
	defer tcm.mu.RUnlock()

	if tcm.totalTasks == 0 {
		return 0.0
	}
	return float64(tcm.repeatedTasks) / float64(tcm.totalTasks)
}

// GetCacheStats returns cache statistics
func (tcm *TaskCacheManager) GetCacheStats() map[string]interface{} {
	tcm.mu.RLock()
	defer tcm.mu.RUnlock()

	uniqueTasks := len(tcm.entries)
	return map[string]interface{}{
		"total_tasks":         tcm.totalTasks,
		"repeated_tasks":      tcm.repeatedTasks,
		"cache_hits":          tcm.cacheHits,
		"cache_misses":        tcm.cacheMisses,
		"unique_tasks":        uniqueTasks,
		"repeated_task_ratio": tcm.GetRepeatedTaskRatio(),
		"hit_rate":            tcm.getHitRate(),           // Traditional: hits/(hits+misses)
		"repeat_rate":         tcm.getRepeatRate(),        // New: repeatedTasks/uniqueTasks
		"unique_hit_rate":     tcm.getUniqueHitRate(),     // New: hits/uniqueTasks
	}
}

// getHitRate calculates cache hit rate (internal, use GetHitRate() for external access)
// Traditional hit rate: hits / (hits + misses)
func (tcm *TaskCacheManager) getHitRate() float64 {
	total := tcm.cacheHits + tcm.cacheMisses
	if total == 0 {
		return 0.0
	}
	return float64(tcm.cacheHits) / float64(total)
}

// getRepeatRate calculates repeat rate: what percentage of unique tasks were repeated
// Formula: repeatedTasks / uniqueTasks
func (tcm *TaskCacheManager) getRepeatRate() float64 {
	uniqueTasks := len(tcm.entries)
	if uniqueTasks == 0 {
		return 0.0
	}
	return float64(tcm.repeatedTasks) / float64(uniqueTasks)
}

// getUniqueHitRate calculates unique hit rate: hits per unique task
// Formula: hits / uniqueTasks
func (tcm *TaskCacheManager) getUniqueHitRate() float64 {
	uniqueTasks := len(tcm.entries)
	if uniqueTasks == 0 {
		return 0.0
	}
	return float64(tcm.cacheHits) / float64(uniqueTasks)
}

// GetEntry returns the cache entry for a cache key (TaskId) if it exists
func (tcm *TaskCacheManager) GetEntry(cacheKey string) (*TaskCacheEntry, bool) {
	tcm.mu.RLock()
	defer tcm.mu.RUnlock()
	
	entry, exists := tcm.entries[cacheKey]
	return entry, exists
}

// RemoveEntry deletes an entry by cache key (TaskId) and updates counters
func (tcm *TaskCacheManager) RemoveEntry(cacheKey string) {
	tcm.mu.Lock()
	defer tcm.mu.Unlock()
	
	entry, exists := tcm.entries[cacheKey]
	if !exists {
		return
	}
	
	// Update counters before deleting
	tcm.totalTasks -= int64(entry.SeenCount)
	tcm.repeatedTasks -= int64(entry.SeenCount - 1)
	if tcm.repeatedTasks < 0 {
		tcm.repeatedTasks = 0
	}
	
	delete(tcm.entries, cacheKey)
}

// GetHitRate returns cache hit rate (0.0-1.0)
func (tcm *TaskCacheManager) GetHitRate() float64 {
	tcm.mu.RLock()
	defer tcm.mu.RUnlock()
	return tcm.getHitRate()
}

// Start starts periodic cleanup goroutine (100ms interval, same as queue resorting)
// NOTE: Uses real-time (not simulation time) - see TIME_MANAGEMENT_ANALYSIS.md
func (tcm *TaskCacheManager) Start(cleanupIntervalMs int) {
	tcm.mu.Lock()
	defer tcm.mu.Unlock()
	
	if tcm.cleanupTicker != nil {
		return // Already started
	}
	
	cleanupInterval := time.Duration(cleanupIntervalMs) * time.Millisecond
	tcm.cleanupTicker = time.NewTicker(cleanupInterval)
	tcm.cleanupStop = make(chan struct{})
	
	go func() {
		for {
			select {
			case <-tcm.cleanupTicker.C:
				tcm.CleanupOldEntries() // Runs every cleanupIntervalMs
			case <-tcm.cleanupStop:
				return
			}
		}
	}()
	
	logger.GetLogger().Infof("[CACHE-START] Periodic cleanup started with interval: %v", cleanupInterval)
}

// Stop stops periodic cleanup goroutine
func (tcm *TaskCacheManager) Stop() {
	tcm.mu.Lock()
	defer tcm.mu.Unlock()
	
	if tcm.cleanupTicker != nil {
		tcm.cleanupTicker.Stop()
		tcm.cleanupTicker = nil
	}
	if tcm.cleanupStop != nil {
		close(tcm.cleanupStop)
		tcm.cleanupStop = nil
	}
	
	logger.GetLogger().Info("[CACHE-STOP] Periodic cleanup stopped")
}

// CleanupOldEntries removes expired cache entries (1.1 × TTL threshold)
// Only runs if approaching memory limit (len > MaxTrackedTasks * 0.9)
func (tcm *TaskCacheManager) CleanupOldEntries() {
	tcm.mu.Lock()
	defer tcm.mu.Unlock()

	// Only run if approaching memory limit
	if len(tcm.entries) <= tcm.config.MaxTrackedTasks*90/100 {
		return
	}

	now := time.Now()
	cacheTTL := time.Duration(tcm.config.CacheTTLHours) * time.Hour
	cutoffAge := time.Duration(float64(cacheTTL) * 1.1) // 1.1 × TTL

	deletedCount := 0
	for cacheKey, entry := range tcm.entries {
		firstSeen := time.Unix(entry.FirstSeen, 0)
		age := now.Sub(firstSeen)

		if age > cutoffAge {
			// Update counters before deleting
			tcm.totalTasks -= int64(entry.SeenCount)
			tcm.repeatedTasks -= int64(entry.SeenCount - 1)
			if tcm.repeatedTasks < 0 {
				tcm.repeatedTasks = 0
			}

			delete(tcm.entries, cacheKey)
			deletedCount++
		}
	}

	if deletedCount > 0 {
		logger.GetLogger().Infof("[CACHE-CLEANUP] Deleted %d expired entries (age > %.1f×TTL)",
			deletedCount, 1.1)
	}
}
