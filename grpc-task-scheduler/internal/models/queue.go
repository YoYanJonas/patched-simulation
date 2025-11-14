package models

import (
	"container/heap"
	"fmt"
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
	// Remove removes a task from the queue using cloudletId (unique instance identifier)
	// NOTE: The parameter is named taskID for interface compatibility, but implementations expect cloudletId
	Remove(taskID string) *TaskEntry  // taskID parameter is actually cloudletId (unique instance ID)
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
	// [DEBUG] Entry point for Enqueue
	fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-ENTRY] FIFOQueue.Enqueue() called for TaskID=%s\n", task.GetTaskID())
	
	// [DEBUG] About to acquire write lock
	fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-LOCK-BEFORE] About to acquire write lock for TaskID=%s\n", task.GetTaskID())
	q.mu.Lock()
	// [DEBUG] Write lock acquired
	fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-LOCK-ACQUIRED] Write lock acquired, queue size: %d\n", len(q.tasks))
	defer func() {
		// [DEBUG] About to release write lock
		fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-LOCK-RELEASE] Releasing write lock for TaskID=%s\n", task.GetTaskID())
		q.mu.Unlock()
		// [DEBUG] Write lock released
		fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-LOCK-RELEASED] Write lock released\n")
	}()

	// [DEBUG] Getting current queue size
	oldSize := len(q.tasks)
	fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-BEFORE] Queue size before enqueue: %d\n", oldSize)
	
	// [DEBUG] Setting task status
	task.Status = TaskStatusQueued
	task.QueuedAt = time.Now()
	fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-STATUS] Task %s status set to Queued, QueuedAt=%v\n", task.GetTaskID(), task.QueuedAt)
	
	// [DEBUG] About to append task
	fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-APPEND-BEFORE] About to append TaskID=%s to queue\n", task.GetTaskID())
	q.tasks = append(q.tasks, task)
	// [DEBUG] Task appended
	newSize := len(q.tasks)
	fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-APPEND-AFTER] TaskID=%s appended to queue (size: %d -> %d)\n", task.GetTaskID(), oldSize, newSize)
	
	// [DEBUG] Log enqueue operation
	fmt.Printf("[DEBUG] [QUEUE-ENQUEUE] Task %s enqueued successfully (queue size: %d -> %d)\n", 
		task.GetTaskID(), oldSize, newSize)
	fmt.Printf("[QUEUE-ENQUEUE] Task %s enqueued successfully (queue size: %d -> %d)\n", 
		task.GetTaskID(), oldSize, newSize)
	
	// [DEBUG] Enqueue complete
	fmt.Printf("[DEBUG] [QUEUE-ENQUEUE-EXIT] FIFOQueue.Enqueue() completed successfully for TaskID=%s\n", task.GetTaskID())
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

func (q *FIFOQueue) Remove(cloudletId string) *TaskEntry {
	// [DIAGNOSTIC] Entry point for Remove
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-ENTRY] FIFOQueue.Remove() called for cloudletId=%s\n", cloudletId)
	
	// [DIAGNOSTIC] About to acquire write lock
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-LOCK-BEFORE] About to acquire write lock for cloudletId=%s\n", cloudletId)
	q.mu.Lock()
	// [DIAGNOSTIC] Write lock acquired
	oldSize := len(q.tasks)
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-LOCK-ACQUIRED] Write lock acquired, queue size: %d\n", oldSize)
	
	// [DIAGNOSTIC] Log task IDs in queue before removal
	if oldSize > 0 {
		taskIds := make([]string, 0, oldSize)
		cloudletIds := make([]string, 0, oldSize)
		for _, task := range q.tasks {
			taskIds = append(taskIds, task.GetTaskID())
			cloudletIds = append(cloudletIds, task.GetCloudletId())
		}
		fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-BEFORE] Queue before removal: size=%d, taskIds=%v, cloudletIds=%v, searching for cloudletId=%s\n", 
			oldSize, taskIds, cloudletIds, cloudletId)
	}
	
	defer func() {
		// [DIAGNOSTIC] About to release write lock
		fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-LOCK-RELEASE] Releasing write lock for cloudletId=%s\n", cloudletId)
		q.mu.Unlock()
		// [DIAGNOSTIC] Write lock released
		newSize := len(q.tasks)
		fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-LOCK-RELEASED] Write lock released, queue size: %d\n", newSize)
	}()

	for i, task := range q.tasks {
		// CRITICAL FIX: Use cloudletId (unique instance identifier) instead of TaskId (pattern-based)
		// This ensures tasks are correctly removed when completion reports use cloudletId
		if task.GetCloudletId() == cloudletId {
			removed := q.tasks[i]
			q.tasks = append(q.tasks[:i], q.tasks[i+1:]...)
			newSize := len(q.tasks)
			fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-FOUND] Task found and removed: TaskID=%s, cloudletId=%s, queue size: %d -> %d\n", 
				removed.GetTaskID(), cloudletId, oldSize, newSize)
			fmt.Printf("[QUEUE-REMOVE] Task %s (cloudletId=%s) removed from queue (size: %d -> %d)\n", 
				removed.GetTaskID(), cloudletId, oldSize, newSize)
			return removed
		}
	}
	
	// [DIAGNOSTIC] Task not found
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-NOT-FOUND] Task with cloudletId=%s NOT FOUND in queue (size: %d)\n", cloudletId, oldSize)
	fmt.Printf("[QUEUE-REMOVE] Task with cloudletId=%s not found in queue (size: %d)\n", cloudletId, oldSize)
	return nil
}

