package scheduler

import (
	"context"
	"fmt"
	"strings"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/internal/models"
	"scheduler-grpc-server/internal/rl"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
	"scheduler-grpc-server/pkg/metrics"
)

type SchedulerService struct {
	pb.UnimplementedTaskSchedulerServer
	pb.UnimplementedSystemMonitoringServer
	metrics         *metrics.InMemoryMetrics
	schedulerEngine *models.SchedulerEngine
	config          *config.Config
	startTime       time.Time
}

func NewSchedulerService(metrics *metrics.InMemoryMetrics, cfg *config.Config) *SchedulerService {
	algorithm := pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_FIFO
	switch cfg.SingleNode.DefaultAlgorithm {
	case "priority":
		algorithm = pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_PRIORITY
	case "shortest_job_first", "sjf":
		algorithm = pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_SHORTEST_JOB_FIRST
	case "fifo", "fcfs":
		algorithm = pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_FIFO
	default:
		algorithm = pb.SchedulingAlgorithm_SCHEDULING_ALGORITHM_FIFO
		logger.GetLogger().Warnf("Unknown algorithm '%s', defaulting to FIFO", cfg.SingleNode.DefaultAlgorithm)
	}

	engine := models.NewSchedulerEngine(cfg.SingleNode.NodeID, algorithm, cfg)

	// Initialize enhanced RL configuration components
	if cfg.RL.Enabled {
		logger.GetLogger().Info("Initializing enhanced RL configuration in scheduler service")

		// Validate fuzzy state discretization is properly configured
		if cfg.RL.StateDiscretization.Enabled {
			logger.GetLogger().Infof("Fuzzy state discretization initialized with %d CPU categories",
				len(cfg.RL.StateDiscretization.CPUUtilization.Categories))
		}

		// Log memory management configuration
		if cfg.RL.MemoryManagement.Enabled {
			logger.GetLogger().Infof("Enhanced memory management initialized: max_experiences=%d, cleanup_strategy=%s",
				cfg.RL.MemoryManagement.MaxExperiences, cfg.RL.MemoryManagement.CleanupStrategy)
		}

		// Validate episode management configuration
		if cfg.RL.EpisodeConfig.Type == "task_based" {
			logger.GetLogger().Infof("Episode management: %d tasks per episode", cfg.RL.EpisodeConfig.TasksPerEpisode)
		} else if cfg.RL.EpisodeConfig.Type == "time_based" {
			logger.GetLogger().Infof("Episode management: %d minutes per episode", cfg.RL.EpisodeConfig.TimePerEpisodeMinutes)
		}
	}

	return &SchedulerService{
		metrics:         metrics,
		schedulerEngine: engine,
		config:          cfg,
		startTime:       time.Now(),
	}
}

func (s *SchedulerService) Start(ctx context.Context) {
	logger.GetLogger().Info("[SCHEDULER-SERVICE-START] Starting scheduler service...")
	logger.GetLogger().Infof("[SCHEDULER-SERVICE-CONFIG] Algorithm=%s, NodeID=%s, RLEnabled=%t, CacheAgentEnabled=%t",
		s.config.SingleNode.DefaultAlgorithm, s.config.SingleNode.NodeID,
		s.config.RL.Enabled, s.config.CacheAgent.Enabled)
	
	s.schedulerEngine.Start(ctx)
	
	logger.GetLogger().Info("[SCHEDULER-SERVICE-STARTED] Scheduler service started successfully")
	
	// Initialize episode management lifecycle
	if s.config.RL.Enabled && s.config.RL.EpisodeConfig.Type != "" {
		logger.GetLogger().Infof("Episode management lifecycle started with type: %s", s.config.RL.EpisodeConfig.Type)
	}

	// Initialize fuzzy state discretization
	if s.config.RL.Enabled && s.config.RL.StateDiscretization.Enabled {
		logger.GetLogger().Info("Fuzzy state discretization active in service lifecycle")
	}

	logger.GetLogger().Infof("Task-based scheduler started with algorithm: %s", s.config.SingleNode.DefaultAlgorithm)
}

