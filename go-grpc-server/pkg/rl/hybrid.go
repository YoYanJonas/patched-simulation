package rl

import (
	"encoding/json"
	"errors"
	"io/ioutil"
	"math"
	"math/rand"
	"strings"
	"sync"
)

// HybridAlgorithm combines reinforcement learning with heuristic approaches
// for more robust decision making and smoother learning transition
type HybridAlgorithm struct {
	// The core RL algorithm (Q-learning, SARSA, etc.)
	rlAlgorithm            RLAlgorithm
	stateEncoder           StateRepresentation
	enhancedEncoder        *EnhancedStateEncoder
	transitionCounter      int     // Counts experiences to control RL transition
	transitionThreshold    int     // Number of experiences before full RL usage
	lastDecisionConfidence float64 // Confidence level of last decision (0-1)
	confidenceThreshold    float64 // Minimum confidence to use RL over heuristics

	// Multi-objective weights
	performanceWeight float64 // Weight for performance optimization
	reliabilityWeight float64 // Weight for reliability optimization
	loadBalanceWeight float64 // Weight for load balancing optimization

	// Historical decision quality tracking
	decisionHistory []float64 // Tracks recent decision quality for confidence
	historyMaxSize  int       // Maximum size of decision history

	// Task type history for learning patterns
	taskTypeHistory    []TaskType // Recent task types for pattern detection
	taskHistoryMaxSize int        // Maximum size of task history

	// Safety mechanism
	safetyFallbackEnabled bool // Enable safety fallback to heuristics

	mutex sync.RWMutex
}

// HybridOption configures the HybridAlgorithm
type HybridOption func(*HybridAlgorithm)

// WithRLAlgorithm sets the core RL algorithm
func WithRLAlgorithm(algorithm RLAlgorithm) HybridOption {
	return func(h *HybridAlgorithm) {
		h.rlAlgorithm = algorithm
	}
}

// WithTransitionThreshold sets how many experiences before full RL usage
func WithTransitionThreshold(threshold int) HybridOption {
	return func(h *HybridAlgorithm) {
		if threshold > 0 {
			h.transitionThreshold = threshold
		}
	}
}

// WithConfidenceThreshold sets the minimum confidence to use RL decisions
func WithConfidenceThreshold(threshold float64) HybridOption {
	return func(h *HybridAlgorithm) {
		if threshold >= 0 && threshold <= 1 {
			h.confidenceThreshold = threshold
		}
	}
}

// WithObjectiveWeights sets the weights for the multi-objective optimization
func WithObjectiveWeights(performance, reliability, loadBalance float64) HybridOption {
	return func(h *HybridAlgorithm) {
		total := performance + reliability + loadBalance
		if total > 0 {
			// Normalize weights to sum to 1.0
			h.performanceWeight = performance / total
			h.reliabilityWeight = reliability / total
			h.loadBalanceWeight = loadBalance / total
		}
	}
}

// WithSafetyFallback enables/disables the safety fallback mechanism
func WithSafetyFallback(enabled bool) HybridOption {
	return func(h *HybridAlgorithm) {
		h.safetyFallbackEnabled = enabled
	}
}

