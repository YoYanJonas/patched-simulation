package gateway

import (
	"context"
	"fmt"
	"strconv"
	"sync"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	pb "grpc-server/api/proto"
	"grpc-server/pkg/logger"
	"grpc-server/pkg/rl"
)

// FogAllocationService implements the gRPC service for fog node task allocation
type FogAllocationService struct {
	pb.UnimplementedFogAllocationServiceServer
	logger        *logger.Logger
	nodeRegistry  *sync.Map // Stores node states: nodeID -> FogNodeState
	decisionStore *rl.DecisionStore
	agent         *rl.Agent
	taskTimers    *sync.Map // For tracking task deadlines: taskID -> time.Time
	stateMutex    sync.RWMutex
}

type ControlRLActions struct {
	EnableLearning       string
	DisableLearning      string
	SwitchAlgorithm      string
	StartTuning          string
	StopTuning           string
	ApplyTunedParameters string
}

var CONTROL_RL_ACTIONS = ControlRLActions{
	EnableLearning:       "enable_learning",
	DisableLearning:      "disable_learning",
	SwitchAlgorithm:      "switch_algorithm",
	StartTuning:          "start_tuning",
	StopTuning:           "stop_tuning",
	ApplyTunedParameters: "apply_tuned_parameters",
}

// NewFogAllocationService creates and initializes a new fog allocation service
func NewFogAllocationService(logger *logger.Logger) *FogAllocationService {

	// TODO constant dir
	agent := rl.NewAgent(logger, "./models")

	// Try to load any existing models
	if err := agent.LoadModels(); err != nil {
		logger.Error("Failed to load RL models", err)
	}

	return &FogAllocationService{
		logger:        logger,
		nodeRegistry:  &sync.Map{},
		decisionStore: rl.NewDecisionStore(),
		agent:         agent,
		taskTimers:    &sync.Map{},
	}
}

// ReportNodeState implements the state reporting RPC endpoint
func (s *FogAllocationService) ReportNodeState(stream pb.FogAllocationService_ReportNodeStateServer) error {
	s.logger.Info("Started new node state reporting stream")

	for {
		// Receive node state update
		stateUpdate, err := stream.Recv()
		if err != nil {
			s.logger.Error("Error receiving node state", err)
			return err
		}

		// Convert and store the node state
		nodeID := stateUpdate.NodeId
		nodeState := rl.FogNodeState{
			NodeID:            nodeID,
			CPUUtilization:    stateUpdate.CpuUtilization,
			MemoryUtilization: stateUpdate.MemoryUtilization,
			NetworkBandwidth:  stateUpdate.NetworkBandwidth,
			TaskCount:         int(stateUpdate.TaskCount),
		}

		// Store node state in registry
		s.nodeRegistry.Store(nodeID, nodeState)
		s.logger.Info(fmt.Sprintf("Updated node state for nodeID: %s, CPU: %.2f%%",
			nodeID, stateUpdate.CpuUtilization*100))

		// Send acknowledgment
		if err := stream.Send(&pb.NodeStateResponse{
			Acknowledged: true,
			Message:      "State update processed successfully",
		}); err != nil {
			s.logger.Error("Failed to send state acknowledgment", err)
			return err
		}
	}
}

// AllocateTask implements the task allocation RPC endpoint
func (s *FogAllocationService) AllocateTask(ctx context.Context, request *pb.TaskAllocationRequest) (*pb.TaskAllocationResponse, error) {
	taskID := request.TaskId
	s.logger.Info(fmt.Sprintf("Processing task allocation request for taskID: %s", taskID))

	// Get available nodes for allocation
	availableNodes := s.getAvailableNodes(request)
	if len(availableNodes) == 0 {
		s.logger.Error(fmt.Sprintf("No available nodes for taskID: %s", taskID), nil)
		return nil, status.Error(codes.ResourceExhausted, "No available nodes found for task allocation")
	}

	// Capture current system state for the decision
	systemState := s.buildSystemState()

	// Use RL agent to select the best node
	selectedNode, err := s.agent.SelectNode(systemState, availableNodes)
	if err != nil {
		s.logger.Error(fmt.Sprintf("RL agent failed to select a node for taskID: %s - %v", taskID, err), nil)
		return nil, status.Error(codes.Internal, "Failed to select a suitable node")
	}

	// Store the decision for learning and future evaluation
	s.decisionStore.StoreDecision(taskID, selectedNode, systemState)

	// Track when the task was allocated for deadline tracking
	s.taskTimers.Store(taskID, time.Now())

	s.logger.Info(fmt.Sprintf("Task %s allocated to node %s using %s algorithm",
		taskID, selectedNode, s.agent.GetActiveAlgorithm()))

	// Return allocation decision to client
	return &pb.TaskAllocationResponse{
		TaskId:                   taskID,
		AllocatedNodeId:          selectedNode,
		Success:                  true,
		Message:                  fmt.Sprintf("Task allocated successfully using %s algorithm", s.agent.GetActiveAlgorithm()),
		ExpectedCompletionTimeMs: estimateCompletionTime(request),
	}, nil
}

