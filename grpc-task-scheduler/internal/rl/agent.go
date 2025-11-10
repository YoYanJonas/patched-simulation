package rl

import (
	"context"
	"fmt"
	"log"
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
		log.Printf("RL Agent initialized with algorithm manager")
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
	log.Printf("RL Agent enabled")
	return nil
}

// Disable disables the agent
func (a *Agent) Disable() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.isEnabled = false
	log.Printf("RL Agent disabled")
}

// Schedule is the main entry point that matches what scheduler.go expects
func (a *Agent) Schedule(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	// [DEBUG] Entry point for Schedule (wrapper)
	log.Printf("[DEBUG] [AGENT-SCHEDULE-WRAPPER] Agent.Schedule called with %d tasks", len(tasks))
	result := a.ScheduleTasks(tasks, nodeManager)
	// [DEBUG] ScheduleTasks returned
	log.Printf("[DEBUG] [AGENT-SCHEDULE-WRAPPER-RETURN] Agent.Schedule returning %d tasks", len(result))
	return result
}

// ScheduleTasks schedules tasks using the selected algorithm
func (a *Agent) ScheduleTasks(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	// [DEBUG] Entry point for ScheduleTasks
	log.Printf("[DEBUG] [AGENT-SCHEDULE-ENTRY] Agent.ScheduleTasks called with %d tasks", len(tasks))
	
	// [DEBUG] About to acquire lock
	log.Printf("[DEBUG] [AGENT-SCHEDULE-LOCK-BEFORE] About to acquire lock")
	a.mu.Lock()
	// [DEBUG] Lock acquired
	log.Printf("[DEBUG] [AGENT-SCHEDULE-LOCK-ACQUIRED] Lock acquired")
	defer func() {
		// [DEBUG] About to release lock
		log.Printf("[DEBUG] [AGENT-SCHEDULE-LOCK-RELEASE] Releasing lock")
		a.mu.Unlock()
		// [DEBUG] Lock released
		log.Printf("[DEBUG] [AGENT-SCHEDULE-LOCK-RELEASED] Lock released")
	}()

	// [DEBUG] Check agent state
	if !a.isEnabled || a.algorithmManager == nil {
		// [DEBUG] Agent disabled or not initialized
		log.Printf("[DEBUG] [AGENT-SCHEDULE-DISABLED] Agent disabled (enabled=%t, manager=%v)", a.isEnabled, a.algorithmManager != nil)
		// Agent is disabled or not properly initialized
		return tasks
	}
	// [DEBUG] Agent is enabled
	log.Printf("[DEBUG] [AGENT-SCHEDULE-ENABLED] Agent is enabled and initialized")

	// [DEBUG] Record decision
	// Record decision
	a.stats.TotalDecisions++
	a.stats.LastDecisionTime = time.Now()
	log.Printf("[DEBUG] [AGENT-SCHEDULE-DECISION] Decision recorded: TotalDecisions=%d", a.stats.TotalDecisions)

	// [DEBUG] About to select algorithm
	// Select algorithm
	log.Printf("[DEBUG] [AGENT-SCHEDULE-SELECT-ALG-BEFORE] About to select algorithm")
	algorithm := a.algorithmManager.SelectAlgorithm(tasks, nodeManager)
	// [DEBUG] Algorithm selected
	if algorithm == nil {
		// [DEBUG] No algorithm available
		log.Printf("[DEBUG] [AGENT-SCHEDULE-ALG-NIL] No algorithm available for scheduling")
		a.stats.FailedRuns++
		log.Printf("No algorithm available for scheduling")
		return tasks
	}
	// [DEBUG] Algorithm selected successfully
	log.Printf("[DEBUG] [AGENT-SCHEDULE-ALG-SELECTED] Algorithm selected: %s", algorithm.Name())

	// [DEBUG] About to schedule tasks
	// Schedule tasks
	log.Printf("[DEBUG] [AGENT-SCHEDULE-ALG-BEFORE] About to call algorithm.Schedule with %d tasks", len(tasks))
	scheduledTasks := algorithm.Schedule(tasks, nodeManager)
	// [DEBUG] Algorithm.Schedule returned
	log.Printf("[DEBUG] [AGENT-SCHEDULE-ALG-AFTER] algorithm.Schedule returned %d tasks", len(scheduledTasks))

	// [DEBUG] About to record performance
	// Record performance
	log.Printf("[DEBUG] [AGENT-SCHEDULE-PERF-BEFORE] About to record performance")
	algType := a.getAlgorithmType(algorithm)
	log.Printf("[DEBUG] [AGENT-SCHEDULE-PERF-TYPE] Algorithm type: %s", algType)
	a.algorithmManager.RecordPerformance(algType, nodeManager, scheduledTasks)
	// [DEBUG] Performance recorded
	log.Printf("[DEBUG] [AGENT-SCHEDULE-PERF-AFTER] Performance recorded")

	a.stats.SuccessfulRuns++
	// [DEBUG] Success recorded
	log.Printf("[DEBUG] [AGENT-SCHEDULE-SUCCESS] Scheduling successful: SuccessfulRuns=%d", a.stats.SuccessfulRuns)

	// [DEBUG] About to return
	log.Printf("[DEBUG] [AGENT-SCHEDULE-EXIT] Agent.ScheduleTasks returning %d tasks", len(scheduledTasks))
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

	log.Printf("Agent reward weights updated successfully")
	return nil
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

	log.Printf("Agent learning mode set to: %v", enabled)
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

// Start starts the agent (placeholder for future background tasks)
func (a *Agent) Start() error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.isEnabled {
		return fmt.Errorf("agent is disabled")
	}

	log.Printf("RL Agent started")
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
	log.Printf("RL Agent stopped gracefully")
}

