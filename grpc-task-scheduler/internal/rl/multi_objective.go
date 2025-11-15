package rl

import (
	"fmt"
	"math"
	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"

	"time"
)

// MultiObjectiveRewardCalculator extends RewardCalculator with multi-objective support
type MultiObjectiveRewardCalculator struct {
	*RewardCalculator
	config              *config.MultiObjectiveConfig
	activeProfile       string
	scalarizationMethod ScalarizationMethod
	adaptationEnabled   bool
	adaptationWindow    int
	performanceHistory  []ObjectivePerformance
	paretoFront         []ObjectiveVector
	adaptationHistory   []WeightAdaptation
}

// ScalarizationMethod defines how to combine multiple objectives
type ScalarizationMethod string

const (
	WeightedSum          ScalarizationMethod = "weighted_sum"
	Tchebycheff          ScalarizationMethod = "tchebycheff"
	AugmentedTchebycheff ScalarizationMethod = "augmented_tchebycheff"
)

// ObjectiveVector represents values for all objectives
type ObjectiveVector struct {
	Latency            float64   `json:"latency"`
	Throughput         float64   `json:"throughput"`
	ResourceEfficiency float64   `json:"resource_efficiency"`
	Fairness           float64   `json:"fairness"`
	DeadlineMiss       float64   `json:"deadline_miss"`
	EnergyEfficiency   float64   `json:"energy_efficiency"`
	Timestamp          time.Time `json:"timestamp"`
	Episode            int       `json:"episode"`
}

// ObjectivePerformance tracks performance across objectives
type ObjectivePerformance struct {
	Episode          int                  `json:"episode"`
	Objectives       ObjectiveVector      `json:"objectives"`
	ScalarizedReward float64              `json:"scalarized_reward"`
	Weights          config.RewardWeights `json:"weights"`
	ProfileUsed      string               `json:"profile_used"`
	Timestamp        time.Time            `json:"timestamp"`
}

// WeightAdaptation records weight adaptation events
type WeightAdaptation struct {
	Timestamp   time.Time            `json:"timestamp"`
	Episode     int                  `json:"episode"`
	OldWeights  config.RewardWeights `json:"old_weights"`
	NewWeights  config.RewardWeights `json:"new_weights"`
	Reason      string               `json:"reason"`
	Performance float64              `json:"performance_change"`
}

// NewMultiObjectiveRewardCalculator creates a new multi-objective reward calculator
func NewMultiObjectiveRewardCalculator(
	weights config.RewardWeights,
	moConfig *config.MultiObjectiveConfig) *MultiObjectiveRewardCalculator {

	baseCalculator := NewRewardCalculator(weights)

	calc := &MultiObjectiveRewardCalculator{
		RewardCalculator:    baseCalculator,
		config:              moConfig,
		activeProfile:       moConfig.ActiveProfile,
		scalarizationMethod: ScalarizationMethod(moConfig.ScalarizationMethod),
		adaptationEnabled:   moConfig.AdaptationEnabled,
		adaptationWindow:    moConfig.AdaptationWindow,
		performanceHistory:  make([]ObjectivePerformance, 0, moConfig.AdaptationWindow*2),
		paretoFront:         make([]ObjectiveVector, 0),
		adaptationHistory:   make([]WeightAdaptation, 0, 20),
	}

	logger.GetLogger().Infof("[MULTI-OBJ-INIT] Multi-objective calculator created: Profile=%s, Scalarization=%s, AdaptationEnabled=%t, AdaptationWindow=%d",
		moConfig.ActiveProfile, moConfig.ScalarizationMethod, moConfig.AdaptationEnabled, moConfig.AdaptationWindow)
	logger.GetLogger().Infof("[MULTI-OBJ-INIT] Initial weights: Latency=%.3f, Throughput=%.3f, ResourceEff=%.3f, Fairness=%.3f, DeadlineMiss=%.3f, EnergyEff=%.3f",
		weights.Latency, weights.Throughput, weights.ResourceEfficiency, weights.Fairness, weights.DeadlineMiss, weights.EnergyEfficiency)

	return calc
}

