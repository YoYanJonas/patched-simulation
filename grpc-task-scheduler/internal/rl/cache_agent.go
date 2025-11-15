package rl

import (
	"sync"
	"time"

	"scheduler-grpc-server/pkg/config"
)

// CacheAgent makes cache decisions using RL policy
type CacheAgent struct {
	qLearningScheduler *CacheQLearningScheduler
	isEnabled          bool
	config             config.CacheAgentConfig
	stats              CacheAgentStats
	mu                 sync.RWMutex
}

// CacheAgentStats tracks agent statistics
type CacheAgentStats struct {
	TotalDecisions   int64
	SuccessfulDecisions int64
	FailedDecisions  int64
	AverageReward    float64
	LastDecisionTime time.Time
	StartTime        time.Time
	IsLearning       bool
}

// NewCacheAgent creates a new cache agent
func NewCacheAgent(cfg config.CacheAgentConfig) *CacheAgent {
	now := time.Now()
	
	// Create Q-learning scheduler config
	rlConfig := CacheRLConfig{
		LearningRate:     cfg.LearningRate,
		DiscountFactor:   cfg.DiscountFactor,
		ExplorationRate:  cfg.ExplorationRate,
		MinExploration:   cfg.MinExploration,
		ExplorationDecay: cfg.ExplorationDecay,
	}
	
	// Default reward weights (can be configured later)
	rewardWeights := CacheRewardWeights{
		CacheHit:     1.0,
		CacheMiss:    -1.0,
		Storage:      0.5,
		Invalidation: 0.2,
	}
	
	return &CacheAgent{
		qLearningScheduler: NewCacheQLearningScheduler(rlConfig, rewardWeights),
		isEnabled:          cfg.Enabled,
		config:             cfg,
		stats: CacheAgentStats{
			StartTime:  now,
			IsLearning: true,
		},
	}
}

// IsEnabled returns whether the agent is enabled
func (ca *CacheAgent) IsEnabled() bool {
	ca.mu.RLock()
	defer ca.mu.RUnlock()
	return ca.isEnabled
}

// SelectAction selects cache action using RL policy
func (ca *CacheAgent) SelectAction(state *CacheStateFeatures) Action {
	
	if !ca.IsEnabled() {
		// Fallback: return default action (ActionDelete = no cache)
		return Action{Type: ActionDelete, Description: "Agent disabled", Priority: 0.0}
	}
	
	result := ca.qLearningScheduler.SelectAction(state)
	
	return result
}

// UpdateReward updates the agent with a reward signal
func (ca *CacheAgent) UpdateReward(
	currentState *CacheStateFeatures,
	action Action,
	reward float64,
	nextState *CacheStateFeatures,
	done bool,
) error {
	if !ca.IsEnabled() {
		return nil
	}
	
	ca.mu.Lock()
	defer ca.mu.Unlock()
	
	// Create experience
	experience := &CacheExperience{
		State:     currentState,
		Action:    action,
		Reward:    reward,
		NextState: nextState,
		Done:      done,
		Timestamp: time.Now(),
	}
	
	// Update Q-learning policy
	err := ca.qLearningScheduler.UpdatePolicy(experience)
	if err != nil {
		ca.stats.FailedDecisions++
		return err
	}
	
	// Update statistics
	ca.stats.TotalDecisions++
	ca.stats.SuccessfulDecisions++
	ca.stats.LastDecisionTime = time.Now()
	
	// Update average reward (exponential moving average)
	alpha := 0.1
	ca.stats.AverageReward = alpha*reward + (1-alpha)*ca.stats.AverageReward
	
	return nil
}

// GetStats returns agent statistics
func (ca *CacheAgent) GetStats() CacheAgentStats {
	ca.mu.RLock()
	defer ca.mu.RUnlock()
	return ca.stats
}

// GetQTable returns Q-table for inspection
func (ca *CacheAgent) GetQTable() map[string]map[ActionType]float64 {
	if !ca.IsEnabled() {
		return nil
	}
	return ca.qLearningScheduler.GetQTable()
}

// GetQLearningScheduler returns the underlying Q-learning scheduler for state access
func (ca *CacheAgent) GetQLearningScheduler() *CacheQLearningScheduler {
	ca.mu.RLock()
	defer ca.mu.RUnlock()
	return ca.qLearningScheduler
}

