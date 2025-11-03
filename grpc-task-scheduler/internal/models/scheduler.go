package models

import (
	"context"
	"fmt"
	"sync"
	"time"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/internal/rl"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
)

type SchedulerEngine struct {
	mu                 sync.RWMutex
	queue              TaskQueue
	nodeManager        *SingleNodeManager
	scheduledTasks     map[string]*TaskEntry  // Track scheduled tasks for delayed rewards
	maxConcurrentTasks int
	algorithm          pb.SchedulingAlgorithm
	objective          pb.ObjectiveFunction

	// Agent integration
	agent        *rl.Agent
	cacheManager *TaskCacheManager
	config       *config.Config

	// Statistics
	totalTasksProcessed int64
	totalTasksCompleted int64
	totalTasksFailed    int64
	totalWaitTime       time.Duration
	totalExecutionTime  time.Duration

	// Control channels
	stopChan chan struct{}
	
	// Periodic resorting
	resortTicker *time.Ticker
	resortChan   chan struct{}
}

// REMOVED: TaskResult struct - No task execution in scheduler

func NewSchedulerEngine(nodeID string, algorithm pb.SchedulingAlgorithm, cfg *config.Config) *SchedulerEngine {
	var queue TaskQueue

	switch algorithm {
	case pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_PRIORITY:
		queue = NewPriorityQueue()
	case pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_SHORTEST_JOB_FIRST:
		queue = NewSJFQueue()
	case pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_FIFO:
		queue = NewFIFOQueue()
	default:
		queue = NewFIFOQueue()
	}

	engine := &SchedulerEngine{
		queue:              queue,
		nodeManager:        NewSingleNodeManager(nodeID, nodeID+"_node"),
		scheduledTasks:     make(map[string]*TaskEntry),  // Initialize scheduled tasks tracking
		maxConcurrentTasks: 10,
		algorithm:          algorithm,
		objective:          pb.ObjectiveFunction_OBJECTIVE_FUNCTION_BALANCE_LOAD,
		config:             cfg,
		stopChan:           make(chan struct{}),
		resortChan:         make(chan struct{}),
	}
	
	// Set queue reference in node manager for real queue length access
	engine.nodeManager.SetQueue(queue)

	// Initialize Cache Manager
	engine.cacheManager = NewTaskCacheManager(cfg.Caching)

	// Initialize Agent if RL is enabled
	if cfg.RL.Enabled {
		agentConfig := rl.AgentConfig{
			AlgorithmManagerConfig: cfg.AlgorithmManager,
		}
		engine.agent = rl.NewAgent(agentConfig)

		// Note: Cache manager no longer used in scheduling RL (cache has its own agent)
	}

	return engine
}

// setCacheManagerInRLAlgorithms removed - cache features no longer used in scheduling RL

func (se *SchedulerEngine) Start(ctx context.Context) {
	// Start periodic queue resorting if enabled
	if se.config.Queue.EnablePeriodicResort {
		se.startPeriodicQueueResorting()
	}
}

func (se *SchedulerEngine) Stop() {
	close(se.stopChan)
	if se.resortTicker != nil {
		se.resortTicker.Stop()
	}
	close(se.resortChan)
}

// startPeriodicQueueResorting starts the periodic queue resorting
func (se *SchedulerEngine) startPeriodicQueueResorting() {
	resortInterval := time.Duration(se.config.Queue.ResortIntervalMs) * time.Millisecond
	se.resortTicker = time.NewTicker(resortInterval)
	
	go func() {
		for {
			select {
			case <-se.resortTicker.C:
				se.resortQueue()
			case <-se.stopChan:
				return
			}
		}
	}()
	
	logger.GetLogger().Infof("Periodic queue resorting started with interval: %v", resortInterval)
}

