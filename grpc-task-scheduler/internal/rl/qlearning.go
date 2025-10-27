package rl

import (
	"fmt"
	"math"
	"math/rand"
	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/config"
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

	// Cache manager for state features
	taskCacheManager interface{} // Generic interface to avoid circular imports
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
	return &QLearningScheduler{
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
}

// SetCacheManager sets the cache manager for state feature extraction
func (q *QLearningScheduler) SetCacheManager(cacheManager interface{}) {
	q.taskCacheManager = cacheManager
}

// Name returns the algorithm name
func (q *QLearningScheduler) Name() string {
	return "Q-Learning Scheduler"
}

// Schedule schedules tasks using Q-learning
func (q *QLearningScheduler) Schedule(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	if len(tasks) <= 1 {
		return tasks
	}

	state := ExtractStateFeatures(tasks, nodeManager, q.taskCacheManager)

	// Select action using Q-learning policy
	action := q.SelectAction(state)

	// Apply the selected action
	reorderedTasks := ApplyAction(action, tasks)

	// Store experience for each task if learning is enabled
	if q.isLearning && q.experienceManager != nil {
		for _, task := range reorderedTasks {
			q.experienceManager.StoreIncompleteExperience(task.GetTaskID(), state, action)
		}

		// Update episode task count
		q.episodeTaskCount += len(tasks)

		// Check for episode completion
		q.checkEpisodeCompletion()
	}

	return reorderedTasks
}

// checkEpisodeCompletion checks if current episode should end and handles completion
func (q *QLearningScheduler) checkEpisodeCompletion() {
	episodeComplete := false

	switch q.config.EpisodeConfig.Type {
	case "task_based":
		if q.episodeTaskCount >= q.config.EpisodeConfig.TasksPerEpisode {
			episodeComplete = true
		}
	case "time_based":
		episodeDuration := time.Since(q.episodeStartTime)
		maxDuration := time.Duration(q.config.EpisodeConfig.TimePerEpisodeMinutes) * time.Minute
		if episodeDuration >= maxDuration {
			episodeComplete = true
		}
	}

	if episodeComplete {
		q.handleEpisodeCompletion()
	}
}

// handleEpisodeCompletion handles the completion of an episode
func (q *QLearningScheduler) handleEpisodeCompletion() {
	// Trigger weight adaptation if multi-objective is enabled
	if q.multiObjectiveCalculator != nil {
		cfg := config.GetConfig()
		if cfg.RL.MultiObjective.Enabled && cfg.RL.MultiObjective.AdaptationEnabled {
			if err := q.adaptWeights(); err != nil {
				fmt.Printf("Warning: Failed to adapt weights at episode %d: %v\n", q.currentEpisode, err)
			}
		}
	}

	// Mark episode as complete in experience manager
	if q.experienceManager != nil {
		fmt.Printf("Episode %d completed with %d tasks\n", q.currentEpisode, q.episodeTaskCount)
		q.experienceManager.MarkEpisodeComplete(q.currentEpisode)
	}

	// Reset episode if configured
	if q.config.EpisodeConfig.ResetOnEpisodeEnd {
		q.resetEpisode()
	} else {
		q.advanceEpisode()
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

	fmt.Printf("Episode %d: Weight adaptation triggered (handled by MultiObjectiveRewardCalculator)\n", q.currentEpisode)

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

	fmt.Printf("Episode %d reset: Fresh start with exploration rate %.3f\n",
		q.currentEpisode, q.config.ExplorationRate)
}

// advanceEpisode advances to the next episode without resetting learning
func (q *QLearningScheduler) advanceEpisode() {
	q.currentEpisode++
	q.episodeTaskCount = 0
	q.episodeStartTime = time.Now()

	fmt.Printf("Advanced to episode %d (continuous learning)\n", q.currentEpisode)
}

// SelectAction selects an action with optimized Q-table access and caching
func (q *QLearningScheduler) SelectAction(state *StateFeatures) Action {
	stateKey := state.GetStateKey()

	// Track frequently accessed states for optimization
	q.frequentStates[stateKey] = time.Now()

	// Clean up old cache entries periodically (every 100 accesses)
	if time.Since(q.cacheCleanup) > time.Minute {
		q.cleanupFrequentStatesCache()
	}

	// Initialize Q-values for this state if not exists
	if _, exists := q.qTable[stateKey]; !exists {
		q.initializeStateQValues(stateKey)
	}

	// Epsilon-greedy action selection
	if q.isLearning && q.rng.Float64() < q.config.ExplorationRate {
		// Explore: choose random action with pre-allocated actions slice
		return q.getRandomAction()
	}

	// Exploit: choose best action with optimized lookup and caching
	return q.getBestActionOptimized(stateKey)
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
		return nil
	}

	currentStateKey := experience.State.GetStateKey()
	nextStateKey := experience.NextState.GetStateKey()

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

	// Q-learning update rule: Q(s,a) = Q(s,a) + α[r + γ*max(Q(s',a')) - Q(s,a)]
	var targetQ float64
	if experience.Done {
		targetQ = experience.Reward
	} else {
		targetQ = experience.Reward + q.config.DiscountFactor*maxNextQ
	}

	newQ := currentQ + q.config.LearningRate*(targetQ-currentQ)
	q.qTable[currentStateKey][experience.Action.Type] = newQ

	// Decay exploration rate
	if q.config.ExplorationRate > q.config.MinExploration {
		q.config.ExplorationRate *= q.config.ExplorationDecay
	}

	return nil
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
func (q *QLearningScheduler) ProcessTaskCompletion(task TaskEntry, report *pb.TaskCompletionReport) error {
	if q.experienceManager == nil {
		return fmt.Errorf("experience manager not initialized")
	}

	// Validate task completion report
	if report == nil {
		return fmt.Errorf("task completion report is nil for task %s", task.GetTaskID())
	}

	if report.Metrics == nil {
		return fmt.Errorf("system metrics missing in completion report for task %s", task.GetTaskID())
	}

	// Complete the experience with comprehensive error handling
	err := q.experienceManager.CompleteExperience(task.GetTaskID(), report)
	if err != nil {
		// Log but don't fail completely - allows system to continue
		fmt.Printf("Warning: Failed to complete experience for task %s: %v\n", task.GetTaskID(), err)
		return fmt.Errorf("experience completion failed for task %s: %w", task.GetTaskID(), err)
	}

	// Immediate Q-table update confirmation
	if q.isLearning {
		fmt.Printf("Q-table updated for task %s (Episode %d)\n", task.GetTaskID(), q.currentEpisode)
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
