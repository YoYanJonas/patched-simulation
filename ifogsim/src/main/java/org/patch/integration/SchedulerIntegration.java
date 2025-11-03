package org.patch.integration;

import org.patch.client.SchedulerClient;
import org.patch.models.UnscheduledQueue;
import org.patch.models.ScheduledQueue;
import org.patch.utils.TaskCacheManager;
import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;
import org.fog.entities.Tuple;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.*;
import java.util.logging.Logger;

/**
 * Handles integration between fog nodes and scheduler gRPC server
 * Manages synchronous communication for task submission
 * Scheduled queue updates are handled by StreamingQueueObserver via GetSortedQueue
 */
public class SchedulerIntegration {
    private static final Logger logger = Logger.getLogger(SchedulerIntegration.class.getName());

    private final SchedulerClient schedulerClient;
    private final UnscheduledQueue unscheduledQueue;
    private final ScheduledQueue scheduledQueue;
    private final TaskCacheManager cacheManager;
    private final int deviceId;

    // Configuration
    private final int maxBatchSize;

    // Debug counter for tracking send attempts
    private int sendAttemptCount = 0;

    /**
     * Constructor
     * 
     * @param schedulerClient  The gRPC client for scheduler communication
     * @param unscheduledQueue The unscheduled task queue
     * @param scheduledQueue   The scheduled task queue
     * @param cacheManager     The cache manager
     * @param deviceId         The device ID for event scheduling
     */
    public SchedulerIntegration(SchedulerClient schedulerClient,
            UnscheduledQueue unscheduledQueue,
            ScheduledQueue scheduledQueue,
            TaskCacheManager cacheManager,
            int deviceId) {
        this.schedulerClient = schedulerClient;
        this.unscheduledQueue = unscheduledQueue;
        this.scheduledQueue = scheduledQueue;
        this.cacheManager = cacheManager;
        this.deviceId = deviceId;

        // Configuration
        this.maxBatchSize = 10; // Maximum 10 tasks per batch
    }

    /**
     * Send tasks to scheduler gRPC server (synchronous)
     * This method should be called when tasks are added to unscheduled queue
     * Blocks until all tasks are sent to scheduler (necessary for CloudSim integration)
     */
    public void sendTasksToScheduler() {
        sendAttemptCount++;

        // Check scheduler client availability
        if (schedulerClient == null) {
            System.out.println(String.format("[SCHEDULER-SEND] Device %d - Attempt %d: Scheduler client is NULL",
                    deviceId, sendAttemptCount));
            logger.severe("Scheduler client is null - cannot send tasks to scheduler");
            return;
        }

        int queueSize = unscheduledQueue.size();
        if (queueSize == 0) {
            // Only log first few empty queue cases to avoid log bloat
            if (sendAttemptCount <= 5) {
                System.out.println(String.format("[SCHEDULER-SEND] Device %d - Attempt %d: Unscheduled queue is empty",
                        deviceId, sendAttemptCount));
            }
            logger.fine("Unscheduled queue is empty - nothing to send");
            return;
        }

        // Get tasks to send (limit batch size)
        List<UnscheduledQueue.TaskInfo> tasksToSend = getTasksForScheduler();
        if (tasksToSend.isEmpty()) {
            System.out.println(String.format("[SCHEDULER-SEND] Device %d - Attempt %d: No tasks found after filtering (queue size: %d)",
                    deviceId, sendAttemptCount, queueSize));
            logger.fine("No tasks found to send (after filtering)");
            return;
        }

        // Log every send attempt (first 20, then every 10th)
        if (sendAttemptCount <= 20 || sendAttemptCount % 10 == 0) {
            System.out.println(String.format("[SCHEDULER-SEND] Device %d - Attempt %d: Sending %d tasks to scheduler (queue size: %d)",
                    deviceId, sendAttemptCount, tasksToSend.size(), queueSize));
        }
        logger.info("Sending " + tasksToSend.size() + " tasks to scheduler");

        // Check if scheduler service is available
        if (!schedulerClient.isConnected()) {
            System.out.println(String.format("[SCHEDULER-SEND] Device %d - Attempt %d: Scheduler client NOT CONNECTED",
                    deviceId, sendAttemptCount));
            logger.warning("Scheduler client not connected - cannot send tasks");
            fallbackToScheduledQueue(tasksToSend);
            return;
        }

        // SYNCHRONOUS: Block here until gRPC calls complete
        // This is necessary for CloudSim to properly integrate with the scheduler
        try {
            // Convert tasks to proto format
            List<Task> protoTasks = convertTasksToProto(tasksToSend);
            List<FogNode> availableNodes = getCurrentFogNodeState();
            SchedulingPolicy policy = createSchedulingPolicy();

            if (sendAttemptCount <= 20 || sendAttemptCount % 10 == 0) {
                System.out.println(String.format("[SCHEDULER-SEND] Device %d - Attempt %d: Calling addTasksToQueue with %d tasks",
                        deviceId, sendAttemptCount, protoTasks.size()));
            }
            logger.info("Calling schedulerClient.addTasksToQueue with " + protoTasks.size() + " tasks");

            // Send to scheduler gRPC server (BLOCKS until all responses received)
            List<AddTaskToQueueResponse> responses = schedulerClient.addTasksToQueue(
                    protoTasks, availableNodes, policy);

            if (sendAttemptCount <= 20 || sendAttemptCount % 10 == 0) {
                System.out.println(String.format("[SCHEDULER-SEND] Device %d - Attempt %d: Received %d responses from scheduler",
                        deviceId, sendAttemptCount, responses.size()));
            }
            logger.info("Received " + responses.size() + " responses from scheduler");

            // Process responses - only remove from unscheduledQueue on success
            // Streaming will update scheduledQueue via GetSortedQueue
            processSchedulerResponses(responses);

        } catch (Exception e) {
            System.out.println(String.format("[SCHEDULER-SEND] Device %d - Attempt %d: EXCEPTION - %s",
                    deviceId, sendAttemptCount, e.getMessage()));
            logger.severe("Failed to send tasks to scheduler: " + e.getMessage());
            e.printStackTrace();
            // Fallback: move tasks to scheduled queue without scheduler decision
            fallbackToScheduledQueue(tasksToSend);
        }
    }

