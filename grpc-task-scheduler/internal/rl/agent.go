package rl

import (
	"context"
	"fmt"
	"sync"
	"time"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
)

// Interface definitions to avoid circular imports
type TaskEntry interface {
	GetTaskID() string
	GetPriority() int32
	GetExecutionTimeMs() int64
	GetDeadline() int64
	GetCPURequirement() float64
	GetMemoryRequirement() int64
	GetArrivalTime() time.Time
}

type SingleNodeManager interface {
	GetNodeID() string
	GetCurrentLoad() float64
	GetAvailableCPU() float64
	GetAvailableMemory() int64
	GetCPUUtilization() float64
	GetMemoryUtilization() float64
	GetQueueLength() int
}

// Use config types instead of duplicates
type AgentConfig struct {
	AlgorithmManagerConfig config.AlgorithmManagerConfig
}

// Agent represents the RL agent that coordinates scheduling decisions
type Agent struct {
	algorithmManager *AlgorithmManager
	isEnabled        bool
	config           config.RLConfig // Use config.RLConfig instead of QLearningConfig
	stats            AgentStats
	mu               sync.RWMutex
	ctx              context.Context
	cancel           context.CancelFunc
	nodeStatusTracker NodeStatusTracker // Optional: can be set to provide real CPU/Memory metrics
}

// AgentStats holds agent performance statistics
type AgentStats struct {
	TotalDecisions   int64     `json:"total_decisions"`
	SuccessfulRuns   int64     `json:"successful_runs"`
	FailedRuns       int64     `json:"failed_runs"`
	AverageReward    float64   `json:"average_reward"`
	LastDecisionTime time.Time `json:"last_decision_time"`
	StartTime        time.Time `json:"start_time"`
	IsLearning       bool      `json:"is_learning"`
}

// NewAgent creates a new RL agent
func NewAgent(cfg AgentConfig) *Agent {
	ctx, cancel := context.WithCancel(context.Background())

	agent := &Agent{
		isEnabled: cfg.AlgorithmManagerConfig.RLEnabled,
		config: config.RLConfig{
			LearningRate:    cfg.AlgorithmManagerConfig.QLearningConfig.LearningRate,
			DiscountFactor:  cfg.AlgorithmManagerConfig.QLearningConfig.DiscountFactor,
			ExplorationRate: cfg.AlgorithmManagerConfig.QLearningConfig.ExplorationRate,
		},
		stats: AgentStats{
			StartTime:  time.Now(),
			IsLearning: cfg.AlgorithmManagerConfig.RLEnabled,
		},
		ctx:    ctx,
		cancel: cancel,
	}

	// Initialize algorithm manager if agent is enabled
	if agent.isEnabled {
		agent.algorithmManager = NewAlgorithmManager(
			cfg.AlgorithmManagerConfig,
		)
	}

	return agent
}

// IsEnabled returns whether the agent is enabled
func (a *Agent) IsEnabled() bool {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.isEnabled
}

// Enable enables the agent
func (a *Agent) Enable() error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if a.isEnabled {
		return nil // Already enabled
	}

	a.isEnabled = true
	return nil
}

// Disable disables the agent
func (a *Agent) Disable() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.isEnabled = false
}

// Schedule is the main entry point that matches what scheduler.go expects
func (a *Agent) Schedule(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	result := a.ScheduleTasks(tasks, nodeManager)
	return result
}

// ScheduleTasks schedules tasks using the selected algorithm
func (a *Agent) ScheduleTasks(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	
	a.mu.Lock()
	defer func() {
		a.mu.Unlock()
	}()

	if !a.isEnabled || a.algorithmManager == nil {
		// Agent is disabled or not properly initialized
		return tasks
	}

	// Record decision
	a.stats.TotalDecisions++
	a.stats.LastDecisionTime = time.Now()

	// Select algorithm
	algorithm := a.algorithmManager.SelectAlgorithm(tasks, nodeManager)
	if algorithm == nil {
		a.stats.FailedRuns++
		return tasks
	}

	// Before scheduling, ensure NodeStatusTracker is set on Q-learning scheduler
	if qlAlg, ok := algorithm.(*QLearningScheduler); ok && a.nodeStatusTracker != nil {
		qlAlg.SetNodeStatusTracker(a.nodeStatusTracker)
	}
	
	scheduledTasks := algorithm.Schedule(tasks, nodeManager)
	
	// CRITICAL VALIDATION: Ensure no tasks are lost during algorithm scheduling
	if len(scheduledTasks) != len(tasks) {
		logger.GetLogger().Errorf("[AGENT-SCHEDULE-ERROR] Task count mismatch: input=%d, output=%d, lost=%d", 
			len(tasks), len(scheduledTasks), len(tasks)-len(scheduledTasks))
		return tasks
	}

	// Record performance
	algType := a.getAlgorithmType(algorithm)
	a.algorithmManager.RecordPerformance(algType, nodeManager, scheduledTasks)

	a.stats.SuccessfulRuns++

	return scheduledTasks
}

