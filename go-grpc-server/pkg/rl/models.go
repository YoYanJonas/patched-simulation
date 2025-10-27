package rl

import (
	"math"
	"sync"
	"time"
)

// FogNodeState represents the resource state of a single fog node
type FogNodeState struct {
	NodeID            string
	CPUUtilization    float64 // 0.0-1.0 representing percentage
	MemoryUtilization float64 // 0.0-1.0 representing percentage
	NetworkBandwidth  float64 // Available bandwidth in Mbps
	TaskCount         int     // Current number of tasks running
}

// SystemState represents the overall state of all fog nodes
type SystemState struct {
	FogNodes map[string]FogNodeState
	// Can be extended with global metrics
}

// TaskDecision tracks the allocation decision for a specific task
type TaskDecision struct {
	TaskID       string
	State        SystemState
	SelectedNode string
	Timestamp    time.Time
}

// DecisionStore manages the tracking of task allocation decisions
type DecisionStore struct {
	decisions map[string]TaskDecision
	mutex     sync.RWMutex
}

// NodePerformanceHistory tracks historical performance for a node
type NodePerformanceHistory struct {
	SuccessCount       int64   // Number of successful task executions
	FailureCount       int64   // Number of failed task executions
	TotalExecutionMs   int64   // Total execution time in milliseconds
	AvgExecutionTimeMs float64 // Average execution time per task
	LastUpdated        time.Time

	// Task type specific performance - maps task type to success rate
	TaskTypePerformance map[string]float64

	// Store recent utilization samples to detect trends (last 10 samples)
	RecentCPUUtilization   []float64
	RecentMemUtilization   []float64
	RecentNetworkBandwidth []float64

	// Reliability score (0-1) based on recent performance
	ReliabilityScore float64

	mutex sync.RWMutex
}

// TaskType categorizes different kinds of workloads
type TaskType string

const (
	TaskTypeCPUIntensive    TaskType = "cpu_intensive"
	TaskTypeMemoryIntensive TaskType = "memory_intensive"
	TaskTypeIOIntensive     TaskType = "io_intensive"
	TaskTypeBalanced        TaskType = "balanced"
	TaskTypeUnknown         TaskType = "unknown"
)

// TaskRequirements defines the resource needs of a task
type TaskRequirements struct {
	CPURequirement       float64
	MemoryRequirement    float64
	BandwidthRequirement float64
	EstimatedDuration    int64 // Expected execution time in ms
	TaskType             TaskType
}

// ExtendedSystemState represents a richer system state including historical data
type ExtendedSystemState struct {
	// Embed the basic system state
	SystemState

	// Global system metrics
	GlobalCPUUtilization     float64
	GlobalMemUtilization     float64
	GlobalNetworkUtilization float64

	// System load balance metrics (standard deviation of resource utilization)
	CPUBalanceScore    float64
	MemoryBalanceScore float64

	// Per-node historical performance
	NodeHistory map[string]*NodePerformanceHistory

	// Time-based features
	TimeOfDayHour int // 0-23 hour of day
	DayOfWeek     int // 0-6 day of week
	IsWeekend     bool

	// Trend indicators (system-wide)
	CPUUtilizationTrend float64 // Positive = increasing, negative = decreasing
	MemUtilizationTrend float64
	RequestRateTrend    float64
}

// GetNodeHistory returns the node history map
func (s *ExtendedSystemState) GetNodeHistory() map[string]*NodePerformanceHistory {
	return s.NodeHistory
}

// GetSystemMetrics returns key system metrics
func (s *ExtendedSystemState) GetSystemMetrics() (cpuBalance, memBalance, cpuTrend, memTrend float64) {
	return s.CPUBalanceScore, s.MemoryBalanceScore, s.CPUUtilizationTrend, s.MemUtilizationTrend
}

// NewDecisionStore creates a new decision tracking store
func NewDecisionStore() *DecisionStore {
	return &DecisionStore{
		decisions: make(map[string]TaskDecision),
	}
}

// StoreDecision records a task allocation decision
func (ds *DecisionStore) StoreDecision(taskID, nodeID string, state SystemState) {
	ds.mutex.Lock()
	defer ds.mutex.Unlock()

	ds.decisions[taskID] = TaskDecision{
		TaskID:       taskID,
		State:        state,
		SelectedNode: nodeID,
		Timestamp:    time.Now(),
	}
}