func (s *SchedulerService) Stop() {
	// Cleanup episode management on service shutdown
	if s.config.RL.Enabled {
		logger.GetLogger().Info("Cleaning up RL components during service shutdown")

		// Note: Episode cleanup is handled by SchedulerEngine.Stop()
		// which will properly finalize any ongoing episodes
		if s.config.RL.EpisodeConfig.Type != "" {
			logger.GetLogger().Info("Episode management cleanup initiated")
		}

		// Memory management cleanup is handled by ExperienceManager
		if s.config.RL.MemoryManagement.Enabled {
			logger.GetLogger().Info("Memory management cleanup initiated")
		}
	}

	s.schedulerEngine.Stop()
	logger.GetLogger().Info("Scheduler service stopped")
}

// AddTaskToQueue adds a task to the scheduling queue
func (s *SchedulerService) AddTaskToQueue(ctx context.Context, req *pb.AddTaskToQueueRequest) (*pb.AddTaskToQueueResponse, error) {
	// [DEBUG] Enhanced logging for incoming queue request
	if req.Task != nil {
		logger.GetLogger().Infof("[SCHEDULER-RECEIVE-START] Received AddTaskToQueue request: TaskID=%s, TaskName=%s, Type=%s, Priority=%d, CPU=%d, Mem=%d, ExecTime=%d",
			req.Task.TaskId, req.Task.TaskName, req.Task.TaskType.String(), req.Task.Priority, req.Task.CpuRequirement, req.Task.MemoryRequirement, req.Task.ExecutionTime)
		logger.GetLogger().Infof("[SCHEDULER-RECEIVE-DETAILS] Task details: MetadataCount=%d, DependenciesCount=%d",
			len(req.Task.Metadata), len(req.Task.Dependencies))
		
		// Log metadata if present
		if len(req.Task.Metadata) > 0 {
			metadataStr := ""
			for k, v := range req.Task.Metadata {
				if metadataStr != "" {
					metadataStr += ", "
				}
				metadataStr += fmt.Sprintf("%s=%s", k, v)
			}
			logger.GetLogger().Infof("[SCHEDULER-RECEIVE-METADATA] Task metadata: %s", metadataStr)
		}
	} else {
		logger.GetLogger().Error("[SCHEDULER-RECEIVE] Received AddTaskToQueue with nil task")
	}
	
	s.metrics.IncrementRequests()

	if req.Task == nil {
		s.metrics.IncrementFailedRequests()
		logger.GetLogger().Error("[SCHEDULER-ERROR] Task is nil")
		return &pb.AddTaskToQueueResponse{
			TaskId:  "",
			Success: false,
			Message: "task cannot be nil",
		}, nil
	}

	if req.Task.TaskId == "" {
		s.metrics.IncrementFailedRequests()
		logger.GetLogger().Error("[SCHEDULER-ERROR] TaskID is empty")
		return &pb.AddTaskToQueueResponse{
			TaskId:  "",
			Success: false,
			Message: "task_id cannot be empty",
		}, nil
	}

	// Extract QueueContext from request (if provided)
	var queueContext *pb.QueueContext
	if req.QueueContext != nil {
		queueContext = req.QueueContext
		logger.GetLogger().Infof("[SCHEDULER-RECEIVE-QUEUE-CONTEXT] QueueContext provided: total_queue_size=%d (from iFogSim fog node)", queueContext.TotalQueueSize)
	} else {
		// Default: empty queue context (will use 0 for queue length)
		queueContext = &pb.QueueContext{
			TotalQueueSize: 0,
		}
		logger.GetLogger().Infof("[SCHEDULER-RECEIVE-QUEUE-CONTEXT] No QueueContext provided, using default (total_queue_size=0)")
	}

	// [DEBUG] About to process task
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROCESS-START] Processing task for queue: TaskID=%s (queue context: total_size=%d)", req.Task.TaskId, queueContext.TotalQueueSize)
	
	// [DEBUG] Check context state before processing
	// Check if context is already done (timeout) before processing
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROCESS-CONTEXT-CHECK] Checking context state before processing")
	select {
	case <-ctx.Done():
		// [DEBUG] Context already cancelled
		logger.GetLogger().Errorf("[DEBUG] [SCHEDULER-TIMEOUT] Request context cancelled before processing TaskID=%s: %v", req.Task.TaskId, ctx.Err())
		s.metrics.IncrementFailedRequests()
		return &pb.AddTaskToQueueResponse{
			TaskId:  req.Task.TaskId,
			Success: false,
			Message: fmt.Sprintf("request timeout before processing: %v", ctx.Err()),
		}, ctx.Err()
	default:
		// [DEBUG] Context is valid
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROCESS-CONTEXT-VALID] Context is valid, proceeding with processing")
	}
	
	// [DEBUG] About to call scheduler engine
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROCESS-CALL] Calling schedulerEngine.AddTaskToQueueWithCache for TaskID=%s", req.Task.TaskId)

	// [DEBUG] Calling AddTaskToQueueWithCache
	// Add task to queue via scheduler engine (with queue context)
	// IMPORTANT: Task is added to queue BEFORE building response
	// If timeout occurs after this point, task is already in queue - no task loss
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROCESS-CALL-BEFORE] About to call AddTaskToQueueWithCache")
	queuePosition, estimatedWait, isCached, cacheKey, cacheAction, err := s.schedulerEngine.AddTaskToQueueWithCache(req.Task, queueContext)
	// [DEBUG] AddTaskToQueueWithCache returned
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROCESS-CALL-AFTER] AddTaskToQueueWithCache returned")
	
	// [DEBUG] Log result
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROCESS-RESULT] AddTaskToQueueWithCache returned for TaskID=%s: queuePosition=%d, estimatedWait=%d, isCached=%t, cacheKey=%s, cacheAction=%s, error=%v",
		req.Task.TaskId, queuePosition, estimatedWait, isCached, cacheKey, cacheAction.String(), err)
	
	// [DEBUG] Check context after task addition
	// Check context again after task addition (in case timeout occurred during processing)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROCESS-CONTEXT-AFTER-CHECK] Checking context state after task addition")
	if ctx.Err() != nil {
		// [DEBUG] Context cancelled during processing
		// Task is already in queue (added before timeout), but response cannot be sent
		// This is acceptable - task is not lost, just client won't get confirmation
		logger.GetLogger().Warnf("[DEBUG] [SCHEDULER-TIMEOUT-AFTER-ADD] Request timeout after adding TaskID=%s to queue - task is in queue but response cannot be sent: %v", req.Task.TaskId, ctx.Err())
		// Task is already in queue, so return success even if context timed out
		// The client will retry if needed, but task is safe in queue
	} else {
		// [DEBUG] Context still valid
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-PROCESS-CONTEXT-AFTER-VALID] Context is still valid after task addition")
	}
	
	// [DEBUG] Check for errors
	if err != nil {
		// [DEBUG] Error occurred - categorize error for better client handling
		errorMessage := err.Error()
		isTransient := false // Default to non-transient
		
		// Categorize errors: transient errors can be retried, permanent errors should not
		if strings.Contains(errorMessage, "queue capacity exceeded") || 
		   strings.Contains(errorMessage, "already scheduled") ||
		   strings.Contains(errorMessage, "validation failed") {
			isTransient = false // Permanent errors
		} else if strings.Contains(errorMessage, "timeout") ||
		          strings.Contains(errorMessage, "connection") ||
		          strings.Contains(errorMessage, "unavailable") {
			isTransient = true // Transient errors that might succeed on retry
		}
		
		logger.GetLogger().Errorf("[SCHEDULER-ERROR] Failed to add task to queue: TaskID=%s, Error=%v, IsTransient=%t", 
			req.Task.TaskId, err, isTransient)
		s.metrics.IncrementFailedRequests()
		
		// Include error category in response message for client retry logic
		responseMessage := errorMessage
		if isTransient {
			responseMessage = fmt.Sprintf("TRANSIENT_ERROR: %s (may succeed on retry)", errorMessage)
		} else {
			responseMessage = fmt.Sprintf("PERMANENT_ERROR: %s (do not retry)", errorMessage)
		}
		
		return &pb.AddTaskToQueueResponse{
			TaskId:  req.Task.TaskId,
			Success: false,
			Message: responseMessage,
		}, nil
	}

	// [DEBUG] Task added successfully
	s.metrics.IncrementSuccessfulRequests()
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-SUCCESS] Task %s added to queue at position %d (wait=%dms, cached=%t, cacheKey=%s, action=%v)",
		req.Task.TaskId, queuePosition, estimatedWait, isCached, cacheKey, cacheAction)

	// [DEBUG] Building response
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESPONSE-BUILD-BEFORE] About to build AddTaskToQueueResponse")
	
	// Extract cloudletId from task metadata (unique instance identifier)
	cloudletId := ""
	if req.Task.Metadata != nil {
		if cid, ok := req.Task.Metadata["cloudlet_id"]; ok && cid != "" {
			cloudletId = cid
		}
	}
	
	response := &pb.AddTaskToQueueResponse{
		TaskId:              req.Task.TaskId,
		CloudletId:          cloudletId,
		Success:             true,
		Message:             "task added to queue successfully",
		QueuePosition:       queuePosition,
		EstimatedWaitTimeMs: estimatedWait,
		IsCachedTask:        isCached,
		CacheKey:            cacheKey,
		CacheAction:         cacheAction,
	}
	
	// [DEBUG] Response built
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESPONSE-BUILD-DONE] AddTaskToQueueResponse built successfully")
	
	// [DEBUG] Log response details
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESPONSE-BUILD] Built response for TaskID=%s: Success=%t, Position=%d, Cached=%t, CacheAction=%s",
		response.TaskId, response.Success, response.QueuePosition, response.IsCachedTask, response.CacheAction.String())
	logger.GetLogger().Infof("[SCHEDULER-RESPONSE-BUILD] Built response for TaskID=%s: Success=%t, Position=%d, Cached=%t, CacheAction=%s",
		response.TaskId, response.Success, response.QueuePosition, response.IsCachedTask, response.CacheAction.String())
	
	// [DEBUG] About to return response
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESPONSE-SEND] Sending queue response back to iFogSim: TaskID=%s, Success=%t, Position=%d",
		response.TaskId, response.Success, response.QueuePosition)
	logger.GetLogger().Infof("[SCHEDULER-RESPONSE-SEND] Sending queue response back to iFogSim: TaskID=%s, Success=%t, Position=%d",
		response.TaskId, response.Success, response.QueuePosition)
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-RESPONSE-RETURN] About to return AddTaskToQueueResponse for TaskID=%s", response.TaskId)
	
	return response, nil
}