// getAvailableNodes returns nodes that meet the task requirements
func (s *FogAllocationService) getAvailableNodes(request *pb.TaskAllocationRequest) []string {
	var availableNodes []string

	s.nodeRegistry.Range(func(key, value interface{}) bool {
		nodeID := key.(string)
		state := value.(rl.FogNodeState)

		// Check if node meets requirements
		if isNodeCapable(state, request) {
			availableNodes = append(availableNodes, nodeID)
		}
		return true
	})

	return availableNodes
}

// isNodeCapable checks if a node can handle a task based on requirements
func isNodeCapable(state rl.FogNodeState, request *pb.TaskAllocationRequest) bool {
	// Define capacity thresholds
	const (
		cpuThreshold    = 0.9  // 90% utilization
		memoryThreshold = 0.85 // 85% utilization
	)

	// Check if node has available capacity for the task requirements
	if state.CPUUtilization+request.CpuRequirement > cpuThreshold {
		return false
	}

	if state.MemoryUtilization+request.MemoryRequirement > memoryThreshold {
		return false
	}

	// Check if node has enough bandwidth
	if state.NetworkBandwidth < request.BandwidthRequirement {
		return false
	}

	return true
}

// ReportTaskOutcome implements the task outcome reporting RPC endpoint
func (s *FogAllocationService) ReportTaskOutcome(ctx context.Context, report *pb.TaskOutcomeRequest) (*pb.TaskOutcomeResponse, error) {
	taskID := report.TaskId
	nodeID := report.NodeId
	success := report.CompletedSuccessfully

	s.logger.Info(fmt.Sprintf("Received task outcome report - TaskID: %s, NodeID: %s, Success: %t, Time: %dms",
		taskID, nodeID, success, report.ActualExecutionTimeMs))

	// Get the original task allocation decision
	decision, exists := s.decisionStore.GetDecision(taskID)
	if !exists {
		s.logger.Error(fmt.Sprintf("No decision record found for task %s - cannot update RL model", taskID), nil)
		return &pb.TaskOutcomeResponse{
			Acknowledged: true,
			Message:      "Task outcome recorded, but no original decision found for learning",
		}, nil
	}

	// Verify that the task was executed on the node we selected
	if decision.SelectedNode != nodeID {
		s.logger.Error(fmt.Sprintf("Task %s was executed on node %s, but was allocated to %s",
			taskID, nodeID, decision.SelectedNode), nil)
	}

	// Capture current system state after task execution
	currentSystemState := s.buildSystemState()

	// Get execution time from when task was allocated until now
	var executionTimeMs int64 = report.ActualExecutionTimeMs
	if executionTimeMs == 0 {
		// If client didn't provide execution time, calculate it from our records
		if startTime, exists := s.taskTimers.Load(taskID); exists {
			executionTimeMs = time.Since(startTime.(time.Time)).Milliseconds()
		}
	}

	// Clean up the timer record
	s.taskTimers.Delete(taskID)

	// Feed the task outcome to the RL agent for learning
	s.agent.Learn(
		decision.State,        // State at time of decision
		decision.SelectedNode, // Action taken (node selected)
		currentSystemState,    // State after task execution
		success,               // Whether task completed successfully
		executionTimeMs,       // How long the task took to execute
	)

	// Log the learning event
	s.logger.Info(fmt.Sprintf("RL model updated based on outcome of task %s (execution time: %dms, success: %t)",
		taskID, executionTimeMs, success))

	// Clean up the decision record now that learning is complete
	s.decisionStore.RemoveDecision(taskID)

	return &pb.TaskOutcomeResponse{
		Acknowledged: true,
		Message:      "Task outcome recorded successfully and model updated",
	}, nil
}