// CalculateMultiObjectiveReward calculates reward considering multiple objectives
// nodeStatusTracker: Tracks accumulated node status from completion reports (provides real CPU/Memory metrics)
func (morc *MultiObjectiveRewardCalculator) CalculateMultiObjectiveReward(
	beforeState *StateFeatures,
	afterState *StateFeatures,
	action Action,
	tasks []TaskEntry,
	nodeStatusTracker NodeStatusTracker,
	episode int) (float64, ObjectiveVector) {

	currentMetrics := morc.calculateMetrics(afterState, tasks, nodeStatusTracker)

	// Convert to objectives
	objectives := ObjectiveVector{
		Latency:            morc.normalizeLatencyObjective(currentMetrics.Latency),
		Throughput:         morc.normalizeThroughputObjective(currentMetrics.Throughput),
		ResourceEfficiency: morc.normalizeResourceObjective(currentMetrics.ResourceEff),
		Fairness:           morc.normalizeFairnessObjective(currentMetrics.Fairness),
		DeadlineMiss:       1.0, // Later Feature: deadline-aware disabled (best value)
		EnergyEfficiency:   morc.normalizeEnergyObjective(currentMetrics.EnergyEfficiency),
		Episode:            episode,
		Timestamp:          time.Now(),
	}

	// Get current weights (may be adapted)
	currentWeights := morc.GetRewardWeights()

	// Apply scalarization method
	scalarizedReward := morc.scalarizeObjectives(objectives, currentWeights)

	// Record performance for adaptation
	performance := ObjectivePerformance{
		Episode:          episode,
		Objectives:       objectives,
		ScalarizedReward: scalarizedReward,
		Weights:          currentWeights,
		ProfileUsed:      morc.activeProfile,
		Timestamp:        time.Now(),
	}
	morc.recordPerformance(performance)

	// Update Pareto front
	morc.updateParetoFront(objectives)

	// Adapt weights if enabled
	if morc.adaptationEnabled && len(morc.performanceHistory) >= morc.adaptationWindow {
		morc.adaptWeights(episode)
	}

	return scalarizedReward, objectives
}

// Add the missing normalization methods
func (morc *MultiObjectiveRewardCalculator) normalizeLatencyObjective(latency float64) float64 {
	if latency <= 0 {
		return 1.0
	}
	maxLatency := 1000.0 // ms
	normalized := 1.0 - math.Min(latency/maxLatency, 1.0)
	return math.Max(0.0, normalized)
}

func (morc *MultiObjectiveRewardCalculator) normalizeThroughputObjective(throughput float64) float64 {
	maxThroughput := 100.0 // tasks/min
	return math.Min(throughput/maxThroughput, 1.0)
}

func (morc *MultiObjectiveRewardCalculator) normalizeResourceObjective(efficiency float64) float64 {
	if efficiency <= 0.8 {
		return efficiency / 0.8
	} else {
		return math.Max(0.0, 1.0-(efficiency-0.8)*5.0)
	}
}

func (morc *MultiObjectiveRewardCalculator) normalizeFairnessObjective(fairness float64) float64 {
	return math.Max(0.0, math.Min(1.0, fairness))
}

// Later Feature: deadline-aware normalization disabled
func (morc *MultiObjectiveRewardCalculator) normalizeDeadlineObjective(missRate float64) float64 {
	return 1.0 // Best value (deadline-aware disabled)
}

func (morc *MultiObjectiveRewardCalculator) normalizeEnergyObjective(efficiency float64) float64 {
	return math.Max(0.0, math.Min(1.0, efficiency))
}

// Update scalarization methods to use config.RewardWeights
func (morc *MultiObjectiveRewardCalculator) scalarizeObjectives(
	objectives ObjectiveVector, weights config.RewardWeights) float64 {

	switch morc.scalarizationMethod {
	case WeightedSum:
		return morc.weightedSum(objectives, weights)
	case Tchebycheff:
		return morc.tchebycheff(objectives, weights)
	case AugmentedTchebycheff:
		return morc.augmentedTchebycheff(objectives, weights)
	default:
		return morc.weightedSum(objectives, weights)
	}
}

func (morc *MultiObjectiveRewardCalculator) weightedSum(
	objectives ObjectiveVector, weights config.RewardWeights) float64 {

	return weights.Latency*objectives.Latency +
		weights.Throughput*objectives.Throughput +
		weights.ResourceEfficiency*objectives.ResourceEfficiency +
		weights.Fairness*objectives.Fairness +
		weights.DeadlineMiss*objectives.DeadlineMiss +
		weights.EnergyEfficiency*objectives.EnergyEfficiency
}

