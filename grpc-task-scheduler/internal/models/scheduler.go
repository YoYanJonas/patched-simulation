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
	
	// BACKWARD COMPATIBILITY: Periodic resorting fields (no longer used)
	// These fields remain for backward compatibility but are not actively used.
	// Resorting now happens on-demand when GetSortedQueue() or GetQueueUpdateResponse() is called.
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

	// Initialize Cache Agent if enabled (uses two-action design: ActionCache, ActionDelete)
	if cfg.CacheAgent.Enabled {
		cacheAgentConfig := cfg.CacheAgent
		engine.cacheAgent = rl.NewCacheAgent(cacheAgentConfig)
	}

	return engine
}

// setCacheManagerInRLAlgorithms removed - cache features no longer used in scheduling RL

func (se *SchedulerEngine) Start(ctx context.Context) {
	logger.GetLogger().Info("[SCHEDULER-ENGINE-START] Starting scheduler engine...")
	logger.GetLogger().Infof("[SCHEDULER-ENGINE-INIT] Algorithm=%s, Objective=%s, MaxConcurrentTasks=%d",
		se.algorithm.String(), se.objective.String(), se.maxConcurrentTasks)
	
	// NOTE: Periodic queue resorting removed - resorting now happens on-demand
	// when GetSortedQueue() or GetQueueUpdateResponse() is called.
	// This eliminates race conditions and ensures fresh queue state on every request.
	logger.GetLogger().Info("[SCHEDULER-ENGINE-RESORTING] On-demand resorting enabled (no periodic background goroutine)")
	
	// Start periodic cache cleanup if enabled
	if se.cacheManager != nil && se.cacheManager.config.Enabled {
		cleanupIntervalMs := se.config.Queue.ResortIntervalMs // Use same interval (100ms)
		se.cacheManager.Start(cleanupIntervalMs)
		logger.GetLogger().Infof("[SCHEDULER-ENGINE-CACHE] Cache manager started with cleanup interval: %dms", cleanupIntervalMs)
	} else {
		logger.GetLogger().Info("[SCHEDULER-ENGINE-CACHE] Cache manager disabled")
	}
	
	// Log RL agent status
	if se.agent != nil && se.agent.IsEnabled() {
		logger.GetLogger().Info("[SCHEDULER-ENGINE-RL] Scheduling RL agent enabled")
	} else {
		logger.GetLogger().Info("[SCHEDULER-ENGINE-RL] Scheduling RL agent disabled (using traditional algorithm)")
	}
	
	// Log cache agent status
	if se.cacheAgent != nil && se.cacheAgent.IsEnabled() {
		logger.GetLogger().Info("[SCHEDULER-ENGINE-CACHE-AGENT] Cache RL agent enabled")
	} else {
		logger.GetLogger().Info("[SCHEDULER-ENGINE-CACHE-AGENT] Cache RL agent disabled (using fingerprint-based caching)")
	}
	
	logger.GetLogger().Info("[SCHEDULER-ENGINE-STARTED] Scheduler engine started successfully")
}

func (se *SchedulerEngine) Stop() {
	logger.GetLogger().Info("[SCHEDULER-ENGINE-STOP] Stopping scheduler engine...")
	
	// Log statistics before shutdown
	se.mu.RLock()
	totalProcessed := se.totalTasksProcessed
	totalCompleted := se.totalTasksCompleted
	totalFailed := se.totalTasksFailed
	queueSize := se.queue.Size()
	scheduledTasksCount := len(se.scheduledTasks)
	se.mu.RUnlock()
	
	logger.GetLogger().Infof("[SCHEDULER-ENGINE-STATS] Final statistics: Processed=%d, Completed=%d, Failed=%d, QueueSize=%d, ScheduledTasks=%d",
		totalProcessed, totalCompleted, totalFailed, queueSize, scheduledTasksCount)
	
	close(se.stopChan)
	// BACKWARD COMPATIBILITY: Cleanup periodic resorting fields (no longer actively used)
	// These are kept for backward compatibility but periodic resorting has been removed.
	if se.resortTicker != nil {
		se.resortTicker.Stop()
	}
	if se.resortChan != nil {
		close(se.resortChan)
	}
	
	// Stop periodic cache cleanup
	if se.cacheManager != nil {
		se.cacheManager.Stop()
		logger.GetLogger().Info("[SCHEDULER-ENGINE-CACHE] Cache manager stopped")
	}
	
	logger.GetLogger().Info("[SCHEDULER-ENGINE-STOPPED] Scheduler engine stopped successfully")
}

// REMOVED: startPeriodicQueueResorting()
// Resorting now happens on-demand in GetSortedQueue() and GetQueueUpdateResponse()
// This eliminates race conditions and ensures queue is always fresh when requested.

