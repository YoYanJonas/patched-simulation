package rl

import (
	"fmt"
	"math"
	"math/rand"
	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
	"time"
)

// QLearningScheduler implements Q-learning for task scheduling
type QLearningScheduler struct {
	config        config.RLConfig
	rewardWeights config.RewardWeights
	qTable        map[string]map[ActionType]float64
	isLearning    bool
	stats         map[string]interface{}
	rng           *rand.Rand

	experienceManager *ExperienceManager

	// Episode management
	currentEpisode   int
	episodeTaskCount int
	episodeStartTime time.Time
	lastEpisodeReset time.Time

	// Multi-objective integration
	multiObjectiveCalculator *MultiObjectiveRewardCalculator

	// Performance optimization: frequently accessed states cache
	frequentStates map[string]time.Time // Track frequently accessed states
	cacheCleanup   time.Time            // Last cache cleanup time
}

// Experience represents a learning experience
type Experience struct {
	State     *StateFeatures
	Action    Action
	Reward    float64
	NextState *StateFeatures
	Done      bool
	Timestamp time.Time
}

// NewQLearningScheduler creates a new Q-learning scheduler
func NewQLearningScheduler(cfg config.RLConfig, weights config.RewardWeights) *QLearningScheduler {
	now := time.Now()
	q := &QLearningScheduler{
		config:        cfg,
		rewardWeights: weights,
		qTable:        make(map[string]map[ActionType]float64),
		isLearning:    true,
		stats:         make(map[string]interface{}),
		rng:           rand.New(rand.NewSource(time.Now().UnixNano())),

		// Initialize episode management
		currentEpisode:   1,
		episodeTaskCount: 0,
		episodeStartTime: now,
		lastEpisodeReset: now,

		// Performance optimization
		frequentStates: make(map[string]time.Time),
		cacheCleanup:   now,
	}

	logger.GetLogger().Infof("[Q-LEARNING-INIT] Q-learning scheduler initialized: LearningRate=%.3f, ExplorationRate=%.3f, DiscountFactor=%.3f",
		cfg.LearningRate, cfg.ExplorationRate, cfg.DiscountFactor)
	logger.GetLogger().Infof("[Q-LEARNING-INIT] Initial Q-table size: %d (empty)", len(q.qTable))
	logger.GetLogger().Infof("[EPISODE-CONFIG] Episode config: Type=%s, TasksPerEpisode=%d, TimePerEpisodeMinutes=%d, ResetOnEnd=%t",
		cfg.EpisodeConfig.Type, cfg.EpisodeConfig.TasksPerEpisode, cfg.EpisodeConfig.TimePerEpisodeMinutes, cfg.EpisodeConfig.ResetOnEpisodeEnd)

	return q
}

// Name returns the algorithm name
func (q *QLearningScheduler) Name() string {
	return "Q-Learning Scheduler"
}

// Schedule schedules tasks using Q-learning
func (q *QLearningScheduler) Schedule(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE] Schedule called: Tasks=%d, Episode=%d, TaskCount=%d, QTableSize=%d, IsLearning=%t",
		len(tasks), q.currentEpisode, q.episodeTaskCount, len(q.qTable), q.isLearning)
	
	if len(tasks) <= 1 {
		logger.GetLogger().Debugf("[Q-LEARNING-SCHEDULE] Skipping (tasks <= 1: %d)", len(tasks))
		return tasks
	}

	// Extract state features
	state := ExtractStateFeatures(tasks, nodeManager)
	stateKey := state.GetStateKey()
	logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-STATE] State extracted: QueueLength=%d, CPUUtil=%.2f, MemUtil=%.2f, Load=%.2f, StateKey=%s",
		state.QueueLength, state.CPUUtilization, state.MemoryUtilization, state.SystemLoad, stateKey)

	// Select action using Q-learning policy
	action := q.SelectAction(state)
	logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-ACTION] Action selected: Type=%d, Description=%s, Episode=%d",
		action.Type, action.Description, q.currentEpisode)

	// Apply the selected action
	reorderedTasks := ApplyAction(action, tasks)
	logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-APPLY] Action applied: Tasks=%d->%d, OrderChanged=%t",
		len(tasks), len(reorderedTasks), len(reorderedTasks) == len(tasks))

	// Store experience for each task if learning is enabled
	if q.isLearning && q.experienceManager != nil {
		logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-EXPERIENCE] Storing experiences: Tasks=%d, State=%s, Action=%s",
			len(reorderedTasks), stateKey, action.Description)
		for i, task := range reorderedTasks {
			q.experienceManager.StoreIncompleteExperience(task.GetTaskID(), state, action)
			if i < 3 || i == len(reorderedTasks)-1 {
				logger.GetLogger().Debugf("[Q-LEARNING-SCHEDULE-EXPERIENCE] Stored experience: TaskID=%s (%d/%d)",
					task.GetTaskID(), i+1, len(reorderedTasks))
			}
		}
		logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-EXPERIENCE] Experiences stored: Count=%d", len(reorderedTasks))

	// [DEBUG] Update episode task count
	// Update episode task count
	oldTaskCount := q.episodeTaskCount
	q.episodeTaskCount += len(tasks)
	// [DEBUG] Episode task count updated
	fmt.Printf("[DEBUG] [QLEARNING-SCHEDULE-EPISODE] Episode task count updated: Episode=%d, TaskCount=%d->%d (+%d tasks)\n",
		q.currentEpisode, oldTaskCount, q.episodeTaskCount, len(tasks))
	logger.GetLogger().Infof("[EPISODE-COUNT] Task count update: Episode=%d, Count=%d->%d (+%d tasks), TasksPerEpisode=%d, Progress=%.1f%%",
		q.currentEpisode, oldTaskCount, q.episodeTaskCount, len(tasks), q.config.EpisodeConfig.TasksPerEpisode,
		float64(q.episodeTaskCount)/float64(q.config.EpisodeConfig.TasksPerEpisode)*100.0)

		// [DEBUG] Check for episode completion
		// Check for episode completion
		fmt.Printf("[DEBUG] [QLEARNING-SCHEDULE-EPISODE-CHECK] About to check episode completion\n")
		q.checkEpisodeCompletion()
		// [DEBUG] Episode check complete
		fmt.Printf("[DEBUG] [QLEARNING-SCHEDULE-EPISODE-CHECK-DONE] Episode check complete\n")
	} else {
		// [DEBUG] Learning disabled or no experience manager
		fmt.Printf("[DEBUG] [QLEARNING-SCHEDULE-EXPERIENCE-SKIP] Learning disabled (isLearning=%t, expMgr=%v)\n", q.isLearning, q.experienceManager != nil)
	}

	logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-EXIT] Schedule returning: Tasks=%d, Episode=%d, TaskCount=%d",
		len(reorderedTasks), q.currentEpisode, q.episodeTaskCount)
	return reorderedTasks
}