    /**
     * Get tasks from unscheduled queue for scheduler
     * 
     * @return List of tasks to send to scheduler
     */
    private List<UnscheduledQueue.TaskInfo> getTasksForScheduler() {
        List<UnscheduledQueue.TaskInfo> allTasks = unscheduledQueue.getAllTasks();
        int batchSize = Math.min(allTasks.size(), maxBatchSize);
        return allTasks.subList(0, batchSize);
    }

    /**
     * Convert tasks to proto format
     * 
     * @param tasks List of tasks to convert
     * @return List of proto tasks
     */
    private List<Task> convertTasksToProto(List<UnscheduledQueue.TaskInfo> tasks) {
        List<Task> protoTasks = new ArrayList<>();

        for (UnscheduledQueue.TaskInfo taskInfo : tasks) {
            Tuple tuple = taskInfo.getTuple();

            Task protoTask = Task.newBuilder()
                    .setTaskId(String.valueOf(tuple.getCloudletId()))
                    .setTaskName(tuple.getTupleType())
                    .setTaskType(TaskType.TASK_TYPE_COMPUTE)
                    .setCpuRequirement(tuple.getCloudletLength())
                    .setMemoryRequirement(tuple.getCloudletFileSize())
                    .setExecutionTime(tuple.getCloudletLength())
                    .setPriority(5) // Default priority
                    .setDeadline(System.currentTimeMillis() + 300000) // 5 minutes deadline
                    .build();

            protoTasks.add(protoTask);
        }

        return protoTasks;
    }

    /**
     * Get current fog node state for scheduler
     * 
     * @return List of available fog nodes
     */
    private List<FogNode> getCurrentFogNodeState() {
        // Create a single fog node representing this device
        FogNode fogNode = FogNode.newBuilder()
                .setNodeId(String.valueOf(deviceId))
                .setNodeName("FogNode_" + deviceId)
                .setStatus(NodeStatus.NODE_STATUS_ACTIVE)
                .setCapacity(ResourceCapacity.newBuilder()
                        .setCpuCores(4)
                        .setMemoryMb(8192)
                        .setStorageGb(100)
                        .setNetworkBandwidthMbps(1000)
                        .build())
                .setCurrentUsage(ResourceUsage.newBuilder()
                        .setCpuUsage(50) // 50% CPU usage
                        .setMemoryUsageMb(4096) // 4GB memory usage
                        .setStorageUsageGb(50) // 50GB storage usage
                        .setNetworkUsageMbps(500) // 500Mbps network usage
                        .build())
                .setLocation(Location.newBuilder()
                        .setLatitude(0.0)
                        .setLongitude(0.0)
                        .setRegion("default")
                        .build())
                .build();

        return Collections.singletonList(fogNode);
    }

