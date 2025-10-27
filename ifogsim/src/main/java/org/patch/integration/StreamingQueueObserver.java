package org.patch.integration;

import org.patch.client.SchedulerClient;
import org.patch.models.ScheduledQueue;
import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;
import org.fog.entities.Tuple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.utils.TupleFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.function.Consumer;

/**
 * Streaming Queue Observer for real-time queue updates from grpc-task-scheduler
 * 
 * This class handles streaming updates from the gRPC scheduler server to keep
 * the scheduled queue current with RL decisions. It manages the streaming
 * connection
 * and processes queue updates asynchronously.
 * 
 * Key Features:
 * - Non-blocking streaming connection to gRPC scheduler
 * - Real-time queue updates based on RL decisions
 * - Error handling and reconnection logic
 * - Integration with iFogSim's event system
 * - Robust retry mechanism with exponential backoff
 * 
 * @author Younes Shafiee
 */
public class StreamingQueueObserver {
    private static final Logger logger = Logger.getLogger(StreamingQueueObserver.class.getName());

    // Core components
    private final SchedulerClient schedulerClient;
    private final ScheduledQueue scheduledQueue;
    private final int deviceId;

    // Streaming state
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private CompletableFuture<Void> streamingFuture;

    // Configuration
    private final long streamingIntervalMs = 1000; // 1 second intervals
    private final int maxRetries = 3;
    private final long retryDelayMs = 5000; // 5 seconds

    // Callback for queue updates
    private Consumer<ScheduledQueue> queueUpdateCallback;

    /**
     * Constructor
     * 
     * @param schedulerClient The gRPC client for scheduler communication
     * @param scheduledQueue  The scheduled task queue
     * @param deviceId        The device ID for event scheduling
     */
    public StreamingQueueObserver(SchedulerClient schedulerClient,
            ScheduledQueue scheduledQueue,
            int deviceId) {
        this.schedulerClient = schedulerClient;
        this.scheduledQueue = scheduledQueue;
        this.deviceId = deviceId;

        logger.info("StreamingQueueObserver initialized for device: " + deviceId);
    }

    /**
     * Set callback for queue updates
     * 
     * @param callback The callback to be called when queue is updated
     */
    public void setQueueUpdateCallback(Consumer<ScheduledQueue> callback) {
        this.queueUpdateCallback = callback;
    }

