package rl

import (
	"fmt"
	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
	"sync"
	"time"
)

type ExperienceManager struct {
	mu                    sync.RWMutex
	incompleteExperiences map[string]*IncompleteExperience // key is cloudletId (unique instance identifier)
	experienceTimeout     time.Duration
	qLearningScheduler    *QLearningScheduler
	multiObjectiveCalc    *MultiObjectiveRewardCalculator

	// Memory Management & Stability Tracking
	config                config.MemoryManagementConfig
	qValueHistory         map[string]map[ActionType][]float64 // Track Q-value changes with BOUNDED memory
	stabilityTracker      map[string]map[ActionType]*StabilityInfo
	completeExperiences   []*CompleteExperience // Store completed experiences for lifecycle management
	episodeCleanupCounter int                   // Track episodes since last cleanup
	lastCleanupTime       time.Time
	memoryUsageBytes      int64 // Estimated memory usage
}

type IncompleteExperience struct {
	TaskID    string // Using cloudletId as unique identifier (not pattern-based taskId)
	State     *StateFeatures
	Action    Action
	Timestamp time.Time
	Timeout   time.Time
	Episode   int // Track episode number to prevent overwrites
}

// New structs for memory management
type CompleteExperience struct {
	Experience    *Experience
	TaskID        string
	EpisodeNumber int
	CompletedAt   time.Time
	Age           int // Episodes since completion
	IsStable      bool
	StabilityAge  int // Episodes since marked stable
}

type StabilityInfo struct {
	QValues       []float64 // Recent Q-values for this state-action pair (BOUNDED)
	IsStable      bool
	StableEpisode int // Episode when marked stable
	LastUpdated   time.Time
}

func NewExperienceManager(scheduler *QLearningScheduler, multiObj *MultiObjectiveRewardCalculator) *ExperienceManager {
	cfg := config.GetConfig()

	// Set very long timeout (24 hours) to prevent expiration during simulation
	experienceTimeout := cfg.RL.MemoryManagement.ExperienceTimeoutMinutes * time.Minute
	if experienceTimeout == 0 || experienceTimeout < 24*time.Hour {
		experienceTimeout = 24 * time.Hour // 24 hours - long enough for any simulation
		logger.GetLogger().Infof("[EXP-MGR-INIT] Experience timeout set to 24 hours (default)")
	} else {
		logger.GetLogger().Infof("[EXP-MGR-INIT] Experience timeout set to %v (from config)", experienceTimeout)
	}

	return &ExperienceManager{
		incompleteExperiences: make(map[string]*IncompleteExperience),
		experienceTimeout:     experienceTimeout,
		qLearningScheduler:    scheduler,
		multiObjectiveCalc:    multiObj,

		// Initialize memory management with bounds
		config:                cfg.RL.MemoryManagement,
		qValueHistory:         make(map[string]map[ActionType][]float64),
		stabilityTracker:      make(map[string]map[ActionType]*StabilityInfo),
		completeExperiences:   make([]*CompleteExperience, 0),
		episodeCleanupCounter: 0,
		lastCleanupTime:       time.Now(),
		memoryUsageBytes:      0,
	}
}

