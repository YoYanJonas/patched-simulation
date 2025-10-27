package rl

import (
	"errors"
	"fmt"
	"sync"
	"time"
)

// TuningStatus contains metrics about the tuning process
type TuningStatus struct {
	IsRunning      bool
	Pending        int
	InProgress     int
	Completed      int
	BestScore      float64
	BestParams     map[string]float64
	Strategy       string
	AlgorithmName  string
	StartTime      time.Time
	LastUpdateTime time.Time
}

// TuningManager coordinates hyperparameter tuning experiments
type TuningManager struct {
	strategy          TuningStrategy
	agent             *Agent
	algorithmName     string
	paramSpace        map[string]ParamRange
	experiments       map[string]*Experiment
	completedExps     []*Experiment
	pendingExps       []*Experiment
	runningExps       map[string]*Experiment
	evaluationBudget  int
	evaluationsPerExp int
	isRunning         bool
	startTime         time.Time
	lastUpdateTime    time.Time
	mutex             sync.RWMutex

	// Logger for status updates
	logger interface {
		Info(msg string)
		Error(msg string, err error)
	}
}

// NewTuningManager creates a new tuning manager
func NewTuningManager(agent *Agent, logger interface {
	Info(msg string)
	Error(msg string, err error)
}) *TuningManager {
	return &TuningManager{
		agent:             agent,
		paramSpace:        make(map[string]ParamRange),
		experiments:       make(map[string]*Experiment),
		runningExps:       make(map[string]*Experiment),
		completedExps:     make([]*Experiment, 0),
		pendingExps:       make([]*Experiment, 0),
		evaluationBudget:  100, // Default: 100 evaluations total
		evaluationsPerExp: 5,   // Default: 5 evaluations per experiment
		logger:            logger,
	}
}

// SetStrategy configures the tuning strategy
func (tm *TuningManager) SetStrategy(strategy TuningStrategy) {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	tm.strategy = strategy
}

// SetAlgorithmToTune specifies which algorithm to optimize
func (tm *TuningManager) SetAlgorithmToTune(name string) error {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	// Check if algorithm exists
	_, err := tm.agent.algorithmManager.GetAlgorithm(name)
	if err != nil {
		return fmt.Errorf("algorithm not found: %s", name)
	}

	tm.algorithmName = name
	return nil
}

// AddParameterRange adds a parameter to tune with its range
func (tm *TuningManager) AddParameterRange(name string, min, max float64, logScale bool, paramType string) {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	tm.paramSpace[name] = ParamRange{
		Min:      min,
		Max:      max,
		LogScale: logScale,
		Type:     paramType,
	}
}

// AddCategoricalParameter adds a parameter with discrete options
func (tm *TuningManager) AddCategoricalParameter(name string, options []float64) {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	tm.paramSpace[name] = ParamRange{
		Min:     0,
		Max:     float64(len(options) - 1),
		Type:    "categorical",
		Options: options,
	}
}

// SetEvaluationBudget sets the total number of evaluations to perform
func (tm *TuningManager) SetEvaluationBudget(budget, evaluationsPerExp int) {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	if budget > 0 {
		tm.evaluationBudget = budget
	}

	if evaluationsPerExp > 0 {
		tm.evaluationsPerExp = evaluationsPerExp
	}
}

// GetDefaultParamRanges returns typical ranges for common parameters
func (tm *TuningManager) GetDefaultParamRanges(algorithmType string) map[string]ParamRange {
	// Create default parameter ranges based on algorithm type
	ranges := make(map[string]ParamRange)

	switch algorithmType {
	case "q_learning", "sarsa", "function_q_learning":
		// Common parameters for Q-learning algorithms
		ranges["alpha"] = ParamRange{
			Min: 0.01, Max: 0.5, LogScale: true, Type: "float",
		}
		ranges["gamma"] = ParamRange{
			Min: 0.8, Max: 0.999, LogScale: false, Type: "float",
		}
		ranges["epsilon"] = ParamRange{
			Min: 0.01, Max: 0.3, LogScale: true, Type: "float",
		}

	case "hybrid":
		// Parameters specific to hybrid algorithm
		ranges["transition_threshold"] = ParamRange{
			Min: 100, Max: 2000, LogScale: true, Type: "int",
		}
		ranges["confidence_threshold"] = ParamRange{
			Min: 0.3, Max: 0.9, LogScale: false, Type: "float",
		}
		ranges["performance_weight"] = ParamRange{
			Min: 0.2, Max: 0.8, LogScale: false, Type: "float",
		}
		ranges["reliability_weight"] = ParamRange{
			Min: 0.1, Max: 0.5, LogScale: false, Type: "float",
		}
	}

	// Add function approximation specific parameters if needed
	if algorithmType == "function_q_learning" {
		ranges["approx_learning_rate"] = ParamRange{
			Min: 0.001, Max: 0.1, LogScale: true, Type: "float",
		}
		ranges["approx_l2_regularization"] = ParamRange{
			Min: 0.0001, Max: 0.01, LogScale: true, Type: "float",
		}
	}

	return ranges
}

