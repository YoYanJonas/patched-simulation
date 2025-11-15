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

	// Model persistence callback (lightweight - just marks dirty flag)
	onDirty func() // Callback to mark model as dirty when Q-table updates

	// Node status tracker for real CPU/Memory metrics (from completion reports)
	nodeStatusTracker NodeStatusTracker // Optional: can be nil if not set
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
		return tasks
	}

	// Extract state features using NodeStatusTracker (if available)
	// Use tracker for real CPU/Memory metrics, fallback to nil if not set
	var tracker NodeStatusTracker = q.nodeStatusTracker
	if tracker == nil {
	}
	state := ExtractStateFeatures(tasks, tracker)
	stateKey := state.GetStateKey()
	logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-STATE] State extracted: QueueLength=%d, CPUUtil=%.2f, MemUtil=%.2f, Load=%.2f, StateKey=%s",
		state.QueueLength, state.CPUUtilization, state.MemoryUtilization, state.SystemLoad, stateKey)

	// Select action using Q-learning policy
	action := q.SelectAction(state)
	logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-ACTION] Action selected: Type=%d, Description=%s, Episode=%d",
		action.Type, action.Description, q.currentEpisode)

	// Apply the selected action
	reorderedTasks := ApplyAction(action, tasks)
	logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-APPLY] Action applied: Tasks=%d->%d",
		len(tasks), len(reorderedTasks))
	
	// CRITICAL VALIDATION: Ensure no tasks are lost during action application
	if len(reorderedTasks) != len(tasks) {
		logger.GetLogger().Errorf("[Q-LEARNING-SCHEDULE-ERROR] Task count mismatch: input=%d, output=%d", 
			len(tasks), len(reorderedTasks))
		return tasks
	}

	// Store experience for each task if learning is enabled
	if q.isLearning && q.experienceManager != nil {
		logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-EXPERIENCE] Storing experiences: Tasks=%d, State=%s, Action=%s",
			len(reorderedTasks), stateKey, action.Description)
		for _, task := range reorderedTasks {
			// CRITICAL: iFogSim sends cloudletId in req.TaskId when completing, so we must use cloudletId for lookup
			// Try to get cloudletId from TaskEntry if it's a *models.TaskEntry
			taskIdForStore := task.GetTaskID()
			cloudletIdForStore := ""
			
			// Type assert to get cloudletId if available (TaskEntry interface -> *models.TaskEntry)
			if taskEntry, ok := task.(interface{ GetCloudletId() string }); ok {
				cloudletIdForStore = taskEntry.GetCloudletId()
			}
			
			// CRITICAL FIX: Use cloudletId if available, otherwise fallback to TaskId
			// But prefer cloudletId since completion report uses cloudletId
			experienceKey := cloudletIdForStore
			if experienceKey == "" {
				experienceKey = taskIdForStore
				logger.GetLogger().Warnf("cloudletId not available, using TaskId=%s as fallback", taskIdForStore)
			}
			
			q.experienceManager.StoreIncompleteExperience(experienceKey, state, action)
		}
		logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-EXPERIENCE] Experiences stored: Count=%d", len(reorderedTasks))
	}

	// Update episode task count
	oldTaskCount := q.episodeTaskCount
	q.episodeTaskCount += len(tasks)
	logger.GetLogger().Infof("[EPISODE-COUNT] Task count update: Episode=%d, Count=%d->%d (+%d tasks), TasksPerEpisode=%d, Progress=%.1f%%",
		q.currentEpisode, oldTaskCount, q.episodeTaskCount, len(tasks), q.config.EpisodeConfig.TasksPerEpisode,
		float64(q.episodeTaskCount)/float64(q.config.EpisodeConfig.TasksPerEpisode)*100.0)

	if q.isLearning && q.experienceManager != nil {
		// Check for episode completion
		q.checkEpisodeCompletion()
	}

	logger.GetLogger().Infof("[Q-LEARNING-SCHEDULE-EXIT] Schedule returning: Tasks=%d, Episode=%d, TaskCount=%d",
		len(reorderedTasks), q.currentEpisode, q.episodeTaskCount)
	return reorderedTasks
}

