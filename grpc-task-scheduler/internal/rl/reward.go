package rl

import (
	"math"
	"scheduler-grpc-server/pkg/config"
	"time"
)

// RewardCalculator calculates rewards for RL algorithms
type RewardCalculator struct {
	weights  config.RewardWeights
	history  []RewardMetrics
	baseline RewardMetrics
}

// RewardMetrics contains metrics used for reward calculation
type RewardMetrics struct {
	Latency          float64   `json:"latency"`
	Throughput       float64   `json:"throughput"`
	ResourceEff      float64   `json:"resource_efficiency"`
	Fairness         float64   `json:"fairness"`
	DeadlineMiss     float64   `json:"deadline_miss"`
	EnergyEfficiency float64   `json:"energy_efficiency"`
	QueueStability   float64   `json:"queue_stability"`
	ResponseTime     float64   `json:"response_time"`
	Timestamp        time.Time `json:"timestamp"`
}

// NewRewardCalculator creates a new reward calculator
func NewRewardCalculator(weights config.RewardWeights) *RewardCalculator {
	return &RewardCalculator{
		weights: weights,
		history: make([]RewardMetrics, 0, 100), // Keep last 100 metrics
		baseline: RewardMetrics{
			Latency:          100.0, // Default baseline values
			Throughput:       1.0,
			ResourceEff:      0.5,
			Fairness:         0.5,
			DeadlineMiss:     0.1,
			EnergyEfficiency: 0.5,
			QueueStability:   0.5,
			ResponseTime:     200.0,
		},
	}
}

// CalculateReward calculates reward based on current metrics and previous state
func (rc *RewardCalculator) CalculateReward(
	beforeState *StateFeatures,
	afterState *StateFeatures,
	action Action,
	tasks []TaskEntry,
	nodeManager SingleNodeManager) float64 {

	// Calculate current metrics
	currentMetrics := rc.calculateMetrics(afterState, tasks, nodeManager)

	// Store metrics in history
	rc.addToHistory(currentMetrics)

	// Calculate individual reward components
	latencyReward := rc.calculateLatencyReward(currentMetrics.Latency)
	throughputReward := rc.calculateThroughputReward(currentMetrics.Throughput)
	resourceReward := rc.calculateResourceEfficiencyReward(currentMetrics.ResourceEff)
	fairnessReward := rc.calculateFairnessReward(currentMetrics.Fairness)
	deadlineReward := rc.calculateDeadlineReward(currentMetrics.DeadlineMiss)
	energyReward := rc.calculateEnergyReward(currentMetrics.EnergyEfficiency)

	// Calculate improvement rewards (comparing to baseline or recent history)
	improvementReward := rc.calculateImprovementReward(currentMetrics)

	// Weighted sum of all reward components
	totalReward := rc.weights.Latency*latencyReward +
		rc.weights.Throughput*throughputReward +
		rc.weights.ResourceEfficiency*resourceReward +
		rc.weights.Fairness*fairnessReward +
		rc.weights.DeadlineMiss*deadlineReward +
		rc.weights.EnergyEfficiency*energyReward +
		0.1*improvementReward // Small weight for improvement

	// Apply action-specific bonuses/penalties
	actionReward := rc.calculateActionSpecificReward(action, beforeState, afterState)
	totalReward += actionReward

	// Normalize reward to reasonable range [-1, 1]
	return rc.normalizeReward(totalReward)
}

// calculateMetrics calculates current system metrics
func (rc *RewardCalculator) calculateMetrics(
	state *StateFeatures,
	tasks []TaskEntry,
	nodeManager SingleNodeManager) RewardMetrics {

	metrics := RewardMetrics{
		Timestamp: time.Now(),
	}

	if state != nil {
		metrics.Latency = state.AvgWaitingTime + state.AvgExecutionTime
		metrics.ResourceEff = (state.CPUUtilization + state.MemoryUtilization) / 2.0
		metrics.ResponseTime = state.RecentLatency
	}

	if len(tasks) > 0 {
		metrics.Throughput = rc.calculateThroughput(tasks)
		metrics.Fairness = rc.calculateFairness(tasks)
		metrics.DeadlineMiss = rc.calculateDeadlineMissRate(tasks)
		metrics.QueueStability = rc.calculateQueueStability(state)
	}

	if nodeManager != nil {
		metrics.EnergyEfficiency = rc.calculateEnergyEfficiency(nodeManager)
	}

	return metrics
}

// Individual reward calculation functions
func (rc *RewardCalculator) calculateLatencyReward(latency float64) float64 {
	// Lower latency is better - exponential decay reward
	if latency <= 0 {
		return 1.0
	}
	// Normalize against baseline and apply exponential function
	normalizedLatency := latency / rc.baseline.Latency
	return math.Exp(-normalizedLatency)
}

