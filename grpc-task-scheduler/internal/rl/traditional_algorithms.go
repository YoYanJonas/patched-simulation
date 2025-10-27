package rl

import (
	"scheduler-grpc-server/pkg/config"
	"sort"
	"time"
)

// Constructor functions for traditional algorithms
func NewFCFSScheduler() SchedulingAlgorithm {
	return NewFCFSAlgorithm()
}

func NewSJFScheduler() SchedulingAlgorithm {
	return NewSJFAlgorithm()
}

func NewPriorityScheduler() SchedulingAlgorithm {
	return NewPriorityAlgorithm()
}

func NewEDFScheduler() SchedulingAlgorithm {
	return NewEDFAlgorithm()
}

// FCFSAlgorithm implements First Come First Serve scheduling
type FCFSAlgorithm struct {
	stats map[string]interface{}
}

func NewFCFSAlgorithm() *FCFSAlgorithm {
	return &FCFSAlgorithm{
		stats: make(map[string]interface{}),
	}
}

func (f *FCFSAlgorithm) Name() string {
	return "First Come First Serve (FCFS)"
}

// Fixed: Match the SchedulingAlgorithm interface signature
func (f *FCFSAlgorithm) Schedule(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	f.updateStats(len(tasks))
	return tasks
}

func (f *FCFSAlgorithm) GetStats() map[string]interface{} {
	return f.stats
}

func (f *FCFSAlgorithm) Configure(params map[string]interface{}) error {
	return nil
}

func (f *FCFSAlgorithm) updateStats(taskCount int) {
	f.stats["name"] = f.Name()
	f.stats["last_scheduled_tasks"] = taskCount
}

// SJFAlgorithm implements Shortest Job First scheduling
type SJFAlgorithm struct {
	stats map[string]interface{}
}

func NewSJFAlgorithm() *SJFAlgorithm {
	return &SJFAlgorithm{
		stats: make(map[string]interface{}),
	}
}

func (s *SJFAlgorithm) Name() string {
	return "Shortest Job First (SJF)"
}

// Fixed: Match the SchedulingAlgorithm interface signature
func (s *SJFAlgorithm) Schedule(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	if len(tasks) <= 1 {
		return tasks
	}

	scheduled := make([]TaskEntry, len(tasks))
	copy(scheduled, tasks)

	sort.Slice(scheduled, func(i, j int) bool {
		return scheduled[i].GetExecutionTimeMs() < scheduled[j].GetExecutionTimeMs()
	})

	s.updateStats(len(tasks))
	return scheduled
}

func (s *SJFAlgorithm) GetStats() map[string]interface{} {
	return s.stats
}

func (s *SJFAlgorithm) Configure(params map[string]interface{}) error {
	return nil
}

func (s *SJFAlgorithm) updateStats(taskCount int) {
	s.stats["name"] = s.Name()
	s.stats["last_scheduled_tasks"] = taskCount
}

// PriorityAlgorithm implements Priority-based scheduling
type PriorityAlgorithm struct {
	stats map[string]interface{}
}

func NewPriorityAlgorithm() *PriorityAlgorithm {
	return &PriorityAlgorithm{
		stats: make(map[string]interface{}),
	}
}

func (p *PriorityAlgorithm) Name() string {
	return "Priority-based Scheduling"
}

// Fixed: Match the SchedulingAlgorithm interface signature
func (p *PriorityAlgorithm) Schedule(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	if len(tasks) <= 1 {
		return tasks
	}

	scheduled := make([]TaskEntry, len(tasks))
	copy(scheduled, tasks)

	sort.Slice(scheduled, func(i, j int) bool {
		return scheduled[i].GetPriority() > scheduled[j].GetPriority()
	})

	p.updateStats(len(tasks))
	return scheduled
}

func (p *PriorityAlgorithm) GetStats() map[string]interface{} {
	return p.stats
}

func (p *PriorityAlgorithm) Configure(params map[string]interface{}) error {
	return nil
}

func (p *PriorityAlgorithm) updateStats(taskCount int) {
	p.stats["name"] = p.Name()
	p.stats["last_scheduled_tasks"] = taskCount
}