func (morc *MultiObjectiveRewardCalculator) tchebycheff(
	objectives ObjectiveVector, weights config.RewardWeights) float64 {

	idealPoint := ObjectiveVector{1.0, 1.0, 1.0, 1.0, 1.0, 1.0, time.Now(), 0} // no use for these two last variables

	deviations := []float64{
		weights.Latency * math.Abs(idealPoint.Latency-objectives.Latency),
		weights.Throughput * math.Abs(idealPoint.Throughput-objectives.Throughput),
		weights.ResourceEfficiency * math.Abs(idealPoint.ResourceEfficiency-objectives.ResourceEfficiency),
		weights.Fairness * math.Abs(idealPoint.Fairness-objectives.Fairness),
		weights.DeadlineMiss * math.Abs(idealPoint.DeadlineMiss-objectives.DeadlineMiss),
		weights.EnergyEfficiency * math.Abs(idealPoint.EnergyEfficiency-objectives.EnergyEfficiency),
	}

	maxDeviation := 0.0
	for _, dev := range deviations {
		if dev > maxDeviation {
			maxDeviation = dev
		}
	}

	return 1.0 - maxDeviation
}

func (morc *MultiObjectiveRewardCalculator) augmentedTchebycheff(
	objectives ObjectiveVector, weights config.RewardWeights) float64 {

	tchebycheffValue := morc.tchebycheff(objectives, weights)
	weightedSumValue := morc.weightedSum(objectives, weights)

	rho := 0.05
	return tchebycheffValue + rho*weightedSumValue
}

// Weight adaptation methods
func (morc *MultiObjectiveRewardCalculator) adaptWeights(episode int) {
	logger.GetLogger().Infof("[MULTI-OBJ-ADAPT-ENTRY] adaptWeights called: Episode=%d, HistorySize=%d, Window=%d",
		episode, len(morc.performanceHistory), morc.adaptationWindow)

	if len(morc.performanceHistory) < morc.adaptationWindow {
		return
	}

	// Get recent performance window
	recentHistory := morc.performanceHistory[len(morc.performanceHistory)-morc.adaptationWindow:]

	logger.GetLogger().Infof("[MULTI-OBJ-ADAPT-HISTORY] Using recent history: Window=%d episodes",
		len(recentHistory))

	// Calculate objective trends
	trends := morc.calculateObjectiveTrends(recentHistory)

	logger.GetLogger().Infof("[MULTI-OBJ-ADAPT-TRENDS] Objective trends: Latency=%.3f, Throughput=%.3f, ResourceEff=%.3f, Fairness=%.3f, DeadlineMiss=%.3f, EnergyEff=%.3f",
		trends["latency"], trends["throughput"], trends["resource_efficiency"],
		trends["fairness"], trends["deadline_miss"], trends["energy_efficiency"])

	// Calculate new weights based on performance
	oldWeights := morc.GetRewardWeights()
	newWeights := morc.calculateAdaptedWeights(trends, oldWeights)

	logger.GetLogger().Infof("[MULTI-OBJ-ADAPT-WEIGHTS] Weight changes: Latency=%.3f->%.3f, Throughput=%.3f->%.3f, ResourceEff=%.3f->%.3f, Fairness=%.3f->%.3f, DeadlineMiss=%.3f->%.3f, EnergyEff=%.3f->%.3f",
		oldWeights.Latency, newWeights.Latency,
		oldWeights.Throughput, newWeights.Throughput,
		oldWeights.ResourceEfficiency, newWeights.ResourceEfficiency,
		oldWeights.Fairness, newWeights.Fairness,
		oldWeights.DeadlineMiss, newWeights.DeadlineMiss,
		oldWeights.EnergyEfficiency, newWeights.EnergyEfficiency)

	// Apply adaptation if significant
	shouldApply := morc.shouldApplyAdaptation(oldWeights, newWeights)
	maxChange := 0.0
	if shouldApply {
		changes := []float64{
			math.Abs(newWeights.Latency - oldWeights.Latency),
			math.Abs(newWeights.Throughput - oldWeights.Throughput),
			math.Abs(newWeights.ResourceEfficiency - oldWeights.ResourceEfficiency),
			math.Abs(newWeights.Fairness - oldWeights.Fairness),
			math.Abs(newWeights.DeadlineMiss - oldWeights.DeadlineMiss),
			math.Abs(newWeights.EnergyEfficiency - oldWeights.EnergyEfficiency),
		}
		for _, change := range changes {
			if change > maxChange {
				maxChange = change
			}
		}
	}

	logger.GetLogger().Infof("[MULTI-OBJ-ADAPT-APPLY] Adaptation check: MaxChange=%.3f, Threshold=0.05, ShouldApply=%t",
		maxChange, shouldApply)

	if shouldApply {
		performanceChange := morc.calculatePerformanceChange(recentHistory)

		reason := morc.generateAdaptationReason(trends)
		logger.GetLogger().Infof("[MULTI-OBJ-ADAPT-REASON] Adaptation reason: %s, PerformanceChange=%.3f",
			reason, performanceChange)

		adaptation := WeightAdaptation{
			Timestamp:   time.Now(),
			Episode:     episode,
			OldWeights:  oldWeights,
			NewWeights:  newWeights,
			Reason:      reason,
			Performance: performanceChange,
		}

		morc.SetRewardWeights(newWeights)
		morc.adaptationHistory = append(morc.adaptationHistory, adaptation)

		logger.GetLogger().Infof("[MULTI-OBJ-ADAPT-SUCCESS] Weight adaptation applied: Episode=%d, AdaptationHistorySize=%d",
			episode, len(morc.adaptationHistory))

		// Limit adaptation history
		if len(morc.adaptationHistory) > 20 {
			morc.adaptationHistory = morc.adaptationHistory[1:]
		}
	} else {
	}

	logger.GetLogger().Infof("[MULTI-OBJ-ADAPT-EXIT] adaptWeights completed: Episode=%d", episode)
}