func (q *FIFOQueue) GetAll() []*TaskEntry {
	// [DEBUG] Entry point for GetAll
	fmt.Printf("[DEBUG] [QUEUE-GETALL-ENTRY] FIFOQueue.GetAll() called\n")
	
	// [DEBUG] About to acquire read lock
	fmt.Printf("[DEBUG] [QUEUE-GETALL-LOCK-BEFORE] About to acquire RLock\n")
	q.mu.RLock()
	// [DEBUG] Read lock acquired
	fmt.Printf("[DEBUG] [QUEUE-GETALL-LOCK-ACQUIRED] RLock acquired, queue size: %d\n", len(q.tasks))
	defer func() {
		// [DEBUG] About to release read lock
		fmt.Printf("[DEBUG] [QUEUE-GETALL-LOCK-RELEASE] Releasing RLock\n")
		q.mu.RUnlock()
		// [DEBUG] Read lock released
		fmt.Printf("[DEBUG] [QUEUE-GETALL-LOCK-RELEASED] RLock released\n")
	}()

	// [DEBUG] Creating result slice
	result := make([]*TaskEntry, len(q.tasks))
	// [DEBUG] Copying tasks
	fmt.Printf("[DEBUG] [QUEUE-GETALL-COPY] Copying %d tasks to result slice\n", len(q.tasks))
	copy(result, q.tasks)
	// [DEBUG] Copy complete
	fmt.Printf("[DEBUG] [QUEUE-GETALL-EXIT] GetAll() returning %d tasks\n", len(result))
	return result
}

func (q *FIFOQueue) Clear() {
	// [DIAGNOSTIC] Entry point for Clear
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-ENTRY] FIFOQueue.Clear() called\n")
	
	// [DIAGNOSTIC] About to acquire write lock
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-LOCK-BEFORE] About to acquire write lock\n")
	q.mu.Lock()
	// [DIAGNOSTIC] Write lock acquired
	oldSize := len(q.tasks)
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-LOCK-ACQUIRED] Write lock acquired, queue size: %d\n", oldSize)
	
	// [DIAGNOSTIC] Log task IDs and cloudletIds before clearing
	if oldSize > 0 {
		taskIds := make([]string, 0, oldSize)
		cloudletIds := make([]string, 0, oldSize)
		for _, task := range q.tasks {
			taskIds = append(taskIds, task.GetTaskID())
			cloudletIds = append(cloudletIds, task.GetCloudletId())
		}
		fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-BEFORE] About to clear queue with %d tasks: taskIds=%v, cloudletIds=%v\n", 
			oldSize, taskIds, cloudletIds)
		fmt.Printf("[QUEUE-CLEAR-BEFORE] About to clear queue with %d tasks: %v\n", oldSize, taskIds)
	} else {
		fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-BEFORE] Queue is already empty (size: 0)\n")
	}
	
	defer func() {
		// [DIAGNOSTIC] About to release write lock
		fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-LOCK-RELEASE] Releasing write lock\n")
		q.mu.Unlock()
		// [DIAGNOSTIC] Write lock released
		newSize := len(q.tasks)
		fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-LOCK-RELEASED] Write lock released, queue size: %d\n", newSize)
	}()
	
	q.tasks = q.tasks[:0]
	newSize := len(q.tasks)
	// [DIAGNOSTIC] Log clear operation
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-AFTER] Queue cleared: size %d -> %d\n", oldSize, newSize)
	fmt.Printf("[QUEUE-CLEAR] Queue cleared (size: %d -> %d)\n", oldSize, newSize)
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

