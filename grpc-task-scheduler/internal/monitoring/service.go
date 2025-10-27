package monitoring

import (
	"context"
	"runtime"
	"time"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/pkg/logger"
	"scheduler-grpc-server/pkg/metrics"
)

// MonitoringService implements SystemMonitoring service
type MonitoringService struct {
	pb.UnimplementedSystemMonitoringServer
	metrics   *metrics.InMemoryMetrics
	startTime time.Time
	nodes     map[string]*pb.NodeSummary // Simple node tracking
}

// NewMonitoringService creates monitoring service
func NewMonitoringService(metrics *metrics.InMemoryMetrics) *MonitoringService {
	return &MonitoringService{
		metrics:   metrics,
		startTime: time.Now(),
		nodes:     make(map[string]*pb.NodeSummary),
	}
}

// GetSystemMetrics returns current system metrics
func (m *MonitoringService) GetSystemMetrics(ctx context.Context, req *pb.GetSystemMetricsRequest) (*pb.GetSystemMetricsResponse, error) {
	logger.GetLogger().Info("Getting system metrics")

	// Get memory stats
	var memStats runtime.MemStats
	runtime.ReadMemStats(&memStats)

	// Get metrics from your existing metrics service
	stats := m.metrics.GetStats()

	return &pb.GetSystemMetricsResponse{
		UptimeSeconds:      int64(time.Since(m.startTime).Seconds()),
		CpuUsagePercent:    float64(runtime.NumGoroutine()) * 0.1, // Simple estimation
		MemoryUsageMb:      int64(memStats.Alloc / 1024 / 1024),
		TotalRequests:      stats["total_requests"].(int64),
		SuccessfulRequests: stats["successful_requests"].(int64),
		FailedRequests:     stats["failed_requests"].(int64),
		SuccessRatePercent: stats["success_rate"].(float64),
		AvgResponseTimeMs:  stats["avg_response_time_ms"].(float64),
		ActiveConnections:  int32(runtime.NumGoroutine()),
		Timestamp:          time.Now().Unix(),
	}, nil
}

// GetNodeRegistry returns node information
func (m *MonitoringService) GetNodeRegistry(ctx context.Context, req *pb.GetNodeRegistryRequest) (*pb.GetNodeRegistryResponse, error) {
	logger.GetLogger().Info("Getting node registry")

	var nodes []*pb.NodeSummary
	var activeCount int64

	for _, node := range m.nodes {
		nodes = append(nodes, node)
		if node.Status == pb.NodeStatus_NODE_STATUS_ACTIVE {
			activeCount++
		}
	}

	// Add some demo nodes if empty
	if len(nodes) == 0 {
		nodes = m.getDemoNodes()
		activeCount = 2
	}

	return &pb.GetNodeRegistryResponse{
		Nodes:       nodes,
		TotalNodes:  int64(len(nodes)),
		ActiveNodes: activeCount,
	}, nil
}

// GetSchedulingStats returns scheduling statistics
func (m *MonitoringService) GetSchedulingStats(ctx context.Context, req *pb.GetSchedulingStatsRequest) (*pb.GetSchedulingStatsResponse, error) {
	logger.GetLogger().Info("Getting scheduling stats")

	stats := m.metrics.GetStats()
	totalTasks := stats["successful_tasks"].(int64) + stats["failed_tasks"].(int64)

	return &pb.GetSchedulingStatsResponse{
		AlgorithmUsage: map[string]int64{
			"ROUND_ROBIN":  totalTasks * 80 / 100, // 80% round robin
			"LEAST_LOADED": totalTasks * 20 / 100, // 20% least loaded
			"RL_BASED":     0,                     // Future
		},
		AlgorithmPerformance: map[string]float32{
			"ROUND_ROBIN":  85.5,
			"LEAST_LOADED": 88.2,
			"RL_BASED":     0.0,
		},
		OverallEfficiency:   86.8,
		TotalTasksProcessed: totalTasks,
	}, nil
}

// GetDashboard returns dashboard data
func (m *MonitoringService) GetDashboard(ctx context.Context, req *pb.GetDashboardRequest) (*pb.GetDashboardResponse, error) {
	logger.GetLogger().Info("Getting dashboard")

	stats := m.metrics.GetStats()
	uptime := time.Since(m.startTime)

	status := &pb.SystemStatus{
		ServerStatus:  "RUNNING",
		UptimeSeconds: int64(uptime.Seconds()),
		ActiveNodes:   int64(len(m.nodes)),
		TasksToday:    stats["successful_tasks"].(int64) + stats["failed_tasks"].(int64),
		CurrentLoad:   65.4, // Mock current load
	}

	recentActivities := []string{
		"Task scheduled to fog-node-01 (5 min ago)",
		"New node fog-node-03 registered (15 min ago)",
		"Algorithm switched to LEAST_LOADED (30 min ago)",
	}

	alerts := []string{}
	if stats["success_rate"].(float64) < 95.0 {
		alerts = append(alerts, "WARNING: Success rate below 95%")
	}
	if int64(uptime.Hours()) > 24 {
		alerts = append(alerts, "INFO: Server running for more than 24 hours")
	}

	return &pb.GetDashboardResponse{
		Status:           status,
		RecentActivities: recentActivities,
		Alerts:           alerts,
	}, nil
}

// Helper method to add demo nodes for presentation
func (m *MonitoringService) getDemoNodes() []*pb.NodeSummary {
	return []*pb.NodeSummary{
		{
			NodeId:             "fog-node-01",
			Status:             pb.NodeStatus_NODE_STATUS_ACTIVE,
			UtilizationPercent: 65.5,
			TasksAssigned:      45,
			Region:             "us-east-1",
		},
		{
			NodeId:             "fog-node-02",
			Status:             pb.NodeStatus_NODE_STATUS_ACTIVE,
			UtilizationPercent: 78.2,
			TasksAssigned:      32,
			Region:             "us-west-1",
		},
		{
			NodeId:             "fog-node-03",
			Status:             pb.NodeStatus_NODE_STATUS_MAINTENANCE,
			UtilizationPercent: 0.0,
			TasksAssigned:      0,
			Region:             "eu-west-1",
		},
	}
}

// AddNode registers a new node (call this from your scheduler service)
func (m *MonitoringService) AddNode(nodeId string, status pb.NodeStatus, region string) {
	m.nodes[nodeId] = &pb.NodeSummary{
		NodeId:             nodeId,
		Status:             status,
		UtilizationPercent: 0.0,
		TasksAssigned:      0,
		Region:             region,
	}
}

// UpdateNodeStats updates node statistics
func (m *MonitoringService) UpdateNodeStats(nodeId string, utilization float64, tasksAssigned int64) {
	if node, exists := m.nodes[nodeId]; exists {
		node.UtilizationPercent = utilization
		node.TasksAssigned = tasksAssigned
	}
}
