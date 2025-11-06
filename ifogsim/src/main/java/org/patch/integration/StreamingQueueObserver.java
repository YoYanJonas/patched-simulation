package org.patch.integration;

import org.patch.client.SchedulerClient;
import org.patch.models.ScheduledQueue;
import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;
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

    // Callback for queue updates
    private Consumer<ScheduledQueue> queueUpdateCallback;
    
    // Reference to RLFogDevice for event scheduling (will be set by RLFogDevice)
    private org.cloudbus.cloudsim.core.SimEntity deviceEntity;

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

        // Load streaming interval from YAML config (with fallback to env var and default)
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
        logger.info("StreamingQueueObserver initialized for device: " + deviceId + 
                " with update interval: " + streamingIntervalMs + "ms (" + streamingIntervalSeconds + "s simulation time)");
    }
    
    /**
     * Set the device entity for event scheduling
     * Must be called by RLFogDevice before startStreaming()
     */
    public void setDeviceEntity(org.cloudbus.cloudsim.core.SimEntity deviceEntity) {
        this.deviceEntity = deviceEntity;
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
        System.out.println(String.format(
                "[FLOW-STREAMING-START] Device %d - Streaming started (interval=%.2fs)",
                deviceId, streamingIntervalSeconds));
        return true;
    }
    
    /**
     * Schedule next queue update event using CloudSim
     */
    private void scheduleNextQueueUpdate(double currentTime) {
        if (deviceEntity == null) {
            logger.severe("Cannot schedule queue update: device entity is null");
            return;
        }
        
        double nextUpdateTime = currentTime + streamingIntervalSeconds;
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
                streamingIntervalSeconds,
                org.patch.utils.ExtendedFogEvents.STREAMING_QUEUE_UPDATE,
                null);
            System.out.println(String.format(
                    "[FLOW-STREAMING-SCHEDULE] Device %d - Scheduled next queue update at time %.2f (current=%.2f, interval=%.2fs)",
                    deviceId, nextUpdateTime, currentTime, streamingIntervalSeconds));
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
        
        System.out.println(String.format(
                "[FLOW-STREAMING-POLL] Device %d - Polling queue from scheduler (time=%.2f)",
                deviceId, currentTime));
        
        // Get current queue state from scheduler
        GetSortedQueueResponse response = getSortedQueueFromScheduler();

        if (response != null) {
            System.out.println(String.format(
                    "[FLOW-FOG-STREAMING-RECEIVE] Time: %.2f - FogNode (ID:%d) - Received queue update from scheduler (tasks in response: %d)",
                    CloudSim.clock(), deviceId, response.getQueueTasksCount()));
            processQueueUpdate(response);
        } else {
            System.err.println(String.format(
                    "[FLOW-FOG-STREAMING-RECEIVE] Time: %.2f - FogNode (ID:%d) - ERROR: getSortedQueueFromScheduler returned NULL!",
                    CloudSim.clock(), deviceId));
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
        System.out.println(String.format(
                "[FLOW-STREAMING-STOP] Device %d - Streaming stopped (time=%.2f)",
                deviceId, CloudSim.clock()));
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
                double requestTime = CloudSim.clock();
                System.out.println(String.format(
                        "[FLOW-STREAMING-QUEUE-REQUEST-START] Time: %.2f - FogNode (ID:%d) - Requesting sorted queue from scheduler (GetSortedQueue)",
                        requestTime, deviceId));
                logger.info(String.format("[IFOGSIM-QUEUE-GET] Device %d requesting sorted queue from scheduler",
                        deviceId));
                
                GetSortedQueueResponse response = schedulerClient.getSortedQueue(String.valueOf(deviceId));
                double receiveTime = CloudSim.clock();

                if (response != null) {
                    System.out.println(String.format(
                            "[FLOW-STREAMING-QUEUE-RESPONSE-RECEIVED] Time: %.2f - FogNode (ID:%d) - Received queue response: Tasks=%d, TotalTasks=%d, NodeID=%s, Timestamp=%d",
                            receiveTime, deviceId, response.getQueueTasksCount(), response.getTotalTasks(), 
                            response.getNodeId(), response.getTimestamp()));
                    logger.info(String.format("[IFOGSIM-QUEUE-RESP] Device %d received queue: Tasks=%d",
                            deviceId, response.getQueueTasksCount()));
                    
                    // Log first 3 task IDs for tracing
                    if (response.getQueueTasksCount() > 0) {
                        StringBuilder taskIds = new StringBuilder();
                        int maxTasks = Math.min(3, response.getQueueTasksCount());
                        for (int i = 0; i < maxTasks && i < response.getQueueTasksList().size(); i++) {
                            if (i > 0) taskIds.append(",");
                            taskIds.append(response.getQueueTasksList().get(i).getTaskId());
                        }
                        if (response.getQueueTasksCount() > 3) {
                            taskIds.append("... (+").append(response.getQueueTasksCount() - 3).append(" more)");
                        }
                        System.out.println(String.format(
                                "[FLOW-STREAMING-QUEUE-TASKIDS] Time: %.2f - FogNode (ID:%d) - Task IDs in response: [%s]",
                                receiveTime, deviceId, taskIds.toString()));
                    }
                    
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
                    "[FLOW-FOG-SCHEDULED-QUEUE-RECEIVE-START] Time: %.2f - FogNode (ID:%d) - Processing queue update from scheduler: Tasks=%d, TotalTasks=%d, NodeID=%s, Timestamp=%d",
                    currentTime, deviceId, taskCount, response.getTotalTasks(), nodeId, response.getTimestamp()));

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
                
                // [DEBUG] CRITICAL: Check if callback will trigger execution
                if (queueUpdateCallback != null) {
                    System.out.println(String.format(
                            "[FLOW-FOG-SCHEDULED-QUEUE-CALLBACK-READY] Time: %.2f - FogNode (ID:%d) - Queue callback IS SET - Will trigger execution",
                            CloudSim.clock(), deviceId));
                } else {
                    System.err.println(String.format(
                            "[FLOW-FOG-SCHEDULED-QUEUE-CALLBACK-MISSING] Time: %.2f - FogNode (ID:%d) - ERROR: Queue callback is NULL! Execution will NOT be triggered!",
                            CloudSim.clock(), deviceId));
                }
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
            int addedCount = 0;
            int skippedCount = 0;
            for (Task task : response.getQueueTasksList()) {
                // Convert proto task to internal format
                ScheduledQueue.TaskInfo taskInfo = convertTaskToTaskInfo(task);

                if (taskInfo != null) {
                    scheduledQueue.addTask(taskInfo);
                    addedCount++;
                    
                    // [DEBUG] Log each task being added
                    System.out.println(String.format(
                            "[FLOW-FOG-SCHEDULED-QUEUE-ADD] Time: %.2f - FogNode (ID:%d) - Adding task %s to scheduled queue (task %d/%d, queue size now: %d)",
                            CloudSim.clock(), deviceId, taskInfo.getTaskId(), addedCount, response.getQueueTasksCount(), scheduledQueue.size()));
                } else {
                    skippedCount++;
                    System.err.println(String.format(
                            "[FLOW-FOG-SCHEDULED-QUEUE-ERROR] Time: %.2f - FogNode (ID:%d) - Failed to convert task %s to TaskInfo - SKIPPED",
                            CloudSim.clock(), deviceId, task.getTaskId()));
                }
            }

            logger.fine("Updated scheduled queue with " + response.getQueueTasksCount() +
                    " tasks for device: " + deviceId);
            
            // [DEBUG] Log summary of update
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULED-QUEUE-UPDATE-SUMMARY] Time: %.2f - FogNode (ID:%d) - Queue update: added=%d, skipped=%d, total_in_response=%d, queue_size_now=%d",
                    CloudSim.clock(), deviceId, addedCount, skippedCount, response.getQueueTasksCount(), scheduledQueue.size()));

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
            // [DEBUG] Log conversion attempt
            System.out.println(String.format(
                    "[FLOW-QUEUE-OBSERVER-CONVERT] Time: %.2f - FogNode (ID:%d) - Converting task %s to TaskInfo (CPU=%d, Mem=%d)",
                    CloudSim.clock(), deviceId, task.getTaskId(), task.getCpuRequirement(), task.getMemoryRequirement()));
            
            // Convert proto task to tuple
            Tuple tuple = convertProtoTaskToTuple(task);

            if (tuple == null) {
                System.err.println(String.format(
                        "[FLOW-QUEUE-OBSERVER-CONVERT-ERROR] Time: %.2f - FogNode (ID:%d) - ERROR: convertProtoTaskToTuple returned NULL for task %s!",
                        CloudSim.clock(), deviceId, task.getTaskId()));
                return null;
            }
            
            // [DEBUG] Log successful tuple conversion
            System.out.println(String.format(
                    "[FLOW-QUEUE-OBSERVER-CONVERT] Time: %.2f - FogNode (ID:%d) - Successfully converted task %s to tuple (tuple ID: %d)",
                    CloudSim.clock(), deviceId, task.getTaskId(), tuple.getCloudletId()));

            // Extract cache information from Task metadata
            boolean isCached = false;
            String cacheKey = "";
            
            if (task.getMetadataMap() != null) {
                String isCachedStr = task.getMetadataMap().get("is_cached");
                if (isCachedStr != null && isCachedStr.equals("true")) {
                    isCached = true;
                }
                cacheKey = task.getMetadataMap().getOrDefault("cache_key", "");
            }
            
            // [DEBUG] Log cache info extraction
            if (isCached) {
                System.out.println(String.format(
                        "[FLOW-QUEUE-OBSERVER] Time: %.2f - FogNode (ID:%d) - Task %s has cache info: isCached=true, cacheKey=%s",
                        CloudSim.clock(), deviceId, task.getTaskId(), cacheKey));
            }

            // Create TaskInfo with cache information from metadata
            return new ScheduledQueue.TaskInfo(
                    tuple,
                    0, // moduleId - will be set by tuple processing
                    String.valueOf(deviceId), // assignedNodeId
                    (long) CloudSim.clock(), // estimatedStartTime - use simulation time
                    (long) (CloudSim.clock() + task.getExecutionTime()), // estimatedCompletionTime - use simulation
                                                                         // time
                    isCached, // isCached - from metadata
                    cacheKey  // cacheKey - from metadata
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
