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
		logger.GetLogger().Errorf("[DEBUG] [CACHE-KEY-NIL] Task is nil")
		return ""
	}
	// TaskId is unique per task instance (based on CloudSim's cloudletId counter)
	return task.TaskId
}

// GenerateTaskFingerprint creates a pattern-based fingerprint for a task
// NOTE: This is used for RL state features only, NOT for cache lookup
// Excludes TaskId to allow pattern matching (similar tasks share same fingerprint)
func (tcm *TaskCacheManager) GenerateTaskFingerprint(task *pb.Task) string {
	// [DEBUG] Entry point for GenerateTaskFingerprint
	logger.GetLogger().Infof("[DEBUG] [CACHE-FINGERPRINT-ENTRY] GenerateTaskFingerprint called: TaskID=%s", task.TaskId)
	
	if task == nil {
		// [DEBUG] Task is nil
		logger.GetLogger().Errorf("[DEBUG] [CACHE-FINGERPRINT-NIL] Task is nil")
		return ""
	}

	// [DEBUG] Creating fingerprint data
	// Create fingerprint: task_name + task_type + cpu + memory + priority
	// DO NOT include task_id - we want to identify similar tasks, not unique instances
	// DO NOT include execution_time - always 0 for new tasks, doesn't help
	logger.GetLogger().Infof("[DEBUG] [CACHE-FINGERPRINT-DATA] Creating fingerprint data: TaskName=%s, Type=%d, CPU=%d, Mem=%d, Priority=%d",
		task.TaskName, task.TaskType, task.CpuRequirement, task.MemoryRequirement, task.Priority)
	data := fmt.Sprintf("%s_%d_%d_%d_%d",
		task.TaskName,           // Tuple type (e.g., "sensor_data")
		task.TaskType,           // TaskType enum (COMPUTE, IO, etc.)
		task.CpuRequirement,     // CPU units
		task.MemoryRequirement,  // Memory in MB
		task.Priority)           // Task priority (1-10)

	// [DEBUG] Computing hash
	logger.GetLogger().Infof("[DEBUG] [CACHE-FINGERPRINT-HASH-BEFORE] About to compute SHA256 hash")
	hash := sha256.Sum256([]byte(data))
	// [DEBUG] Hash computed
	fingerprint := fmt.Sprintf("%x", hash)[:16] // Use first 16 chars
	logger.GetLogger().Infof("[DEBUG] [CACHE-FINGERPRINT-HASH-AFTER] Hash computed: fingerprint=%s", fingerprint)
	
	// [DEBUG] About to return
	logger.GetLogger().Infof("[DEBUG] [CACHE-FINGERPRINT-EXIT] GenerateTaskFingerprint returning: %s", fingerprint)
	return fingerprint
}

