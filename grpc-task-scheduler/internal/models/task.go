package models

import (
	"fmt"
	"time"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/internal/rl"
	"scheduler-grpc-server/pkg/logger"
)

// TaskStatus represents the current status of a task in the queue
type TaskStatus int32

const (
	TaskStatusPending TaskStatus = iota
	TaskStatusQueued
)

// TaskEntry represents a task in the scheduler's queue
type TaskEntry struct {
	Task         *pb.Task   `json:"task"`
	Status       TaskStatus `json:"status"`
	QueuedAt     time.Time  `json:"queued_at"`
	ArrivalTime  time.Time  `json:"arrival_time"`
	Priority     int32      `json:"priority"`
	EstimatedEnd time.Time  `json:"estimated_end"`
	
	// Cache information (stored separately, will be added to Task metadata when returning)
	IsCached   bool              `json:"is_cached"`
	CacheKey   string            `json:"cache_key"`
	CacheAction pb.CacheAction   `json:"cache_action"`
	
	// Cache RL agent state (for delayed reward calculation)
	CacheState *rl.CacheStateFeatures `json:"cache_state,omitempty"` // State when cache decision was made
	CacheRLAction *rl.Action           `json:"cache_rl_action,omitempty"` // RL action taken
}

// NewTaskEntry creates a new task entry from a protobuf task
func NewTaskEntry(task *pb.Task) *TaskEntry {
	now := time.Now()
	estimatedDuration := time.Duration(task.ExecutionTime) * time.Millisecond

	return &TaskEntry{
		Task:         task,
		Status:       TaskStatusPending,
		QueuedAt:     now,
		ArrivalTime:  now,
		Priority:     task.Priority,
		EstimatedEnd: now.Add(estimatedDuration),
	}
}

// GetTaskID returns the task ID (pattern-based, may be reused for repeated tasks)
func (te *TaskEntry) GetTaskID() string {
	if te.Task == nil {
		return ""
	}
	return te.Task.TaskId
}

// GetCloudletId returns the cloudletId from task metadata (unique instance identifier)
// This is the unique identifier assigned by CloudSim for each task instance
func (te *TaskEntry) GetCloudletId() string {
	if te.Task == nil || te.Task.Metadata == nil {
		return ""
	}
	if cid, ok := te.Task.Metadata["cloudlet_id"]; ok && cid != "" {
		return cid
	}
	return ""
}

// GetEstimatedDuration returns the estimated execution duration
func (te *TaskEntry) GetEstimatedDuration() time.Duration {
	if te.Task == nil {
		return 0
	}
	return time.Duration(te.Task.ExecutionTime) * time.Millisecond
}

// GetWaitTime returns how long the task has been waiting in the queue
func (te *TaskEntry) GetWaitTime() time.Duration {
	return time.Since(te.QueuedAt)
}

// IsExpired checks if task has exceeded its estimated completion time
func (te *TaskEntry) IsExpired() bool {
	return time.Now().After(te.EstimatedEnd)
}

// ValidateTask validates task requirements
func ValidateTask(task *pb.Task) error {
	if task == nil {
		return fmt.Errorf("task cannot be nil")
	}

	if task.TaskId == "" {
		return fmt.Errorf("task_id cannot be empty")
	}

	if task.CpuRequirement < 0 {
		return fmt.Errorf("cpu_requirement cannot be negative")
	}

	if task.MemoryRequirement < 0 {
		return fmt.Errorf("memory_requirement cannot be negative")
	}

	if task.ExecutionTime <= 0 {
		return fmt.Errorf("execution_time must be positive")
	}

	if task.Priority < 1 || task.Priority > 10 {
		return fmt.Errorf("priority must be between 1 and 10")
	}

	return nil
}

// TaskComparator defines comparison functions for different scheduling algorithms
type TaskComparator func(a, b *TaskEntry) bool

// Comparators for different scheduling strategies
var (
	// ByPriority sorts by priority (higher priority first)
	ByPriority TaskComparator = func(a, b *TaskEntry) bool {
		return a.Priority > b.Priority
	}

	// ByShortestJob sorts by execution time (shortest first)
	ByShortestJob TaskComparator = func(a, b *TaskEntry) bool {
		return a.Task.ExecutionTime < b.Task.ExecutionTime
	}

	// ByArrivalTime sorts by arrival time (FIFO)
	ByArrivalTime TaskComparator = func(a, b *TaskEntry) bool {
		return a.QueuedAt.Before(b.QueuedAt)
	}

	// Later Feature: deadline-aware disabled (behaves like FIFO)
	ByDeadline TaskComparator = func(a, b *TaskEntry) bool {
		return a.QueuedAt.Before(b.QueuedAt) // FIFO instead of deadline
	}
)

// Interface methods for RL action.go compatibility
func (te *TaskEntry) GetPriority() int32 {
	return te.Priority
}

func (te *TaskEntry) GetExecutionTimeMs() int64 {
	if te.Task == nil {
		return 0
	}
	return te.Task.ExecutionTime
}

// Later Feature: deadline-aware disabled
func (te *TaskEntry) GetDeadline() int64 {
	// [DEBUG] Verify deadline always returns 0
	if te.Task != nil && te.Task.Deadline != 0 {
		logger.GetLogger().Warnf("[DEADLINE-DISABLED] Task %s has non-zero deadline %d, but GetDeadline() returns 0", te.Task.TaskId, te.Task.Deadline)
	}
	return 0 // Deadline-aware disabled
}

func (te *TaskEntry) GetCPURequirement() float64 {
	if te.Task == nil {
		return 0
	}
	return float64(te.Task.CpuRequirement)
}

func (te *TaskEntry) GetMemoryRequirement() int64 {
	if te.Task == nil {
		return 0
	}
	return te.Task.MemoryRequirement
}

func (te *TaskEntry) GetArrivalTime() time.Time {
	return te.ArrivalTime
}
