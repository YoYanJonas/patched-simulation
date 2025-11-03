package rl

import (
	pb "scheduler-grpc-server/api/proto"
)

// Cache action types for RL agent
const (
	ActionCacheUse        ActionType = 10 // Use existing cache (within TTL)
	ActionCacheStore      ActionType = 11 // Store result in cache (new task)
	ActionCacheInvalidate ActionType = 12 // Invalidate expired cache
	ActionNoCache         ActionType = 13 // Don't cache this task
)

// GetAllCacheActions returns all available cache actions
func GetAllCacheActions() []Action {
	return []Action{
		{Type: ActionCacheUse, Description: "Use cache if available", Priority: 0.7},
		{Type: ActionCacheStore, Description: "Store result in cache", Priority: 0.6},
		{Type: ActionCacheInvalidate, Description: "Invalidate cache entry", Priority: 0.4},
		{Type: ActionNoCache, Description: "Don't cache this task", Priority: 0.3},
	}
}

// MapCacheActionToProto maps RL cache action to proto CacheAction
// Handles expired cache cases (forces invalidation if expired)
func MapCacheActionToProto(
	rlAction Action,
	cacheExists bool,
	cacheExpired bool,
) pb.CacheAction {
	// If cache expired, force invalidation regardless of RL action
	if cacheExpired && cacheExists {
		return pb.CacheAction_CACHE_ACTION_INVALIDATE
	}

	switch rlAction.Type {
	case ActionCacheUse:
		if cacheExists && !cacheExpired {
			return pb.CacheAction_CACHE_ACTION_USE
		} else {
			// Cache doesn't exist or expired - store for future
			return pb.CacheAction_CACHE_ACTION_STORE
		}

	case ActionCacheStore:
		return pb.CacheAction_CACHE_ACTION_STORE

	case ActionCacheInvalidate:
		if cacheExists {
			return pb.CacheAction_CACHE_ACTION_INVALIDATE
		}
		// No cache to invalidate
		return pb.CacheAction_CACHE_ACTION_NONE

	case ActionNoCache:
		return pb.CacheAction_CACHE_ACTION_NONE

	default:
		return pb.CacheAction_CACHE_ACTION_NONE
	}
}

// IsCacheAction checks if an action type is a cache action
func IsCacheAction(actionType ActionType) bool {
	return actionType == ActionCacheUse ||
		actionType == ActionCacheStore ||
		actionType == ActionCacheInvalidate ||
		actionType == ActionNoCache
}