// checkEpisodeCompletion checks if current episode should end and handles completion
func (q *QLearningScheduler) checkEpisodeCompletion() {
	// [DEBUG] Entry point for checkEpisodeCompletion
	fmt.Printf("[DEBUG] [EPISODE-CHECK-ENTRY] checkEpisodeCompletion called: CurrentEpisode=%d, EpisodeTaskCount=%d, EpisodeType=%s\n",
		q.currentEpisode, q.episodeTaskCount, q.config.EpisodeConfig.Type)
	
	episodeComplete := false

	switch q.config.EpisodeConfig.Type {
	case "task_based":
		// [DEBUG] Task-based episode check
		fmt.Printf("[DEBUG] [EPISODE-CHECK-TASK] Task-based episode: TaskCount=%d, TasksPerEpisode=%d\n",
			q.episodeTaskCount, q.config.EpisodeConfig.TasksPerEpisode)
		if q.episodeTaskCount >= q.config.EpisodeConfig.TasksPerEpisode {
			// [DEBUG] Episode complete by task count
			fmt.Printf("[DEBUG] [EPISODE-CHECK-TASK-COMPLETE] Episode complete: TaskCount=%d >= TasksPerEpisode=%d\n",
				q.episodeTaskCount, q.config.EpisodeConfig.TasksPerEpisode)
			episodeComplete = true
			logger.GetLogger().Infof("[EPISODE-CHECK] Episode completion triggered: Episode=%d, TaskCount=%d >= Threshold=%d",
				q.currentEpisode, q.episodeTaskCount, q.config.EpisodeConfig.TasksPerEpisode)
		} else {
			// [DEBUG] Episode not complete yet
			fmt.Printf("[DEBUG] [EPISODE-CHECK-TASK-NOT-COMPLETE] Episode not complete: TaskCount=%d < TasksPerEpisode=%d\n",
				q.episodeTaskCount, q.config.EpisodeConfig.TasksPerEpisode)
			logger.GetLogger().Debugf("[EPISODE-CHECK] Episode not complete: Episode=%d, TaskCount=%d < Threshold=%d, Progress=%.1f%%",
				q.currentEpisode, q.episodeTaskCount, q.config.EpisodeConfig.TasksPerEpisode,
				float64(q.episodeTaskCount)/float64(q.config.EpisodeConfig.TasksPerEpisode)*100.0)
		}
	case "time_based":
		// [DEBUG] Time-based episode check
		episodeDuration := time.Since(q.episodeStartTime)
		maxDuration := time.Duration(q.config.EpisodeConfig.TimePerEpisodeMinutes) * time.Minute
		fmt.Printf("[DEBUG] [EPISODE-CHECK-TIME] Time-based episode: Duration=%.2fs, MaxDuration=%.2fs\n",
			episodeDuration.Seconds(), maxDuration.Seconds())
		if episodeDuration >= maxDuration {
			// [DEBUG] Episode complete by time
			fmt.Printf("[DEBUG] [EPISODE-CHECK-TIME-COMPLETE] Episode complete: Duration=%.2fs >= MaxDuration=%.2fs\n",
				episodeDuration.Seconds(), maxDuration.Seconds())
			episodeComplete = true
		} else {
			// [DEBUG] Episode not complete yet
			fmt.Printf("[DEBUG] [EPISODE-CHECK-TIME-NOT-COMPLETE] Episode not complete: Duration=%.2fs < MaxDuration=%.2fs\n",
				episodeDuration.Seconds(), maxDuration.Seconds())
		}
	default:
		// [DEBUG] Unknown episode type
		fmt.Printf("[DEBUG] [EPISODE-CHECK-UNKNOWN] Unknown episode type: %s\n", q.config.EpisodeConfig.Type)
	}

	if episodeComplete {
		// [DEBUG] About to handle episode completion
		fmt.Printf("[DEBUG] [EPISODE-CHECK-COMPLETE] Episode complete, calling handleEpisodeCompletion: Episode=%d\n", q.currentEpisode)
		q.handleEpisodeCompletion()
		// [DEBUG] Episode completion handled
		fmt.Printf("[DEBUG] [EPISODE-CHECK-HANDLED] Episode completion handled: Episode=%d\n", q.currentEpisode)
	} else {
		// [DEBUG] Episode not complete
		fmt.Printf("[DEBUG] [EPISODE-CHECK-NOT-COMPLETE] Episode not complete yet: Episode=%d, TaskCount=%d\n",
			q.currentEpisode, q.episodeTaskCount)
	}
	
	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [EPISODE-CHECK-EXIT] checkEpisodeCompletion returning: Episode=%d, Complete=%t\n",
		q.currentEpisode, episodeComplete)
}

