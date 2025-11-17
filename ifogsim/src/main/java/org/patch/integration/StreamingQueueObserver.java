package org.patch.integration;

import org.patch.client.SchedulerClient;
import org.patch.models.ScheduledQueue;
import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;
import org.patch.proto.IfogsimCommon.CacheAction;
import org.fog.entities.Tuple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.utils.TupleFactory;
import org.patch.config.EnhancedConfigurationLoader;
import org.fog.utils.Config;
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

    // Configuration
    private final long streamingIntervalMs; // Configurable via YAML (default: 1000ms)
    private final double streamingIntervalSeconds; // Converted to simulation time (seconds)
    private final int maxRetries = 3;
    private final long retryDelayMs = 5000; // 5 seconds
    
    // Polling optimization: Track poll count and implement limits
    private int pollCount = 0;
    private int maxPolls = 1000; // Maximum polls per simulation (proportional to simulation time)
    private int consecutiveEmptyPolls = 0; // Track consecutive empty queue polls
    private static final int MAX_CONSECUTIVE_EMPTY_POLLS = 5; // Skip polling after 5 consecutive empty polls
    private static final double ADAPTIVE_POLL_INTERVAL_MULTIPLIER = 2.0; // Double interval when queue is empty
    private double currentPollInterval; // Current adaptive poll interval

    // Callback for queue updates
    private Consumer<ScheduledQueue> queueUpdateCallback;

    // Reference to RLFogDevice for event scheduling (will be set by RLFogDevice)
    private org.cloudbus.cloudsim.core.SimEntity deviceEntity;

    // Reference to TaskExecutionEngine for checking active tasks (will be set by
    // RLFogDevice)
    private org.patch.processing.TaskExecutionEngine taskExecutionEngine;

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

        // Load streaming interval from YAML config (with fallback to env var and
        // default)
        // Priority: YAML config > Environment variable > Default (1000ms)
        EnhancedConfigurationLoader.initialize(); // Ensure config is loaded
        String intervalStr = org.patch.config.YamlConfigLoader.getValue(
                "schedulers.settings.streaming.update-interval-ms",
                null);

        // Fallback to environment variable if YAML value is not found
        if (intervalStr == null || intervalStr.isEmpty() || intervalStr.equals("null")) {
            intervalStr = System.getenv("STREAMING_UPDATE_INTERVAL_MS");
        }

        // Fallback to default if still not found
        if (intervalStr == null || intervalStr.isEmpty()) {
            intervalStr = "1000";
        }

        // Parse interval value (with error handling)
        long intervalValue = 1000; // Default fallback
        try {
            intervalValue = Long.parseLong(intervalStr);
        } catch (NumberFormatException e) {
            logger.warning("Invalid streaming interval value: " + intervalStr +
                    ", using default: 1000ms");
        }
        this.streamingIntervalMs = intervalValue;
        // Convert milliseconds to simulation time (seconds)
        this.streamingIntervalSeconds = intervalValue / 1000.0;
        this.currentPollInterval = this.streamingIntervalSeconds; // Start with base interval
        
        // Calculate max polls based on simulation time (proportional to MAX_SIMULATION_TIME)
        double maxSimulationTime = Config.MAX_SIMULATION_TIME;
        // Dynamic calculation: maxPolls = (MAX_SIMULATION_TIME / streamingIntervalSeconds) * 2
        // Multiplier of 2 provides buffer for adaptive polling and edge cases
        // Minimum of 100 polls to ensure basic functionality
        this.maxPolls = (int) Math.max(100, maxSimulationTime / this.streamingIntervalSeconds * 2);
        
        logger.info("StreamingQueueObserver initialized for device: " + deviceId +
                " with update interval: " + streamingIntervalMs + "ms (" + streamingIntervalSeconds
                + "s simulation time), maxPolls=" + maxPolls + " (simulation time: " + maxSimulationTime + "s)");
    }

    /**
     * Set the device entity for event scheduling
     * Must be called by RLFogDevice before startStreaming()
     */
    public void setDeviceEntity(org.cloudbus.cloudsim.core.SimEntity deviceEntity) {
        this.deviceEntity = deviceEntity;
    }

    /**
     * Set the TaskExecutionEngine for checking active tasks
     * 
     * @param taskExecutionEngine The TaskExecutionEngine instance
     */
    public void setTaskExecutionEngine(org.patch.processing.TaskExecutionEngine taskExecutionEngine) {
        this.taskExecutionEngine = taskExecutionEngine;
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

            if (deviceEntity == null) {
                logger.severe("Cannot start streaming: device entity is null. Call setDeviceEntity() first.");
                return false;
            }

            // Check connection (non-blocking, will retry in first poll)
            if (!schedulerClient.isConnected()) {
                logger.warning("Scheduler client not connected, will attempt connection on first poll");
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error checking streaming prerequisites", e);
            return false;
        }

        shouldStop.set(false);
        isStreaming.set(true);

        if (CloudSim.running() || CloudSim.clock() > 0.0) {
            double currentTime = CloudSim.clock();
            scheduleNextQueueUpdate(currentTime);
        } else {
            scheduleNextQueueUpdate(0.1);
        }

        logger.info("Started streaming queue updates for device: " + deviceId +
                " (interval: " + streamingIntervalSeconds + "s)");
        return true;
    }

    /**
     * Schedule next queue update event using CloudSim with adaptive polling
     */
    private void scheduleNextQueueUpdate(double currentTime) {
        if (deviceEntity == null) {
            logger.severe("Cannot schedule queue update: device entity is null");
            return;
        }

        // Check poll limit
        if (pollCount >= maxPolls) {
            logger.warning(String.format(
                    "[POLL-LIMIT] Device %d - Poll limit reached (%d/%d), stopping polling",
                    deviceId, pollCount, maxPolls));
            shouldStop.set(true);
            isStreaming.set(false);
            return;
        }

        // Use adaptive poll interval (longer when queue is empty)
        double intervalToUse = currentPollInterval;
        double nextUpdateTime = currentTime + intervalToUse;
        double maxSimulationTime = Config.MAX_SIMULATION_TIME;

        // Don't schedule beyond MAX_SIMULATION_TIME
        if (nextUpdateTime >= maxSimulationTime) {
            logger.info("Not scheduling queue update beyond MAX_SIMULATION_TIME: " + maxSimulationTime);
            return;
        }

        try {
            org.cloudbus.cloudsim.core.CloudSim.send(
                    deviceEntity.getId(),
                    deviceEntity.getId(),
                    intervalToUse,
                    org.patch.utils.ExtendedFogEvents.STREAMING_QUEUE_UPDATE,
                    null);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to schedule queue update event", e);
        }
    }

    /**
     * Poll queue from scheduler (called from CloudSim event)
     */
    public void pollQueueFromScheduler() {
        if (!isStreaming.get() || shouldStop.get()) {
            return;
        }

        double currentTime = CloudSim.clock();
        double maxSimulationTime = Config.MAX_SIMULATION_TIME;

        // Stop if simulation has ended
        if (currentTime >= maxSimulationTime || !CloudSim.running()) {
            logger.info(String.format(
                    "Stopping queue polling for device %d - Simulation time %.2f >= MAX_SIMULATION_TIME %.2f or not running",
                    deviceId, currentTime, maxSimulationTime));
            shouldStop.set(true);
            isStreaming.set(false);
            return;
        }

        // Check poll limit before polling
        if (pollCount >= maxPolls) {
            logger.warning(String.format(
                    "[POLL-LIMIT] Device %d - Poll limit reached (%d/%d), stopping polling",
                    deviceId, pollCount, maxPolls));
            shouldStop.set(true);
            isStreaming.set(false);
            return;
        }

        // Adaptive polling: Use longer interval if too many consecutive empty polls, but STILL POLL
        // CRITICAL FIX: Don't skip polling entirely - we need to check if tasks were added later
        if (consecutiveEmptyPolls >= MAX_CONSECUTIVE_EMPTY_POLLS) {
            // Use longer interval when queue is consistently empty, but continue polling
            currentPollInterval = streamingIntervalSeconds * ADAPTIVE_POLL_INTERVAL_MULTIPLIER;
            logger.fine(String.format(
                    "[POLL-ADAPTIVE] Device %d - Using longer interval (consecutive empty=%d), interval=%.2fs, but still polling",
                    deviceId, consecutiveEmptyPolls, currentPollInterval));
            // Continue to poll below - don't return early!
        }

        // Increment poll count
        pollCount++;

        // Get current queue state from scheduler
        GetSortedQueueResponse response = getSortedQueueFromScheduler();

        if (response != null) {
            int taskCount = response.getQueueTasksCount();
            
            // Update adaptive polling based on queue state
            if (taskCount == 0) {
                consecutiveEmptyPolls++;
                // Increase poll interval when queue is empty
                currentPollInterval = streamingIntervalSeconds * ADAPTIVE_POLL_INTERVAL_MULTIPLIER;
                logger.fine(String.format(
                        "[POLL-ADAPTIVE] Device %d - Queue empty, consecutiveEmpty=%d, interval=%.2fs",
                        deviceId, consecutiveEmptyPolls, currentPollInterval));
            } else {
                // Reset to base interval when queue has tasks
                consecutiveEmptyPolls = 0;
                currentPollInterval = streamingIntervalSeconds;
                logger.info(String.format(
                        "[POLL-ADAPTIVE] Device %d - Queue has %d tasks, resetting consecutiveEmpty=0 and interval=%.2fs",
                        deviceId, taskCount, currentPollInterval));
            }
            
            processQueueUpdate(response);
        } else {
            // Treat null response as empty (conservative)
            consecutiveEmptyPolls++;
            currentPollInterval = streamingIntervalSeconds * ADAPTIVE_POLL_INTERVAL_MULTIPLIER;
        }

        if (isStreaming.get() && !shouldStop.get()) {
            scheduleNextQueueUpdate(currentTime);
        }
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

        logger.info("Stopped streaming queue updates for device: " + deviceId);
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
                // CRITICAL: Check if simulation should stop before making gRPC calls
                double currentTime = CloudSim.clock();
                double maxSimulationTime = Config.MAX_SIMULATION_TIME;
                if (currentTime >= maxSimulationTime || !CloudSim.running()) {
                    logger.warning(String.format(
                            "[SHUTDOWN] Stopping queue fetch for device %d - Simulation time %.2f >= MAX_SIMULATION_TIME %.2f or not running",
                            deviceId, currentTime, maxSimulationTime));
                    shouldStop.set(true);
                    return null;
                }

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

            } catch (io.grpc.StatusRuntimeException e) {
                // Handle gRPC-specific errors
                io.grpc.Status.Code statusCode = e.getStatus().getCode();
                if (statusCode == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                    logger.log(Level.WARNING, String.format(
                            "[TIMEOUT] GetSortedQueue request timed out for device %d (attempt %d/%d) - will retry",
                            deviceId, retries + 1, maxRetries));
                } else if (statusCode == io.grpc.Status.Code.UNAVAILABLE) {
                    logger.log(Level.WARNING, String.format(
                            "[UNAVAILABLE] Scheduler unavailable for device %d (attempt %d/%d) - will retry",
                            deviceId, retries + 1, maxRetries));
                } else {
                    logger.log(Level.WARNING, String.format(
                            "[ERROR] Failed to get sorted queue for device %d (attempt %d/%d): %s",
                            deviceId, retries + 1, maxRetries, e.getMessage()));
                }
                retries++;

                if (retries < maxRetries) {
                    try {
                        // Exponential backoff with jitter
                        long delay = Math.min(retryDelayMs * (long) Math.pow(2, retries), 30000); // Max 30 seconds
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warning("Retry delay interrupted");
                        break;
                    }
                } else {
                    logger.warning(String.format(
                            "[RETRY-EXHAUSTED] Max retries reached for device %d - will continue polling but may have stale queue",
                            deviceId));
                    // Continue - don't break the loop, just log and return null
                    // The next polling event will try again
                }
            } catch (Exception e) {
                // Handle non-gRPC exceptions
                logger.log(Level.WARNING, String.format(
                        "Failed to get sorted queue for device %d (attempt %d/%d): %s",
                        deviceId, retries + 1, maxRetries, e.getMessage()), e);
                retries++;
                if (retries < maxRetries) {
                    try {
                        long delay = Math.min(retryDelayMs * (long) Math.pow(2, retries), 30000);
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warning("Retry delay interrupted");
                        break;
                    }
                } else {
                    logger.warning(String.format(
                            "[RETRY-EXHAUSTED] Max retries reached for device %d",
                            deviceId));
                }
            }
        }

        logger.warning(String.format(
                "[QUEUE-FETCH-FAILED] Failed to get sorted queue after %d attempts for device %d - returning empty response, will retry on next poll",
                maxRetries, deviceId));
        // Return empty response instead of null to allow simulation to continue
        return GetSortedQueueResponse.newBuilder()
                .setNodeId(String.valueOf(deviceId))
                .setTotalTasks(0)
                .setTimestamp(System.currentTimeMillis() / 1000)
                .build();
    }

    /**
     * Process queue update from scheduler
     * 
     * @param response The response from scheduler
     */
    private void processQueueUpdate(GetSortedQueueResponse response) {
        try {
            int taskCount = response.getQueueTasksCount();

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

            // Log if queue went from empty to non-empty (tasks are ready to execute)
            if (oldSize == 0 && newSize > 0) {
                logger.info(String.format("Scheduled queue now has %d tasks - ready for execution", newSize));
            }

            logger.fine("Successfully processed queue update for device: " + deviceId);

            // Trigger callback if queue has tasks to trigger task execution
            if (queueUpdateCallback != null && !scheduledQueue.isEmpty()) {
                queueUpdateCallback.accept(scheduledQueue);
            }

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
            int addedCount = 0;
            int skippedCount = 0;
            for (Task task : response.getQueueTasksList()) {

                // Convert proto task to internal format
                ScheduledQueue.TaskInfo taskInfo = convertTaskToTaskInfo(task);

                if (taskInfo != null) {
                    // CRITICAL: Check if this cloudletId is already being processed
                    // This prevents re-adding tasks that are already executing
                    // (same cloudletId from server because task hasn't completed yet)
                    long cloudletId = taskInfo.getTuple().getCloudletId();
                    if (taskExecutionEngine != null && taskExecutionEngine.isCloudletIdActive(cloudletId)) {
                        skippedCount++;
                        logger.fine("Skipping task " + taskInfo.getTaskId() + " (cloudletId=" + cloudletId
                                + ") - already in activeTasks");
                        continue; // Skip this task - it's already being processed
                    }

                    scheduledQueue.addTask(taskInfo);
                    addedCount++;
                } else {
                    skippedCount++;
                    logger.warning("Failed to convert task " + task.getTaskId() + " to TaskInfo - SKIPPED");
                }
            }

            // Note: Two-stage removal is no longer needed
            // Tasks are removed from activeTasks immediately when ACK confirms success
            // This is handled in handleTupleComplete() and handleCachedTask()

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
                logger.warning("convertProtoTaskToTuple returned NULL for task: " + task.getTaskId());
                return null;
            }

            // Extract cache information from Task metadata
            boolean isCached = false;
            String cacheKey = "";
            CacheAction cacheAction = CacheAction.CACHE_ACTION_NONE; // Default to NONE

            if (task.getMetadataMap() != null) {
                String isCachedStr = task.getMetadataMap().get("is_cached");
                if (isCachedStr != null && isCachedStr.equals("true")) {
                    isCached = true;
                }
                cacheKey = task.getMetadataMap().getOrDefault("cache_key", "");

                // Extract cache action from metadata
                String cacheActionStr = task.getMetadataMap().getOrDefault("cache_action", "CACHE_ACTION_NONE");
                try {
                    // Convert string to CacheAction enum
                    cacheAction = CacheAction.valueOf(cacheActionStr);
                } catch (IllegalArgumentException e) {
                    // Fallback: try to parse common values
                    if (cacheActionStr.contains("STORE")) {
                        cacheAction = CacheAction.CACHE_ACTION_STORE;
                    } else if (cacheActionStr.contains("USE")) {
                        cacheAction = CacheAction.CACHE_ACTION_USE;
                    } else if (cacheActionStr.contains("INVALIDATE")) {
                        cacheAction = CacheAction.CACHE_ACTION_INVALIDATE;
                    } else {
                        cacheAction = CacheAction.CACHE_ACTION_NONE;
                    }
                    logger.warning("Failed to parse cache_action: " + cacheActionStr + ", using: " + cacheAction);
                }
            }

            // Create TaskInfo with cache information from metadata (including cacheAction)
            return new ScheduledQueue.TaskInfo(
                    tuple,
                    0, // moduleId - will be set by tuple processing
                    String.valueOf(deviceId), // assignedNodeId
                    (long) CloudSim.clock(), // estimatedStartTime - use simulation time
                    (long) (CloudSim.clock() + task.getExecutionTime()), // estimatedCompletionTime - use simulation
                                                                         // time
                    isCached, // isCached - from metadata
                    cacheKey, // cacheKey - from metadata
                    task.getTaskId(), // ✅ Use scheduler-assigned TaskId (reused pattern ID)
                    cacheAction // ✅ NEW: Cache action from metadata
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
