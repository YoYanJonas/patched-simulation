package scheduler

import (
	"context"
	"fmt"
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
	engine.SetMaxConcurrentTasks(cfg.SingleNode.MaxConcurrentTasks)

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
	s.schedulerEngine.Start(ctx)
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
	s.metrics.IncrementRequests()

	if req.Task == nil {
		s.metrics.IncrementFailedRequests()
		return &pb.AddTaskToQueueResponse{
			TaskId:  "",
			Success: false,
			Message: "task cannot be nil",
		}, nil
	}

	if req.Task.TaskId == "" {
		s.metrics.IncrementFailedRequests()
		return &pb.AddTaskToQueueResponse{
			TaskId:  "",
			Success: false,
			Message: "task_id cannot be empty",
		}, nil
	}

	// Add task to queue via scheduler engine
	queuePosition, estimatedWait, err := s.schedulerEngine.AddTaskToQueue(req.Task)
	if err != nil {
		s.metrics.IncrementFailedRequests()
		return &pb.AddTaskToQueueResponse{
			TaskId:  req.Task.TaskId,
			Success: false,
			Message: err.Error(),
		}, nil
	}

	s.metrics.IncrementSuccessfulRequests()
	logger.GetLogger().Infof("Task %s added to queue at position %d", req.Task.TaskId, queuePosition)

	return &pb.AddTaskToQueueResponse{
		TaskId:                req.Task.TaskId,
		Success:               true,
		Message:               "task added to queue successfully",
		QueuePosition:         queuePosition,
		EstimatedWaitTimeMs:   estimatedWait,
	}, nil
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

	// Delegate to SchedulerEngine - keeps service layer clean
	err := s.schedulerEngine.ProcessTaskCompletion(req)
	if err != nil {
		s.metrics.IncrementFailedRequests()
		return &pb.TaskCompletionAck{
			Success: false,
			Message: fmt.Sprintf("failed to process completion: %v", err),
		}, nil
	}

	s.metrics.IncrementSuccessfulRequests()
	logger.GetLogger().Infof("Task completion processed: %s", req.TaskId)

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

// GetSortedQueue returns the current sorted queue
func (s *SchedulerService) GetSortedQueue(ctx context.Context, req *pb.GetSortedQueueRequest) (*pb.GetSortedQueueResponse, error) {
	s.metrics.IncrementRequests()

	if s.schedulerEngine == nil {
		s.metrics.IncrementFailedRequests()
		return &pb.GetSortedQueueResponse{
			SortedTasks:   []*pb.Task{},
			AlgorithmUsed: "unknown",
			QueueSize:     0,
			Timestamp:     time.Now().Unix(),
			NodeId:       "unknown",
		}, fmt.Errorf("scheduler engine not initialized")
	}

	// Get sorted queue from scheduler engine
	response := s.schedulerEngine.GetSortedQueue(req.IncludeMetadata)

	s.metrics.IncrementSuccessfulRequests()
	logger.GetLogger().Infof("Sorted queue requested: %d tasks", len(response.SortedTasks))

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