// resortQueue resorts the queue based on algorithm type
func (se *SchedulerEngine) resortQueue() {
	se.mu.Lock()
	defer se.mu.Unlock()
	
	if se.queue.Size() <= 1 {
		return // No need to resort single or empty queue
	}
	
	// Get all tasks from queue
	allTasks := se.queue.GetAll()
	if len(allTasks) <= 1 {
		return
	}
	
	// Convert to TaskEntry slice for sorting
	taskEntries := make([]rl.TaskEntry, len(allTasks))
	for i, task := range allTasks {
		taskEntries[i] = task
	}
	
	var sortedTasks []rl.TaskEntry
	
	if se.agent != nil && se.agent.IsEnabled() {
		// RL-based resorting with multi-objective configuration
		sortedTasks = se.sortQueueWithObjectives(taskEntries)
		logger.GetLogger().Debugf("Queue resorted using RL algorithm with multi-objective: %d tasks", len(sortedTasks))
	} else {
		// Traditional algorithm resorting
		sortedTasks = se.resortQueueTraditional(taskEntries)
		logger.GetLogger().Debugf("Queue resorted using traditional algorithm: %d tasks", len(sortedTasks))
	}
	
	// Update queue with sorted tasks
	se.updateQueueWithSortedTasks(sortedTasks)
}

// resortQueueTraditional resorts queue using traditional algorithms
func (se *SchedulerEngine) resortQueueTraditional(tasks []rl.TaskEntry) []rl.TaskEntry {
	if len(tasks) <= 1 {
		return tasks
	}
	
	switch se.algorithm {
	case pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_PRIORITY:
		return se.sortByPriority(tasks)
	case pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_SHORTEST_JOB_FIRST:
		return se.sortByShortestJob(tasks)
	case pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_FIFO:
		return tasks // FIFO doesn't need resorting
	default:
		return tasks
	}
}

// updateQueueWithSortedTasks updates the queue with sorted tasks
func (se *SchedulerEngine) updateQueueWithSortedTasks(sortedTasks []rl.TaskEntry) {
	// Clear current queue
	se.queue.Clear()
	
	// Add sorted tasks back to queue
	for _, task := range sortedTasks {
		// Convert back to TaskEntry and enqueue
		if taskEntry, ok := task.(*TaskEntry); ok {
			se.queue.Enqueue(taskEntry)
		}
	}
}

// Helper sorting methods for traditional algorithms
func (se *SchedulerEngine) sortByPriority(tasks []rl.TaskEntry) []rl.TaskEntry {
	// Simple bubble sort by priority
	n := len(tasks)
	for i := 0; i < n-1; i++ {
		for j := 0; j < n-i-1; j++ {
			if tasks[j].GetPriority() < tasks[j+1].GetPriority() {
				tasks[j], tasks[j+1] = tasks[j+1], tasks[j]
			}
		}
	}
	return tasks
}

func (se *SchedulerEngine) sortByShortestJob(tasks []rl.TaskEntry) []rl.TaskEntry {
	// Simple bubble sort by execution time
	n := len(tasks)
	for i := 0; i < n-1; i++ {
		for j := 0; j < n-i-1; j++ {
			if tasks[j].GetExecutionTimeMs() > tasks[j+1].GetExecutionTimeMs() {
				tasks[j], tasks[j+1] = tasks[j+1], tasks[j]
			}
		}
	}
	return tasks
}

// sortQueueWithObjectives sorts queue using RL agent with multi-objective configuration
func (se *SchedulerEngine) sortQueueWithObjectives(tasks []rl.TaskEntry) []rl.TaskEntry {
	if len(tasks) <= 1 {
		return tasks
	}
	
	// Get current multi-objective configuration
	activeProfile := se.config.RL.MultiObjective.ActiveProfile
	weights := se.config.RL.MultiObjective.Profiles[activeProfile].Weights
	
	// Log current objective profile being used
	logger.GetLogger().Debugf("Using multi-objective profile: %s", activeProfile)
	logger.GetLogger().Debugf("Objective weights: Latency=%.2f, Throughput=%.2f, ResourceEff=%.2f, Fairness=%.2f, DeadlineMiss=%.2f, EnergyEff=%.2f",
		weights.Latency, weights.Throughput, weights.ResourceEfficiency, weights.Fairness, weights.DeadlineMiss, weights.EnergyEfficiency)
	
	// Use RL agent with multi-objective configuration
	sortedTasks := se.agent.Schedule(tasks, se.nodeManager)
	
	// Log sorting results
	logger.GetLogger().Debugf("Multi-objective queue sorting completed: %d tasks resorted", len(sortedTasks))
	
	return sortedTasks
}

