package rl

import (
	"fmt"
)

// RLAlgorithm defines the interface that all reinforcement learning algorithms must implement
type RLAlgorithm interface {
	// Name returns the identifier for this algorithm
	Name() string

	// SelectAction chooses an action based on the current state
	// availableActions are the possible node IDs that can be selected
	SelectAction(state SystemState, availableActions []string) (string, error)

	// Learn updates the algorithm's knowledge based on observed outcomes
	Learn(prevState SystemState, action string, newState SystemState, reward float64)

	// SaveModel persists the algorithm's learned knowledge to storage
	SaveModel(path string) error

	// LoadModel restores the algorithm's knowledge from storage
	LoadModel(path string) error

	// Reset clears the algorithm's learned knowledge
	Reset()

	// GetParams returns the algorithm's current parameters
	GetParams() map[string]interface{}

	// SetParams updates the algorithm's parameters
	SetParams(params map[string]interface{}) error
}

// StateRepresentation defines how states are encoded for learning algorithms
type StateRepresentation interface {
	// EncodeState converts a system state to a representation the algorithm can use
	EncodeState(state SystemState) string

	// GetStateFeatures extracts key features from a system state for algorithms that need them
	GetStateFeatures(state SystemState) map[string]float64
}

// BasicStateEncoder provides a simple implementation of state encoding
type BasicStateEncoder struct {
	discretizationLevels int
}

// NewBasicStateEncoder creates a new state encoder with the specified discretization
func NewBasicStateEncoder(levels int) *BasicStateEncoder {
	if levels < 2 {
		levels = 5 // Default discretization
	}
	return &BasicStateEncoder{
		discretizationLevels: levels,
	}
}

// EncodeState creates a discrete representation of the system state
func (e *BasicStateEncoder) EncodeState(state SystemState) string {
	// Implementation details similar to what we had before
	var totalCPU, totalMem float64
	var avgCPU, avgMem float64
	var maxCPU, maxMem float64

	// Calculate aggregate statistics
	nodeCount := len(state.FogNodes)
	if nodeCount == 0 {
		return "empty_state"
	}

	for _, node := range state.FogNodes {
		totalCPU += node.CPUUtilization
		totalMem += node.MemoryUtilization

		if node.CPUUtilization > maxCPU {
			maxCPU = node.CPUUtilization
		}
		if node.MemoryUtilization > maxMem {
			maxMem = node.MemoryUtilization
		}
	}

	avgCPU = totalCPU / float64(nodeCount)
	avgMem = totalMem / float64(nodeCount)

	// Discretize values into buckets to reduce state space
	avgCPULevel := e.discretize(avgCPU, 0, 1)
	avgMemLevel := e.discretize(avgMem, 0, 1)
	maxCPULevel := e.discretize(maxCPU, 0, 1)
	maxMemLevel := e.discretize(maxMem, 0, 1)
	nodeBusyLevel := e.discretizeInt(nodeCount, 1, 100)

	// Combine discretized features into a state identifier
	return fmt.Sprintf("c%sm%sC%sM%sn%s",
		avgCPULevel, avgMemLevel, maxCPULevel, maxMemLevel, nodeBusyLevel)
}

// GetStateFeatures extracts key features from the system state
func (e *BasicStateEncoder) GetStateFeatures(state SystemState) map[string]float64 {
	features := make(map[string]float64)

	// Extract key system metrics
	var totalCPU, totalMem, totalTasks float64
	nodeCount := len(state.FogNodes)

	if nodeCount == 0 {
		return features
	}

	for _, node := range state.FogNodes {
		totalCPU += node.CPUUtilization
		totalMem += node.MemoryUtilization
		totalTasks += float64(node.TaskCount)
	}

	features["avg_cpu"] = totalCPU / float64(nodeCount)
	features["avg_mem"] = totalMem / float64(nodeCount)
	features["avg_tasks"] = totalTasks / float64(nodeCount)
	features["node_count"] = float64(nodeCount)

	// Add more sophisticated features here as needed

	return features
}

// discretize converts a continuous value to a discrete bucket identifier
func (e *BasicStateEncoder) discretize(value, min, max float64) string {
	bucketSize := (max - min) / float64(e.discretizationLevels)
	bucketIndex := int((value - min) / bucketSize)

	// Handle edge cases
	if bucketIndex >= e.discretizationLevels {
		bucketIndex = e.discretizationLevels - 1
	}
	if bucketIndex < 0 {
		bucketIndex = 0
	}

	return string('a' + rune(bucketIndex))
}

// discretizeInt converts an integer value to a discrete bucket identifier
func (e *BasicStateEncoder) discretizeInt(value, min, max int) string {
	range_ := max - min
	bucketSize := float64(range_) / float64(e.discretizationLevels)
	bucketIndex := int((float64(value) - float64(min)) / bucketSize)

	// Handle edge cases
	if bucketIndex >= e.discretizationLevels {
		bucketIndex = e.discretizationLevels - 1
	}
	if bucketIndex < 0 {
		bucketIndex = 0
	}

	return string('a' + rune(bucketIndex))
}
