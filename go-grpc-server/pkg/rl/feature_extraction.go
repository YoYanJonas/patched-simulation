package rl

import (
	"math"
	"time"
)

// FeatureExtractor defines methods for extracting features from system states
type FeatureExtractor interface {
	// ExtractFeatures converts a SystemState into a feature vector
	ExtractFeatures(state SystemState) map[string]float64

	// Name returns the identifier for this extractor
	Name() string

	// GetFeatureDimension returns the size of the feature vector
	GetFeatureDimension() int
}

// FeatureExtractorOption configures a feature extractor
type FeatureExtractorOption func(extractor *BaseFeatureExtractor)

// BaseFeatureExtractor provides shared functionality for feature extractors
type BaseFeatureExtractor struct {
	useTimeData       bool
	useHistoricalData bool
	useTrendData      bool
	useNodeSpecific   bool
	featureNormRanges map[string][2]float64 // min/max for normalization
	featureNames      []string              // tracks available features
}

// BasicFeatureExtractor extracts standard features from system state
type BasicFeatureExtractor struct {
	BaseFeatureExtractor
}

// AdvancedFeatureExtractor extracts extended features including historical data
type AdvancedFeatureExtractor struct {
	BaseFeatureExtractor
}

// WithFeatureTimeData enables/disables time-based features
func WithFeatureTimeData(enable bool) FeatureExtractorOption {
	return func(e *BaseFeatureExtractor) {
		e.useTimeData = enable
		if enable {
			e.featureNames = append(e.featureNames, "time_hour", "time_day", "time_weekend")
		}
	}
}

// WithFeatureHistoricalData enables/disables historical performance features (renamed)
func WithFeatureHistoricalData(enable bool) FeatureExtractorOption {
	return func(e *BaseFeatureExtractor) {
		e.useHistoricalData = enable
		if enable {
			e.featureNames = append(e.featureNames,
				"reliability_avg", "success_rate", "exec_time_stability")
		}
	}
}

// WithFeatureTrends enables/disables resource trend features
func WithFeatureTrends(enable bool) FeatureExtractorOption {
	return func(e *BaseFeatureExtractor) {
		e.useTrendData = enable
		if enable {
			e.featureNames = append(e.featureNames,
				"cpu_trend", "mem_trend", "task_trend")
		}
	}
}

// WithFeatureNodeSpecific enables/disables per-node features
func WithFeatureNodeSpecific(enable bool) FeatureExtractorOption {
	return func(e *BaseFeatureExtractor) {
		e.useNodeSpecific = enable
	}
}

// WithFeatureNormRange sets the min/max normalization range for a feature
func WithFeatureNormRange(feature string, min, max float64) FeatureExtractorOption {
	return func(e *BaseFeatureExtractor) {
		e.featureNormRanges[feature] = [2]float64{min, max}
	}
}

// NewBaseFeatureExtractor creates a base feature extractor
func NewBaseFeatureExtractor(options ...FeatureExtractorOption) BaseFeatureExtractor {
	base := BaseFeatureExtractor{
		useTimeData:       false,
		useHistoricalData: false,
		useTrendData:      false,
		useNodeSpecific:   false,
		featureNames:      []string{},
		featureNormRanges: map[string][2]float64{
			"cpu":         {0.0, 1.0},
			"memory":      {0.0, 1.0},
			"network":     {0.0, 100.0},
			"tasks":       {0.0, 100.0},
			"balance":     {0.0, 1.0},
			"trend":       {-0.2, 0.2},
			"reliability": {0.0, 1.0},
		},
	}

	// Add core features that are always present
	base.featureNames = append(base.featureNames,
		"cpu_avg", "cpu_max", "cpu_imbalance",
		"mem_avg", "mem_max", "mem_imbalance",
		"task_avg", "task_max", "node_count")

	// Apply all options
	for _, option := range options {
		option(&base)
	}

	return base
}

// NewBasicFeatureExtractor creates a new basic feature extractor
func NewBasicFeatureExtractor(options ...FeatureExtractorOption) *BasicFeatureExtractor {
	return &BasicFeatureExtractor{
		BaseFeatureExtractor: NewBaseFeatureExtractor(options...),
	}
}