func (q *PriorityQueue) Remove(cloudletId string) *TaskEntry {
	// [DIAGNOSTIC] Entry point for Remove
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-ENTRY] PriorityQueue.Remove() called for cloudletId=%s\n", cloudletId)
	
	q.mu.Lock()
	oldSize := q.tasks.Len()
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-LOCK-ACQUIRED] PriorityQueue write lock acquired, queue size: %d\n", oldSize)
	
	// [DIAGNOSTIC] Log task IDs in queue before removal
	if oldSize > 0 {
		taskIds := make([]string, 0, oldSize)
		cloudletIds := make([]string, 0, oldSize)
		for _, task := range q.tasks {
			taskIds = append(taskIds, task.GetTaskID())
			cloudletIds = append(cloudletIds, task.GetCloudletId())
		}
		fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-BEFORE] PriorityQueue before removal: size=%d, taskIds=%v, cloudletIds=%v, searching for cloudletId=%s\n", 
			oldSize, taskIds, cloudletIds, cloudletId)
	}
	
	defer func() {
		newSize := q.tasks.Len()
		fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-LOCK-RELEASED] PriorityQueue write lock released, queue size: %d\n", newSize)
		q.mu.Unlock()
	}()

	for i, task := range q.tasks {
		// CRITICAL FIX: Use cloudletId (unique instance identifier) instead of TaskId (pattern-based)
		// This ensures tasks are correctly removed when completion reports use cloudletId
		if task.GetCloudletId() == cloudletId {
			removed := q.tasks[i]
			// Remove from heap efficiently
			lastIndex := len(q.tasks) - 1
			q.tasks[i] = q.tasks[lastIndex]
			q.tasks = q.tasks[:lastIndex]
			if i < len(q.tasks) {
				heap.Fix(&q.tasks, i)
			}
			newSize := q.tasks.Len()
			fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-FOUND] PriorityQueue task found and removed: TaskID=%s, cloudletId=%s, queue size: %d -> %d\n", 
				removed.GetTaskID(), cloudletId, oldSize, newSize)
			fmt.Printf("[QUEUE-REMOVE] PriorityQueue: Task %s (cloudletId=%s) removed (size: %d -> %d)\n", 
				removed.GetTaskID(), cloudletId, oldSize, newSize)
			return removed
		}
	}
	
	// [DIAGNOSTIC] Task not found
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-NOT-FOUND] PriorityQueue: Task with cloudletId=%s NOT FOUND (size: %d)\n", cloudletId, oldSize)
	fmt.Printf("[QUEUE-REMOVE] PriorityQueue: Task with cloudletId=%s not found (size: %d)\n", cloudletId, oldSize)
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
	// [DIAGNOSTIC] Entry point for Clear
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-ENTRY] PriorityQueue.Clear() called\n")
	
	q.mu.Lock()
	oldSize := q.tasks.Len()
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-LOCK-ACQUIRED] PriorityQueue write lock acquired, queue size: %d\n", oldSize)
	
	// [DIAGNOSTIC] Log task IDs before clearing
	if oldSize > 0 {
		taskIds := make([]string, 0, oldSize)
		cloudletIds := make([]string, 0, oldSize)
		for _, task := range q.tasks {
			taskIds = append(taskIds, task.GetTaskID())
			cloudletIds = append(cloudletIds, task.GetCloudletId())
		}
		fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-BEFORE] PriorityQueue: About to clear %d tasks: taskIds=%v, cloudletIds=%v\n", 
			oldSize, taskIds, cloudletIds)
		fmt.Printf("[QUEUE-CLEAR-BEFORE] PriorityQueue: About to clear %d tasks: %v\n", oldSize, taskIds)
	}
	
	defer func() {
		newSize := q.tasks.Len()
		fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-LOCK-RELEASED] PriorityQueue write lock released, queue size: %d\n", newSize)
		q.mu.Unlock()
	}()
	
	q.tasks = q.tasks[:0]
	heap.Init(&q.tasks)
	newSize := q.tasks.Len()
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-AFTER] PriorityQueue cleared: size %d -> %d\n", oldSize, newSize)
	fmt.Printf("[QUEUE-CLEAR] PriorityQueue cleared (size: %d -> %d)\n", oldSize, newSize)
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