func (em *ExperienceManager) StoreIncompleteExperience(taskID string, state *StateFeatures, action Action, episode int) {
	// NOTE: taskID parameter is cloudletId (unique instance identifier from iFogSim)
	// This is NOT the pattern-based taskId - cloudletId and taskId are sent separately
	// cloudletId is used for experience lookup, taskId is used for cache operations
	em.mu.Lock()
	defer em.mu.Unlock()

	// Check if experience exists for this task in the current episode
	if existing, exists := em.incompleteExperiences[taskID]; exists {
		if existing.Episode == episode {
			// Experience already exists for this task in this episode
			// Skip storage to prevent overwrite
			logger.GetLogger().Infof("[EXP-MGR-STORE-SKIP] Skipping duplicate experience: cloudletId=%s, Episode=%d, ExistingAction=%s, NewAction=%s",
				taskID, episode, existing.Action.Description, action.Description)
			return // Return without overwriting
		}
		// Different episode - allow overwrite (task reused across episodes)
		logger.GetLogger().Warnf("[EXP-MGR-STORE-OVERWRITE] Overwriting experience from different episode: cloudletId=%s, OldEpisode=%d, NewEpisode=%d",
			taskID, existing.Episode, episode)
	}

	em.incompleteExperiences[taskID] = &IncompleteExperience{
		TaskID:    taskID,
		State:     state,
		Action:    action,
		Timestamp: time.Now(),
		Timeout:   time.Now().Add(em.experienceTimeout),
		Episode:   episode, // Store episode number
	}
	
	totalIncomplete := len(em.incompleteExperiences)
	logger.GetLogger().Infof("[EXP-MGR-STORE] Experience stored successfully: cloudletId=%s, TotalIncomplete=%d, Timeout=%s, Episode=%d",
		taskID, totalIncomplete, em.incompleteExperiences[taskID].Timeout.Format(time.RFC3339), episode)

	// Update memory usage estimation
	em.updateMemoryUsage()
}

func (em *ExperienceManager) CompleteExperience(taskID string, report *pb.TaskCompletionReport, nodeStatus *pb.FogNode, queueLength int) error {
	// NOTE: taskID parameter is cloudletId (unique instance identifier from iFogSim)
	// This is passed explicitly from ProcessTaskCompletion to ensure correct lookup
	// taskId (from report.TaskId) is pattern-based and used for cache operations, NOT for experience lookup
	// Both cloudletId and taskId are sent separately from iFogSim - we do NOT assume taskId contains cloudletId
	
	em.mu.Lock()
	
	// Log all available cloudletIds before lookup (for debugging)
	availableCloudletIds := make([]string, 0, len(em.incompleteExperiences))
	for id := range em.incompleteExperiences {
		availableCloudletIds = append(availableCloudletIds, id)
	}
	
	logger.GetLogger().Infof("[EXP-MGR-COMPLETE] Looking up cloudletId=%s, AvailableIncompleteExperiences=%d, cloudletIds=%v",
		taskID, len(availableCloudletIds), availableCloudletIds)
	
	// Lookup experience
	incompleteExp, exists := em.incompleteExperiences[taskID]
	em.mu.Unlock()
	
	if !exists {
		
		// Retry with small delay (handles race conditions between storage and completion)
		// This can happen if completion report arrives very quickly after scheduling
		time.Sleep(10 * time.Millisecond)
		em.mu.Lock()
		incompleteExp, exists = em.incompleteExperiences[taskID]
		em.mu.Unlock()
		
		if !exists {
			// Still not found - log detailed error
			logger.GetLogger().Errorf("[EXP-MGR-COMPLETE-ERROR] cloudletId=%s NOT FOUND after retry", taskID)
			logger.GetLogger().Errorf("[EXP-MGR-COMPLETE-ERROR] Total incomplete experiences: %d", len(availableCloudletIds))
			logger.GetLogger().Errorf("[EXP-MGR-COMPLETE-ERROR] Available cloudletIds: %v", availableCloudletIds)
			return fmt.Errorf("cloudletId %s not found in incomplete experiences after retry (total available: %d)", taskID, len(availableCloudletIds))
		}
		logger.GetLogger().Infof("[EXP-MGR-COMPLETE-RETRY] Experience found after retry: cloudletId=%s", taskID)
	}
	
	em.mu.Lock()
	
	// Remove from incomplete experiences
	delete(em.incompleteExperiences, taskID)
	remainingIncomplete := len(em.incompleteExperiences)
	
	em.mu.Unlock()
	
	logger.GetLogger().Infof("[EXP-MGR-COMPLETE] Experience found: cloudletId=%s, CreatedAt=%s, Age=%s, RemainingIncomplete=%d",
		taskID, incompleteExp.Timestamp.Format(time.RFC3339), time.Since(incompleteExp.Timestamp).String(), remainingIncomplete)
	logger.GetLogger().Infof("[EXP-MGR-COMPLETE] State comparison: OldQueueLength=%d, NewQueueLength=%d, OldStateKey=%s",
		incompleteExp.State.QueueLength, queueLength, incompleteExp.State.GetStateKey())

	// Calculate delayed reward with node status
	reward, err := em.calculateDelayedReward(incompleteExp, report, nodeStatus, taskID)
	if err != nil {
		logger.GetLogger().Errorf("[EXP-MGR-COMPLETE] Failed to calculate reward: cloudletId=%s, Error=%v", taskID, err)
		return fmt.Errorf("failed to calculate reward: %w", err)
	}
	logger.GetLogger().Infof("[EXP-MGR-COMPLETE] Reward calculated: cloudletId=%s, Reward=%.3f", taskID, reward)

	// Create next state from node status (for Q-learning update)
	// Use actual current queue length (passed from scheduler engine)
	// This is more accurate than approximating from incomplete experience
	nextState := ExtractStateFeaturesFromNodeStatus(
		[]TaskEntry{}, // No tasks needed for next state
		nodeStatus,
		queueLength, // Use actual queue length at completion time
	)
	logger.GetLogger().Infof("[EXP-MGR-COMPLETE] Next state created: cloudletId=%s, NextStateKey=%s, QueueLength=%d",
		taskID, nextState.GetStateKey(), queueLength)

	experience := &Experience{
		State:     incompleteExp.State, // State at scheduling time
		Action:    incompleteExp.Action,
		Reward:    reward,
		NextState: nextState, // State at completion time (with real node status)
		Done:      false,
		Timestamp: time.Now(),
	}

	// Update Q-learning policy
	logger.GetLogger().Infof("[EXP-MGR-COMPLETE] Updating Q-learning policy: cloudletId=%s, State=%s, Action=%s, Reward=%.3f",
		taskID, experience.State.GetStateKey(), experience.Action.Description, experience.Reward)
	if err := em.qLearningScheduler.UpdatePolicy(experience); err != nil {
		logger.GetLogger().Errorf("[EXP-MGR-COMPLETE] ERROR: Failed to update Q-learning policy: cloudletId=%s, Error=%v", taskID, err)
		return err
	}
	logger.GetLogger().Infof("[EXP-MGR-COMPLETE] Q-learning policy updated successfully: cloudletId=%s", taskID)

	// Store complete experience and track stability
	em.storeCompleteExperience(experience, taskID)
	em.trackQValueStability(incompleteExp.State, incompleteExp.Action)
	
	logger.GetLogger().Infof("[EXP-MGR-COMPLETE] Experience completed successfully: cloudletId=%s, Reward=%.3f, StateKey=%s->%s",
		taskID, reward, experience.State.GetStateKey(), experience.NextState.GetStateKey())
	return nil
}

