package rl

import (
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
	// If cache expired, force invalidation regardless of RL action
	// Also invalidate if fog node has cache but server cache expired (sync both)
	if cacheExpired && (cacheExists || fogCacheExists) {
		return pb.CacheAction_CACHE_ACTION_INVALIDATE
	}

	// Two-action design
	if rlAction.Type == ActionCache {
		if cacheExists && !cacheExpired {
			return pb.CacheAction_CACHE_ACTION_USE
		} else if fogCacheExists && !cacheExpired {
			// This ensures cache is synchronized and updated
			return pb.CacheAction_CACHE_ACTION_STORE
		} else {
			return pb.CacheAction_CACHE_ACTION_STORE
		}
	}

	if rlAction.Type == ActionDelete {
		if cacheExists && !cacheExpired {
			return pb.CacheAction_CACHE_ACTION_INVALIDATE
		} else if fogCacheExists {
			return pb.CacheAction_CACHE_ACTION_INVALIDATE
		} else {
			return pb.CacheAction_CACHE_ACTION_NONE
		}
	}

	// Unknown action type - default to NONE
	return pb.CacheAction_CACHE_ACTION_NONE
}

// IsCacheAction checks if an action type is a cache action
func IsCacheAction(actionType ActionType) bool {
	
	isCache := actionType == ActionCache || actionType == ActionDelete
	
	return isCache
}

