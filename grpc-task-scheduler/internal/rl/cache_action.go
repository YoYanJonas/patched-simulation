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
// fogCacheExists: Whether fog node has local cache (from metadata)
func MapCacheActionToProto(
	rlAction Action,
	cacheExists bool,
	cacheExpired bool,
	fogCacheExists bool,
) pb.CacheAction {
	// [DEBUG] Entry point for MapCacheActionToProto
	fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-ENTRY] MapCacheActionToProto called: RLAction=%v, CacheExists=%t, CacheExpired=%t, FogCacheExists=%t\n",
		rlAction.Type, cacheExists, cacheExpired, fogCacheExists)
	
	// If cache expired, force invalidation regardless of RL action
	// Also invalidate if fog node has cache but server cache expired (sync both)
	if cacheExpired && (cacheExists || fogCacheExists) {
		// [DEBUG] Cache expired, forcing invalidation (server or fog node)
		fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-EXPIRED] Cache expired, forcing invalidation (server=%t, fog=%t)\n", cacheExists, fogCacheExists)
		return pb.CacheAction_CACHE_ACTION_INVALIDATE
	}

	// Two-action design
	if rlAction.Type == ActionCache {
		if cacheExists && !cacheExpired {
			// [DEBUG] Server cache exists and not expired, using cache
			fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-CACHE-USE] ActionCache with server cache, returning USE\n")
			return pb.CacheAction_CACHE_ACTION_USE
		} else if fogCacheExists && !cacheExpired {
			// [DEBUG] Fog node has cache but server doesn't - tell fog node to re-cache (STORE)
			// This ensures cache is synchronized and updated
			fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-CACHE-STORE] ActionCache with fog cache only, returning STORE (re-cache)\n")
			return pb.CacheAction_CACHE_ACTION_STORE
		} else {
			// [DEBUG] No cache anywhere, storing for future
			fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-CACHE-STORE] ActionCache with no cache, returning STORE\n")
			return pb.CacheAction_CACHE_ACTION_STORE
		}
	}

	if rlAction.Type == ActionDelete {
		if cacheExists && !cacheExpired {
			// [DEBUG] Server cache exists, invalidating
			fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-DELETE-INVALIDATE] ActionDelete with server cache, returning INVALIDATE\n")
			return pb.CacheAction_CACHE_ACTION_INVALIDATE
		} else if fogCacheExists {
			// [DEBUG] Fog node has cache but server doesn't - invalidate fog node's cache
			fmt.Printf("[DEBUG] [CACHE-ACTION-MAP-DELETE-INVALIDATE] ActionDelete with fog cache only, returning INVALIDATE\n")
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