// NEW: ReportTaskCompletion - delegates to SchedulerEngine
func (s *SchedulerService) ReportTaskCompletion(ctx context.Context, req *pb.TaskCompletionReport) (*pb.TaskCompletionAck, error) {
	s.metrics.IncrementRequests()

	if req.TaskId == "" {
		s.metrics.IncrementFailedRequests()
		return &pb.TaskCompletionAck{
			Success: false,
			Message: "task_id is required",
		}, nil
	}

	// [DEBUG] Entry point for task completion report
	fmt.Printf("[DEBUG] [SERVICE-COMPLETE-ENTRY] ReportTaskCompletion called: TaskID=%s, HasNodeStatus=%t\n", 
		req.TaskId, req.NodeStatus != nil)
	logger.GetLogger().Infof("[SERVICE-COMPLETE-ENTRY] ReportTaskCompletion: TaskID=%s, HasNodeStatus=%t", 
		req.TaskId, req.NodeStatus != nil)

	// Delegate to SchedulerEngine - keeps service layer clean
	err := s.schedulerEngine.ProcessTaskCompletion(req)
	if err != nil {
		fmt.Printf("[DEBUG] [SERVICE-COMPLETE-ERROR] ProcessTaskCompletion failed: TaskID=%s, Error=%v\n", 
			req.TaskId, err)
		s.metrics.IncrementFailedRequests()
		return &pb.TaskCompletionAck{
			Success: false,
			Message: fmt.Sprintf("failed to process completion: %v", err),
		}, nil
	}

	fmt.Printf("[DEBUG] [SERVICE-COMPLETE-SUCCESS] Task completion processed successfully: TaskID=%s\n", req.TaskId)
	s.metrics.IncrementSuccessfulRequests()
	logger.GetLogger().Infof("[SERVICE-COMPLETE-SUCCESS] Task completion processed: %s", req.TaskId)

	return &pb.TaskCompletionAck{
		Success: true,
		Message: "task completion processed successfully",
	}, nil
}

