package org.patch.client;

import io.grpc.StatusRuntimeException;
import org.patch.proto.TaskSchedulerGrpc;
import org.patch.proto.SystemMonitoringGrpc;
import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;
import org.patch.config.EnhancedConfigurationLoader;
import org.patch.utils.LoggingConfig;
import org.patch.utils.StructuredLogger;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Specialized gRPC client for task scheduling service integration with iFogSim.
 * 
 * <p>
 * This client provides comprehensive task scheduling capabilities by connecting
 * to the grpc-task-scheduler service. It enables iFogSim to leverage external
 * reinforcement learning algorithms for intelligent task scheduling decisions.
 * </p>
 * 
 * <p>
 * Key Features:
 * </p>
 * <ul>
 * <li>Task scheduling with RL-based decision making</li>
 * <li>System monitoring and health checks</li>
 * <li>Performance metrics collection</li>
 * <li>Graceful degradation with fallback scheduling</li>
 * <li>Structured logging with correlation tracking</li>
 * <li>Cache management for task results</li>
 * </ul>
 * 
 * <p>
 * Supported Operations:
 * </p>
 * <ul>
 * <li>Schedule individual tasks with available fog nodes</li>
 * <li>Batch task scheduling for multiple tasks</li>
 * <li>Task completion reporting for learning</li>
 * <li>System state monitoring and health checks</li>
 * <li>Performance metrics and dashboard data</li>
 * <li>RL parameter updates and configuration</li>
 * </ul>
 * 
 * <p>
 * Usage Example:
 * </p>
 * 
 * <pre>{@code
 * // Create scheduler client
 * GrpcClient baseClient = new GrpcClient("localhost", 50051);
 * SchedulerClient scheduler = new SchedulerClient(baseClient);
 * 
 * // Schedule a task
 * ScheduleTaskResponse response = scheduler.scheduleTask(task, availableNodes, policy);
 * 
 * // Report task completion
 * scheduler.reportTaskCompletion(taskId, success, executionTime);
 * 
 * // Close client
 * scheduler.close();
 * }</pre>
 * 
 * @author Younes Shafiee
 * @version 1.0.0
 * @since 1.0.0
 * @see GrpcClient
 * @see TaskSchedulerGrpc
 * @see SystemMonitoringGrpc
 */