// NewHybridAlgorithm creates a new hybrid algorithm instance
func NewHybridAlgorithm(encoder StateRepresentation, options ...HybridOption) *HybridAlgorithm {
	// Create enhanced encoder if provided encoder is basic
	var enhancedEncoder *EnhancedStateEncoder
	if basicEncoder, ok := encoder.(*BasicStateEncoder); ok {
		// Create enhanced encoder with similar discretization
		enhancedEncoder = NewEnhancedStateEncoder(
			WithDiscretizationLevels(basicEncoder.discretizationLevels),
		)
	} else if enhancedEncoder, ok = encoder.(*EnhancedStateEncoder); !ok {
		// If not already an EnhancedStateEncoder, create a default one
		enhancedEncoder = NewEnhancedStateEncoder()
	}

	// Default to Q-learning if no algorithm is provided
	defaultRL := NewQLearningAlgorithm(encoder)

	hybrid := &HybridAlgorithm{
		rlAlgorithm:            defaultRL,
		stateEncoder:           encoder,
		enhancedEncoder:        enhancedEncoder,
		transitionCounter:      0,
		transitionThreshold:    1000, // Default: require 1000 experiences for full RL
		lastDecisionConfidence: 0.5,  // Start with neutral confidence
		confidenceThreshold:    0.6,  // Default: 60% confidence to use RL
		performanceWeight:      0.5,  // Default weights for multi-objective optimization
		reliabilityWeight:      0.3,
		loadBalanceWeight:      0.2,
		decisionHistory:        make([]float64, 0, 100),
		historyMaxSize:         100, // Track last 100 decisions for confidence
		taskTypeHistory:        make([]TaskType, 0, 20),
		taskHistoryMaxSize:     20,
		safetyFallbackEnabled:  true, // Safety fallback enabled by default
	}

	// Apply all options
	for _, option := range options {
		option(hybrid)
	}

	return hybrid
}

// Name returns the identifier for this algorithm
func (h *HybridAlgorithm) Name() string {
	return "hybrid"
}

// SelectAction chooses an action based on both RL and heuristics
func (h *HybridAlgorithm) SelectAction(state SystemState, availableActions []string) (string, error) {
	if len(availableActions) == 0 {
		return "", errors.New("no available actions")
	}

	// If only one option, no decision needed
	if len(availableActions) == 1 {
		return availableActions[0], nil
	}

	h.mutex.Lock()
	defer h.mutex.Unlock()

	// Calculate RL vs heuristic blend factor based on transition progress
	rlBlendFactor := math.Min(1.0, float64(h.transitionCounter)/float64(h.transitionThreshold))

	// Get decision from RL algorithm
	rlDecision, err := h.rlAlgorithm.SelectAction(state, availableActions)
	if err != nil {
		// If RL fails, fall back to pure heuristic
		rlBlendFactor = 0
	}

	// Extract task type from task history, default to balanced if unknown
	taskType := TaskTypeBalanced
	if len(h.taskTypeHistory) > 0 {
		// Use most recent task type
		taskType = h.taskTypeHistory[len(h.taskTypeHistory)-1]
	}

	// If we have extended state, use it for better heuristics
	var heuristicScores map[string]float64

	// Check if state is an ExtendedSystemState by direct access to fields via reflection
	// Instead of type assertion, we'll use different functions for different state types
	if h.isExtendedState(state) {
		// Convert to ExtendedSystemState explicitly
		extState := h.convertToExtendedState(state)
		heuristicScores = h.getExtendedHeuristicScores(extState, availableActions, taskType)
	} else {
		heuristicScores = h.getHeuristicScores(state, availableActions, taskType)
	}

	// Select action based on blend of RL and heuristic
	selectedAction := ""

	// Safety mechanism: check if RL decision confidence is too low
	if rlBlendFactor < h.confidenceThreshold || h.lastDecisionConfidence < h.confidenceThreshold {
		// Use purely heuristic approach
		selectedAction = h.selectBestHeuristicAction(heuristicScores)
		h.lastDecisionConfidence = 0.8 // Heuristics generally have good confidence
	} else {
		// Use RL with probability based on blend factor, otherwise use heuristic
		if rand.Float64() < rlBlendFactor {
			selectedAction = rlDecision

			// Calculate confidence based on how much the RL algorithm and heuristic agree
			if heuristicScore, exists := heuristicScores[rlDecision]; exists {
				// Check how close the RL decision is to the best heuristic option
				bestHeuristicScore := h.getMaxScore(heuristicScores)
				if bestHeuristicScore > 0 {
					// Higher confidence if RL agrees with heuristic
					h.lastDecisionConfidence = heuristicScore / bestHeuristicScore
				} else {
					h.lastDecisionConfidence = 0.5 // Neutral confidence
				}
			} else {
				// RL chose something the heuristic didn't even score
				h.lastDecisionConfidence = 0.3 // Low confidence
			}
		} else {
			// Use heuristic
			selectedAction = h.selectBestHeuristicAction(heuristicScores)
			h.lastDecisionConfidence = 0.8 // Heuristics generally have good confidence
		}
	}

	// Update confidence history
	h.updateDecisionHistory(h.lastDecisionConfidence)

	return selectedAction, nil
}

