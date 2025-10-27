package rl

import (
	"fmt"
	"math"
	"sort"
	"strings"
	"time"
)

// EnhancedStateEncoder provides a more sophisticated implementation of state encoding
// with feature normalization and advanced metrics
type EnhancedStateEncoder struct {
	discretizationLevels int
	useTimeFeatures      bool
	useHistorical        bool
	useReliability       bool
	useTrends            bool
	featureRanges        map[string][2]float64 // Store min/max for normalization
}

// NewEnhancedStateEncoder creates an enhanced state encoder
func NewEnhancedStateEncoder(options ...EncoderOption) *EnhancedStateEncoder {
	encoder := &EnhancedStateEncoder{
		discretizationLevels: 10, // Higher default discretization
		useTimeFeatures:      true,
		useHistorical:        true,
		useReliability:       true,
		useTrends:            true,
		featureRanges: map[string][2]float64{
			"cpu":         {0.0, 1.0},
			"memory":      {0.0, 1.0},
			"network":     {0.0, 100.0}, // Assuming 100 Mbps max
			"tasks":       {0.0, 100.0}, // Assuming max 100 tasks
			"balance":     {0.0, 1.0},
			"trend":       {-0.2, 0.2}, // Typical trend range
			"reliability": {0.0, 1.0},
		},
	}

	// Apply all options
	for _, option := range options {
		option(encoder)
	}

	return encoder
}

// EncoderOption configures the EnhancedStateEncoder
type EncoderOption func(*EnhancedStateEncoder)

// WithDiscretizationLevels sets the number of discretization levels
func WithDiscretizationLevels(levels int) EncoderOption {
	return func(e *EnhancedStateEncoder) {
		if levels >= 2 {
			e.discretizationLevels = levels
		}
	}
}

// WithTimeFeatures enables/disables time-based features
func WithTimeFeatures(enable bool) EncoderOption {
	return func(e *EnhancedStateEncoder) {
		e.useTimeFeatures = enable
	}
}

// WithHistoricalFeatures enables/disables historical performance features
func WithHistoricalFeatures(enable bool) EncoderOption {
	return func(e *EnhancedStateEncoder) {
		e.useHistorical = enable
	}
}

// WithReliabilityFeatures enables/disables node reliability features
func WithReliabilityFeatures(enable bool) EncoderOption {
	return func(e *EnhancedStateEncoder) {
		e.useReliability = enable
	}
}

// WithTrendFeatures enables/disables load trend features
func WithTrendFeatures(enable bool) EncoderOption {
	return func(e *EnhancedStateEncoder) {
		e.useTrends = enable
	}
}

// WithFeatureRange sets the min and max values for a specific feature
func WithFeatureRange(feature string, min, max float64) EncoderOption {
	return func(e *EnhancedStateEncoder) {
		e.featureRanges[feature] = [2]float64{min, max}
	}
}

// EncodeState creates a discrete representation of the basic system state
func (e *EnhancedStateEncoder) EncodeState(state SystemState) string {
	// Get feature vector (normalized continuous values)
	features := e.GetStateFeatures(state)

	// Convert features to a state identifier
	return e.featuresToStateID(features)
}

// EncodeExtendedState creates a rich discrete representation of the extended system state
func (e *EnhancedStateEncoder) EncodeExtendedState(state *ExtendedSystemState) string {
	// Get feature vector (normalized continuous values)
	features := e.GetExtendedStateFeatures(state)

	// Convert features to a state identifier
	return e.featuresToStateID(features)
}

