package rl

import (
	"fmt"
	"time"

	pb "scheduler-grpc-server/api/proto"
)

// CacheStateFeatures represents discrete state features for cache RL agent
type CacheStateFeatures struct {
	// Task identity (discrete - 8 char prefix)
	TaskFingerprintPrefix string // fingerprint[:8] - 8 chars

	// System state (fuzzy categories)
	SystemLoadCategory    string // "low", "medium", "high" (from node manager)
	QueueLengthCategory   string // "short", "medium", "long" (from QueueContext)

	// Cache context (fuzzy categories)
	CacheHitRateCategory  string // "poor", "fair", "good", "excellent" (from cache manager)
	TaskFrequencyCategory string // "none", "rare", "occasional", "frequent", "very_frequent" (from entry.SeenCount)

	// Cache entry state
	CacheExists    bool   // Does entry exist?
	CacheAgeCategory string // "fresh", "recent", "old", "expired", "none" (calculated from FirstSeen)

	// Cached state key
	cachedStateKey string
	keyDirty       bool
}

// GetStateKey generates a discrete state key from all features
func (csf *CacheStateFeatures) GetStateKey() string {
	if !csf.keyDirty && csf.cachedStateKey != "" {
		return csf.cachedStateKey
	}

	// Discrete key using categories only
	csf.cachedStateKey = fmt.Sprintf("fp:%s_load:%s_q:%s_hit:%s_freq:%s_exists:%t_age:%s",
		csf.TaskFingerprintPrefix,    // 8 chars
		csf.SystemLoadCategory,       // "low"/"medium"/"high"
		csf.QueueLengthCategory,      // "short"/"medium"/"long"
		csf.CacheHitRateCategory,     // "poor"/"fair"/"good"/"excellent"
		csf.TaskFrequencyCategory,     // "none"/"rare"/"occasional"/"frequent"/"very_frequent"
		csf.CacheExists,              // true/false
		csf.CacheAgeCategory)         // "fresh"/"recent"/"old"/"expired"/"none"

	csf.keyDirty = false
	return csf.cachedStateKey
}

// Note: Interfaces are kept simple - we'll use type assertions in ExtractCacheStateFeatures
// or pass the actual types directly

