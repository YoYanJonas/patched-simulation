package rl

import (
	"sync"
	"time"
)

// AlgorithmPerformance tracks performance metrics for an algorithm
type AlgorithmPerformance struct {
	Latency          float64   `json:"latency"`
	Throughput       float64   `json:"throughput"`
	ResourceEff      float64   `json:"resource_efficiency"`
	Fairness         float64   `json:"fairness"`
	DeadlineMiss     float64   `json:"deadline_miss"`
	EnergyEfficiency float64   `json:"energy_efficiency"`
	Timestamp        time.Time `json:"timestamp"`
}

// PerformanceRecord stores individual performance measurements with metrics
type PerformanceRecord struct {
	Metrics   map[string]float64 `json:"metrics"`
	Timestamp time.Time          `json:"timestamp"`
}

// EnhancedPerformanceTracker provides advanced performance tracking with persistence support
type EnhancedPerformanceTracker struct {
	mu                   sync.RWMutex
	algorithmPerformance map[AlgorithmType][]PerformanceRecord
	maxRecords           int
	totalTasksProcessed  int64
}

// NewEnhancedPerformanceTracker creates a new enhanced performance tracker
func NewEnhancedPerformanceTracker(maxRecords int) *EnhancedPerformanceTracker {
	return &EnhancedPerformanceTracker{
		algorithmPerformance: make(map[AlgorithmType][]PerformanceRecord),
		maxRecords:           maxRecords,
		totalTasksProcessed:  0,
	}
}

// RecordPerformance records performance metrics for an algorithm
func (ept *EnhancedPerformanceTracker) RecordPerformance(algorithmType AlgorithmType, metrics map[string]float64) {
	ept.mu.Lock()
	defer ept.mu.Unlock()

	record := PerformanceRecord{
		Metrics:   metrics,
		Timestamp: time.Now(),
	}

	// Add to algorithm performance history
	ept.algorithmPerformance[algorithmType] = append(ept.algorithmPerformance[algorithmType], record)

	// Maintain max records limit for memory efficiency
	if len(ept.algorithmPerformance[algorithmType]) > ept.maxRecords {
		ept.algorithmPerformance[algorithmType] = ept.algorithmPerformance[algorithmType][1:]
	}

	ept.totalTasksProcessed++
}

// GetPerformanceStats returns performance statistics for an algorithm
func (ept *EnhancedPerformanceTracker) GetPerformanceStats(algorithmType AlgorithmType) map[string]interface{} {
	ept.mu.RLock()
	defer ept.mu.RUnlock()

	records := ept.algorithmPerformance[algorithmType]
	if len(records) == 0 {
		return map[string]interface{}{
			"total_records": 0,
			"avg_latency":   0.0,
			"avg_throughput": 0.0,
		}
	}

	// Calculate performance metrics
	var totalLatency, totalThroughput float64
	for _, record := range records {
		if latency, exists := record.Metrics["latency"]; exists {
			totalLatency += latency
		}
		if throughput, exists := record.Metrics["throughput"]; exists {
			totalThroughput += throughput
		}
	}

	avgLatency := totalLatency / float64(len(records))
	avgThroughput := totalThroughput / float64(len(records))

	return map[string]interface{}{
		"total_records":   len(records),
		"avg_latency":     avgLatency,
		"avg_throughput":  avgThroughput,
		"total_processed": ept.totalTasksProcessed,
	}
}

// GetAlgorithmPerformance returns performance data for all algorithms
func (ept *EnhancedPerformanceTracker) GetAlgorithmPerformance() map[AlgorithmType][]PerformanceRecord {
	ept.mu.RLock()
	defer ept.mu.RUnlock()

	// Return a copy to prevent external modification
	result := make(map[AlgorithmType][]PerformanceRecord)
	for algType, records := range ept.algorithmPerformance {
		result[algType] = make([]PerformanceRecord, len(records))
		copy(result[algType], records)
	}
	return result
}