func (s *SchedulerService) GetSchedulingStatus(ctx context.Context, req *pb.GetSchedulingStatusRequest) (*pb.GetSchedulingStatusResponse, error) {
	s.metrics.IncrementRequests()

	stats := s.metrics.GetStats()
	queueStats := s.schedulerEngine.GetQueueStatus()

	response := &pb.GetSchedulingStatusResponse{
		TotalTasksScheduled: queueStats["total_tasks_processed"].(int64),
		TotalTasksCompleted: queueStats["total_tasks_completed"].(int64),
		TotalTasksFailed:    queueStats["total_tasks_failed"].(int64),
		SystemMetrics: map[string]string{
			"uptime":            stats["uptime"].(string),
			"total_requests":    fmt.Sprintf("%d", stats["total_requests"].(int64)),
			"success_rate":      fmt.Sprintf("%.2f%%", queueStats["success_rate"].(float64)),
			"avg_response_time": stats["avg_response_time"].(string),
			"queue_size":        fmt.Sprintf("%d", queueStats["queue_size"].(int)),
			"running_tasks":     fmt.Sprintf("%d", queueStats["running_tasks"].(int)),
			"algorithm":         queueStats["algorithm"].(string),
			"node_utilization":  fmt.Sprintf("%.2f%%", queueStats["node_utilization"].(float64)),
		},
	}

	if avgRespTime, ok := queueStats["avg_execution_time_ms"].(float64); ok {
		response.AverageResponseTimeMs = avgRespTime
	}

	s.metrics.IncrementSuccessfulRequests()
	return response, nil
}

