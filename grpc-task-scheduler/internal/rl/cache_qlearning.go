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
	cql.mu.Lock()
	defer cql.mu.Unlock()

	stateKey := state.GetStateKey()
	cacheActions := GetAllCacheActions()

	// Epsilon-greedy: explore with probability epsilon
	if cql.rng.Float64() < cql.config.ExplorationRate {
		// Explore: random action
		randomIdx := cql.rng.Intn(len(cacheActions))
		cql.lastState = state
		cql.lastAction = cacheActions[randomIdx]
		cql.lastTimestamp = time.Now()
		return cacheActions[randomIdx]
	}

	// Exploit: select best action from Q-table
	bestAction := cacheActions[0]
	bestQValue := cql.getQValue(stateKey, bestAction.Type)

	for _, action := range cacheActions[1:] {
		qValue := cql.getQValue(stateKey, action.Type)
		if qValue > bestQValue {
			bestQValue = qValue
			bestAction = action
		}
	}

	cql.lastState = state
	cql.lastAction = bestAction
	cql.lastTimestamp = time.Now()
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

// getQValue gets Q-value for state-action pair (returns 0 if not found)
func (cql *CacheQLearningScheduler) getQValue(stateKey string, actionType ActionType) float64 {
	if cql.qTable[stateKey] == nil {
		return 0.0
	}
	return cql.qTable[stateKey][actionType]
}

// getMaxQValue gets maximum Q-value for a state across all cache actions
func (cql *CacheQLearningScheduler) getMaxQValue(stateKey string) float64 {
	actionRewards := cql.qTable[stateKey]
	if actionRewards == nil || len(actionRewards) == 0 {
		return 0.0
	}

	maxQ := -1e9
	for _, qValue := range actionRewards {
		if qValue > maxQ {
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