// checkEpisodeCompletion checks if current episode should end and handles completion
func (q *QLearningScheduler) checkEpisodeCompletion() {
	episodeComplete := false

	switch q.config.EpisodeConfig.Type {
	case "task_based":
		if q.episodeTaskCount >= q.config.EpisodeConfig.TasksPerEpisode {
			episodeComplete = true
			logger.GetLogger().Infof("[EPISODE-CHECK] Episode completion triggered: Episode=%d, TaskCount=%d >= Threshold=%d",
				q.currentEpisode, q.episodeTaskCount, q.config.EpisodeConfig.TasksPerEpisode)
		}
	case "time_based":
		episodeDuration := time.Since(q.episodeStartTime)
		maxDuration := time.Duration(q.config.EpisodeConfig.TimePerEpisodeMinutes) * time.Minute
		if episodeDuration >= maxDuration {
			episodeComplete = true
		}
	default:
	}

	if episodeComplete {
		q.handleEpisodeCompletion()
	}
}

// handleEpisodeCompletion handles the completion of an episode
func (q *QLearningScheduler) handleEpisodeCompletion() {
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
		cfg := config.GetConfig()
		if cfg.RL.MultiObjective.Enabled && cfg.RL.MultiObjective.AdaptationEnabled {
			logger.GetLogger().Infof("[EPISODE-COMPLETE-MULTIOBJ] Multi-objective adaptation triggered: Episode=%d, Enabled=%t",
				q.currentEpisode, cfg.RL.MultiObjective.AdaptationEnabled)
			if err := q.adaptWeights(); err != nil {
				logger.GetLogger().Errorf("[ERROR-MULTIOBJ] Weight adaptation failed: Episode=%d, Error=%v", q.currentEpisode, err)
			} else {
				logger.GetLogger().Infof("[EPISODE-COMPLETE-MULTIOBJ] Weight adaptation completed: Episode=%d", q.currentEpisode)
			}
		}
	} else {
	}

	// Mark episode as complete in experience manager
	if q.experienceManager != nil {
		logger.GetLogger().Infof("[EPISODE-COMPLETE-EXPMGR] Experience manager notified: Episode=%d, TaskCount=%d",
			q.currentEpisode, q.episodeTaskCount)
		q.experienceManager.MarkEpisodeComplete(q.currentEpisode)
		logger.GetLogger().Infof("[EPISODE-COMPLETE-EXPMGR] Episode marked complete in experience manager: Episode=%d", q.currentEpisode)
	} else {
		logger.GetLogger().Warnf("[WARN-EPISODE] Experience manager not available for episode %d", q.currentEpisode)
	}

	// Reset episode if configured
	if q.config.EpisodeConfig.ResetOnEpisodeEnd {
		logger.GetLogger().Infof("[EPISODE-COMPLETE-RESET] Resetting episode: Episode=%d", q.currentEpisode)
		q.resetEpisode()
		logger.GetLogger().Infof("[EPISODE-RESET] Episode reset: Episode=%d, ExplorationRate=%.3f", q.currentEpisode, q.config.ExplorationRate)
	} else {
		oldEpisode := q.currentEpisode
		logger.GetLogger().Infof("[EPISODE-COMPLETE-ADVANCE] Advancing to next episode: %d->%d", oldEpisode, q.currentEpisode+1)
		q.advanceEpisode()
		logger.GetLogger().Infof("[EPISODE-ADVANCE] Episode advanced: %d->%d, TaskCount reset, StartTime updated", oldEpisode, q.currentEpisode)
	}
	
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


	return nil
}

// resetEpisode resets the current episode (for episodic learning)
func (q *QLearningScheduler) resetEpisode() {
	// Reset episode counters
	q.episodeTaskCount = 0
	q.episodeStartTime = time.Now()
	q.lastEpisodeReset = time.Now()

	// Reset exploration rate to initial value for fresh exploration
	cfg := config.GetConfig()
	q.config.ExplorationRate = cfg.RL.ExplorationRate
}

// advanceEpisode advances to the next episode without resetting learning
func (q *QLearningScheduler) advanceEpisode() {
	// Advance episode
	q.currentEpisode++
	q.episodeTaskCount = 0
	q.episodeStartTime = time.Now()
}

