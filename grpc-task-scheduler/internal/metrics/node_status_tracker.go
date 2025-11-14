package metrics

import (
	"sync"
	"time"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/logger"
)

// NodeStatusTracker accumulates and tracks node status from completion reports
// This enables state extraction during scheduling to use real CPU/Memory metrics
// instead of always returning 0.0 from nodeManager
type NodeStatusTracker struct {
	mu sync.RWMutex

	// Last received node status (most recent)
	lastCPUUtilization    float64
	lastMemoryUtilization float64
	lastUpdateTime        time.Time

	// Running averages (exponential moving average)
	avgCPUUtilization    float64
	avgMemoryUtilization float64

	// Configuration
	alpha float64 // Exponential moving average factor (0.0-1.0, higher = more weight to recent values)

	// Statistics
	updateCount int64
}

// NewNodeStatusTracker creates a new node status tracker
func NewNodeStatusTracker(alpha float64) *NodeStatusTracker {
	if alpha <= 0.0 || alpha > 1.0 {
		alpha = 0.3 // Default: 30% weight to new value, 70% to history
		logger.GetLogger().Warnf("[NODE-STATUS-TRACKER] Invalid alpha value, using default: 0.3")
	}

	return &NodeStatusTracker{
		alpha:                 alpha,
		lastCPUUtilization:    0.0,
		lastMemoryUtilization: 0.0,
		avgCPUUtilization:     0.0,
		avgMemoryUtilization:  0.0,
		lastUpdateTime:        time.Time{},
		updateCount:           0,
	}
}

// UpdateFromCompletionReport updates the tracker with node status from a completion report
func (nst *NodeStatusTracker) UpdateFromCompletionReport(nodeStatus *pb.FogNode) {
	if nodeStatus == nil {
		logger.GetLogger().Debugf("[NODE-STATUS-TRACKER] UpdateFromCompletionReport: nodeStatus is nil, skipping update")
		return
	}

	nst.mu.Lock()
	defer nst.mu.Unlock()

	// Extract CPU utilization (0-100 percentage to 0.0-1.0)
	var cpuUtil float64
	if nodeStatus.CurrentUsage != nil {
		cpuUtil = float64(nodeStatus.CurrentUsage.CpuUsage) / 100.0
		// Clamp to [0.0, 1.0]
		if cpuUtil < 0.0 {
			cpuUtil = 0.0
		}
		if cpuUtil > 1.0 {
			cpuUtil = 1.0
		}
	} else {
		logger.GetLogger().Warnf("[NODE-STATUS-TRACKER] UpdateFromCompletionReport: CurrentUsage is nil, using 0.0 for CPU")
		cpuUtil = 0.0
	}

	// Extract Memory utilization (actual MB used / total MB capacity)
	var memUtil float64
	if nodeStatus.CurrentUsage != nil && nodeStatus.Capacity != nil && nodeStatus.Capacity.MemoryMb > 0 {
		memUtil = float64(nodeStatus.CurrentUsage.MemoryUsageMb) / float64(nodeStatus.Capacity.MemoryMb)
		// Clamp to [0.0, 1.0]
		if memUtil < 0.0 {
			memUtil = 0.0
		}
		if memUtil > 1.0 {
			memUtil = 1.0
		}
	} else {
		logger.GetLogger().Warnf("[NODE-STATUS-TRACKER] UpdateFromCompletionReport: Missing CurrentUsage or Capacity, using 0.0 for Memory")
		memUtil = 0.0
	}

	// Update last received values
	nst.lastCPUUtilization = cpuUtil
	nst.lastMemoryUtilization = memUtil
	nst.lastUpdateTime = time.Now()
	nst.updateCount++

	// Update running averages using exponential moving average
	// EMA: new_avg = alpha * new_value + (1 - alpha) * old_avg
	if nst.updateCount == 1 {
		// First update: use the value directly
		nst.avgCPUUtilization = cpuUtil
		nst.avgMemoryUtilization = memUtil
		logger.GetLogger().Infof("[NODE-STATUS-TRACKER] First update: CPU=%.3f, Memory=%.3f (Node=%s)",
			cpuUtil, memUtil, nodeStatus.NodeId)
	} else {
		// Subsequent updates: exponential moving average
		oldAvgCPU := nst.avgCPUUtilization
		oldAvgMem := nst.avgMemoryUtilization
		nst.avgCPUUtilization = nst.alpha*cpuUtil + (1.0-nst.alpha)*oldAvgCPU
		nst.avgMemoryUtilization = nst.alpha*memUtil + (1.0-nst.alpha)*oldAvgMem
		logger.GetLogger().Debugf("[NODE-STATUS-TRACKER] Update #%d: CPU=%.3f->%.3f (avg), Memory=%.3f->%.3f (avg), Node=%s",
			nst.updateCount, cpuUtil, nst.avgCPUUtilization, memUtil, nst.avgMemoryUtilization, nodeStatus.NodeId)
	}

	logger.GetLogger().Infof("[NODE-STATUS-TRACKER] Updated from completion report: Node=%s, CPU=%.2f%% (avg=%.2f%%), Memory=%.2f%% (avg=%.2f%%), UpdateCount=%d",
		nodeStatus.NodeId, cpuUtil*100, nst.avgCPUUtilization*100, memUtil*100, nst.avgMemoryUtilization*100, nst.updateCount)
}