func (rc *RewardCalculator) calculateThroughputReward(throughput float64) float64 {
	// Higher throughput is better
	normalizedThroughput := throughput / rc.baseline.Throughput
	return math.Tanh(normalizedThroughput) // Bounded between -1 and 1
}

func (rc *RewardCalculator) calculateResourceEfficiencyReward(efficiency float64) float64 {
	// Reward high efficiency but penalize over-utilization
	if efficiency < 0.8 {
		return efficiency // Linear reward up to 80%
	} else if efficiency < 0.95 {
		return 0.8 + (efficiency-0.8)*0.5 // Reduced reward 80-95%
	} else {
		return 0.875 - (efficiency-0.95)*2 // Penalty above 95%
	}
}

func (rc *RewardCalculator) calculateFairnessReward(fairness float64) float64 {
	// Reward fairness with slight preference for higher values
	return fairness
}

func (rc *RewardCalculator) calculateDeadlineReward(missRate float64) float64 {
	// Heavily penalize deadline misses
	return math.Max(0.0, 1.0-missRate*2.0) // Double penalty for missed deadlines
}

func (rc *RewardCalculator) calculateEnergyReward(energyEff float64) float64 {
	// Reward energy efficiency
	return energyEff
}

func (rc *RewardCalculator) calculateImprovementReward(current RewardMetrics) float64 {
	if len(rc.history) < 5 {
		return 0.0 // Not enough history
	}

	// Calculate average of recent history (excluding current)
	recentAvg := rc.calculateRecentAverage()

	improvements := 0.0
	comparisons := 0.0

	// Latency improvement (lower is better)
	if current.Latency < recentAvg.Latency {
		improvements += (recentAvg.Latency - current.Latency) / recentAvg.Latency
	}
	comparisons++

	// Throughput improvement (higher is better)
	if current.Throughput > recentAvg.Throughput && recentAvg.Throughput > 0 {
		improvements += (current.Throughput - recentAvg.Throughput) / recentAvg.Throughput
	}
	comparisons++

	// Resource efficiency improvement
	if current.ResourceEff > recentAvg.ResourceEff {
		improvements += (current.ResourceEff - recentAvg.ResourceEff)
	}
	comparisons++

	if comparisons > 0 {
		return improvements / comparisons
	}
	return 0.0
}

func (rc *RewardCalculator) calculateActionSpecificReward(
	action Action, beforeState *StateFeatures, afterState *StateFeatures) float64 {

	reward := 0.0

	// Reward actions that improve queue management
	if beforeState != nil && afterState != nil {
		queueImprovement := float64(beforeState.QueueLength - afterState.QueueLength)
		reward += queueImprovement * 0.01 // Small bonus for reducing queue length
	}

	// Action-specific bonuses/penalties
	switch action.Type {
	case ActionReorder:
		// Small penalty for reordering (computational cost)
		reward -= 0.05
		// Bonus if reordering leads to better priority alignment
		if action.Priority > 0.7 {
			reward += 0.1
		}
	case ActionScheduleNext:
		// Neutral action
		reward += 0.0
	case ActionDelay:
		// Small penalty for delays
		reward -= 0.02
	case ActionPriorityBoost:
		// Penalty for priority manipulation unless justified
		reward -= 0.03
		if action.Priority > 0.8 {
			reward += 0.05 // Justified priority boost
		}
	}

	return reward
}

// Utility functions for metric calculations
func (rc *RewardCalculator) calculateThroughput(tasks []TaskEntry) float64 {
	// Simple throughput calculation: tasks per minute
	if len(tasks) == 0 {
		return 0.0
	}

	// Calculate based on task completion rate
	return float64(len(tasks)) / 60.0 // Assuming 1-minute window
}

func (rc *RewardCalculator) calculateFairness(tasks []TaskEntry) float64 {
	if len(tasks) <= 1 {
		return 1.0 // Perfect fairness with 0 or 1 task
	}

	// Calculate fairness using coefficient of variation of waiting times
	waitingTimes := make([]float64, len(tasks))
	sum := 0.0

	for i, task := range tasks {
		waitingTime := time.Since(task.GetArrivalTime()).Seconds()
		waitingTimes[i] = waitingTime
		sum += waitingTime
	}

	if sum == 0 {
		return 1.0 // All tasks have zero waiting time
	}

	mean := sum / float64(len(tasks))
	variance := 0.0

	for _, wt := range waitingTimes {
		diff := wt - mean
		variance += diff * diff
	}

	variance /= float64(len(tasks))
	stdDev := math.Sqrt(variance)

	// Coefficient of variation
	cv := stdDev / mean

	// Convert to fairness score (lower CV = higher fairness)
	return 1.0 / (1.0 + cv)
}