// GetSystemState implements the system state query RPC endpoint
func (s *FogAllocationService) GetSystemState(ctx context.Context, request *pb.SystemStateRequest) (*pb.SystemStateResponse, error) {
	s.logger.Info(fmt.Sprintf("Processing system state request (detailed metrics: %t)", request.IncludeDetailedMetrics))

	response := &pb.SystemStateResponse{
		FogNodes:          make(map[string]*pb.NodeState),
		TotalTasksRunning: 0,
		TotalTasksQueued:  0,
	}

	// Fill response with current node states
	totalTasksRunning := 0
	systemHealthScore := 0.0
	nodeCount := 0

	s.nodeRegistry.Range(func(key, value interface{}) bool {
		nodeID := key.(string)
		state := value.(rl.FogNodeState)

		// Convert internal state to proto response type
		nodeState := &pb.NodeState{
			NodeId:            nodeID,
			CpuUtilization:    state.CPUUtilization,
			MemoryUtilization: state.MemoryUtilization,
			NetworkBandwidth:  state.NetworkBandwidth,
			TaskCount:         int32(state.TaskCount),
			IsAvailable:       isNodeAvailable(state),
		}

		if request.IncludeDetailedMetrics {
			nodeState.CustomMetrics = make(map[string]float64)
			// Future: add detailed metrics here
		}

		response.FogNodes[nodeID] = nodeState
		totalTasksRunning += state.TaskCount

		// Simple health score calculation (will be more sophisticated in future phases)
		nodeHealthScore := 1.0 - (state.CPUUtilization*0.5 + state.MemoryUtilization*0.5)
		systemHealthScore += nodeHealthScore
		nodeCount++

		return true
	})

	response.TotalTasksRunning = int32(totalTasksRunning)

	// Calculate overall system health
	if nodeCount > 0 {
		response.SystemHealthScore = systemHealthScore / float64(nodeCount)
	}

	return response, nil
}

// findSuitableNode selects the best node for task allocation
// This is a placeholder for the RL-based decision logic that will be implemented in Phase 3-4
func (s *FogAllocationService) findSuitableNode(request *pb.TaskAllocationRequest) (string, error) {
	var bestNodeID string
	lowestUtilization := 1.1 // Start above maximum possible utilization

	// Simple greedy allocation strategy based on CPU utilization
	s.nodeRegistry.Range(func(key, value interface{}) bool {
		nodeID := key.(string)
		state := value.(rl.FogNodeState)

		if state.CPUUtilization < lowestUtilization && isNodeAvailable(state) {
			lowestUtilization = state.CPUUtilization
			bestNodeID = nodeID
		}
		return true
	})

	if bestNodeID == "" {
		return "", status.Error(codes.ResourceExhausted, "No available nodes found for task allocation")
	}

	return bestNodeID, nil
}

// buildSystemState creates a snapshot of the current system state
func (s *FogAllocationService) buildSystemState() rl.SystemState {
	systemState := rl.SystemState{
		FogNodes: make(map[string]rl.FogNodeState),
	}

	s.nodeRegistry.Range(func(key, value interface{}) bool {
		nodeID := key.(string)
		nodeState := value.(rl.FogNodeState)
		systemState.FogNodes[nodeID] = nodeState
		return true
	})

	return systemState
}

// isNodeAvailable determines if a node can accept new tasks
func isNodeAvailable(state rl.FogNodeState) bool {
	// Simple availability check - can be enhanced in future phases
	const (
		cpuThreshold    = 0.9  // 90% utilization
		memoryThreshold = 0.85 // 85% utilization
	)

	return state.CPUUtilization < cpuThreshold &&
		state.MemoryUtilization < memoryThreshold
}

// estimateCompletionTime provides an estimate for task completion time
// This will be enhanced with ML-based prediction in future phases
func estimateCompletionTime(request *pb.TaskAllocationRequest) int64 {
	// Simple placeholder implementation
	// In reality, this would consider task requirements, node performance, etc.
	baseTime := int64(1000) // 1 second base time

	// Adjust based on requirements
	cpuFactor := int64(request.CpuRequirement * 1000)
	memoryFactor := int64(request.MemoryRequirement * 500)

	return baseTime + cpuFactor + memoryFactor
}