    /**
     * Start streaming queue updates from the scheduler
     * 
     * @return true if streaming started successfully
     */
    public boolean startStreaming() {
        try {
            if (isStreaming.get()) {
                logger.warning("Streaming already active for device: " + deviceId);
                return true;
            }

            if (schedulerClient == null) {
                logger.severe("Cannot start streaming: scheduler client is null");
                return false;
            }

            if (!schedulerClient.isConnected()) {
                logger.severe("Cannot start streaming: scheduler client not connected");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error checking streaming prerequisites", e);
            return false;
        }

        shouldStop.set(false);
        isStreaming.set(true);

        // Start streaming asynchronously
        streamingFuture = CompletableFuture.runAsync(this::streamingLoop);

        logger.info("Started streaming queue updates for device: " + deviceId);
        return true;
    }

    /**
     * Stop streaming queue updates
     */
    public void stopStreaming() {
        if (!isStreaming.get()) {
            logger.warning("Streaming not active for device: " + deviceId);
            return;
        }

        shouldStop.set(true);
        isStreaming.set(false);

        // Wait for streaming to stop
        if (streamingFuture != null) {
            try {
                streamingFuture.get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error stopping streaming", e);
            }
        }

        logger.info("Stopped streaming queue updates for device: " + deviceId);
    }

    /**
     * Main streaming loop
     */
    private void streamingLoop() {
        logger.info("Streaming loop started for device: " + deviceId);

        while (!shouldStop.get() && isStreaming.get()) {
            try {
                // Get current queue state from scheduler
                GetSortedQueueResponse response = getSortedQueueFromScheduler();

                if (response != null) {
                    processQueueUpdate(response);
                }

                // Wait before next update
                Thread.sleep(streamingIntervalMs);

            } catch (InterruptedException e) {
                logger.info("Streaming interrupted for device: " + deviceId);
                break;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in streaming loop for device: " + deviceId, e);

                // Wait before retry
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }

        isStreaming.set(false);
        logger.info("Streaming loop ended for device: " + deviceId);
    }

    /**
     * Get sorted queue from scheduler with retry logic
     * 
     * @return GetSortedQueueResponse or null if failed
     */
    private GetSortedQueueResponse getSortedQueueFromScheduler() {
        int retries = 0;

        while (retries < maxRetries && !shouldStop.get()) {
            try {
                if (!schedulerClient.isConnected()) {
                    logger.warning("Scheduler client disconnected, attempting reconnection");
                    // Attempt to reconnect
                    try {
                        schedulerClient.healthCheck();
                    } catch (Exception e) {
                        retries++;
                        continue;
                    }
                }

                // Request sorted queue from scheduler
                GetSortedQueueResponse response = schedulerClient.getSortedQueue(String.valueOf(deviceId));

                if (response != null) {
                    return response;
                }

            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to get sorted queue (attempt " + (retries + 1) + ")", e);
                retries++;

                if (retries < maxRetries) {
                    try {
                        // Exponential backoff with jitter
                        long delay = Math.min(retryDelayMs * (long) Math.pow(2, retries), 30000); // Max 30 seconds
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }

        logger.severe("Failed to get sorted queue after " + maxRetries + " attempts");
        return null;
    }

    /**
     * Process queue update from scheduler
     * 
     * @param response The response from scheduler
     */
    private void processQueueUpdate(GetSortedQueueResponse response) {
        try {
            logger.fine("Processing queue update for device: " + deviceId +
                    " with " + response.getQueueTasksCount() + " tasks");

            // Update scheduled queue with new ordering
            updateScheduledQueue(response);

            logger.fine("Successfully processed queue update for device: " + deviceId);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error processing queue update", e);
        }
    }

    /**
     * Update scheduled queue with new ordering from scheduler
     * 
     * @param response The response from scheduler
     */
    private void updateScheduledQueue(GetSortedQueueResponse response) {
        try {
            // Clear current scheduled queue
            scheduledQueue.clear();

            // Add tasks in the new order from scheduler
            for (Task task : response.getQueueTasksList()) {
                // Convert proto task to internal format
                ScheduledQueue.TaskInfo taskInfo = convertTaskToTaskInfo(task);

                if (taskInfo != null) {
                    scheduledQueue.addTask(taskInfo);
                }
            }

            logger.fine("Updated scheduled queue with " + response.getQueueTasksCount() +
                    " tasks for device: " + deviceId);

            // Trigger callback if set
            if (queueUpdateCallback != null) {
                queueUpdateCallback.accept(scheduledQueue);
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error updating scheduled queue", e);
        }
    }

    /**
     * Convert Task proto to TaskInfo
     * 
     * @param task The proto task
     * @return TaskInfo or null if conversion failed
     */
    private ScheduledQueue.TaskInfo convertTaskToTaskInfo(Task task) {
        try {
            // Convert proto task to tuple
            Tuple tuple = convertProtoTaskToTuple(task);

            if (tuple == null) {
                return null;
            }

            // Create TaskInfo with default values (proto doesn't have all fields)
            return new ScheduledQueue.TaskInfo(
                    tuple,
                    0, // moduleId - will be set by tuple processing
                    String.valueOf(deviceId), // assignedNodeId
                    (long) CloudSim.clock(), // estimatedStartTime - use simulation time
                    (long) (CloudSim.clock() + task.getExecutionTime()), // estimatedCompletionTime - use simulation
                                                                         // time
                    false, // isCached
                    "" // cacheKey
            );

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error converting task to TaskInfo", e);
            return null;
        }
    }

    /**
     * Convert proto Task to Tuple (iFogSim compatible)
     * 
     * @param task The proto task
     * @return Tuple or null if conversion failed
     */
    private Tuple convertProtoTaskToTuple(Task task) {
        return TupleFactory.createFromProtoTask(task, deviceId);
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        stopStreaming();

        if (schedulerClient != null) {
            // Don't close the scheduler client here as it might be shared
            logger.info("StreamingQueueObserver cleanup completed for device: " + deviceId);
        }
    }
}
