package rl

import (
	"fmt"
	"math/rand"
	"sync"
	"time"
)

// CacheQLearningScheduler implements Q-learning for cache decisions
type CacheQLearningScheduler struct {
	config        CacheRLConfig
	rewardWeights CacheRewardWeights
	qTable        map[string]map[ActionType]float64 // state -> action -> Q-value
	isLearning    bool
	stats         map[string]interface{}
	rng           *rand.Rand
	mu            sync.RWMutex

	// Experience tracking (simplified - no experience replay for now)
	lastState      *CacheStateFeatures
	lastAction     Action
	lastTimestamp  time.Time

	// Episode management
	currentEpisode   int
	episodeTaskCount int
	episodeStartTime time.Time

	// Performance optimization
	frequentStates map[string]time.Time
	cacheCleanup   time.Time
}

// CacheRLConfig contains cache RL algorithm configuration
type CacheRLConfig struct {
	LearningRate    float64
	DiscountFactor  float64
	ExplorationRate float64
	MinExploration  float64
	ExplorationDecay float64
}

// CacheRewardWeights defines weights for different reward components
type CacheRewardWeights struct {
	CacheHit      float64
	CacheMiss     float64
	Storage       float64
	Invalidation  float64
}

// CacheExperience represents a cache learning experience
type CacheExperience struct {
	State      *CacheStateFeatures
	Action     Action
	Reward     float64
	NextState  *CacheStateFeatures
	Done       bool
	Timestamp  time.Time
}

// NewCacheQLearningScheduler creates a new cache Q-learning scheduler
func NewCacheQLearningScheduler(cfg CacheRLConfig, weights CacheRewardWeights) *CacheQLearningScheduler {
	now := time.Now()
	return &CacheQLearningScheduler{
		config:        cfg,
		rewardWeights: weights,
		qTable:        make(map[string]map[ActionType]float64),
		isLearning:    true,
		stats:         make(map[string]interface{}),
		rng:           rand.New(rand.NewSource(time.Now().UnixNano())),

		// Initialize episode management
		currentEpisode:   1,
		episodeTaskCount:  0,
		episodeStartTime:  now,

		// Performance optimization
		frequentStates: make(map[string]time.Time),
		cacheCleanup:   now,
	}
}

// SelectAction selects cache action using epsilon-greedy policy
func (cql *CacheQLearningScheduler) SelectAction(state *CacheStateFeatures) Action {
	// [DEBUG] Entry point for CacheQLearning SelectAction
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-ENTRY] CacheQLearning.SelectAction called\n")
	
	// [DEBUG] About to acquire lock
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-LOCK-BEFORE] About to acquire lock\n")
	cql.mu.Lock()
	// [DEBUG] Lock acquired
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-LOCK-ACQUIRED] Lock acquired\n")
	defer func() {
		// [DEBUG] About to release lock
		fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-LOCK-RELEASE] Releasing lock\n")
		cql.mu.Unlock()
		// [DEBUG] Lock released
		fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-LOCK-RELEASED] Lock released\n")
	}()

	// [DEBUG] Getting state key
	stateKey := state.GetStateKey()
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-STATE-KEY] State key: %s\n", stateKey)
	
	// [DEBUG] Getting cache actions (two-action design)
	cacheActions := GetAllCacheActions()
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-ACTIONS] Available cache actions: %d\n", len(cacheActions))

	// [DEBUG] Epsilon-greedy selection
	// Epsilon-greedy: explore with probability epsilon
	explorationRate := cql.config.ExplorationRate
	randomValue := cql.rng.Float64()
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-EPSILON] ExplorationRate=%.3f, RandomValue=%.3f\n", explorationRate, randomValue)
	
	if randomValue < explorationRate {
		// [DEBUG] Exploring
		fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-EXPLORE] Exploring: choosing random action\n")
		// Explore: random action
		randomIdx := cql.rng.Intn(len(cacheActions))
		cql.lastState = state
		cql.lastAction = cacheActions[randomIdx]
		cql.lastTimestamp = time.Now()
		// [DEBUG] Random action selected
		fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-EXPLORE-DONE] Random action selected: Type=%d, Index=%d\n", cacheActions[randomIdx].Type, randomIdx)
		return cacheActions[randomIdx]
	}

	// [DEBUG] Exploiting
	// Exploit: select best action from Q-table
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-EXPLOIT] Exploiting: selecting best action from Q-table\n")
	bestAction := cacheActions[0]
	bestQValue := cql.getQValue(stateKey, bestAction.Type)
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-EXPLOIT-INIT] Initial best action: Type=%d, QValue=%.3f\n", bestAction.Type, bestQValue)

	for i, action := range cacheActions[1:] {
		qValue := cql.getQValue(stateKey, action.Type)
		if qValue > bestQValue {
			bestQValue = qValue
			bestAction = action
			fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-EXPLOIT-UPDATE] New best action: Type=%d, QValue=%.3f (index %d)\n", action.Type, qValue, i+1)
		}
	}

	cql.lastState = state
	cql.lastAction = bestAction
	cql.lastTimestamp = time.Now()
	// [DEBUG] Best action selected
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-EXPLOIT-DONE] Best action selected: Type=%d, QValue=%.3f\n", bestAction.Type, bestQValue)
	
	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [CACHE-QLEARNING-SELECT-EXIT] CacheQLearning.SelectAction returning: Type=%d\n", bestAction.Type)
	return bestAction
}