// UpdateObjectiveProfile updates the active objective profile for multi-objective RL
func (se *SchedulerEngine) UpdateObjectiveProfile(profileName string) error {
	se.mu.Lock()
	defer se.mu.Unlock()
	
	// Check if profile exists
	if _, exists := se.config.RL.MultiObjective.Profiles[profileName]; !exists {
		return fmt.Errorf("objective profile '%s' not found", profileName)
	}
	
	// Update active profile
	se.config.RL.MultiObjective.ActiveProfile = profileName
	
	// Update agent with new weights if agent is enabled
	if se.agent != nil && se.agent.IsEnabled() {
		weights := se.config.RL.MultiObjective.Profiles[profileName].Weights
		if err := se.agent.UpdateRewardWeights(weights); err != nil {
			return fmt.Errorf("failed to update agent reward weights: %w", err)
		}
	}
	
	logger.GetLogger().Infof("Objective profile updated to: %s", profileName)
	return nil
}

// GetCurrentObjectiveProfile returns the current active objective profile
func (se *SchedulerEngine) GetCurrentObjectiveProfile() string {
	se.mu.RLock()
	defer se.mu.RUnlock()
	return se.config.RL.MultiObjective.ActiveProfile
}

// GetAvailableObjectiveProfiles returns all available objective profiles
func (se *SchedulerEngine) GetAvailableObjectiveProfiles() []string {
	se.mu.RLock()
	defer se.mu.RUnlock()
	
	profiles := make([]string, 0, len(se.config.RL.MultiObjective.Profiles))
	for profileName := range se.config.RL.MultiObjective.Profiles {
		profiles = append(profiles, profileName)
	}
	return profiles
}

// AddTaskToQueue adds a task to the scheduling queue (legacy compatibility)
func (se *SchedulerEngine) AddTaskToQueue(task *pb.Task) (int64, int64, error) {
	queuePos, waitTime, _, _, _, err := se.AddTaskToQueueWithCache(task)
	return queuePos, waitTime, err
}

// AddTaskToQueueWithCache adds a task to the scheduling queue with cache information
func (se *SchedulerEngine) AddTaskToQueueWithCache(task *pb.Task) (int64, int64, bool, string, pb.CacheAction, error) {
	if err := ValidateTask(task); err != nil {
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, err
	}

	// Check if task is already scheduled (prevent duplicates)
	se.mu.RLock()
	if _, exists := se.scheduledTasks[task.TaskId]; exists {
		se.mu.RUnlock()
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("task %s already scheduled", task.TaskId)
	}
	se.mu.RUnlock()

	// CRITICAL: Only use cache for RL algorithms
	var isCached bool
	var cacheKey string
	var cacheAction pb.CacheAction

	// Cache decision (fingerprint-based only)
	if se.cacheManager != nil && se.cacheManager.config.Enabled {
		isCached, cacheKey, cacheAction = se.cacheManager.ProcessTask(task)
	} else {
		// Cache disabled
		isCached = false
		cacheKey = ""
		cacheAction = pb.CacheAction_CACHE_ACTION_NONE
	}

	// [DEBUG] Log cache decision
	if isCached && cacheAction == pb.CacheAction_CACHE_ACTION_USE {
		logger.GetLogger().Infof("[SCHEDULER-CACHE-DEBUG] Task %s is CACHED (cacheKey=%s) - Adding to queue with cache flag",
			task.TaskId, cacheKey)
	} else {
		logger.GetLogger().Infof("[SCHEDULER-CACHE-DEBUG] Task %s is NOT cached (isCached=%t, action=%v) - Adding to queue",
			task.TaskId, isCached, cacheAction)
	}

	// Create task entry WITH cache information
	// IMPORTANT: ALL tasks (cached or not) go through the queue
	taskEntry := NewTaskEntry(task)
	taskEntry.IsCached = isCached
	taskEntry.CacheKey = cacheKey
	taskEntry.CacheAction = cacheAction

	// Add to queue (ALL tasks go through queue, cache decision is made during execution)
	if err := se.queue.Enqueue(taskEntry); err != nil {
		logger.GetLogger().Errorf("[SCHEDULER-CACHE-DEBUG] Failed to enqueue task %s: %v", task.TaskId, err)
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, err
	}

	// Track scheduled task for delayed rewards
	se.mu.Lock()
	se.scheduledTasks[task.TaskId] = taskEntry
	se.mu.Unlock()

	// [DEBUG] Log queue state after adding
	logger.GetLogger().Debugf("[SCHEDULER-CACHE-DEBUG] Task %s added to queue (isCached=%t, cacheAction=%v). Queue size=%d, scheduledTasks size=%d",
		task.TaskId, isCached, cacheAction, se.queue.Size(), len(se.scheduledTasks))

	// Calculate queue position and estimated wait time
	queuePosition := int64(se.getTaskQueuePosition(task.TaskId))
	estimatedWait := se.calculateEstimatedWaitTime(queuePosition)

	// Log cache decision for RL algorithms
	if se.agent != nil && se.agent.IsEnabled() {
		if isCached {
			logger.GetLogger().Infof("Task %s cache decision: %s (key: %s)", 
				task.TaskId, cacheAction.String(), cacheKey)
		}
	}

	return queuePosition, estimatedWait, isCached, cacheKey, cacheAction, nil
}