// StartTuning begins the hyperparameter tuning process
func (tm *TuningManager) StartTuning() error {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	if tm.isRunning {
		return errors.New("tuning is already running")
	}

	if tm.strategy == nil {
		return errors.New("no tuning strategy configured")
	}

	if tm.algorithmName == "" {
		return errors.New("no algorithm selected for tuning")
	}

	if len(tm.paramSpace) == 0 {
		return errors.New("no parameters configured for tuning")
	}

	// Generate initial experiments
	experimentCount := tm.evaluationBudget / tm.evaluationsPerExp
	exps := tm.strategy.GenerateExperiments(tm.paramSpace, experimentCount)

	// Initialize experiment tracking
	tm.experiments = make(map[string]*Experiment)
	tm.completedExps = make([]*Experiment, 0)
	tm.pendingExps = make([]*Experiment, 0, len(exps))
	tm.runningExps = make(map[string]*Experiment)

	for i := range exps {
		exps[i].AlgorithmName = tm.algorithmName
		tm.experiments[exps[i].ID] = &exps[i]
		tm.pendingExps = append(tm.pendingExps, &exps[i])
	}

	tm.isRunning = true
	tm.startTime = time.Now()
	tm.lastUpdateTime = time.Now()

	// Start asynchronous tuning process
	go tm.tuningLoop()

	tm.logger.Info(fmt.Sprintf("Started tuning for algorithm '%s' with %d experiments",
		tm.algorithmName, len(exps)))

	return nil
}

// StopTuning halts the tuning process
func (tm *TuningManager) StopTuning() {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	tm.isRunning = false
	tm.logger.Info("Tuning process stopped")
}

// GetTuningStatus returns the current status of the tuning process
func (tm *TuningManager) GetTuningStatus() TuningStatus {
	tm.mutex.RLock()
	defer tm.mutex.RUnlock()

	status := TuningStatus{
		IsRunning:      tm.isRunning,
		Pending:        len(tm.pendingExps),
		InProgress:     len(tm.runningExps),
		Completed:      len(tm.completedExps),
		AlgorithmName:  tm.algorithmName,
		StartTime:      tm.startTime,
		LastUpdateTime: tm.lastUpdateTime,
	}

	if tm.strategy != nil {
		status.Strategy = tm.strategy.Name()
		status.BestParams, status.BestScore = tm.strategy.GetBestParameters()
	}

	return status
}

// GetBestParameters returns the best parameters found so far
func (tm *TuningManager) GetBestParameters() (map[string]float64, float64, bool) {
	tm.mutex.RLock()
	defer tm.mutex.RUnlock()

	if tm.strategy == nil {
		return nil, 0, false
	}

	params, score := tm.strategy.GetBestParameters()
	return params, score, len(tm.completedExps) > 0
}

// ApplyBestParameters applies the best found parameters to the algorithm
func (tm *TuningManager) ApplyBestParameters() error {
	tm.mutex.RLock()
	defer tm.mutex.RUnlock()

	if tm.strategy == nil {
		return errors.New("no tuning strategy configured")
	}

	if len(tm.completedExps) == 0 {
		return errors.New("no completed experiments")
	}

	params, _, _ := tm.GetBestParameters()

	return tm.agent.SetAlgorithmParameters(tm.algorithmName, params)
}