// isExtendedState checks if a state has extended features
// This avoids type assertions by directly checking for the existence of extended state fields
func (h *HybridAlgorithm) isExtendedState(state SystemState) bool {
	// Simpler check: extended state should implement additional methods
	extState := ToExtendedState(state)
	return extState != nil
}

// convertToExtendedState safely converts a regular state to an extended state
func (h *HybridAlgorithm) convertToExtendedState(state SystemState) *ExtendedSystemState {
	return ToExtendedState(state)
}

// getHeuristicScores calculates scores for each available action using heuristics
func (h *HybridAlgorithm) getHeuristicScores(state SystemState, availableActions []string, taskType TaskType) map[string]float64 {
	scores := make(map[string]float64, len(availableActions))

	for _, nodeID := range availableActions {
		// Get node state
		node, exists := state.FogNodes[nodeID]
		if !exists {
			continue
		}

		// Calculate performance score (higher for nodes with more available resources)
		cpuAvailable := 1.0 - node.CPUUtilization
		memAvailable := 1.0 - node.MemoryUtilization

		var performanceScore float64

		// Adjust based on task type
		switch taskType {
		case TaskTypeCPUIntensive:
			performanceScore = cpuAvailable*0.8 + memAvailable*0.2
		case TaskTypeMemoryIntensive:
			performanceScore = cpuAvailable*0.2 + memAvailable*0.8
		case TaskTypeIOIntensive:
			networkScore := node.NetworkBandwidth / 100.0 // Normalize to 0-1 range assuming 100Mbps max
			performanceScore = (cpuAvailable + memAvailable + networkScore) / 3.0
		default: // Balanced
			performanceScore = (cpuAvailable + memAvailable) / 2.0
		}

		// We don't have reliability data in basic state, so assume neutral
		reliabilityScore := 0.5

		// Calculate load balancing score
		// Lower task count is better for balancing
		maxTasks := 20.0 // Assume 20 is the maximum reasonable task count
		loadBalanceScore := 1.0 - (float64(node.TaskCount) / maxTasks)
		loadBalanceScore = math.Max(0.0, loadBalanceScore)

		// Calculate weighted score
		finalScore := performanceScore*h.performanceWeight +
			reliabilityScore*h.reliabilityWeight +
			loadBalanceScore*h.loadBalanceWeight

		scores[nodeID] = finalScore
	}

	return scores
}

// getExtendedHeuristicScores calculates scores for each available action using extended state data
func (h *HybridAlgorithm) getExtendedHeuristicScores(state *ExtendedSystemState, availableActions []string, taskType TaskType) map[string]float64 {
	scores := make(map[string]float64, len(availableActions))

	for _, nodeID := range availableActions {
		// Use enhanced encoder to get node affinity
		nodeAffinity := h.enhancedEncoder.GetExtendedNodeAffinity(state, nodeID, taskType)

		// Get node state
		node, exists := state.FogNodes[nodeID]
		if !exists {
			continue
		}

		// Performance is based on node affinity (which includes resource availability)
		performanceScore := nodeAffinity

		// Reliability score from node history if available
		reliabilityScore := 0.5 // Default neutral
		if nodeHistory, exists := state.NodeHistory[nodeID]; exists {
			reliabilityScore = nodeHistory.ReliabilityScore
		}

		// Calculate load balancing score
		// Consider global balance in addition to local task count
		taskCountBalance := 1.0 - (float64(node.TaskCount) / 20.0) // Normalize task count
		taskCountBalance = math.Max(0.0, taskCountBalance)

		// Use CPU and memory balance scores if available
		cpuBalance := state.CPUBalanceScore
		memBalance := state.MemoryBalanceScore

		loadBalanceScore := (taskCountBalance + cpuBalance + memBalance) / 3.0

		// If this node is trending toward higher utilization, reduce its score
		if nodeHistory, exists := state.NodeHistory[nodeID]; exists {
			if len(nodeHistory.RecentCPUUtilization) >= 2 {
				last := len(nodeHistory.RecentCPUUtilization) - 1
				cpuTrend := nodeHistory.RecentCPUUtilization[last] - nodeHistory.RecentCPUUtilization[0]

				// If trending up (getting busier), reduce score
				if cpuTrend > 0 {
					performanceScore *= (1.0 - cpuTrend*0.5)
				}
			}
		}

		// Calculate weighted score
		finalScore := performanceScore*h.performanceWeight +
			reliabilityScore*h.reliabilityWeight +
			loadBalanceScore*h.loadBalanceWeight

		scores[nodeID] = finalScore
	}

	return scores
}

