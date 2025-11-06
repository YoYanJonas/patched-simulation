package rl

import (
	"fmt"
	pb "scheduler-grpc-server/api/proto"
)

// CalculateCacheReward calculates reward for cache action
func CalculateCacheReward(
	action pb.CacheAction,
	executionTimeSaved int64, // ms saved if cache used
	cacheHitSuccess bool,      // was cache hit successful?
	systemLoad float64,        // current system load [0.0-1.0]
) float64 {
	// [DEBUG] Entry point for CalculateCacheReward
	fmt.Printf("[DEBUG] [CACHE-REWARD-ENTRY] CalculateCacheReward called: Action=%v, TimeSaved=%dms, HitSuccess=%t, Load=%.3f\n",
		action, executionTimeSaved, cacheHitSuccess, systemLoad)
	
	reward := 0.0

	switch action {
	case pb.CacheAction_CACHE_ACTION_USE:
		if cacheHitSuccess {
			// Reward for successful cache hit
			reward += float64(executionTimeSaved) * 0.1 // 0.1 per ms saved
			reward += 50.0                             // base reward for cache hit
		} else {
			// Penalty for cache miss when expecting hit
			reward -= 100.0
		}

	case pb.CacheAction_CACHE_ACTION_STORE:
		// Reward for storing (future cache hits)
		reward += 10.0
		if systemLoad > 0.7 {
			reward += 20.0 // Extra reward when system is loaded
		}

	case pb.CacheAction_CACHE_ACTION_INVALIDATE:
		// Small reward for cleaning expired cache
		reward += 5.0

	case pb.CacheAction_CACHE_ACTION_NONE:
		// Neutral (no reward/penalty)
		reward += 0.0

	default:
		// [DEBUG] Unknown action
		fmt.Printf("[DEBUG] [CACHE-REWARD-UNKNOWN] Unknown cache action: %v\n", action)
		// Unknown action - no reward
		reward += 0.0
	}

	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [CACHE-REWARD-EXIT] CalculateCacheReward returning: %.3f\n", reward)
	return reward
}