// handleEpisodeCompletion handles the completion of an episode
func (q *QLearningScheduler) handleEpisodeCompletion() {
	// [DEBUG] Entry point for handleEpisodeCompletion
	fmt.Printf("[DEBUG] [EPISODE-HANDLE-ENTRY] handleEpisodeCompletion called: Episode=%d, TaskCount=%d, ResetOnEnd=%t\n",
		q.currentEpisode, q.episodeTaskCount, q.config.EpisodeConfig.ResetOnEpisodeEnd)

	// Diagnostic logging: Q-table state at episode end
	qTableSize := len(q.qTable)
	episode := q.currentEpisode
	taskCount := q.episodeTaskCount
	episodeDuration := time.Since(q.episodeStartTime)

	logger.GetLogger().Infof("[EPISODE-COMPLETE] Episode %d completed: TaskCount=%d, QTableSize=%d, Duration=%.2fs, ExplorationRate=%.3f",
		episode, taskCount, qTableSize, episodeDuration.Seconds(), q.config.ExplorationRate)

	if qTableSize == 0 {
		logger.GetLogger().Warnf("[WARN-QTABLE-EMPTY] Episode %d ended with empty Q-table! No learning occurred. Episode=%d, TaskCount=%d, IsLearning=%t",
			episode, episode, taskCount, q.isLearning)
	}
	
	// Trigger weight adaptation if multi-objective is enabled
	if q.multiObjectiveCalculator != nil {
		// [DEBUG] Multi-objective calculator exists
		fmt.Printf("[DEBUG] [EPISODE-HANDLE-MULTIOBJ] Multi-objective calculator exists, checking if enabled\n")
		cfg := config.GetConfig()
		if cfg.RL.MultiObjective.Enabled && cfg.RL.MultiObjective.AdaptationEnabled {
			// [DEBUG] Multi-objective adaptation enabled
			fmt.Printf("[DEBUG] [EPISODE-HANDLE-MULTIOBJ-ENABLED] Multi-objective adaptation enabled, calling adaptWeights\n")
			logger.GetLogger().Infof("[EPISODE-COMPLETE-MULTIOBJ] Multi-objective adaptation triggered: Episode=%d, Enabled=%t",
				q.currentEpisode, cfg.RL.MultiObjective.AdaptationEnabled)
			if err := q.adaptWeights(); err != nil {
				// [DEBUG] Weight adaptation failed
				fmt.Printf("[DEBUG] [EPISODE-HANDLE-MULTIOBJ-ERROR] Warning: Failed to adapt weights at episode %d: %v\n", q.currentEpisode, err)
				fmt.Printf("Warning: Failed to adapt weights at episode %d: %v\n", q.currentEpisode, err)
				logger.GetLogger().Errorf("[ERROR-MULTIOBJ] Weight adaptation failed: Episode=%d, Error=%v", q.currentEpisode, err)
			} else {
				// [DEBUG] Weight adaptation succeeded
				fmt.Printf("[DEBUG] [EPISODE-HANDLE-MULTIOBJ-SUCCESS] Weight adaptation succeeded: Episode=%d\n", q.currentEpisode)
				logger.GetLogger().Infof("[EPISODE-COMPLETE-MULTIOBJ] Weight adaptation completed: Episode=%d", q.currentEpisode)
			}
		} else {
			// [DEBUG] Multi-objective adaptation not enabled
			fmt.Printf("[DEBUG] [EPISODE-HANDLE-MULTIOBJ-DISABLED] Multi-objective adaptation not enabled: Enabled=%t, AdaptationEnabled=%t\n",
				cfg.RL.MultiObjective.Enabled, cfg.RL.MultiObjective.AdaptationEnabled)
			logger.GetLogger().Debugf("[EPISODE-COMPLETE-MULTIOBJ] Multi-objective adaptation skipped: Enabled=%t, AdaptationEnabled=%t",
				cfg.RL.MultiObjective.Enabled, cfg.RL.MultiObjective.AdaptationEnabled)
		}
	} else {
		// [DEBUG] Multi-objective calculator not available
		fmt.Printf("[DEBUG] [EPISODE-HANDLE-MULTIOBJ-NONE] Multi-objective calculator not available\n")
		logger.GetLogger().Debugf("[EPISODE-COMPLETE-MULTIOBJ] Multi-objective calculator not available")
	}

	// Mark episode as complete in experience manager
	if q.experienceManager != nil {
		// [DEBUG] Experience manager exists
		fmt.Printf("[DEBUG] [EPISODE-HANDLE-EXPMGR] Experience manager exists, marking episode complete: Episode=%d, TaskCount=%d\n",
			q.currentEpisode, q.episodeTaskCount)
		fmt.Printf("Episode %d completed with %d tasks\n", q.currentEpisode, q.episodeTaskCount)
		logger.GetLogger().Infof("[EPISODE-COMPLETE-EXPMGR] Experience manager notified: Episode=%d, TaskCount=%d",
			q.currentEpisode, q.episodeTaskCount)
		q.experienceManager.MarkEpisodeComplete(q.currentEpisode)
		// [DEBUG] Episode marked complete
		fmt.Printf("[DEBUG] [EPISODE-HANDLE-EXPMGR-DONE] Episode marked complete in experience manager: Episode=%d\n", q.currentEpisode)
		logger.GetLogger().Infof("[EPISODE-COMPLETE-EXPMGR] Episode marked complete in experience manager: Episode=%d", q.currentEpisode)
	} else {
		// [DEBUG] Experience manager not available
		fmt.Printf("[DEBUG] [EPISODE-HANDLE-EXPMGR-NONE] Experience manager not available\n")
		logger.GetLogger().Warnf("[WARN-EPISODE] Experience manager not available for episode %d", q.currentEpisode)
	}

	// Reset episode if configured
	if q.config.EpisodeConfig.ResetOnEpisodeEnd {
		// [DEBUG] Reset on episode end enabled
		fmt.Printf("[DEBUG] [EPISODE-HANDLE-RESET] ResetOnEpisodeEnd=true, calling resetEpisode: Episode=%d\n", q.currentEpisode)
		logger.GetLogger().Infof("[EPISODE-COMPLETE-RESET] Resetting episode: Episode=%d", q.currentEpisode)
		q.resetEpisode()
		// [DEBUG] Episode reset complete
		fmt.Printf("[DEBUG] [EPISODE-HANDLE-RESET-DONE] Episode reset complete: Episode=%d\n", q.currentEpisode)
		logger.GetLogger().Infof("[EPISODE-RESET] Episode reset: Episode=%d, ExplorationRate=%.3f", q.currentEpisode, q.config.ExplorationRate)
	} else {
		// [DEBUG] Advance to next episode
		fmt.Printf("[DEBUG] [EPISODE-HANDLE-ADVANCE] ResetOnEpisodeEnd=false, calling advanceEpisode: Episode=%d\n", q.currentEpisode)
		oldEpisode := q.currentEpisode
		logger.GetLogger().Infof("[EPISODE-COMPLETE-ADVANCE] Advancing to next episode: %d->%d", oldEpisode, q.currentEpisode+1)
		q.advanceEpisode()
		// [DEBUG] Episode advance complete
		fmt.Printf("[DEBUG] [EPISODE-HANDLE-ADVANCE-DONE] Episode advance complete: Episode=%d\n", q.currentEpisode)
		logger.GetLogger().Infof("[EPISODE-ADVANCE] Episode advanced: %d->%d, TaskCount reset, StartTime updated", oldEpisode, q.currentEpisode)
	}
	
	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [EPISODE-HANDLE-EXIT] handleEpisodeCompletion returning: Episode=%d\n", q.currentEpisode)
}