// selectBestHeuristicAction selects the action with the highest score
func (h *HybridAlgorithm) selectBestHeuristicAction(scores map[string]float64) string {
	var bestAction string
	var bestScore float64 = -1

	for action, score := range scores {
		if score > bestScore {
			bestScore = score
			bestAction = action
		}
	}

	return bestAction
}

// getMaxScore returns the highest score in the map
func (h *HybridAlgorithm) getMaxScore(scores map[string]float64) float64 {
	var max float64 = -1
	for _, score := range scores {
		if score > max {
			max = score
		}
	}
	return max
}

// updateDecisionHistory tracks confidence of recent decisions
func (h *HybridAlgorithm) updateDecisionHistory(confidence float64) {
	h.decisionHistory = append(h.decisionHistory, confidence)
	if len(h.decisionHistory) > h.historyMaxSize {
		h.decisionHistory = h.decisionHistory[1:]
	}
}

// Learn updates the algorithm's knowledge based on observed outcomes
func (h *HybridAlgorithm) Learn(prevState SystemState, action string, newState SystemState, reward float64) {
	h.mutex.Lock()
	defer h.mutex.Unlock()

	// Always update the core RL algorithm
	h.rlAlgorithm.Learn(prevState, action, newState, reward)

	// Increment transition counter to gradually shift toward RL
	h.transitionCounter++

	// Extract task type if it's in the new state (ExtendedSystemState with fresh task)
	// This is a simplified approach - in a real system, you'd pass the task type directly
	if h.isExtendedState(newState) {
		extState := h.convertToExtendedState(newState)

		// For demo purposes - would actually need to get this from the task
		// This is just a placeholder showing how you might track task types
		var taskType TaskType = TaskTypeBalanced

		// Find which node has increased task count as a simple heuristic
		node, exists := extState.FogNodes[action]
		prevNode, prevExists := prevState.FogNodes[action]

		if exists && prevExists && node.TaskCount > prevNode.TaskCount {
			// Guess task type based on resource changes
			cpuDiff := node.CPUUtilization - prevNode.CPUUtilization
			memDiff := node.MemoryUtilization - prevNode.MemoryUtilization

			if cpuDiff > 2*memDiff {
				taskType = TaskTypeCPUIntensive
			} else if memDiff > 2*cpuDiff {
				taskType = TaskTypeMemoryIntensive
			} else if node.NetworkBandwidth < prevNode.NetworkBandwidth {
				taskType = TaskTypeIOIntensive
			}
		}

		// Update task type history
		h.updateTaskTypeHistory(taskType)
	}
}

// updateTaskTypeHistory keeps track of recent task types
func (h *HybridAlgorithm) updateTaskTypeHistory(taskType TaskType) {
	h.taskTypeHistory = append(h.taskTypeHistory, taskType)
	if len(h.taskTypeHistory) > h.taskHistoryMaxSize {
		h.taskTypeHistory = h.taskTypeHistory[1:]
	}
}