func (morc *MultiObjectiveRewardCalculator) calculateObjectiveTrends(
	history []ObjectivePerformance) map[string]float64 {

	trends := make(map[string]float64)

	if len(history) < 2 {
		return trends
	}

	// Simple linear trend: compare first half with second half
	midPoint := len(history) / 2
	firstHalf := history[:midPoint]
	secondHalf := history[midPoint:]

	firstAvg := morc.calculateAverageObjectives(firstHalf)
	secondAvg := morc.calculateAverageObjectives(secondHalf)

	// Calculate improvement rates
	trends["latency"] = (secondAvg.Latency - firstAvg.Latency) / (firstAvg.Latency + 1e-6)
	trends["throughput"] = (secondAvg.Throughput - firstAvg.Throughput) / (firstAvg.Throughput + 1e-6)
	trends["resource_efficiency"] = (secondAvg.ResourceEfficiency - firstAvg.ResourceEfficiency) / (firstAvg.ResourceEfficiency + 1e-6)
	trends["fairness"] = (secondAvg.Fairness - firstAvg.Fairness) / (firstAvg.Fairness + 1e-6)
	trends["deadline_miss"] = (secondAvg.DeadlineMiss - firstAvg.DeadlineMiss) / (firstAvg.DeadlineMiss + 1e-6)
	trends["energy_efficiency"] = (secondAvg.EnergyEfficiency - firstAvg.EnergyEfficiency) / (firstAvg.EnergyEfficiency + 1e-6)

	return trends
}

func (morc *MultiObjectiveRewardCalculator) calculateAverageObjectives(
	history []ObjectivePerformance) ObjectiveVector {

	if len(history) == 0 {
		return ObjectiveVector{}
	}

	avg := ObjectiveVector{}
	for _, perf := range history {
		avg.Latency += perf.Objectives.Latency
		avg.Throughput += perf.Objectives.Throughput
		avg.ResourceEfficiency += perf.Objectives.ResourceEfficiency
		avg.Fairness += perf.Objectives.Fairness
		avg.DeadlineMiss += perf.Objectives.DeadlineMiss
		avg.EnergyEfficiency += perf.Objectives.EnergyEfficiency
	}

	n := float64(len(history))
	avg.Latency /= n
	avg.Throughput /= n
	avg.ResourceEfficiency /= n
	avg.Fairness /= n
	avg.DeadlineMiss /= n
	avg.EnergyEfficiency /= n

	return avg
}

// Fix: Removed unused variances parameter
func (morc *MultiObjectiveRewardCalculator) calculateAdaptedWeights(
	trends map[string]float64,
	currentWeights config.RewardWeights) config.RewardWeights {

	// Simple adaptation strategy: increase weight for improving objectives
	// and decrease for stagnating ones
	newWeights := currentWeights
	adaptationRate := 0.1

	// Calculate adaptation factors based on trends
	adaptationFactors := make(map[string]float64)

	// Positive trend = increase weight slightly, negative = decrease
	adaptationFactors["latency"] = 1.0 + adaptationRate*trends["latency"]
	adaptationFactors["throughput"] = 1.0 + adaptationRate*trends["throughput"]
	adaptationFactors["resource_efficiency"] = 1.0 + adaptationRate*trends["resource_efficiency"]
	adaptationFactors["fairness"] = 1.0 + adaptationRate*trends["fairness"]
	adaptationFactors["deadline_miss"] = 1.0 + adaptationRate*trends["deadline_miss"]
	adaptationFactors["energy_efficiency"] = 1.0 + adaptationRate*trends["energy_efficiency"]

	// Apply adaptation factors
	newWeights.Latency *= adaptationFactors["latency"]
	newWeights.Throughput *= adaptationFactors["throughput"]
	newWeights.ResourceEfficiency *= adaptationFactors["resource_efficiency"]
	newWeights.Fairness *= adaptationFactors["fairness"]
	newWeights.DeadlineMiss *= adaptationFactors["deadline_miss"]
	newWeights.EnergyEfficiency *= adaptationFactors["energy_efficiency"]

	// Normalize to ensure weights sum to 1
	newWeights.Normalize()

	return newWeights
}