func (s *SchedulerService) HealthCheck(ctx context.Context, req *pb.HealthCheckRequest) (*pb.HealthCheckResponse, error) {
	status := "OK"
	if s.config.RL.Enabled {
		status = "OK-RL-ENHANCED"
	}
	return &pb.HealthCheckResponse{
		Healthy:   true,
		Status:    status,
		Timestamp: time.Now().Unix(),
		Version:   "1.0.0-phase7",
	}, nil
}

func (s *SchedulerService) GetSystemMetrics(ctx context.Context, req *pb.GetSystemMetricsRequest) (*pb.GetSystemMetricsResponse, error) {
	stats := s.metrics.GetStats()
	queueStats := s.schedulerEngine.GetQueueStatus()

	response := &pb.GetSystemMetricsResponse{
		UptimeSeconds:      int64(time.Since(s.startTime).Seconds()),
		CpuUsagePercent:    queueStats["node_utilization"].(float64),
		MemoryUsageMb:      0,
		TotalRequests:      stats["total_requests"].(int64),
		SuccessfulRequests: stats["successful_requests"].(int64),
		FailedRequests:     stats["failed_requests"].(int64),
		SuccessRatePercent: stats["success_rate"].(float64),
		AvgResponseTimeMs:  stats["avg_response_time_ms"].(float64),
		ActiveConnections:  int32(queueStats["running_tasks"].(int)),
		Timestamp:          time.Now().Unix(),
	}

	// Log enhanced configuration status periodically
	if s.config.RL.Enabled {
		logger.GetLogger().Debugf("RL System Status: StateDiscretization=%t, MemoryMgmt=%t, MultiObjective=%t",
			s.config.RL.StateDiscretization.Enabled,
			s.config.RL.MemoryManagement.Enabled,
			s.config.RL.MultiObjective.Enabled)
	}

	return response, nil
}

