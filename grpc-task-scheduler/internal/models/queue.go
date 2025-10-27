package models

import (
	"container/heap"
	"sync"
	"time"
)

// TaskQueue interface for task-based scheduling
type TaskQueue interface {
	Enqueue(task *TaskEntry) error
	Dequeue() *TaskEntry
	Peek() *TaskEntry
	Size() int
	IsEmpty() bool
	Remove(taskID string) *TaskEntry
	GetAll() []*TaskEntry
	Clear()
}

// =============================================================================
// FIFO Queue - First In First Out (Task-based, not time-based)
// =============================================================================

type FIFOQueue struct {
	mu    sync.RWMutex
	tasks []*TaskEntry
}

func NewFIFOQueue() *FIFOQueue {
	return &FIFOQueue{
		tasks: make([]*TaskEntry, 0),
	}
}

func (q *FIFOQueue) Enqueue(task *TaskEntry) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	task.Status = TaskStatusQueued
	task.QueuedAt = time.Now()
	q.tasks = append(q.tasks, task)
	return nil
}

func (q *FIFOQueue) Dequeue() *TaskEntry {
	q.mu.Lock()
	defer q.mu.Unlock()

	if len(q.tasks) == 0 {
		return nil
	}

	task := q.tasks[0]
	q.tasks = q.tasks[1:]
	return task
}

func (q *FIFOQueue) Peek() *TaskEntry {
	q.mu.RLock()
	defer q.mu.RUnlock()

	if len(q.tasks) == 0 {
		return nil
	}
	return q.tasks[0]
}

func (q *FIFOQueue) Size() int {
	q.mu.RLock()
	defer q.mu.RUnlock()
	return len(q.tasks)
}

func (q *FIFOQueue) IsEmpty() bool {
	return q.Size() == 0
}

func (q *FIFOQueue) Remove(taskID string) *TaskEntry {
	q.mu.Lock()
	defer q.mu.Unlock()

	for i, task := range q.tasks {
		if task.GetTaskID() == taskID {
			removed := q.tasks[i]
			q.tasks = append(q.tasks[:i], q.tasks[i+1:]...)
			return removed
		}
	}
	return nil
}

func (q *FIFOQueue) GetAll() []*TaskEntry {
	q.mu.RLock()
	defer q.mu.RUnlock()

	result := make([]*TaskEntry, len(q.tasks))
	copy(result, q.tasks)
	return result
}

func (q *FIFOQueue) Clear() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.tasks = q.tasks[:0]
}

// =============================================================================
// Priority Queue - Highest Priority First (Task-based)
// =============================================================================

type PriorityQueue struct {
	mu    sync.RWMutex
	tasks PriorityHeap
}

type PriorityHeap []*TaskEntry

func (h PriorityHeap) Len() int { return len(h) }

func (h PriorityHeap) Less(i, j int) bool {
	// Higher priority number = higher priority (10 > 1)
	if h[i].Priority != h[j].Priority {
		return h[i].Priority > h[j].Priority
	}
	// Same priority: FIFO (first come, first served)
	return h[i].QueuedAt.Before(h[j].QueuedAt)
}

func (h PriorityHeap) Swap(i, j int) { h[i], h[j] = h[j], h[i] }

func (h *PriorityHeap) Push(x interface{}) {
	*h = append(*h, x.(*TaskEntry))
}

func (h *PriorityHeap) Pop() interface{} {
	old := *h
	n := len(old)
	task := old[n-1]
	*h = old[0 : n-1]
	return task
}

func NewPriorityQueue() *PriorityQueue {
	pq := &PriorityQueue{
		tasks: make(PriorityHeap, 0),
	}
	heap.Init(&pq.tasks)
	return pq
}

func (q *PriorityQueue) Enqueue(task *TaskEntry) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	task.Status = TaskStatusQueued
	task.QueuedAt = time.Now()
	heap.Push(&q.tasks, task)
	return nil
}

func (q *PriorityQueue) Dequeue() *TaskEntry {
	q.mu.Lock()
	defer q.mu.Unlock()

	if q.tasks.Len() == 0 {
		return nil
	}

	return heap.Pop(&q.tasks).(*TaskEntry)
}