// SelectAction selects an action with optimized Q-table access and caching
func (q *QLearningScheduler) SelectAction(state *StateFeatures) Action {
	
	stateKey := state.GetStateKey()

	// Diagnostic logging: Q-table state before selection (CHANGED TO INFO LEVEL)
	qTableSize := len(q.qTable)
	stateExists := q.qTable[stateKey] != nil

	logger.GetLogger().Infof("[Q-LEARNING-SELECT] Selecting action: State=%s, QTableSize=%d, StateExists=%v, Episode=%d, TaskCount=%d, ExplorationRate=%.3f",
		stateKey, qTableSize, stateExists, q.currentEpisode, q.episodeTaskCount, q.config.ExplorationRate)

	// Track frequently accessed states for optimization
	q.frequentStates[stateKey] = time.Now()

	// Clean up old cache entries periodically (every 100 accesses)
	if time.Since(q.cacheCleanup) > time.Minute {
		q.cleanupFrequentStatesCache()
	}

	// Initialize Q-values for this state if not exists
	if _, exists := q.qTable[stateKey]; !exists {
		q.initializeStateQValues(stateKey)
	} else {
	}
	
	// Check if deadline is disabled and log
	deadlineDisabled := q.rewardWeights.DeadlineMiss == 0.0
	if deadlineDisabled {
	}

	// Epsilon-greedy action selection
	explorationRate := q.config.ExplorationRate
	randomValue := q.rng.Float64()
	isExploring := randomValue < explorationRate
	logger.GetLogger().Infof("[Q-LEARNING-SELECT-EPSILON] Epsilon-greedy: ExplorationRate=%.3f, Random=%.3f, Explore=%t, Episode=%d",
		explorationRate, randomValue, isExploring, q.currentEpisode)
	
	if q.isLearning && randomValue < explorationRate {
		// Explore: choose random action with pre-allocated actions slice
		result := q.getRandomAction()
		logger.GetLogger().Infof("[Q-LEARNING-SELECT-EXPLORE] Exploring: Random action selected, Type=%d, Description=%s, Episode=%d",
			result.Type, result.Description, q.currentEpisode)
		return result
	}

	// Exploit: choose best action with optimized lookup and caching
	result := q.getBestActionOptimized(stateKey)
	
	// Get best Q-value and log ALL Q-values for this state
	bestQValue := math.Inf(-1)
	allQValues := make(map[int]float64)
	if stateActions, exists := q.qTable[stateKey]; exists {
		for actionType, qVal := range stateActions {
			allQValues[int(actionType)] = qVal
			if actionType == result.Type {
				bestQValue = qVal
			}
		}
	}
	logger.GetLogger().Infof("[Q-LEARNING-SELECT-EXPLOIT] Exploiting: Best action selected, Type=%d, Description=%s, QValue=%.3f, Episode=%d",
		result.Type, result.Description, bestQValue, q.currentEpisode)
	

	// Diagnostic logging: Q-table state after selection (CHANGED TO INFO LEVEL)
	finalQTableSize := len(q.qTable)

	logger.GetLogger().Infof("[Q-LEARNING-SELECT] Action selected: %s, QTableSize=%d (after selection), Episode=%d",
		result.Description, finalQTableSize, q.currentEpisode)

	return result
}

// getRandomAction returns a random action with optimized access
// Filters out ActionDeadlineAware if deadline is disabled
func (q *QLearningScheduler) getRandomAction() Action {
	// Get all actions and filter out deadline-aware if disabled
	actions := q.getAvailableActions()
	if len(actions) == 0 {
		// Fallback to ActionNone if no actions available
		allActions := GetAllActions()
		return allActions[0] // ActionNone
	}
	return actions[q.rng.Intn(len(actions))]
}