func (s *SchedulerService) GetNodeRegistry(ctx context.Context, req *pb.GetNodeRegistryRequest) (*pb.GetNodeRegistryResponse, error) {
	nodeInfo := s.schedulerEngine.GetNodeInfo()
	queueStats := s.schedulerEngine.GetQueueStatus()

	nodeSummary := &pb.NodeSummary{
		NodeId:             nodeInfo.NodeId,
		Status:             nodeInfo.Status,
		UtilizationPercent: queueStats["node_utilization"].(float64),
		TasksAssigned:      queueStats["total_tasks_processed"].(int64),
		Region:             nodeInfo.Location.Region,
	}

	return &pb.GetNodeRegistryResponse{
		Nodes:       []*pb.NodeSummary{nodeSummary},
		TotalNodes:  1,
		ActiveNodes: 1,
	}, nil
}

func (s *SchedulerService) GetSchedulingStats(ctx context.Context, req *pb.GetSchedulingStatsRequest) (*pb.GetSchedulingStatsResponse, error) {
	queueStats := s.schedulerEngine.GetQueueStatus()

	algorithmUsage := map[string]int64{
		queueStats["algorithm"].(string): queueStats["total_tasks_processed"].(int64),
	}

	algorithmPerformance := map[string]float32{
		queueStats["algorithm"].(string): float32(queueStats["success_rate"].(float64)),
	}

	return &pb.GetSchedulingStatsResponse{
		AlgorithmUsage:       algorithmUsage,
		AlgorithmPerformance: algorithmPerformance,
		OverallEfficiency:    float32(queueStats["success_rate"].(float64)) / 100.0,
		TotalTasksProcessed:  queueStats["total_tasks_processed"].(int64),
	}, nil
}

func (s *SchedulerService) GetDashboard(ctx context.Context, req *pb.GetDashboardRequest) (*pb.GetDashboardResponse, error) {
	queueStats := s.schedulerEngine.GetQueueStatus()

	status := &pb.SystemStatus{
		ServerStatus:  "Running",
		UptimeSeconds: int64(time.Since(s.startTime).Seconds()),
		ActiveNodes:   1,
		TasksToday:    queueStats["total_tasks_processed"].(int64),
		CurrentLoad:   queueStats["node_utilization"].(float64),
	}

	recentActivities := []string{
		fmt.Sprintf("Queue size: %d tasks", queueStats["queue_size"].(int)),
		fmt.Sprintf("Running tasks: %d", queueStats["running_tasks"].(int)),
		fmt.Sprintf("Algorithm: %s", queueStats["algorithm"].(string)),
	}

	var alerts []string
	if queueStats["queue_size"].(int) > 50 {
		alerts = append(alerts, "High queue size detected")
	}
	if queueStats["node_utilization"].(float64) > 80.0 {
		alerts = append(alerts, "High node utilization")
	}

	return &pb.GetDashboardResponse{
		Status:           status,
		RecentActivities: recentActivities,
		Alerts:           alerts,
	}, nil
}