// adaptWeights adapts reward weights based on recent performance
func (q *QLearningScheduler) adaptWeights() error {
	if q.multiObjectiveCalculator == nil {
		return fmt.Errorf("multi-objective calculator not initialized")
	}

	// Use the multi-objective calculator's built-in adaptation
	// The MultiObjectiveRewardCalculator already has adaptation logic
	// We can trigger it by calling its adaptation methods

	// For now, let's use a simplified version that leverages the existing calculator
	cfg := config.GetConfig()

	// Get recent performance from the multi-objective calculator's history
	recentPerformance := q.multiObjectiveCalculator.GetPerformanceHistory(cfg.RL.MultiObjective.AdaptationWindow)
	if len(recentPerformance) == 0 {
		return nil // No performance data to adapt on
	}

	// The MultiObjectiveRewardCalculator already handles weight adaptation internally
	// when CalculateMultiObjectiveReward is called with sufficient history
	// So we don't need to manually adapt weights here - it's handled automatically

	fmt.Printf("Episode %d: Weight adaptation triggered (handled by MultiObjectiveRewardCalculator)\n", q.currentEpisode)

	return nil
}

// resetEpisode resets the current episode (for episodic learning)
func (q *QLearningScheduler) resetEpisode() {
	// [DEBUG] Entry point for resetEpisode
	fmt.Printf("[DEBUG] [EPISODE-RESET-ENTRY] resetEpisode called: Episode=%d, TaskCount=%d, ExplorationRate=%.3f\n",
		q.currentEpisode, q.episodeTaskCount, q.config.ExplorationRate)
	
	oldEpisode := q.currentEpisode
	oldTaskCount := q.episodeTaskCount
	oldExplorationRate := q.config.ExplorationRate
	
	// Reset episode counters
	q.episodeTaskCount = 0
	q.episodeStartTime = time.Now()
	q.lastEpisodeReset = time.Now()
	// [DEBUG] Episode counters reset
	fmt.Printf("[DEBUG] [EPISODE-RESET-COUNTERS] Episode counters reset: TaskCount=%d->0, StartTime=%v, LastReset=%v\n",
		oldTaskCount, q.episodeStartTime, q.lastEpisodeReset)

	// Reset exploration rate to initial value for fresh exploration
	cfg := config.GetConfig()
	q.config.ExplorationRate = cfg.RL.ExplorationRate
	// [DEBUG] Exploration rate reset
	fmt.Printf("[DEBUG] [EPISODE-RESET-EXPLORATION] Exploration rate reset: %.3f->%.3f\n",
		oldExplorationRate, q.config.ExplorationRate)

	fmt.Printf("Episode %d reset: Fresh start with exploration rate %.3f\n",
		oldEpisode, q.config.ExplorationRate)
	
	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [EPISODE-RESET-EXIT] resetEpisode returning: Episode=%d, TaskCount=%d, ExplorationRate=%.3f\n",
		q.currentEpisode, q.episodeTaskCount, q.config.ExplorationRate)
}