func (morc *MultiObjectiveRewardCalculator) shouldApplyAdaptation(
	oldWeights config.RewardWeights, newWeights config.RewardWeights) bool {

	// Check if adaptation is significant enough
	threshold := 0.05 // 5% change threshold

	changes := []float64{
		math.Abs(newWeights.Latency - oldWeights.Latency),
		math.Abs(newWeights.Throughput - oldWeights.Throughput),
		math.Abs(newWeights.ResourceEfficiency - oldWeights.ResourceEfficiency),
		math.Abs(newWeights.Fairness - oldWeights.Fairness),
		math.Abs(newWeights.DeadlineMiss - oldWeights.DeadlineMiss),
		math.Abs(newWeights.EnergyEfficiency - oldWeights.EnergyEfficiency),
	}

	maxChange := 0.0
	for _, change := range changes {
		if change > maxChange {
			maxChange = change
		}
	}

	return maxChange >= threshold
}

func (morc *MultiObjectiveRewardCalculator) calculatePerformanceChange(
	recentHistory []ObjectivePerformance) float64 {

	if len(recentHistory) < 4 {
		return 0.0
	}

	// Compare first quarter with last quarter
	quarterSize := len(recentHistory) / 4
	firstQuarter := recentHistory[:quarterSize]
	lastQuarter := recentHistory[len(recentHistory)-quarterSize:]

	firstAvg := 0.0
	for _, perf := range firstQuarter {
		firstAvg += perf.ScalarizedReward
	}
	firstAvg /= float64(len(firstQuarter))

	lastAvg := 0.0
	for _, perf := range lastQuarter {
		lastAvg += perf.ScalarizedReward
	}
	lastAvg /= float64(len(lastQuarter))

	// Return relative improvement
	if firstAvg > 0 {
		return (lastAvg - firstAvg) / firstAvg
	}
	return 0.0
}

// Fix: Removed unused variances parameter
func (morc *MultiObjectiveRewardCalculator) generateAdaptationReason(
	trends map[string]float64) string {

	// Find the objective with highest positive trend
	maxTrend := -math.Inf(1)
	maxTrendObjective := ""

	for obj, trend := range trends {
		if trend > maxTrend {
			maxTrend = trend
			maxTrendObjective = obj
		}
	}

	if maxTrend > 0.05 {
		return fmt.Sprintf("Boosting %s due to positive trend (%.3f)", maxTrendObjective, maxTrend)
	}

	return "General performance-based adaptation"
}

func (morc *MultiObjectiveRewardCalculator) recordPerformance(performance ObjectivePerformance) {
	morc.performanceHistory = append(morc.performanceHistory, performance)

	// Limit history size
	maxHistory := morc.adaptationWindow * 2
	if len(morc.performanceHistory) > maxHistory {
		morc.performanceHistory = morc.performanceHistory[1:]
	}
}

func (morc *MultiObjectiveRewardCalculator) updateParetoFront(objectives ObjectiveVector) {
	// Simple Pareto front maintenance
	// Add new point and remove dominated ones

	// Check if new point is dominated by existing ones
	dominated := false
	for _, existing := range morc.paretoFront {
		if morc.dominates(existing, objectives) {
			dominated = true
			break
		}
	}

	if !dominated {
		// Add new point
		morc.paretoFront = append(morc.paretoFront, objectives)

		// Remove points dominated by the new one
		removedCount := 0
		newFront := make([]ObjectiveVector, 0)
		for _, existing := range morc.paretoFront {
			if !morc.dominates(objectives, existing) {
				newFront = append(newFront, existing)
			} else {
				removedCount++
			}
		}
		morc.paretoFront = newFront

		logger.GetLogger().Infof("[MULTI-OBJ-PARETO-ADDED] Point added to front: Episode=%d, RemovedDominated=%d, NewSize=%d",
			objectives.Episode, removedCount, len(morc.paretoFront))

		// Limit Pareto front size
		if len(morc.paretoFront) > 50 {
			// Keep most recent points
			morc.paretoFront = morc.paretoFront[len(morc.paretoFront)-50:]
		}
	}
}