func (s *SchedulerService) UpdateObjectiveWeights(ctx context.Context, req *pb.UpdateObjectiveWeightsRequest) (*pb.UpdateObjectiveWeightsResponse, error) {
	s.metrics.IncrementRequests()

	if len(req.Weights) == 0 {
		s.metrics.IncrementFailedRequests()
		return &pb.UpdateObjectiveWeightsResponse{
			Success: false,
			Message: "weights cannot be empty",
		}, status.Errorf(codes.InvalidArgument, "weights cannot be empty")
	}

	// Update config reward weights
	s.config.RL.RewardWeights = config.RewardWeights{
		Latency:            req.Weights["latency"],
		Throughput:         req.Weights["throughput"],
		ResourceEfficiency: req.Weights["resource_efficiency"],
		Fairness:           req.Weights["fairness"],
		DeadlineMiss:       req.Weights["deadline_miss"],
		EnergyEfficiency:   req.Weights["energy_efficiency"],
	}
	s.config.RL.RewardWeights.Normalize()

	// Delegate to SchedulerEngine for RL weight updates
	if err := s.schedulerEngine.UpdateObjectiveWeights(s.config.RL.RewardWeights); err != nil {
		s.metrics.IncrementFailedRequests()
		return &pb.UpdateObjectiveWeightsResponse{
			Success: false,
			Message: fmt.Sprintf("failed to update weights: %v", err),
		}, nil
	}

	logger.GetLogger().Infof("Objective weights updated: %+v", s.config.RL.RewardWeights)

	s.metrics.IncrementSuccessfulRequests()
	return &pb.UpdateObjectiveWeightsResponse{
		Success: true,
		Message: "Objective weights updated successfully",
	}, nil
}

// GetAgent returns the agent from the scheduler engine for model persistence
func (s *SchedulerService) GetAgent() *rl.Agent {
	if s.schedulerEngine == nil {
		return nil
	}
	return s.schedulerEngine.GetAgent()
}

// GetCacheAgent returns the cache RL agent from the scheduler engine
func (s *SchedulerService) GetCacheAgent() *rl.CacheAgent {
	if s.schedulerEngine == nil {
		return nil
	}
	return s.schedulerEngine.GetCacheAgent()
}

// GetSortedQueue returns the current sorted queue
func (s *SchedulerService) GetSortedQueue(ctx context.Context, req *pb.GetSortedQueueRequest) (*pb.GetSortedQueueResponse, error) {
	// [DEBUG] Entry point - request received
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RECEIVE-START] Received GetSortedQueue request from iFogSim: IncludeMetadata=%t", req.IncludeMetadata)
	s.metrics.IncrementRequests()

	// [DEBUG] Check scheduler engine initialization
	if s.schedulerEngine == nil {
		// [DEBUG] Scheduler engine is nil
		logger.GetLogger().Error("[DEBUG] [SCHEDULER-GET-QUEUE-ERROR] Scheduler engine not initialized")
		s.metrics.IncrementFailedRequests()
		return &pb.GetSortedQueueResponse{
			SortedTasks:   []*pb.Task{},
			AlgorithmUsed: "unknown",
			QueueSize:     0,
			Timestamp:     time.Now().Unix(),
			NodeId:       "unknown",
		}, fmt.Errorf("scheduler engine not initialized")
	}
	// [DEBUG] Scheduler engine is initialized
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-ENGINE-CHECK] Scheduler engine is initialized, proceeding")

	// [DEBUG] Check context state before processing
	// Check if context is already done (timeout) before processing
	select {
	case <-ctx.Done():
		// [DEBUG] Context already cancelled
		logger.GetLogger().Errorf("[DEBUG] [SCHEDULER-GET-QUEUE-TIMEOUT] Request context cancelled before processing GetSortedQueue: %v", ctx.Err())
		s.metrics.IncrementFailedRequests()
		return &pb.GetSortedQueueResponse{
			SortedTasks:   []*pb.Task{},
			AlgorithmUsed: "unknown",
			QueueSize:     0,
			Timestamp:     time.Now().Unix(),
			NodeId:       "unknown",
		}, ctx.Err()
	default:
		// [DEBUG] Context is valid
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-CONTEXT-CHECK] Context is valid, proceeding with processing")
	}
	
	// [DEBUG] About to call scheduler engine
	// Get sorted queue from scheduler engine
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-CALL] Calling schedulerEngine.GetSortedQueue (IncludeMetadata=%t)", req.IncludeMetadata)
	response := s.schedulerEngine.GetSortedQueue(req.IncludeMetadata)
	// [DEBUG] Scheduler engine returned response
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-ENGINE-RETURNED] SchedulerEngine.GetSortedQueue returned, response has %d tasks", len(response.SortedTasks))
	
	// [DEBUG] Check context after processing
	// Check context again after processing (in case timeout occurred during processing)
	if ctx.Err() != nil {
		// [DEBUG] Context cancelled during processing
		logger.GetLogger().Warnf("[DEBUG] [SCHEDULER-GET-QUEUE-TIMEOUT-AFTER] Request timeout after processing GetSortedQueue - response may not be sent: %v", ctx.Err())
		// For GetSortedQueue, this is read-only, so no data loss
		// Client will retry if needed
	} else {
		// [DEBUG] Context still valid
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-CONTEXT-AFTER] Context is still valid after processing")
	}
	
	// [DEBUG] Log response details
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-CALL-RESULT] GetSortedQueue returned: Tasks=%d, Algorithm=%s, QueueSize=%d",
		len(response.SortedTasks), response.AlgorithmUsed, response.QueueSize)

	// [DEBUG] Enhanced logging for scheduled queue on scheduler side
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RESPONSE-BUILD] Built response: Tasks=%d, Algorithm=%s, QueueSize=%d, NodeID=%s, Timestamp=%d",
		len(response.SortedTasks), response.AlgorithmUsed, response.QueueSize, response.NodeId, response.Timestamp)
	
	// [DEBUG] Log task details
	// Log task details if queue has tasks (first 10)
	if len(response.SortedTasks) > 0 {
		taskDetails := ""
		maxTasks := len(response.SortedTasks)
		if maxTasks > 10 {
			maxTasks = 10
		}
		for i := 0; i < maxTasks; i++ {
			task := response.SortedTasks[i]
			if i > 0 {
				taskDetails += "|"
			}
			taskDetails += fmt.Sprintf("ID=%s,CPU=%d,Mem=%d", task.TaskId, task.CpuRequirement, task.MemoryRequirement)
		}
		if len(response.SortedTasks) > 10 {
			taskDetails += fmt.Sprintf("... (+%d more)", len(response.SortedTasks)-10)
		}
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-TASKS] Queue task details: %s", taskDetails)
	} else {
		logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-EMPTY] Queue is EMPTY - no tasks scheduled yet")
	}

	// [DEBUG] About to increment success metrics and return
	s.metrics.IncrementSuccessfulRequests()
	logger.GetLogger().Infof("[DEBUG] [SCHEDULER-GET-QUEUE-RETURN] About to return response with %d tasks", len(response.SortedTasks))
	return response, nil
}