// advanceEpisode advances to the next episode without resetting learning
func (q *QLearningScheduler) advanceEpisode() {
	// [DEBUG] Entry point for advanceEpisode
	fmt.Printf("[DEBUG] [EPISODE-ADVANCE-ENTRY] advanceEpisode called: Episode=%d, TaskCount=%d, ExplorationRate=%.3f\n",
		q.currentEpisode, q.episodeTaskCount, q.config.ExplorationRate)
	
	oldEpisode := q.currentEpisode
	oldTaskCount := q.episodeTaskCount
	
	// Advance episode
	q.currentEpisode++
	q.episodeTaskCount = 0
	q.episodeStartTime = time.Now()
	// [DEBUG] Episode advanced
	fmt.Printf("[DEBUG] [EPISODE-ADVANCE-DONE] Episode advanced: %d->%d, TaskCount=%d->0, StartTime=%v\n",
		oldEpisode, q.currentEpisode, oldTaskCount, q.episodeStartTime)

	fmt.Printf("Advanced to episode %d (continuous learning)\n", q.currentEpisode)
	
	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [EPISODE-ADVANCE-EXIT] advanceEpisode returning: Episode=%d, TaskCount=%d, ExplorationRate=%.3f\n",
		q.currentEpisode, q.episodeTaskCount, q.config.ExplorationRate)
}

// SelectAction selects an action with optimized Q-table access and caching
func (q *QLearningScheduler) SelectAction(state *StateFeatures) Action {
	// [DEBUG] Entry point for SelectAction
	fmt.Printf("[DEBUG] [QLEARNING-SELECT-ENTRY] QLearning.SelectAction called\n")
	
	// [DEBUG] Getting state key
	stateKey := state.GetStateKey()
	fmt.Printf("[DEBUG] [QLEARNING-SELECT-STATE-KEY] State key: %s\n", stateKey)

	// Diagnostic logging: Q-table state before selection (CHANGED TO INFO LEVEL)
	qTableSize := len(q.qTable)
	stateExists := q.qTable[stateKey] != nil

	logger.GetLogger().Infof("[Q-LEARNING-SELECT] Selecting action: State=%s, QTableSize=%d, StateExists=%v, Episode=%d, TaskCount=%d, ExplorationRate=%.3f",
		stateKey, qTableSize, stateExists, q.currentEpisode, q.episodeTaskCount, q.config.ExplorationRate)

	// [DEBUG] Track frequently accessed states
	// Track frequently accessed states for optimization
	q.frequentStates[stateKey] = time.Now()
	fmt.Printf("[DEBUG] [QLEARNING-SELECT-FREQ-STATE] Tracked frequent state\n")

	// [DEBUG] Clean up cache if needed
	// Clean up old cache entries periodically (every 100 accesses)
	if time.Since(q.cacheCleanup) > time.Minute {
		fmt.Printf("[DEBUG] [QLEARNING-SELECT-CLEANUP] Cleaning up frequent states cache\n")
		q.cleanupFrequentStatesCache()
		fmt.Printf("[DEBUG] [QLEARNING-SELECT-CLEANUP-DONE] Cleanup complete\n")
	}

	// [DEBUG] Initialize Q-values if needed
	// Initialize Q-values for this state if not exists
	if _, exists := q.qTable[stateKey]; !exists {
		// [DEBUG] Initializing Q-values for new state
		fmt.Printf("[DEBUG] [QLEARNING-SELECT-INIT-Q] Initializing Q-values for new state: %s\n", stateKey)
		q.initializeStateQValues(stateKey)
		// [DEBUG] Q-values initialized
		fmt.Printf("[DEBUG] [QLEARNING-SELECT-INIT-Q-DONE] Q-values initialized\n")
	} else {
		// [DEBUG] State already exists in Q-table
		fmt.Printf("[DEBUG] [QLEARNING-SELECT-Q-EXISTS] State already exists in Q-table\n")
	}

	// Epsilon-greedy action selection
	explorationRate := q.config.ExplorationRate
	randomValue := q.rng.Float64()
	isExploring := randomValue < explorationRate
	logger.GetLogger().Infof("[Q-LEARNING-SELECT-EPSILON] Epsilon-greedy: ExplorationRate=%.3f, Random=%.3f, Explore=%t, Episode=%d",
		explorationRate, randomValue, isExploring, q.currentEpisode)
	fmt.Printf("[DEBUG] [QLEARNING-SELECT-EPSILON] ExplorationRate=%.3f, RandomValue=%.3f, isLearning=%t\n",
		explorationRate, randomValue, q.isLearning)
	
	if q.isLearning && randomValue < explorationRate {
		// [DEBUG] Exploring
		fmt.Printf("[DEBUG] [QLEARNING-SELECT-EXPLORE] Exploring: choosing random action\n")
		// Explore: choose random action with pre-allocated actions slice
		result := q.getRandomAction()
		// [DEBUG] Random action selected
		fmt.Printf("[DEBUG] [QLEARNING-SELECT-EXPLORE-DONE] Random action selected: Type=%d\n", result.Type)
		logger.GetLogger().Infof("[Q-LEARNING-SELECT-EXPLORE] Exploring: Random action selected, Type=%d, Description=%s, Episode=%d",
			result.Type, result.Description, q.currentEpisode)
		return result
	}

	// [DEBUG] Exploiting
	// Exploit: choose best action with optimized lookup and caching
	fmt.Printf("[DEBUG] [QLEARNING-SELECT-EXPLOIT] Exploiting: choosing best action\n")
	result := q.getBestActionOptimized(stateKey)
	// [DEBUG] Best action selected
	fmt.Printf("[DEBUG] [QLEARNING-SELECT-EXPLOIT-DONE] Best action selected: Type=%d, Description=%s\n", result.Type, result.Description)
	
	// Get best Q-value for logging
	bestQValue := math.Inf(-1)
	if stateActions, exists := q.qTable[stateKey]; exists {
		if qVal, exists := stateActions[result.Type]; exists {
			bestQValue = qVal
		}
	}
	logger.GetLogger().Infof("[Q-LEARNING-SELECT-EXPLOIT] Exploiting: Best action selected, Type=%d, Description=%s, QValue=%.3f, Episode=%d",
		result.Type, result.Description, bestQValue, q.currentEpisode)
	
	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [QLEARNING-SELECT-EXIT] QLearning.SelectAction returning: Type=%d\n", result.Type)

	// Diagnostic logging: Q-table state after selection (CHANGED TO INFO LEVEL)
	finalQTableSize := len(q.qTable)

	logger.GetLogger().Infof("[Q-LEARNING-SELECT] Action selected: %s, QTableSize=%d (after selection), Episode=%d",
		result.Description, finalQTableSize, q.currentEpisode)

	return result
}

