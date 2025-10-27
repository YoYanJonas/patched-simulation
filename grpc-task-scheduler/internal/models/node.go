package models

import (
	"fmt"
	"sync"
	"time"

	pb "scheduler-grpc-server/api/proto"
)

// SingleNodeManager manages the state and resources of a single fog node
type SingleNodeManager struct {
	mu             sync.RWMutex
	NodeID         string               `json:"node_id"`
	NodeName       string               `json:"node_name"`
	Capacity       *pb.ResourceCapacity `json:"capacity"`
	CurrentUsage   *pb.ResourceUsage    `json:"current_usage"`
	Status         pb.NodeStatus        `json:"status"`
	LastUpdated    time.Time            `json:"last_updated"`
	TasksProcessed int64                `json:"tasks_processed"`
	TasksCompleted int64                `json:"tasks_completed"`
	TasksFailed    int64                `json:"tasks_failed"`
	AverageLoad    float64              `json:"average_load"`
	
	// Queue reference for real queue length access
	queue TaskQueue `json:"-"`
}

// NewSingleNodeManager creates a new single node manager
func NewSingleNodeManager(nodeID, nodeName string) *SingleNodeManager {
	return &SingleNodeManager{
		NodeID:      nodeID,
		NodeName:    nodeName,
		Status:      pb.NodeStatus_NODE_STATUS_ACTIVE,
		LastUpdated: time.Now(),
		Capacity: &pb.ResourceCapacity{
			CpuCores:             4, // Default values
			MemoryMb:             8192,
			StorageGb:            100,
			NetworkBandwidthMbps: 1000,
		},
		CurrentUsage: &pb.ResourceUsage{
			CpuUsage:         0,
			MemoryUsageMb:    0,
			StorageUsageGb:   0,
			NetworkUsageMbps: 0,
		},
	}
}

// GetNodeInfo returns current node information
func (nm *SingleNodeManager) GetNodeInfo() *pb.FogNode {
	nm.mu.RLock()
	defer nm.mu.RUnlock()

	return &pb.FogNode{
		NodeId:       nm.NodeID,
		NodeName:     nm.NodeName,
		Status:       nm.Status,
		Capacity:     nm.Capacity,
		CurrentUsage: nm.CurrentUsage,
	}
}

// HasCapacityForTask checks if node can handle the given task
func (nm *SingleNodeManager) HasCapacityForTask(task *pb.Task) bool {
	nm.mu.RLock()
	defer nm.mu.RUnlock()

	if nm.Status != pb.NodeStatus_NODE_STATUS_ACTIVE {
		return false
	}

	// Check CPU capacity
	cpuUsagePercent := float64(nm.CurrentUsage.CpuUsage)
	availableCPU := float64(nm.Capacity.CpuCores) * (100 - cpuUsagePercent) / 100
	if availableCPU < float64(task.CpuRequirement) {
		return false
	}

	// Check memory capacity
	availableMemory := nm.Capacity.MemoryMb - nm.CurrentUsage.MemoryUsageMb
	if availableMemory < task.MemoryRequirement {
		return false
	}

	return true
}

// AllocateResources allocates resources for a task
func (nm *SingleNodeManager) AllocateResources(task *pb.Task) error {
	nm.mu.Lock()
	defer nm.mu.Unlock()

	if !nm.hasCapacityForTaskUnsafe(task) {
		return ErrInsufficientResources
	}

	// Allocate CPU (convert task CPU requirement to usage percentage)
	cpuPercentage := (float64(task.CpuRequirement) / float64(nm.Capacity.CpuCores)) * 100
	nm.CurrentUsage.CpuUsage += int64(cpuPercentage)

	// Allocate memory
	nm.CurrentUsage.MemoryUsageMb += task.MemoryRequirement

	nm.LastUpdated = time.Now()
	return nil
}

// ReleaseResources releases resources after task completion
func (nm *SingleNodeManager) ReleaseResources(task *pb.Task) {
	nm.mu.Lock()
	defer nm.mu.Unlock()

	// Release CPU
	cpuPercentage := (float64(task.CpuRequirement) / float64(nm.Capacity.CpuCores)) * 100
	nm.CurrentUsage.CpuUsage -= int64(cpuPercentage)
	if nm.CurrentUsage.CpuUsage < 0 {
		nm.CurrentUsage.CpuUsage = 0
	}

	// Release memory
	nm.CurrentUsage.MemoryUsageMb -= task.MemoryRequirement
	if nm.CurrentUsage.MemoryUsageMb < 0 {
		nm.CurrentUsage.MemoryUsageMb = 0
	}

	nm.LastUpdated = time.Now()
}

// UpdateTaskStats updates task processing statistics
func (nm *SingleNodeManager) UpdateTaskStats(completed bool) {
	nm.mu.Lock()
	defer nm.mu.Unlock()

	nm.TasksProcessed++
	if completed {
		nm.TasksCompleted++
	} else {
		nm.TasksFailed++
	}

	// Update average load (simple exponential moving average)
	currentLoad := nm.getCurrentLoadUnsafe()
	if nm.AverageLoad == 0 {
		nm.AverageLoad = currentLoad
	} else {
		nm.AverageLoad = 0.9*nm.AverageLoad + 0.1*currentLoad
	}

	nm.LastUpdated = time.Now()
}

