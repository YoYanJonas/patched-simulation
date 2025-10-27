package rl

import (
	"errors"
	"sync"
)

var (
	ErrAlgorithmNotFound          = errors.New("reinforcement learning algorithm not found")
	ErrAlgorithmAlreadyRegistered = errors.New("algorithm with this name already registered")
)

// AlgorithmManager manages multiple reinforcement learning algorithms
type AlgorithmManager struct {
	algorithms      map[string]RLAlgorithm
	activeAlgorithm string
	mutex           sync.RWMutex
}

// NewAlgorithmManager creates a new algorithm manager
func NewAlgorithmManager() *AlgorithmManager {
	return &AlgorithmManager{
		algorithms: make(map[string]RLAlgorithm),
	}
}

// RegisterAlgorithm adds a new algorithm to the manager
func (am *AlgorithmManager) RegisterAlgorithm(algorithm RLAlgorithm) error {
	am.mutex.Lock()
	defer am.mutex.Unlock()

	name := algorithm.Name()
	if _, exists := am.algorithms[name]; exists {
		return ErrAlgorithmAlreadyRegistered
	}

	am.algorithms[name] = algorithm

	// If this is the first algorithm, make it active
	if am.activeAlgorithm == "" {
		am.activeAlgorithm = name
	}

	return nil
}

// SetActiveAlgorithm changes the current active algorithm
func (am *AlgorithmManager) SetActiveAlgorithm(name string) error {
	am.mutex.Lock()
	defer am.mutex.Unlock()

	if _, exists := am.algorithms[name]; !exists {
		return ErrAlgorithmNotFound
	}

	am.activeAlgorithm = name
	return nil
}

// GetActiveAlgorithm returns the current active algorithm
func (am *AlgorithmManager) GetActiveAlgorithm() (RLAlgorithm, error) {
	am.mutex.RLock()
	defer am.mutex.RUnlock()

	if am.activeAlgorithm == "" {
		return nil, errors.New("no active algorithm set")
	}

	alg, exists := am.algorithms[am.activeAlgorithm]
	if !exists {
		return nil, ErrAlgorithmNotFound
	}

	return alg, nil
}

// GetAlgorithm returns a specific algorithm by name
func (am *AlgorithmManager) GetAlgorithm(name string) (RLAlgorithm, error) {
	am.mutex.RLock()
	defer am.mutex.RUnlock()

	alg, exists := am.algorithms[name]
	if !exists {
		return nil, ErrAlgorithmNotFound
	}

	return alg, nil
}

// GetRegisteredAlgorithms returns a list of all registered algorithm names
func (am *AlgorithmManager) GetRegisteredAlgorithms() []string {
	am.mutex.RLock()
	defer am.mutex.RUnlock()

	names := make([]string, 0, len(am.algorithms))
	for name := range am.algorithms {
		names = append(names, name)
	}

	return names
}

// SelectAction delegates the action selection to the active algorithm
func (am *AlgorithmManager) SelectAction(state SystemState, availableActions []string) (string, error) {
	algorithm, err := am.GetActiveAlgorithm()
	if err != nil {
		return "", err
	}

	return algorithm.SelectAction(state, availableActions)
}

// Learn delegates the learning to the active algorithm
func (am *AlgorithmManager) Learn(prevState SystemState, action string, newState SystemState, reward float64) error {
	algorithm, err := am.GetActiveAlgorithm()
	if err != nil {
		return err
	}

	algorithm.Learn(prevState, action, newState, reward)
	return nil
}