// ProcessTaskCompletion processes task completion for RL experience collection
// NOTE: This method should not be used directly - use ProcessTaskCompletionWithNodeStatus instead
// This method exists for interface compatibility but will fail without real node status
func (a *Agent) ProcessTaskCompletion(task TaskEntry, report *pb.TaskCompletionReport) error {
	return fmt.Errorf("ProcessTaskCompletion requires real node status - use ProcessTaskCompletionWithNodeStatus instead")
}

// ProcessTaskCompletionWithNodeStatus processes task completion with node status from completion report
func (a *Agent) ProcessTaskCompletionWithNodeStatus(task TaskEntry, report *pb.TaskCompletionReport, nodeStatus *pb.FogNode, queueLength int) error {
	fmt.Printf("[DEBUG] [AGENT-COMPLETE-ENTRY] ProcessTaskCompletionWithNodeStatus called: TaskID=%s, QueueLength=%d, HasNodeStatus=%t\n", 
		report.TaskId, queueLength, nodeStatus != nil)
	logger.GetLogger().Infof("[AGENT-COMPLETE-ENTRY] ProcessTaskCompletionWithNodeStatus: TaskID=%s, QueueLength=%d, HasNodeStatus=%t", 
		report.TaskId, queueLength, nodeStatus != nil)
	
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.isEnabled || a.algorithmManager == nil {
		fmt.Printf("[DEBUG] [AGENT-COMPLETE-ERROR] Agent disabled or not initialized: TaskID=%s, Enabled=%t, Manager=%t\n", 
			report.TaskId, a.isEnabled, a.algorithmManager != nil)
		logger.GetLogger().Errorf("[AGENT-COMPLETE-ERROR] Agent disabled or not initialized: TaskID=%s, Enabled=%t, Manager=%t", 
			report.TaskId, a.isEnabled, a.algorithmManager != nil)
		return fmt.Errorf("agent is disabled or not initialized")
	}

	// Delegate to algorithm manager with node status and actual queue length from completion report
	fmt.Printf("[DEBUG] [AGENT-COMPLETE-CALL] Calling algorithmManager.ProcessTaskCompletion: TaskID=%s\n", report.TaskId)
	err := a.algorithmManager.ProcessTaskCompletion(task, report, nodeStatus, queueLength)
	if err != nil {
		fmt.Printf("[DEBUG] [AGENT-COMPLETE-ERROR] algorithmManager.ProcessTaskCompletion failed: TaskID=%s, Error=%v\n", 
			report.TaskId, err)
		logger.GetLogger().Errorf("[AGENT-COMPLETE-ERROR] algorithmManager.ProcessTaskCompletion failed: TaskID=%s, Error=%v", 
			report.TaskId, err)
	} else {
		fmt.Printf("[DEBUG] [AGENT-COMPLETE-SUCCESS] algorithmManager.ProcessTaskCompletion succeeded: TaskID=%s\n", report.TaskId)
		logger.GetLogger().Infof("[AGENT-COMPLETE-SUCCESS] algorithmManager.ProcessTaskCompletion succeeded: TaskID=%s", report.TaskId)
	}
	return err
}

// GetAlgorithmManager returns the algorithm manager for model persistence
func (a *Agent) GetAlgorithmManager() *AlgorithmManager {
	return a.algorithmManager
}

