package rl

import (
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Agent manages the reinforcement learning process for fog node selection
type Agent struct {
	// Core RL components
	algorithmManager *AlgorithmManager
	stateEncoder     StateRepresentation

	// Learning parameters
	learningEnabled bool
	mutex           sync.RWMutex

	// Logging component
	logger interface {
		Info(msg string)
		Error(msg string, err error)
	}

	savePath string // address of agent's model

	performanceMetrics   *PerformanceMetrics
	performanceTracking  bool
	usingEnhancedEncoder bool // Whether using enhanced encoder

	tuningManager *TuningManager
}

// PerformanceMetrics tracks the RL agent's performance over time
type PerformanceMetrics struct {
	// Sliding window of recent rewards
	recentRewards    []float64
	maxRecentRewards int // Size limit for the reward window

	// Overall statistics
	totalDecisions   int64
	successfulTasks  int64
	failedTasks      int64
	cumulativeReward float64

	// Time tracking
	avgDecisionTimeNs int64 // Average time to make a decision in nanoseconds

	// Per-algorithm metrics
	algorithmMetrics map[string]*AlgorithmMetrics

	mutex sync.RWMutex
}

// AlgorithmMetrics tracks metrics for a single algorithm
type AlgorithmMetrics struct {
	Decisions   int64
	SuccessRate float64
	AvgReward   float64
	LastUpdated time.Time
}

// PerformanceSnapshot represents a snapshot of performance metrics
type PerformanceSnapshot struct {
	TotalDecisions    int64
	SuccessfulTasks   int64
	FailedTasks       int64
	SuccessRate       float64
	AvgRecentReward   float64
	CumulativeReward  float64
	AvgDecisionTimeNs int64
	RecentRewardsSize int
	AlgorithmMetrics  map[string]AlgorithmMetricsSnapshot

	HybridMetrics *HybridMetrics `json:"hybrid_metrics,omitempty"`
}

// AlgorithmMetricsSnapshot represents metrics for a single algorithm
type AlgorithmMetricsSnapshot struct {
	Decisions   int64
	SuccessRate float64
	AvgReward   float64
	LastUpdated time.Time
}

type HybridMetrics struct {
	TransitionProgress float64 // How far the algorithm has progressed from heuristic to RL
	ConfidenceAvg      float64 // Average confidence in decisions
	HeuristicUsage     float64 // Percentage of decisions made using heuristics
}

// NewPerformanceMetrics creates a new metrics tracker
func NewPerformanceMetrics(windowSize int) *PerformanceMetrics {
	if windowSize <= 0 {
		windowSize = 100 // Default window size
	}

	return &PerformanceMetrics{
		recentRewards:    make([]float64, 0, windowSize),
		maxRecentRewards: windowSize,
		algorithmMetrics: make(map[string]*AlgorithmMetrics),
	}
}

// NewAgent creates a new reinforcement learning agent
func NewAgent(logger interface {
	Info(msg string)
	Error(msg string, err error)
}, savePath string, options ...AgentOption) *Agent {
	if savePath == "" {
		savePath = "./models"
	}

	// Create save directory if it doesn't exist
	if err := os.MkdirAll(savePath, 0755); err != nil && logger != nil {
		logger.Error("Failed to create model save directory", err)
	}

	// Create state encoder with 5 discretization levels (default)
	stateEncoder := NewBasicStateEncoder(5)

	// Create algorithm manager
	algorithmManager := NewAlgorithmManager()

	// Create and register basic algorithms
	qLearning := NewQLearningAlgorithm(stateEncoder)
	algorithmManager.RegisterAlgorithm(qLearning)

	sarsa := NewSARSAAlgorithm(stateEncoder)
	algorithmManager.RegisterAlgorithm(sarsa)

	// Default to Q-Learning as active algorithm // TODO constant active algorithm
	algorithmManager.SetActiveAlgorithm("q_learning")

	agent := &Agent{
		algorithmManager:     algorithmManager,
		stateEncoder:         stateEncoder,
		learningEnabled:      true,
		logger:               logger,
		savePath:             savePath,
		performanceMetrics:   NewPerformanceMetrics(1000), // Track last 1000 rewards // TODO constant
		performanceTracking:  true,
		usingEnhancedEncoder: false,
	}

	// Apply all options to customize the agent
	for _, option := range options {
		option(agent)
	}

	// Initialize the tuning manager
	agent.tuningManager = NewTuningManager(agent, logger)

	if logger != nil {
		algs := algorithmManager.GetRegisteredAlgorithms()
		logger.Info(fmt.Sprintf("RL Agent initialized with algorithms: %v", algs))
	}

	return agent
}

// SelectNode chooses the best node for task allocation based on current system state
func (a *Agent) SelectNode(state SystemState, availableNodes []string) (string, error) {
	// Start timing the decision
	startTime := time.Now()

	// Validate inputs
	if len(availableNodes) == 0 {
		return "", fmt.Errorf("no available nodes to select from")
	}
	if state.FogNodes == nil || len(state.FogNodes) == 0 {
		return "", fmt.Errorf("system state has no fog nodes")
	}
	
	// Validate that available nodes exist in the state
	for _, nodeID := range availableNodes {
		if _, exists := state.FogNodes[nodeID]; !exists {
			return "", fmt.Errorf("node %s not found in system state", nodeID)
		}
	}

	a.mutex.RLock()
	defer a.mutex.RUnlock()

	// If learning is disabled, use simple selection strategy
	if !a.learningEnabled {
		// Use least loaded or round-robin strategy when learning is off
		return availableNodes[0], nil
	}

	// If using enhanced encoder with extended state, convert to extended state
	var extendedState *ExtendedSystemState
	if a.usingEnhancedEncoder {
		// Create an enhanced state from the base state
		extendedState = ToExtendedState(state)

		// Only update metrics if we have history data
		if extendedState != nil && a.IsUsingHybridAlgorithm() {
			// Update system metrics before decision
			extendedState.CalculateSystemMetrics()
			extendedState.CalculateTrends()
		}
	}

	// Use algorithm manager to select best node
	// We'll use the original state for now - algorithm will handle extended state internally if needed
	selectedNode, err := a.algorithmManager.SelectAction(state, availableNodes)
	if err != nil {
		if a.logger != nil {
			a.logger.Error("Failed to select node using RL algorithm", err)
		}

		// Fallback to simple selection if algorithm fails
		return availableNodes[0], nil
	}

	// Track the decision time
	decisionTimeNs := time.Since(startTime).Nanoseconds()
	a.TrackDecision(a.GetActiveAlgorithm(), decisionTimeNs)

	return selectedNode, nil
}

// Learn updates the agent's knowledge based on task outcome
func (a *Agent) Learn(prevState SystemState, selectedNode string,
	newState SystemState, success bool, executionTimeMs int64) {
	a.mutex.Lock()
	defer a.mutex.Unlock()

	// Skip learning if disabled
	if !a.learningEnabled {
		return
	}

	// Calculate reward based on outcome and state changes
	reward := CalculateReward(prevState, newState, success, executionTimeMs, 0)

	// Update the active algorithm
	err := a.algorithmManager.Learn(prevState, selectedNode, newState, reward)
	if err != nil && a.logger != nil {
		a.logger.Error("Failed to update RL model", err)
	}

	// Track the reward for performance monitoring
	a.TrackReward(a.GetActiveAlgorithm(), reward, success)

	if a.logger != nil {
		a.logger.Info(fmt.Sprintf("RL model updated with reward: %.2f", reward))
	}
}

// SetActiveAlgorithm changes the active algorithm used for decision making
func (a *Agent) SetActiveAlgorithm(algorithmName string) error {
	a.mutex.Lock()
	defer a.mutex.Unlock()

	err := a.algorithmManager.SetActiveAlgorithm(algorithmName)
	if err == nil && a.logger != nil {
		a.logger.Info(fmt.Sprintf("Switched to %s algorithm", algorithmName))
	}

	return err
}

// GetActiveAlgorithm returns the name of the currently active algorithm
func (a *Agent) GetActiveAlgorithm() string {
	a.mutex.RLock()
	defer a.mutex.RUnlock()

	alg, err := a.algorithmManager.GetActiveAlgorithm()
	if err != nil {
		return "unknown"
	}

	return alg.Name()
}

// EnableLearning turns on the learning functionality
func (a *Agent) EnableLearning() {
	a.mutex.Lock()
	defer a.mutex.Unlock()

	a.learningEnabled = true
	if a.logger != nil {
		a.logger.Info("RL learning enabled")
	}
}

// DisableLearning turns off the learning functionality (agent will use current knowledge only)
func (a *Agent) DisableLearning() {
	a.mutex.Lock()
	defer a.mutex.Unlock()

	a.learningEnabled = false
	if a.logger != nil {
		a.logger.Info("RL learning disabled")
	}
}

// IsLearningEnabled returns whether learning is currently enabled
func (a *Agent) IsLearningEnabled() bool {
	a.mutex.RLock()
	defer a.mutex.RUnlock()

	return a.learningEnabled
}

// CalculateReward determines the reward value based on task outcome and system state changes
func CalculateReward(prevState SystemState, newState SystemState, success bool,
	executionTimeMs int64, deadlineMs int64) float64 {
	// Base reward depends on task success/failure
	if !success {
		return -10.0 // Strong penalty for failure
	}

	// Start with base reward for success
	reward := 5.0

	// Add reward components based on execution time
	if deadlineMs > 0 {
		if executionTimeMs <= deadlineMs {
			// Task completed within deadline - positive reward
			timeRatio := float64(executionTimeMs) / float64(deadlineMs)
			if timeRatio <= 0.5 {
				reward += 3.0 // Completed in half the deadline or less
			} else {
				reward += 1.0 // Completed within deadline
			}
		} else {
			// Task exceeded deadline - negative component
			reward -= 2.0
		}
	} else {
		// No deadline specified, use fixed thresholds
		if executionTimeMs < 500 {
			reward += 3.0 // Fast execution bonus
		} else if executionTimeMs > 2000 {
			reward -= 1.0 // Slow execution penalty
		}
	}

	// Calculate load balance improvement
	prevImbalance := calculateLoadImbalance(prevState)
	newImbalance := calculateLoadImbalance(newState)

	// Reward improvement in balance
	balanceChange := prevImbalance - newImbalance
	reward += balanceChange * 5.0

	return reward
}

// calculateLoadImbalance computes how imbalanced the system is
func calculateLoadImbalance(state SystemState) float64 {
	if len(state.FogNodes) < 2 {
		return 0.0
	}

	// Calculate average CPU utilization
	total := 0.0
	for _, node := range state.FogNodes {
		total += node.CPUUtilization
	}
	avg := total / float64(len(state.FogNodes))

	// Calculate variance
	variance := 0.0
	for _, node := range state.FogNodes {
		diff := node.CPUUtilization - avg
		variance += diff * diff
	}
	variance /= float64(len(state.FogNodes))

	return variance
}

func (a *Agent) GetAlgorithmManager() *AlgorithmManager {
	a.mutex.RLock()
	defer a.mutex.RUnlock()

	return a.algorithmManager
}

// SaveModels persists all algorithm models to disk
func (a *Agent) SaveModels() error {
	a.mutex.RLock()
	algorithms := a.algorithmManager.GetRegisteredAlgorithms()
	a.mutex.RUnlock()

	var lastErr error
	for _, name := range algorithms {
		alg, err := a.algorithmManager.GetAlgorithm(name)
		if err != nil {
			lastErr = err
			if a.logger != nil {
				a.logger.Error(fmt.Sprintf("Failed to get algorithm: %s", name), err)
			}
			continue
		}

		modelPath := filepath.Join(a.savePath, fmt.Sprintf("%s.json", name))
		if err := alg.SaveModel(modelPath); err != nil {
			lastErr = err
			if a.logger != nil {
				a.logger.Error(fmt.Sprintf("Failed to save model: %s", name), err)
			}
		} else if a.logger != nil {
			a.logger.Info(fmt.Sprintf("Saved model: %s to %s", name, modelPath))
		}
	}

	return lastErr
}

// LoadModels attempts to load algorithm models from disk
func (a *Agent) LoadModels() error {
	a.mutex.RLock()
	algorithms := a.algorithmManager.GetRegisteredAlgorithms()
	a.mutex.RUnlock()

	var lastErr error
	for _, name := range algorithms {
		alg, err := a.algorithmManager.GetAlgorithm(name)
		if err != nil {
			lastErr = err
			if a.logger != nil {
				a.logger.Error(fmt.Sprintf("Failed to get algorithm: %s", name), err)
			}
			continue
		}

		modelPath := filepath.Join(a.savePath, fmt.Sprintf("%s.json", name))

		// Check if file exists
		if _, err := os.Stat(modelPath); os.IsNotExist(err) {
			if a.logger != nil {
				a.logger.Info(fmt.Sprintf("No saved model found for: %s", name))
			}
			continue
		}

		if err := alg.LoadModel(modelPath); err != nil {
			lastErr = err
			if a.logger != nil {
				a.logger.Error(fmt.Sprintf("Failed to load model: %s", name), err)
			}
		} else if a.logger != nil {
			a.logger.Info(fmt.Sprintf("Loaded model: %s from %s", name, modelPath))
		}
	}

	return lastErr
}

// TrackDecision records a decision made by the agent
func (a *Agent) TrackDecision(algorithm string, decisionTimeNs int64) {
	if !a.performanceTracking {
		return
	}

	a.performanceMetrics.mutex.Lock()
	defer a.performanceMetrics.mutex.Unlock()

	a.performanceMetrics.totalDecisions++

	// Update decision time average
	prevTotal := float64(a.performanceMetrics.avgDecisionTimeNs) * float64(a.performanceMetrics.totalDecisions-1)
	a.performanceMetrics.avgDecisionTimeNs = int64(
		(prevTotal + float64(decisionTimeNs)) / float64(a.performanceMetrics.totalDecisions),
	)

	// Update algorithm-specific metrics
	if _, exists := a.performanceMetrics.algorithmMetrics[algorithm]; !exists {
		a.performanceMetrics.algorithmMetrics[algorithm] = &AlgorithmMetrics{
			LastUpdated: time.Now(),
		}
	}

	am := a.performanceMetrics.algorithmMetrics[algorithm]
	am.Decisions++
	am.LastUpdated = time.Now()
}

// TrackReward records the outcome and reward from a decision
func (a *Agent) TrackReward(algorithm string, reward float64, success bool) {
	if !a.performanceTracking {
		return
	}

	a.performanceMetrics.mutex.Lock()
	defer a.performanceMetrics.mutex.Unlock()

	// Update success/failure counts
	if success {
		a.performanceMetrics.successfulTasks++
	} else {
		a.performanceMetrics.failedTasks++
	}

	// Update cumulative reward
	a.performanceMetrics.cumulativeReward += reward

	// Add to recent rewards sliding window
	if len(a.performanceMetrics.recentRewards) >= a.performanceMetrics.maxRecentRewards {
		// Remove oldest reward to maintain window size
		a.performanceMetrics.recentRewards = a.performanceMetrics.recentRewards[1:]
	}
	a.performanceMetrics.recentRewards = append(a.performanceMetrics.recentRewards, reward)

	// Update algorithm-specific metrics
	if am, exists := a.performanceMetrics.algorithmMetrics[algorithm]; exists {
		// Update average reward for this algorithm
		prevTotal := am.AvgReward * float64(am.Decisions-1)
		am.AvgReward = (prevTotal + reward) / float64(am.Decisions)

		// Update success rate
		successCount := float64(a.performanceMetrics.successfulTasks)
		totalCount := float64(a.performanceMetrics.successfulTasks + a.performanceMetrics.failedTasks)
		am.SuccessRate = successCount / totalCount

		am.LastUpdated = time.Now()
	}
}

// GetPerformanceMetrics returns a copy of the current performance metrics
func (a *Agent) GetPerformanceMetrics() PerformanceSnapshot {
	a.performanceMetrics.mutex.RLock()
	defer a.performanceMetrics.mutex.RUnlock()

	// Calculate average recent reward
	avgRecentReward := 0.0
	if len(a.performanceMetrics.recentRewards) > 0 {
		sum := 0.0
		for _, r := range a.performanceMetrics.recentRewards {
			sum += r
		}
		avgRecentReward = sum / float64(len(a.performanceMetrics.recentRewards))
	}

	// Calculate overall success rate
	successRate := 0.0
	totalCount := a.performanceMetrics.successfulTasks + a.performanceMetrics.failedTasks
	if totalCount > 0 {
		successRate = float64(a.performanceMetrics.successfulTasks) / float64(totalCount)
	}

	// Get algorithm metrics
	algorithmMetrics := make(map[string]AlgorithmMetricsSnapshot)
	for name, metrics := range a.performanceMetrics.algorithmMetrics {
		algorithmMetrics[name] = AlgorithmMetricsSnapshot{
			Decisions:   metrics.Decisions,
			SuccessRate: metrics.SuccessRate,
			AvgReward:   metrics.AvgReward,
			LastUpdated: metrics.LastUpdated,
		}
	}

	// Add hybrid metrics if applicable
	var hybridMetrics *HybridMetrics

	if a.IsUsingHybridAlgorithm() {
		alg, err := a.algorithmManager.GetAlgorithm("hybrid")
		if err == nil {
			if hybrid, ok := alg.(*HybridAlgorithm); ok {
				hybridMetrics = &HybridMetrics{
					TransitionProgress: hybrid.GetTransitionProgress(),
					ConfidenceAvg:      hybrid.GetAverageConfidence(),
					HeuristicUsage:     hybrid.GetHeuristicUsage(),
				}
			}
		}
	}

	return PerformanceSnapshot{
		TotalDecisions:    a.performanceMetrics.totalDecisions,
		SuccessfulTasks:   a.performanceMetrics.successfulTasks,
		FailedTasks:       a.performanceMetrics.failedTasks,
		SuccessRate:       successRate,
		AvgRecentReward:   avgRecentReward,
		CumulativeReward:  a.performanceMetrics.cumulativeReward,
		AvgDecisionTimeNs: a.performanceMetrics.avgDecisionTimeNs,
		RecentRewardsSize: len(a.performanceMetrics.recentRewards),
		AlgorithmMetrics:  algorithmMetrics,
		HybridMetrics:     hybridMetrics,
	}
}

// SetAlgorithmParameters updates parameters for a specific algorithm
func (a *Agent) SetAlgorithmParameters(algorithmName string, parameters map[string]float64) error {
	// Validate input
	if algorithmName == "" {
		return fmt.Errorf("algorithm name cannot be empty")
	}
	if parameters == nil {
		return fmt.Errorf("parameters cannot be nil")
	}
	if len(parameters) == 0 {
		return fmt.Errorf("parameters cannot be empty")
	}
	
	a.mutex.Lock()
	defer a.mutex.Unlock()

	alg, err := a.algorithmManager.GetAlgorithm(algorithmName)
	if err != nil {
		return fmt.Errorf("algorithm not found: %s", algorithmName)
	}

	// Validate parameter values
	for name, value := range parameters {
		if math.IsNaN(value) || math.IsInf(value, 0) {
			return fmt.Errorf("invalid parameter value for %s: %f", name, value)
		}
	}

	// Convert float64 parameters to interface{} for SetParams
	paramsInterface := make(map[string]interface{})
	for name, value := range parameters {
		paramsInterface[name] = value
	}

	// Update parameters
	if err := alg.SetParams(paramsInterface); err != nil {
		return fmt.Errorf("failed to set parameters: %v", err)
	}

	if a.logger != nil {
		a.logger.Info(fmt.Sprintf("Updated parameters for algorithm %s", algorithmName))
	}

	return nil
}

// GetAlgorithmParameters returns the current parameters for a specific algorithm
func (a *Agent) GetAlgorithmParameters(algorithmName string) (map[string]float64, error) {
	a.mutex.RLock()
	defer a.mutex.RUnlock()

	alg, err := a.algorithmManager.GetAlgorithm(algorithmName)
	if err != nil {
		return nil, fmt.Errorf("algorithm not found: %s", algorithmName)
	}

	// Get parameters and convert from interface{} to float64
	paramsInterface := alg.GetParams()
	paramsFloat := make(map[string]float64)

	for name, value := range paramsInterface {
		if floatVal, ok := value.(float64); ok {
			paramsFloat[name] = floatVal
		}
	}

	return paramsFloat, nil
}

// GetExtendedSystemState creates a new extended system state from a basic state
func (a *Agent) GetExtendedSystemState(state SystemState) *ExtendedSystemState {
	if a.usingEnhancedEncoder {
		return ToExtendedState(state)
	}
	return nil
}

// EncodeStateEnhanced encodes a state using the enhanced encoder if available
func (a *Agent) EncodeStateEnhanced(state SystemState, extState *ExtendedSystemState) string {
	if enhancedEncoder, ok := a.stateEncoder.(*EnhancedStateEncoder); ok {
		if extState != nil {
			return enhancedEncoder.EncodeExtendedState(extState)
		}
		return enhancedEncoder.EncodeState(state)
	}
	// Fallback to basic encoding
	return a.stateEncoder.EncodeState(state)
}

// IsUsingEnhancedEncoder returns whether the agent is using the enhanced encoder
func (a *Agent) IsUsingEnhancedEncoder() bool {
	return a.usingEnhancedEncoder
}

// IsUsingHybridAlgorithm returns whether the agent is currently using the hybrid algorithm
func (a *Agent) IsUsingHybridAlgorithm() bool {
	return a.GetActiveAlgorithm() == "hybrid"
}

// StartTuning begins the hyperparameter tuning process
func (a *Agent) StartTuning(algorithmName string, strategyName string, budget int) error {
	// Configure algorithm to tune
	if algorithmName == "" {
		algorithmName = a.GetActiveAlgorithm()
	}

	if err := a.tuningManager.SetAlgorithmToTune(algorithmName); err != nil {
		return err
	}

	// Set default parameter ranges for this algorithm
	paramRanges := a.tuningManager.GetDefaultParamRanges(algorithmName)
	for param, rng := range paramRanges {
		a.tuningManager.AddParameterRange(
			param,
			rng.Min,
			rng.Max,
			rng.LogScale,
			rng.Type,
		)
	}

	// Configure tuning strategy
	var strategy TuningStrategy

	switch strategyName {
	case "evolutionary": // TODO ?
		popSize := budget / 4
		if popSize < 4 {
			popSize = 4
		}
		generations := budget / popSize
		if generations < 1 {
			generations = 1
		}
		strategy = NewEvolutionarySearchStrategy(popSize, generations)
	default:
		strategy = NewRandomSearchStrategy(budget)
	}

	a.tuningManager.SetStrategy(strategy)
	a.tuningManager.SetEvaluationBudget(budget, 1)

	// Start tuning
	return a.tuningManager.StartTuning()
}

// StopTuning halts the hyperparameter tuning process
func (a *Agent) StopTuning() {
	a.tuningManager.StopTuning()
}

// GetTuningStatus returns the current status of the tuning process
func (a *Agent) GetTuningStatus() TuningStatus {
	return a.tuningManager.GetTuningStatus()
}

// ApplyTunedParameters applies the best parameters found during tuning
func (a *Agent) ApplyTunedParameters() (map[string]float64, float64, error) {
	err := a.tuningManager.ApplyBestParameters()
	if err != nil {
		return nil, 0, err
	}

	params, score, found := a.tuningManager.GetBestParameters()
	if !found {
		return nil, 0, errors.New("no tuned parameters available")
	}

	return params, score, nil
}