// REMOVED: schedulerLoop() - No periodic task execution in scheduler

// REMOVED: processTaskQueue() - No task execution in scheduler

// REMOVED: executeTaskToCompletion() - No task execution in scheduler

// REMOVED: resultProcessor() - No task execution in scheduler

// REMOVED: handleTaskCompletion() - No task execution in scheduler

func (se *SchedulerEngine) GetQueueStatus() map[string]interface{} {
	se.mu.RLock()
	defer se.mu.RUnlock()

	avgWaitTime := float64(0)
	avgExecutionTime := float64(0)

	if se.totalTasksProcessed > 0 {
		avgWaitTime = float64(se.totalWaitTime.Milliseconds()) / float64(se.totalTasksProcessed)
		avgExecutionTime = float64(se.totalExecutionTime.Milliseconds()) / float64(se.totalTasksProcessed)
	}

	// Get cache statistics
	cacheStats := se.cacheManager.GetCacheStats()

	status := map[string]interface{}{
		"algorithm":             se.algorithm.String(),
		"objective":             se.objective.String(),
		"queue_size":            se.queue.Size(),
		"scheduled_tasks":       len(se.scheduledTasks),
		"total_tasks_processed": se.totalTasksProcessed,
		"total_tasks_completed": se.totalTasksCompleted,
		"total_tasks_failed":    se.totalTasksFailed,
		"success_rate":          se.getSuccessRate(),
		"avg_wait_time_ms":      avgWaitTime,
		"avg_execution_time_ms": avgExecutionTime,
		"max_concurrent_tasks":  se.maxConcurrentTasks,
		"node_utilization":      se.nodeManager.GetCurrentLoad(),
		"cache_hits":            cacheStats["cache_hits"],
		"cache_misses":          cacheStats["cache_misses"],
		"cache_hit_rate":        cacheStats["hit_rate"],
		"repeated_task_ratio":   cacheStats["repeated_task_ratio"],
		"unique_tasks_tracked":  cacheStats["unique_tasks"],
	}

	// Add agent status if available
	if se.agent != nil {
		status["agent_enabled"] = se.agent.IsEnabled()
		status["agent_stats"] = se.agent.GetStats()
	} else {
		status["agent_enabled"] = false
	}

	return status
}

func (se *SchedulerEngine) getSuccessRate() float64 {
	if se.totalTasksProcessed == 0 {
		return 0.0
	}
	return (float64(se.totalTasksCompleted) / float64(se.totalTasksProcessed)) * 100.0
}

func (se *SchedulerEngine) GetNodeInfo() *pb.FogNode {
	return se.nodeManager.GetNodeInfo()
}

func (se *SchedulerEngine) SetMaxConcurrentTasks(max int) {
	se.mu.Lock()
	defer se.mu.Unlock()
	se.maxConcurrentTasks = max
}

func (se *SchedulerEngine) GetTaskStatus(taskID string) *TaskEntry {
	se.mu.RLock()
	defer se.mu.RUnlock()

	// Only check queue - no running or completed tasks tracking
	for _, task := range se.queue.GetAll() {
		if task.GetTaskID() == taskID {
			return task
		}
	}

	return nil
}