// resortQueue resorts the queue based on algorithm type
func (se *SchedulerEngine) resortQueue() {
	// [DEBUG] Entry point for resortQueue
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-ENTRY] resortQueue() called")
	
	// [DEBUG] About to acquire write lock
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-LOCK-BEFORE] About to acquire write lock (mu.Lock)")
	se.mu.Lock()
	// [DEBUG] Write lock acquired
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-LOCK-ACQUIRED] Write lock acquired successfully")
	defer func() {
		// [DEBUG] About to release write lock
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-LOCK-RELEASE] Releasing write lock")
		se.mu.Unlock()
		// [DEBUG] Write lock released
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-LOCK-RELEASED] Write lock released")
	}()
	
	// [DEBUG] Getting queue size
	queueSize := se.queue.Size()
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-START] Starting queue resorting (queue size: %d, scheduledTasks map size: %d)", 
		queueSize, len(se.scheduledTasks))
	
	// [DEBUG] Check if resort is needed
	if queueSize <= 1 {
		// [DEBUG] Skipping resort - queue too small
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-SKIP] Skipping resort (queue size <= 1: %d)", queueSize)
		if queueSize == 0 {
			// [DEBUG] Queue is empty
			logger.GetLogger().Warnf("[DEBUG] [SCHEDULER-RESORT-EMPTY] Queue is EMPTY during resort - scheduledTasks map has %d tasks", len(se.scheduledTasks))
			// Log task IDs in scheduledTasks map for debugging
			if len(se.scheduledTasks) > 0 {
				taskIds := make([]string, 0, len(se.scheduledTasks))
				for taskId := range se.scheduledTasks {
					taskIds = append(taskIds, taskId)
				}
				logger.GetLogger().Warnf("[DEBUG] [SCHEDULER-RESORT-EMPTY-DEBUG] Tasks in scheduledTasks map (not in queue): %v", taskIds)
			}
		}
		return // No need to resort single or empty queue
	}
	
	// [DEBUG] Getting all tasks from queue
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-GETALL-BEFORE] About to call queue.GetAll()")
	// Get all tasks from queue
	allTasks := se.queue.GetAll()
	// [DEBUG] Got all tasks
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-GETALL-AFTER] queue.GetAll() returned %d tasks", len(allTasks))
	
	if len(allTasks) <= 1 {
		// [DEBUG] Skipping resort - not enough tasks
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-SKIP] Skipping resort (tasks <= 1: %d)", len(allTasks))
		return
	}
	
	// [DEBUG] About to sort tasks
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-DEBUG] Resorting %d tasks (algorithm: %s, RL enabled: %t)", 
		len(allTasks), se.algorithm.String(), se.agent != nil && se.agent.IsEnabled())
	
	// [DEBUG] Log task IDs before resorting
	taskIdsBefore := make([]string, 0, len(allTasks))
	for _, task := range allTasks {
		taskIdsBefore = append(taskIdsBefore, task.GetTaskID())
	}
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-TASK-IDS] Task IDs before resorting: %v", taskIdsBefore)
	
	// [DEBUG] Converting to TaskEntry slice
	// Convert to TaskEntry slice for sorting
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-CONVERT] Converting %d tasks to TaskEntry slice", len(allTasks))
	taskEntries := make([]rl.TaskEntry, len(allTasks))
	for i, task := range allTasks {
		taskEntries[i] = task
	}
	// [DEBUG] Conversion complete
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-CONVERT-DONE] Conversion complete: %d TaskEntries", len(taskEntries))
	
	// [DEBUG] About to sort
	var sortedTasks []rl.TaskEntry
	
	if se.agent != nil && se.agent.IsEnabled() {
		// [DEBUG] Using RL-based sorting
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-SORT-RL] Using RL-based sorting with multi-objective")
		// RL-based resorting with multi-objective configuration
		sortedTasks = se.sortQueueWithObjectives(taskEntries)
		// [DEBUG] RL sorting complete
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-SORT-RL-DONE] RL sorting complete: %d tasks", len(sortedTasks))
		logger.GetLogger().Debugf("Queue resorted using RL algorithm with multi-objective: %d tasks", len(sortedTasks))
		
		// Log task order change to verify resorting is working
		if len(taskIdsBefore) > 0 && len(sortedTasks) > 0 {
			taskIdsAfter := make([]string, 0, len(sortedTasks))
			for _, task := range sortedTasks {
				taskIdsAfter = append(taskIdsAfter, task.GetTaskID())
			}
			// Check if order changed
			orderChanged := false
			if len(taskIdsBefore) == len(taskIdsAfter) {
				for i := 0; i < len(taskIdsBefore); i++ {
					if taskIdsBefore[i] != taskIdsAfter[i] {
						orderChanged = true
						break
					}
				}
			} else {
				orderChanged = true
			}
			if orderChanged {
				logger.GetLogger().Infof("[SCHEDULER-RESORT-ORDER-CHANGE] Task order changed by RL: Before=%v, After=%v",
					taskIdsBefore, taskIdsAfter)
			} else {
				logger.GetLogger().Debugf("[SCHEDULER-RESORT-ORDER-SAME] Task order unchanged (RL may have kept same order): %v",
					taskIdsBefore)
			}
		}
	} else {
		// [DEBUG] Using traditional sorting
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-SORT-TRAD] Using traditional algorithm sorting")
		// Traditional algorithm resorting
		sortedTasks = se.resortQueueTraditional(taskEntries)
		// [DEBUG] Traditional sorting complete
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-SORT-TRAD-DONE] Traditional sorting complete: %d tasks", len(sortedTasks))
		logger.GetLogger().Debugf("Queue resorted using traditional algorithm: %d tasks", len(sortedTasks))
	}
	
	// [DEBUG] About to update queue
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-COMPLETE] Queue resorting completed (%d tasks sorted)", len(sortedTasks))
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-UPDATE-BEFORE] About to call updateQueueWithSortedTasks with %d tasks", len(sortedTasks))
	// Update queue with sorted tasks
	se.updateQueueWithSortedTasks(sortedTasks)
	// [DEBUG] Queue updated
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-UPDATE-AFTER] updateQueueWithSortedTasks completed")
	
	// [DEBUG] Log summary after resorting
	// Note: We already hold write lock, so we can read directly without additional lock
	finalQueueSize := se.queue.Size()
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-SUMMARY] Resorting complete: QueueSize=%d, Algorithm=%s, RLEnabled=%t",
		finalQueueSize, se.algorithm.String(), se.agent != nil && se.agent.IsEnabled())
	// [DEBUG] Resort complete
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESORT-EXIT] resortQueue() completed successfully")
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
// NOTE: This is called from within resortQueue() which already holds the lock, so we don't need to lock again
func (se *SchedulerEngine) updateQueueWithSortedTasks(sortedTasks []rl.TaskEntry) {
	// [DEBUG] Entry point for updateQueueWithSortedTasks
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-ENTRY] updateQueueWithSortedTasks called with %d tasks", len(sortedTasks))
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE] Starting queue update: clearing queue and re-adding %d sorted tasks", len(sortedTasks))
	
	// [DEBUG] Getting current queue state
	// [DEBUG] Log task IDs before clearing
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-BEFORE-GETALL] About to get current queue tasks")
	taskIdsBeforeClear := make([]string, 0, se.queue.Size())
	allTasksBefore := se.queue.GetAll()
	// [DEBUG] Got current queue tasks
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-AFTER-GETALL] Got %d tasks from current queue", len(allTasksBefore))
	for _, task := range allTasksBefore {
		taskIdsBeforeClear = append(taskIdsBeforeClear, task.GetTaskID())
	}
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-BEFORE-CLEAR] Task IDs before clearing: %v (count: %d)", taskIdsBeforeClear, len(taskIdsBeforeClear))
	
	// [DEBUG] About to clear queue
	// Clear current queue
	oldSize := se.queue.Size()
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-CLEAR-BEFORE] About to clear queue (old size: %d)", oldSize)
	se.queue.Clear()
	// [DEBUG] Queue cleared
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-CLEAR-AFTER] Queue cleared (old size: %d, new size: %d)", oldSize, se.queue.Size())
	
	// [DEBUG] Preparing sorted tasks for re-adding
	// [DEBUG] Log task IDs in sortedTasks
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-SORTED-PREPARE] Preparing %d sorted tasks for re-adding", len(sortedTasks))
	taskIdsInSorted := make([]string, 0, len(sortedTasks))
	for _, task := range sortedTasks {
		taskIdsInSorted = append(taskIdsInSorted, task.GetTaskID())
	}
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-SORTED] Task IDs in sortedTasks: %v (count: %d)", taskIdsInSorted, len(sortedTasks))
	
	// [DEBUG] About to re-add tasks
	// Add sorted tasks back to queue
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-ADD-BEFORE] About to re-add %d tasks to queue", len(sortedTasks))
	addedCount := 0
	skippedCount := 0
	skippedTaskIds := make([]string, 0)
	for i, task := range sortedTasks {
		// [DEBUG] Processing each task
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-ADD-TASK] Processing task %d/%d: TaskID=%s", i+1, len(sortedTasks), task.GetTaskID())
		// Convert back to TaskEntry and enqueue
		if taskEntry, ok := task.(*TaskEntry); ok {
			// [DEBUG] Type assertion successful
			logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-ADD-TASK-ASSERT] Type assertion successful for task %s", taskEntry.GetTaskID())
			logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-ADD-TASK-ENQUEUE] About to enqueue task %s", taskEntry.GetTaskID())
			if err := se.queue.Enqueue(taskEntry); err != nil {
				// [DEBUG] Enqueue failed
				logger.GetLogger().Errorf("[DEBUG] [SCHEDULER-UPDATE-QUEUE] Failed to enqueue task %d (ID: %s): %v", i, taskEntry.GetTaskID(), err)
				skippedCount++
				skippedTaskIds = append(skippedTaskIds, taskEntry.GetTaskID())
			} else {
				// [DEBUG] Enqueue successful
				addedCount++
				logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-ADD] Re-added task %s to queue (position %d/%d)", taskEntry.GetTaskID(), addedCount, len(sortedTasks))
			}
		} else {
			// [DEBUG] Type assertion failed
			taskId := "unknown"
			if task != nil {
				taskId = task.GetTaskID()
			}
			logger.GetLogger().Errorf("[DEBUG] [SCHEDULER-UPDATE-QUEUE] Type assertion failed for task %d (ID: %s): expected *TaskEntry, got %T", i, taskId, task)
			skippedCount++
			skippedTaskIds = append(skippedTaskIds, taskId)
		}
	}
	
	finalSize := se.queue.Size()
	logger.GetLogger().Infof("[SCHEDULER-UPDATE-QUEUE] Queue update complete: added=%d, skipped=%d, final queue size=%d", 
		addedCount, skippedCount, finalSize)
	
	if len(skippedTaskIds) > 0 {
		logger.GetLogger().Warnf("[SCHEDULER-UPDATE-QUEUE-SKIPPED] Skipped task IDs: %v", skippedTaskIds)
	}
	
	if finalSize != len(sortedTasks) {
		logger.GetLogger().Warnf("[SCHEDULER-UPDATE-QUEUE] WARNING: Queue size (%d) != sorted tasks count (%d) - %d tasks lost!", 
			finalSize, len(sortedTasks), len(sortedTasks)-finalSize)
	}
	
	// [DEBUG] Log task IDs after update
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-AFTER-GETALL-BEFORE] About to get queue tasks after update")
	taskIdsAfterUpdate := make([]string, 0, finalSize)
	allTasksAfter := se.queue.GetAll()
	// [DEBUG] Got queue tasks after update
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-AFTER-GETALL-AFTER] Got %d tasks from queue after update", len(allTasksAfter))
	for _, task := range allTasksAfter {
		taskIdsAfterUpdate = append(taskIdsAfterUpdate, task.GetTaskID())
	}
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-AFTER] Task IDs after update: %v (count: %d)", taskIdsAfterUpdate, finalSize)
	logger.GetLogger().Infof("[SCHEDULER-UPDATE-QUEUE-AFTER] Task IDs after update: %v (count: %d)", taskIdsAfterUpdate, finalSize)
	
	// [DEBUG] UpdateQueueWithSortedTasks complete
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-UPDATE-QUEUE-EXIT] updateQueueWithSortedTasks completed successfully")
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
	// [DEBUG] Entry point for sortQueueWithObjectives
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SORT-OBJECTIVES-ENTRY] sortQueueWithObjectives called with %d tasks", len(tasks))
	
	if len(tasks) <= 1 {
		// [DEBUG] Not enough tasks to sort
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SORT-OBJECTIVES-SKIP] Skipping sort (tasks <= 1: %d)", len(tasks))
		return tasks
	}
	
	// [DEBUG] Getting multi-objective configuration
	// Get current multi-objective configuration
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SORT-OBJECTIVES-CONFIG] Getting multi-objective configuration")
	activeProfile := se.config.RL.MultiObjective.ActiveProfile
	weights := se.config.RL.MultiObjective.Profiles[activeProfile].Weights
	
	// [DEBUG] Log current objective profile being used
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SORT-OBJECTIVES-PROFILE] Using multi-objective profile: %s", activeProfile)
	logger.GetLogger().Debugf("Using multi-objective profile: %s", activeProfile)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SORT-OBJECTIVES-WEIGHTS] Objective weights: Latency=%.2f, Throughput=%.2f, ResourceEff=%.2f, Fairness=%.2f, DeadlineMiss=%.2f, EnergyEff=%.2f",
		weights.Latency, weights.Throughput, weights.ResourceEfficiency, weights.Fairness, weights.DeadlineMiss, weights.EnergyEfficiency)
	logger.GetLogger().Debugf("Objective weights: Latency=%.2f, Throughput=%.2f, ResourceEff=%.2f, Fairness=%.2f, DeadlineMiss=%.2f, EnergyEff=%.2f",
		weights.Latency, weights.Throughput, weights.ResourceEfficiency, weights.Fairness, weights.DeadlineMiss, weights.EnergyEfficiency)
	
	// [DEBUG] About to call agent.Schedule
	// Use RL agent with multi-objective configuration
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SORT-OBJECTIVES-AGENT-BEFORE] About to call agent.Schedule with %d tasks", len(tasks))
	sortedTasks := se.agent.Schedule(tasks, se.nodeManager)
	// [DEBUG] agent.Schedule returned
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SORT-OBJECTIVES-AGENT-AFTER] agent.Schedule returned %d tasks", len(sortedTasks))
	
	// [DEBUG] Log sorting results
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SORT-OBJECTIVES-COMPLETE] Multi-objective queue sorting completed: %d tasks resorted", len(sortedTasks))
	logger.GetLogger().Debugf("Multi-objective queue sorting completed: %d tasks resorted", len(sortedTasks))
	
	// [DEBUG] About to return
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SORT-OBJECTIVES-EXIT] sortQueueWithObjectives returning %d tasks", len(sortedTasks))
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
	// [DEBUG] Entry point for AddTaskToQueueWithCache
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-ENTRY] AddTaskToQueueWithCache called: TaskID=%s, Name=%s, Type=%s, CPU=%d, Mem=%d, Priority=%d",
		task.TaskId, task.TaskName, task.TaskType.String(), task.CpuRequirement, task.MemoryRequirement, task.Priority)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-TASK-RECEIVE] Received task: TaskID=%s, Name=%s, Type=%s, CPU=%d, Mem=%d, Priority=%d",
		task.TaskId, task.TaskName, task.TaskType.String(), task.CpuRequirement, task.MemoryRequirement, task.Priority)
	
	// [DEBUG] Validating task
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-VALIDATE-BEFORE] About to validate task %s", task.TaskId)
	if err := ValidateTask(task); err != nil {
		// [DEBUG] Validation failed
		logger.GetLogger().Errorf("[DEBUG] [SCHEDULER-TASK-VALIDATE] Task %s validation failed: %v", task.TaskId, err)
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, err
	}
	// [DEBUG] Validation passed
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-VALIDATE-AFTER] Task %s validation passed", task.TaskId)

	// [DEBUG] Check for duplicates
	// Check if task is already scheduled (prevent duplicates)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-DUPLICATE-CHECK] Checking for duplicate task %s", task.TaskId)
	// Extract cloudletId from metadata (unique per tuple instance)
	// CRITICAL: Never fall back to TaskId - cloudletId is required for unique instance tracking
	cloudletId := ""
	if task.Metadata != nil {
		if cid, ok := task.Metadata["cloudlet_id"]; ok && cid != "" {
			cloudletId = cid  // ✅ Use cloudletId from metadata
			logger.GetLogger().Infof("[SCHEDULER-CLOUDLET-ID] Extracted cloudletId=%s from metadata (TaskId=%s)", cloudletId, task.TaskId)
			// [DEBUG-LOG] Log key extraction for ACK failure investigation
			logger.GetLogger().Errorf("[DEBUG-KEY-EXTRACTION] AddTaskToQueueWithCache: TaskId=%s, cloudletId extracted from metadata=%s, TaskId==cloudletId? %t", 
				task.TaskId, cloudletId, task.TaskId == cloudletId)
		} else {
			// CRITICAL: cloudlet_id metadata is required - do NOT fall back to TaskId
			logger.GetLogger().Errorf("[CRITICAL-ERROR] AddTaskToQueueWithCache: TaskId=%s, cloudlet_id NOT in metadata - REQUIRED for tracking unique instances", task.TaskId)
			return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("cloudlet_id metadata is required for task %s (missing metadata prevents unique instance tracking)", task.TaskId)
		}
	} else {
		// CRITICAL: metadata is nil - do NOT fall back to TaskId
		logger.GetLogger().Errorf("[CRITICAL-ERROR] AddTaskToQueueWithCache: TaskId=%s, metadata is nil - REQUIRED for tracking unique instances", task.TaskId)
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("task metadata is required for task %s (missing cloudlet_id prevents unique instance tracking)", task.TaskId)
	}

	// Note: Duplicate check will be done later when we acquire the write lock

	// Step 1: Generate pattern fingerprint (for RL state) and cache key (unique identifier)
	logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Step 1 - Generating fingerprint and cache key", task.TaskId)
	
	// Generate pattern fingerprint for RL state (pattern-based, excludes TaskId)
	fingerprint := se.cacheManager.GenerateTaskFingerprint(task)
	if fingerprint == "" {
		logger.GetLogger().Errorf("[SCHEDULER-FLOW-ERROR] Task %s: Failed to generate fingerprint", task.TaskId)
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("failed to generate fingerprint for task %s", task.TaskId)
	}
	
	// Generate cache key (unique per task instance, based on TaskId)
	cacheKey := se.cacheManager.GenerateCacheKey(task)
	if cacheKey == "" {
		logger.GetLogger().Errorf("[SCHEDULER-FLOW-ERROR] Task %s: Failed to generate cache key", task.TaskId)
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("failed to generate cache key for task %s", task.TaskId)
	}
	
	logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Fingerprint=%s (pattern), CacheKey=%s (unique)", task.TaskId, fingerprint, cacheKey)

	// Step 2: Check cache entry state using cache key (TaskId) - lazy cleanup
	logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Step 2 - Checking cache entry state (cacheKey=%s)", task.TaskId, cacheKey)
	var entryFirstSeen int64 = 0
	var entrySeenCount int = 0
	cacheExists := false
	cacheExpired := false
	cacheAgeCategory := "none"
	
	// Extract local_cache_exists from proto field (fog node's cache status)
	fogCacheExists := task.LocalCacheExists
	if fogCacheExists {
		logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Fog node reports local_cache_exists=true", task.TaskId)
	}

	if se.cacheManager != nil && se.cacheManager.config.Enabled {
		// Look up cache entry using cache key (TaskId), not fingerprint
		entry, exists := se.cacheManager.GetEntry(cacheKey)
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
				logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Cache entry EXPIRED (age=%.2f, TTL=%.2f) - Removing", task.TaskId, age.Seconds(), ttl.Seconds())
				se.cacheManager.RemoveEntry(cacheKey) // Remove by cache key (TaskId)
				cacheExists = false
				cacheExpired = true
				cacheAgeCategory = "expired"
				entryFirstSeen = 0
				entrySeenCount = 0
			} else {
				// Not expired - cache exists
				cacheAgeCategory = rl.CategorizeCacheAge(ttlRatio)
				logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Cache entry EXISTS (age=%.2f, TTL=%.2f, category=%s, seenCount=%d)", 
					task.TaskId, age.Seconds(), ttl.Seconds(), cacheAgeCategory, entrySeenCount)
				// Note: SeenCount will be updated in ProcessTask if using fallback
			}
		} else {
			logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: No cache entry found (first time seen)", task.TaskId)
		}
	}

	// Step 3: Cache Agent Decision (if enabled)
	logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Step 3 - Cache agent decision (enabled=%t)", 
		task.TaskId, se.cacheAgent != nil && se.cacheAgent.IsEnabled())
	var isCached bool
	// cacheKey is already declared in Step 1
	var cacheAction pb.CacheAction
	var cacheState *rl.CacheStateFeatures // Store for delayed reward
	var rlAction rl.Action                // Store for delayed reward

	// ALWAYS provide cache data to iFogSim, even when RL is disabled
	// This ensures compatibility between iFogSim fog node and server
	if se.cacheAgent != nil && se.cacheAgent.IsEnabled() {
		// RL enabled: Use cache agent for decisions
		// Extract cache state features
		hitRate := se.cacheManager.GetHitRate()
		systemLoad := se.nodeManager.GetCurrentLoad()
		queueSize := int32(0)
		if queueContext != nil {
			queueSize = queueContext.TotalQueueSize
		}
		cacheTTLHours := int(se.cacheManager.config.CacheTTLHours)

		logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Extracting cache state (hitRate=%.3f, load=%.3f, queueSize=%d)", 
			task.TaskId, hitRate, systemLoad, queueSize)

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

		logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Cache state extracted (exists=%t, ageCategory=%s, freq=%s, queue=%s, load=%s)", 
			task.TaskId, extractedState.CacheExists, extractedState.CacheAgeCategory, 
			extractedState.TaskFrequencyCategory, extractedState.QueueLengthCategory, extractedState.SystemLoadCategory)

		// Store state for delayed reward
		cacheState = extractedState

		// Cache agent decides action
		rlAction = se.cacheAgent.SelectAction(extractedState)
		logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Cache agent selected action: Type=%d", task.TaskId, rlAction.Type)

		// Map RL action to proto CacheAction (with expired handling and fog node cache consideration)
		cacheAction = rl.MapCacheActionToProto(
			rlAction,
			cacheExists && !cacheExpired,
			cacheExpired,
			fogCacheExists, // Pass fog node's cache status
		)

		// Determine isCached
		isCached = (cacheAction == pb.CacheAction_CACHE_ACTION_USE)
		logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: Mapped to cache action: %v, isCached=%t", 
			task.TaskId, cacheAction, isCached)

	} else {
		// RL disabled: Provide default cache data (CACHE_ACTION_NONE) for compatibility
		// This ensures iFogSim fog node always receives cache data, even with traditional algorithms
		logger.GetLogger().Infof("[SCHEDULER-FLOW-DEBUG] Task %s: RL disabled, using default cache behavior (NONE)", task.TaskId)
		
		// Default behavior: no cache action (normal task scheduling)
		isCached = false
		cacheAction = pb.CacheAction_CACHE_ACTION_NONE
		
		// Still update cache manager for statistics (if enabled)
		if se.cacheManager != nil && se.cacheManager.config.Enabled {
			_, _, _ = se.cacheManager.ProcessTask(task) // Update statistics, but don't use the action
		}
	}

	// Step 4: Handle cache invalidation if needed
	// Note: We remove by cache key (TaskId) since entries are keyed by cache key
	if cacheAction == pb.CacheAction_CACHE_ACTION_INVALIDATE && cacheExists {
		se.cacheManager.RemoveEntry(cacheKey)
	}

	// CacheKey is already set to TaskId (unique identifier) from Step 1
	// Fingerprint remains for RL state features only

	// [DEBUG] Log cache decision
	if isCached && cacheAction == pb.CacheAction_CACHE_ACTION_USE {
		logger.GetLogger().Infof("[SCHEDULER-CACHE-DEBUG] Task %s is CACHED (cacheKey=%s) - Adding to queue with cache flag",
			task.TaskId, cacheKey)
	} else {
		logger.GetLogger().Infof("[SCHEDULER-CACHE-DEBUG] Task %s is NOT cached (isCached=%t, action=%v) - Adding to queue",
			task.TaskId, isCached, cacheAction)
	}

	// [DEBUG] Creating task entry
	// Create task entry WITH cache information
	// IMPORTANT: ALL tasks (cached or not) go through the queue
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-ENTRY-CREATE] About to create TaskEntry for TaskID=%s", task.TaskId)
	taskEntry := NewTaskEntry(task)
	taskEntry.IsCached = isCached
	taskEntry.CacheKey = cacheKey
	taskEntry.CacheAction = cacheAction
	// [DEBUG] TaskEntry created
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-ENTRY-CREATED] TaskEntry created: TaskID=%s, IsCached=%t, CacheKey=%s, CacheAction=%s",
		taskEntry.GetTaskID(), taskEntry.IsCached, taskEntry.CacheKey, taskEntry.CacheAction.String())
	
	// Store cache state and RL action for delayed reward (if cache agent made the decision)
	if se.cacheAgent != nil && se.cacheAgent.IsEnabled() && cacheState != nil && rlAction.Type != 0 {
		taskEntry.CacheState = cacheState
		taskEntry.CacheRLAction = &rlAction
	}

	// [DEBUG] About to enqueue task
	// Step 4: Add to queue (ALL tasks go through queue, cache decision is made during execution)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-FLOW-DEBUG] Task %s: Step 4 - Adding to queue (isCached=%t, cacheAction=%v)", 
		task.TaskId, isCached, cacheAction)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-ENQUEUE-BEFORE] About to enqueue TaskID=%s", task.TaskId)
	queueSizeBeforeEnqueue := se.queue.Size()
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-ENQUEUE-BEFORE-SIZE] Queue size before enqueue: %d", queueSizeBeforeEnqueue)
	if err := se.queue.Enqueue(taskEntry); err != nil {
		// [DEBUG] Enqueue failed
		logger.GetLogger().Errorf("[DEBUG] [SCHEDULER-FLOW-ERROR] Task %s: Failed to enqueue: %v", task.TaskId, err)
		logger.GetLogger().Errorf("[DEBUG] [SCHEDULER-CACHE-DEBUG] Failed to enqueue task %s: %v", task.TaskId, err)
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, err
	}
	// [DEBUG] Enqueue successful
	queueSizeAfterEnqueue := se.queue.Size()
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-ENQUEUE-AFTER] TaskID=%s enqueued successfully (queue size: %d -> %d)", 
		task.TaskId, queueSizeBeforeEnqueue, queueSizeAfterEnqueue)

	// [DEBUG] About to add to scheduledTasks map and update statistics
	// Track scheduled task for delayed rewards and update statistics in a single lock acquisition
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-SCHEDULED-MAP-BEFORE] About to add TaskID=%s to scheduledTasks map", task.TaskId)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-SCHEDULED-MAP-LOCK-BEFORE] About to acquire write lock for scheduledTasks and statistics")
	se.mu.Lock()
	// [DEBUG] Lock acquired
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-SCHEDULED-MAP-LOCK-ACQUIRED] Write lock acquired for scheduledTasks")
	
	// Check for duplicate (now that we have the lock)
	if _, exists := se.scheduledTasks[cloudletId]; exists {  // ✅ Check using cloudletId
		// [DEBUG] Duplicate found
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-DUPLICATE-FOUND] Task %s (cloudletId=%s) already exists in scheduledTasks", task.TaskId, cloudletId)
		se.mu.Unlock()
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("task %s (cloudletId=%s) already scheduled", task.TaskId, cloudletId)
	}
	
	// Add to scheduledTasks and update statistics in the same lock
	se.scheduledTasks[cloudletId] = taskEntry  // ✅ Store using cloudletId (unique)
	se.totalTasksProcessed++
	totalProcessed := se.totalTasksProcessed
	// [DEBUG] Added to scheduledTasks
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-SCHEDULED-MAP-ADDED] TaskID=%s (cloudletId=%s) added to scheduledTasks map (size: %d)", task.TaskId, cloudletId, len(se.scheduledTasks))
			// [DEBUG-LOG] Log exact key used for storage
			logger.GetLogger().Errorf("[DEBUG-KEY-STORAGE] AddTaskToQueueWithCache: Storing task in scheduledTasks with key='%s' (TaskId='%s', cloudletId='%s', keysMatch=%t)", 
				cloudletId, task.TaskId, cloudletId, task.TaskId == cloudletId)
	se.mu.Unlock()
	// [DEBUG] Lock released
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-SCHEDULED-MAP-LOCK-RELEASED] Write lock released for scheduledTasks")

	// [DEBUG] Log queue state after adding
	queueSizeAfterAdd := se.queue.Size()
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-FLOW-DEBUG] Task %s: After enqueue - queue size=%d, scheduledTasks size=%d (isCached=%t, cacheAction=%v)",
		task.TaskId, queueSizeAfterAdd, len(se.scheduledTasks), isCached, cacheAction)

	// Calculate queue position and estimated wait time
	queuePosition := int64(se.getTaskQueuePosition(task.TaskId))
	estimatedWait := se.calculateEstimatedWaitTime(queuePosition)

	// Log cache decision for RL algorithms
	if se.agent != nil && se.agent.IsEnabled() {
		if isCached {
			logger.GetLogger().Infof("[SCHEDULER-TASK-CACHE] Task %s cache decision: %s (key: %s)", 
				task.TaskId, cacheAction.String(), cacheKey)
		}
	}
	
	// [DEBUG] Log final result
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-TASK-ENQUEUED] Task %s enqueued successfully: Position=%d, WaitTime=%dms, Cached=%t, TotalProcessed=%d",
		task.TaskId, queuePosition, estimatedWait, isCached, totalProcessed)
	logger.GetLogger().Infof("[SCHEDULER-TASK-ENQUEUED] Task %s enqueued successfully: Position=%d, WaitTime=%dms, Cached=%t, TotalProcessed=%d",
		task.TaskId, queuePosition, estimatedWait, isCached, totalProcessed)

	// [DEBUG] About to return from AddTaskToQueueWithCache
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-ADD-TASK-EXIT] AddTaskToQueueWithCache returning: TaskID=%s, Position=%d, Wait=%d, Cached=%t, CacheKey=%s, Action=%s",
		task.TaskId, queuePosition, estimatedWait, isCached, cacheKey, cacheAction.String())
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
	logger.GetLogger().Infof("[SCHEDULER-COMPLETION-RECEIVE] Received completion report: TaskID=%s", req.TaskId)
	
	se.mu.Lock()
	defer se.mu.Unlock()

	// [DEBUG-LOG] Log lookup key and all available keys for ACK failure investigation
	logger.GetLogger().Errorf("[DEBUG-KEY-LOOKUP] ProcessTaskCompletion: Looking up task with req.TaskId='%s'", req.TaskId)
	allKeys := make([]string, 0, len(se.scheduledTasks))
	for key := range se.scheduledTasks {
		allKeys = append(allKeys, key)
	}
	logger.GetLogger().Errorf("[DEBUG-KEY-LOOKUP] ProcessTaskCompletion: scheduledTasks map contains %d keys: %v", len(allKeys), allKeys)

	// Find the task in scheduled tasks (for delayed rewards)
	task, exists := se.scheduledTasks[req.TaskId]
	if !exists {
		// Fallback: Try to find task by cloudletId from stored task metadata
		logger.GetLogger().Errorf("[DEBUG-KEY-LOOKUP] ProcessTaskCompletion: Primary lookup failed for req.TaskId='%s', trying fallback lookup", req.TaskId)
		found := false
		for key, storedTask := range se.scheduledTasks {
			if storedTask.Task.Metadata != nil {
				if cid, ok := storedTask.Task.Metadata["cloudlet_id"]; ok && cid == req.TaskId {
					// Found by cloudletId in metadata
					logger.GetLogger().Errorf("[DEBUG-KEY-LOOKUP] ProcessTaskCompletion: Fallback lookup SUCCESS - found task with key='%s' matching cloudletId='%s'", key, req.TaskId)
					task = storedTask
					exists = true
					found = true
					break
				}
			}
		}
		if !found {
			logger.GetLogger().Warnf("[SCHEDULER-COMPLETION-ERROR] Task %s not found in scheduled tasks", req.TaskId)
			// [DEBUG-LOG] Log lookup failure details
			logger.GetLogger().Errorf("[DEBUG-KEY-LOOKUP] ProcessTaskCompletion: LOOKUP FAILED - req.TaskId='%s' not found in scheduledTasks. Available keys: %v", 
				req.TaskId, allKeys)
			return fmt.Errorf("task %s not found in scheduled tasks", req.TaskId)
		}
	}
	// [DEBUG-LOG] Log successful lookup
	logger.GetLogger().Errorf("[DEBUG-KEY-LOOKUP] ProcessTaskCompletion: LOOKUP SUCCESS - req.TaskId='%s' found in scheduledTasks", req.TaskId)

	// Derive success from completion report
	success := se.deriveTaskSuccess(req)
	errorMessage := se.deriveErrorMessage(req)

	// Update task status based on completion report
	if success {
		task.MarkCompleted()
		se.totalTasksCompleted++
		logger.GetLogger().Infof("[SCHEDULER-COMPLETION-SUCCESS] Task %s marked as completed (TotalCompleted=%d)", 
			req.TaskId, se.totalTasksCompleted)
	} else {
		task.MarkFailed(errorMessage)
		se.totalTasksFailed++
		logger.GetLogger().Warnf("[SCHEDULER-COMPLETION-FAILED] Task %s marked as failed: %s (TotalFailed=%d)", 
			req.TaskId, errorMessage, se.totalTasksFailed)
	}

	// Update execution time if provided
	actualExecutionTimeMs := se.deriveActualExecutionTime(req)
	if actualExecutionTimeMs > 0 {
		actualDuration := time.Duration(actualExecutionTimeMs) * time.Millisecond
		se.totalExecutionTime += actualDuration
	}

	// Extract node status from completion report
	var nodeStatus *pb.FogNode
	if req.NodeStatus != nil {
		nodeStatus = req.NodeStatus
		logger.GetLogger().Infof("[SCHEDULER-COMPLETION-NODE-STATUS] Task=%s, Node=%s, CPU=%.2f%%, Memory=%d MB",
			req.TaskId, nodeStatus.NodeId,
			float64(nodeStatus.CurrentUsage.CpuUsage),
			nodeStatus.CurrentUsage.MemoryUsageMb)
	} else {
		logger.GetLogger().Warnf("[SCHEDULER-COMPLETION-NO-NODE-STATUS] Task=%s has no node status in completion report", req.TaskId)
	}

	// Get actual current queue length (before task is removed from scheduled tasks)
	// This is the accurate queue length at completion time
	currentQueueLength := se.queue.Size()
	logger.GetLogger().Infof("[SCHEDULER-COMPLETION-QUEUE-LENGTH] Task=%s, CurrentQueueLength=%d", req.TaskId, currentQueueLength)

	// **KEY PART: Delegate to Agent for RL experience handling** (before deleting from map)
	fmt.Printf("[DEBUG] [SCHEDULER-COMPLETE-AGENT-CHECK] Agent check: agent=%t, enabled=%t, TaskID=%s\n", 
		se.agent != nil, se.agent != nil && se.agent.IsEnabled(), req.TaskId)
	logger.GetLogger().Infof("[SCHEDULER-COMPLETE-AGENT-CHECK] Agent check: agent=%t, enabled=%t, TaskID=%s", 
		se.agent != nil, se.agent != nil && se.agent.IsEnabled(), req.TaskId)
	
	if se.agent != nil && se.agent.IsEnabled() {
		// The Agent should handle experience collection through AlgorithmManager
		// Pass actual queue length for accurate next state calculation
		fmt.Printf("[DEBUG] [SCHEDULER-COMPLETE-AGENT-CALL] Calling reportTaskCompletionToAgent: TaskID=%s, QueueLength=%d\n", 
			req.TaskId, currentQueueLength)
		logger.GetLogger().Infof("[SCHEDULER-COMPLETE-AGENT-CALL] Calling reportTaskCompletionToAgent: TaskID=%s, QueueLength=%d", 
			req.TaskId, currentQueueLength)
		
		if err := se.reportTaskCompletionToAgent(task, req, nodeStatus, currentQueueLength); err != nil {
			// Log error but don't fail the whole operation
			fmt.Printf("[DEBUG] [SCHEDULER-COMPLETE-AGENT-ERROR] Failed to report completion to RL agent: TaskID=%s, Error=%v\n", 
				req.TaskId, err)
			logger.GetLogger().Warnf("[SCHEDULER-COMPLETE-AGENT-ERROR] Failed to report completion to RL agent: TaskID=%s, Error=%v", 
				req.TaskId, err)
		} else {
			fmt.Printf("[DEBUG] [SCHEDULER-COMPLETE-AGENT-SUCCESS] Successfully reported to RL agent: TaskID=%s\n", req.TaskId)
			logger.GetLogger().Infof("[SCHEDULER-COMPLETE-AGENT-SUCCESS] Successfully reported to RL agent: TaskID=%s", req.TaskId)
		}
	} else {
		fmt.Printf("[DEBUG] [SCHEDULER-COMPLETE-AGENT-SKIP] Skipping agent report: agent=%t, enabled=%t, TaskID=%s\n", 
			se.agent != nil, se.agent != nil && se.agent.IsEnabled(), req.TaskId)
		logger.GetLogger().Warnf("[SCHEDULER-COMPLETE-AGENT-SKIP] Skipping agent report: agent=%t, enabled=%t, TaskID=%s", 
			se.agent != nil, se.agent != nil && se.agent.IsEnabled(), req.TaskId)
	}

	// **NEW: Process cache agent delayed reward** (before deleting from map)
	logger.GetLogger().Infof("[SCHEDULER-TASK-COMPLETE] Processing completion for task %s (cache agent enabled: %t)", 
		req.TaskId, se.cacheAgent != nil && se.cacheAgent.IsEnabled())
	if se.cacheAgent != nil && se.cacheAgent.IsEnabled() {
		if err := se.reportTaskCompletionToCacheAgent(task, req); err != nil {
			// Log error but don't fail the whole operation
			logger.GetLogger().Warnf("[SCHEDULER-TASK-COMPLETE-ERROR] Failed to report completion to cache agent: %v", err)
		} else {
			logger.GetLogger().Infof("[SCHEDULER-TASK-COMPLETE-SUCCESS] Cache agent reward updated for task %s", req.TaskId)
		}
	}

	// **CRITICAL: Remove from scheduled tasks to prevent duplicate scheduling** (after processing rewards)
	// [DEBUG-LOG] Log removal key
	logger.GetLogger().Errorf("[DEBUG-KEY-REMOVAL] ProcessTaskCompletion: Removing from scheduledTasks with key='%s'", req.TaskId)
	delete(se.scheduledTasks, req.TaskId)
	
	// **CRITICAL: Remove from queue to prevent re-sending completed tasks**
	// This ensures GetSortedQueue() only returns uncompleted tasks
	// [DEBUG-LOG] Log queue removal key
	logger.GetLogger().Errorf("[DEBUG-KEY-REMOVAL] ProcessTaskCompletion: Removing from queue with key='%s'", req.TaskId)
	removedTask := se.queue.Remove(req.TaskId)
	if removedTask != nil {
		logger.GetLogger().Infof("[SCHEDULER-COMPLETION-QUEUE-REMOVED] Task %s removed from queue (queue size: %d -> %d)",
			req.TaskId, se.queue.Size()+1, se.queue.Size())
	} else {
		// Task might have been removed already or was never in queue (e.g., cached task)
		logger.GetLogger().Debugf("[SCHEDULER-COMPLETION-QUEUE-NOT-FOUND] Task %s not found in queue (may have been removed already or was cached)",
			req.TaskId)
	}
	
	logger.GetLogger().Infof("[SCHEDULER-COMPLETION-DONE] Task %s completion processed successfully (RemainingScheduled=%d, QueueSize=%d)",
		req.TaskId, len(se.scheduledTasks), se.queue.Size())

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
func (se *SchedulerEngine) reportTaskCompletionToAgent(task *TaskEntry, req *pb.TaskCompletionReport, nodeStatus *pb.FogNode, queueLength int) error {
	fmt.Printf("[DEBUG] [SCHEDULER-REPORT-AGENT-ENTRY] reportTaskCompletionToAgent called: TaskID=%s, QueueLength=%d, HasNodeStatus=%t\n", 
		req.TaskId, queueLength, nodeStatus != nil)
	logger.GetLogger().Infof("[SCHEDULER-REPORT-AGENT-ENTRY] reportTaskCompletionToAgent: TaskID=%s, QueueLength=%d, HasNodeStatus=%t", 
		req.TaskId, queueLength, nodeStatus != nil)
	
	if se.agent == nil || !se.agent.IsEnabled() {
		fmt.Printf("[DEBUG] [SCHEDULER-REPORT-AGENT-SKIP] Agent not enabled: TaskID=%s\n", req.TaskId)
		return nil // Agent not enabled or initialized
	}

	// Pass node status and actual queue length from completion report to the agent
	fmt.Printf("[DEBUG] [SCHEDULER-REPORT-AGENT-CALL] Calling agent.ProcessTaskCompletionWithNodeStatus: TaskID=%s\n", req.TaskId)
	err := se.agent.ProcessTaskCompletionWithNodeStatus(task, req, nodeStatus, queueLength)
	if err != nil {
		fmt.Printf("[DEBUG] [SCHEDULER-REPORT-AGENT-ERROR] Agent.ProcessTaskCompletionWithNodeStatus failed: TaskID=%s, Error=%v\n", 
			req.TaskId, err)
		logger.GetLogger().Errorf("[SCHEDULER-REPORT-AGENT-ERROR] Agent.ProcessTaskCompletionWithNodeStatus failed: TaskID=%s, Error=%v", 
			req.TaskId, err)
	} else {
		fmt.Printf("[DEBUG] [SCHEDULER-REPORT-AGENT-SUCCESS] Agent.ProcessTaskCompletionWithNodeStatus succeeded: TaskID=%s\n", req.TaskId)
		logger.GetLogger().Infof("[SCHEDULER-REPORT-AGENT-SUCCESS] Agent.ProcessTaskCompletionWithNodeStatus succeeded: TaskID=%s", req.TaskId)
	}
	return err
}