// Store completed experience for lifecycle management
func (em *ExperienceManager) storeCompleteExperience(experience *Experience, taskID string) {
	em.mu.Lock()
	defer em.mu.Unlock()

	if !em.config.Enabled {
		return
	}

	currentEpisode := em.qLearningScheduler.GetCurrentEpisode()

	completeExp := &CompleteExperience{
		Experience:    experience,
		TaskID:        taskID,
		EpisodeNumber: currentEpisode,
		CompletedAt:   time.Now(),
		Age:           0,
		IsStable:      false,
		StabilityAge:  0,
	}

	em.completeExperiences = append(em.completeExperiences, completeExp)
	em.updateMemoryUsage()

	// Trigger cleanup if memory limit exceeded (using config values)
	estimatedBytesPerExperience := int64(em.config.EstimatedBytesPerExperience)
	if em.memoryUsageBytes > int64(em.config.MaxExperiences)*estimatedBytesPerExperience {
		em.triggerMemoryCleanup()
	}
}

// Track Q-value stability with BOUNDED memory usage
func (em *ExperienceManager) trackQValueStability(state *StateFeatures, action Action) {
	if !em.config.Enabled {
		return
	}

	stateKey := state.GetStateKey()

	// Get current Q-table from scheduler
	qTable := em.qLearningScheduler.GetQTable()

	em.mu.Lock()
	defer em.mu.Unlock()

	// Initialize tracking structures if needed
	if _, exists := em.qValueHistory[stateKey]; !exists {
		em.qValueHistory[stateKey] = make(map[ActionType][]float64)
		em.stabilityTracker[stateKey] = make(map[ActionType]*StabilityInfo)
	}

	if _, exists := em.qValueHistory[stateKey][action.Type]; !exists {
		// CRITICAL: Pre-allocate with exact capacity to prevent memory growth
		em.qValueHistory[stateKey][action.Type] = make([]float64, 0, em.config.StabilityWindow)
		em.stabilityTracker[stateKey][action.Type] = &StabilityInfo{
			QValues:     make([]float64, 0, em.config.StabilityWindow),
			IsStable:    false,
			LastUpdated: time.Now(),
		}
	}

	// Get current Q-value (NO DUPLICATION - just read current value)
	currentQValue := 0.0
	if stateActions, exists := qTable[stateKey]; exists {
		if qVal, exists := stateActions[action.Type]; exists {
			currentQValue = qVal
		}
	}

	// Update Q-value history with STRICT BOUNDS
	stability := em.stabilityTracker[stateKey][action.Type]

	// MEMORY OPTIMIZATION: Maintain EXACT window size
	if len(stability.QValues) >= em.config.StabilityWindow {
		// Remove oldest value to maintain window size
		stability.QValues = stability.QValues[1:]
	}

	stability.QValues = append(stability.QValues, currentQValue)
	stability.LastUpdated = time.Now()

	// Check for stability if we have enough history
	if len(stability.QValues) >= em.config.StabilityWindow {
		em.checkQValueStability(stateKey, action.Type, stability)

		// AGGRESSIVE CLEANUP: If stable, reduce history size immediately
		if stability.IsStable && len(stability.QValues) > em.config.StabilityWindow/2 {
			// Keep only half the window for stable state-action pairs
			keepSize := em.config.StabilityWindow / 2
			minHistorySize := em.config.MinHistorySize
			if keepSize < minHistorySize {
				keepSize = minHistorySize // Use config minimum
			}
			stability.QValues = stability.QValues[len(stability.QValues)-keepSize:]
		}
	}
}