// ClearPerformanceData clears all performance data for memory management
func (ept *EnhancedPerformanceTracker) ClearPerformanceData() {
	ept.mu.Lock()
	defer ept.mu.Unlock()

	for algType := range ept.algorithmPerformance {
		ept.algorithmPerformance[algType] = ept.algorithmPerformance[algType][:0]
	}
	ept.totalTasksProcessed = 0
}

// GetBestAlgorithm returns the algorithm with the best performance
func (ept *EnhancedPerformanceTracker) GetBestAlgorithm() AlgorithmType {
	ept.mu.RLock()
	defer ept.mu.RUnlock()

	bestAlg := AlgorithmFCFS // default
	bestScore := 0.0

	for algType, records := range ept.algorithmPerformance {
		if len(records) == 0 {
			continue
		}

		// Calculate average performance score from recent records
		totalScore := 0.0
		count := 0

		// Use last 10 records or all if less than 10
		start := len(records) - 10
		if start < 0 {
			start = 0
		}

		for i := start; i < len(records); i++ {
			record := records[i]
			// Simple scoring: higher throughput + lower latency + higher efficiency
			throughput := record.Metrics["throughput"]
			latency := record.Metrics["avg_latency"]
			efficiency := record.Metrics["resource_efficiency"]

			score := throughput + (1.0 / (latency + 1.0)) + efficiency
			totalScore += score
			count++
		}

		if count > 0 {
			avgScore := totalScore / float64(count)
			if avgScore > bestScore {
				bestScore = avgScore
				bestAlg = algType
			}
		}
	}

	return bestAlg
}

// GetTotalTasksProcessed returns the total number of tasks processed
func (ept *EnhancedPerformanceTracker) GetTotalTasksProcessed() int64 {
	ept.mu.RLock()
	defer ept.mu.RUnlock()

	// Return the stored total tasks counter
	return ept.totalTasksProcessed
}

// SetTotalTasksProcessed sets the total number of tasks processed (for persistence restoration)
func (ept *EnhancedPerformanceTracker) SetTotalTasksProcessed(total int64) {
	ept.mu.Lock()
	defer ept.mu.Unlock()

	// Store the restored value from saved model
	ept.totalTasksProcessed = total
}

// GetPerformanceStats extracts performance statistics using RL interfaces
func GetPerformanceStats(nodeManager SingleNodeManager, tasks []TaskEntry) map[string]float64 {
	stats := make(map[string]float64)

	if nodeManager != nil {
		stats["cpu_utilization"] = nodeManager.GetCPUUtilization()
		stats["memory_utilization"] = nodeManager.GetMemoryUtilization()
		stats["queue_length"] = float64(nodeManager.GetQueueLength())
		stats["resource_efficiency"] = (stats["cpu_utilization"] + stats["memory_utilization"]) / 2.0
		stats["fairness"] = 0.8 // Default fairness score
		stats["throughput"] = float64(len(tasks))
		stats["avg_latency"] = 100.0              // Default latency
		stats["task_count"] = float64(len(tasks)) // Add task count for tracking
	}

	return stats
}

// GetPerformanceHistory returns performance history for an algorithm
func (ept *EnhancedPerformanceTracker) GetPerformanceHistory(algType AlgorithmType) []PerformanceRecord {
	ept.mu.RLock()
	defer ept.mu.RUnlock()

	if records, exists := ept.algorithmPerformance[algType]; exists {
		// Return a copy to prevent external modification
		result := make([]PerformanceRecord, len(records))
		copy(result, records)
		return result
	}

	return []PerformanceRecord{}
}

// GetStats returns overall performance tracker statistics
func (ept *EnhancedPerformanceTracker) GetStats() map[string]interface{} {
	ept.mu.RLock()
	defer ept.mu.RUnlock()

	stats := make(map[string]interface{})
	stats["total_tasks_processed"] = ept.totalTasksProcessed
	stats["max_records"] = ept.maxRecords
	stats["algorithms_tracked"] = len(ept.algorithmPerformance)

	algorithmStats := make(map[string]int)
	for algType, records := range ept.algorithmPerformance {
		algorithmStats[string(algType)] = len(records)
	}
	stats["algorithm_record_counts"] = algorithmStats

	return stats
}