// GetDecision retrieves a stored decision by task ID
func (ds *DecisionStore) GetDecision(taskID string) (TaskDecision, bool) {
	ds.mutex.RLock()
	defer ds.mutex.RUnlock()

	decision, exists := ds.decisions[taskID]
	return decision, exists
}

// RemoveDecision removes a decision after processing
func (ds *DecisionStore) RemoveDecision(taskID string) {
	ds.mutex.Lock()
	defer ds.mutex.Unlock()

	delete(ds.decisions, taskID)
}

// UpdateNodeHistory adds a task outcome to the node's performance history
func (s *ExtendedSystemState) UpdateNodeHistory(nodeID string, success bool, executionTimeMs int64, taskType TaskType) {
	if s.NodeHistory == nil {
		s.NodeHistory = make(map[string]*NodePerformanceHistory)
	}

	if _, exists := s.NodeHistory[nodeID]; !exists {
		s.NodeHistory[nodeID] = &NodePerformanceHistory{
			TaskTypePerformance:    make(map[string]float64),
			RecentCPUUtilization:   make([]float64, 0, 10),
			RecentMemUtilization:   make([]float64, 0, 10),
			RecentNetworkBandwidth: make([]float64, 0, 10),
			LastUpdated:            time.Now(),
		}
	}

	history := s.NodeHistory[nodeID]
	history.mutex.Lock()
	defer history.mutex.Unlock()

	// Update general performance metrics
	if success {
		history.SuccessCount++
	} else {
		history.FailureCount++
	}

	history.TotalExecutionMs += executionTimeMs
	totalTasks := history.SuccessCount + history.FailureCount
	if totalTasks > 0 {
		history.AvgExecutionTimeMs = float64(history.TotalExecutionMs) / float64(totalTasks)
	}

	// Update task type specific performance
	taskTypeStr := string(taskType)
	successRate := 0.0
	if success {
		// Incrementally update success rate with higher weight on recent outcomes
		oldRate, exists := history.TaskTypePerformance[taskTypeStr]
		if !exists {
			history.TaskTypePerformance[taskTypeStr] = 1.0
		} else {
			successRate = oldRate*0.9 + 0.1 // 90% old rate, 10% new result (1.0 for success)
			history.TaskTypePerformance[taskTypeStr] = successRate
		}
	} else {
		// Update with 0.0 for failure
		oldRate, exists := history.TaskTypePerformance[taskTypeStr]
		if !exists {
			history.TaskTypePerformance[taskTypeStr] = 0.0
		} else {
			successRate = oldRate*0.9 + 0.0 // 90% old rate, 10% new result (0.0 for failure)
			history.TaskTypePerformance[taskTypeStr] = successRate
		}
	}

	// Update utilization history if we have current node state
	if node, exists := s.FogNodes[nodeID]; exists {
		// Store utilization (keep only last 10 samples)
		if len(history.RecentCPUUtilization) >= 10 {
			history.RecentCPUUtilization = history.RecentCPUUtilization[1:]
		}
		history.RecentCPUUtilization = append(history.RecentCPUUtilization, node.CPUUtilization)

		if len(history.RecentMemUtilization) >= 10 {
			history.RecentMemUtilization = history.RecentMemUtilization[1:]
		}
		history.RecentMemUtilization = append(history.RecentMemUtilization, node.MemoryUtilization)

		if len(history.RecentNetworkBandwidth) >= 10 {
			history.RecentNetworkBandwidth = history.RecentNetworkBandwidth[1:]
		}
		history.RecentNetworkBandwidth = append(history.RecentNetworkBandwidth, node.NetworkBandwidth)
	}

	// Calculate reliability score based on recent performance
	// Simple weighted formula: 70% success rate + 30% execution time stability
	totalTasks = history.SuccessCount + history.FailureCount
	if totalTasks > 0 {
		successRate := float64(history.SuccessCount) / float64(totalTasks)

		// Time stability score (1.0 = perfectly stable)
		timeStability := 1.0
		if history.AvgExecutionTimeMs > 0 {
			// Calculate coefficient of variation (lower is better)
			// This is a simplified version - just using the last execution time
			deviation := math.Abs(float64(executionTimeMs) - history.AvgExecutionTimeMs)
			timeStability = math.Max(0, 1.0-deviation/history.AvgExecutionTimeMs)
		}

		history.ReliabilityScore = 0.7*successRate + 0.3*timeStability
	} else {
		history.ReliabilityScore = 0.5 // Neutral for no history
	}

	history.LastUpdated = time.Now()
}