// Check if Q-values are stable for a state-action pair
func (em *ExperienceManager) checkQValueStability(stateKey string, actionType ActionType, stability *StabilityInfo) {
	if len(stability.QValues) < em.config.StabilityWindow {
		return
	}

	// Calculate variance of recent Q-values
	mean := 0.0
	for _, qVal := range stability.QValues {
		mean += qVal
	}
	mean /= float64(len(stability.QValues))

	variance := 0.0
	for _, qVal := range stability.QValues {
		diff := qVal - mean
		variance += diff * diff
	}
	variance /= float64(len(stability.QValues))

	// Mark as stable if variance is below threshold
	wasStable := stability.IsStable
	stability.IsStable = variance < em.config.StabilityThreshold

	if !wasStable && stability.IsStable {
		stability.StableEpisode = em.qLearningScheduler.GetCurrentEpisode()
	}
}

// Clean up stable experiences
func (em *ExperienceManager) CleanupStableExperiences() int {
	if !em.config.Enabled || em.config.CleanupStrategy != "stability_based" {
		return 0
	}

	em.mu.Lock()
	defer em.mu.Unlock()

	currentEpisode := em.qLearningScheduler.GetCurrentEpisode()
	cleaned := 0
	preserveEpisodes := em.config.PreserveRecentEpisodes

	// Create new slice for experiences to keep
	keptExperiences := make([]*CompleteExperience, 0, len(em.completeExperiences))

	for _, exp := range em.completeExperiences {
		shouldKeep := true

		// FIX: Declare variables in proper scope for each iteration
		stateKey := exp.Experience.State.GetStateKey()
		actionType := exp.Experience.Action.Type

		// Always preserve recent episodes
		if currentEpisode-exp.EpisodeNumber <= preserveEpisodes {
			shouldKeep = true
		} else {
			// Check if state-action pair is stable and old enough
			if em.stabilityTracker[stateKey] != nil {
				if stability, exists := em.stabilityTracker[stateKey][actionType]; exists && stability.IsStable {
					// Remove if stable for enough episodes
					episodesSinceStable := currentEpisode - stability.StableEpisode
					if episodesSinceStable >= em.config.StabilityWindow {
						shouldKeep = false
						cleaned++
					}
				}
			}
		}

		if shouldKeep {
			// Update age
			exp.Age = currentEpisode - exp.EpisodeNumber
			if em.stabilityTracker[stateKey] != nil {
				if stability, exists := em.stabilityTracker[stateKey][actionType]; exists && stability.IsStable {
					exp.IsStable = true
					exp.StabilityAge = currentEpisode - stability.StableEpisode
				}
			}
			keptExperiences = append(keptExperiences, exp)
		}
	}

	em.completeExperiences = keptExperiences
	em.updateMemoryUsage()

	if cleaned > 0 {
	}

	return cleaned
}