// getRandomAction returns a random action with optimized access
func (q *QLearningScheduler) getRandomAction() Action {
	// Pre-allocate actions slice for better performance
	actions := GetAllActions()
	return actions[q.rng.Intn(len(actions))]
}

// getBestActionOptimized finds the best action with optimized Q-table lookup
func (q *QLearningScheduler) getBestActionOptimized(stateKey string) Action {
	stateActions := q.qTable[stateKey]
	
	// Pre-allocate for better performance
	bestAction := ActionNone
	bestValue := math.Inf(-1)

	// Optimized iteration with early exit for common cases
	for actionType, qValue := range stateActions {
		if qValue > bestValue {
			bestValue = qValue
			bestAction = actionType
		}
	}

	// Return the action with the best Q-value using optimized lookup
	return q.getActionByType(bestAction)
}

// getActionByType returns action by type with optimized lookup
func (q *QLearningScheduler) getActionByType(actionType ActionType) Action {
	// Pre-allocated actions for faster lookup
	actions := GetAllActions()
	for _, action := range actions {
		if action.Type == actionType {
			return action
		}
	}
	return actions[0] // Fallback
}

// cleanupFrequentStatesCache removes old entries from frequent states cache
func (q *QLearningScheduler) cleanupFrequentStatesCache() {
	cutoff := time.Now().Add(-5 * time.Minute)
	for stateKey, lastAccess := range q.frequentStates {
		if lastAccess.Before(cutoff) {
			delete(q.frequentStates, stateKey)
		}
	}
	q.cacheCleanup = time.Now()
}

// UpdatePolicy updates Q-values based on experience
func (q *QLearningScheduler) UpdatePolicy(experience *Experience) error {
	if !q.isLearning {
		logger.GetLogger().Debugf("[Q-LEARNING-UPDATE] Learning disabled, skipping update")
		return nil
	}

	currentStateKey := experience.State.GetStateKey()
	nextStateKey := experience.NextState.GetStateKey()

	logger.GetLogger().Infof("[Q-LEARNING-UPDATE] UpdatePolicy called: State=%s, NextState=%s, Action=%s, Reward=%.3f, Done=%t, Episode=%d",
		currentStateKey, nextStateKey, experience.Action.Description, experience.Reward, experience.Done, q.currentEpisode)

	// Track Q-table updates
	oldSize := len(q.qTable)

	// Initialize Q-values if not exists
	q.initializeStateQValues(currentStateKey)
	q.initializeStateQValues(nextStateKey)

	// Get current Q-value
	currentQ := q.qTable[currentStateKey][experience.Action.Type]

	// Find max Q-value for next state
	maxNextQ := math.Inf(-1)
	for _, qValue := range q.qTable[nextStateKey] {
		if qValue > maxNextQ {
			maxNextQ = qValue
		}
	}

	if math.IsInf(maxNextQ, -1) {
		maxNextQ = 0.0
	}

	logger.GetLogger().Infof("[Q-LEARNING-UPDATE-QVALUES] Q-values: CurrentQ=%.3f, MaxNextQ=%.3f, Reward=%.3f, LearningRate=%.3f, DiscountFactor=%.3f",
		currentQ, maxNextQ, experience.Reward, q.config.LearningRate, q.config.DiscountFactor)

	// Q-learning update rule: Q(s,a) = Q(s,a) + α[r + γ*max(Q(s',a')) - Q(s,a)]
	var targetQ float64
	if experience.Done {
		targetQ = experience.Reward
	} else {
		targetQ = experience.Reward + q.config.DiscountFactor*maxNextQ
	}

	newQ := currentQ + q.config.LearningRate*(targetQ-currentQ)
	q.qTable[currentStateKey][experience.Action.Type] = newQ

	newSize := len(q.qTable)

	// Log Q-table update (CHANGED TO INFO LEVEL)
	logger.GetLogger().Infof("[Q-LEARNING-UPDATE] Q-table updated: State=%s, Action=%s, Reward=%.3f, QTableSize=%d->%d, CurrentQ=%.3f, NewQ=%.3f, Episode=%d",
		currentStateKey, q.getActionDescription(experience.Action.Type), experience.Reward, oldSize, newSize, currentQ, newQ, q.currentEpisode)

	// Log if this is first update (Q-table was empty)
	if oldSize == 0 && newSize > 0 {
		logger.GetLogger().Infof("[Q-LEARNING-UPDATE] First Q-table entry created! State=%s, Action=%s, Reward=%.3f, Episode=%d",
			currentStateKey, q.getActionDescription(experience.Action.Type), experience.Reward, q.currentEpisode)
	}

	// Decay exploration rate
	if q.config.ExplorationRate > q.config.MinExploration {
		q.config.ExplorationRate *= q.config.ExplorationDecay
	}

	return nil
}

// getActionDescription returns action description for logging
func (q *QLearningScheduler) getActionDescription(actionType ActionType) string {
	actions := GetAllActions()
	for _, action := range actions {
		if action.Type == actionType {
			return action.Description
		}
	}
	return fmt.Sprintf("ActionType(%d)", actionType)
}