// SaveModel persists the algorithm's knowledge
func (h *HybridAlgorithm) SaveModel(path string) error {
	// First let the core RL algorithm save its model
	if err := h.rlAlgorithm.SaveModel(path + ".rl"); err != nil {
		return err
	}

	// Save hybrid-specific parameters
	h.mutex.RLock()
	defer h.mutex.RUnlock()

	params := map[string]interface{}{
		"transitionCounter":      h.transitionCounter,
		"transitionThreshold":    h.transitionThreshold,
		"confidenceThreshold":    h.confidenceThreshold,
		"performanceWeight":      h.performanceWeight,
		"reliabilityWeight":      h.reliabilityWeight,
		"loadBalanceWeight":      h.loadBalanceWeight,
		"lastDecisionConfidence": h.lastDecisionConfidence,
		"decisionHistory":        h.decisionHistory,
		"taskTypeHistory":        h.taskTypeHistory,
	}

	data, err := json.MarshalIndent(params, "", "  ")
	if err != nil {
		return err
	}

	return ioutil.WriteFile(path+".hybrid", data, 0644)
}

// LoadModel restores the algorithm's knowledge
func (h *HybridAlgorithm) LoadModel(path string) error {
	// First let the core RL algorithm load its model
	if err := h.rlAlgorithm.LoadModel(path + ".rl"); err != nil {
		return err
	}

	// Load hybrid-specific parameters
	data, err := ioutil.ReadFile(path + ".hybrid")
	if err != nil {
		return err
	}

	h.mutex.Lock()
	defer h.mutex.Unlock()

	var params map[string]interface{}
	if err := json.Unmarshal(data, &params); err != nil {
		return err
	}

	// Restore parameters
	if val, ok := params["transitionCounter"].(float64); ok {
		h.transitionCounter = int(val)
	}
	if val, ok := params["transitionThreshold"].(float64); ok {
		h.transitionThreshold = int(val)
	}
	if val, ok := params["confidenceThreshold"].(float64); ok {
		h.confidenceThreshold = val
	}
	if val, ok := params["performanceWeight"].(float64); ok {
		h.performanceWeight = val
	}
	if val, ok := params["reliabilityWeight"].(float64); ok {
		h.reliabilityWeight = val
	}
	if val, ok := params["loadBalanceWeight"].(float64); ok {
		h.loadBalanceWeight = val
	}
	if val, ok := params["lastDecisionConfidence"].(float64); ok {
		h.lastDecisionConfidence = val
	}

	// Restore history arrays
	if histArr, ok := params["decisionHistory"].([]interface{}); ok {
		h.decisionHistory = make([]float64, 0, len(histArr))
		for _, v := range histArr {
			if val, ok := v.(float64); ok {
				h.decisionHistory = append(h.decisionHistory, val)
			}
		}
	}

	// Task type history needs special handling since it's an enum
	if taskArr, ok := params["taskTypeHistory"].([]interface{}); ok {
		h.taskTypeHistory = make([]TaskType, 0, len(taskArr))
		for _, v := range taskArr {
			if val, ok := v.(string); ok {
				h.taskTypeHistory = append(h.taskTypeHistory, TaskType(val))
			}
		}
	}

	return nil
}

// Reset clears the algorithm's learned knowledge
func (h *HybridAlgorithm) Reset() {
	h.mutex.Lock()
	defer h.mutex.Unlock()

	// Reset the core RL algorithm
	h.rlAlgorithm.Reset()

	// Reset hybrid-specific state
	h.transitionCounter = 0
	h.lastDecisionConfidence = 0.5
	h.decisionHistory = make([]float64, 0, h.historyMaxSize)
	h.taskTypeHistory = make([]TaskType, 0, h.taskHistoryMaxSize)
}

// GetParams returns the algorithm's current parameters
func (h *HybridAlgorithm) GetParams() map[string]interface{} {
	h.mutex.RLock()
	defer h.mutex.RUnlock()

	// Get core RL algorithm params
	rlParams := h.rlAlgorithm.GetParams()

	// Add hybrid-specific params
	hybridParams := map[string]interface{}{
		"rl_algorithm":         h.rlAlgorithm.Name(),
		"transition_progress":  float64(h.transitionCounter) / float64(h.transitionThreshold),
		"transition_threshold": h.transitionThreshold,
		"confidence_threshold": h.confidenceThreshold,
		"performance_weight":   h.performanceWeight,
		"reliability_weight":   h.reliabilityWeight,
		"load_balance_weight":  h.loadBalanceWeight,
		"last_confidence":      h.lastDecisionConfidence,
		"avg_confidence":       h.getAverageConfidence(),
		"safety_fallback":      h.safetyFallbackEnabled,
	}

	// Merge the maps
	for k, v := range rlParams {
		hybridParams["rl_"+k] = v
	}

	return hybridParams
}