// Enforce maximum experience limit
func (em *ExperienceManager) enforceExperienceLimit() int {
	if !em.config.Enabled || len(em.completeExperiences) <= em.config.MaxExperiences {
		return 0
	}

	em.mu.Lock()
	defer em.mu.Unlock()

	// Sort experiences by age (oldest first) but preserve recent episodes
	currentEpisode := em.qLearningScheduler.GetCurrentEpisode()
	preserveEpisodes := em.config.PreserveRecentEpisodes

	// Separate recent and old experiences
	recentExp := make([]*CompleteExperience, 0)
	oldExp := make([]*CompleteExperience, 0)

	for _, exp := range em.completeExperiences {
		if currentEpisode-exp.EpisodeNumber <= preserveEpisodes {
			recentExp = append(recentExp, exp)
		} else {
			oldExp = append(oldExp, exp)
		}
	}

	// Calculate how many to remove
	totalExperiences := len(recentExp) + len(oldExp)
	toRemove := totalExperiences - em.config.MaxExperiences

	if toRemove <= 0 {
		return 0
	}

	// Remove oldest experiences first, but not more than available old experiences
	removed := 0
	if toRemove <= len(oldExp) {
		// Remove from old experiences only
		oldExp = oldExp[toRemove:]
		removed = toRemove
	} else {
		// Remove all old experiences and some recent ones (should be rare)
		excessToRemove := toRemove - len(oldExp)
		removed = len(oldExp)
		oldExp = nil

		if excessToRemove < len(recentExp) {
			recentExp = recentExp[excessToRemove:]
			removed += excessToRemove
		}
	}

	// Rebuild experiences list
	em.completeExperiences = append(recentExp, oldExp...)
	em.updateMemoryUsage()

	if removed > 0 {
	}

	return removed
}

// Trigger memory cleanup when limits are exceeded
func (em *ExperienceManager) triggerMemoryCleanup() {

	// Try stability-based cleanup first
	em.CleanupStableExperiences()

	// If still over limit, enforce hard limit
	if len(em.completeExperiences) > em.config.MaxExperiences {
		em.enforceExperienceLimit()
	}

	// If memory is still critical, trigger emergency cleanup (using config value)
	maxReasonableMemory := int64(em.config.EmergencyCleanupThresholdMB * 1024 * 1024)
	if em.memoryUsageBytes > maxReasonableMemory {
		go em.emergencyMemoryCleanup() // Run in background to avoid deadlock
	}

}