// GetAvgCPUUtilization returns the running average CPU utilization (0.0-1.0)
func (nst *NodeStatusTracker) GetAvgCPUUtilization() float64 {
	nst.mu.RLock()
	defer nst.mu.RUnlock()
	return nst.avgCPUUtilization
}

// GetAvgMemoryUtilization returns the running average memory utilization (0.0-1.0)
func (nst *NodeStatusTracker) GetAvgMemoryUtilization() float64 {
	nst.mu.RLock()
	defer nst.mu.RUnlock()
	return nst.avgMemoryUtilization
}

// GetLastCPUUtilization returns the most recent CPU utilization (0.0-1.0)
func (nst *NodeStatusTracker) GetLastCPUUtilization() float64 {
	nst.mu.RLock()
	defer nst.mu.RUnlock()
	return nst.lastCPUUtilization
}

// GetLastMemoryUtilization returns the most recent memory utilization (0.0-1.0)
func (nst *NodeStatusTracker) GetLastMemoryUtilization() float64 {
	nst.mu.RLock()
	defer nst.mu.RUnlock()
	return nst.lastMemoryUtilization
}

// GetSystemLoad returns the average of CPU and Memory utilization (0.0-1.0)
func (nst *NodeStatusTracker) GetSystemLoad() float64 {
	nst.mu.RLock()
	defer nst.mu.RUnlock()
	return (nst.avgCPUUtilization + nst.avgMemoryUtilization) / 2.0
}

// GetResourcePressure returns the maximum of CPU and Memory utilization (0.0-1.0)
func (nst *NodeStatusTracker) GetResourcePressure() float64 {
	nst.mu.RLock()
	defer nst.mu.RUnlock()
	if nst.avgCPUUtilization > nst.avgMemoryUtilization {
		return nst.avgCPUUtilization
	}
	return nst.avgMemoryUtilization
}

// GetUpdateCount returns the number of times the tracker has been updated
func (nst *NodeStatusTracker) GetUpdateCount() int64 {
	nst.mu.RLock()
	defer nst.mu.RUnlock()
	return nst.updateCount
}

// GetLastUpdateTime returns the time of the last update
func (nst *NodeStatusTracker) GetLastUpdateTime() time.Time {
	nst.mu.RLock()
	defer nst.mu.RUnlock()
	return nst.lastUpdateTime
}

// HasData returns true if the tracker has received at least one update
func (nst *NodeStatusTracker) HasData() bool {
	nst.mu.RLock()
	defer nst.mu.RUnlock()
	return nst.updateCount > 0
}

// GetStats returns statistics about the tracker
func (nst *NodeStatusTracker) GetStats() map[string]interface{} {
	nst.mu.RLock()
	defer nst.mu.RUnlock()

	return map[string]interface{}{
		"update_count":           nst.updateCount,
		"last_update_time":       nst.lastUpdateTime,
		"last_cpu_utilization":   nst.lastCPUUtilization,
		"last_memory_utilization": nst.lastMemoryUtilization,
		"avg_cpu_utilization":    nst.avgCPUUtilization,
		"avg_memory_utilization": nst.avgMemoryUtilization,
		"system_load":            (nst.avgCPUUtilization + nst.avgMemoryUtilization) / 2.0,
		"resource_pressure":       nst.getResourcePressureUnsafe(),
		"alpha":                  nst.alpha,
		"has_data":               nst.updateCount > 0,
	}
}

// getResourcePressureUnsafe returns resource pressure without locking (must be called with lock held)
func (nst *NodeStatusTracker) getResourcePressureUnsafe() float64 {
	if nst.avgCPUUtilization > nst.avgMemoryUtilization {
		return nst.avgCPUUtilization
	}
	return nst.avgMemoryUtilization
}

