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
	state := &CacheStateFeatures{
		keyDirty: true,
	}

	// 1. TaskFingerprintPrefix (first 8 chars)
	if len(fingerprint) >= 8 {
		state.TaskFingerprintPrefix = fingerprint[:8]
	} else {
		state.TaskFingerprintPrefix = fingerprint
	}

	// 2. SystemLoadCategory (from node manager)
	state.SystemLoadCategory = categorizeSystemLoad(systemLoad)

	// 3. QueueLengthCategory (from QueueContext)
	totalQueueSize := int32(0)
	if queueContext != nil {
		totalQueueSize = queueContext.TotalQueueSize
	} else {
	}
	state.QueueLengthCategory = categorizeQueueLength(int(totalQueueSize))

	// 4. CacheHitRateCategory (from cache manager)
	state.CacheHitRateCategory = categorizeCacheHitRate(hitRate)

	// 5. TaskFrequencyCategory (from entrySeenCount)
	// 6. CacheExists
	// 7. CacheAgeCategory
	if entryFirstSeen > 0 {
		state.CacheExists = true
		state.TaskFrequencyCategory = categorizeTaskFrequency(entrySeenCount)
		
		// Calculate cache age from FirstSeen
		now := time.Now()
		firstSeen := time.Unix(entryFirstSeen, 0)
		age := now.Sub(firstSeen)
		cacheTTL := time.Duration(cacheTTLHours) * time.Hour
		ttlRatio := float64(age) / float64(cacheTTL)
		state.CacheAgeCategory = CategorizeCacheAge(ttlRatio)
	} else {
		state.CacheExists = false
		state.TaskFrequencyCategory = "none"
		state.CacheAgeCategory = "none"
	}

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