// featuresToStateID converts a feature map to a state identifier string
func (e *EnhancedStateEncoder) featuresToStateID(features map[string]float64) string {
	parts := make([]string, 0, len(features))
	keys := make([]string, 0, len(features))

	// Sort keys for consistent encoding
	for k := range features {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	// Encode each feature
	for _, key := range keys {
		value := features[key]

		// Get the range for this feature
		featureRange, exists := e.featureRanges[key]
		if !exists {
			// Use default range
			featureRange = [2]float64{0.0, 1.0}
		}

		// Discretize and add to parts
		bucketID := e.discretize(value, featureRange[0], featureRange[1])
		if len(key) > 2 {
			parts = append(parts, fmt.Sprintf("%s%s", key[:2], bucketID))
		} else {
			parts = append(parts, fmt.Sprintf("%s%s", key, bucketID))
		}
	}

	// Combine all parts into a single state identifier
	return strings.Join(parts, "_")
}

// GetStateFeatures extracts features from the basic system state
func (e *EnhancedStateEncoder) GetStateFeatures(state SystemState) map[string]float64 {
	features := make(map[string]float64)

	// Extract basic system metrics
	nodeCount := float64(len(state.FogNodes))
	if nodeCount == 0 {
		// Empty state, provide default features
		return map[string]float64{
			"cpu_avg":    0.0,
			"mem_avg":    0.0,
			"task_count": 0.0,
			"node_count": 0.0,
		}
	}

	// Calculate basic metrics
	var totalCPU, totalMem, totalTasks, maxCPU, maxMem float64
	var totalNetwork, minCPU, minMem float64 = 0, 1.0, 1.0 // Initialize min values at max possible

	for _, node := range state.FogNodes {
		totalCPU += node.CPUUtilization
		totalMem += node.MemoryUtilization
		totalNetwork += node.NetworkBandwidth
		totalTasks += float64(node.TaskCount)

		// Track min/max values
		if node.CPUUtilization > maxCPU {
			maxCPU = node.CPUUtilization
		}
		if node.CPUUtilization < minCPU {
			minCPU = node.CPUUtilization
		}

		if node.MemoryUtilization > maxMem {
			maxMem = node.MemoryUtilization
		}
		if node.MemoryUtilization < minMem {
			minMem = node.MemoryUtilization
		}
	}

	// Basic system metrics
	features["cpu_avg"] = totalCPU / nodeCount
	features["mem_avg"] = totalMem / nodeCount
	features["net_avg"] = totalNetwork / nodeCount
	features["task_avg"] = totalTasks / nodeCount
	features["cpu_max"] = maxCPU
	features["mem_max"] = maxMem
	features["node_count"] = nodeCount

	// Calculate load balance metrics (CPU and memory imbalance)
	features["cpu_imbalance"] = maxCPU - minCPU
	features["mem_imbalance"] = maxMem - minMem

	// Add time features if enabled
	if e.useTimeFeatures {
		now := time.Now()

		// Hour of day normalized to 0-1
		hourOfDay := float64(now.Hour()) / 23.0
		features["hour"] = hourOfDay

		// Day of week normalized to 0-1 (0 = Sunday, 1 = Saturday)
		dayOfWeek := float64(now.Weekday()) / 6.0
		features["day"] = dayOfWeek

		// Is weekend (binary feature)
		if now.Weekday() == time.Saturday || now.Weekday() == time.Sunday {
			features["weekend"] = 1.0
		} else {
			features["weekend"] = 0.0
		}
	}

	// Apply normalization to all features
	e.normalizeFeatures(features)

	return features
}

// GetExtendedStateFeatures extracts features including historical and trend data
func (e *EnhancedStateEncoder) GetExtendedStateFeatures(state *ExtendedSystemState) map[string]float64 {
	// Get base features from the embedded SystemState
	features := e.GetStateFeatures(state.SystemState)

	if e.useHistorical {
		// Add system-wide balance scores
		features["cpu_balance"] = state.CPUBalanceScore
		features["mem_balance"] = state.MemoryBalanceScore

		// Add trend indicators if enabled
		if e.useTrends {
			features["cpu_trend"] = state.CPUUtilizationTrend
			features["mem_trend"] = state.MemUtilizationTrend
			features["req_trend"] = state.RequestRateTrend
		}

		// Add reliability metrics if enabled
		if e.useReliability {
			var totalReliability float64
			var nodeWithHistory int

			for _, history := range state.NodeHistory {
				totalReliability += history.ReliabilityScore
				nodeWithHistory++
			}

			if nodeWithHistory > 0 {
				features["reliability"] = totalReliability / float64(nodeWithHistory)
			} else {
				features["reliability"] = 0.5 // Neutral when no history
			}
		}
	}

	// Re-normalize with the new features
	e.normalizeFeatures(features)

	return features
}

// normalizeFeatures ensures all features are properly scaled
func (e *EnhancedStateEncoder) normalizeFeatures(features map[string]float64) {
	for key, value := range features {
		// Find the range for this feature
		rangeKey := key
		// For similar features, use the same range (e.g., cpu_avg, cpu_max -> cpu)
		if strings.HasPrefix(key, "cpu_") {
			rangeKey = "cpu"
		} else if strings.HasPrefix(key, "mem_") {
			rangeKey = "memory"
		} else if strings.HasPrefix(key, "net_") {
			rangeKey = "network"
		} else if strings.HasPrefix(key, "task_") {
			rangeKey = "tasks"
		} else if strings.HasPrefix(key, "reliability") {
			rangeKey = "reliability"
		} else if strings.HasSuffix(key, "trend") {
			rangeKey = "trend"
		} else if strings.HasSuffix(key, "balance") {
			rangeKey = "balance"
		}

		featureRange, exists := e.featureRanges[rangeKey]
		if exists {
			min, max := featureRange[0], featureRange[1]
			if max > min {
				// Apply min-max normalization
				normalizedValue := (value - min) / (max - min)
				// Clamp to 0-1 range
				features[key] = math.Max(0.0, math.Min(1.0, normalizedValue))
			}
		}
	}
}

// discretize converts a continuous value to a discrete bucket identifier
func (e *EnhancedStateEncoder) discretize(value, min, max float64) string {
	bucketSize := (max - min) / float64(e.discretizationLevels)
	bucketIndex := int((value - min) / bucketSize)

	// Handle edge cases
	if bucketIndex >= e.discretizationLevels {
		bucketIndex = e.discretizationLevels - 1
	}
	if bucketIndex < 0 {
		bucketIndex = 0
	}

	// Use a more readable encoding with digits and letters
	if bucketIndex < 10 {
		return fmt.Sprintf("%d", bucketIndex)
	}
	return string('A' + rune(bucketIndex-10))
}

// GetNodeAffinity calculates how well-suited a node is for a particular task type
// Returns a score from 0-1 where higher is better
func (e *EnhancedStateEncoder) GetNodeAffinity(state SystemState, nodeID string, taskType TaskType) float64 {
	// Basic node affinity calculation
	node, exists := state.FogNodes[nodeID]
	if !exists {
		return 0.0
	}

	// Base affinity on available resources
	cpuAvailable := 1.0 - node.CPUUtilization
	memAvailable := 1.0 - node.MemoryUtilization

	// Calculate affinity based on task type
	switch taskType {
	case TaskTypeCPUIntensive:
		return cpuAvailable*0.8 + memAvailable*0.2
	case TaskTypeMemoryIntensive:
		return cpuAvailable*0.2 + memAvailable*0.8
	case TaskTypeIOIntensive:
		return (cpuAvailable + memAvailable + (node.NetworkBandwidth / 100.0)) / 3.0
	default:
		return (cpuAvailable + memAvailable) / 2.0
	}
}

// GetExtendedNodeAffinity provides enhanced node affinity calculation with historical data
func (e *EnhancedStateEncoder) GetExtendedNodeAffinity(state *ExtendedSystemState, nodeID string, taskType TaskType) float64 {
	// First get the base affinity
	baseScore := e.GetNodeAffinity(state.SystemState, nodeID, taskType)

	// If we don't have history data, return the base score
	history, exists := state.NodeHistory[nodeID]
	if !exists {
		return baseScore
	}

	// Check task-specific success rate
	taskTypeStr := string(taskType)
	successRate, exists := history.TaskTypePerformance[taskTypeStr]
	if !exists {
		successRate = 0.5 // Neutral if no data
	}

	// Calculate resource trend (is this node getting more or less busy?)
	cpuTrend := 0.0
	if len(history.RecentCPUUtilization) >= 2 {
		last := len(history.RecentCPUUtilization) - 1
		cpuTrend = history.RecentCPUUtilization[last] - history.RecentCPUUtilization[0]
	}

	// Prefer nodes with improving (negative) CPU trend
	trendScore := 0.5 - cpuTrend*2.0 // 0.5 baseline, higher for negative trend
	trendScore = math.Max(0.0, math.Min(1.0, trendScore))

	// Combine factors with appropriate weights
	// Base affinity (30%), success rate (40%), reliability (20%), trend (10%)
	return baseScore*0.3 + successRate*0.4 + history.ReliabilityScore*0.2 + trendScore*0.1
}