public class SchedulerClient implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(SchedulerClient.class.getName());

    private final GrpcClient baseClient;
    private final TaskSchedulerGrpc.TaskSchedulerBlockingStub schedulerStub;
    private final SystemMonitoringGrpc.SystemMonitoringBlockingStub monitoringStub;

    // Enhanced logging
    private final StructuredLogger structuredLogger;
    private final LoggingConfig loggingConfig;

    /**
     * Constructor using base GrpcClient
     */
    public SchedulerClient(GrpcClient baseClient) {
        this.baseClient = baseClient;
        this.schedulerStub = TaskSchedulerGrpc.newBlockingStub(baseClient.getChannel());
        this.monitoringStub = SystemMonitoringGrpc.newBlockingStub(baseClient.getChannel());

        // Initialize enhanced logging
        this.loggingConfig = LoggingConfig.getInstance();
        this.structuredLogger = loggingConfig.createLoggerWithCorrelation(SchedulerClient.class);
    }

    /**
     * Constructor with host and port
     */
    public SchedulerClient(String host, int port) {
        this(new GrpcClient(new GrpcClientConfig.Builder(host, port)
                .usePlaintext(EnhancedConfigurationLoader.getGrpcConfigBoolean("grpc.use.plaintext", true))
                .connectTimeout(
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.connection.timeout", 5000),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .retryConfig(
                        EnhancedConfigurationLoader.getGrpcConfigInt("grpc.retry.max.attempts", 3),
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.retry.delay", 1000),
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.retry.max.delay", 30000))
                .keepAliveConfig(
                        EnhancedConfigurationLoader.getGrpcConfigBoolean("grpc.keepalive.enabled", true),
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.keepalive.time", 30),
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.keepalive.timeout", 10),
                        EnhancedConfigurationLoader.getGrpcConfigBoolean("grpc.keepalive.without.calls", true))
                .build()));
    }

    /**
     * Add a single task to the queue with graceful degradation
     */
    public AddTaskToQueueResponse addTaskToQueue(Task task, List<FogNode> availableNodes, SchedulingPolicy policy) {
        // Record start time for performance measurement
        long startTime = System.currentTimeMillis();

        // Prepare structured logging fields for correlation tracking
        Map<String, Object> requestFields = new HashMap<>();
        requestFields.put("task_id", task.getTaskId());
        requestFields.put("available_nodes_count", availableNodes.size());
        requestFields.put("policy_algorithm", policy.getAlgorithm().toString());
        requestFields.put("correlation_id", structuredLogger.getCorrelationId());

        // Log the start of gRPC request for monitoring and debugging
        structuredLogger.grpcRequestStart("TaskScheduler", "scheduleTask", requestFields);

        try {
            // Check if the underlying gRPC service is available before attempting request
            // This implements graceful degradation when the service is down
            if (!baseClient.isServiceAvailable()) {
                Map<String, Object> fallbackFields = new HashMap<>();
                fallbackFields.put("reason", "service_unavailable");
                fallbackFields.put("task_id", task.getTaskId());

                structuredLogger.warning("Scheduler service unavailable, using fallback scheduling", fallbackFields);
                return createFallbackAddTaskToQueueResponse(task, availableNodes);
            }

            AddTaskToQueueRequest request = AddTaskToQueueRequest.newBuilder()
                    .setTask(task)
                    .addAllAvailableNodes(availableNodes)
                    .setPolicy(policy)
                    .build();

            AddTaskToQueueResponse response = schedulerStub.addTaskToQueue(request);
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> successFields = new HashMap<>();
            successFields.put("task_id", task.getTaskId());
            successFields.put("queue_position", response.getQueuePosition());
            successFields.put("success", response.getSuccess());
            successFields.put("is_cached", response.getIsCachedTask());
            successFields.put("cache_action", response.getCacheAction().toString());

            structuredLogger.grpcRequestComplete("TaskScheduler", "addTaskToQueue", duration, response.getSuccess());

            if (loggingConfig.isPerformanceLoggingEnabled()) {
                structuredLogger.performance("schedule_task", duration, successFields);
            }

            return response;
        } catch (StatusRuntimeException e) {
            long duration = System.currentTimeMillis() - startTime;
            Map<String, Object> errorFields = new HashMap<>();
            errorFields.put("task_id", task.getTaskId());
            errorFields.put("error_code", e.getStatus().getCode().toString());
            errorFields.put("error_message", e.getMessage());
            errorFields.put("duration_ms", duration);

            structuredLogger.error("Failed to schedule task", errorFields, e);
            structuredLogger.grpcRequestComplete("TaskScheduler", "scheduleTask", duration, false);

            // Graceful degradation: return fallback response
            structuredLogger.warning("Using fallback scheduling due to service failure", errorFields);
            return createFallbackAddTaskToQueueResponse(task, availableNodes);
        }
    }

    /**
     * Create fallback schedule task response when service is unavailable
     */
    private AddTaskToQueueResponse createFallbackAddTaskToQueueResponse(Task task, List<FogNode> availableNodes) {
        // Simple round-robin fallback: select first available node
        if (availableNodes.isEmpty()) {
            logger.warning("No available nodes for fallback scheduling");
            return AddTaskToQueueResponse.newBuilder()
                    .setTaskId(task.getTaskId())
                    .setSuccess(false)
                    .setErrorMessage("No available nodes")
                    .build();
        }

        // Select first available node as fallback
        FogNode selectedNode = availableNodes.get(0);
        long currentTime = System.currentTimeMillis();

        // Get fallback configuration values
        long schedulingDelay = EnhancedConfigurationLoader.getGrpcConfigLong("grpc.fallback.scheduling.delay", 1000);
        long executionTime = EnhancedConfigurationLoader.getGrpcConfigLong("grpc.fallback.execution.time", 5000);

        return AddTaskToQueueResponse.newBuilder()
                .setTaskId(task.getTaskId())
                .setSuccess(true)
                .setQueuePosition(1) // First in queue
                .setEstimatedWaitTimeMs(schedulingDelay)
                .setErrorMessage("Using fallback scheduling - service unavailable")
                .build();
    }

    /**
     * Add multiple tasks to queue (individual calls)
     */
    public List<AddTaskToQueueResponse> addTasksToQueue(List<Task> tasks, List<FogNode> availableNodes,
            SchedulingPolicy policy) {
        List<AddTaskToQueueResponse> responses = new ArrayList<>();

        for (Task task : tasks) {
            try {
                AddTaskToQueueResponse response = addTaskToQueue(task, availableNodes, policy);
                responses.add(response);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to add task to queue: " + task.getTaskId(), e);
                // Create error response for failed task
                AddTaskToQueueResponse errorResponse = AddTaskToQueueResponse.newBuilder()
                        .setTaskId(task.getTaskId())
                        .setSuccess(false)
                        .setErrorMessage("Failed to add task to queue: " + e.getMessage())
                        .build();
                responses.add(errorResponse);
            }
        }

        return responses;
    }

    /**
     * Get scheduling status
     */
    public GetSchedulingStatusResponse getSchedulingStatus(String nodeId) {
        try {
            GetSchedulingStatusRequest request = GetSchedulingStatusRequest.newBuilder()
                    .setNodeId(nodeId)
                    .build();

            return schedulerStub.getSchedulingStatus(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to get scheduling status: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Report task completion for RL learning
     */
    public TaskCompletionAck reportTaskCompletion(TaskCompletionReport report) {
        try {
            return schedulerStub.reportTaskCompletion(report);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to report task completion: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Update objective weights for runtime configuration
     */
    public UpdateObjectiveWeightsResponse updateObjectiveWeights(Map<String, Double> weights) {
        try {
            UpdateObjectiveWeightsRequest request = UpdateObjectiveWeightsRequest.newBuilder()
                    .putAllWeights(weights)
                    .build();

            return schedulerStub.updateObjectiveWeights(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to update objective weights: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Health check with enhanced error handling
     */
    public HealthCheckResponse healthCheck() {
        try {
            HealthCheckRequest request = HealthCheckRequest.newBuilder().build();
            return schedulerStub.healthCheck(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Health check failed: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get current sorted queue
     */
    public GetSortedQueueResponse getSortedQueue(String nodeId) {
        try {
            GetSortedQueueRequest request = GetSortedQueueRequest.newBuilder()
                    .setNodeId(nodeId)
                    .build();
            return schedulerStub.getSortedQueue(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to get sorted queue: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Subscribe to queue updates (streaming)
     */
    public Iterator<QueueUpdateResponse> subscribeToQueueUpdates(String nodeId) {
        try {
            SubscribeRequest request = SubscribeRequest.newBuilder()
                    .setNodeId(nodeId)
                    .build();
            return schedulerStub.subscribeToQueueUpdates(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to subscribe to queue updates: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Enhanced health check with connection validation
     */
    public boolean isServiceHealthy() {
        try {
            // First check base connection health
            if (!baseClient.isConnectionHealthy()) {
                logger.fine("Base connection unhealthy");
                return false;
            }

            // Then perform service-specific health check
            HealthCheckResponse response = healthCheck();
            boolean isHealthy = response.getHealthy();

            if (isHealthy) {
                logger.fine("Scheduler service health check passed");
            } else {
                logger.warning("Scheduler service health check failed: " + response.getStatus());
            }

            return isHealthy;
        } catch (Exception e) {
            logger.fine("Scheduler service health check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get system metrics
     */
    public GetSystemMetricsResponse getSystemMetrics() {
        try {
            GetSystemMetricsRequest request = GetSystemMetricsRequest.newBuilder().build();
            return monitoringStub.getSystemMetrics(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to get system metrics: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get node registry
     */
    public GetNodeRegistryResponse getNodeRegistry() {
        try {
            GetNodeRegistryRequest request = GetNodeRegistryRequest.newBuilder().build();
            return monitoringStub.getNodeRegistry(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to get node registry: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get scheduling statistics
     */
    public GetSchedulingStatsResponse getSchedulingStats() {
        try {
            GetSchedulingStatsRequest request = GetSchedulingStatsRequest.newBuilder().build();
            return monitoringStub.getSchedulingStats(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to get scheduling stats: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get live dashboard data
     */
    public GetDashboardResponse getDashboard() {
        try {
            GetDashboardRequest request = GetDashboardRequest.newBuilder().build();
            return monitoringStub.getDashboard(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to get dashboard data: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Check if client is connected
     */
    public boolean isConnected() {
        return baseClient.isConnected();
    }

    @Override
    public void close() {
        baseClient.close();
    }

    /**
     * Get the underlying base client
     */
    public GrpcClient getBaseClient() {
        return baseClient;
    }

    // ===== PERFORMANCE TRACKING METHODS =====

    /**
     * Get total number of requests made
     */
    public long getTotalRequests() {
        return baseClient.getTotalRequests();
    }

    /**
     * Get number of successful requests
     */
    public long getSuccessfulRequests() {
        return baseClient.getSuccessfulRequests();
    }

    /**
     * Get number of failed requests
     */
    public long getFailedRequests() {
        return baseClient.getFailedRequests();
    }

    /**
     * Get success rate
     */
    public double getSuccessRate() {
        return baseClient.getSuccessRate();
    }

    /**
     * Get average latency
     */
    public double getAverageLatency() {
        return baseClient.getAverageLatency();
    }

    /**
     * Get maximum latency
     */
    public double getMaxLatency() {
        return baseClient.getMaxLatency();
    }

    /**
     * Get total energy consumed by this client
     */
    public double getTotalEnergyConsumed() {
        return baseClient.getTotalEnergyConsumed();
    }

    /**
     * Get total cost of this client
     */
    public double getTotalCost() {
        return baseClient.getTotalCost();
    }
}