// UpdatePolicy updates Q-values based on cache experience
func (cql *CacheQLearningScheduler) UpdatePolicy(experience *CacheExperience) error {
	cql.mu.Lock()
	defer cql.mu.Unlock()

	if experience.State == nil {
		return fmt.Errorf("experience state is nil")
	}

	stateKey := experience.State.GetStateKey()

	// Get current Q-value
	currentQ := cql.getQValue(stateKey, experience.Action.Type)

	// Calculate target Q-value
	var targetQ float64
	if experience.Done {
		// Terminal state: Q(s,a) = reward
		targetQ = experience.Reward
	} else {
		// Non-terminal: Q(s,a) = reward + gamma * max(Q(s',a'))
		if experience.NextState != nil {
			nextStateKey := experience.NextState.GetStateKey()
			maxNextQ := cql.getMaxQValue(nextStateKey)
			targetQ = experience.Reward + cql.config.DiscountFactor*maxNextQ
		} else {
			// No next state - just use reward
			targetQ = experience.Reward
		}
	}

	// Update Q-value using Q-learning formula: Q(s,a) = Q(s,a) + alpha * (target - Q(s,a))
	newQ := currentQ + cql.config.LearningRate*(targetQ-currentQ)

	// Store in Q-table
	if cql.qTable[stateKey] == nil {
		cql.qTable[stateKey] = make(map[ActionType]float64)
	}
	cql.qTable[stateKey][experience.Action.Type] = newQ

	// Update statistics
	cql.episodeTaskCount++
	if cql.episodeTaskCount%100 == 0 {
		cql.decayExploration()
	}

	return nil
}

// getQValue gets Q-value for state-action pair (initializes with small random values if not found)
func (cql *CacheQLearningScheduler) getQValue(stateKey string, actionType ActionType) float64 {
	if cql.qTable[stateKey] == nil {
		// Initialize state with small random Q-values for exploration
		// This prevents all actions from having same Q-value (0.0) initially
		cql.qTable[stateKey] = make(map[ActionType]float64)
		// Initialize all actions with small random values (two-action design)
		for _, action := range GetAllCacheActions() {
			// Small random value between -0.1 and 0.1 for initial exploration
			cql.qTable[stateKey][action.Type] = (cql.rng.Float64() - 0.5) * 0.2
		}
	}
	
	qValue, exists := cql.qTable[stateKey][actionType]
	if !exists {
		// Action not seen before - initialize with small random value
		qValue = (cql.rng.Float64() - 0.5) * 0.2
		cql.qTable[stateKey][actionType] = qValue
	}
	return qValue
}

// getMaxQValue gets maximum Q-value for a state across all cache actions
func (cql *CacheQLearningScheduler) getMaxQValue(stateKey string) float64 {
	// Only consider actions from two-action design
	validActions := GetAllCacheActions()
	actionSet := make(map[ActionType]bool)
	for _, action := range validActions {
		actionSet[action.Type] = true
	}

	actionRewards := cql.qTable[stateKey]
	if len(actionRewards) == 0 {
		return 0.0
	}

	maxQ := -1e9
	for actionType, qValue := range actionRewards {
		// Only consider actions from two-action design
		if actionSet[actionType] && qValue > maxQ {
			maxQ = qValue
		}
	}
	return maxQ
}

// decayExploration decays exploration rate
func (cql *CacheQLearningScheduler) decayExploration() {
	if cql.config.ExplorationRate > cql.config.MinExploration {
		cql.config.ExplorationRate *= cql.config.ExplorationDecay
		if cql.config.ExplorationRate < cql.config.MinExploration {
			cql.config.ExplorationRate = cql.config.MinExploration
		}
	}
}

// GetQTable returns Q-table for inspection
func (cql *CacheQLearningScheduler) GetQTable() map[string]map[ActionType]float64 {
	cql.mu.RLock()
	defer cql.mu.RUnlock()

	// Return a copy
	result := make(map[string]map[ActionType]float64)
	for stateKey, actions := range cql.qTable {
		result[stateKey] = make(map[ActionType]float64)
		for actionType, qValue := range actions {
			result[stateKey][actionType] = qValue
		}
	}
	return result
}

// GetStats returns scheduler statistics
func (cql *CacheQLearningScheduler) GetStats() map[string]interface{} {
	cql.mu.RLock()
	defer cql.mu.RUnlock()

	return map[string]interface{}{
		"current_episode":     cql.currentEpisode,
		"episode_task_count":  cql.episodeTaskCount,
		"q_table_size":        len(cql.qTable),
		"exploration_rate":    cql.config.ExplorationRate,
		"learning_enabled":    cql.isLearning,
	}
}