// reportTaskCompletionToCacheAgent processes completion for cache agent delayed reward
func (se *SchedulerEngine) reportTaskCompletionToCacheAgent(task *TaskEntry, req *pb.TaskCompletionReport) error {
	// [DEBUG] Entry point for reportTaskCompletionToCacheAgent
	fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-ENTRY] reportTaskCompletionToCacheAgent called: TaskID=%s\n", req.TaskId)
	
	if se.cacheAgent == nil || !se.cacheAgent.IsEnabled() {
		// [DEBUG] Cache agent not enabled
		fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-SKIP] Cache agent not enabled or nil\n")
		return nil // Cache agent not enabled
	}

	// Check if we have cache state and action stored (cache agent made the decision)
	if task.CacheState == nil || task.CacheRLAction == nil {
		// [DEBUG] Cache agent didn't make the decision
		fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-SKIP] Cache agent didn't make decision (fallback mode): CacheState=%v, CacheRLAction=%v\n",
			task.CacheState != nil, task.CacheRLAction != nil)
		// Cache agent didn't make the decision (fallback mode) - skip reward update
		return nil
	}
	
	// [DEBUG] Cache agent made the decision
	fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-PROCESS] Cache agent made decision, processing reward: TaskID=%s, Action=%v\n",
		req.TaskId, task.CacheRLAction.Type)

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

	// [DEBUG] About to calculate reward
	fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-CALC-BEFORE] About to calculate reward: Action=%v, TimeSaved=%dms, HitSuccess=%t, Load=%.3f\n",
		task.CacheAction, executionTimeSaved, cacheHitSuccess, systemLoad)
	
	// Calculate reward
	reward := rl.CalculateCacheReward(
		task.CacheAction,
		executionTimeSaved,
		cacheHitSuccess,
		systemLoad,
	)
	
	// [DEBUG] Reward calculated
	fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-CALC-AFTER] Reward calculated: %.3f\n", reward)
	
	logger.GetLogger().Infof("[SCHEDULER-CACHE-REWARD] Task %s: Calculated reward=%.3f (action=%v, timeSaved=%dms, hitSuccess=%t, load=%.3f)", 
		req.TaskId, reward, task.CacheAction, executionTimeSaved, cacheHitSuccess, systemLoad)

	// Create next state (current state after task completion)
	// For cache agent, next state is similar to current state but with updated metrics
	nextState := task.CacheState // Use same state (or extract new state)
	// Note: In a full implementation, we'd extract the new state from current system metrics
	// For now, use the same state as next state (episodic learning)

	// Update cache agent with reward
	done := true // Task is complete, episode is done
	
	// [DEBUG] About to update cache agent
	fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-UPDATE-BEFORE] About to update cache agent Q-table: TaskID=%s, Reward=%.3f, Done=%t\n",
		req.TaskId, reward, done)
	
	logger.GetLogger().Infof("[SCHEDULER-CACHE-REWARD] Task %s: Updating cache agent Q-table with reward", req.TaskId)
	err := se.cacheAgent.UpdateReward(
		task.CacheState,
		*task.CacheRLAction,
		reward,
		nextState,
		done,
	)

	if err != nil {
		// [DEBUG] Error updating cache agent
		fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-UPDATE-ERROR] Failed to update cache agent: %v\n", err)
		return fmt.Errorf("failed to update cache agent reward: %w", err)
	}

	// [DEBUG] Cache agent updated successfully
	fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-UPDATE-SUCCESS] Cache agent updated successfully: TaskID=%s\n", req.TaskId)

	logger.GetLogger().Infof("[CACHE-AGENT-REWARD] Task %s: Action=%v, Reward=%.2f, CacheHitSuccess=%t, TimeSaved=%dms",
		task.Task.TaskId, task.CacheRLAction.Type, reward, cacheHitSuccess, executionTimeSaved)

	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [SCHEDULER-CACHE-REWARD-EXIT] reportTaskCompletionToCacheAgent returning successfully: TaskID=%s\n", req.TaskId)
	return nil
}