// initializeStateQValues initializes Q-values for a state
func (q *QLearningScheduler) initializeStateQValues(stateKey string) {
	if _, exists := q.qTable[stateKey]; !exists {
		q.qTable[stateKey] = make(map[ActionType]float64)
		actions := GetAllActions()
		for _, action := range actions {
			q.qTable[stateKey][action.Type] = 0.0
		}
	}
}

// IsLearning returns whether the agent is in learning mode
func (q *QLearningScheduler) IsLearning() bool {
	return q.isLearning
}

// SetLearningMode sets the learning mode
func (q *QLearningScheduler) SetLearningMode(enabled bool) {
	q.isLearning = enabled
}

// UpdateRewardWeights updates the reward calculator weights
func (q *QLearningScheduler) UpdateRewardWeights(weights config.RewardWeights) error {
	q.rewardWeights = weights

	// Also update multi-objective calculator if present
	if q.multiObjectiveCalculator != nil {
		q.multiObjectiveCalculator.SetRewardWeights(weights)
	}

	return nil
}

// GetStats returns algorithm statistics
func (q *QLearningScheduler) GetStats() map[string]interface{} {
	q.stats["name"] = q.Name()
	q.stats["learning_rate"] = q.config.LearningRate
	q.stats["discount_factor"] = q.config.DiscountFactor
	q.stats["exploration_rate"] = q.config.ExplorationRate
	q.stats["is_learning"] = q.isLearning
	q.stats["q_table_size"] = len(q.qTable)

	// Episode statistics - now using currentEpisode field actively
	q.stats["current_episode"] = q.currentEpisode
	q.stats["episode_task_count"] = q.episodeTaskCount
	q.stats["episode_type"] = q.config.EpisodeConfig.Type

	if q.config.EpisodeConfig.Type == "time_based" {
		episodeDuration := time.Since(q.episodeStartTime).Minutes()
		q.stats["episode_duration_minutes"] = episodeDuration
		q.stats["episode_progress"] = episodeDuration / float64(q.config.EpisodeConfig.TimePerEpisodeMinutes)
	} else {
		progress := float64(q.episodeTaskCount) / float64(q.config.EpisodeConfig.TasksPerEpisode)
		q.stats["episode_progress"] = progress
	}

	// Calculate average Q-values
	totalQ := 0.0
	count := 0
	for _, actions := range q.qTable {
		for _, qValue := range actions {
			totalQ += qValue
			count++
		}
	}

	if count > 0 {
		q.stats["avg_q_value"] = totalQ / float64(count)
	} else {
		q.stats["avg_q_value"] = 0.0
	}

	return q.stats
}

// Configure configures the algorithm with parameters
func (q *QLearningScheduler) Configure(params map[string]interface{}) error {
	if lr, ok := params["learning_rate"].(float64); ok {
		q.config.LearningRate = lr
	}
	if df, ok := params["discount_factor"].(float64); ok {
		q.config.DiscountFactor = df
	}
	if er, ok := params["exploration_rate"].(float64); ok {
		q.config.ExplorationRate = er
	}
	return nil
}

// GetQTable returns a copy of the Q-table for inspection
func (q *QLearningScheduler) GetQTable() map[string]map[ActionType]float64 {
	qTableCopy := make(map[string]map[ActionType]float64)
	for state, actions := range q.qTable {
		qTableCopy[state] = make(map[ActionType]float64)
		for action, value := range actions {
			qTableCopy[state][action] = value
		}
	}
	return qTableCopy
}

// SaveQTable saves Q-table to a file (placeholder for model persistence)
func (q *QLearningScheduler) SaveQTable(filepath string) error {
	// Note: This method is kept for interface compatibility
	// Actual persistence is handled by ModelStorage.SaveQLearningAgent()
	return fmt.Errorf("use ModelStorage.SaveQLearningAgent() for Q-table persistence")
}

// LoadQTable loads Q-table from a file (placeholder for model persistence)
func (q *QLearningScheduler) LoadQTable(filepath string) error {
	// Note: This method is kept for interface compatibility
	// Actual loading is handled by ModelStorage.LoadQLearningAgent()
	return fmt.Errorf("use ModelStorage.LoadQLearningAgent() for Q-table loading")
}

// Episode Management Methods

// GetCurrentEpisode returns the current episode number
func (q *QLearningScheduler) GetCurrentEpisode() int {
	return q.currentEpisode
}

// GetEpisodeProgress returns the progress of current episode (0.0 to 1.0)
func (q *QLearningScheduler) GetEpisodeProgress() float64 {
	switch q.config.EpisodeConfig.Type {
	case "task_based":
		if q.config.EpisodeConfig.TasksPerEpisode <= 0 {
			return 0.0
		}
		progress := float64(q.episodeTaskCount) / float64(q.config.EpisodeConfig.TasksPerEpisode)
		return math.Min(progress, 1.0)
	case "time_based":
		if q.config.EpisodeConfig.TimePerEpisodeMinutes <= 0 {
			return 0.0
		}
		elapsed := time.Since(q.episodeStartTime).Minutes()
		progress := elapsed / float64(q.config.EpisodeConfig.TimePerEpisodeMinutes)
		return math.Min(progress, 1.0)
	default:
		return 0.0
	}
}

// ForceEpisodeCompletion forces the completion of current episode
func (q *QLearningScheduler) ForceEpisodeCompletion() {
	q.handleEpisodeCompletion()
}

// Experience Manager Integration

// SetExperienceManager sets the experience manager
func (q *QLearningScheduler) SetExperienceManager(em *ExperienceManager) {
	q.experienceManager = em
}

// GetExperienceManager returns the experience manager
func (q *QLearningScheduler) GetExperienceManager() *ExperienceManager {
	return q.experienceManager
}