    /**
     * Create scheduling policy
     * 
     * @return Scheduling policy proto
     */
    private SchedulingPolicy createSchedulingPolicy() {
        return SchedulingPolicy.newBuilder()
                .setAlgorithm(SchedulingAlgorithm.SCHEDULING_ALGORITHM_FIFO)
                .setObjective(ObjectiveFunction.OBJECTIVE_FUNCTION_BALANCE_LOAD)
                .build();
    }

    /**
     * Process scheduler responses - only removes tasks from unscheduledQueue on success
     * Scheduled queue updates are handled by StreamingQueueObserver via GetSortedQueue
     * Cache actions and execution decisions are handled by the scheduler and reflected in the streamed queue
     * 
     * @param responses The responses from scheduler
     */
    private void processSchedulerResponses(List<AddTaskToQueueResponse> responses) {
        logger.info("Processing scheduler responses for " + responses.size() + " tasks");

        for (AddTaskToQueueResponse taskResponse : responses) {
            if (taskResponse.getSuccess()) {
                // Task successfully sent to scheduler - remove from unscheduled queue
                // The scheduler now owns this task and will include it in GetSortedQueue responses
                // StreamingQueueObserver will update scheduledQueue based on scheduler's decisions
                UnscheduledQueue.TaskInfo removedTask = unscheduledQueue.removeTask(taskResponse.getTaskId());

                if (removedTask != null) {
                    logger.info("Task " + taskResponse.getTaskId() + " successfully sent to scheduler, removed from unscheduled queue");

                    // Log cache information for debugging (if available)
                    CacheAction cacheAction = taskResponse.getCacheAction();
                    if (cacheAction != null && cacheAction != CacheAction.CACHE_ACTION_UNSPECIFIED) {
                        logger.fine("Task " + taskResponse.getTaskId() + " cache action: " + cacheAction);
                    }
                } else {
                    logger.warning("Task " + taskResponse.getTaskId() + " was sent to scheduler but not found in unscheduled queue");
                }
            } else {
                logger.warning("Scheduler failed for task " + taskResponse.getTaskId() +
                        ": " + taskResponse.getErrorMessage());
                // Task remains in unscheduled queue for retry or fallback
            }
        }
    }

    /**
     * Fallback method when scheduler is unavailable
     * Only used when scheduler service cannot be reached
     * 
     * @param tasks Tasks to move to scheduled queue
     */
    private void fallbackToScheduledQueue(List<UnscheduledQueue.TaskInfo> tasks) {
        logger.warning("Fallback: Scheduler unavailable, moving " + tasks.size() + " tasks directly to scheduled queue");

        for (UnscheduledQueue.TaskInfo taskInfo : tasks) {
            // Remove from unscheduled queue
            unscheduledQueue.removeTask(taskInfo.getTaskId());

            // Add to scheduled queue with default values (fallback behavior)
            ScheduledQueue.TaskInfo scheduledTask = new ScheduledQueue.TaskInfo(
                    taskInfo.getTuple(),
                    taskInfo.getModuleId(),
                    CloudSim.clock(),
                    String.valueOf(deviceId), // Assign to this device
                    (long) CloudSim.clock(),
                    (long) (CloudSim.clock() + 1000), // 1 second execution time in simulation time
                    false, // Not cached
                    null // No cache key
            );

            scheduledQueue.addTask(scheduledTask);
        }
    }

    /**
     * Get integration statistics
     * 
     * @return Map of integration statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("unscheduledQueueSize", unscheduledQueue.size());
        stats.put("scheduledQueueSize", scheduledQueue.size());
        stats.put("cacheStats", cacheManager.getCacheStats());
        return stats;
    }
}