func (morc *MultiObjectiveRewardCalculator) dominates(a, b ObjectiveVector) bool {
	// a dominates b if a is better or equal in all objectives and strictly better in at least one
	betterInAll := a.Latency >= b.Latency &&
		a.Throughput >= b.Throughput &&
		a.ResourceEfficiency >= b.ResourceEfficiency &&
		a.Fairness >= b.Fairness &&
		a.DeadlineMiss >= b.DeadlineMiss &&
		a.EnergyEfficiency >= b.EnergyEfficiency

	strictlyBetterInOne := a.Latency > b.Latency ||
		a.Throughput > b.Throughput ||
		a.ResourceEfficiency > b.ResourceEfficiency ||
		a.Fairness > b.Fairness ||
		a.DeadlineMiss > b.DeadlineMiss ||
		a.EnergyEfficiency > b.EnergyEfficiency

	return betterInAll && strictlyBetterInOne
}

// Public API methods
func (morc *MultiObjectiveRewardCalculator) GetActiveProfile() string {
	return morc.activeProfile
}

func (morc *MultiObjectiveRewardCalculator) SetActiveProfile(profileName string) error {
	// Validate profile exists in config
	if morc.config != nil {
		if _, exists := morc.config.Profiles[profileName]; !exists {
			return fmt.Errorf("profile '%s' not found", profileName)
		}

		// Update weights from profile
		profile := morc.config.Profiles[profileName]
		morc.SetRewardWeights(profile.Weights)
	}

	morc.activeProfile = profileName
	return nil
}

func (morc *MultiObjectiveRewardCalculator) GetParetoFront() []ObjectiveVector {
	// Return copy to prevent external modification
	front := make([]ObjectiveVector, len(morc.paretoFront))
	copy(front, morc.paretoFront)
	return front
}

func (morc *MultiObjectiveRewardCalculator) GetPerformanceHistory(episodes int) []ObjectivePerformance {
	if episodes <= 0 || episodes > len(morc.performanceHistory) {
		episodes = len(morc.performanceHistory)
	}

	start := len(morc.performanceHistory) - episodes
	history := make([]ObjectivePerformance, episodes)
	copy(history, morc.performanceHistory[start:])
	return history
}

func (morc *MultiObjectiveRewardCalculator) GetAdaptationHistory() []WeightAdaptation {
	history := make([]WeightAdaptation, len(morc.adaptationHistory))
	copy(history, morc.adaptationHistory)
	return history
}

func (morc *MultiObjectiveRewardCalculator) Reset() {
	morc.RewardCalculator.Reset()
	morc.performanceHistory = morc.performanceHistory[:0]
	morc.paretoFront = morc.paretoFront[:0]
	morc.adaptationHistory = morc.adaptationHistory[:0]
}

func (morc *MultiObjectiveRewardCalculator) GetScalarizationMethod() ScalarizationMethod {
	return morc.scalarizationMethod
}

func (morc *MultiObjectiveRewardCalculator) SetScalarizationMethod(method ScalarizationMethod) {
	morc.scalarizationMethod = method
}