// NewAdvancedFeatureExtractor creates a new advanced feature extractor
func NewAdvancedFeatureExtractor(options ...FeatureExtractorOption) *AdvancedFeatureExtractor {
	// Enable advanced features by default
	baseOptions := []FeatureExtractorOption{
		WithFeatureTimeData(true),
		WithFeatureTrends(true),
		WithFeatureHistoricalData(true),
	}

	// Append user options (which can override defaults)
	options = append(baseOptions, options...)

	return &AdvancedFeatureExtractor{
		BaseFeatureExtractor: NewBaseFeatureExtractor(options...),
	}
}

// Name returns the identifier for this extractor
func (b *BasicFeatureExtractor) Name() string {
	return "basic_feature_extractor"
}

// Name returns the identifier for this extractor
func (a *AdvancedFeatureExtractor) Name() string {
	return "advanced_feature_extractor"
}

// GetFeatureDimension returns the size of the feature vector
func (b *BaseFeatureExtractor) GetFeatureDimension() int {
	return len(b.featureNames)
}

// ExtractFeatures converts a SystemState into a feature vector
func (b *BasicFeatureExtractor) ExtractFeatures(state SystemState) map[string]float64 {
	features := make(map[string]float64)
	nodeCount := len(state.FogNodes)

	// Handle empty state
	if nodeCount == 0 {
		for _, name := range b.featureNames {
			features[name] = 0.0
		}
		features["node_count"] = 0.0
		return features
	}

	// Calculate basic metrics
	var totalCPU, totalMem, totalTasks float64
	var maxCPU, maxMem, maxTasks float64
	var minCPU, minMem float64 = 1.0, 1.0

	for _, node := range state.FogNodes {
		totalCPU += node.CPUUtilization
		totalMem += node.MemoryUtilization
		totalTasks += float64(node.TaskCount)

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

		if float64(node.TaskCount) > maxTasks {
			maxTasks = float64(node.TaskCount)
		}
	}

	// Core system metrics
	features["cpu_avg"] = totalCPU / float64(nodeCount)
	features["mem_avg"] = totalMem / float64(nodeCount)
	features["task_avg"] = totalTasks / float64(nodeCount)
	features["cpu_max"] = maxCPU
	features["mem_max"] = maxMem
	features["task_max"] = maxTasks
	features["node_count"] = float64(nodeCount)

	// Balance metrics
	features["cpu_imbalance"] = maxCPU - minCPU
	features["mem_imbalance"] = maxMem - minMem

	// Calculate variance metrics
	cpuVariance, memVariance := b.calculateVariance(state, features["cpu_avg"], features["mem_avg"])
	features["cpu_variance"] = cpuVariance
	features["mem_variance"] = memVariance

	// Add time-based features if enabled
	if b.useTimeData {
		b.addTimeFeatures(features)
	}

	// Normalize features
	b.normalizeFeatures(features)

	return features
}

// ExtractFeatures converts a SystemState into an advanced feature vector
func (a *AdvancedFeatureExtractor) ExtractFeatures(state SystemState) map[string]float64 {
	// Start with basic features
	features := a.BaseFeatureExtractor.extractBaseFeatures(state)

	// If state is an ExtendedSystemState, extract additional features
	if extState := ToExtendedState(state); extState != nil {
		// Add trend features if enabled
		if a.useTrendData {
			features["cpu_trend"] = extState.CPUUtilizationTrend
			features["mem_trend"] = extState.MemUtilizationTrend
			features["req_trend"] = extState.RequestRateTrend
		}

		// Add historical reliability metrics if enabled
		if a.useHistoricalData {
			a.addHistoricalFeatures(features, extState)
		}

		// Add system balance scores
		features["cpu_balance_score"] = extState.CPUBalanceScore
		features["mem_balance_score"] = extState.MemoryBalanceScore
	}

	// Add time-based features if enabled
	if a.useTimeData {
		a.addTimeFeatures(features)
	}

	// Normalize all features
	a.normalizeFeatures(features)

	return features
}