// ControlRLAgent implements the agent control RPC endpoint // TODO make actions better.
func (s *FogAllocationService) ControlRLAgent(ctx context.Context, request *pb.RLAgentControlRequest) (*pb.RLAgentControlResponse, error) {
	s.logger.Info(fmt.Sprintf("Processing agent control request: %s", request.Action))

	response := &pb.RLAgentControlResponse{
		Success:          true,
		Message:          "Action completed successfully",
		CurrentAlgorithm: s.agent.GetActiveAlgorithm(),
		LearningEnabled:  s.agent.IsLearningEnabled(),
	}

	switch request.Action {
	case CONTROL_RL_ACTIONS.EnableLearning:
		s.agent.EnableLearning()
		response.Message = "Learning enabled successfully"
		response.LearningEnabled = true

	case CONTROL_RL_ACTIONS.DisableLearning:
		s.agent.DisableLearning()
		response.Message = "Learning disabled successfully"
		response.LearningEnabled = false

	case CONTROL_RL_ACTIONS.SwitchAlgorithm:
		if request.AlgorithmName == "" {
			response.Success = false
			response.Message = "Missing algorithm name"
			return response, nil
		}

		err := s.agent.SetActiveAlgorithm(request.AlgorithmName)
		if err != nil {
			response.Success = false
			response.Message = fmt.Sprintf("Failed to switch algorithm: %v", err)
			return response, nil
		}

		response.CurrentAlgorithm = s.agent.GetActiveAlgorithm()
		response.Message = fmt.Sprintf("Switched to %s algorithm", request.AlgorithmName)

	case CONTROL_RL_ACTIONS.StartTuning:
		algorithm := request.AlgorithmName
		strategyName := "random" // TODO check other options
		budget := 20

		// Extract parameters from task metadata
		for key, value := range request.TaskMetadata {
			if key == "strategy" {
				strategyName = value
			} else if key == "budget" {
				if b, err := strconv.Atoi(value); err == nil && b > 0 {
					budget = b
				}
			}
		}

		err := s.agent.StartTuning(algorithm, strategyName, budget)
		if err != nil {
			response.Success = false
			response.Message = fmt.Sprintf("Failed to start tuning: %v", err)
			return response, nil
		}

		response.Message = fmt.Sprintf("Started %s tuning for algorithm %s", strategyName, algorithm)

	case CONTROL_RL_ACTIONS.StopTuning:
		s.agent.StopTuning()
		response.Message = "Stopped hyperparameter tuning"

	case CONTROL_RL_ACTIONS.ApplyTunedParameters:
		_, score, err := s.agent.ApplyTunedParameters()
		if err != nil {
			response.Success = false
			response.Message = fmt.Sprintf("Failed to apply tuned parameters: %v", err)
			return response, nil
		}

		response.Message = fmt.Sprintf("Applied tuned parameters (score: %.4f)", score)

	default:
		response.Success = false
		response.Message = fmt.Sprintf("Unknown action: %s", request.Action)
	}

	return response, nil
}

// GetRLAgentStatus implements the agent status RPC endpoint
func (s *FogAllocationService) GetRLAgentStatus(ctx context.Context, request *pb.RLAgentStatusRequest) (*pb.RLAgentStatusResponse, error) {
	s.logger.Info("Processing agent status request")

	// Get registered algorithms
	algManager := s.agent.GetAlgorithmManager()
	var algorithms []string
	if algManager != nil {
		algorithms = algManager.GetRegisteredAlgorithms()
	}

	return &pb.RLAgentStatusResponse{
		ActiveAlgorithm:     s.agent.GetActiveAlgorithm(),
		LearningEnabled:     s.agent.IsLearningEnabled(),
		AvailableAlgorithms: algorithms,
	}, nil
}

// SaveRLModels triggers saving of RL models to disk
func (s *FogAllocationService) SaveRLModels() error {
	return s.agent.SaveModels()
}