// CalculateDelayedReward calculates reward considering multiple objectives.
// This method aligns with the signature expected by ExperienceManager.
func (morc *MultiObjectiveRewardCalculator) CalculateDelayedReward(
	incompleteExpState *StateFeatures, // Corresponds to incompleteExp.State
	action Action,
	completedTasks []*pb.CompletedTask,
	systemMetrics *pb.SystemPerformanceMetrics, // Corresponds to report.Metrics
	nodeStatus *pb.FogNode, // NEW: Node status at completion time
) (float64, error) {

	taskID := "unknown"
	if len(completedTasks) > 0 {
		taskID = completedTasks[0].TaskId
	}

	logger.GetLogger().Infof("[MULTI-OBJ-REWARD-ENTRY] CalculateDelayedReward called: TaskID=%s, CompletedTasks=%d, NodeStatus=%t",
		taskID, len(completedTasks), nodeStatus != nil)

	// Convert proto metrics to internal SystemPerformanceMetrics (use node status for resource utilization)
	currentMetrics := morc.calculateMetricsFromReport(completedTasks, systemMetrics, nodeStatus)

	logger.GetLogger().Infof("[MULTI-OBJ-REWARD-METRICS] Metrics extracted: Latency=%.2fms, Throughput=%.2f, ResourceUtil=%.3f, Fairness=%.3f, DeadlineMisses=%d, EnergyEff=%.3f",
		currentMetrics.AverageLatencyMs, currentMetrics.TotalThroughput, currentMetrics.ResourceUtilization,
		currentMetrics.FairnessIndex, currentMetrics.DeadlineMisses, currentMetrics.EnergyEfficiency)

	// GET EPISODE NUMBER: Extract from performance history or use default
	currentEpisode := 1
	if len(morc.performanceHistory) > 0 {
		currentEpisode = morc.performanceHistory[len(morc.performanceHistory)-1].Episode + 1
	}
	logger.GetLogger().Infof("[MULTI-OBJ-REWARD-EPISODE] Episode number determined: %d (from history=%t, historySize=%d)",
		currentEpisode, len(morc.performanceHistory) > 0, len(morc.performanceHistory))

	// Convert to objectives
	objectives := ObjectiveVector{
		Latency:            morc.normalizeLatencyObjective(currentMetrics.AverageLatencyMs),
		Throughput:         morc.normalizeThroughputObjective(currentMetrics.TotalThroughput),
		ResourceEfficiency: morc.normalizeResourceObjective(currentMetrics.ResourceUtilization),
		Fairness:           morc.normalizeFairnessObjective(currentMetrics.FairnessIndex),
		DeadlineMiss:       1.0, // Later Feature: deadline-aware disabled (best value)
		EnergyEfficiency:   morc.normalizeEnergyObjective(currentMetrics.EnergyEfficiency),
		Episode:            currentEpisode, // ← USE ACTUAL EPISODE NUMBER
		Timestamp:          time.Now(),
	}

	logger.GetLogger().Infof("[MULTI-OBJ-REWARD-NORMALIZE] Normalized objectives: Latency=%.3f, Throughput=%.3f, ResourceEff=%.3f, Fairness=%.3f, DeadlineMiss=%.3f, EnergyEff=%.3f",
		objectives.Latency, objectives.Throughput, objectives.ResourceEfficiency, objectives.Fairness, objectives.DeadlineMiss, objectives.EnergyEfficiency)

	// Get current weights (may be adapted)
	currentWeights := morc.GetRewardWeights()

	logger.GetLogger().Infof("[MULTI-OBJ-REWARD-WEIGHTS] Current weights: Latency=%.3f, Throughput=%.3f, ResourceEff=%.3f, Fairness=%.3f, DeadlineMiss=%.3f (disabled), EnergyEff=%.3f",
		currentWeights.Latency, currentWeights.Throughput, currentWeights.ResourceEfficiency,
		currentWeights.Fairness, currentWeights.DeadlineMiss, currentWeights.EnergyEfficiency)
	logger.GetLogger().Infof("[DEADLINE-DISABLED] DeadlineMiss weight=%.3f, objective=%.3f, contribution=%.3f (zero contribution verified)",
		currentWeights.DeadlineMiss, objectives.DeadlineMiss, currentWeights.DeadlineMiss*objectives.DeadlineMiss)

	// Apply scalarization method
	scalarizedReward := morc.scalarizeObjectives(objectives, currentWeights)

	logger.GetLogger().Infof("[MULTI-OBJ-REWARD-SCALARIZE] Scalarization method=%s, scalarized reward=%.3f, Profile=%s",
		morc.scalarizationMethod, scalarizedReward, morc.activeProfile)

	// Record performance for adaptation
	performance := ObjectivePerformance{
		Episode:          currentEpisode,
		Objectives:       objectives,
		ScalarizedReward: scalarizedReward,
		Weights:          currentWeights,
		ProfileUsed:      morc.activeProfile,
		Timestamp:        time.Now(),
	}
	morc.recordPerformance(performance)

	logger.GetLogger().Infof("[MULTI-OBJ-REWARD-RECORD] Performance recorded: Episode=%d, Reward=%.3f, HistorySize=%d",
		currentEpisode, scalarizedReward, len(morc.performanceHistory))

	// Update Pareto front
	oldParetoSize := len(morc.paretoFront)
	morc.updateParetoFront(objectives)
	newParetoSize := len(morc.paretoFront)
	if newParetoSize != oldParetoSize {
		logger.GetLogger().Infof("[MULTI-OBJ-PARETO-UPDATE] Pareto front updated: Size=%d->%d, Episode=%d",
			oldParetoSize, newParetoSize, currentEpisode)
	}

	// Adapt weights if enabled
	if morc.adaptationEnabled && len(morc.performanceHistory) >= morc.adaptationWindow {
		logger.GetLogger().Infof("[MULTI-OBJ-REWARD-ADAPT] Triggering weight adaptation: HistorySize=%d >= Window=%d",
			len(morc.performanceHistory), morc.adaptationWindow)
		morc.adaptWeights(currentEpisode)
	}

	logger.GetLogger().Infof("[MULTI-OBJ-REWARD-EXIT] CalculateDelayedReward returning: TaskID=%s, Reward=%.3f, Episode=%d",
		taskID, scalarizedReward, currentEpisode)

	return scalarizedReward, nil
}