// getBestActionOptimized finds the best action with optimized Q-table lookup
// Filters out ActionDeadlineAware if deadline is disabled
func (q *QLearningScheduler) getBestActionOptimized(stateKey string) Action {
	stateActions := q.qTable[stateKey]
	
	// Pre-allocate for better performance
	bestAction := ActionNone
	bestValue := math.Inf(-1)
	
	// Check if deadline is disabled (weight = 0.0)
	deadlineDisabled := q.rewardWeights.DeadlineMiss == 0.0

	// Optimized iteration with early exit for common cases
	for actionType, qValue := range stateActions {
		// Filter out ActionDeadlineAware if deadline is disabled
		if deadlineDisabled && actionType == ActionDeadlineAware {
			continue
		}
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

// getAvailableActions returns available actions, filtering out ActionDeadlineAware if deadline is disabled
func (q *QLearningScheduler) getAvailableActions() []Action {
	allActions := GetAllActions()
	
	// Check if deadline is disabled (weight = 0.0)
	deadlineDisabled := q.rewardWeights.DeadlineMiss == 0.0
	
	if !deadlineDisabled {
		// Deadline enabled, return all actions
		return allActions
	}
	
	// Deadline disabled, filter out ActionDeadlineAware
	filteredActions := make([]Action, 0, len(allActions))
	for _, action := range allActions {
		if action.Type != ActionDeadlineAware {
			filteredActions = append(filteredActions, action)
		}
	}
	
	return filteredActions
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

	// Mark model as dirty (lightweight - no I/O, just sets flag)
	if q.onDirty != nil {
		q.onDirty()
	}

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
// Filters out ActionDeadlineAware if deadline is disabled
func (q *QLearningScheduler) initializeStateQValues(stateKey string) {
	if _, exists := q.qTable[stateKey]; !exists {
		q.qTable[stateKey] = make(map[ActionType]float64)
		// Use getAvailableActions to filter out ActionDeadlineAware if disabled
		actions := q.getAvailableActions()
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

// SetDirtyCallback sets the callback to mark model as dirty when Q-table updates
// This is a lightweight callback (no I/O) - just sets a flag in ModelStorage
func (q *QLearningScheduler) SetDirtyCallback(callback func()) {
	q.onDirty = callback
}

// SetNodeStatusTracker sets the node status tracker for real CPU/Memory metrics
func (q *QLearningScheduler) SetNodeStatusTracker(tracker NodeStatusTracker) {
	q.nodeStatusTracker = tracker
	logger.GetLogger().Infof("[Q-LEARNING] NodeStatusTracker set: HasTracker=%t", tracker != nil)
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
	// CRITICAL DIAGNOSTIC: Log original Q-table before copying
	originalSize := len(q.qTable)
	logger.GetLogger().Warnf("[Q-LEARNING-GET-QTABLE] GetQTable called: Original q.qTable size = %d", originalSize)
	
	if originalSize > 0 {
		logger.GetLogger().Warnf("[Q-LEARNING-GET-QTABLE] Listing ALL states in original q.qTable:")
		stateIndex := 0
		for stateKey, actions := range q.qTable {
			stateIndex++
			logger.GetLogger().Warnf("[Q-LEARNING-GET-QTABLE]   State[%d]: Key='%s', Actions=%d", stateIndex, stateKey, len(actions))
			for actionType, qValue := range actions {
				logger.GetLogger().Warnf("[Q-LEARNING-GET-QTABLE]     -> ActionType=%d, QValue=%.6f", actionType, qValue)
			}
		}
	} else {
		logger.GetLogger().Errorf("[Q-LEARNING-GET-QTABLE] ERROR: Original q.qTable is EMPTY (0 states)!")
	}
	
	qTableCopy := make(map[string]map[ActionType]float64)
	for state, actions := range q.qTable {
		qTableCopy[state] = make(map[ActionType]float64)
		for action, value := range actions {
			qTableCopy[state][action] = value
		}
	}
	
	copySize := len(qTableCopy)
	logger.GetLogger().Warnf("[Q-LEARNING-GET-QTABLE] GetQTable returning: Copy size = %d (original was %d)", copySize, originalSize)
	if originalSize != copySize {
		logger.GetLogger().Errorf("[Q-LEARNING-GET-QTABLE] CRITICAL MISMATCH: Original size (%d) != Copy size (%d)!", originalSize, copySize)
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
	logger.GetLogger().Infof("[QLEARNING-COMPLETE-ENTRY] ProcessTaskCompletion: TaskID=%s, QueueLength=%d, HasNodeStatus=%t, HasExpMgr=%t", 
		task.GetTaskID(), queueLength, nodeStatus != nil, q.experienceManager != nil)
	
	if q.experienceManager == nil {
		logger.GetLogger().Errorf("[QLEARNING-COMPLETE-ERROR] Experience manager not initialized: TaskID=%s", task.GetTaskID())
		return fmt.Errorf("experience manager not initialized")
	}

	// Validate task completion report
	if report == nil {
		return fmt.Errorf("task completion report is nil for task %s", task.GetTaskID())
	}

	if report.Metrics == nil {
		return fmt.Errorf("system metrics missing in completion report for task %s", task.GetTaskID())
	}

	// CRITICAL: iFogSim sends cloudletId in req.TaskId when completing tasks
	// We must use report.TaskId (cloudletId) for lookup, not task.GetTaskID() (TaskId)
	cloudletIdForCompletion := report.TaskId
	
	// CRITICAL FIX: Use report.TaskId (cloudletId) for experience lookup
	// This matches what iFogSim sends in the completion report
	err := q.experienceManager.CompleteExperience(cloudletIdForCompletion, report, nodeStatus, queueLength)
	if err != nil {
		// Log but don't fail completely - allows system to continue
		logger.GetLogger().Errorf("[QLEARNING-COMPLETE-ERROR] experienceManager.CompleteExperience failed: TaskID=%s, Error=%v", 
			task.GetTaskID(), err)
		return fmt.Errorf("experience completion failed for task %s: %w", task.GetTaskID(), err)
	}

	// Immediate Q-table update confirmation
	if q.isLearning {
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