// CalculateSystemMetrics computes global system metrics and balance scores
func (s *ExtendedSystemState) CalculateSystemMetrics() {
	nodeCount := len(s.FogNodes)
	if nodeCount == 0 {
		return
	}

	// Calculate global utilization averages
	var totalCPU, totalMem, totalNetwork float64
	for _, node := range s.FogNodes {
		totalCPU += node.CPUUtilization
		totalMem += node.MemoryUtilization
		totalNetwork += node.NetworkBandwidth
	}

	s.GlobalCPUUtilization = totalCPU / float64(nodeCount)
	s.GlobalMemUtilization = totalMem / float64(nodeCount)
	s.GlobalNetworkUtilization = totalNetwork / float64(nodeCount)

	// Calculate balance scores (standard deviation)
	var cpuVariance, memVariance float64
	for _, node := range s.FogNodes {
		cpuDiff := node.CPUUtilization - s.GlobalCPUUtilization
		memDiff := node.MemoryUtilization - s.GlobalMemUtilization

		cpuVariance += cpuDiff * cpuDiff
		memVariance += memDiff * memDiff
	}

	cpuVariance /= float64(nodeCount)
	memVariance /= float64(nodeCount)

	s.CPUBalanceScore = 1.0 - math.Sqrt(cpuVariance) // Higher is better (more balanced)
	s.MemoryBalanceScore = 1.0 - math.Sqrt(memVariance)

	// Set time-based features
	now := time.Now()
	s.TimeOfDayHour = now.Hour()
	s.DayOfWeek = int(now.Weekday())
	s.IsWeekend = s.DayOfWeek == 0 || s.DayOfWeek == 6 // Sunday or Saturday
}

// CalculateTrends determines whether resource usage is trending up or down
func (s *ExtendedSystemState) CalculateTrends() {
	// To calculate trends, we need historical data from node history
	if len(s.NodeHistory) == 0 {
		return
	}

	var cpuSlope, memSlope float64
	var sampleCount int

	// Calculate slope for each node and average them
	for _, history := range s.NodeHistory {
		history.mutex.RLock()

		cpuLen := len(history.RecentCPUUtilization)
		memLen := len(history.RecentMemUtilization)

		if cpuLen >= 3 { // Need at least 3 points for meaningful trend
			// Simple linear regression slope estimation
			// For simplicity, just compare newest vs oldest
			newest := history.RecentCPUUtilization[cpuLen-1]
			oldest := history.RecentCPUUtilization[0]
			cpuSlope += (newest - oldest) / float64(cpuLen-1)
		}

		if memLen >= 3 {
			newest := history.RecentMemUtilization[memLen-1]
			oldest := history.RecentMemUtilization[0]
			memSlope += (newest - oldest) / float64(memLen-1)
		}

		history.mutex.RUnlock()
		sampleCount++
	}

	// Average the slopes across nodes
	if sampleCount > 0 {
		s.CPUUtilizationTrend = cpuSlope / float64(sampleCount)
		s.MemUtilizationTrend = memSlope / float64(sampleCount)

		// Calculate request rate trend based on task counts
		var totalCurrent, totalPrevious int
		for nodeID, node := range s.FogNodes {
			totalCurrent += node.TaskCount

			// Use historical data if available
			if history, exists := s.NodeHistory[nodeID]; exists && len(history.RecentCPUUtilization) > 0 {
				// Assuming task count correlates with CPU, use trend as proxy
				if len(history.RecentCPUUtilization) >= 3 {
					totalPrevious++ // Simple increment for trend calculation
				}
			}
		}

		if totalPrevious > 0 {
			s.RequestRateTrend = float64(totalCurrent-totalPrevious) / float64(totalPrevious)
			// Normalize to similar range as other trends
			s.RequestRateTrend = math.Max(-1.0, math.Min(1.0, s.RequestRateTrend))
		}

	}
}

// ToExtendedState converts a basic SystemState to an ExtendedSystemState
func ToExtendedState(basic SystemState) *ExtendedSystemState {
	extended := &ExtendedSystemState{
		SystemState: basic,
		NodeHistory: make(map[string]*NodePerformanceHistory),
	}

	// Calculate system-wide metrics
	extended.CalculateSystemMetrics()

	return extended
}