func (q *SJFQueue) Remove(cloudletId string) *TaskEntry {
	// [DIAGNOSTIC] Entry point for Remove
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-ENTRY] SJFQueue.Remove() called for cloudletId=%s\n", cloudletId)
	
	q.mu.Lock()
	oldSize := len(q.tasks)
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-LOCK-ACQUIRED] SJFQueue write lock acquired, queue size: %d\n", oldSize)
	
	// [DIAGNOSTIC] Log task IDs in queue before removal
	if oldSize > 0 {
		taskIds := make([]string, 0, oldSize)
		cloudletIds := make([]string, 0, oldSize)
		for _, task := range q.tasks {
			taskIds = append(taskIds, task.GetTaskID())
			cloudletIds = append(cloudletIds, task.GetCloudletId())
		}
		fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-BEFORE] SJFQueue before removal: size=%d, taskIds=%v, cloudletIds=%v, searching for cloudletId=%s\n", 
			oldSize, taskIds, cloudletIds, cloudletId)
	}
	
	defer func() {
		newSize := len(q.tasks)
		fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-LOCK-RELEASED] SJFQueue write lock released, queue size: %d\n", newSize)
		q.mu.Unlock()
	}()

	for i, task := range q.tasks {
		// CRITICAL FIX: Use cloudletId (unique instance identifier) instead of TaskId (pattern-based)
		// This ensures tasks are correctly removed when completion reports use cloudletId
		if task.GetCloudletId() == cloudletId {
			removed := q.tasks[i]
			q.tasks = append(q.tasks[:i], q.tasks[i+1:]...)
			newSize := len(q.tasks)
			fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-FOUND] SJFQueue task found and removed: TaskID=%s, cloudletId=%s, queue size: %d -> %d\n", 
				removed.GetTaskID(), cloudletId, oldSize, newSize)
			fmt.Printf("[QUEUE-REMOVE] SJFQueue: Task %s (cloudletId=%s) removed (size: %d -> %d)\n", 
				removed.GetTaskID(), cloudletId, oldSize, newSize)
			return removed
		}
	}
	
	// [DIAGNOSTIC] Task not found
	fmt.Printf("[DIAGNOSTIC-QUEUE-REMOVE-NOT-FOUND] SJFQueue: Task with cloudletId=%s NOT FOUND (size: %d)\n", cloudletId, oldSize)
	fmt.Printf("[QUEUE-REMOVE] SJFQueue: Task with cloudletId=%s not found (size: %d)\n", cloudletId, oldSize)
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
	// [DIAGNOSTIC] Entry point for Clear
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-ENTRY] SJFQueue.Clear() called\n")
	
	q.mu.Lock()
	oldSize := len(q.tasks)
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-LOCK-ACQUIRED] SJFQueue write lock acquired, queue size: %d\n", oldSize)
	
	// [DIAGNOSTIC] Log task IDs before clearing
	if oldSize > 0 {
		taskIds := make([]string, 0, oldSize)
		cloudletIds := make([]string, 0, oldSize)
		for _, task := range q.tasks {
			taskIds = append(taskIds, task.GetTaskID())
			cloudletIds = append(cloudletIds, task.GetCloudletId())
		}
		fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-BEFORE] SJFQueue: About to clear %d tasks: taskIds=%v, cloudletIds=%v\n", 
			oldSize, taskIds, cloudletIds)
		fmt.Printf("[QUEUE-CLEAR-BEFORE] SJFQueue: About to clear %d tasks: %v\n", oldSize, taskIds)
	}
	
	defer func() {
		newSize := len(q.tasks)
		fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-LOCK-RELEASED] SJFQueue write lock released, queue size: %d\n", newSize)
		q.mu.Unlock()
	}()
	
	q.tasks = q.tasks[:0]
	newSize := len(q.tasks)
	fmt.Printf("[DIAGNOSTIC-QUEUE-CLEAR-AFTER] SJFQueue cleared: size %d -> %d\n", oldSize, newSize)
	fmt.Printf("[QUEUE-CLEAR] SJFQueue cleared (size: %d -> %d)\n", oldSize, newSize)
}