// GetRLPerformanceMetrics implements the performance metrics retrieval RPC endpoint
func (s *FogAllocationService) GetRLPerformanceMetrics(ctx context.Context, request *pb.RLPerformanceRequest) (*pb.RLPerformanceResponse, error) {
	s.logger.Info("Processing RL performance metrics request")

	metrics := s.agent.GetPerformanceMetrics()

	// Convert algorithm metrics to protobuf message format
	algorithmMetrics := make(map[string]*pb.RLPerformanceResponse_AlgorithmMetric)
	for name, metric := range metrics.AlgorithmMetrics {
		algorithmMetrics[name] = &pb.RLPerformanceResponse_AlgorithmMetric{
			Decisions:         metric.Decisions,
			SuccessRate:       metric.SuccessRate,
			AvgReward:         metric.AvgReward,
			LastUpdatedUnixMs: metric.LastUpdated.UnixMilli(),
		}
	}

	response := &pb.RLPerformanceResponse{
		TotalDecisions:    metrics.TotalDecisions,
		SuccessfulTasks:   metrics.SuccessfulTasks,
		FailedTasks:       metrics.FailedTasks,
		SuccessRate:       metrics.SuccessRate,
		AvgRecentReward:   metrics.AvgRecentReward,
		CumulativeReward:  metrics.CumulativeReward,
		AvgDecisionTimeNs: metrics.AvgDecisionTimeNs,
		AlgorithmMetrics:  algorithmMetrics,
	}

	// Add hybrid metrics if available
	if metrics.HybridMetrics != nil {
		response.HybridMetrics = &pb.RLPerformanceResponse_HybridMetric{
			TransitionProgress: metrics.HybridMetrics.TransitionProgress,
			ConfidenceAvg:      metrics.HybridMetrics.ConfidenceAvg,
			HeuristicUsage:     metrics.HybridMetrics.HeuristicUsage,
		}
	}

	// ADD TUNING STATUS METRICS
	// Get tuning status using the accessor method
	tuningStatus := s.agent.GetTuningStatus()

	if tuningStatus.IsRunning || len(tuningStatus.BestParams) > 0 {
		// Add tuning status metrics
		now := time.Now().UnixMilli()

		// Add status metric - use proper Go conditional
		var runningStatus float64 = 0.0
		if tuningStatus.IsRunning {
			runningStatus = 1.0
		}

		algorithmMetrics["tuning_status"] = &pb.RLPerformanceResponse_AlgorithmMetric{
			Decisions:         int64(tuningStatus.Pending + tuningStatus.InProgress + tuningStatus.Completed),
			AvgReward:         float64(tuningStatus.Completed), // Use this to show completed count
			SuccessRate:       runningStatus,                   // 1.0 means running
			LastUpdatedUnixMs: tuningStatus.LastUpdateTime.UnixMilli(),
		}

		// Add best score metric if available
		if len(tuningStatus.BestParams) > 0 {
			algorithmMetrics["tuning_best_score"] = &pb.RLPerformanceResponse_AlgorithmMetric{
				Decisions:         1,
				AvgReward:         tuningStatus.BestScore,
				SuccessRate:       1.0,
				LastUpdatedUnixMs: now,
			}

			// Add individual best parameters as metrics
			for param, value := range tuningStatus.BestParams {
				metricName := fmt.Sprintf("tuning_param_%s", param)
				algorithmMetrics[metricName] = &pb.RLPerformanceResponse_AlgorithmMetric{
					Decisions:         1,
					AvgReward:         value,
					SuccessRate:       1.0,
					LastUpdatedUnixMs: now,
				}
			}
		}
	}

	return response, nil
}

// SetRLParameters implements the parameter tuning RPC endpoint
func (s *FogAllocationService) SetRLParameters(ctx context.Context, request *pb.RLParametersRequest) (*pb.RLParametersResponse, error) {
	algorithmName := request.AlgorithmName
	parameters := request.Parameters

	s.logger.Info(fmt.Sprintf("Processing parameter update request for algorithm: %s", algorithmName))

	// Set the parameters
	err := s.agent.SetAlgorithmParameters(algorithmName, parameters)
	if err != nil {
		errMsg := fmt.Sprintf("Failed to set parameters: %v", err)
		s.logger.Error(errMsg, err)
		return &pb.RLParametersResponse{
			Success: false,
			Message: errMsg,
		}, nil
	}

	// Get the updated parameters to return in the response
	currentParams, err := s.agent.GetAlgorithmParameters(algorithmName)
	if err != nil {
		s.logger.Error(fmt.Sprintf("Failed to get current parameters: %v", err), err)
	}

	return &pb.RLParametersResponse{
		Success:           true,
		Message:           fmt.Sprintf("Parameters updated successfully for algorithm: %s", algorithmName),
		CurrentParameters: currentParams,
	}, nil
}