// tuningLoop manages the asynchronous tuning process
func (tm *TuningManager) tuningLoop() {
	for {
		tm.mutex.Lock()

		// Check if we should stop
		if !tm.isRunning {
			tm.mutex.Unlock()
			return
		}

		// Check if all experiments are done
		if len(tm.pendingExps) == 0 && len(tm.runningExps) == 0 {
			tm.isRunning = false
			tm.logger.Info("Tuning completed - all experiments finished")
			tm.mutex.Unlock()
			return
		}

		// Start a pending experiment if available
		if len(tm.pendingExps) > 0 {
			exp := tm.pendingExps[0]
			tm.pendingExps = tm.pendingExps[1:]

			exp.Status = "running"
			exp.StartTime = time.Now()
			tm.runningExps[exp.ID] = exp

			// Release mutex while evaluating
			tm.mutex.Unlock()

			// Evaluate experiment (this is computationally intensive)
			tm.evaluateExperiment(exp)

			// Re-acquire mutex to update state
			tm.mutex.Lock()

			// Mark as completed
			delete(tm.runningExps, exp.ID)
			exp.Status = "completed"
			exp.EndTime = time.Now()
			tm.completedExps = append(tm.completedExps, exp)
			tm.lastUpdateTime = time.Now()

			tm.logger.Info(fmt.Sprintf("Experiment %s completed with score: %.4f",
				exp.ID, exp.Results.Score))
		}

		// Check if we need to generate new experiments
		if len(tm.completedExps) > 0 && len(tm.pendingExps) == 0 && !tm.strategy.IsDone() {
			// Convert to slice for the strategy
			completed := make([]Experiment, len(tm.completedExps))
			for i, exp := range tm.completedExps {
				completed[i] = *exp
			}

			// Generate new experiments
			newExps := tm.strategy.ProcessResults(completed)

			// Add new experiments to pending queue
			for i := range newExps {
				newExps[i].AlgorithmName = tm.algorithmName
				tm.experiments[newExps[i].ID] = &newExps[i]
				tm.pendingExps = append(tm.pendingExps, &newExps[i])
			}

			if len(newExps) > 0 {
				tm.logger.Info(fmt.Sprintf("Generated %d new experiments", len(newExps)))
			}
		}

		tm.mutex.Unlock()

		// Short sleep to prevent CPU hogging
		time.Sleep(100 * time.Millisecond)
	}
}

// evaluateExperiment assesses the performance of a parameter configuration
func (tm *TuningManager) evaluateExperiment(exp *Experiment) {
	// Apply parameters to algorithm
	err := tm.agent.SetAlgorithmParameters(exp.AlgorithmName, exp.Params)
	if err != nil {
		tm.logger.Error(fmt.Sprintf("Failed to set parameters for experiment %s", exp.ID), err)
		exp.Status = "failed"
		exp.Error = err.Error()
		return
	}

	// Set this algorithm as active
	err = tm.agent.SetActiveAlgorithm(exp.AlgorithmName)
	if err != nil {
		tm.logger.Error(fmt.Sprintf("Failed to set active algorithm for experiment %s", exp.ID), err)
		exp.Status = "failed"
		exp.Error = err.Error()
		return
	}

	// In a real system, we would execute a series of tasks and measure performance
	// For simulation, we'll implement a simplified evaluation

	// This is where you'd typically have code to:
	// 1. Run the agent with these parameters
	// 2. Collect metrics on performance
	// 3. Calculate a score

	// For now, evaluate using recent performance metrics
	performanceSnapshot := tm.agent.GetPerformanceMetrics()
	originalAlgMetrics := performanceSnapshot.AlgorithmMetrics[exp.AlgorithmName]

	// Wait for a short time to let new tasks be processed with these parameters
	// In production, you'd want a better way to evaluate parameter quality
	time.Sleep(1 * time.Second)

	// Get updated metrics
	updatedMetrics := tm.agent.GetPerformanceMetrics()
	updatedAlgMetrics := updatedMetrics.AlgorithmMetrics[exp.AlgorithmName]

	// Calculate delta in metrics
	rewardDelta := updatedAlgMetrics.AvgReward - originalAlgMetrics.AvgReward
	successRateDelta := updatedAlgMetrics.SuccessRate - originalAlgMetrics.SuccessRate

	// Calculate score - this is a simplified evaluation and should be replaced
	// with more robust evaluation in production
	score := rewardDelta*0.7 + successRateDelta*0.3

	// Record results
	exp.Results = ExperimentResults{
		RewardMean:     updatedAlgMetrics.AvgReward,
		SuccessRate:    updatedAlgMetrics.SuccessRate,
		EpisodeCount:   int(updatedAlgMetrics.Decisions - originalAlgMetrics.Decisions),
		Score:          score,
		CompletionTime: float64(time.Since(exp.StartTime).Milliseconds()),
	}

	// Ensure non-negative score for the optimizer (will substitute expected reward)
	if exp.Results.EpisodeCount == 0 {
		exp.Results.Score = 0
	}
}
