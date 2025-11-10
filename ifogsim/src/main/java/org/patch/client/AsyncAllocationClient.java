package org.patch.client;

import org.patch.proto.IfogsimAllocation.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Async wrapper for AllocationClient to support event-based gRPC operations.
 * 
 * <p>
 * This class wraps blocking gRPC calls in CompletableFuture to enable asynchronous
 * operations that can be integrated with CloudSim's event system. The async calls
 * are executed in a separate thread pool to avoid blocking the simulation thread.
 * </p>
 * 
 * <p>
 * 
 * Future phases will integrate these with CloudSim events to advance simulation time.
 * </p>
 * 
 * @author Younes Shafiee
 * @version 1.0.0
 * @since 1.0.0
 * @see AllocationClient
 */
public class AsyncAllocationClient {
    private static final Logger logger = Logger.getLogger(AsyncAllocationClient.class.getName());
    
    private final AllocationClient allocationClient;
    private final ExecutorService executorService;
    
    /**
     * Constructor using existing AllocationClient
     */
    public AsyncAllocationClient(AllocationClient allocationClient) {
        this.allocationClient = allocationClient;
        // Use a dedicated thread pool for async gRPC operations
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "AsyncAllocationClient-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Async version of allocateTask.
     * Returns a CompletableFuture that completes when the gRPC call finishes.
     * 
     * @param taskId Task identifier
     * @param cpuRequirement CPU requirement
     * @param memoryRequirement Memory requirement
     * @param bandwidthRequirement Bandwidth requirement
     * @param priority Task priority
     * @param deadlineMs Deadline in milliseconds
     * @param taskMetadata Task metadata
     * @return CompletableFuture that will contain the response
     */
    public CompletableFuture<TaskAllocationResponse> allocateTaskAsync(
            String taskId,
            double cpuRequirement,
            double memoryRequirement,
            double bandwidthRequirement,
            int priority,
            long deadlineMs,
            Map<String, String> taskMetadata) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return allocationClient.allocateTask(
                    taskId, cpuRequirement, memoryRequirement, 
                    bandwidthRequirement, priority, deadlineMs, taskMetadata);
            } catch (Exception e) {
                logger.severe("Async allocateTask failed: " + e.getMessage());
                throw new RuntimeException("Async allocation failed", e);
            }
        }, executorService);
    }
    
    /**
     * Async version of reportTaskOutcome.
     * Returns a CompletableFuture that completes when the gRPC call finishes.
     * 
     * @param taskId Task identifier
     * @param nodeId Node identifier where task was executed
     * @param completedSuccessfully Whether task execution succeeded
     * @param actualExecutionTimeMs Actual execution time in milliseconds
     * @param actualCpuUsage Actual CPU usage
     * @param actualMemoryUsage Actual memory usage
     * @param errorMessage Error message if task failed
     * @return CompletableFuture that will contain the response
     */
    public CompletableFuture<TaskOutcomeResponse> reportTaskOutcomeAsync(
            String taskId,
            String nodeId,
            boolean completedSuccessfully,
            long actualExecutionTimeMs,
            double actualCpuUsage,
            double actualMemoryUsage,
            String errorMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return allocationClient.reportTaskOutcome(
                    taskId, nodeId, completedSuccessfully, 
                    actualExecutionTimeMs, actualCpuUsage, 
                    actualMemoryUsage, errorMessage);
            } catch (Exception e) {
                logger.severe("Async reportTaskOutcome failed: " + e.getMessage());
                throw new RuntimeException("Async outcome reporting failed", e);
            }
        }, executorService);
    }
    
    /**
     * Shutdown the executor service.
     * Should be called when the client is no longer needed.
     */
    public void shutdown() {
        executorService.shutdown();
    }
    
    /**
     * Get the underlying AllocationClient (for direct access if needed)
     */
    public AllocationClient getAllocationClient() {
        return allocationClient;
    }
}

