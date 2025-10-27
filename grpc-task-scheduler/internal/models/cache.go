package models

import (
	"crypto/sha256"
	"fmt"
	"sync"
	"time"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/config"
)

// TaskCacheEntry represents a cached task fingerprint
type TaskCacheEntry struct {
	TaskFingerprint string         `json:"task_fingerprint"`
	FirstSeen       time.Time      `json:"first_seen"`
	LastSeen        time.Time      `json:"last_seen"`
	SeenCount       int            `json:"seen_count"`
	LastAction      pb.CacheAction `json:"last_action"`
}

// TaskCacheManager manages task fingerprinting and cache decisions
type TaskCacheManager struct {
	mu            sync.RWMutex
	entries       map[string]*TaskCacheEntry // fingerprint -> entry
	config        config.CachingConfig
	totalTasks    int64
	repeatedTasks int64
	cacheHits     int64
	cacheMisses   int64
}

// NewTaskCacheManager creates a new cache manager
func NewTaskCacheManager(cfg config.CachingConfig) *TaskCacheManager {
	return &TaskCacheManager{
		entries: make(map[string]*TaskCacheEntry),
		config:  cfg,
	}
}

// GenerateTaskFingerprint creates a unique fingerprint for a task
func (tcm *TaskCacheManager) GenerateTaskFingerprint(task *pb.Task) string {
	if task == nil {
		return ""
	}

	// Create fingerprint: task_type + cpu + memory + execution_time
	data := fmt.Sprintf("%d_%d_%d_%d",
		task.TaskType,
		task.CpuRequirement,
		task.MemoryRequirement,
		task.ExecutionTime)

	hash := sha256.Sum256([]byte(data))
	return fmt.Sprintf("%x", hash)[:16] // Use first 16 chars
}

// ProcessTask processes a task and returns cache decision
func (tcm *TaskCacheManager) ProcessTask(task *pb.Task) (bool, string, pb.CacheAction) {
	if !tcm.config.Enabled {
		return false, "", pb.CacheAction_CACHE_ACTION_NONE
	}

	tcm.mu.Lock()
	defer tcm.mu.Unlock()

	fingerprint := tcm.GenerateTaskFingerprint(task)
	if fingerprint == "" {
		return false, "", pb.CacheAction_CACHE_ACTION_NONE
	}

	tcm.totalTasks++
	now := time.Now()

	entry, exists := tcm.entries[fingerprint]
	if !exists {
		// First time seeing this task
		tcm.entries[fingerprint] = &TaskCacheEntry{
			TaskFingerprint: fingerprint,
			FirstSeen:       now,
			LastSeen:        now,
			SeenCount:       1,
			LastAction:      pb.CacheAction_CACHE_ACTION_NONE,
		}
		tcm.cacheMisses++
		return false, fingerprint, pb.CacheAction_CACHE_ACTION_STORE
	}

	// Task seen before
	entry.LastSeen = now
	entry.SeenCount++
	tcm.repeatedTasks++

	// Check if cache is still valid (simple time-based invalidation)
	timeSinceLastSeen := now.Sub(entry.LastSeen)
	if timeSinceLastSeen > time.Duration(tcm.config.CacheTTLHours)*time.Hour {
		// Cache expired - invalidate
		entry.LastAction = pb.CacheAction_CACHE_ACTION_INVALIDATE
		return false, fingerprint, pb.CacheAction_CACHE_ACTION_INVALIDATE
	}

	// Cache hit - use cached result
	tcm.cacheHits++
	entry.LastAction = pb.CacheAction_CACHE_ACTION_USE
	return true, fingerprint, pb.CacheAction_CACHE_ACTION_USE
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

// getHitRate calculates cache hit rate
func (tcm *TaskCacheManager) getHitRate() float64 {
	total := tcm.cacheHits + tcm.cacheMisses
	if total == 0 {
		return 0.0
	}
	return float64(tcm.cacheHits) / float64(total)
}

// CleanupOldEntries removes old cache entries (simple LRU)
func (tcm *TaskCacheManager) CleanupOldEntries() {
	tcm.mu.Lock()
	defer tcm.mu.Unlock()

	if len(tcm.entries) <= tcm.config.MaxTrackedTasks {
		return
	}

	// Simple cleanup: remove oldest entries
	cutoff := time.Now().Add(-time.Duration(tcm.config.CacheTTLHours) * time.Hour * 2)

	for fingerprint, entry := range tcm.entries {
		if entry.LastSeen.Before(cutoff) {
			delete(tcm.entries, fingerprint)
		}
	}
}