func (se *SchedulerEngine) getSchedulingReasoning(task *pb.Task, queuePosition int64) string {
	if se.agent != nil && se.agent.IsEnabled() {
		return fmt.Sprintf("RL Agent: Algorithm %s, queue position %d", se.agent.GetCurrentAlgorithm(), queuePosition)
	}

	switch se.algorithm {
	case pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_PRIORITY:
		return fmt.Sprintf("Priority-based: Task priority %d, queue position %d", task.Priority, queuePosition)
	case pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_SHORTEST_JOB_FIRST:
		return fmt.Sprintf("SJF: Task execution time %dms, queue position %d", task.ExecutionTime, queuePosition)
	case pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_FIFO:
		return fmt.Sprintf("FIFO: Task queued, position %d", queuePosition)
	default:
		return fmt.Sprintf("Default FIFO: Task queued, position %d", queuePosition)
	}
}

func (se *SchedulerEngine) calculateEstimatedWaitTime(queuePosition int64) int64 {
	avgTaskTime := int64(3)
	return queuePosition * avgTaskTime
}

func (se *SchedulerEngine) getTaskQueuePosition(taskID string) int {
	tasks := se.queue.GetAll()
	for i, task := range tasks {
		if task.GetTaskID() == taskID {
			return i + 1
		}
	}
	return len(tasks)
}

// UpdateObjectiveWeights updates Agent weights when gRPC call happens
func (se *SchedulerEngine) UpdateObjectiveWeights(weights config.RewardWeights) error {
	se.mu.Lock()
	defer se.mu.Unlock()

	if se.agent != nil && se.agent.IsEnabled() {
		se.config.RL.RewardWeights = weights

		return se.agent.UpdateRewardWeights(weights)
	}

	return nil
}

// ProcessTaskCompletion processes task completion reports from iFogSim
func (se *SchedulerEngine) ProcessTaskCompletion(req *pb.TaskCompletionReport) error {
	se.mu.Lock()
	defer se.mu.Unlock()

	// Find the task in scheduled tasks (for delayed rewards)
	task, exists := se.scheduledTasks[req.TaskId]
	if !exists {
		return fmt.Errorf("task %s not found in scheduled tasks", req.TaskId)
	}

	// Derive success from completion report
	success := se.deriveTaskSuccess(req)
	errorMessage := se.deriveErrorMessage(req)

	// Update task status based on completion report
	if success {
		task.MarkCompleted()
		se.totalTasksCompleted++
	} else {
		task.MarkFailed(errorMessage)
		se.totalTasksFailed++
	}

	// Update execution time if provided
	actualExecutionTimeMs := se.deriveActualExecutionTime(req)
	if actualExecutionTimeMs > 0 {
		actualDuration := time.Duration(actualExecutionTimeMs) * time.Millisecond
		se.totalExecutionTime += actualDuration
	}

	// **CRITICAL: Remove from scheduled tasks to prevent duplicate scheduling**
	delete(se.scheduledTasks, req.TaskId)

	// **KEY PART: Delegate to Agent for RL experience handling**
	if se.agent != nil && se.agent.IsEnabled() {
		// The Agent should handle experience collection through AlgorithmManager
		if err := se.reportTaskCompletionToAgent(task, req); err != nil {
			// Log error but don't fail the whole operation
			fmt.Printf("Warning: Failed to report completion to RL agent: %v\n", err)
		}
	}

	return nil
}

// Helper methods to derive missing fields from the report
func (se *SchedulerEngine) deriveTaskSuccess(req *pb.TaskCompletionReport) bool {
	// Check if we have completed tasks info
	if len(req.Tasks) > 0 {
		// Look for the specific task
		for _, completedTask := range req.Tasks {
			if completedTask.TaskId == req.TaskId {
				return completedTask.DeadlineMet // Use deadline met as success indicator
			}
		}
	}

	// Fallback: if no deadline misses reported, assume success
	if req.Metrics != nil {
		return req.Metrics.DeadlineMisses == 0
	}

	return true // Default to success
}

func (se *SchedulerEngine) deriveErrorMessage(req *pb.TaskCompletionReport) string {
	// Check if task failed based on deadline or other metrics
	if !se.deriveTaskSuccess(req) {
		if req.Metrics != nil && req.Metrics.DeadlineMisses > 0 {
			return "deadline missed"
		}
		return "task execution failed"
	}
	return ""
}