// GetAgent returns the RL agent for external access
func (se *SchedulerEngine) GetAgent() *rl.Agent {
	se.mu.RLock()
	defer se.mu.RUnlock()
	return se.agent
}

// GetCacheAgent returns the cache RL agent
func (se *SchedulerEngine) GetCacheAgent() *rl.CacheAgent {
	se.mu.RLock()
	defer se.mu.RUnlock()
	return se.cacheAgent
}

// GetSortedQueue returns the current sorted queue as proto tasks
// CRITICAL: Resorts queue on-demand before returning to ensure fresh, sorted order
// FIX: Simplified lock acquisition to prevent deadlocks - use single write lock for all operations
func (se *SchedulerEngine) GetSortedQueue(includeMetadata bool) *pb.GetSortedQueueResponse {
	// [DEBUG] Entry point for GetSortedQueue
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-START] GetSortedQueue called (on-demand resorting, queue size before resort=%d)", 
		se.queue.Size())
	
	// [DEBUG] About to call resortQueue
	// CRITICAL: Resort queue FIRST to ensure latest sorted order before sending
	// This eliminates race conditions from periodic resorting and guarantees fresh queue
	// Note: resortQueue() acquires its own lock, so we call it before acquiring our lock
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RESORT-BEFORE] About to call resortQueue()")
	se.resortQueue() // This will lock internally and apply algorithm/RL policy
	// [DEBUG] ResortQueue completed
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RESORT-AFTER] resortQueue() completed, queue size after resort: %d", se.queue.Size())
	
	// FIX: Acquire write lock ONCE for all operations (prevents deadlock from multiple lock acquisitions)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-LOCK-BEFORE] About to acquire write lock (Lock)")
	se.mu.Lock()
	// [DEBUG] Write lock acquired
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-LOCK-ACQUIRED] Write lock acquired successfully")
	defer func() {
		// [DEBUG] About to release write lock
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-LOCK-RELEASE] Releasing write lock")
		se.mu.Unlock()
		// [DEBUG] Write lock released
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-LOCK-RELEASED] Write lock released")
	}()

	// [DEBUG] About to get all tasks from queue
	// Get all tasks from queue (now freshly resorted)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-GETALL-BEFORE] About to call queue.GetAll()")
	allTasks := se.queue.GetAll()
	// [DEBUG] Got all tasks
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-GETALL-AFTER] queue.GetAll() returned %d tasks", len(allTasks))
	
	// [DEBUG] Log retrieved tasks
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RETRIEVE] Retrieved %d tasks from queue for response", len(allTasks))
	
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
	
	// CRITICAL FIX (Issue 1): Add tasks to scheduledTasks if not already present
	// This ensures tasks sent via GetSortedQueue are tracked for completion reports
	// NOTE: We're already holding the write lock, so we can safely modify scheduledTasks
	tasksAddedToScheduled := 0
	for _, taskEntry := range allTasks {
		// Extract cloudletId from metadata (same as AddTaskToQueueWithCache)
		// CRITICAL: Never fall back to TaskId - cloudletId is required for unique instance tracking
		cloudletId := ""
		if taskEntry.Task.Metadata != nil {
			if cid, ok := taskEntry.Task.Metadata["cloudlet_id"]; ok && cid != "" {
				cloudletId = cid
				// [DEBUG-LOG] Log key extraction for ACK failure investigation
				logger.GetLogger().Errorf("[DEBUG-KEY-EXTRACTION] GetSortedQueue: TaskId=%s, cloudletId extracted from metadata=%s, TaskId==cloudletId? %t", 
					taskEntry.Task.TaskId, cloudletId, taskEntry.Task.TaskId == cloudletId)
			} else {
				// CRITICAL: cloudlet_id metadata is required - do NOT fall back to TaskId
				logger.GetLogger().Errorf("[CRITICAL-ERROR] GetSortedQueue: TaskId=%s, cloudlet_id NOT in metadata - SKIPPING scheduledTasks tracking (required for unique instance tracking)", taskEntry.Task.TaskId)
				continue // Skip this task - cannot track without cloudletId
			}
		} else {
			// CRITICAL: metadata is nil - do NOT fall back to TaskId
			logger.GetLogger().Errorf("[CRITICAL-ERROR] GetSortedQueue: TaskId=%s, metadata is nil - SKIPPING scheduledTasks tracking (required for unique instance tracking)", taskEntry.Task.TaskId)
			continue // Skip this task - cannot track without cloudletId
		}
		
		// Check if task is already in scheduledTasks
		if _, exists := se.scheduledTasks[cloudletId]; !exists {
			// Add to scheduledTasks using cloudletId as key (unique)
			se.scheduledTasks[cloudletId] = taskEntry
			tasksAddedToScheduled++
			logger.GetLogger().Infof("[SCHEDULER-GET-QUEUE-SCHEDULED] Added task to scheduledTasks: cloudletId=%s, TaskID=%s (scheduledTasks size: %d)",
				cloudletId, taskEntry.Task.TaskId, len(se.scheduledTasks))
			// [DEBUG-LOG] Log exact key used for storage
			logger.GetLogger().Errorf("[DEBUG-KEY-STORAGE] GetSortedQueue: Storing task in scheduledTasks with key='%s' (TaskId='%s', cloudletId='%s', keysMatch=%t)", 
				cloudletId, taskEntry.Task.TaskId, cloudletId, taskEntry.Task.TaskId == cloudletId)
		} else {
			logger.GetLogger().Debugf("[SCHEDULER-GET-QUEUE-SCHEDULED] Task already in scheduledTasks: cloudletId=%s, TaskID=%s",
				cloudletId, taskEntry.Task.TaskId)
			// [DEBUG-LOG] Log existing entry for ACK failure investigation
			logger.GetLogger().Debugf("[DEBUG-KEY-STORAGE] GetSortedQueue: Task already exists in scheduledTasks with key='%s' (TaskId='%s')", 
				cloudletId, taskEntry.Task.TaskId)
		}
	}
	
	if tasksAddedToScheduled > 0 {
		logger.GetLogger().Infof("[SCHEDULER-GET-QUEUE-SCHEDULED] Added %d tasks to scheduledTasks (total scheduledTasks: %d)",
			tasksAddedToScheduled, len(se.scheduledTasks))
	}
	
	// [DEBUG] Starting proto conversion
	// Convert to proto tasks WITH cache information in metadata
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-PROTO-START] Starting proto conversion for %d tasks", len(allTasks))
	protoTasks := make([]*pb.Task, 0, len(allTasks))
	
	for i, taskEntry := range allTasks {
		// [DEBUG] Converting each task
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-PROTO-TASK] Converting task %d/%d: TaskID=%s", i+1, len(allTasks), taskEntry.GetTaskID())
		protoTask := se.taskEntryToProtoTaskWithCache(taskEntry)
		// [DEBUG] Task converted successfully
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-PROTO-TASK-DONE] Task %d converted: TaskID=%s, Type=%s", i+1, protoTask.TaskId, protoTask.TaskType.String())
		protoTasks = append(protoTasks, protoTask)
	}
	// [DEBUG] All tasks converted
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-PROTO-COMPLETE] Proto conversion complete: %d tasks converted", len(protoTasks))

	// [DEBUG] Log response details
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RESPONSE] Returning %d tasks to iFogSim (algorithm=%s, nodeId=%s, includeMetadata=%t)",
		len(protoTasks), se.algorithm.String(), se.nodeManager.NodeID, includeMetadata)

	// [DEBUG] Building response struct
	// Build response
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RESPONSE-BUILD-START] Building GetSortedQueueResponse struct")
	response := &pb.GetSortedQueueResponse{
		SortedTasks:   protoTasks,
		AlgorithmUsed: se.algorithm.String(),
		QueueSize:     int64(len(allTasks)),
		Timestamp:     time.Now().Unix(),
		NodeId:        se.nodeManager.NodeID,
	}
	// [DEBUG] Response struct built
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RESPONSE-BUILD-DONE] Response struct built: Tasks=%d, QueueSize=%d, Timestamp=%d",
		len(response.SortedTasks), response.QueueSize, response.Timestamp)

	// [DEBUG] Adding metadata if requested
	// Add metadata if requested
	if includeMetadata {
		// [DEBUG] Metadata requested, building metadata map
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-METADATA-START] Building metadata map")
		response.Metadata = map[string]string{
			"objective":           se.objective.String(),
			"scheduled_tasks":     fmt.Sprintf("%d", len(se.scheduledTasks)),
			"total_processed":     fmt.Sprintf("%d", se.totalTasksProcessed),
			"total_completed":        fmt.Sprintf("%d", se.totalTasksCompleted),
			"total_failed":        fmt.Sprintf("%d", se.totalTasksFailed),
			"success_rate":        fmt.Sprintf("%.2f", se.getSuccessRate()),
			"node_utilization":   fmt.Sprintf("%.2f", se.nodeManager.GetCurrentLoad()),
		}
		// [DEBUG] Metadata built
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-METADATA-DONE] Metadata built with %d entries", len(response.Metadata))
	} else {
		// [DEBUG] Metadata not requested
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-METADATA-SKIP] Metadata not requested, skipping")
	}

	// [DEBUG] About to return response
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RETURN] Returning response with %d tasks", len(response.SortedTasks))
	return response
}