// GetCurrentLoad returns current resource utilization percentage
func (nm *SingleNodeManager) GetCurrentLoad() float64 {
	nm.mu.RLock()
	defer nm.mu.RUnlock()
	return nm.getCurrentLoadUnsafe()
}

// GetStats returns node statistics
func (nm *SingleNodeManager) GetStats() map[string]interface{} {
	nm.mu.RLock()
	defer nm.mu.RUnlock()

	return map[string]interface{}{
		"node_id":         nm.NodeID,
		"status":          nm.Status.String(),
		"tasks_processed": nm.TasksProcessed,
		"tasks_completed": nm.TasksCompleted,
		"tasks_failed":    nm.TasksFailed,
		"success_rate":    nm.getSuccessRateUnsafe(),
		"current_load":    nm.getCurrentLoadUnsafe(),
		"average_load":    nm.AverageLoad,
		"cpu_usage":       nm.CurrentUsage.CpuUsage,
		"memory_usage_mb": nm.CurrentUsage.MemoryUsageMb,
		"last_updated":    nm.LastUpdated,
	}
}

// Private helper methods
func (nm *SingleNodeManager) hasCapacityForTaskUnsafe(task *pb.Task) bool {
	if nm.Status != pb.NodeStatus_NODE_STATUS_ACTIVE {
		return false
	}

	// Check CPU capacity
	cpuUsagePercent := float64(nm.CurrentUsage.CpuUsage)
	availableCPU := float64(nm.Capacity.CpuCores) * (100 - cpuUsagePercent) / 100
	if availableCPU < float64(task.CpuRequirement) {
		return false
	}

	// Check memory capacity
	availableMemory := nm.Capacity.MemoryMb - nm.CurrentUsage.MemoryUsageMb
	if availableMemory < task.MemoryRequirement {
		return false
	}

	return true
}

func (nm *SingleNodeManager) getCurrentLoadUnsafe() float64 {
	cpuLoad := float64(nm.CurrentUsage.CpuUsage) / 100.0
	memoryLoad := float64(nm.CurrentUsage.MemoryUsageMb) / float64(nm.Capacity.MemoryMb)

	// Return the maximum of CPU and memory load
	if cpuLoad > memoryLoad {
		return cpuLoad
	}
	return memoryLoad
}

func (nm *SingleNodeManager) getSuccessRateUnsafe() float64 {
	if nm.TasksProcessed == 0 {
		return 0.0
	}
	return (float64(nm.TasksCompleted) / float64(nm.TasksProcessed)) * 100.0
}

// Common errors
var (
	ErrInsufficientResources = fmt.Errorf("insufficient resources")
	ErrNodeNotActive         = fmt.Errorf("node is not active")
)

// GetCPUUtilization returns current CPU utilization as percentage
func (nm *SingleNodeManager) GetCPUUtilization() float64 {
	nm.mu.RLock()
	defer nm.mu.RUnlock()
	return float64(nm.CurrentUsage.CpuUsage)
}

// GetMemoryUtilization returns current memory utilization as percentage
func (nm *SingleNodeManager) GetMemoryUtilization() float64 {
	nm.mu.RLock()
	defer nm.mu.RUnlock()
	if nm.Capacity.MemoryMb == 0 {
		return 0
	}
	return (float64(nm.CurrentUsage.MemoryUsageMb) / float64(nm.Capacity.MemoryMb)) * 100
}

// SetQueue sets the queue reference for real queue length access
func (nm *SingleNodeManager) SetQueue(queue TaskQueue) {
	nm.mu.Lock()
	defer nm.mu.Unlock()
	nm.queue = queue
}

// GetQueueLength returns the current queue length
func (nm *SingleNodeManager) GetQueueLength() int {
	nm.mu.RLock()
	defer nm.mu.RUnlock()
	
	if nm.queue == nil {
		return 0 // No queue reference available
	}
	
	return nm.queue.Size()
}

// GetNodeID returns the node ID (already exists but confirming interface compliance)
func (nm *SingleNodeManager) GetNodeID() string {
	nm.mu.RLock()
	defer nm.mu.RUnlock()
	return nm.NodeID
}

// GetAvailableCPU returns available CPU cores
func (nm *SingleNodeManager) GetAvailableCPU() float64 {
	nm.mu.RLock()
	defer nm.mu.RUnlock()

	usedPercentage := float64(nm.CurrentUsage.CpuUsage)
	totalCores := float64(nm.Capacity.CpuCores)
	return totalCores * (100.0 - usedPercentage) / 100.0
}

// GetAvailableMemory returns available memory in MB
func (nm *SingleNodeManager) GetAvailableMemory() int64 {
	nm.mu.RLock()
	defer nm.mu.RUnlock()
	return nm.Capacity.MemoryMb - nm.CurrentUsage.MemoryUsageMb
}