// getAverageConfidence calculates the average decision confidence
func (h *HybridAlgorithm) getAverageConfidence() float64 {
	if len(h.decisionHistory) == 0 {
		return 0.5 // Neutral if no history
	}

	var sum float64
	for _, conf := range h.decisionHistory {
		sum += conf
	}

	return sum / float64(len(h.decisionHistory))
}

// GetAverageConfidence returns the average decision confidence as a public method
func (h *HybridAlgorithm) GetAverageConfidence() float64 {
	h.mutex.RLock()
	defer h.mutex.RUnlock()
	return h.getAverageConfidence()
}

// SetParams updates the algorithm's parameters
func (h *HybridAlgorithm) SetParams(params map[string]interface{}) error {
	h.mutex.Lock()
	defer h.mutex.Unlock()

	// Extract hybrid-specific parameters
	rlParams := make(map[string]interface{})

	for k, v := range params {
		if k == "transition_threshold" {
			if val, ok := v.(float64); ok && val > 0 {
				h.transitionThreshold = int(val)
			}
		} else if k == "confidence_threshold" {
			if val, ok := v.(float64); ok && val >= 0 && val <= 1 {
				h.confidenceThreshold = val
			}
		} else if k == "performance_weight" {
			if val, ok := v.(float64); ok && val >= 0 {
				h.performanceWeight = val
			}
		} else if k == "reliability_weight" {
			if val, ok := v.(float64); ok && val >= 0 {
				h.reliabilityWeight = val
			}
		} else if k == "load_balance_weight" {
			if val, ok := v.(float64); ok && val >= 0 {
				h.loadBalanceWeight = val
			}
		} else if k == "safety_fallback" {
			if val, ok := v.(bool); ok {
				h.safetyFallbackEnabled = val
			}
		} else if strings.HasPrefix(k, "rl_") {
			// Pass parameters to the underlying RL algorithm
			rlParams[strings.TrimPrefix(k, "rl_")] = v
		}
	}

	// Normalize weights
	totalWeight := h.performanceWeight + h.reliabilityWeight + h.loadBalanceWeight
	if totalWeight > 0 {
		h.performanceWeight /= totalWeight
		h.reliabilityWeight /= totalWeight
		h.loadBalanceWeight /= totalWeight
	} else {
		// Reset to defaults if all weights are 0
		h.performanceWeight = 0.5
		h.reliabilityWeight = 0.3
		h.loadBalanceWeight = 0.2
	}

	// Update the core RL algorithm if we have parameters for it
	if len(rlParams) > 0 {
		return h.rlAlgorithm.SetParams(rlParams)
	}

	return nil
}

// GetConfidence returns the current decision confidence level
func (h *HybridAlgorithm) GetConfidence() float64 {
	h.mutex.RLock()
	defer h.mutex.RUnlock()
	return h.lastDecisionConfidence
}

// GetTransitionProgress returns how far the algorithm has progressed
// from pure heuristic to full RL
func (h *HybridAlgorithm) GetTransitionProgress() float64 {
	h.mutex.RLock()
	defer h.mutex.RUnlock()
	return math.Min(1.0, float64(h.transitionCounter)/float64(h.transitionThreshold))
}

// GetHeuristicUsage returns the percentage of decisions made using heuristics
func (h *HybridAlgorithm) GetHeuristicUsage() float64 {
	h.mutex.RLock()
	defer h.mutex.RUnlock()

	// If confidence is below threshold, we use heuristics
	if h.lastDecisionConfidence < h.confidenceThreshold {
		return 1.0
	}

	// Otherwise, we blend based on transition progress
	transitionProgress := h.GetTransitionProgress()
	return 1.0 - transitionProgress
}