// ProcessTask processes a task and returns cache decision
func (tcm *TaskCacheManager) ProcessTask(task *pb.Task) (bool, string, pb.CacheAction) {
	// [DEBUG] Entry point for ProcessTask
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-ENTRY] ProcessTask called: TaskID=%s", task.TaskId)
	
	if !tcm.config.Enabled {
		// [DEBUG] Cache disabled
		logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-DISABLED] Cache disabled for TaskID=%s", task.TaskId)
		return false, "", pb.CacheAction_CACHE_ACTION_NONE
	}
	// [DEBUG] Cache enabled
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-ENABLED] Cache enabled for TaskID=%s", task.TaskId)

	// [DEBUG] About to acquire lock
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-LOCK-BEFORE] About to acquire lock for TaskID=%s", task.TaskId)
	tcm.mu.Lock()
	// [DEBUG] Lock acquired
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-LOCK-ACQUIRED] Lock acquired")
	defer func() {
		// [DEBUG] About to release lock
		logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-LOCK-RELEASE] Releasing lock")
		tcm.mu.Unlock()
		// [DEBUG] Lock released
		logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-LOCK-RELEASED] Lock released")
	}()

	// Generate cache key (unique per task instance)
	cacheKey := tcm.GenerateCacheKey(task)
	if cacheKey == "" {
		logger.GetLogger().Errorf("[DEBUG] [CACHE-PROCESS-KEY-FAILED] Failed to generate cache key for TaskID=%s", task.TaskId)
		return false, "", pb.CacheAction_CACHE_ACTION_NONE
	}
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-KEY] Generated cache key: %s (TaskID=%s)", cacheKey, task.TaskId)

	// [DEBUG] Update total tasks
	tcm.totalTasks++
	now := time.Now()
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-TOTAL] Total tasks incremented: %d", tcm.totalTasks)

	// [DEBUG] Check for existing entry using cache key (TaskId)
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-ENTRY-CHECK] Checking for existing entry: cacheKey=%s", cacheKey)
	entry, exists := tcm.entries[cacheKey]
	if !exists {
		// [DEBUG] First time seeing this task instance
		logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-NEW] First time seeing task: cacheKey=%s (TaskID=%s)", cacheKey, task.TaskId)
		// First time seeing this task instance
		tcm.entries[cacheKey] = &TaskCacheEntry{
			FirstSeen: now.Unix(),
			SeenCount: 1,
		}
		tcm.cacheMisses++
		// [DEBUG] Cache miss
		logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-MISS] Cache miss: TotalMisses=%d, Action=STORE", tcm.cacheMisses)
		return false, cacheKey, pb.CacheAction_CACHE_ACTION_STORE
	}
	// [DEBUG] Entry exists
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-EXISTS] Entry exists: cacheKey=%s, SeenCount=%d", cacheKey, entry.SeenCount)

	// [DEBUG] Task instance seen before - update SeenCount
	// Task instance seen before - update SeenCount (for this specific task)
	entry.SeenCount++
	tcm.repeatedTasks++
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-UPDATE] Updated entry: SeenCount=%d, RepeatedTasks=%d", entry.SeenCount, tcm.repeatedTasks)

	// [DEBUG] Check cache expiration
	// Check if cache is still valid (check time since FIRST SEEN)
	firstSeenTime := time.Unix(entry.FirstSeen, 0)
	timeSinceFirstSeen := now.Sub(firstSeenTime)
	cacheTTL := time.Duration(tcm.config.CacheTTLHours) * time.Hour
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-EXPIRY-CHECK] Checking expiration: Age=%v, TTL=%v", timeSinceFirstSeen, cacheTTL)
	
	if timeSinceFirstSeen > cacheTTL {
		// [DEBUG] Cache expired
		// Cache expired - invalidate
		logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-EXPIRE] Cache expired: cacheKey=%s, Age=%v, TTL=%v", cacheKey, timeSinceFirstSeen, cacheTTL)
		logger.GetLogger().Infof("[CACHE-EXPIRE] Cache expired for cacheKey %s after %v (TTL=%v)",
			cacheKey, timeSinceFirstSeen, cacheTTL)
		return false, cacheKey, pb.CacheAction_CACHE_ACTION_INVALIDATE
	}
	// [DEBUG] Cache still valid
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-VALID] Cache still valid: cacheKey=%s", cacheKey)

	// [DEBUG] Cache hit
	// Cache hit - use cached result
	tcm.cacheHits++
	logger.GetLogger().Infof("[DEBUG] [CACHE-PROCESS-HIT] Cache hit: TotalHits=%d, Action=USE", tcm.cacheHits)
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

	return map[string]interface{}{
		"total_tasks":         tcm.totalTasks,
		"repeated_tasks":      tcm.repeatedTasks,
		"cache_hits":          tcm.cacheHits,
		"cache_misses":        tcm.cacheMisses,
		"unique_tasks":        len(tcm.entries),
		"repeated_task_ratio": tcm.GetRepeatedTaskRatio(),
		"hit_rate":            tcm.getHitRate(),
	}
}

// getHitRate calculates cache hit rate (internal, use GetHitRate() for external access)
func (tcm *TaskCacheManager) getHitRate() float64 {
	total := tcm.cacheHits + tcm.cacheMisses
	if total == 0 {
		return 0.0
	}
	return float64(tcm.cacheHits) / float64(total)
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
	logger.GetLogger().Debugf("[CACHE-REMOVE] Deleted entry %s (seen %d times, totalTasks: %d, repeatedTasks: %d)",
		cacheKey, entry.SeenCount, tcm.totalTasks, tcm.repeatedTasks)
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