// getAlgorithmType determines algorithm type from algorithm instance
func (a *Agent) getAlgorithmType(algorithm SchedulingAlgorithm) AlgorithmType {
	switch algorithm.Name() {
	case "FCFS Scheduler":
		return AlgorithmFCFS
	case "SJF Scheduler":
		return AlgorithmSJF
	case "Priority Scheduler":
		return AlgorithmPriority
	case "EDF Scheduler":
		return AlgorithmEDF
	case "Q-Learning Scheduler":
		return AlgorithmQLearning
	default:
		return AlgorithmFCFS
	}
}

// UpdateRewardWeights updates reward weights for RL algorithms
func (a *Agent) UpdateRewardWeights(weights config.RewardWeights) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.isEnabled {
		return fmt.Errorf("agent is disabled")
	}

	if a.algorithmManager == nil {
		return fmt.Errorf("algorithm manager not initialized")
	}

	// Update algorithm manager
	if err := a.algorithmManager.UpdateRewardWeights(weights); err != nil {
		return fmt.Errorf("failed to update reward weights: %w", err)
	}

	return nil
}

// UpdateActiveProfile updates the active profile in the multi-objective calculator
func (a *Agent) UpdateActiveProfile(profileName string) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.isEnabled {
		return fmt.Errorf("agent is disabled")
	}

	if a.algorithmManager == nil {
		return fmt.Errorf("algorithm manager not initialized")
	}

	// Update active profile in algorithm manager
	if err := a.algorithmManager.UpdateActiveProfile(profileName); err != nil {
		return fmt.Errorf("failed to update active profile: %w", err)
	}

	return nil
}

// SetNodeStatusTracker sets the node status tracker for Q-learning scheduler
func (a *Agent) SetNodeStatusTracker(tracker NodeStatusTracker) {
	a.mu.Lock()
	defer a.mu.Unlock()
	
	a.nodeStatusTracker = tracker
	
	// Also set it on the Q-learning scheduler if it exists
	if a.algorithmManager != nil {
		currentAlg := a.algorithmManager.GetCurrentAlgorithm()
		if qlAlg, ok := currentAlg.(*QLearningScheduler); ok {
			qlAlg.SetNodeStatusTracker(tracker)
			logger.GetLogger().Infof("[AGENT] NodeStatusTracker set on Q-learning scheduler")
		}
	}
	
	logger.GetLogger().Infof("[AGENT] NodeStatusTracker set: HasTracker=%t", tracker != nil)
}

// GetCurrentAlgorithm returns information about the current algorithm
func (a *Agent) GetCurrentAlgorithm() map[string]interface{} {
	a.mu.RLock()
	defer a.mu.RUnlock()

	result := make(map[string]interface{})

	if !a.isEnabled || a.algorithmManager == nil {
		result["enabled"] = false
		result["algorithm"] = "none"
		return result
	}

	currentAlg := a.algorithmManager.GetCurrentAlgorithm()
	result["enabled"] = true

	if currentAlg != nil {
		result["algorithm"] = currentAlg.Name()
		result["stats"] = currentAlg.GetStats()
	} else {
		result["algorithm"] = "none"
	}

	return result
}

// GetStats returns agent statistics
func (a *Agent) GetStats() AgentStats {
	a.mu.RLock()
	defer a.mu.RUnlock()

	stats := a.stats

	// Calculate success rate
	if stats.TotalDecisions > 0 {
		stats.AverageReward = float64(stats.SuccessfulRuns) / float64(stats.TotalDecisions)
	}

	return stats
}

// GetAlgorithmStats returns detailed algorithm statistics
func (a *Agent) GetAlgorithmStats() map[string]interface{} {
	a.mu.RLock()
	defer a.mu.RUnlock()

	if !a.isEnabled || a.algorithmManager == nil {
		return map[string]interface{}{
			"enabled":    false,
			"algorithms": map[string]interface{}{},
		}
	}

	stats := a.algorithmManager.GetAlgorithmStats()
	stats["enabled"] = true
	stats["agent_stats"] = a.stats

	return stats
}

