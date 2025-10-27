package rl

import (
	"fmt"
	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/config"
	"sync"
	"time"
)

type ExperienceManager struct {
	mu                    sync.RWMutex
	incompleteExperiences map[string]*IncompleteExperience // key is task_id
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
	TaskID    string // Using task_id as identifier
	State     *StateFeatures
	Action    Action
	Timestamp time.Time
	Timeout   time.Time
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

	return &ExperienceManager{
		incompleteExperiences: make(map[string]*IncompleteExperience),
		experienceTimeout:     cfg.RL.MemoryManagement.ExperienceTimeoutMinutes * time.Minute,
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

func (em *ExperienceManager) StoreIncompleteExperience(taskID string, state *StateFeatures, action Action) {
	em.mu.Lock()
	defer em.mu.Unlock()

	em.incompleteExperiences[taskID] = &IncompleteExperience{
		TaskID:    taskID,
		State:     state,
		Action:    action,
		Timestamp: time.Now(),
		Timeout:   time.Now().Add(em.experienceTimeout),
	}

	// Update memory usage estimation
	em.updateMemoryUsage()
}

func (em *ExperienceManager) CompleteExperience(taskID string, report *pb.TaskCompletionReport) error {
	em.mu.Lock()
	incompleteExp := em.incompleteExperiences[taskID]
	delete(em.incompleteExperiences, taskID)
	em.mu.Unlock()

	if incompleteExp == nil {
		return fmt.Errorf("task %s not found in incomplete experiences", taskID)
	}

	reward, err := em.calculateDelayedReward(incompleteExp, report)
	if err != nil {
		return fmt.Errorf("failed to calculate reward: %w", err)
	}

	experience := &Experience{
		State:     incompleteExp.State,
		Action:    incompleteExp.Action,
		Reward:    reward,
		NextState: em.getCurrentState(),
		Done:      false,
		Timestamp: time.Now(),
	}

	// Update Q-learning policy
	if err := em.qLearningScheduler.UpdatePolicy(experience); err != nil {
		return err
	}

	// Store complete experience and track stability
	em.storeCompleteExperience(experience, taskID)
	em.trackQValueStability(incompleteExp.State, incompleteExp.Action)

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
		fmt.Printf("State-Action pair %s:%v marked as stable (variance: %.6f)\n",
			stateKey, actionType, variance)
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
		fmt.Printf("Stability-based cleanup: Removed %d stable experiences, kept %d\n",
			cleaned, len(em.completeExperiences))
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
		fmt.Printf("Experience limit enforcement: Removed %d old experiences, kept %d\n",
			removed, len(em.completeExperiences))
	}

	return removed
}

// Trigger memory cleanup when limits are exceeded
func (em *ExperienceManager) triggerMemoryCleanup() {
	fmt.Println("Memory limit exceeded, triggering emergency cleanup...")

	// Try stability-based cleanup first
	cleaned := em.CleanupStableExperiences()

	// If still over limit, enforce hard limit
	if len(em.completeExperiences) > em.config.MaxExperiences {
		em.enforceExperienceLimit()
	}

	// If memory is still critical, trigger emergency cleanup (using config value)
	maxReasonableMemory := int64(em.config.EmergencyCleanupThresholdMB * 1024 * 1024)
	if em.memoryUsageBytes > maxReasonableMemory {
		go em.emergencyMemoryCleanup() // Run in background to avoid deadlock
	}

	fmt.Printf("Emergency cleanup completed: %d experiences cleaned\n", cleaned)
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
	fmt.Println("EMERGENCY: Aggressive memory cleanup initiated...")

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
	fmt.Printf("Emergency cleanup completed: Memory usage now %d KB\n", em.memoryUsageBytes/1024)
}

func (em *ExperienceManager) Cleanup() {
	em.mu.Lock()
	defer em.mu.Unlock()

	now := time.Now()

	// Clean up timed-out incomplete experiences
	for taskID, exp := range em.incompleteExperiences {
		if now.After(exp.Timeout) {
			delete(em.incompleteExperiences, taskID)
		}
	}

	// Scheduled cleanup based on episode intervals
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
	fmt.Printf("Performing scheduled cleanup (every %d episodes)...\n",
		em.config.CleanupIntervalEpisodes)

	cleanedStable := em.CleanupStableExperiences()
	cleanedLimit := em.enforceExperienceLimit()

	// Clean up old Q-value history for unused state-action pairs
	em.cleanupUnusedQValueHistory()

	em.updateMemoryUsage()

	fmt.Printf("Scheduled cleanup completed: %d stable + %d limit-based experiences cleaned\n",
		cleanedStable, cleanedLimit)
	fmt.Printf("Memory usage: %d KB, Complete experiences: %d, Q-value entries: %d\n",
		em.memoryUsageBytes/1024, len(em.completeExperiences), em.getQValueEntryCount())
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

	fmt.Printf("ExperienceManager: Episode %d marked as complete.\n", episodeNumber)

	// Episode completion triggers cleanup and stability updates
	if em.config.Enabled {
		// Update ages of all complete experiences
		for _, exp := range em.completeExperiences {
			exp.Age = episodeNumber - exp.EpisodeNumber

			// FIX: Declare variables in proper scope
			stateKey := exp.Experience.State.GetStateKey()
			actionType := exp.Experience.Action.Type

			// Safe null check before accessing nested maps
			if em.stabilityTracker[stateKey] != nil {
				if stability, exists := em.stabilityTracker[stateKey][actionType]; exists && stability.IsStable {
					exp.IsStable = true
					exp.StabilityAge = episodeNumber - stability.StableEpisode
				}
			}
		}

		// Check if cleanup should be triggered
		if em.episodeCleanupCounter >= em.config.CleanupIntervalEpisodes-1 {
			// Will trigger on next Cleanup() call
			fmt.Printf("Episode %d: Cleanup scheduled for next interval\n", episodeNumber)
		}
	}
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

func (em *ExperienceManager) calculateDelayedReward(incompleteExp *IncompleteExperience, report *pb.TaskCompletionReport) (float64, error) {
	if em.multiObjectiveCalc != nil {
		return em.multiObjectiveCalc.CalculateDelayedReward(
			incompleteExp.State,
			incompleteExp.Action,
			report.Tasks,
			report.Metrics,
		)
	}

	return em.calculateSimpleReward(report), nil
}

func (em *ExperienceManager) calculateSimpleReward(report *pb.TaskCompletionReport) float64 {
	reward := 0.0

	// Penalty for deadline misses
	reward -= float64(report.Metrics.DeadlineMisses) * 10.0

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