// SubscribeToQueueUpdates provides streaming queue updates
func (s *SchedulerService) SubscribeToQueueUpdates(req *pb.SubscribeRequest, stream pb.TaskScheduler_SubscribeToQueueUpdatesServer) error {
	ctx := stream.Context()
	s.metrics.IncrementRequests()

	if s.schedulerEngine == nil {
		s.metrics.IncrementFailedRequests()
		return fmt.Errorf("scheduler engine not initialized")
	}

	// Set up update interval
	updateInterval := time.Duration(req.UpdateIntervalMs) * time.Millisecond
	if updateInterval <= 0 {
		updateInterval = 1 * time.Second  // Default to 1 second
	}

	logger.GetLogger().Infof("Starting queue subscription with interval: %v", updateInterval)

	// Create ticker for periodic updates
	ticker := time.NewTicker(updateInterval)
	defer ticker.Stop()

	// Send initial queue state
	initialResponse := s.schedulerEngine.GetQueueUpdateResponse("initial", req.IncludeMetadata)
	if err := stream.Send(initialResponse); err != nil {
		s.metrics.IncrementFailedRequests()
		return fmt.Errorf("failed to send initial queue state: %v", err)
	}

	// Send periodic updates
	for {
		select {
		case <-ctx.Done():
			logger.GetLogger().Info("Queue subscription cancelled by client")
			return nil
		case <-ticker.C:
			// Send periodic update
			response := s.schedulerEngine.GetQueueUpdateResponse("periodic", req.IncludeMetadata)
			if err := stream.Send(response); err != nil {
				s.metrics.IncrementFailedRequests()
				return fmt.Errorf("failed to send queue update: %v", err)
			}
		}
	}
}
