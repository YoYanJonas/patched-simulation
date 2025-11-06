package rl

import (
	"fmt"
	pb "scheduler-grpc-server/api/proto"
)

// Cache action types for RL agent (two-action design)
const (
	ActionCache  ActionType = iota // Cache this task
	ActionDelete                    // Remove this task from cache
)

// GetAllCacheActions returns all available cache actions (two-action design)
func GetAllCacheActions() []Action {
	return []Action{
		{Type: ActionCache, Description: "Cache this task", Priority: 0.5},
		{Type: ActionDelete, Description: "Remove this task from cache", Priority: 0.5},
	}
}

// MapCacheActionToProto maps RL cache action to proto CacheAction
// Handles expired cache cases (forces invalidation if expired)
// Two-action design: ActionCache and ActionDelete
func MapCacheActionToProto(
	rlAction Action,
	cacheExists bool,
	cacheExpired bool,
) pb.CacheAction {
	// [DEBUG] Entry point for MapCacheActionToProto
	fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-ENTRY] MapCacheActionToProto called: RLAction=%v, CacheExists=%t, CacheExpired=%t\n",
		rlAction.Type, cacheExists, cacheExpired)
	
	// If cache expired, force invalidation regardless of RL action
	if cacheExpired && cacheExists {
		// [DEBUG] Cache expired, forcing invalidation
		fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-EXPIRED] Cache expired, forcing invalidation\n")
		return pb.CacheAction_CACHE_ACTION_INVALIDATE
	}

	// Two-action design
	if rlAction.Type == ActionCache {
		if cacheExists && !cacheExpired {
			// [DEBUG] Cache exists and not expired, using cache
			fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-CACHE-USE] ActionCache with existing cache, returning USE\n")
			return pb.CacheAction_CACHE_ACTION_USE
		} else {
			// [DEBUG] Cache doesn't exist or expired, storing for future
			fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-CACHE-STORE] ActionCache with no cache, returning STORE\n")
			return pb.CacheAction_CACHE_ACTION_STORE
		}
	}

	if rlAction.Type == ActionDelete {
		if cacheExists && !cacheExpired {
			// [DEBUG] Cache exists, invalidating
			fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-DELETE-INVALIDATE] ActionDelete with existing cache, returning INVALIDATE\n")
			return pb.CacheAction_CACHE_ACTION_INVALIDATE
		} else {
			// [DEBUG] No cache to delete
			fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-DELETE-NONE] ActionDelete with no cache, returning NONE\n")
			return pb.CacheAction_CACHE_ACTION_NONE
		}
	}

	// Unknown action type - default to NONE
	fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-DEFAULT] Unknown RL action type: %v, returning NONE\n", rlAction.Type)
	return pb.CacheAction_CACHE_ACTION_NONE
}

// IsCacheAction checks if an action type is a cache action
func IsCacheAction(actionType ActionType) bool {
	// [DEBUG] Entry point for IsCacheAction
	fmt.Printf("[DEBUG] [CACHE-ACTION-IS-ENTRY] IsCacheAction called: ActionType=%v\n", actionType)
	
	isCache := actionType == ActionCache || actionType == ActionDelete
	
	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [CACHE-ACTION-IS-EXIT] IsCacheAction returning: %t\n", isCache)
	return isCache
}

