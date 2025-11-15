package org.patch.client;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.patch.proto.FogAllocationServiceGrpc;
import org.patch.proto.IfogsimAllocation.*;
import org.patch.config.EnhancedConfigurationLoader;
import org.patch.utils.NetworkLatencyConverter;
import org.patch.utils.NetworkEnergyCostCalculator;
import org.patch.models.PendingAllocationRequest;
import org.patch.models.PendingOutcomeRequest;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Specialized gRPC client for task allocation service integration with iFogSim.
 * 
 * <p>
 * This client provides comprehensive task allocation capabilities by connecting
 * to the go-grpc-server service. It enables iFogSim to leverage external
 * reinforcement learning algorithms for intelligent load balancing and task
 * allocation decisions.
 * </p>
 * 
 * <p>
 * Key Features:
 * </p>
 * <ul>
 * <li>Task allocation with RL-based load balancing</li>
 * <li>System state monitoring and reporting</li>
 * <li>Performance metrics collection</li>
 * <li>Graceful degradation with fallback allocation</li>
 * <li>Structured logging with correlation tracking</li>
 * <li>RL parameter configuration and updates</li>
 * </ul>
 * 
 * <p>
 * Supported Operations:
 * </p>
 * <ul>
 * <li>Allocate tasks to optimal cloud resources</li>
 * <li>Report task outcomes for learning</li>
 * <li>Get system state and performance metrics</li>
 * <li>Configure RL algorithm parameters</li>
 * <li>Monitor node states and resource utilization</li>
 * <li>Control RL agent learning and exploration</li>
 * </ul>
 * 
 * <p>
 * Usage Example:
 * </p>
 * 
 * <pre>{@code
 * // Create allocation client
 * GrpcClient baseClient = new GrpcClient("localhost", 50052);
 * AllocationClient allocation = new AllocationClient(baseClient);
 * 
 * // Allocate a task
 * AllocationResponse response = allocation.allocateTask(task, requirements);
 * 
 * // Report task outcome
 * allocation.reportTaskOutcome(taskId, success, metrics);
 * 
 * // Close client
 * allocation.close();
 * }</pre>
 * 
 * @author Younes Shafiee
 * @version 1.0.0
 * @since 1.0.0
 * @see GrpcClient
 * @see FogAllocationServiceGrpc
 */
