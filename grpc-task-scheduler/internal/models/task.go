package models

import (
	"fmt"
	"time"

	pb "scheduler-grpc-server/api/proto"
)

// TaskStatus represents the current status of a task
type TaskStatus int32

const (
	TaskStatusPending TaskStatus = iota
	TaskStatusQueued
	TaskStatusRunning
	TaskStatusCompleted
	TaskStatusFailed
	TaskStatusTimeout
)

// TaskEntry represents a task in the scheduler's queue
type TaskEntry struct {
	Task         *pb.Task   `json:"task"`
	Status       TaskStatus `json:"status"`
	QueuedAt     time.Time  `json:"queued_at"`
	ArrivalTime  time.Time  `json:"arrival_time"`
	StartedAt    *time.Time `json:"started_at,omitempty"`
	CompletedAt  *time.Time `json:"completed_at,omitempty"`
	Priority     int32      `json:"priority"`
	EstimatedEnd time.Time  `json:"estimated_end"`
	ActualEnd    *time.Time `json:"actual_end,omitempty"`
	ErrorMessage string     `json:"error_message,omitempty"`
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

// GetTaskID returns the task ID
func (te *TaskEntry) GetTaskID() string {
	if te.Task == nil {
		return ""
	}
	return te.Task.TaskId
}

// GetEstimatedDuration returns the estimated execution duration
func (te *TaskEntry) GetEstimatedDuration() time.Duration {
	if te.Task == nil {
		return 0
	}
	return time.Duration(te.Task.ExecutionTime) * time.Millisecond
}

// GetWaitTime returns how long the task has been waiting
func (te *TaskEntry) GetWaitTime() time.Duration {
	if te.StartedAt != nil {
		return te.StartedAt.Sub(te.QueuedAt)
	}
	return time.Since(te.QueuedAt)
}

// GetExecutionTime returns actual execution time if completed
func (te *TaskEntry) GetExecutionTime() time.Duration {
	if te.StartedAt == nil || te.CompletedAt == nil {
		return 0
	}
	return te.CompletedAt.Sub(*te.StartedAt)
}

// MarkStarted marks the task as started
func (te *TaskEntry) MarkStarted() {
	now := time.Now()
	te.StartedAt = &now
	te.Status = TaskStatusRunning
}

// MarkCompleted marks the task as completed
func (te *TaskEntry) MarkCompleted() {
	now := time.Now()
	te.CompletedAt = &now
	te.ActualEnd = &now
	te.Status = TaskStatusCompleted
}

// MarkFailed marks the task as failed
func (te *TaskEntry) MarkFailed(reason string) {
	now := time.Now()
	te.CompletedAt = &now
	te.ActualEnd = &now
	te.Status = TaskStatusFailed
	te.ErrorMessage = reason
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

	// ByDeadline sorts by estimated end time
	ByDeadline TaskComparator = func(a, b *TaskEntry) bool {
		return a.EstimatedEnd.Before(b.EstimatedEnd)
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

func (te *TaskEntry) GetDeadline() int64 {
	if te.Task == nil {
		return 0
	}
	return te.Task.Deadline
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
