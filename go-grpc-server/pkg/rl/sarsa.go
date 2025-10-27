package rl

import (
	"encoding/json"
	"errors"
	"io/ioutil"
	"math"
	"math/rand"
	"sync"
)

// SARSAAlgorithm implements the SARSA reinforcement learning algorithm
type SARSAAlgorithm struct {
	// Q-table: maps state ID -> action -> value
	qTable       map[string]map[string]float64
	epsilon      float64 // Exploration rate
	alpha        float64 // Learning rate
	gamma        float64 // Discount factor
	stateEncoder StateRepresentation
	lastState    string
	lastAction   string
	mutex        sync.RWMutex
}

// NewSARSAAlgorithm creates a new SARSA algorithm instance
func NewSARSAAlgorithm(stateEncoder StateRepresentation) *SARSAAlgorithm {
	return &SARSAAlgorithm{
		qTable:       make(map[string]map[string]float64),
		epsilon:      0.1, // 10% exploration rate
		alpha:        0.1, // Learning rate
		gamma:        0.9, // Discount factor
		stateEncoder: stateEncoder,
		lastState:    "",
		lastAction:   "",
	}
}

// Name returns the identifier for this algorithm
func (s *SARSAAlgorithm) Name() string {
	return "sarsa"
}

// SelectAction chooses an action based on epsilon-greedy policy
func (s *SARSAAlgorithm) SelectAction(state SystemState, availableActions []string) (string, error) {
	if len(availableActions) == 0 {
		return "", errors.New("no available actions")
	}

	stateID := s.stateEncoder.EncodeState(state)
	var selectedAction string

	// With probability epsilon, choose a random action (explore)
	if rand.Float64() < s.epsilon {
		selectedAction = availableActions[rand.Intn(len(availableActions))]
	} else {
		// Otherwise, choose the best action (exploit)
		s.mutex.RLock()

		// If we've never seen this state before, use random action
		actionValues, exists := s.qTable[stateID]
		if !exists {
			s.mutex.RUnlock()
			selectedAction = availableActions[rand.Intn(len(availableActions))]
		} else {
			// Find the action with the highest Q-value
			var bestAction string
			bestValue := math.Inf(-1)

			for _, action := range availableActions {
				if value, exists := actionValues[action]; exists && value > bestValue {
					bestValue = value
					bestAction = action
				}
			}

			s.mutex.RUnlock()

			// If no best action found, use random action
			if bestAction == "" {
				selectedAction = availableActions[rand.Intn(len(availableActions))]
			} else {
				selectedAction = bestAction
			}
		}
	}

	// Store this state-action pair for the next learning update
	s.mutex.Lock()
	s.lastState = stateID
	s.lastAction = selectedAction
	s.mutex.Unlock()

	return selectedAction, nil
}

// Learn updates the Q-table based on observed reward using SARSA
func (s *SARSAAlgorithm) Learn(prevState SystemState, action string, newState SystemState, reward float64) {
	prevStateID := s.stateEncoder.EncodeState(prevState)
	newStateID := s.stateEncoder.EncodeState(newState)

	s.mutex.Lock()
	defer s.mutex.Unlock()

	// Initialize states in Q-table if needed
	if _, exists := s.qTable[prevStateID]; !exists {
		s.qTable[prevStateID] = make(map[string]float64)
	}
	if _, exists := s.qTable[newStateID]; !exists {
		s.qTable[newStateID] = make(map[string]float64)
	}

	// Initialize action Q-value if needed
	if _, exists := s.qTable[prevStateID][action]; !exists {
		s.qTable[prevStateID][action] = 0.0
	}

	// Get next action's Q-value
	var nextQ float64 = 0
	if s.lastState == newStateID && s.lastAction != "" {
		if val, exists := s.qTable[newStateID][s.lastAction]; exists {
			nextQ = val
		}
	}

	// SARSA update formula (uses the actual next action)
	oldQ := s.qTable[prevStateID][action]
	newQ := oldQ + s.alpha*(reward+s.gamma*nextQ-oldQ)
	s.qTable[prevStateID][action] = newQ
}

// SaveModel persists the Q-table to a file
func (s *SARSAAlgorithm) SaveModel(path string) error {
	s.mutex.RLock()
	defer s.mutex.RUnlock()

	data, err := json.MarshalIndent(s.qTable, "", "  ")
	if err != nil {
		return err
	}

	return ioutil.WriteFile(path, data, 0644)
}

// LoadModel loads the Q-table from a file
func (s *SARSAAlgorithm) LoadModel(path string) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	data, err := ioutil.ReadFile(path)
	if err != nil {
		return err
	}

	return json.Unmarshal(data, &s.qTable)
}

// Reset clears the Q-table and last state-action
func (s *SARSAAlgorithm) Reset() {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	s.qTable = make(map[string]map[string]float64)
	s.lastState = ""
	s.lastAction = ""
}

// GetParams returns the algorithm's parameters
func (s *SARSAAlgorithm) GetParams() map[string]interface{} {
	s.mutex.RLock()
	defer s.mutex.RUnlock()

	return map[string]interface{}{
		"epsilon": s.epsilon,
		"alpha":   s.alpha,
		"gamma":   s.gamma,
	}
}

// SetParams updates the algorithm's parameters
func (s *SARSAAlgorithm) SetParams(params map[string]interface{}) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	if epsilon, ok := params["epsilon"].(float64); ok {
		if epsilon < 0 || epsilon > 1 {
			return errors.New("epsilon must be between 0 and 1")
		}
		s.epsilon = epsilon
	}

	if alpha, ok := params["alpha"].(float64); ok {
		if alpha < 0 || alpha > 1 {
			return errors.New("alpha must be between 0 and 1")
		}
		s.alpha = alpha
	}

	if gamma, ok := params["gamma"].(float64); ok {
		if gamma < 0 || gamma > 1 {
			return errors.New("gamma must be between 0 and 1")
		}
		s.gamma = gamma
	}

	return nil
}