// extractBaseFeatures extracts the basic feature set
func (b *BaseFeatureExtractor) extractBaseFeatures(state SystemState) map[string]float64 {
	features := make(map[string]float64)
	nodeCount := len(state.FogNodes)

	// Handle empty state
	if nodeCount == 0 {
		for _, name := range b.featureNames {
			features[name] = 0.0
		}
		features["node_count"] = 0.0
		return features
	}

	// Calculate basic metrics
	var totalCPU, totalMem, totalTasks float64
	var maxCPU, maxMem, maxTasks float64
	var minCPU, minMem float64 = 1.0, 1.0

	for _, node := range state.FogNodes {
		totalCPU += node.CPUUtilization
		totalMem += node.MemoryUtilization
		totalTasks += float64(node.TaskCount)

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

		if float64(node.TaskCount) > maxTasks {
			maxTasks = float64(node.TaskCount)
		}
	}

	// Core system metrics
	features["cpu_avg"] = totalCPU / float64(nodeCount)
	features["mem_avg"] = totalMem / float64(nodeCount)
	features["task_avg"] = totalTasks / float64(nodeCount)
	features["cpu_max"] = maxCPU
	features["mem_max"] = maxMem
	features["task_max"] = maxTasks
	features["node_count"] = float64(nodeCount)

	// Balance metrics
	features["cpu_imbalance"] = maxCPU - minCPU
	features["mem_imbalance"] = maxMem - minMem

	return features
}

// calculateVariance computes the variance of resource utilization
func (b *BaseFeatureExtractor) calculateVariance(state SystemState, cpuAvg, memAvg float64) (float64, float64) {
	var cpuVariance, memVariance float64
	nodeCount := len(state.FogNodes)

	if nodeCount == 0 {
		return 0.0, 0.0
	}

	for _, node := range state.FogNodes {
		cpuDiff := node.CPUUtilization - cpuAvg
		memDiff := node.MemoryUtilization - memAvg

		cpuVariance += cpuDiff * cpuDiff
		memVariance += memDiff * memDiff
	}

	cpuVariance /= float64(nodeCount)
	memVariance /= float64(nodeCount)

	return cpuVariance, memVariance
}

// addTimeFeatures adds time-based features to the feature vector
func (b *BaseFeatureExtractor) addTimeFeatures(features map[string]float64) {
	now := time.Now()

	// Hour of day normalized to 0-1
	features["time_hour"] = float64(now.Hour()) / 23.0

	// Day of week normalized to 0-1
	features["time_day"] = float64(now.Weekday()) / 6.0

	// Is weekend as binary feature
	if now.Weekday() == time.Saturday || now.Weekday() == time.Sunday {
		features["time_weekend"] = 1.0
	} else {
		features["time_weekend"] = 0.0
	}
}

// addHistoricalFeatures adds historical performance data to the feature vector
func (a *AdvancedFeatureExtractor) addHistoricalFeatures(features map[string]float64, extState *ExtendedSystemState) {
	var totalReliability, totalSuccessRate float64
	var nodeCount int

	for _, history := range extState.NodeHistory {
		totalReliability += history.ReliabilityScore

		// Calculate success rate
		totalTasks := history.SuccessCount + history.FailureCount
		if totalTasks > 0 {
			successRate := float64(history.SuccessCount) / float64(totalTasks)
			totalSuccessRate += successRate
		}

		nodeCount++
	}

	// Average across nodes
	if nodeCount > 0 {
		features["reliability_avg"] = totalReliability / float64(nodeCount)
		features["success_rate"] = totalSuccessRate / float64(nodeCount)

		// Add execution time stability feature
		var execTimeStability float64 = 0.5 // Default neutral value
		if nodeCount > 0 {
			execTimeStability = 0.0
			for _, history := range extState.NodeHistory {
				// Higher value means more stable execution times
				if history.AvgExecutionTimeMs > 0 {
					execTimeStability += 1.0
				}
			}
			execTimeStability /= float64(nodeCount)
		}
		features["exec_time_stability"] = execTimeStability
	} else {
		features["reliability_avg"] = 0.5 // neutral value
		features["success_rate"] = 0.5    // neutral value
		features["exec_time_stability"] = 0.5
	}
}

// normalizeFeatures ensures all features are properly scaled
func (b *BaseFeatureExtractor) normalizeFeatures(features map[string]float64) {
	for key, value := range features {
		// Find the proper range for this feature
		rangeKey := key

		// Group similar features
		if key == "cpu_avg" || key == "cpu_max" || key == "cpu_imbalance" {
			rangeKey = "cpu"
		} else if key == "mem_avg" || key == "mem_max" || key == "mem_imbalance" {
			rangeKey = "memory"
		} else if key == "task_avg" || key == "task_max" {
			rangeKey = "tasks"
		} else if key == "reliability_avg" || key == "success_rate" {
			rangeKey = "reliability"
		} else if key == "cpu_trend" || key == "mem_trend" || key == "req_trend" {
			rangeKey = "trend"
		}

		// Apply normalization if range exists
		if featureRange, exists := b.featureNormRanges[rangeKey]; exists {
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