// updateMemoryUsage calculates memory usage with optimized estimation
func (em *ExperienceManager) updateMemoryUsage() {
	// Use config values for accurate estimation with optimized calculation
	experienceBytes := int64(len(em.completeExperiences) * em.config.EstimatedBytesPerExperience)
	incompleteBytes := int64(len(em.incompleteExperiences) * em.config.EstimatedBytesPerIncompleteExperience)

	// Optimized Q-value memory calculation - avoid repeated map iterations
	qValueBytes := int64(0)
	stateKeyOverhead := int64(em.config.StateKeyOverhead)

	for stateKey, stateActions := range em.qValueHistory {
		stateKeyLen := int64(len(stateKey))
		for _, qValues := range stateActions {
			qValueBytes += int64(len(qValues)*8) + stateKeyLen + stateKeyOverhead
		}
	}

	// Stability tracker overhead (batch calculation)
	stabilityBytes := int64(len(em.stabilityTracker) * em.config.StabilityTrackerOverheadBytes)

	em.memoryUsageBytes = experienceBytes + incompleteBytes + qValueBytes + stabilityBytes

	// Emergency cleanup check with optimized threshold
	maxMemory := int64(em.config.EmergencyCleanupThresholdMB * 1024 * 1024)
	if em.memoryUsageBytes > maxMemory {
		go em.emergencyMemoryCleanup() // Non-blocking cleanup
	}
}

// Emergency memory cleanup when bounds are exceeded
func (em *ExperienceManager) emergencyMemoryCleanup() {

	em.mu.Lock()
	defer em.mu.Unlock()

	// 1. Immediately reduce all Q-value histories to minimum size (using config value)
	minHistorySize := em.config.MinHistorySize
	for stateKey, actions := range em.qValueHistory {
		for actionType, qValues := range actions {
			if len(qValues) > minHistorySize {
				// Keep only the most recent values
				em.qValueHistory[stateKey][actionType] = qValues[len(qValues)-minHistorySize:]
				if em.stabilityTracker[stateKey] != nil {
					if stability, exists := em.stabilityTracker[stateKey][actionType]; exists {
						stability.QValues = stability.QValues[len(stability.QValues)-minHistorySize:]
					}
				}
			}
		}
	}

	// 2. Aggressively clean experiences
	if len(em.completeExperiences) > em.config.MaxExperiences/2 {
		// Keep only most recent half
		keepCount := em.config.MaxExperiences / 2
		em.completeExperiences = em.completeExperiences[len(em.completeExperiences)-keepCount:]
	}

	// 3. Remove unused state-action pairs immediately
	em.cleanupUnusedQValueHistoryUnsafe()

	em.updateMemoryUsageUnsafe()
}

func (em *ExperienceManager) Cleanup() {
	em.mu.Lock()
	defer em.mu.Unlock()

	now := time.Now()

	// CRITICAL: Do NOT clean up incomplete experiences during simulation
	// Only clean them at simulation end (via explicit cleanup method)
	// This prevents "experience not found" errors
	logger.GetLogger().Infof("[EXP-MGR-CLEANUP] Skipping incomplete experience cleanup during simulation (preserving all incomplete experiences)")
	
	// Only clean up complete experiences if memory limit exceeded
	// This is safe because complete experiences are already used for learning
	if len(em.completeExperiences) > em.config.MaxExperiences {
		logger.GetLogger().Infof("[EXP-MGR-CLEANUP] Memory limit exceeded, cleaning complete experiences: Current=%d, Max=%d",
			len(em.completeExperiences), em.config.MaxExperiences)
		em.enforceExperienceLimit()
	}

	// Scheduled cleanup based on episode intervals (only for complete experiences)
	if em.config.Enabled {
		em.episodeCleanupCounter++

		if em.episodeCleanupCounter >= em.config.CleanupIntervalEpisodes {
			em.performScheduledCleanup()
			em.episodeCleanupCounter = 0
			em.lastCleanupTime = now
		}
	}
}

// Perform scheduled cleanup
func (em *ExperienceManager) performScheduledCleanup() {
	em.CleanupStableExperiences()
	em.enforceExperienceLimit()

	// Clean up old Q-value history for unused state-action pairs
	em.cleanupUnusedQValueHistory()

	em.updateMemoryUsage()
}

// Clean up unused Q-value history
func (em *ExperienceManager) cleanupUnusedQValueHistory() {
	em.mu.Lock()
	defer em.mu.Unlock()
	em.cleanupUnusedQValueHistoryUnsafe()
}