// EDFAlgorithm implements Earliest Deadline First scheduling
type EDFAlgorithm struct {
	stats map[string]interface{}
}

func NewEDFAlgorithm() *EDFAlgorithm {
	return &EDFAlgorithm{
		stats: make(map[string]interface{}),
	}
}

func (e *EDFAlgorithm) Name() string {
	return "Earliest Deadline First (EDF)"
}

// Fixed: Match the SchedulingAlgorithm interface signature
func (e *EDFAlgorithm) Schedule(tasks []TaskEntry, nodeManager SingleNodeManager) []TaskEntry {
	if len(tasks) <= 1 {
		return tasks
	}

	scheduled := make([]TaskEntry, len(tasks))
	copy(scheduled, tasks)

	sort.Slice(scheduled, func(i, j int) bool {
		deadlineI := time.Unix(scheduled[i].GetDeadline(), 0)
		deadlineJ := time.Unix(scheduled[j].GetDeadline(), 0)
		return deadlineI.Before(deadlineJ)
	})

	e.updateStats(len(tasks))
	return scheduled
}

func (e *EDFAlgorithm) GetStats() map[string]interface{} {
	return e.stats
}

func (e *EDFAlgorithm) Configure(params map[string]interface{}) error {
	return nil
}

func (e *EDFAlgorithm) updateStats(taskCount int) {
	e.stats["name"] = e.Name()
	e.stats["last_scheduled_tasks"] = taskCount
}

// PerformanceTracker for traditional algorithms (simple version)
type PerformanceTracker struct {
	window      int
	performance map[AlgorithmType][]float64
	weights     config.RewardWeights
}

// NewPerformanceTracker creates a new performance tracker for traditional algorithms
func NewPerformanceTracker(window int) *PerformanceTracker {
	return &PerformanceTracker{
		window:      window,
		performance: make(map[AlgorithmType][]float64),
		weights: config.RewardWeights{
			Latency:            0.3,
			Throughput:         0.3,
			ResourceEfficiency: 0.2,
			Fairness:           0.2,
		},
	}
}

// RecordPerformance records performance metrics for an algorithm
func (pt *PerformanceTracker) RecordPerformance(algType AlgorithmType, stats map[string]float64) {
	score := pt.calculatePerformanceScore(stats)

	if _, exists := pt.performance[algType]; !exists {
		pt.performance[algType] = make([]float64, 0, pt.window)
	}

	// Add new score
	pt.performance[algType] = append(pt.performance[algType], score)

	// Keep only last 'window' scores
	if len(pt.performance[algType]) > pt.window {
		pt.performance[algType] = pt.performance[algType][1:]
	}
}

// GetBestAlgorithm returns the algorithm with the best average performance
func (pt *PerformanceTracker) GetBestAlgorithm() AlgorithmType {
	bestAlg := AlgorithmType("")
	bestScore := -1.0

	for algType, scores := range pt.performance {
		if len(scores) == 0 {
			continue
		}

		// Calculate average score
		sum := 0.0
		for _, score := range scores {
			sum += score
		}
		avgScore := sum / float64(len(scores))

		if avgScore > bestScore {
			bestScore = avgScore
			bestAlg = algType
		}
	}

	return bestAlg
}

// calculatePerformanceScore calculates a weighted performance score
func (pt *PerformanceTracker) calculatePerformanceScore(stats map[string]float64) float64 {
	score := 0.0

	if latency, ok := stats["avg_latency"]; ok {
		// Lower latency is better, so invert
		score += pt.weights.Latency * (1.0 / (1.0 + latency))
	}

	if throughput, ok := stats["throughput"]; ok {
		// Higher throughput is better
		score += pt.weights.Throughput * throughput
	}

	if resourceEff, ok := stats["resource_efficiency"]; ok {
		// Higher efficiency is better
		score += pt.weights.ResourceEfficiency * resourceEff
	}

	if fairness, ok := stats["fairness"]; ok {
		// Higher fairness is better
		score += pt.weights.Fairness * fairness
	}

	return score
}
