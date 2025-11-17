package models

import (
	"context"
	"fmt"
	"sync"
	"time"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/internal/metrics"
	"scheduler-grpc-server/internal/rl"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
)

type SchedulerEngine struct {
	mu                 sync.RWMutex
	queue              TaskQueue
	nodeManager        *SingleNodeManager
	nodeStatusTracker  *metrics.NodeStatusTracker // NEW: Tracks node status from completion reports
	scheduledTasks     map[string]*TaskEntry  // Track scheduled tasks for delayed rewards
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
		nodeStatusTracker: metrics.NewNodeStatusTracker(0.3), // Alpha=0.3: 30% weight to new value, 70% to history
		scheduledTasks:     make(map[string]*TaskEntry),  // Initialize scheduled tasks tracking
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
		
		// Wire up NodeStatusTracker to agent (which will pass it to Q-learning scheduler)
		// Create a wrapper that implements NodeStatusTracker interface
		engine.agent.SetNodeStatusTracker(engine.nodeStatusTracker)
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
	logger.GetLogger().Infof("[SCHEDULER-ENGINE-INIT] Algorithm=%s, Objective=%s",
		se.algorithm.String(), se.objective.String())
	
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

// resortQueue resorts the queue based on algorithm type
func (se *SchedulerEngine) resortQueue() {
	
	se.mu.Lock()
	defer func() {
		se.mu.Unlock()
	}()
	
	queueSize := se.queue.Size()
	
	if queueSize <= 1 {
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
	var actionDescription string
	
	if se.agent != nil && se.agent.IsEnabled() {
		// RL-based resorting with multi-objective configuration
		sortedTasks = se.sortQueueWithObjectives(taskEntries)
		actionDescription = "RL-based"
	} else {
		// Traditional algorithm resorting
		sortedTasks = se.resortQueueTraditional(taskEntries)
		actionDescription = se.algorithm.String()
	}
	
	// Log resort action
	logger.GetLogger().Infof("Resort queue: Action=%s, QueueSize=%d", actionDescription, len(sortedTasks))
	
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
// NOTE: This is called from within resortQueue() which already holds se.mu lock
// CRITICAL: Queue operations (Clear, Enqueue) have their own locks, so they can happen
// concurrently with other queue operations, but we hold se.mu to prevent concurrent resorting
func (se *SchedulerEngine) updateQueueWithSortedTasks(sortedTasks []rl.TaskEntry) {
	
	// CRITICAL: queue.GetAll() has its own lock, so it's safe to call even though we hold se.mu
	// The queue's internal lock protects against concurrent enqueue/dequeue operations
	taskIdsBeforeClear := make([]string, 0, se.queue.Size())
	allTasksBefore := se.queue.GetAll()
	for _, task := range allTasksBefore {
		taskIdsBeforeClear = append(taskIdsBeforeClear, task.GetTaskID())
	}
	
	// CRITICAL: queue.Clear() has its own lock, so it's safe even if tasks are being enqueued concurrently
	// However, we hold se.mu to ensure only one resort operation happens at a time
	oldSize := se.queue.Size()
	se.queue.Clear()
	newSizeAfterClear := se.queue.Size()
	if oldSize > 0 && newSizeAfterClear > 0 {
	}
	
	taskIdsInSorted := make([]string, 0, len(sortedTasks))
	for _, task := range sortedTasks {
		taskIdsInSorted = append(taskIdsInSorted, task.GetTaskID())
	}
	
	// Add sorted tasks back to queue
	addedCount := 0
	skippedCount := 0
	skippedTaskIds := make([]string, 0)
	
	for _, task := range sortedTasks {
		// Convert back to TaskEntry and enqueue
		if taskEntry, ok := task.(*TaskEntry); ok {
			// CRITICAL: queue.Enqueue() has its own lock, so it's safe even if tasks are being added concurrently
			// We hold se.mu to prevent concurrent resorting, but queue operations are thread-safe
			if err := se.queue.Enqueue(taskEntry); err != nil {
				skippedCount++
				skippedTaskIds = append(skippedTaskIds, taskEntry.GetTaskID())
			} else {
				addedCount++
			}
		} else {
			taskId := "unknown"
			if task != nil {
				taskId = task.GetTaskID()
			}
			skippedCount++
			skippedTaskIds = append(skippedTaskIds, taskId)
		}
	}
	
	finalSize := se.queue.Size()
	
	if len(skippedTaskIds) > 0 {
		logger.GetLogger().Warnf("Skipped task IDs during resort: %v", skippedTaskIds)
	}
	
	if finalSize != len(sortedTasks) {
		logger.GetLogger().Warnf("Queue size mismatch after resort: QueueSize=%d, Expected=%d", finalSize, len(sortedTasks))
	}
	
	taskIdsAfterUpdate := make([]string, 0, finalSize)
	allTasksAfter := se.queue.GetAll()
	for _, task := range allTasksAfter {
		taskIdsAfterUpdate = append(taskIdsAfterUpdate, task.GetTaskID())
	}
	logger.GetLogger().Infof("[SCHEDULER-UPDATE-QUEUE-AFTER] Task IDs after update: %v (count: %d)", taskIdsAfterUpdate, finalSize)
	
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
	
	// Use RL agent with multi-objective configuration
	sortedTasks := se.agent.Schedule(tasks, se.nodeManager)
	
	// CRITICAL VALIDATION: Ensure no tasks are lost during sorting
	if len(sortedTasks) != len(tasks) {
		logger.GetLogger().Errorf("[SCHEDULER-SORT-ERROR] Task count mismatch: input=%d, output=%d", 
			len(tasks), len(sortedTasks))
		return tasks
	}
	
	// CRITICAL VALIDATION: Ensure all task IDs are preserved
	inputTaskIds := make(map[string]bool, len(tasks))
	for _, task := range tasks {
		inputTaskIds[task.GetTaskID()] = true
	}
	missingTaskIds := make([]string, 0)
	for _, task := range sortedTasks {
		if !inputTaskIds[task.GetTaskID()] {
			missingTaskIds = append(missingTaskIds, task.GetTaskID())
		}
		delete(inputTaskIds, task.GetTaskID())
	}
	if len(missingTaskIds) > 0 {
	}
	if len(inputTaskIds) > 0 {
		missingInOutput := make([]string, 0, len(inputTaskIds))
		for taskId := range inputTaskIds {
			missingInOutput = append(missingInOutput, taskId)
		}
		return tasks
	}
	
	
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
	
	// Update agent with new weights and active profile if agent is enabled
	if se.agent != nil && se.agent.IsEnabled() {
		weights := se.config.RL.MultiObjective.Profiles[profileName].Weights
		if err := se.agent.UpdateRewardWeights(weights); err != nil {
			return fmt.Errorf("failed to update agent reward weights: %w", err)
		}
		
		// Also update the active profile in the multi-objective calculator
		if err := se.agent.UpdateActiveProfile(profileName); err != nil {
			return fmt.Errorf("failed to update active profile: %w", err)
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
	// Extract cloudletId from metadata (unique per tuple instance)
	// CRITICAL: Never fall back to TaskId - cloudletId is required for unique instance tracking
	cloudletId := ""
	if task.Metadata != nil {
		if cid, ok := task.Metadata["cloudlet_id"]; ok && cid != "" {
			cloudletId = cid  // ✅ Use cloudletId from metadata
		} else {
			return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("cloudlet_id metadata is required for task %s", task.TaskId)
		}
	} else {
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("task metadata is required for task %s", task.TaskId)
	}

	// Note: Duplicate check will be done later when we acquire the write lock

	// Step 1: Generate pattern fingerprint (for RL state) and cache key (unique identifier)
	
	// Generate pattern fingerprint for RL state (pattern-based, excludes TaskId)
	fingerprint := se.cacheManager.GenerateTaskFingerprint(task)
	if fingerprint == "" {
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("failed to generate fingerprint for task %s", task.TaskId)
	}
	
	// Generate cache key (unique per task instance, based on TaskId)
	cacheKey := se.cacheManager.GenerateCacheKey(task)
	if cacheKey == "" {
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("failed to generate cache key for task %s", task.TaskId)
	}
	

	// Step 2: Check cache entry state using cache key (TaskId) - lazy cleanup
	var entryFirstSeen int64 = 0
	var entrySeenCount int = 0
	cacheExists := false
	cacheExpired := false
	cacheAgeCategory := "none"
	
	// Extract local_cache_exists from proto field (fog node's cache status)
	fogCacheExists := task.LocalCacheExists
	if fogCacheExists {
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
				se.cacheManager.RemoveEntry(cacheKey) // Remove by cache key (TaskId)
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
		_ = queueSize // Used in ExtractCacheStateFeatures
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

		// Map RL action to proto CacheAction (with expired handling and fog node cache consideration)
		cacheAction = rl.MapCacheActionToProto(
			rlAction,
			cacheExists && !cacheExpired,
			cacheExpired,
			fogCacheExists, // Pass fog node's cache status
		)

		// Determine isCached
		isCached = (cacheAction == pb.CacheAction_CACHE_ACTION_USE)

	} else {
		// RL disabled: Provide default cache data (CACHE_ACTION_NONE) for compatibility
		// This ensures iFogSim fog node always receives cache data, even with traditional algorithms
		
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

	// Cache decision made

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

	// Step 4: Add to queue (ALL tasks go through queue, cache decision is made during execution)
	
	// CRITICAL: Acquire lock BEFORE enqueueing to prevent race condition with resortQueue
	// This ensures:
	// 1. If resortQueue is running (clearing/re-adding), we wait for it to complete
	// 2. Tasks are not lost during queue.Clear() in updateQueueWithSortedTasks
	// 3. New tasks are added after resorting completes, ensuring they're included in next resort
	se.mu.Lock()
	
	// Check queue capacity while holding lock (queue.Size() is thread-safe, but we want consistent state)
	queueSizeBeforeEnqueue := se.queue.Size()
	
	// Check queue capacity if configured
	maxQueueSize := se.config.Queue.MaxQueueSize
	if maxQueueSize > 0 && queueSizeBeforeEnqueue >= maxQueueSize {
		se.mu.Unlock()
		err := fmt.Errorf("queue capacity exceeded: current size=%d, max size=%d", queueSizeBeforeEnqueue, maxQueueSize)
		logger.GetLogger().Errorf("[SCHEDULER-ADD-TASK-ERROR] Task %s: %v", task.TaskId, err)
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, err
	}
	
	// CRITICAL: Enqueue to queue while holding se.mu lock
	// This ensures we don't enqueue during resortQueue's queue.Clear() operation
	// queue.Enqueue() has its own internal lock, so it's still thread-safe
	if err := se.queue.Enqueue(taskEntry); err != nil {
		se.mu.Unlock()
		logger.GetLogger().Errorf("[SCHEDULER-ADD-TASK-ERROR] Task %s: Failed to enqueue: %v (queue size: %d, max: %d)", 
			task.TaskId, err, queueSizeBeforeEnqueue, maxQueueSize)
		logger.GetLogger().Errorf("[SCHEDULER-CACHE-DEBUG] Failed to enqueue task %s: %v", task.TaskId, err)
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("failed to enqueue task %s: %w", task.TaskId, err)
	}
	queueSizeAfterEnqueue := se.queue.Size()
	
	if queueSizeAfterEnqueue > 0 {
		allTasksAfterAdd := se.queue.GetAll()
		taskFoundInQueue := false
		for _, qTask := range allTasksAfterAdd {
			if qTask.GetTaskID() == task.TaskId || qTask.GetCloudletId() == cloudletId {
				taskFoundInQueue = true
				break
			}
		}
		if !taskFoundInQueue {
			taskIdsInQueueAfterAdd := make([]string, 0, len(allTasksAfterAdd))
			cloudletIdsInQueueAfterAdd := make([]string, 0, len(allTasksAfterAdd))
			for _, t := range allTasksAfterAdd {
				taskIdsInQueueAfterAdd = append(taskIdsInQueueAfterAdd, t.GetTaskID())
				cloudletIdsInQueueAfterAdd = append(cloudletIdsInQueueAfterAdd, t.GetCloudletId())
			}
		}
	}

	// CRITICAL: Add to scheduledTasks map and update statistics while still holding the lock
	// This ensures atomic operation: task is in queue AND in scheduledTasks map
	// We already hold se.mu from above, so we can directly update scheduledTasks
	
	// Check for duplicate (we already have the lock)
	if _, exists := se.scheduledTasks[cloudletId]; exists {  // ✅ Check using cloudletId
		se.mu.Unlock()
		return 0, 0, false, "", pb.CacheAction_CACHE_ACTION_NONE, fmt.Errorf("task %s (cloudletId=%s) already scheduled", task.TaskId, cloudletId)
	}
	
	// Add to scheduledTasks and update statistics in the same lock
	se.scheduledTasks[cloudletId] = taskEntry  // ✅ Store using cloudletId (unique)
	se.totalTasksProcessed++
	
	// Release lock after all operations complete (enqueue + scheduledTasks update)
	se.mu.Unlock()
	
	logger.GetLogger().Infof("Task %s added to queue", task.TaskId)

	// Store experience immediately for RL learning (if agent is enabled)
	// This ensures experience exists even if task completes before GetSortedQueue() is called
	if se.agent != nil && se.agent.IsEnabled() {
		se.storeExperienceForNewTask(taskEntry, cloudletId, queueSizeAfterEnqueue)
	}

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
	
	logger.GetLogger().Infof("[SCHEDULER-TASK-ENQUEUED] Task %s enqueued successfully: Position=%d, WaitTime=%dms, Cached=%t, TotalProcessed=%d",
		task.TaskId, queuePosition, estimatedWait, isCached, se.totalTasksProcessed)

	return queuePosition, estimatedWait, isCached, cacheKey, cacheAction, nil
}

// storeExperienceForNewTask stores an incomplete experience immediately when a task is added
// This ensures the experience exists even if the task completes before GetSortedQueue() is called
func (se *SchedulerEngine) storeExperienceForNewTask(taskEntry *TaskEntry, cloudletId string, queueSize int) {
	// Get Q-learning scheduler from agent
	qlScheduler := se.agent.GetQLearningScheduler()
	if qlScheduler == nil {
		// Q-learning not available, skip experience storage
		return
	}

	// Get experience manager
	expManager := qlScheduler.GetExperienceManager()
	if expManager == nil {
		// Experience manager not available, skip
		return
	}

	// Get current queue state (all tasks including the new one)
	// FIX: Acquire read lock before getting queue state to prevent race condition
	// This ensures consistent queue state during state extraction, preventing resortQueue()
	// from clearing the queue between task enqueue and state extraction
	se.mu.RLock()
	allTasks := se.queue.GetAll()
	actualQueueSize := len(allTasks)
	se.mu.RUnlock()
	
	if actualQueueSize == 0 {
		// Queue is empty (shouldn't happen, but handle gracefully)
		logger.GetLogger().Warnf("[SCHEDULER-STORE-EXP] Queue is empty during state extraction, skipping experience storage for cloudletId=%s", cloudletId)
		return
	}

	// Verify queue size matches expected value (silent check - no log needed)
	if actualQueueSize != queueSize {
		// Queue size mismatch detected but not critical - state extraction uses actual size
	}

	// Convert to TaskEntry slice for state extraction
	taskEntries := make([]rl.TaskEntry, len(allTasks))
	for i, task := range allTasks {
		taskEntries[i] = task
	}

	// Extract state features using current queue state and node status tracker
	// metrics.NodeStatusTracker implements rl.NodeStatusTracker interface automatically
	var tracker rl.NodeStatusTracker = nil
	if se.nodeStatusTracker != nil {
		// metrics.NodeStatusTracker implements all methods of rl.NodeStatusTracker interface
		// Go's interface system allows this direct assignment
		tracker = se.nodeStatusTracker
	}
	state := rl.ExtractStateFeatures(taskEntries, tracker)

	// Select action using Q-learning policy
	action := qlScheduler.SelectAction(state)

	// Get current episode
	currentEpisode := qlScheduler.GetCurrentEpisode()

	// Store incomplete experience
	expManager.StoreIncompleteExperience(cloudletId, state, action, currentEpisode)

	logger.GetLogger().Infof("[SCHEDULER-EXPERIENCE-STORED] Experience stored immediately for task: cloudletId=%s, Action=%s, StateKey=%s, QueueSize=%d, Episode=%d",
		cloudletId, action.Description, state.GetStateKey(), queueSize, currentEpisode)
}

func (se *SchedulerEngine) GetQueueStatus() map[string]interface{} {
	se.mu.RLock()
	defer se.mu.RUnlock()

	avgWaitTime := float64(0)

	if se.totalTasksProcessed > 0 {
		avgWaitTime = float64(se.totalWaitTime.Milliseconds()) / float64(se.totalTasksProcessed)
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

	// Extract cloudletId - REQUIRED, no fallback
	cloudletIdForCompletion := req.CloudletId
	logger.GetLogger().Infof("[SCHEDULER-COMPLETION-ENTRY] ProcessTaskCompletion called: cloudletId=%s, taskId=%s", cloudletIdForCompletion, req.TaskId)
	
	if cloudletIdForCompletion == "" {
		// CRITICAL: No fallback to TaskId - cloudletId is required for experience lookup
		// Log error and return error to prevent incorrect experience completion
		logger.GetLogger().Errorf("[SCHEDULER-COMPLETION-ERROR] cloudlet_id is missing in completion report. TaskId=%s. Cannot complete experience without cloudletId.", req.TaskId)
		return fmt.Errorf("cloudlet_id is required for experience completion, but was not provided in completion report (TaskId=%s)", req.TaskId)
	}
	
	logger.GetLogger().Infof("[SCHEDULER-COMPLETION-VALIDATED] cloudletId validated: cloudletId=%s, taskId=%s (taskId used for report matching only)", cloudletIdForCompletion, req.TaskId)

	// Find the task in scheduled tasks using cloudletId (unique identifier)
	task, exists := se.scheduledTasks[cloudletIdForCompletion]
	if !exists {
		logger.GetLogger().Warnf("[SCHEDULER-COMPLETION-ERROR] Task with cloudletId %s not found in scheduled tasks", cloudletIdForCompletion)
		return fmt.Errorf("task with cloudletId %s not found in scheduled tasks", cloudletIdForCompletion)
	}

	// Derive success from completion report
	success := se.deriveTaskSuccess(req)
	errorMessage := se.deriveErrorMessage(req)

	// Update statistics based on completion report (server doesn't execute tasks, so we don't mark task status)
	if success {
		se.totalTasksCompleted++
		logger.GetLogger().Infof("[SCHEDULER-COMPLETION-SUCCESS] Task cloudletId=%s completion report processed (TotalCompleted=%d)", 
			cloudletIdForCompletion, se.totalTasksCompleted)
	} else {
		se.totalTasksFailed++
		logger.GetLogger().Warnf("[SCHEDULER-COMPLETION-FAILED] Task cloudletId=%s completion report indicates failure: %s (TotalFailed=%d)", 
			cloudletIdForCompletion, errorMessage, se.totalTasksFailed)
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
		
		// Extract node status values
		var cpuUsagePercent float64 = 0.0
		var memoryUsageMb int64 = 0
		var memoryCapacityMb int64 = 0
		
		if nodeStatus.CurrentUsage != nil {
			cpuUsagePercent = float64(nodeStatus.CurrentUsage.CpuUsage)
			memoryUsageMb = nodeStatus.CurrentUsage.MemoryUsageMb
		}
		
		if nodeStatus.Capacity != nil {
			memoryCapacityMb = nodeStatus.Capacity.MemoryMb
		}
		
		// Calculate memory percentage for logging
		var memoryPercent float64 = 0.0
		if memoryCapacityMb > 0 {
			memoryPercent = (float64(memoryUsageMb) / float64(memoryCapacityMb)) * 100.0
		}
		
		// Update NodeStatusTracker with node status from completion report
		se.nodeStatusTracker.UpdateFromCompletionReport(nodeStatus)
		logger.GetLogger().Infof("Node status received: Task cloudletId=%s, Node=%s, CPU=%.2f%%, Memory=%.2f%%", 
			cloudletIdForCompletion, nodeStatus.NodeId, cpuUsagePercent, memoryPercent)
	}

	// Get actual current queue length (before task is removed from scheduled tasks)
	currentQueueLength := se.queue.Size()

	// **KEY PART: Delegate to Agent for RL experience handling** (before deleting from map)
	if se.agent != nil && se.agent.IsEnabled() {
		// The Agent should handle experience collection through AlgorithmManager
		// Pass actual queue length for accurate next state calculation
		// Pass cloudletId explicitly (required for experience lookup)
		logger.GetLogger().Infof("[SCHEDULER-COMPLETION-AGENT] Delegating to agent: cloudletId=%s, QueueLength=%d", cloudletIdForCompletion, currentQueueLength)
		if err := se.reportTaskCompletionToAgent(task, req, nodeStatus, currentQueueLength, cloudletIdForCompletion); err != nil {
			// Log error but don't fail the whole operation
			logger.GetLogger().Warnf("Failed to report completion to RL agent: cloudletId=%s, Error=%v", cloudletIdForCompletion, err)
		} else {
			logger.GetLogger().Infof("[SCHEDULER-COMPLETION-AGENT-SUCCESS] Agent processed completion successfully: cloudletId=%s", cloudletIdForCompletion)
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
	// Use cloudletIdForCompletion (already extracted and validated above)
	if _, exists := se.scheduledTasks[cloudletIdForCompletion]; exists {
		delete(se.scheduledTasks, cloudletIdForCompletion)
	}
	
	// **CRITICAL: Remove from queue to prevent re-sending completed tasks**
	// This ensures GetSortedQueue() only returns uncompleted tasks
	cloudletIdForRemoval := task.GetCloudletId()
	
	// CRITICAL FIX: Use cloudletId (not req.TaskId) to remove from queue
	// Queue.Remove() expects cloudletId, not TaskId
	removedTask := se.queue.Remove(cloudletIdForRemoval)
	
	if removedTask == nil {
		// Task might have been removed already or was never in queue (e.g., cached task)
	}

	return nil
}

// Helper methods to derive missing fields from the report
func (se *SchedulerEngine) deriveTaskSuccess(req *pb.TaskCompletionReport) bool {
	// Check if we have completed tasks info
	// Match by cloudletId (preferred) or taskId (fallback for matching within report)
	if len(req.Tasks) > 0 {
		// Look for the specific task - prefer cloudletId match, fallback to taskId
		for _, completedTask := range req.Tasks {
			// Match by cloudletId (unique identifier) - preferred
			if req.CloudletId != "" && completedTask.CloudletId == req.CloudletId {
				return completedTask.DeadlineMet // Later Feature: always true (deadline-aware disabled)
			}
			// Fallback: match by taskId (for backward compatibility within report)
			if completedTask.TaskId == req.TaskId {
				return completedTask.DeadlineMet // Later Feature: always true (deadline-aware disabled)
			}
		}
	}

	// Later Feature: deadline-aware check disabled
	return true // Assume success (deadline-aware disabled)
}

func (se *SchedulerEngine) deriveErrorMessage(req *pb.TaskCompletionReport) string {
	// Later Feature: deadline-aware check disabled
	if !se.deriveTaskSuccess(req) {
		// if req.Metrics != nil && req.Metrics.DeadlineMisses > 0 {
		//     return "deadline missed"
		// }
		return "task execution failed"
	}
	return ""
}

func (se *SchedulerEngine) deriveActualExecutionTime(req *pb.TaskCompletionReport) float64 {
	// Look for the specific task in completed tasks
	// Match by cloudletId (preferred) or taskId (fallback for matching within report)
	if len(req.Tasks) > 0 {
		for _, completedTask := range req.Tasks {
			// Match by cloudletId (unique identifier) - preferred
			if req.CloudletId != "" && completedTask.CloudletId == req.CloudletId {
				return completedTask.ActualExecutionTimeMs
			}
			// Fallback: match by taskId (for backward compatibility within report)
			if completedTask.TaskId == req.TaskId {
				return completedTask.ActualExecutionTimeMs
			}
		}
	}
	return 0
}

// reportTaskCompletionToAgent sends completion data to the RL agent
func (se *SchedulerEngine) reportTaskCompletionToAgent(task *TaskEntry, req *pb.TaskCompletionReport, nodeStatus *pb.FogNode, queueLength int, cloudletId string) error {
	if se.agent == nil || !se.agent.IsEnabled() {
		return nil // Agent not enabled or initialized
	}

	// Pass node status, actual queue length, and cloudletId from completion report to the agent
	// cloudletId is required for experience lookup (no fallback to taskId)
	err := se.agent.ProcessTaskCompletionWithNodeStatus(task, req, nodeStatus, queueLength, cloudletId)
	if err != nil {
		logger.GetLogger().Errorf("Agent.ProcessTaskCompletionWithNodeStatus failed: cloudletId=%s, Error=%v", cloudletId, err)
	}
	return err
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
// FIX: Proper locking to prevent race conditions between GetSortedQueue and AddTaskToQueueWithCache
func (se *SchedulerEngine) GetSortedQueue(includeMetadata bool) *pb.GetSortedQueueResponse {
	
	// CRITICAL FIX: Acquire lock BEFORE checking queue size to prevent race condition
	// This ensures we see a consistent state even if tasks are being added concurrently
	se.mu.Lock()
	queueSizeBefore := se.queue.Size()
	scheduledTasksCount := len(se.scheduledTasks)
	se.mu.Unlock()
	
	// Optimization: Skip resorting if queue is empty (no work to do)
	if queueSizeBefore == 0 {
		
		if scheduledTasksCount > 0 {
			se.mu.RLock()
			scheduledTaskIds := make([]string, 0, len(se.scheduledTasks))
			for taskId := range se.scheduledTasks {
				scheduledTaskIds = append(scheduledTaskIds, taskId)
			}
			se.mu.RUnlock()
		}
		
		se.mu.RLock()
		algorithmName := se.algorithm.String()
		if se.agent != nil && se.agent.IsEnabled() {
			algorithmName = "qlearning"
		}
		nodeId := se.nodeManager.GetNodeID()
		se.mu.RUnlock()
		
		// Return empty response immediately
		return &pb.GetSortedQueueResponse{
			SortedTasks:   []*pb.Task{},
			AlgorithmUsed: algorithmName,
			QueueSize:     0,
			Timestamp:     time.Now().Unix(),
			NodeId:        nodeId,
		}
	}
	
	// CRITICAL: Resort queue FIRST to ensure latest sorted order before sending
	// This eliminates race conditions from periodic resorting and guarantees fresh queue
	// Note: resortQueue() acquires its own lock, so we call it before acquiring our lock
	se.resortQueue() // This will lock internally and apply algorithm/RL policy
	
	// FIX: Acquire write lock ONCE for all operations (prevents deadlock from multiple lock acquisitions)
	se.mu.Lock()
	defer func() {
		se.mu.Unlock()
	}()

	// Get all tasks from queue (now freshly resorted)
	// CRITICAL: We hold the write lock, so queue.GetAll() should see all tasks
	allTasks := se.queue.GetAll()
	
	taskIdsInQueue := make([]string, 0, len(allTasks))
	cloudletIdsInQueue := make([]string, 0, len(allTasks))
	for _, taskEntry := range allTasks {
		taskIdsInQueue = append(taskIdsInQueue, taskEntry.GetTaskID())
		cloudletIdsInQueue = append(cloudletIdsInQueue, taskEntry.GetCloudletId())
	}
	
	scheduledTaskIds := make([]string, 0, len(se.scheduledTasks))
	scheduledCloudletIds := make([]string, 0, len(se.scheduledTasks))
	for key := range se.scheduledTasks {
		scheduledTaskIds = append(scheduledTaskIds, key) // Key is cloudletId
		scheduledCloudletIds = append(scheduledCloudletIds, key)
	}
	
	if len(scheduledCloudletIds) > len(cloudletIdsInQueue) {
		missingInQueue := make([]string, 0)
		for _, scheduledCid := range scheduledCloudletIds {
			found := false
			for _, queueCid := range cloudletIdsInQueue {
				if scheduledCid == queueCid {
					found = true
					break
				}
			}
			if !found {
				missingInQueue = append(missingInQueue, scheduledCid)
			}
		}
	}
	
	for taskId := range se.scheduledTasks {
		foundInQueue := false
		for _, taskEntry := range allTasks {
			if taskEntry.GetTaskID() == taskId {
				foundInQueue = true
				break
			}
		}
		if !foundInQueue {
			// Task is in scheduledTasks but not in queue (likely cached and removed from queue)
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
			} else {
				// cloudlet_id metadata is required - skip this task
				continue
			}
		} else {
			// metadata is nil - skip this task
			continue
		}
		
		// Check if task is already in scheduledTasks
		if _, exists := se.scheduledTasks[cloudletId]; !exists {
			// Add to scheduledTasks using cloudletId as key (unique)
			se.scheduledTasks[cloudletId] = taskEntry
			tasksAddedToScheduled++
		} else {
			// Task already in scheduledTasks
		}
	}
	
	// Tasks added to scheduledTasks if needed
	
	// Convert to proto tasks WITH cache information in metadata
	protoTasks := make([]*pb.Task, 0, len(allTasks))
	
	for _, taskEntry := range allTasks {
		protoTask := se.taskEntryToProtoTaskWithCache(taskEntry)
		protoTasks = append(protoTasks, protoTask)
	}

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
	} else {
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
		OutputSize:      taskEntry.Task.OutputSize,      // ✅ Copy output_size from original task
		Priority:        taskEntry.Task.Priority,
		Deadline:        0, // Later Feature: deadline-aware disabled
		Dependencies:    taskEntry.Task.Dependencies,
		LocalCacheExists: taskEntry.Task.LocalCacheExists, // ✅ Copy local_cache_exists from original task
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
// CRITICAL: Resorts queue on-demand before returning to ensure fresh, sorted order
func (se *SchedulerEngine) GetQueueUpdateResponse(updateReason string, includeMetadata bool) *pb.QueueUpdateResponse {
	// CRITICAL: Resort queue FIRST to ensure latest sorted order before sending
	// This eliminates race conditions and guarantees fresh queue on every request
	// Note: resortQueue() acquires its own lock, so we call it before acquiring our lock
	se.resortQueue() // This will lock internally and apply algorithm/RL policy
	
	// Get all tasks from queue (now freshly resorted) - no lock needed for queue.GetAll()
	allTasks := se.queue.GetAll()
	
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
			} else {
				// cloudlet_id metadata is required - skip this task
				continue
			}
		} else {
			// metadata is nil - skip this task
			continue
		}
		
		// Check if task is already in scheduledTasks
		if _, exists := se.scheduledTasks[cloudletId]; !exists {
			// Add to scheduledTasks using cloudletId as key (unique)
			se.scheduledTasks[cloudletId] = taskEntry
			tasksAddedToScheduled++
		}
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