func (em *ExperienceManager) cleanupUnusedQValueHistoryUnsafe() {
	// Remove Q-value history for state-action pairs not seen recently (using config value)
	cutoffDuration := time.Duration(em.config.UnusedHistoryCleanupHours) * time.Hour
	cutoffTime := time.Now().Add(-cutoffDuration)

	for stateKey, actions := range em.stabilityTracker {
		for actionType, stability := range actions {
			if stability.LastUpdated.Before(cutoffTime) {
				delete(em.stabilityTracker[stateKey], actionType)
				if em.qValueHistory[stateKey] != nil {
					delete(em.qValueHistory[stateKey], actionType)
				}
			}
		}

		// Remove empty state entries
		if len(em.stabilityTracker[stateKey]) == 0 {
			delete(em.stabilityTracker, stateKey)
			delete(em.qValueHistory, stateKey)
		}
	}
}

// Get total Q-value entry count for monitoring
func (em *ExperienceManager) getQValueEntryCount() int {
	count := 0
	for _, stateActions := range em.qValueHistory {
		for _, qValues := range stateActions {
			count += len(qValues)
		}
	}
	return count
}

func (em *ExperienceManager) GetPendingCount() int {
	em.mu.RLock()
	defer em.mu.RUnlock()
	return len(em.incompleteExperiences)
}

// statistics
func (em *ExperienceManager) GetStats() map[string]interface{} {
	em.mu.RLock()
	defer em.mu.RUnlock()

	stats := map[string]interface{}{
		"pending_experiences":    len(em.incompleteExperiences),
		"complete_experiences":   len(em.completeExperiences),
		"memory_usage_kb":        em.memoryUsageBytes / 1024,
		"memory_usage_mb":        em.memoryUsageBytes / (1024 * 1024),
		"episodes_since_cleanup": em.episodeCleanupCounter,
		"last_cleanup":           em.lastCleanupTime.Format(time.RFC3339),
	}

	if em.config.Enabled {
		// Count stable vs unstable state-action pairs
		stableCount := 0
		totalPairs := 0

		for _, actions := range em.stabilityTracker {
			for _, stability := range actions {
				totalPairs++
				if stability.IsStable {
					stableCount++
				}
			}
		}

		stats["stable_state_action_pairs"] = stableCount
		stats["total_state_action_pairs"] = totalPairs
		stats["q_value_entries"] = em.getQValueEntryCount()
		stats["memory_management_enabled"] = true
		stats["cleanup_strategy"] = em.config.CleanupStrategy
		stats["stability_window"] = em.config.StabilityWindow
		stats["max_experiences"] = em.config.MaxExperiences

		// Memory efficiency metrics
		if totalPairs > 0 {
			stats["avg_q_values_per_pair"] = float64(em.getQValueEntryCount()) / float64(totalPairs)
		}
	} else {
		stats["memory_management_enabled"] = false
	}

	return stats
}

// FIXED MarkEpisodeComplete - compilation errors resolved
func (em *ExperienceManager) MarkEpisodeComplete(episodeNumber int) {
	em.mu.Lock()
	defer em.mu.Unlock()


	// Episode completion triggers cleanup and stability updates
	if em.config.Enabled {
		// Update ages of all complete experiences
		updatedCount := 0
		stableCount := 0
		for _, exp := range em.completeExperiences {
			oldAge := exp.Age
			exp.Age = episodeNumber - exp.EpisodeNumber
			if oldAge != exp.Age {
				updatedCount++
			}

			// FIX: Declare variables in proper scope
			stateKey := exp.Experience.State.GetStateKey()
			actionType := exp.Experience.Action.Type

			// Safe null check before accessing nested maps
			if em.stabilityTracker[stateKey] != nil {
				if stability, exists := em.stabilityTracker[stateKey][actionType]; exists && stability.IsStable {
					wasStable := exp.IsStable
					exp.IsStable = true
					exp.StabilityAge = episodeNumber - stability.StableEpisode
					if !wasStable {
						stableCount++
					}
				}
			}
		}

		// Check if cleanup should be triggered
		if em.episodeCleanupCounter >= em.config.CleanupIntervalEpisodes-1 {
			// Will trigger on next Cleanup() call
		}
	}
	
	logger.GetLogger().Infof("[EXP-MGR-EPISODE] MarkEpisodeComplete completed: Episode=%d, IncompleteExperiences=%d, CompleteExperiences=%d, CleanupCounter=%d",
		episodeNumber, len(em.incompleteExperiences), len(em.completeExperiences), em.episodeCleanupCounter)

}

