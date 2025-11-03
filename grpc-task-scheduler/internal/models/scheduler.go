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
	cacheAgent   *rl.CacheAgent  // NEW: Cache RL agent
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

	// Initialize Scheduling Agent if RL is enabled
	if cfg.RL.Enabled {
		agentConfig := rl.AgentConfig{
			AlgorithmManagerConfig: cfg.AlgorithmManager,
		}
		engine.agent = rl.NewAgent(agentConfig)
	}

	// Initialize Cache Agent if enabled
	if cfg.CacheAgent.Enabled {
		cacheAgentConfig := rl.CacheAgentConfig{
			Enabled:         cfg.CacheAgent.Enabled,
			LearningRate:    cfg.CacheAgent.LearningRate,
			DiscountFactor:  cfg.CacheAgent.DiscountFactor,
			ExplorationRate: cfg.CacheAgent.ExplorationRate,
			MinExploration:  cfg.CacheAgent.MinExploration,
			ExplorationDecay: cfg.CacheAgent.ExplorationDecay,
		}
		engine.cacheAgent = rl.NewCacheAgent(cacheAgentConfig)
	}

	return engine
}

// setCacheManagerInRLAlgorithms removed - cache features no longer used in scheduling RL

func (se *SchedulerEngine) Start(ctx context.Context) {
	// Start periodic queue resorting if enabled
	if se.config.Queue.EnablePeriodicResort {
		se.startPeriodicQueueResorting()
	}
	
	// Start periodic cache cleanup if enabled
	if se.cacheManager != nil && se.cacheManager.config.Enabled {
		cleanupIntervalMs := se.config.Queue.ResortIntervalMs // Use same as queue resorting (100ms)
		se.cacheManager.Start(cleanupIntervalMs)
	}
}

func (se *SchedulerEngine) Stop() {
	close(se.stopChan)
	if se.resortTicker != nil {
		se.resortTicker.Stop()
	}
	close(se.resortChan)
	
	// Stop periodic cache cleanup
	if se.cacheManager != nil {
		se.cacheManager.Stop()
	}
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
	var queueContext *pb.QueueContext // nil for legacy calls
	queuePos, waitTime, _, _, _, err := se.AddTaskToQueueWithCache(task, queueContext)
	return queuePos, waitTime, err
}