// ExtractCacheStateFeatures extracts cache state features for RL agent
// entryFirstSeen: Unix timestamp (int64) of when entry was first seen, or 0 if no entry
// entrySeenCount: Number of times task was seen, or 0 if no entry
func ExtractCacheStateFeatures(
	task *pb.Task,
	fingerprint string,
	queueContext *pb.QueueContext,
	entryFirstSeen int64, // Unix timestamp, 0 if no entry
	entrySeenCount int,   // Seen count, 0 if no entry
	hitRate float64,
	systemLoad float64,
	cacheTTLHours int,
) *CacheStateFeatures {
	// [DEBUG] Entry point for ExtractCacheStateFeatures
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-ENTRY] ExtractCacheStateFeatures called: TaskID=%s, Fingerprint=%s, HitRate=%.3f, Load=%.3f\n",
		task.TaskId, fingerprint, hitRate, systemLoad)
	
	state := &CacheStateFeatures{
		keyDirty: true,
	}
	// [DEBUG] State struct created
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-CREATE] CacheStateFeatures struct created\n")

	// [DEBUG] 1. TaskFingerprintPrefix
	// 1. TaskFingerprintPrefix (first 8 chars)
	if len(fingerprint) >= 8 {
		state.TaskFingerprintPrefix = fingerprint[:8]
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-FP] Using first 8 chars: %s\n", state.TaskFingerprintPrefix)
	} else {
		state.TaskFingerprintPrefix = fingerprint
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-FP] Using full fingerprint: %s\n", state.TaskFingerprintPrefix)
	}

	// [DEBUG] 2. SystemLoadCategory
	// 2. SystemLoadCategory (from node manager)
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-LOAD] Categorizing system load: %.3f\n", systemLoad)
	state.SystemLoadCategory = categorizeSystemLoad(systemLoad)
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-LOAD-DONE] SystemLoadCategory: %s\n", state.SystemLoadCategory)

	// [DEBUG] 3. QueueLengthCategory
	// 3. QueueLengthCategory (from QueueContext)
	totalQueueSize := int32(0)
	if queueContext != nil {
		totalQueueSize = queueContext.TotalQueueSize
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-QUEUE] QueueContext provided: TotalQueueSize=%d\n", totalQueueSize)
	} else {
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-QUEUE] QueueContext is nil, using default: 0\n")
	}
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-QUEUE-CAT] Categorizing queue length: %d\n", totalQueueSize)
	state.QueueLengthCategory = categorizeQueueLength(int(totalQueueSize))
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-QUEUE-CAT-DONE] QueueLengthCategory: %s\n", state.QueueLengthCategory)

	// [DEBUG] 4. CacheHitRateCategory
	// 4. CacheHitRateCategory (from cache manager)
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-HITRATE] Categorizing cache hit rate: %.3f\n", hitRate)
	state.CacheHitRateCategory = categorizeCacheHitRate(hitRate)
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-HITRATE-DONE] CacheHitRateCategory: %s\n", state.CacheHitRateCategory)

	// [DEBUG] 5-7. Cache entry state
	// 5. TaskFrequencyCategory (from entrySeenCount)
	// 6. CacheExists
	// 7. CacheAgeCategory
	if entryFirstSeen > 0 {
		// [DEBUG] Cache entry exists
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-ENTRY] Cache entry exists: FirstSeen=%d, SeenCount=%d\n", entryFirstSeen, entrySeenCount)
		state.CacheExists = true
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-FREQ] Categorizing task frequency: SeenCount=%d\n", entrySeenCount)
		state.TaskFrequencyCategory = categorizeTaskFrequency(entrySeenCount)
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-FREQ-DONE] TaskFrequencyCategory: %s\n", state.TaskFrequencyCategory)
		
		// [DEBUG] Calculate cache age
		// Calculate cache age from FirstSeen
		now := time.Now()
		firstSeen := time.Unix(entryFirstSeen, 0)
		age := now.Sub(firstSeen)
		cacheTTL := time.Duration(cacheTTLHours) * time.Hour
		ttlRatio := float64(age) / float64(cacheTTL)
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-AGE] Cache age: Age=%v, TTL=%v, Ratio=%.3f\n", age, cacheTTL, ttlRatio)
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-AGE-CAT] Categorizing cache age: Ratio=%.3f\n", ttlRatio)
		state.CacheAgeCategory = CategorizeCacheAge(ttlRatio)
		// [DEBUG] Cache age category set
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-AGE-CAT-DONE] CacheAgeCategory: %s\n", state.CacheAgeCategory)
	} else {
		// [DEBUG] No cache entry
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-NO-ENTRY] No cache entry: FirstSeen=%d\n", entryFirstSeen)
		state.CacheExists = false
		state.TaskFrequencyCategory = "none"
		state.CacheAgeCategory = "none"
		fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-NO-ENTRY-DONE] Set defaults: CacheExists=false, Frequency=none, Age=none\n")
	}
	
	// [DEBUG] Generate state key
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-KEY-BEFORE] About to generate state key\n")
	stateKey := state.GetStateKey()
	// [DEBUG] State key generated
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-KEY-AFTER] State key generated: %s\n", stateKey)
	
	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [CACHE-STATE-EXTRACT-EXIT] ExtractCacheStateFeatures returning state: Key=%s, CacheExists=%t\n", stateKey, state.CacheExists)

	return state
}

// Categorization functions (with configurable boundaries)

func categorizeSystemLoad(load float64) string {
	// Boundaries: [0, 0.4, 0.7, 1.0] → ["low", "medium", "high"]
	if load < 0.4 {
		return "low"
	} else if load < 0.7 {
		return "medium"
	}
	return "high"
}

func categorizeQueueLength(length int) string {
	// Boundaries: [0, 5, 15, 100] → ["short", "medium", "long"]
	if length < 5 {
		return "short"
	} else if length < 15 {
		return "medium"
	}
	return "long"
}

func categorizeCacheHitRate(rate float64) string {
	// Boundaries: [0, 0.3, 0.6, 0.9, 1.0] → ["poor", "fair", "good", "excellent"]
	if rate < 0.3 {
		return "poor"
	} else if rate < 0.6 {
		return "fair"
	} else if rate < 0.9 {
		return "good"
	}
	return "excellent"
}

func categorizeTaskFrequency(frequency int) string {
	// Boundaries: [0, 1, 5, 20, 1000] → ["none", "rare", "occasional", "frequent", "very_frequent"]
	if frequency == 0 {
		return "none"
	} else if frequency == 1 {
		return "rare"
	} else if frequency < 5 {
		return "occasional"
	} else if frequency < 20 {
		return "frequent"
	}
	return "very_frequent"
}

// CategorizeCacheAge calculates cache age category from TTL ratio (exported for use in scheduler)
func CategorizeCacheAge(ttlRatio float64) string {
	// Categorize:
	// ttlRatio < 0.5 → "fresh"
	// ttlRatio < 0.75 → "recent"
	// ttlRatio < 1.0 → "old"
	// ttlRatio >= 1.0 → "expired"
	if ttlRatio < 0.5 {
		return "fresh"
	} else if ttlRatio < 0.75 {
		return "recent"
	} else if ttlRatio < 1.0 {
		return "old"
	}
	return "expired"
}