// Unsafe version for internal use (already holding mutex)
func (em *ExperienceManager) updateMemoryUsageUnsafe() {
	// Use config values for accurate estimation
	experienceBytes := int64(len(em.completeExperiences) * em.config.EstimatedBytesPerExperience)
	incompleteBytes := int64(len(em.incompleteExperiences) * em.config.EstimatedBytesPerIncompleteExperience)

	qValueBytes := int64(0)
	for stateKey, stateActions := range em.qValueHistory {
		for _, qValues := range stateActions {
			qValueBytes += int64(len(qValues) * 8)
			qValueBytes += int64(len(stateKey) + em.config.StateKeyOverhead)
		}
	}

	stabilityBytes := int64(len(em.stabilityTracker) * em.config.StabilityTrackerOverheadBytes)
	em.memoryUsageBytes = experienceBytes + incompleteBytes + qValueBytes + stabilityBytes
}

func (em *ExperienceManager) calculateDelayedReward(incompleteExp *IncompleteExperience, report *pb.TaskCompletionReport, nodeStatus *pb.FogNode, cloudletId string) (float64, error) {
	// NOTE: cloudletId is the unique instance identifier passed from CompleteExperience
	// Use cloudletId for logging consistency (not pattern-based taskId from report)

	logger.GetLogger().Infof("[REWARD-CALC] Calculating delayed reward: cloudletId=%s, MultiObj=%t, CompletedTasks=%d, NodeStatus=%t",
		cloudletId, em.multiObjectiveCalc != nil, len(report.Tasks), nodeStatus != nil)

	if em.multiObjectiveCalc != nil {
		// Pass node status to multi-objective calculator
		reward, err := em.multiObjectiveCalc.CalculateDelayedReward(
			incompleteExp.State,
			incompleteExp.Action,
			report.Tasks,
			report.Metrics,
			nodeStatus, // NEW: Pass node status
		)
		if err != nil {
			logger.GetLogger().Errorf("[REWARD-CALC] Multi-objective reward calculation failed: cloudletId=%s, Error=%v",
				cloudletId, err)
			return 0.0, err
		}
		logger.GetLogger().Infof("[REWARD-CALC-RESULT] Multi-objective reward calculated: cloudletId=%s, Reward=%.3f",
			cloudletId, reward)
		return reward, nil
	}

	reward := em.calculateSimpleReward(report)
	logger.GetLogger().Infof("[REWARD-SIMPLE] Simple reward calculated: cloudletId=%s, Reward=%.3f, Latency=%.2fms, Throughput=%.2f, DeadlineMisses=%d",
		cloudletId, reward, report.Metrics.AverageLatencyMs, report.Metrics.TotalThroughput, report.Metrics.DeadlineMisses)
	return reward, nil
}

func (em *ExperienceManager) calculateSimpleReward(report *pb.TaskCompletionReport) float64 {
	reward := 0.0

	// Later Feature: deadline-aware penalty disabled
	// reward -= float64(report.Metrics.DeadlineMisses) * 10.0

	// Reward for low latency
	if report.Metrics.AverageLatencyMs > 0 {
		reward += 1000.0 / report.Metrics.AverageLatencyMs
	}

	// Reward for high throughput
	reward += report.Metrics.TotalThroughput * 5.0

	return reward
}

func (em *ExperienceManager) getCurrentState() *StateFeatures {
	return &StateFeatures{
		Timestamp: time.Now(),
		// Add other relevant state features as needed
	}
}