// taskEntryToProtoTaskWithCache converts TaskEntry to proto Task with cache info in metadata
func (se *SchedulerEngine) taskEntryToProtoTaskWithCache(taskEntry *TaskEntry) *pb.Task {
	// [DEBUG] Starting proto task conversion
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROTO-CONVERT-START] Converting TaskEntry to proto Task: TaskID=%s", taskEntry.Task.TaskId)
	
	// [DEBUG] Creating task copy
	// Create a copy of the task with cache info in metadata
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROTO-CONVERT-CREATE] Creating proto Task struct for TaskID=%s", taskEntry.Task.TaskId)
	taskCopy := &pb.Task{
		TaskId:          taskEntry.Task.TaskId,
		TaskName:        taskEntry.Task.TaskName,
		TaskType:        taskEntry.Task.TaskType,
		CpuRequirement:  taskEntry.Task.CpuRequirement,
		MemoryRequirement: taskEntry.Task.MemoryRequirement,
		ExecutionTime:   taskEntry.Task.ExecutionTime,
		OutputSize:      taskEntry.Task.OutputSize,      // ✅ Copy output_size from original task
		Priority:        taskEntry.Task.Priority,
		Deadline:        taskEntry.Task.Deadline,
		Dependencies:    taskEntry.Task.Dependencies,
		LocalCacheExists: taskEntry.Task.LocalCacheExists, // ✅ Copy local_cache_exists from original task
	}
	// [DEBUG] Task struct created
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROTO-CONVERT-CREATE-DONE] Proto Task struct created: TaskID=%s, Type=%s, CPU=%d",
		taskCopy.TaskId, taskCopy.TaskType.String(), taskCopy.CpuRequirement)
	
	// [DEBUG] Copying metadata
	// Copy existing metadata if any
	if taskEntry.Task.Metadata != nil {
		// [DEBUG] Metadata exists, copying
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROTO-CONVERT-METADATA] Copying existing metadata: %d entries", len(taskEntry.Task.Metadata))
		taskCopy.Metadata = make(map[string]string)
		for k, v := range taskEntry.Task.Metadata {
			taskCopy.Metadata[k] = v
		}
		// [DEBUG] Metadata copied
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROTO-CONVERT-METADATA-DONE] Metadata copied: %d entries", len(taskCopy.Metadata))
	} else {
		// [DEBUG] No existing metadata
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROTO-CONVERT-METADATA] No existing metadata, creating new map")
		taskCopy.Metadata = make(map[string]string)
	}
	
	// [DEBUG] Adding cache information
	// Add cache information to metadata
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROTO-CONVERT-CACHE] Adding cache info: IsCached=%t, CacheKey=%s, Action=%s",
		taskEntry.IsCached, taskEntry.CacheKey, taskEntry.CacheAction.String())
	if taskEntry.IsCached {
		taskCopy.Metadata["is_cached"] = "true"
	} else {
		taskCopy.Metadata["is_cached"] = "false"
	}
	taskCopy.Metadata["cache_key"] = taskEntry.CacheKey
	taskCopy.Metadata["cache_action"] = taskEntry.CacheAction.String()
	// [DEBUG] Cache info added
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROTO-CONVERT-CACHE-DONE] Cache info added to metadata")
	
	// [DEBUG] Conversion complete
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROTO-CONVERT-DONE] Proto conversion complete: TaskID=%s", taskCopy.TaskId)
	return taskCopy
}

