package rl

import (
	"encoding/json"
	"errors"
	"math"
	"math/rand"
	"os"
	"sync"
)

// QLearningAlgorithm implements the Q-Learning reinforcement learning algorithm
type QLearningAlgorithm struct {
	// Q-table: maps state ID -> action -> value
	qTable       map[string]map[string]float64
	epsilon      float64 // Exploration rate
	alpha        float64 // Learning rate
	gamma        float64 // Discount factor
	stateEncoder StateRepresentation
	mutex        sync.RWMutex
}

// NewQLearningAlgorithm creates a new Q-learning algorithm instance
func NewQLearningAlgorithm(stateEncoder StateRepresentation) *QLearningAlgorithm {
	return &QLearningAlgorithm{
		qTable:       make(map[string]map[string]float64),
		epsilon:      0.1, // 10% exploration rate
		alpha:        0.1, // Learning rate
		gamma:        0.9, // Discount factor - value future rewards highly
		stateEncoder: stateEncoder,
	}
}

// Name returns the identifier for this algorithm
func (q *QLearningAlgorithm) Name() string {
	return "q_learning"
}

// SelectAction chooses an action based on epsilon-greedy policy
func (q *QLearningAlgorithm) SelectAction(state SystemState, availableActions []string) (string, error) {
	if len(availableActions) == 0 {
		return "", errors.New("no available actions")
	}

	// With probability epsilon, choose a random action (explore)
	if rand.Float64() < q.epsilon {
		return availableActions[rand.Intn(len(availableActions))], nil
	}

	// Otherwise, choose the best action (exploit)
	stateID := q.stateEncoder.EncodeState(state)

	q.mutex.RLock()
	defer q.mutex.RUnlock()

	// If we've never seen this state before, use random action
	actionValues, exists := q.qTable[stateID]
	if !exists {
		return availableActions[rand.Intn(len(availableActions))], nil
	}

	// Find the action with the highest Q-value
	var bestAction string
	bestValue := math.Inf(-1)

	for _, action := range availableActions {
		if value, exists := actionValues[action]; exists && value > bestValue {
			bestValue = value
			bestAction = action
		}
	}

	// If no best action found, use random action
	if bestAction == "" {
		return availableActions[rand.Intn(len(availableActions))], nil
	}

	return bestAction, nil
}

// Learn updates the Q-table based on observed reward
func (q *QLearningAlgorithm) Learn(prevState SystemState, action string, newState SystemState, reward float64) {
	prevStateID := q.stateEncoder.EncodeState(prevState)
	newStateID := q.stateEncoder.EncodeState(newState)

	q.mutex.Lock()
	defer q.mutex.Unlock()

	// Initialize state in Q-table if needed
	if _, exists := q.qTable[prevStateID]; !exists {
		q.qTable[prevStateID] = make(map[string]float64)
	}

	// Initialize next state in Q-table if needed
	if _, exists := q.qTable[newStateID]; !exists {
		q.qTable[newStateID] = make(map[string]float64)
	}

	// Initialize action Q-value if needed
	if _, exists := q.qTable[prevStateID][action]; !exists {
		q.qTable[prevStateID][action] = 0.0
	}

	// Find maximum Q-value for next state
	maxNextQ := 0.0
	for _, qValue := range q.qTable[newStateID] {
		if qValue > maxNextQ {
			maxNextQ = qValue
		}
	}

	// Q-Learning update formula
	oldQ := q.qTable[prevStateID][action]
	newQ := oldQ + q.alpha*(reward+q.gamma*maxNextQ-oldQ)
	q.qTable[prevStateID][action] = newQ
}

// SaveModel persists the Q-table to a file
func (q *QLearningAlgorithm) SaveModel(path string) error {
	q.mutex.RLock()
	defer q.mutex.RUnlock()

	data, err := json.MarshalIndent(q.qTable, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(path, data, 0644)
}

// LoadModel loads the Q-table from a file
func (q *QLearningAlgorithm) LoadModel(path string) error {
	q.mutex.Lock()
	defer q.mutex.Unlock()

	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}

	return json.Unmarshal(data, &q.qTable)
}

// Reset clears the Q-table
func (q *QLearningAlgorithm) Reset() {
	q.mutex.Lock()
	defer q.mutex.Unlock()

	q.qTable = make(map[string]map[string]float64)
}

// GetParams returns the algorithm's parameters
func (q *QLearningAlgorithm) GetParams() map[string]interface{} {
	q.mutex.RLock()
	defer q.mutex.RUnlock()

	return map[string]interface{}{
		"epsilon": q.epsilon,
		"alpha":   q.alpha,
		"gamma":   q.gamma,
	}
}

// SetParams updates the algorithm's parameters
func (q *QLearningAlgorithm) SetParams(params map[string]interface{}) error {
	q.mutex.Lock()
	defer q.mutex.Unlock()

	if epsilon, ok := params["epsilon"].(float64); ok {
		if epsilon < 0 || epsilon > 1 {
			return errors.New("epsilon must be between 0 and 1")
		}
		q.epsilon = epsilon
	}

	if alpha, ok := params["alpha"].(float64); ok {
		if alpha < 0 || alpha > 1 {
			return errors.New("alpha must be between 0 and 1")
		}
		q.alpha = alpha
	}

	if gamma, ok := params["gamma"].(float64); ok {
		if gamma < 0 || gamma > 1 {
			return errors.New("gamma must be between 0 and 1")
		}
		q.gamma = gamma
	}

	return nil
}