func (q *PriorityQueue) Peek() *TaskEntry {
	q.mu.RLock()
	defer q.mu.RUnlock()

	if q.tasks.Len() == 0 {
		return nil
	}
	return q.tasks[0]
}

func (q *PriorityQueue) Size() int {
	q.mu.RLock()
	defer q.mu.RUnlock()
	return q.tasks.Len()
}

func (q *PriorityQueue) IsEmpty() bool {
	return q.Size() == 0
}

func (q *PriorityQueue) Remove(taskID string) *TaskEntry {
	q.mu.Lock()
	defer q.mu.Unlock()

	for i, task := range q.tasks {
		if task.GetTaskID() == taskID {
			removed := q.tasks[i]
			// Remove from heap efficiently
			lastIndex := len(q.tasks) - 1
			q.tasks[i] = q.tasks[lastIndex]
			q.tasks = q.tasks[:lastIndex]
			if i < len(q.tasks) {
				heap.Fix(&q.tasks, i)
			}
			return removed
		}
	}
	return nil
}

func (q *PriorityQueue) GetAll() []*TaskEntry {
	q.mu.RLock()
	defer q.mu.RUnlock()

	result := make([]*TaskEntry, len(q.tasks))
	copy(result, q.tasks)
	return result
}

func (q *PriorityQueue) Clear() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.tasks = q.tasks[:0]
	heap.Init(&q.tasks)
}

// =============================================================================
// SJF Queue - Shortest Job First (Task-based)
// =============================================================================

type SJFQueue struct {
	mu    sync.RWMutex
	tasks []*TaskEntry
}

func NewSJFQueue() *SJFQueue {
	return &SJFQueue{
		tasks: make([]*TaskEntry, 0),
	}
}

func (q *SJFQueue) Enqueue(task *TaskEntry) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	task.Status = TaskStatusQueued
	task.QueuedAt = time.Now()

	// Insert in sorted order by execution time (shortest first)
	inserted := false
	for i, existingTask := range q.tasks {
		if task.Task.ExecutionTime < existingTask.Task.ExecutionTime {
			// Insert at position i
			q.tasks = append(q.tasks[:i], append([]*TaskEntry{task}, q.tasks[i:]...)...)
			inserted = true
			break
		} else if task.Task.ExecutionTime == existingTask.Task.ExecutionTime {
			// Same execution time: use FIFO (first come, first served)
			if task.QueuedAt.Before(existingTask.QueuedAt) {
				q.tasks = append(q.tasks[:i], append([]*TaskEntry{task}, q.tasks[i:]...)...)
				inserted = true
				break
			}
		}
	}

	if !inserted {
		q.tasks = append(q.tasks, task) // Longest job, add to end
	}

	return nil
}

func (q *SJFQueue) Dequeue() *TaskEntry {
	q.mu.Lock()
	defer q.mu.Unlock()

	if len(q.tasks) == 0 {
		return nil
	}

	task := q.tasks[0] // Always shortest job first
	q.tasks = q.tasks[1:]
	return task
}

func (q *SJFQueue) Peek() *TaskEntry {
	q.mu.RLock()
	defer q.mu.RUnlock()

	if len(q.tasks) == 0 {
		return nil
	}
	return q.tasks[0]
}

func (q *SJFQueue) Size() int {
	q.mu.RLock()
	defer q.mu.RUnlock()
	return len(q.tasks)
}

func (q *SJFQueue) IsEmpty() bool {
	return q.Size() == 0
}

func (q *SJFQueue) Remove(taskID string) *TaskEntry {
	q.mu.Lock()
	defer q.mu.Unlock()

	for i, task := range q.tasks {
		if task.GetTaskID() == taskID {
			removed := q.tasks[i]
			q.tasks = append(q.tasks[:i], q.tasks[i+1:]...)
			return removed
		}
	}
	return nil
}

func (q *SJFQueue) GetAll() []*TaskEntry {
	q.mu.RLock()
	defer q.mu.RUnlock()

	result := make([]*TaskEntry, len(q.tasks))
	copy(result, q.tasks)
	return result
}

func (q *SJFQueue) Clear() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.tasks = q.tasks[:0]
}