// GetQueueUpdateResponse creates a queue update response for streaming
// CRITICAL: Resorts queue on-demand before returning to ensure fresh, sorted order
func (se *SchedulerEngine) GetQueueUpdateResponse(updateReason string, includeMetadata bool) *pb.QueueUpdateResponse {
	// CRITICAL: Resort queue FIRST to ensure latest sorted order before sending
	// This eliminates race conditions and guarantees fresh queue on every request
	// Note: resortQueue() acquires its own lock, so we call it before acquiring our lock
	logger.GetLogger().Infof("[SCHEDULER-GET-QUEUE-START] GetQueueUpdateResponse called (on-demand resorting, queue size before resort=%d)", 
		se.queue.Size())
	se.resortQueue() // This will lock internally and apply algorithm/RL policy
	logger.GetLogger().Infof("[SCHEDULER-GET-QUEUE-AFTER-RESORT] Queue size after resort: %d", se.queue.Size())
	
	// Get all tasks from queue (now freshly resorted) - no lock needed for queue.GetAll()
	allTasks := se.queue.GetAll()
	
	logger.GetLogger().Infof("[SCHEDULER-GET-QUEUE-UPDATE-RETRIEVE] Retrieved %d tasks from queue for streaming update (reason=%s)",
		len(allTasks), updateReason)
	
	// FIX: Acquire write lock ONCE for all operations (prevents deadlock from multiple lock acquisitions)
	se.mu.Lock()
	defer se.mu.Unlock()
	
	// CRITICAL FIX (Issue 1): Add tasks to scheduledTasks if not already present
	// This ensures tasks sent via GetQueueUpdateResponse (streaming) are tracked for completion reports
	// NOTE: We're already holding the write lock, so we can safely modify scheduledTasks
	tasksAddedToScheduled := 0
	for _, taskEntry := range allTasks {
		// Extract cloudletId from metadata (same as AddTaskToQueueWithCache and GetSortedQueue)
		// CRITICAL: Never fall back to TaskId - cloudletId is required for unique instance tracking
		cloudletId := ""
		if taskEntry.Task.Metadata != nil {
			if cid, ok := taskEntry.Task.Metadata["cloudlet_id"]; ok && cid != "" {
				cloudletId = cid
				// [DEBUG-LOG] Log key extraction for ACK failure investigation
				logger.GetLogger().Errorf("[DEBUG-KEY-EXTRACTION] GetQueueUpdateResponse: TaskId=%s, cloudletId extracted from metadata=%s, TaskId==cloudletId? %t", 
					taskEntry.Task.TaskId, cloudletId, taskEntry.Task.TaskId == cloudletId)
			} else {
				// CRITICAL: cloudlet_id metadata is required - do NOT fall back to TaskId
				logger.GetLogger().Errorf("[CRITICAL-ERROR] GetQueueUpdateResponse: TaskId=%s, cloudlet_id NOT in metadata - SKIPPING scheduledTasks tracking (required for unique instance tracking)", taskEntry.Task.TaskId)
				continue // Skip this task - cannot track without cloudletId
			}
		} else {
			// CRITICAL: metadata is nil - do NOT fall back to TaskId
			logger.GetLogger().Errorf("[CRITICAL-ERROR] GetQueueUpdateResponse: TaskId=%s, metadata is nil - SKIPPING scheduledTasks tracking (required for unique instance tracking)", taskEntry.Task.TaskId)
			continue // Skip this task - cannot track without cloudletId
		}
		
		// Check if task is already in scheduledTasks
		if _, exists := se.scheduledTasks[cloudletId]; !exists {
			// Add to scheduledTasks using cloudletId as key (unique)
			se.scheduledTasks[cloudletId] = taskEntry
			tasksAddedToScheduled++
			logger.GetLogger().Infof("[SCHEDULER-GET-QUEUE-UPDATE-SCHEDULED] Added task to scheduledTasks: cloudletId=%s, TaskID=%s (scheduledTasks size: %d)",
				cloudletId, taskEntry.Task.TaskId, len(se.scheduledTasks))
			// [DEBUG-LOG] Log exact key used for storage
			logger.GetLogger().Errorf("[DEBUG-KEY-STORAGE] GetQueueUpdateResponse: Storing task in scheduledTasks with key='%s' (TaskId='%s', cloudletId='%s', keysMatch=%t)", 
				cloudletId, taskEntry.Task.TaskId, cloudletId, taskEntry.Task.TaskId == cloudletId)
		} else {
			logger.GetLogger().Debugf("[SCHEDULER-GET-QUEUE-UPDATE-SCHEDULED] Task already in scheduledTasks: cloudletId=%s, TaskID=%s",
				cloudletId, taskEntry.Task.TaskId)
			// [DEBUG-LOG] Log existing entry for ACK failure investigation
			logger.GetLogger().Debugf("[DEBUG-KEY-STORAGE] GetQueueUpdateResponse: Task already exists in scheduledTasks with key='%s' (TaskId='%s')", 
				cloudletId, taskEntry.Task.TaskId)
		}
	}
	
	if tasksAddedToScheduled > 0 {
		logger.GetLogger().Infof("[SCHEDULER-GET-QUEUE-UPDATE-SCHEDULED] Added %d tasks to scheduledTasks (total scheduledTasks: %d)",
			tasksAddedToScheduled, len(se.scheduledTasks))
	}
	
	// Convert to proto tasks WITH cache information in metadata
	protoTasks := make([]*pb.Task, 0, len(allTasks))
	for _, taskEntry := range allTasks {
		protoTask := se.taskEntryToProtoTaskWithCache(taskEntry)
		protoTasks = append(protoTasks, protoTask)
	}
	
	logger.GetLogger().Infof("[SCHEDULER-GET-QUEUE-UPDATE-RESPONSE] Returning %d tasks in streaming update (reason=%s, includeMetadata=%t)",
		len(protoTasks), updateReason, includeMetadata)

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
