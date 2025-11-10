package org.patch.client;

import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Async wrapper for SchedulerClient to support event-based gRPC operations.
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
 * @see SchedulerClient
 */
public class AsyncSchedulerClient {
    private static final Logger logger = Logger.getLogger(AsyncSchedulerClient.class.getName());
    
    private final SchedulerClient schedulerClient;
    private final ExecutorService executorService;
    
    /**
     * Constructor using existing SchedulerClient
     */
    public AsyncSchedulerClient(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
        // Use a dedicated thread pool for async gRPC operations
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "AsyncSchedulerClient-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Async version of addTaskToQueue.
     * Returns a CompletableFuture that completes when the gRPC call finishes.
     * 
     * @param task The task to schedule
     * @param availableNodes List of available fog nodes
     * @param policy Scheduling policy
     * @return CompletableFuture that will contain the response
     */
    public CompletableFuture<AddTaskToQueueResponse> addTaskToQueueAsync(
            Task task, 
            List<FogNode> availableNodes, 
            SchedulingPolicy policy) {
        return addTaskToQueueAsync(task, availableNodes, policy, null);
    }
    
    /**
     * Async version of addTaskToQueue with QueueContext.
     * Returns a CompletableFuture that completes when the gRPC call finishes.
     * 
     * @param task The task to schedule
     * @param availableNodes List of available fog nodes
     * @param policy Scheduling policy
     * @param queueContext Optional queue context
     * @return CompletableFuture that will contain the response
     */
    public CompletableFuture<AddTaskToQueueResponse> addTaskToQueueAsync(
            Task task, 
            List<FogNode> availableNodes, 
            SchedulingPolicy policy,
            QueueContext queueContext) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (queueContext != null) {
                    return schedulerClient.addTaskToQueue(task, availableNodes, policy, queueContext);
                } else {
                    return schedulerClient.addTaskToQueue(task, availableNodes, policy);
                }
            } catch (Exception e) {
                logger.severe("Async addTaskToQueue failed: " + e.getMessage());
                throw new RuntimeException("Async scheduling failed", e);
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
     * Get the underlying SchedulerClient (for direct access if needed)
     */
    public SchedulerClient getSchedulerClient() {
        return schedulerClient;
    }
}