// AddTaskToQueueWithCache adds a task to the scheduling queue with cache information
// queueContext: Queue context from iFogSim (contains total_queue_size)
func (se *SchedulerEngine) AddTaskToQueueWithCache(task *pb.Task, queueContext *pb.QueueContext) (int64, int64, bool, string, pb.CacheAction, error) {
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

	// Step 1: Generate fingerprint
	fingerprint := se.cacheManager.GenerateTaskFingerprint(task)
	if fingerprint == "" {
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("failed to generate fingerprint for task %s", task.TaskId)
	}

	// Step 2: Check cache entry state (lazy cleanup)
	var entryFirstSeen int64 = 0
	var entrySeenCount int = 0
	cacheExists := false
	cacheExpired := false
	cacheAgeCategory := "none"

	if se.cacheManager != nil && se.cacheManager.config.Enabled {
		entry, exists := se.cacheManager.GetEntry(fingerprint)
		if exists {
			cacheExists = true
			entryFirstSeen = entry.FirstSeen
			entrySeenCount = entry.SeenCount

			// Calculate age and check expiration (lazy cleanup)
			now := time.Now()
			firstSeen := time.Unix(entry.FirstSeen, 0)
			age := now.Sub(firstSeen)
			ttl := time.Duration(se.cacheManager.config.CacheTTLHours) * time.Hour
			ttlRatio := float64(age) / float64(ttl)

			if ttlRatio >= 1.0 {
				// EXPIRED - Delete immediately (lazy cleanup)
				se.cacheManager.RemoveEntry(fingerprint)
				cacheExists = false
				cacheExpired = true
				cacheAgeCategory = "expired"
				entryFirstSeen = 0
				entrySeenCount = 0
			} else {
				// Not expired - cache exists
				cacheAgeCategory = rl.CategorizeCacheAge(ttlRatio)
				// Note: SeenCount will be updated in ProcessTask if using fallback
			}
		}
	}

	// Step 3: Cache Agent Decision (if enabled)
	var isCached bool
	var cacheKey string
	var cacheAction pb.CacheAction
	var cacheState *rl.CacheStateFeatures // Store for delayed reward
	var rlAction rl.Action                // Store for delayed reward

	if se.cacheAgent != nil && se.cacheAgent.IsEnabled() {
		// Extract cache state features
		hitRate := se.cacheManager.GetHitRate()
		systemLoad := se.nodeManager.GetCurrentLoad()
		cacheTTLHours := int(se.cacheManager.config.CacheTTLHours)

		extractedState := rl.ExtractCacheStateFeatures(
			task,
			fingerprint,
			queueContext, // From iFogSim
			entryFirstSeen,
			entrySeenCount,
			hitRate,
			systemLoad,
			cacheTTLHours,
		)

		// Set cache entry state (update if not expired)
		extractedState.CacheExists = cacheExists && !cacheExpired
		extractedState.CacheAgeCategory = cacheAgeCategory

		// Store state for delayed reward
		cacheState = extractedState

		// Cache agent decides action
		rlAction = se.cacheAgent.SelectAction(extractedState)

		// Map RL action to proto CacheAction (with expired handling)
		cacheAction = rl.MapCacheActionToProto(
			rlAction,
			cacheExists && !cacheExpired,
			cacheExpired,
		)

		// Determine isCached
		isCached = (cacheAction == pb.CacheAction_CACHE_ACTION_USE)

		// Update entry SeenCount if not expired (handled via ProcessTask for consistency)
		// For cache agent, we track via cache manager's ProcessTask when needed
		// Note: SeenCount is updated during state extraction above

	} else {
		// Fallback: fingerprint-based (current implementation)
		if se.cacheManager != nil && se.cacheManager.config.Enabled {
			isCached, cacheKey, cacheAction = se.cacheManager.ProcessTask(task)
		} else {
			// Cache disabled
			isCached = false
			cacheAction = pb.CacheAction_CACHE_ACTION_NONE
		}
	}

	// Step 4: Handle cache invalidation if needed
	if cacheAction == pb.CacheAction_CACHE_ACTION_INVALIDATE && cacheExists {
		se.cacheManager.RemoveEntry(fingerprint)
	}

	// Set cacheKey (always fingerprint)
	cacheKey = fingerprint

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
	
	// Store cache state and RL action for delayed reward (if cache agent made the decision)
	if se.cacheAgent != nil && se.cacheAgent.IsEnabled() && cacheState != nil && rlAction.Type != 0 {
		taskEntry.CacheState = cacheState
		taskEntry.CacheRLAction = &rlAction
	}

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

	// **KEY PART: Delegate to Agent for RL experience handling** (before deleting from map)
	if se.agent != nil && se.agent.IsEnabled() {
		// The Agent should handle experience collection through AlgorithmManager
		if err := se.reportTaskCompletionToAgent(task, req); err != nil {
			// Log error but don't fail the whole operation
			fmt.Printf("Warning: Failed to report completion to RL agent: %v\n", err)
		}
	}

	// **NEW: Process cache agent delayed reward** (before deleting from map)
	if se.cacheAgent != nil && se.cacheAgent.IsEnabled() {
		if err := se.reportTaskCompletionToCacheAgent(task, req); err != nil {
			// Log error but don't fail the whole operation
			logger.GetLogger().Warnf("Failed to report completion to cache agent: %v", err)
		}
	}

	// **CRITICAL: Remove from scheduled tasks to prevent duplicate scheduling** (after processing rewards)
	delete(se.scheduledTasks, req.TaskId)

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

// reportTaskCompletionToCacheAgent processes completion for cache agent delayed reward
func (se *SchedulerEngine) reportTaskCompletionToCacheAgent(task *TaskEntry, req *pb.TaskCompletionReport) error {
	if se.cacheAgent == nil || !se.cacheAgent.IsEnabled() {
		return nil // Cache agent not enabled
	}

	// Check if we have cache state and action stored (cache agent made the decision)
	if task.CacheState == nil || task.CacheRLAction == nil {
		// Cache agent didn't make the decision (fallback mode) - skip reward update
		return nil
	}

	// Determine if cache was actually used successfully
	success := se.deriveTaskSuccess(req)
	actualExecutionTimeMs := se.deriveActualExecutionTime(req)
	
	// Cache was successful if:
	// 1. Task was marked as cached (IsCached = true)
	// 2. Task completed successfully
	// 3. Execution time is 0 or very small (instant cache hit)
	wasCachedSuccessfully := task.IsCached && success && actualExecutionTimeMs < 100
	// Note: If task was cached, execution time should be ~0 (instant)
	// If task was not cached but agent said to use cache, that's a cache miss (bad decision)

	// Calculate reward based on cache action and actual result
	systemLoad := se.nodeManager.GetCurrentLoad()
	executionTimeSaved := int64(0)
	if task.Task != nil {
		// Time saved = original execution time (if cache was used)
		if wasCachedSuccessfully {
			executionTimeSaved = task.Task.ExecutionTime
		}
	}

	// Determine cache hit success
	cacheHitSuccess := false
	if task.CacheAction == pb.CacheAction_CACHE_ACTION_USE {
		// Agent decided to use cache
		cacheHitSuccess = wasCachedSuccessfully
	}

	// Calculate reward
	reward := rl.CalculateCacheReward(
		task.CacheAction,
		executionTimeSaved,
		cacheHitSuccess,
		systemLoad,
	)

	// Create next state (current state after task completion)
	// For cache agent, next state is similar to current state but with updated metrics
	nextState := task.CacheState // Use same state (or extract new state)
	// Note: In a full implementation, we'd extract the new state from current system metrics
	// For now, use the same state as next state (episodic learning)

	// Update cache agent with reward
	done := true // Task is complete, episode is done
	err := se.cacheAgent.UpdateReward(
		task.CacheState,
		*task.CacheRLAction,
		reward,
		nextState,
		done,
	)

	if err != nil {
		return fmt.Errorf("failed to update cache agent reward: %w", err)
	}

	logger.GetLogger().Infof("[CACHE-AGENT-REWARD] Task %s: Action=%v, Reward=%.2f, CacheHitSuccess=%t, TimeSaved=%dms",
		task.Task.TaskId, task.CacheRLAction.Type, reward, cacheHitSuccess, executionTimeSaved)

	return nil
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