public class AllocationClient implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(AllocationClient.class.getName());

    private final GrpcClient baseClient;
    private final FogAllocationServiceGrpc.FogAllocationServiceBlockingStub allocationStub;
    private final FogAllocationServiceGrpc.FogAllocationServiceStub asyncStub;
    
    // Async client for event-based operations ()
    private AsyncAllocationClient asyncClient;

    /**
     * Constructor using base GrpcClient
     */
    public AllocationClient(GrpcClient baseClient) {
        this.baseClient = baseClient;
        this.allocationStub = FogAllocationServiceGrpc.newBlockingStub(baseClient.getChannel());
        this.asyncStub = FogAllocationServiceGrpc.newStub(baseClient.getChannel());
        
        // Initialize async client for event-based operations ()
        this.asyncClient = new AsyncAllocationClient(this);
    }

    /**
     * Constructor with host and port
     */
    public AllocationClient(String host, int port) {
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
     * Request task allocation decision with graceful degradation
     */
    public TaskAllocationResponse allocateTask(String taskId, double cpuRequirement,
            double memoryRequirement, double bandwidthRequirement,
            int priority, long deadlineMs, Map<String, String> taskMetadata) {
        long requestStartTime = System.currentTimeMillis();
        
        logger.info(String.format("[IFOGSIM-ALLOC-SEND] Sending allocation request: TaskID=%s, CPU=%.3f, Mem=%.3f, BW=%.2f, Priority=%d, Deadline=%d",
                taskId, cpuRequirement, memoryRequirement, bandwidthRequirement, priority, deadlineMs));
        
        try {
            // Check if service is available
            boolean isConnected = baseClient.isConnected();
            boolean isServiceAvailable = baseClient.isServiceAvailable();
            logger.info(String.format("[IFOGSIM-ALLOC-CONN] Connection check: isConnected=%s, isServiceAvailable=%s",
                    isConnected, isServiceAvailable));
            
            if (!isServiceAvailable) {
                logger.warning(String.format("[IFOGSIM-ALLOC-FALLBACK] Allocation service unavailable, using fallback allocation for TaskID=%s", taskId));
                return createFallbackAllocationResponse(taskId, cpuRequirement, memoryRequirement);
            }

            TaskAllocationRequest request = TaskAllocationRequest.newBuilder()
                    .setTaskId(taskId)
                    .setCpuRequirement(cpuRequirement)
                    .setMemoryRequirement(memoryRequirement)
                    .setBandwidthRequirement(bandwidthRequirement)
                    .setPriority(priority)
                    .setDeadlineMs(0) // Later Feature: deadline-aware disabled
                    .putAllTaskMetadata(taskMetadata)
                    .build();

            logger.info(String.format("[IFOGSIM-ALLOC-CALL] Calling gRPC allocateTask: TaskID=%s", taskId));
            TaskAllocationResponse response = allocationStub.allocateTask(request);
            long latency = System.currentTimeMillis() - requestStartTime;
            
            logger.info(String.format("[IFOGSIM-ALLOC-RESP] Received allocation response: TaskID=%s, AllocatedNode=%s, Success=%s, Latency=%dms",
                    response.getTaskId(), response.getAllocatedNodeId(), response.getSuccess(), latency));
            
            return response;
        } catch (StatusRuntimeException e) {
            long latency = System.currentTimeMillis() - requestStartTime;
            logger.log(Level.SEVERE, String.format("[IFOGSIM-ALLOC-ERROR] Failed to allocate task: TaskID=%s, Error=%s, Latency=%dms",
                    taskId, e.getMessage(), latency), e);

            // Graceful degradation: return fallback response
            logger.warning(String.format("[IFOGSIM-ALLOC-FALLBACK] Using fallback allocation due to service failure for TaskID=%s", taskId));
            return createFallbackAllocationResponse(taskId, cpuRequirement, memoryRequirement);
        }
    }

    /**
     * Create fallback allocation response when service is unavailable
     */
    private TaskAllocationResponse createFallbackAllocationResponse(String taskId, double cpuRequirement,
            double memoryRequirement) {
        // Get fallback configuration values
        String fallbackNodeId = EnhancedConfigurationLoader.getGrpcConfig("grpc.fallback.node.id", "fallback-node-1");
        long executionTime = EnhancedConfigurationLoader.getGrpcConfigLong("grpc.fallback.execution.time", 5000);
        long currentTime = System.currentTimeMillis();

        return TaskAllocationResponse.newBuilder()
                .setSuccess(true)
                .setAllocatedNodeId(fallbackNodeId)
                .setExpectedCompletionTimeMs(currentTime + executionTime)
                .setMessage("Using fallback allocation - service unavailable")
                .build();
    }

    /**
     * Report task outcome for RL learning
     */
    public TaskOutcomeResponse reportTaskOutcome(String taskId, String nodeId,
            boolean completedSuccessfully, long actualExecutionTimeMs,
            double actualCpuUsage, double actualMemoryUsage,
            String errorMessage) {
        try {
            TaskOutcomeRequest request = TaskOutcomeRequest.newBuilder()
                    .setTaskId(taskId)
                    .setNodeId(nodeId)
                    .setCompletedSuccessfully(completedSuccessfully)
                    .setActualExecutionTimeMs(actualExecutionTimeMs)
                    .setActualCpuUsage(actualCpuUsage)
                    .setActualMemoryUsage(actualMemoryUsage)
                    .setErrorMessage(errorMessage)
                    .build();

            return allocationStub.reportTaskOutcome(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to report task outcome: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get system state overview
     */
    public SystemStateResponse getSystemState(boolean includeDetailedMetrics) {
        try {
            SystemStateRequest request = SystemStateRequest.newBuilder()
                    .setIncludeDetailedMetrics(includeDetailedMetrics)
                    .build();

            return allocationStub.getSystemState(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to get system state: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Control RL agent (enable/disable learning, switch algorithms)
     */
    public RLAgentControlResponse controlRLAgent(String action, String algorithmName,
            Map<String, String> taskMetadata) {
        try {
            RLAgentControlRequest request = RLAgentControlRequest.newBuilder()
                    .setAction(action)
                    .setAlgorithmName(algorithmName)
                    .putAllTaskMetadata(taskMetadata)
                    .build();

            return allocationStub.controlRLAgent(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to control RL agent: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get RL agent status
     */
    public RLAgentStatusResponse getRLAgentStatus() {
        try {
            RLAgentStatusRequest request = RLAgentStatusRequest.newBuilder().build();
            return allocationStub.getRLAgentStatus(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to get RL agent status: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get RL performance metrics
     */
    public RLPerformanceResponse getRLPerformanceMetrics() {
        try {
            RLPerformanceRequest request = RLPerformanceRequest.newBuilder().build();
            return allocationStub.getRLPerformanceMetrics(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to get RL performance metrics: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Set RL algorithm parameters for tuning
     */
    public RLParametersResponse setRLParameters(Map<String, Double> parameters) {
        try {
            RLParametersRequest request = RLParametersRequest.newBuilder()
                    .putAllParameters(parameters)
                    .build();
            return allocationStub.setRLParameters(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to set RL parameters: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Report node state (streaming method)
     * Note: This is a streaming method that requires special handling
     * Use startNodeStateReporting() for proper streaming implementation
     */
    public void reportNodeState(NodeStateRequest nodeState) {
        try {
            // For streaming methods, we need to use the async stub
            // This is a simplified implementation - for production use
            // startNodeStateReporting()
            logger.info("Reporting node state for node: " + nodeState.getNodeId());

            // Create a simple response observer for the streaming call
            StreamObserver<NodeStateResponse> responseObserver = new StreamObserver<NodeStateResponse>() {
                @Override
                public void onNext(NodeStateResponse response) {
                    logger.fine("Received node state response: " + response.getMessage());
                }

                @Override
                public void onError(Throwable t) {
                    logger.warning("Node state reporting error: " + t.getMessage());
                }

                @Override
                public void onCompleted() {
                    logger.fine("Node state reporting completed");
                }
            };

            // Start the streaming call
            StreamObserver<NodeStateRequest> requestObserver = asyncStub.reportNodeState(responseObserver);
            requestObserver.onNext(nodeState);
            requestObserver.onCompleted();

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to report node state: " + e.getMessage(), e);
            throw new RuntimeException("Node state reporting failed", e);
        }
    }

    /**
     * Start streaming node state reports
     */
    public StreamObserver<NodeStateRequest> startNodeStateReporting(
            StreamObserver<NodeStateResponse> responseObserver) {
        return asyncStub.reportNodeState(responseObserver);
    }

    /**
     * Create a node state request
     */
    public NodeStateRequest createNodeStateRequest(String nodeId, double cpuUtilization,
            double memoryUtilization, double networkBandwidth,
            int taskCount, Map<String, Double> customMetrics) {
        return NodeStateRequest.newBuilder()
                .setNodeId(nodeId)
                .setCpuUtilization(cpuUtilization)
                .setMemoryUtilization(memoryUtilization)
                .setNetworkBandwidth(networkBandwidth)
                .setTaskCount(taskCount)
                .putAllCustomMetrics(customMetrics)
                .build();
    }

    /**
     * Health check using system state as indicator
     */
    public boolean healthCheck() {
        try {
            // Use system state request as health indicator
            SystemStateResponse response = getSystemState(false);
            return response != null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Health check failed: " + e.getMessage(), e);
            return false;
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
            boolean isHealthy = healthCheck();

            if (isHealthy) {
                logger.fine("Allocation service health check passed");
            } else {
                logger.warning("Allocation service health check failed");
            }

            return isHealthy;
        } catch (Exception e) {
            logger.fine("Allocation service health check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if client is connected
     */
    public boolean isConnected() {
        return baseClient.isConnected();
    }

    // ===== EVENT-BASED ASYNC METHODS () =====
    
    /**
     * Event-based async version of allocateTask.
     * Makes async gRPC call, converts latency to simulation time, calculates energy/cost,
     * and schedules a CloudSim event for the response.
     * 
     * @param taskId Task identifier
     * @param cpuRequirement CPU requirement
     * @param memoryRequirement Memory requirement
     * @param bandwidthRequirement Bandwidth requirement
     * @param priority Task priority
     * @param deadlineMs Deadline in milliseconds
     * @param taskMetadata Task metadata
     * @param deviceId Device ID for event scheduling
     * @param tuple Tuple object (for state management - )
     * @return PendingAllocationRequest for tracking the async operation
     */
    public PendingAllocationRequest allocateTaskAsync(
            String taskId,
            double cpuRequirement,
            double memoryRequirement,
            double bandwidthRequirement,
            int priority,
            long deadlineMs,
            Map<String, String> taskMetadata,
            int deviceId,
            org.fog.entities.Tuple tuple) {
        // Record start time
        long realStartTime = System.currentTimeMillis();
        double simulationStartTime = CloudSim.clock();
        
        // Make async gRPC call
        java.util.concurrent.CompletableFuture<TaskAllocationResponse> future = 
            asyncClient.allocateTaskAsync(taskId, cpuRequirement, memoryRequirement,
                bandwidthRequirement, priority, deadlineMs, taskMetadata);
        
        // Estimate message size (rough approximation)
        long messageSizeBytes = estimateAllocationMessageSize(taskId, cpuRequirement, 
            memoryRequirement, taskMetadata);
        
        // Estimate latency (we'll use actual latency when response arrives)
        double estimatedSimulationLatency = NetworkLatencyConverter.convertToSimulationTime(50); // 50ms estimate
        
        // Calculate estimated energy and cost
        double estimatedEnergy = NetworkEnergyCostCalculator.calculateNetworkEnergy(
            estimatedSimulationLatency, messageSizeBytes);
        double estimatedCost = NetworkEnergyCostCalculator.calculateNetworkCost(
            estimatedSimulationLatency, messageSizeBytes);
        
        // Create pending request with Tuple
        PendingAllocationRequest pending = new PendingAllocationRequest(
            taskId, tuple, future, realStartTime, 
            simulationStartTime, estimatedEnergy, estimatedCost);
        
        // Schedule timeout event
        long timeoutMs = EnhancedConfigurationLoader.getSimulationConfigLong(
            "simulation.network.latency.timeout-ms", 5000);
        double timeoutSimulationSec = NetworkLatencyConverter.convertToSimulationTime(timeoutMs);
        CloudSim.send(deviceId, deviceId, timeoutSimulationSec, 
            org.patch.utils.ExtendedFogEvents.GRPC_ALLOCATOR_TIMEOUT, pending);
        
        logger.info(String.format(
            "[DEBUG-ASYNC-ALLOCATOR] Time: %.2f - Created pending allocation request for task: %s (Est. Energy: %.6f J, Est. Cost: %.8f $, Timeout: %d ms)",
            simulationStartTime, taskId, estimatedEnergy, estimatedCost, timeoutMs));
        
        // When future completes, calculate actual latency and schedule event
        future.whenComplete((response, throwable) -> {
            long realLatency = System.currentTimeMillis() - realStartTime;
            double simulationLatency = NetworkLatencyConverter.convertToSimulationTime(realLatency);
            
            // Error Handling - Check for exceptions
            if (throwable != null) {
                // Error occurred - schedule error event
                logger.severe(String.format(
                    "[GRPC-ALLOCATOR-ASYNC] Error in async call for task: %s - %s",
                    taskId, throwable.getMessage()));
                // Error will be handled in timeout handler or response handler
                return;
            }
            
            // Calculate actual energy and cost
            double actualEnergy = NetworkEnergyCostCalculator.calculateNetworkEnergy(
                simulationLatency, messageSizeBytes);
            double actualCost = NetworkEnergyCostCalculator.calculateNetworkCost(
                simulationLatency, messageSizeBytes);
            
            logger.info(String.format(
                "[DEBUG-ASYNC-ALLOCATOR] Time: %.2f - Scheduling allocation response event for task: %s (Real latency: %d ms, Sim latency: %.4f sec, Energy: %.6f J, Cost: %.8f $)",
                CloudSim.clock(), taskId, realLatency, simulationLatency, actualEnergy, actualCost));
            
            // CRITICAL: Add to deferred queue instead of calling CloudSim.send() directly
            // This prevents ConcurrentModificationException by deferring until end of tick
            double validDelay = NetworkLatencyConverter.ensureValidEventDelay(simulationLatency);
            org.patch.utils.DeferredEventQueue.addDeferredEvent(
                deviceId,
                deviceId,
                validDelay,
                org.patch.utils.ExtendedFogEvents.GRPC_ALLOCATOR_RESPONSE,
                pending
            );
        });
        
        return pending;
    }
    
    /**
     * Event-based async version of reportTaskOutcome.
     * Makes async gRPC call, converts latency to simulation time, calculates energy/cost,
     * and schedules a CloudSim event for the response.
     * 
     * @param taskId Task identifier
     * @param nodeId Node identifier
     * @param completedSuccessfully Whether task completed successfully
     * @param actualExecutionTimeMs Actual execution time
     * @param actualCpuUsage Actual CPU usage
     * @param actualMemoryUsage Actual memory usage
     * @param errorMessage Error message if failed
     * @param deviceId Device ID for event scheduling
     * @return PendingOutcomeRequest for tracking the async operation
     */
    public PendingOutcomeRequest reportTaskOutcomeAsync(
            String taskId,
            String nodeId,
            boolean completedSuccessfully,
            long actualExecutionTimeMs,
            double actualCpuUsage,
            double actualMemoryUsage,
            String errorMessage,
            int deviceId) {
        // Record start time
        long realStartTime = System.currentTimeMillis();
        double simulationStartTime = CloudSim.clock();
        
        // Make async gRPC call
        java.util.concurrent.CompletableFuture<TaskOutcomeResponse> future = 
            asyncClient.reportTaskOutcomeAsync(taskId, nodeId, completedSuccessfully,
                actualExecutionTimeMs, actualCpuUsage, actualMemoryUsage, errorMessage);
        
        // Estimate message size
        long messageSizeBytes = estimateOutcomeMessageSize(taskId, nodeId, errorMessage);
        
        // Estimate latency
        double estimatedSimulationLatency = NetworkLatencyConverter.convertToSimulationTime(30); // 30ms estimate
        
        // Calculate estimated energy and cost
        double estimatedEnergy = NetworkEnergyCostCalculator.calculateNetworkEnergy(
            estimatedSimulationLatency, messageSizeBytes);
        double estimatedCost = NetworkEnergyCostCalculator.calculateNetworkCost(
            estimatedSimulationLatency, messageSizeBytes);
        
        // Create pending request
        PendingOutcomeRequest pending = new PendingOutcomeRequest(
            taskId, future, realStartTime, simulationStartTime, estimatedEnergy, estimatedCost);
        
        // 
        long timeoutMs = EnhancedConfigurationLoader.getSimulationConfigLong(
            "simulation.network.latency.timeout-ms", 5000);
        double timeoutSimulationSec = NetworkLatencyConverter.convertToSimulationTime(timeoutMs);
        CloudSim.send(deviceId, deviceId, timeoutSimulationSec, 
            org.patch.utils.ExtendedFogEvents.GRPC_ALLOCATOR_OUTCOME_TIMEOUT, pending);
        
        // When future completes, calculate actual latency and schedule event
        future.whenComplete((response, throwable) -> {
            long realLatency = System.currentTimeMillis() - realStartTime;
            double simulationLatency = NetworkLatencyConverter.convertToSimulationTime(realLatency);
            
            // 
            if (throwable != null) {
                // Error occurred - log but don't fail (outcome reporting is best-effort)
                logger.warning(String.format(
                    "[GRPC-ALLOCATOR-OUTCOME-ASYNC] Error in async outcome call for task: %s - %s",
                    taskId, throwable.getMessage()));
                return;
            }
            
            // CRITICAL: Add to deferred queue instead of calling CloudSim.send() directly
            // This prevents ConcurrentModificationException by deferring until end of tick
            double validDelay = NetworkLatencyConverter.ensureValidEventDelay(simulationLatency);
            org.patch.utils.DeferredEventQueue.addDeferredEvent(
                deviceId,
                deviceId,
                validDelay,
                org.patch.utils.ExtendedFogEvents.GRPC_ALLOCATOR_OUTCOME_RESPONSE,
                pending
            );
        });
        
        return pending;
    }
    
    /**
     * Estimate message size for allocation request
     */
    private long estimateAllocationMessageSize(String taskId, double cpuRequirement,
            double memoryRequirement, Map<String, String> taskMetadata) {
        long size = 100; // Base overhead
        size += taskId.length() * 2; // Task ID (UTF-8)
        size += 24; // CPU, memory, bandwidth (doubles)
        size += 8; // Priority, deadline (int, long)
        if (taskMetadata != null) {
            size += taskMetadata.size() * 50; // Metadata overhead
        }
        return size;
    }
    
    /**
     * Estimate message size for outcome report
     */
    private long estimateOutcomeMessageSize(String taskId, String nodeId, String errorMessage) {
        long size = 50; // Base overhead
        size += (taskId != null ? taskId.length() : 0) * 2;
        size += (nodeId != null ? nodeId.length() : 0) * 2;
        size += (errorMessage != null ? errorMessage.length() : 0) * 2;
        size += 16; // Execution time, CPU, memory (long, doubles)
        size += 1; // Boolean
        return size;
    }
    
    @Override
    public void close() {
        if (asyncClient != null) {
            asyncClient.shutdown();
        }
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