func (rc *RewardCalculator) calculateDeadlineMissRate(tasks []TaskEntry) float64 {
	if len(tasks) == 0 {
		return 0.0
	}

	missedDeadlines := 0
	for _, task := range tasks {
		// Estimate completion time
		estimatedCompletion := task.GetArrivalTime().Add(
			time.Since(task.GetArrivalTime()) +
				time.Duration(task.GetExecutionTimeMs())*time.Millisecond)

		// Check against deadline
		deadlineTime := time.Unix(task.GetDeadline(), 0)
		if estimatedCompletion.After(deadlineTime) {
			missedDeadlines++
		}
	}

	return float64(missedDeadlines) / float64(len(tasks))
}

func (rc *RewardCalculator) calculateQueueStability(state *StateFeatures) float64 {
	if len(rc.history) < 3 {
		return 0.5 // Default neutral stability
	}

	// Calculate queue length variance over recent history
	recentQueueLengths := make([]float64, 0)
	for i := len(rc.history) - 3; i < len(rc.history); i++ {
		if state != nil {
			recentQueueLengths = append(recentQueueLengths, float64(state.QueueLength))
		}
	}

	if len(recentQueueLengths) == 0 {
		return 0.5
	}

	sum := 0.0
	for _, ql := range recentQueueLengths {
		sum += ql
	}
	mean := sum / float64(len(recentQueueLengths))

	variance := 0.0
	for _, ql := range recentQueueLengths {
		diff := ql - mean
		variance += diff * diff
	}
	variance /= float64(len(recentQueueLengths))

	if mean == 0 {
		return 1.0 // Stable empty queue
	}

	// Convert to stability score
	cv := math.Sqrt(variance) / mean
	return 1.0 / (1.0 + cv)
}

func (rc *RewardCalculator) calculateEnergyEfficiency(nodeManager SingleNodeManager) float64 {
	// Calculate energy efficiency based on resource utilization
	cpuUtil := nodeManager.GetCPUUtilization()
	memUtil := nodeManager.GetMemoryUtilization()

	// Average utilization
	avgUtil := (cpuUtil + memUtil) / 2.0

	// Energy efficiency curve: optimal around 60-70% utilization
	if avgUtil < 0.3 {
		// Low utilization = poor energy efficiency
		return avgUtil / 0.3
	} else if avgUtil < 0.8 {
		// Good utilization range
		return 1.0
	} else {
		// Over-utilization = declining efficiency
		return 1.0 - (avgUtil-0.8)/0.2
	}
}

// Helper methods (unchanged)
func (rc *RewardCalculator) addToHistory(metrics RewardMetrics) {
	rc.history = append(rc.history, metrics)
	// Keep only last 100 entries
	if len(rc.history) > 100 {
		rc.history = rc.history[1:]
	}
}

func (rc *RewardCalculator) calculateRecentAverage() RewardMetrics {
	if len(rc.history) == 0 {
		return rc.baseline
	}

	// Average of last 5 entries (excluding current which isn't in history yet)
	start := len(rc.history) - 5
	if start < 0 {
		start = 0
	}

	avg := RewardMetrics{}
	count := float64(len(rc.history) - start)

	for i := start; i < len(rc.history); i++ {
		avg.Latency += rc.history[i].Latency
		avg.Throughput += rc.history[i].Throughput
		avg.ResourceEff += rc.history[i].ResourceEff
		avg.Fairness += rc.history[i].Fairness
		avg.DeadlineMiss += rc.history[i].DeadlineMiss
		avg.EnergyEfficiency += rc.history[i].EnergyEfficiency
	}

	avg.Latency /= count
	avg.Throughput /= count
	avg.ResourceEff /= count
	avg.Fairness /= count
	avg.DeadlineMiss /= count
	avg.EnergyEfficiency /= count

	return avg
}

func (rc *RewardCalculator) normalizeReward(reward float64) float64 {
	// Clamp reward to [-1, 1] range
	if reward > 1.0 {
		return 1.0
	} else if reward < -1.0 {
		return -1.0
	}
	return reward
}

// Public API methods (unchanged)
func (rc *RewardCalculator) GetRewardWeights() config.RewardWeights {
	return rc.weights
}

func (rc *RewardCalculator) SetRewardWeights(weights config.RewardWeights) {
	rc.weights = weights
}

func (rc *RewardCalculator) GetRecentMetrics(count int) []RewardMetrics {
	if len(rc.history) == 0 {
		return []RewardMetrics{}
	}

	start := len(rc.history) - count
	if start < 0 {
		start = 0
	}

	result := make([]RewardMetrics, len(rc.history)-start)
	copy(result, rc.history[start:])
	return result
}

func (rc *RewardCalculator) GetBaseline() RewardMetrics {
	return rc.baseline
}

func (rc *RewardCalculator) SetBaseline(baseline RewardMetrics) {
	rc.baseline = baseline
}

func (rc *RewardCalculator) Reset() {
	rc.history = rc.history[:0] // Clear history but keep capacity
}
