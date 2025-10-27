package org.patch.client;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.patch.proto.FogAllocationServiceGrpc;
import org.patch.proto.IfogsimAllocation.*;
import org.patch.config.EnhancedConfigurationLoader;

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

    /**
     * Constructor using base GrpcClient
     */
    public AllocationClient(GrpcClient baseClient) {
        this.baseClient = baseClient;
        this.allocationStub = FogAllocationServiceGrpc.newBlockingStub(baseClient.getChannel());
        this.asyncStub = FogAllocationServiceGrpc.newStub(baseClient.getChannel());
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
        try {
            // Check if service is available
            if (!baseClient.isServiceAvailable()) {
                logger.warning("Allocation service unavailable, using fallback allocation");
                return createFallbackAllocationResponse(taskId, cpuRequirement, memoryRequirement);
            }

            TaskAllocationRequest request = TaskAllocationRequest.newBuilder()
                    .setTaskId(taskId)
                    .setCpuRequirement(cpuRequirement)
                    .setMemoryRequirement(memoryRequirement)
                    .setBandwidthRequirement(bandwidthRequirement)
                    .setPriority(priority)
                    .setDeadlineMs(deadlineMs)
                    .putAllTaskMetadata(taskMetadata)
                    .build();

            return allocationStub.allocateTask(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.SEVERE, "Failed to allocate task: " + e.getMessage(), e);

            // Graceful degradation: return fallback response
            logger.warning("Using fallback allocation due to service failure");
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