func (se *SchedulerEngine) deriveActualExecutionTime(req *pb.TaskCompletionReport) float64 {
	// Look for the specific task in completed tasks
	if len(req.Tasks) > 0 {
		for _, completedTask := range req.Tasks {
			if completedTask.TaskId == req.TaskId {
				return completedTask.ActualExecutionTimeMs
			}
		}
	}
	return 0
}

// reportTaskCompletionToAgent sends completion data to the RL agent
func (se *SchedulerEngine) reportTaskCompletionToAgent(task *TaskEntry, req *pb.TaskCompletionReport) error {
	if se.agent == nil || !se.agent.IsEnabled() {
		return nil // Agent not enabled or initialized
	}

	// Pass nodeManager to the agent for proper integration
	return se.agent.ProcessTaskCompletionWithNodeManager(task, req, se.nodeManager)
}

// GetAgent returns the RL agent for external access
func (se *SchedulerEngine) GetAgent() *rl.Agent {
	se.mu.RLock()
	defer se.mu.RUnlock()
	return se.agent
}

// GetSortedQueue returns the current sorted queue as proto tasks
func (se *SchedulerEngine) GetSortedQueue(includeMetadata bool) *pb.GetSortedQueueResponse {
	se.mu.RLock()
	defer se.mu.RUnlock()

	// Get all tasks from queue
	allTasks := se.queue.GetAll()
	
	// [DEBUG] Log queue state
	logger.GetLogger().Debugf("[SCHEDULER-QUEUE-DEBUG] GetSortedQueue called: queue size=%d, scheduledTasks map size=%d",
		len(allTasks), len(se.scheduledTasks))
	
	// Log which tasks are in queue vs in scheduledTasks map
	taskIdsInQueue := make([]string, 0, len(allTasks))
	for _, taskEntry := range allTasks {
		taskIdsInQueue = append(taskIdsInQueue, taskEntry.GetTaskID())
	}
	logger.GetLogger().Debugf("[SCHEDULER-QUEUE-DEBUG] Tasks in queue: %v", taskIdsInQueue)
	
	scheduledTaskIds := make([]string, 0, len(se.scheduledTasks))
	for taskId := range se.scheduledTasks {
		scheduledTaskIds = append(scheduledTaskIds, taskId)
	}
	logger.GetLogger().Debugf("[SCHEDULER-QUEUE-DEBUG] Tasks in scheduledTasks map: %v", scheduledTaskIds)
	
	// [DEBUG] Check if tasks are in scheduledTasks but not in queue (cached tasks)
	for taskId := range se.scheduledTasks {
		foundInQueue := false
		for _, taskEntry := range allTasks {
			if taskEntry.GetTaskID() == taskId {
				foundInQueue = true
				break
			}
		}
		if !foundInQueue {
			logger.GetLogger().Infof("[SCHEDULER-QUEUE-DEBUG] Task %s is in scheduledTasks map but NOT in queue (likely cached and removed from queue)",
				taskId)
		}
	}
	
	// Convert to proto tasks WITH cache information in metadata
	protoTasks := make([]*pb.Task, 0, len(allTasks))
	for _, taskEntry := range allTasks {
		protoTask := se.taskEntryToProtoTaskWithCache(taskEntry)
		protoTasks = append(protoTasks, protoTask)
	}

	// [DEBUG] Log response details
	logger.GetLogger().Infof("[SCHEDULER-QUEUE-DEBUG] GetSortedQueue returning %d tasks (algorithm=%s, nodeId=%s)",
		len(protoTasks), se.algorithm.String(), se.nodeManager.NodeID)

	// Build response
	response := &pb.GetSortedQueueResponse{
		SortedTasks:   protoTasks,
		AlgorithmUsed: se.algorithm.String(),
		QueueSize:     int64(len(allTasks)),
		Timestamp:     time.Now().Unix(),
		NodeId:        se.nodeManager.NodeID,
	}

	// Add metadata if requested
	if includeMetadata {
		response.Metadata = map[string]string{
			"objective":           se.objective.String(),
			"scheduled_tasks":     fmt.Sprintf("%d", len(se.scheduledTasks)),
			"total_processed":     fmt.Sprintf("%d", se.totalTasksProcessed),
			"total_completed":        fmt.Sprintf("%d", se.totalTasksCompleted),
			"total_failed":        fmt.Sprintf("%d", se.totalTasksFailed),
			"success_rate":        fmt.Sprintf("%.2f", se.getSuccessRate()),
			"node_utilization":   fmt.Sprintf("%.2f", se.nodeManager.GetCurrentLoad()),
		}
	}

	return response
}