// SetLearningMode sets learning mode for RL algorithms
func (a *Agent) SetLearningMode(enabled bool) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.isEnabled {
		return fmt.Errorf("agent is disabled")
	}

	if a.algorithmManager == nil {
		return fmt.Errorf("algorithm manager not initialized")
	}

	a.algorithmManager.SetLearningMode(enabled)
	a.stats.IsLearning = enabled

	return nil
}

// GetAvailableAlgorithms returns list of available algorithms
func (a *Agent) GetAvailableAlgorithms() []string {
	a.mu.RLock()
	defer a.mu.RUnlock()

	if !a.isEnabled || a.algorithmManager == nil {
		return []string{}
	}

	return a.algorithmManager.GetAvailableAlgorithms()
}

// GetQLearningScheduler returns the Q-learning scheduler if available
func (a *Agent) GetQLearningScheduler() *QLearningScheduler {
	a.mu.RLock()
	defer a.mu.RUnlock()

	if !a.isEnabled || a.algorithmManager == nil {
		return nil
	}

	// Get Q-learning algorithm from algorithm manager
	alg := a.algorithmManager.GetAlgorithm(AlgorithmQLearning)
	if alg == nil {
		return nil
	}

	// Type assert to QLearningScheduler
	qlScheduler, ok := alg.(*QLearningScheduler)
	if !ok {
		return nil
	}

	return qlScheduler
}

// Start starts the agent (placeholder for future background tasks)
func (a *Agent) Start() error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.isEnabled {
		return fmt.Errorf("agent is disabled")
	}

	return nil
}

// Stop gracefully stops the agent and cleans up resources
func (a *Agent) Stop() {
	a.mu.Lock()
	defer a.mu.Unlock()

	if a.cancel != nil {
		a.cancel()
		a.cancel = nil
	}

	if a.algorithmManager != nil {
		a.algorithmManager.SetLearningMode(false)
	}

	a.isEnabled = false
}

// ProcessTaskCompletion processes task completion for RL experience collection
// NOTE: This method should not be used directly - use ProcessTaskCompletionWithNodeStatus instead
// This method exists for interface compatibility but will fail without real node status
func (a *Agent) ProcessTaskCompletion(task TaskEntry, report *pb.TaskCompletionReport) error {
	return fmt.Errorf("ProcessTaskCompletion requires real node status - use ProcessTaskCompletionWithNodeStatus instead")
}

// ProcessTaskCompletionWithNodeStatus processes task completion with node status from completion report
// cloudletId is required for experience lookup (no fallback to taskId)
func (a *Agent) ProcessTaskCompletionWithNodeStatus(task TaskEntry, report *pb.TaskCompletionReport, nodeStatus *pb.FogNode, queueLength int, cloudletId string) error {
	logger.GetLogger().Infof("[AGENT-COMPLETE-ENTRY] ProcessTaskCompletionWithNodeStatus called: cloudletId=%s, taskId=%s, QueueLength=%d", 
		cloudletId, report.TaskId, queueLength)
	
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.isEnabled || a.algorithmManager == nil {
		logger.GetLogger().Errorf("[AGENT-COMPLETE-ERROR] Agent disabled or not initialized: cloudletId=%s, Enabled=%t, Manager=%t", 
			cloudletId, a.isEnabled, a.algorithmManager != nil)
		return fmt.Errorf("agent is disabled or not initialized")
	}

	// Delegate to algorithm manager with node status, actual queue length, and cloudletId from completion report
	logger.GetLogger().Infof("[AGENT-COMPLETE-DELEGATE] Delegating to algorithmManager: cloudletId=%s", cloudletId)
	err := a.algorithmManager.ProcessTaskCompletion(task, report, nodeStatus, queueLength, cloudletId)
	if err != nil {
		logger.GetLogger().Errorf("[AGENT-COMPLETE-ERROR] algorithmManager.ProcessTaskCompletion failed: cloudletId=%s, Error=%v", 
			cloudletId, err)
	} else {
		logger.GetLogger().Infof("[AGENT-COMPLETE-SUCCESS] algorithmManager processed successfully: cloudletId=%s", cloudletId)
	}
	return err
}

// GetAlgorithmManager returns the algorithm manager for model persistence
func (a *Agent) GetAlgorithmManager() *AlgorithmManager {
	return a.algorithmManager
}

