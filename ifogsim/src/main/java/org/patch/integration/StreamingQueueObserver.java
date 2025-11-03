package org.patch.integration;

import org.patch.client.SchedulerClient;
import org.patch.models.ScheduledQueue;
import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;
import org.fog.entities.Tuple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.utils.TupleFactory;
import org.fog.utils.Config;
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

            // Wait a bit for connection to establish if not immediately ready
            int waitAttempts = 0;
            int maxWaitAttempts = 10; // Wait up to 1 second (10 * 100ms)
            while (!schedulerClient.isConnected() && waitAttempts < maxWaitAttempts) {
                try {
                    Thread.sleep(100); // Wait 100ms between checks
                    waitAttempts++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!schedulerClient.isConnected()) {
                logger.severe("Cannot start streaming: scheduler client not connected after waiting");
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
                // Check if simulation is still running first
                // CloudSim.clock() might return stale values after simulation ends
                if (!CloudSim.running()) {
                    logger.info(String.format(
                            "Stopping streaming loop for device %d - Simulation is no longer running",
                            deviceId));
                    shouldStop.set(true);
                    break;
                }

                // Check if simulation has ended
                double currentTime = CloudSim.clock();
                double maxSimulationTime = Config.MAX_SIMULATION_TIME;

                // Safety check: if currentTime is abnormally large, simulation might have ended
                // CloudSim.clock() can return inconsistent values after simulation ends
                if (currentTime > maxSimulationTime * 100) {
                    logger.warning(String.format(
                            "Stopping streaming loop for device %d - Simulation time %.2f is abnormally large (expected < %.2f), simulation may have ended",
                            deviceId, currentTime, maxSimulationTime));
                    shouldStop.set(true);
                    break;
                }

                // Stop streaming when simulation time exceeds MAX_SIMULATION_TIME
                // Add a small buffer (10 seconds) to allow final events to be processed
                if (currentTime >= (maxSimulationTime + 10.0)) {
                    logger.info(String.format(
                            "Stopping streaming loop for device %d - Simulation time %.2f >= MAX_SIMULATION_TIME %.2f",
                            deviceId, currentTime, maxSimulationTime));
                    shouldStop.set(true);
                    break;
                }

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
                logger.info(String.format("[IFOGSIM-QUEUE-GET] Device %d requesting sorted queue from scheduler",
                        deviceId));
                GetSortedQueueResponse response = schedulerClient.getSortedQueue(String.valueOf(deviceId));

                if (response != null) {
                    logger.info(String.format("[IFOGSIM-QUEUE-RESP] Device %d received queue: Tasks=%d",
                            deviceId, response.getQueueTasksCount()));
                    return response;
                } else {
                    logger.warning(
                            String.format("[IFOGSIM-QUEUE-NULL] Device %d received null queue response", deviceId));
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
            int taskCount = response.getQueueTasksCount();
            double currentTime = CloudSim.clock();

            // [DEBUG] Log scheduled queue update from streaming endpoint - ENHANCED
            String nodeId = response.getNodeId();
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULED-QUEUE-RECEIVE] Time: %.2f - FogNode (ID:%d) - Received scheduled queue update from streaming endpoint: Tasks=%d, NodeID=%s",
                    currentTime, deviceId, taskCount, nodeId));

            // Log task details if queue has tasks (first 10)
            if (taskCount > 0 && taskCount <= 10) {
                StringBuilder taskDetails = new StringBuilder();
                java.util.List<Task> tasksList = response.getQueueTasksList();
                for (int i = 0; i < taskCount && i < 10 && i < tasksList.size(); i++) {
                    Task task = tasksList.get(i);
                    if (i > 0) {
                        taskDetails.append("|");
                    }
                    taskDetails.append(String.format("ID=%s,CPU=%d,Mem=%d",
                            task.getTaskId(), task.getCpuRequirement(), task.getMemoryRequirement()));
                }
                logger.info(String.format("[FLOW-FOG-SCHEDULED-QUEUE-DETAILS] Device %d - Queue tasks: %s",
                        deviceId, taskDetails.toString()));
            }

            logger.fine("Processing queue update for device: " + deviceId +
                    " with " + taskCount + " tasks");

            // Update scheduled queue with new ordering
            int oldSize = scheduledQueue.size();
            updateScheduledQueue(response);
            int newSize = scheduledQueue.size();

            // [DEBUG] Log scheduled queue update result - ENHANCED
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULED-QUEUE-UPDATE] Time: %.2f - FogNode (ID:%d) - Scheduled queue updated: oldSize=%d, newSize=%d, QueueIsEmpty=%s (from streaming endpoint)",
                    CloudSim.clock(), deviceId, oldSize, newSize, scheduledQueue.isEmpty() ? "YES" : "NO"));

            // Log if queue went from empty to non-empty (tasks are ready to execute)
            if (oldSize == 0 && newSize > 0) {
                System.out.println(String.format(
                        "[FLOW-FOG-SCHEDULED-QUEUE-READY] Time: %.2f - FogNode (ID:%d) - Scheduled queue NOW HAS TASKS! Ready for execution (queue size: %d)",
                        CloudSim.clock(), deviceId, newSize));
                logger.info(String.format("Scheduled queue now has %d tasks - ready for execution", newSize));
            }

            logger.fine("Successfully processed queue update for device: " + deviceId);

            // Trigger callback if queue has tasks to trigger task execution
            if (queueUpdateCallback != null && !scheduledQueue.isEmpty()) {
                System.out.println(String.format(
                        "[FLOW-FOG-SCHEDULED-QUEUE-CALLBACK] Time: %.2f - FogNode (ID:%d) - Triggering execution callback (scheduled queue size: %d) - Tasks ready for execution",
                        CloudSim.clock(), deviceId, scheduledQueue.size()));
                queueUpdateCallback.accept(scheduledQueue);
                System.out.println(String.format(
                        "[FLOW-FOG-SCHEDULED-QUEUE-CALLBACK] Time: %.2f - FogNode (ID:%d) - Execution callback triggered (scheduled queue size: %d)",
                        CloudSim.clock(), deviceId, scheduledQueue.size()));
            } else if (scheduledQueue.isEmpty()) {
                System.out.println(String.format(
                        "[FLOW-FOG-SCHEDULED-QUEUE-EMPTY] Time: %.2f - FogNode (ID:%d) - Scheduled queue is EMPTY, no execution callback triggered",
                        CloudSim.clock(), deviceId));
            }

        } catch (Exception e) {
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULED-QUEUE] Time: %.2f - FogNode (ID:%d) - ERROR processing queue update: %s",
                    CloudSim.clock(), deviceId, e.getMessage()));
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