// taskEntryToProtoTaskWithCache converts TaskEntry to proto Task with cache info in metadata
func (se *SchedulerEngine) taskEntryToProtoTaskWithCache(taskEntry *TaskEntry) *pb.Task {
	// Create a copy of the task with cache info in metadata
	taskCopy := &pb.Task{
		TaskId:          taskEntry.Task.TaskId,
		TaskName:        taskEntry.Task.TaskName,
		TaskType:        taskEntry.Task.TaskType,
		CpuRequirement:  taskEntry.Task.CpuRequirement,
		MemoryRequirement: taskEntry.Task.MemoryRequirement,
		ExecutionTime:   taskEntry.Task.ExecutionTime,
		Priority:        taskEntry.Task.Priority,
		Deadline:        taskEntry.Task.Deadline,
		Dependencies:    taskEntry.Task.Dependencies,
	}
	
	// Copy existing metadata if any
	if taskEntry.Task.Metadata != nil {
		taskCopy.Metadata = make(map[string]string)
		for k, v := range taskEntry.Task.Metadata {
			taskCopy.Metadata[k] = v
		}
	} else {
		taskCopy.Metadata = make(map[string]string)
	}
	
	// Add cache information to metadata
	if taskEntry.IsCached {
		taskCopy.Metadata["is_cached"] = "true"
	} else {
		taskCopy.Metadata["is_cached"] = "false"
	}
	taskCopy.Metadata["cache_key"] = taskEntry.CacheKey
	taskCopy.Metadata["cache_action"] = taskEntry.CacheAction.String()
	
	return taskCopy
}

// GetQueueUpdateResponse creates a queue update response for streaming
func (se *SchedulerEngine) GetQueueUpdateResponse(updateReason string, includeMetadata bool) *pb.QueueUpdateResponse {
	// CRITICAL: Resort queue FIRST to ensure latest sorted order before sending
	// This coordinates resorting with streaming to guarantee latest queue order
	// Note: resortQueue() acquires its own lock, so we call it before acquiring our lock
	if se.config.Queue.EnablePeriodicResort {
		se.resortQueue() // This will lock internally
	}
	
	se.mu.RLock()
	defer se.mu.RUnlock()

	// Get all tasks from queue (now freshly resorted)
	allTasks := se.queue.GetAll()
	
	// Convert to proto tasks WITH cache information in metadata
	protoTasks := make([]*pb.Task, 0, len(allTasks))
	for _, taskEntry := range allTasks {
		protoTask := se.taskEntryToProtoTaskWithCache(taskEntry)
		protoTasks = append(protoTasks, protoTask)
	}

	// Calculate confidence score (simplified)
	confidenceScore := 0.8
	if se.agent != nil && se.agent.IsEnabled() {
		confidenceScore = 0.9  // RL algorithms have higher confidence
	}

	// Build response
	response := &pb.QueueUpdateResponse{
		SortedTasks:     protoTasks,
		AlgorithmUsed:   se.algorithm.String(),
		ConfidenceScore: confidenceScore,
		UpdateTimestamp: time.Now().Unix(),
		UpdateReason:    updateReason,
		NodeId:          se.nodeManager.NodeID,
	}

	// Add metadata if requested
	if includeMetadata {
		response.Metadata = map[string]string{
			"objective":           se.objective.String(),
			"scheduled_tasks":     fmt.Sprintf("%d", len(se.scheduledTasks)),
			"total_processed":     fmt.Sprintf("%d", se.totalTasksProcessed),
			"total_completed":     fmt.Sprintf("%d", se.totalTasksCompleted),
			"total_failed":        fmt.Sprintf("%d", se.totalTasksFailed),
			"success_rate":        fmt.Sprintf("%.2f", se.getSuccessRate()),
			"node_utilization":   fmt.Sprintf("%.2f", se.nodeManager.GetCurrentLoad()),
		}
	}

	return response
}
