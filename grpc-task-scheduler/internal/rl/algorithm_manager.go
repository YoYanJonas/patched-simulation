package rl

import (
	"fmt"
	"strings"
	"sync"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/config"
)

// AlgorithmType represents different algorithm types
type AlgorithmType string

const (
	AlgorithmFCFS      AlgorithmType = "fcfs"
	AlgorithmSJF       AlgorithmType = "sjf"
	AlgorithmPriority  AlgorithmType = "priority"
	AlgorithmEDF       AlgorithmType = "edf"
	AlgorithmQLearning AlgorithmType = "qlearning"
)

// SchedulingAlgorithm interface for all scheduling algorithms
type SchedulingAlgorithm interface {
	Name() string
	Schedule(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry
	GetStats() map[string]interface{}
	Configure(params map[string]interface{}) error
}

// RLAlgorithm interface for reinforcement learning algorithms
type RLAlgorithm interface {
	SchedulingAlgorithm
	SelectAction(state *StateFeatures) Action
	UpdatePolicy(experience *Experience) error
	IsLearning() bool
	SetLearningMode(enabled bool)
	UpdateRewardWeights(weights config.RewardWeights) error // Use config.RewardWeights
}

// AlgorithmManager manages different scheduling algorithms
type AlgorithmManager struct {
	config                     config.AlgorithmManagerConfig // Use config type
	traditionAlgorithms        map[AlgorithmType]SchedulingAlgorithm
	rlAlgorithms               map[AlgorithmType]RLAlgorithm
	performanceTracker         *PerformanceTracker         // For traditional algorithms
	enhancedPerformanceTracker *EnhancedPerformanceTracker // For RL algorithms with persistence
	currentAlgorithm           SchedulingAlgorithm
	mu                         sync.RWMutex
	stats                      map[string]interface{}

	multiObjectiveCalculators map[AlgorithmType]*MultiObjectiveRewardCalculator
	experienceManagers        map[AlgorithmType]*ExperienceManager
}

// NewAlgorithmManager creates a new algorithm manager
func NewAlgorithmManager(cfg config.AlgorithmManagerConfig) *AlgorithmManager {
	am := &AlgorithmManager{
		config:                     cfg,
		traditionAlgorithms:        make(map[AlgorithmType]SchedulingAlgorithm),
		rlAlgorithms:               make(map[AlgorithmType]RLAlgorithm),
		performanceTracker:         NewPerformanceTracker(50),          // Simple tracker
		enhancedPerformanceTracker: NewEnhancedPerformanceTracker(100), // Enhanced tracker
		stats:                      make(map[string]interface{}),
		multiObjectiveCalculators:  make(map[AlgorithmType]*MultiObjectiveRewardCalculator),
		experienceManagers:         make(map[AlgorithmType]*ExperienceManager),
	}

	// Initialize traditional algorithms
	am.initializeTraditionalAlgorithms()

	// Initialize RL algorithms if enabled
	if cfg.RLEnabled {
		am.initializeRLAlgorithms(cfg)
	}

	// Set current algorithm
	am.setCurrentAlgorithm(AlgorithmType(cfg.DefaultAlgorithm))

	return am
}

// initializeTraditionalAlgorithms initializes traditional scheduling algorithms
func (am *AlgorithmManager) initializeTraditionalAlgorithms() {
	am.traditionAlgorithms[AlgorithmFCFS] = NewFCFSScheduler()
	am.traditionAlgorithms[AlgorithmSJF] = NewSJFScheduler()
	am.traditionAlgorithms[AlgorithmPriority] = NewPriorityScheduler()
	am.traditionAlgorithms[AlgorithmEDF] = NewEDFScheduler()
}

// initializeRLAlgorithms initializes RL algorithms
func (am *AlgorithmManager) initializeRLAlgorithms(cfg config.AlgorithmManagerConfig) {
	// Get the main RL config from global config
	mainConfig := config.GetConfig()

	// Create Q-Learning scheduler
	qlearningScheduler := NewQLearningScheduler(mainConfig.RL, cfg.RewardWeights)

	// CREATE: Multi-objective calculator if multi-objective is enabled
	var multiObjectiveCalc *MultiObjectiveRewardCalculator
	if mainConfig.RL.MultiObjective.Enabled {
		multiObjectiveCalc = NewMultiObjectiveRewardCalculator(
			cfg.RewardWeights,
			&mainConfig.RL.MultiObjective,
		)

		// Set the multi-objective calculator in the scheduler
		qlearningScheduler.SetMultiObjectiveCalculator(multiObjectiveCalc)

		// Store for future reference
		am.multiObjectiveCalculators[AlgorithmQLearning] = multiObjectiveCalc
	}

	// CREATE: Experience manager
	experienceManager := NewExperienceManager(qlearningScheduler, multiObjectiveCalc)

	// Set the experience manager in the scheduler
	qlearningScheduler.SetExperienceManager(experienceManager)

	// Store for future reference
	am.experienceManagers[AlgorithmQLearning] = experienceManager

	// Register the RL algorithm
	am.rlAlgorithms[AlgorithmQLearning] = qlearningScheduler
}

// UpdateRewardWeights updates reward weights for RL algorithms
func (am *AlgorithmManager) UpdateRewardWeights(weights config.RewardWeights) error {
	am.mu.Lock()
	defer am.mu.Unlock()

	// Update all RL algorithms with new weights
	for algType, rlAlg := range am.rlAlgorithms {
		if err := rlAlg.UpdateRewardWeights(weights); err != nil {
			return fmt.Errorf("failed to update weights for %s algorithm: %w", algType, err)
		}
	}

	return nil
}

// Rest of the methods remain the same...
func (am *AlgorithmManager) GetAlgorithm(algType AlgorithmType) SchedulingAlgorithm {
	am.mu.RLock()
	defer am.mu.RUnlock()

	if rlAlg, exists := am.rlAlgorithms[algType]; exists {
		return rlAlg
	}

	if tradAlg, exists := am.traditionAlgorithms[algType]; exists {
		return tradAlg
	}

	return nil
}

func (am *AlgorithmManager) GetCurrentAlgorithm() SchedulingAlgorithm {
	am.mu.RLock()
	defer am.mu.RUnlock()
	return am.currentAlgorithm
}

func (am *AlgorithmManager) SelectAlgorithm(tasks []TaskEntry, nodeManager SingleNodeManager) SchedulingAlgorithm {
	am.mu.RLock()
	defer am.mu.RUnlock()

	selectedAlg := am.GetAlgorithm(AlgorithmType(am.config.DefaultAlgorithm))
	if selectedAlg != nil {
		return selectedAlg
	}

	fallbackAlgorithm := am.GetAlgorithm(AlgorithmType(am.config.FallbackAlgorithm))
	if fallbackAlgorithm != nil {
		return fallbackAlgorithm
	}

	return am.traditionAlgorithms[AlgorithmFCFS]
}

func (am *AlgorithmManager) setCurrentAlgorithm(algType AlgorithmType) {
	algorithm := am.GetAlgorithm(algType)
	if algorithm != nil {
		am.currentAlgorithm = algorithm
	} else {
		am.currentAlgorithm = am.traditionAlgorithms[AlgorithmFCFS]
	}
}

func (am *AlgorithmManager) SetLearningMode(enabled bool) {
	am.mu.Lock()
	defer am.mu.Unlock()

	for _, rlAlg := range am.rlAlgorithms {
		rlAlg.SetLearningMode(enabled)
	}
}

func (am *AlgorithmManager) RecordPerformance(algType AlgorithmType, nodeManager SingleNodeManager, tasks []TaskEntry) {
	stats := map[string]float64{
		"task_count":       float64(len(tasks)),
		"node_load":        nodeManager.GetCurrentLoad(),
		"cpu_available":    nodeManager.GetAvailableCPU(),
		"memory_available": float64(nodeManager.GetAvailableMemory()),
	}

	// Add episode-aware performance tracking for RL algorithms
	if rlAlg, exists := am.rlAlgorithms[algType]; exists {
		if qlScheduler, ok := rlAlg.(*QLearningScheduler); ok {
			stats["current_episode"] = float64(qlScheduler.GetCurrentEpisode())
			stats["episode_progress"] = qlScheduler.GetEpisodeProgress()
			stats["episode_task_count"] = float64(qlScheduler.GetEpisodeTaskCount())
			stats["exploration_rate"] = qlScheduler.config.ExplorationRate
		}

		// USE ENHANCED TRACKER for RL algorithms
		am.enhancedPerformanceTracker.RecordPerformance(algType, stats)
	} else {
		// USE SIMPLE TRACKER for traditional algorithms
		am.performanceTracker.RecordPerformance(algType, stats)
	}
}

func (am *AlgorithmManager) GetAlgorithmStats() map[string]interface{} {
	am.mu.RLock()
	defer am.mu.RUnlock()

	stats := make(map[string]interface{})

	tradStats := make(map[string]interface{})
	for algType, alg := range am.traditionAlgorithms {
		tradStats[string(algType)] = alg.GetStats()
	}
	stats["traditional"] = tradStats

	rlStats := make(map[string]interface{})
	for algType, alg := range am.rlAlgorithms {
		rlStats[string(algType)] = alg.GetStats()
	}
	stats["rl"] = rlStats

	if am.currentAlgorithm != nil {
		stats["current_algorithm"] = am.currentAlgorithm.Name()
	}

	stats["default_algorithm"] = am.config.DefaultAlgorithm
	stats["fallback_algorithm"] = am.config.FallbackAlgorithm

	return stats
}

func (am *AlgorithmManager) GetAvailableAlgorithms() []string {
	am.mu.RLock()
	defer am.mu.RUnlock()

	var algorithms []string

	for algType := range am.traditionAlgorithms {
		algorithms = append(algorithms, string(algType))
	}

	for algType := range am.rlAlgorithms {
		algorithms = append(algorithms, string(algType))
	}

	return algorithms
}

func (am *AlgorithmManager) GetBestAlgorithm() AlgorithmType {
	// Get best from both trackers and compare
	traditionalBest := am.performanceTracker.GetBestAlgorithm()
	rlBest := am.enhancedPerformanceTracker.GetBestAlgorithm()

	// Simple logic: prefer RL if available and learning is enabled
	if am.config.RLEnabled && rlBest != "" {
		return rlBest
	}

	return traditionalBest
}

func (am *AlgorithmManager) String() string {
	am.mu.RLock()
	defer am.mu.RUnlock()

	var sb strings.Builder
	sb.WriteString("AlgorithmManager{")
	sb.WriteString(fmt.Sprintf("default: %s, ", am.config.DefaultAlgorithm))
	sb.WriteString(fmt.Sprintf("fallback: %s, ", am.config.FallbackAlgorithm))
	sb.WriteString(fmt.Sprintf("traditional: %d, ", len(am.traditionAlgorithms)))
	sb.WriteString(fmt.Sprintf("rl: %d", len(am.rlAlgorithms)))
	sb.WriteString("}")
	return sb.String()
}

// ProcessTaskCompletion processes task completion through RL algorithms
func (am *AlgorithmManager) ProcessTaskCompletion(task TaskEntry, report *pb.TaskCompletionReport, nodeManager SingleNodeManager) error {
	am.mu.Lock()
	defer am.mu.Unlock()

	// Only process if we have RL algorithms enabled
	if !am.config.RLEnabled {
		return nil // No RL processing needed
	}

	// Validate inputs
	if task == nil {
		return fmt.Errorf("task is nil")
	}

	if report == nil {
		return fmt.Errorf("completion report is nil for task %s", task.GetTaskID())
	}

	// Find Q-Learning algorithm to handle experience completion
	if qlearningAlg, exists := am.rlAlgorithms[AlgorithmQLearning]; exists {
		// Cast to QLearningScheduler to access experience management
		if qlScheduler, ok := qlearningAlg.(*QLearningScheduler); ok {
			// Process with comprehensive error handling
			err := qlScheduler.ProcessTaskCompletion(task, report)
			if err != nil {
				// Log error but record performance anyway for tracking
				fmt.Printf("Error processing task completion for %s: %v\n", task.GetTaskID(), err)

				// Still record performance metrics for analysis if nodeManager provided
				if nodeManager != nil {
					am.RecordPerformance(AlgorithmQLearning, nodeManager, []TaskEntry{task})
				}

				return fmt.Errorf("task completion processing failed: %w", err)
			}

			// Record performance metrics for episode-aware tracking
			if nodeManager != nil {
				am.RecordPerformance(AlgorithmQLearning, nodeManager, []TaskEntry{task})
			}

			// Experience completed and Q-table updated
			fmt.Printf("Task completion processed successfully for %s (RL experience updated)\n", task.GetTaskID())
			return nil
		}
	}

	return fmt.Errorf("Q-Learning algorithm not available for experience processing")
}

// CleanupExperiences triggers cleanup for all experience managers
func (am *AlgorithmManager) CleanupExperiences() {
	am.mu.RLock()
	defer am.mu.RUnlock()

	for _, em := range am.experienceManagers {
		if em != nil {
			em.Cleanup()
		}
	}
}

// GetExperienceManagerStats returns statistics from all experience managers
func (am *AlgorithmManager) GetExperienceManagerStats() map[string]interface{} {
	am.mu.RLock()
	defer am.mu.RUnlock()

	stats := make(map[string]interface{})
	for algType, em := range am.experienceManagers {
		if em != nil {
			stats[string(algType)] = em.GetStats()
		}
	}
	return stats
}

// GetMultiObjectiveStats returns statistics from all multi-objective calculators
func (am *AlgorithmManager) GetMultiObjectiveStats() map[string]interface{} {
	am.mu.RLock()
	defer am.mu.RUnlock()

	stats := make(map[string]interface{})
	for algType, calc := range am.multiObjectiveCalculators {
		if calc != nil {
			stats[string(algType)] = map[string]interface{}{
				"active_profile":       calc.GetActiveProfile(),
				"scalarization_method": calc.GetScalarizationMethod(),
				"pareto_front_size":    len(calc.GetParetoFront()),
				"adaptation_history":   len(calc.GetAdaptationHistory()),
				"performance_history":  len(calc.GetPerformanceHistory(0)),
			}
		}
	}
	return stats
}

// ValidateIntegration validates that all components are properly integrated
func (am *AlgorithmManager) ValidateIntegration() []string {
	am.mu.RLock()
	defer am.mu.RUnlock()

	var issues []string

	// Check RL algorithm integration
	for algType, rlAlg := range am.rlAlgorithms {
		if qlScheduler, ok := rlAlg.(*QLearningScheduler); ok {
			// Check experience manager integration
			if qlScheduler.GetExperienceManager() == nil {
				issues = append(issues, fmt.Sprintf("Algorithm %s missing experience manager", algType))
			}

			// Check multi-objective calculator integration
			if am.multiObjectiveCalculators[algType] == nil {
				cfg := config.GetConfig()
				if cfg.RL.MultiObjective.Enabled {
					issues = append(issues, fmt.Sprintf("Algorithm %s missing multi-objective calculator", algType))
				}
			}
		}
	}

	// Check experience manager integration
	for algType, em := range am.experienceManagers {
		if em == nil {
			issues = append(issues, fmt.Sprintf("Experience manager for %s is nil", algType))
		}
	}

	if len(issues) == 0 {
		fmt.Println("All integrations validated successfully")
	}

	return issues
}

// ============= GETTER METHODS FOR MODEL PERSISTENCE =============

// GetCurrentAlgorithmType returns the type of the currently active algorithm
func (am *AlgorithmManager) GetCurrentAlgorithmType() string {
	am.mu.RLock()
	defer am.mu.RUnlock()

	if am.currentAlgorithm == nil {
		return am.config.DefaultAlgorithm // fallback to default
	}

	// Determine algorithm type by checking which map contains the current algorithm
	for algType, alg := range am.traditionAlgorithms {
		if alg == am.currentAlgorithm {
			return string(algType)
		}
	}

	for algType, alg := range am.rlAlgorithms {
		if alg == am.currentAlgorithm {
			return string(algType)
		}
	}

	return am.config.DefaultAlgorithm // fallback
}

// GetDefaultAlgorithm returns the default algorithm type configured
func (am *AlgorithmManager) GetDefaultAlgorithm() string {
	return am.config.DefaultAlgorithm
}

// GetFallbackAlgorithm returns the fallback algorithm type configured
func (am *AlgorithmManager) GetFallbackAlgorithm() string {
	return am.config.FallbackAlgorithm
}

// IsRLEnabled checks if Reinforcement Learning is enabled
func (am *AlgorithmManager) IsRLEnabled() bool {
	return am.config.RLEnabled
}

// IsLearningModeEnabled checks if the learning mode is currently enabled for RL algorithms
func (am *AlgorithmManager) IsLearningModeEnabled() bool {
	am.mu.RLock()
	defer am.mu.RUnlock()

	// Check if any RL algorithm is in learning mode
	for _, rlAlg := range am.rlAlgorithms {
		if rlAlg.IsLearning() {
			return true
		}
	}
	return false
}

// GetTotalTasksProcessed returns the total number of tasks processed across all algorithms
func (am *AlgorithmManager) GetTotalTasksProcessed() int64 {
	am.mu.RLock()
	defer am.mu.RUnlock()

	// Get total from ENHANCED performance tracker (for RL persistence)
	if am.enhancedPerformanceTracker != nil {
		return am.enhancedPerformanceTracker.GetTotalTasksProcessed()
	}
	return 0
}

// GetQLearningAlgorithm returns the Q-learning algorithm if available
func (am *AlgorithmManager) GetQLearningAlgorithm() RLAlgorithm {
	am.mu.RLock()
	defer am.mu.RUnlock()

	if ql, exists := am.rlAlgorithms[AlgorithmQLearning]; exists {
		return ql
	}
	return nil
}

// ============= SETTER METHODS FOR MODEL PERSISTENCE =============

// SetCurrentAlgorithmType sets the current algorithm by its type
func (am *AlgorithmManager) SetCurrentAlgorithmType(algType string) {
	am.mu.Lock()
	defer am.mu.Unlock()

	am.setCurrentAlgorithm(AlgorithmType(algType))
}

// SetRLEnabled sets the Reinforcement Learning enabled flag
func (am *AlgorithmManager) SetRLEnabled(enabled bool) {
	am.mu.Lock()
	defer am.mu.Unlock()

	am.config.RLEnabled = enabled
}

// SetLearningModeEnabled sets the learning mode for RL algorithms
func (am *AlgorithmManager) SetLearningModeEnabled(enabled bool) {
	am.mu.Lock()
	defer am.mu.Unlock()

	// Set learning mode for all RL algorithms
	for _, rlAlg := range am.rlAlgorithms {
		rlAlg.SetLearningMode(enabled)
	}
}

// SetTotalTasksProcessed sets the total number of tasks processed
func (am *AlgorithmManager) SetTotalTasksProcessed(total int64) {
	am.mu.Lock()
	defer am.mu.Unlock()

	// Set total in ENHANCED performance tracker (for RL persistence)
	if am.enhancedPerformanceTracker != nil {
		am.enhancedPerformanceTracker.SetTotalTasksProcessed(total)
	}
}