// SetMultiObjectiveCalculator sets the multi-objective calculator for weight adaptation
func (q *QLearningScheduler) SetMultiObjectiveCalculator(calc *MultiObjectiveRewardCalculator) {
	q.multiObjectiveCalculator = calc
}

// ProcessTaskCompletion handles task completion for experience collection
func (q *QLearningScheduler) ProcessTaskCompletion(task TaskEntry, report *pb.TaskCompletionReport, nodeStatus *pb.FogNode, queueLength int) error {
	fmt.Printf("[DEBUG] [QLEARNING-COMPLETE-ENTRY] ProcessTaskCompletion called: TaskID=%s, QueueLength=%d, HasNodeStatus=%t, HasExpMgr=%t\n", 
		task.GetTaskID(), queueLength, nodeStatus != nil, q.experienceManager != nil)
	logger.GetLogger().Infof("[QLEARNING-COMPLETE-ENTRY] ProcessTaskCompletion: TaskID=%s, QueueLength=%d, HasNodeStatus=%t, HasExpMgr=%t", 
		task.GetTaskID(), queueLength, nodeStatus != nil, q.experienceManager != nil)
	
	if q.experienceManager == nil {
		fmt.Printf("[DEBUG] [QLEARNING-COMPLETE-ERROR] Experience manager not initialized: TaskID=%s\n", task.GetTaskID())
		logger.GetLogger().Errorf("[QLEARNING-COMPLETE-ERROR] Experience manager not initialized: TaskID=%s", task.GetTaskID())
		return fmt.Errorf("experience manager not initialized")
	}

	// Validate task completion report
	if report == nil {
		fmt.Printf("[DEBUG] [QLEARNING-COMPLETE-ERROR] Report is nil: TaskID=%s\n", task.GetTaskID())
		return fmt.Errorf("task completion report is nil for task %s", task.GetTaskID())
	}

	if report.Metrics == nil {
		fmt.Printf("[DEBUG] [QLEARNING-COMPLETE-ERROR] Metrics missing: TaskID=%s\n", task.GetTaskID())
		return fmt.Errorf("system metrics missing in completion report for task %s", task.GetTaskID())
	}

	// Complete the experience with node status and actual queue length from completion report
	fmt.Printf("[DEBUG] [QLEARNING-COMPLETE-CALL] Calling experienceManager.CompleteExperience: TaskID=%s\n", task.GetTaskID())
	err := q.experienceManager.CompleteExperience(task.GetTaskID(), report, nodeStatus, queueLength)
	if err != nil {
		// Log but don't fail completely - allows system to continue
		fmt.Printf("[DEBUG] [QLEARNING-COMPLETE-ERROR] experienceManager.CompleteExperience failed: TaskID=%s, Error=%v\n", 
			task.GetTaskID(), err)
		logger.GetLogger().Errorf("[QLEARNING-COMPLETE-ERROR] experienceManager.CompleteExperience failed: TaskID=%s, Error=%v", 
			task.GetTaskID(), err)
		return fmt.Errorf("experience completion failed for task %s: %w", task.GetTaskID(), err)
	}

	// Immediate Q-table update confirmation
	if q.isLearning {
		fmt.Printf("[DEBUG] [QLEARNING-COMPLETE-SUCCESS] Q-table updated for task %s (Episode %d, QTableSize=%d)\n", 
			task.GetTaskID(), q.currentEpisode, len(q.qTable))
		logger.GetLogger().Infof("[QLEARNING-COMPLETE-SUCCESS] Q-table updated for task %s (Episode %d, QTableSize=%d)", 
			task.GetTaskID(), q.currentEpisode, len(q.qTable))
	}

	return nil
}

// GetEpisodeTaskCount returns the current episode task count
func (q *QLearningScheduler) GetEpisodeTaskCount() int {
	return q.episodeTaskCount
}

// Getter methods for model persistence
func (q *QLearningScheduler) GetEpisodeStartTime() time.Time {
	return q.episodeStartTime
}

func (q *QLearningScheduler) GetLastEpisodeReset() time.Time {
	return q.lastEpisodeReset
}

func (q *QLearningScheduler) GetConfig() config.RLConfig {
	return q.config
}

func (q *QLearningScheduler) GetRewardWeights() config.RewardWeights {
	return q.rewardWeights
}

// Setter methods for model persistence
func (q *QLearningScheduler) SetCurrentEpisode(episode int) {
	q.currentEpisode = episode
}

func (q *QLearningScheduler) SetEpisodeTaskCount(count int) {
	q.episodeTaskCount = count
}

func (q *QLearningScheduler) SetEpisodeStartTime(startTime time.Time) {
	q.episodeStartTime = startTime
}

func (q *QLearningScheduler) SetLastEpisodeReset(resetTime time.Time) {
	q.lastEpisodeReset = resetTime
}

func (q *QLearningScheduler) SetExplorationRate(rate float64) {
	q.config.ExplorationRate = rate
}

func (q *QLearningScheduler) SetLearning(enabled bool) {
	q.isLearning = enabled
}

func (q *QLearningScheduler) SetRewardWeights(weights config.RewardWeights) {
	q.rewardWeights = weights
	// Also update multi-objective calculator if present
	if q.multiObjectiveCalculator != nil {
		q.multiObjectiveCalculator.SetRewardWeights(weights)
	}
}

func (q *QLearningScheduler) SetQTable(qTable map[string]map[ActionType]float64) {
	q.qTable = qTable
}

// GetMultiObjectiveCalculator returns the MultiObjectiveCalculator associated with this scheduler.
func (q *QLearningScheduler) GetMultiObjectiveCalculator() *MultiObjectiveRewardCalculator {
	return q.multiObjectiveCalculator
}