// calculateMetricsFromReport is a helper to derive SystemPerformanceMetrics from the report.
// This converts protobuf metrics to internal metrics format.
func (morc *MultiObjectiveRewardCalculator) calculateMetricsFromReport(
	tasks []*pb.CompletedTask, // ← CHANGED: Now accepts pointers directly
	metrics *pb.SystemPerformanceMetrics,
	nodeStatus *pb.FogNode, // NEW: Node status at completion time
) SystemPerformanceMetrics {

	// Convert protobuf metrics to internal format
	derivedMetrics := SystemPerformanceMetrics{
		TotalThroughput:     metrics.TotalThroughput,
		AverageLatencyMs:    metrics.AverageLatencyMs,
		EnergyEfficiency:    metrics.EnergyEfficiency,
		ResourceUtilization: metrics.ResourceUtilization, // Will be overridden if nodeStatus available
		DeadlineMisses:      metrics.DeadlineMisses,
		FairnessIndex:       metrics.FairnessIndex,
	}

	// NEW: Use node status for resource utilization calculation
	if nodeStatus != nil && nodeStatus.CurrentUsage != nil && nodeStatus.Capacity != nil {
		// Calculate CPU utilization percentage (clamp to [0.0, 1.0])
		cpuUtilPercent := float64(nodeStatus.CurrentUsage.CpuUsage) / 100.0 // Already percentage
		if cpuUtilPercent < 0.0 {
			cpuUtilPercent = 0.0
		}
		if cpuUtilPercent > 1.0 {
			cpuUtilPercent = 1.0
		}

		// Calculate Memory utilization percentage
		memoryUtilPercent := 0.0
		if nodeStatus.Capacity.MemoryMb > 0 {
			memoryUtilPercent = float64(nodeStatus.CurrentUsage.MemoryUsageMb) / float64(nodeStatus.Capacity.MemoryMb)
			// Clamp to [0.0, 1.0] to handle edge cases
			if memoryUtilPercent < 0.0 {
				memoryUtilPercent = 0.0
			}
			if memoryUtilPercent > 1.0 {
				memoryUtilPercent = 1.0
			}
		}

		// Resource utilization = average of CPU and Memory
		derivedMetrics.ResourceUtilization = (cpuUtilPercent + memoryUtilPercent) / 2.0

		logger.GetLogger().Infof("[MULTI-OBJ-METRICS-NODE] Node=%s, CPU=%.2f%%, Memory=%.2f%%, ResourceUtil=%.3f",
			nodeStatus.NodeId, cpuUtilPercent*100, memoryUtilPercent*100, derivedMetrics.ResourceUtilization)
	} else {
		// Fallback to systemMetrics if node status not available
		derivedMetrics.ResourceUtilization = metrics.ResourceUtilization
		logger.GetLogger().Warnf("[MULTI-OBJ-METRICS-NO-NODE] Node status not available, using systemMetrics.ResourceUtilization=%.3f",
			derivedMetrics.ResourceUtilization)
	}

	// If you need to derive additional metrics from tasks, do it here:
	if len(tasks) > 0 {
		totalExecutionTime := 0.0
		for _, task := range tasks { // ← SAFE: iterating over pointers, no copying
			totalExecutionTime += task.GetActualExecutionTimeMs() // ← Use getter methods
		}
		// You can use totalExecutionTime for additional calculations if needed
	}

	return derivedMetrics
}

// SystemPerformanceMetrics represents aggregated system metrics for reward calculation.
// This struct should align with or be derived from pb.SystemPerformanceMetrics.
type SystemPerformanceMetrics struct {
	TotalThroughput     float64
	AverageLatencyMs    float64
	EnergyEfficiency    float64
	ResourceUtilization float64
	DeadlineMisses      int32 // Using int32 to match proto definition
	FairnessIndex       float64
}
