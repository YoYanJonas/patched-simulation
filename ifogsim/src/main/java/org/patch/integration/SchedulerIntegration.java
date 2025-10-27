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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Handles integration between fog nodes and scheduler gRPC server
 * Manages non-blocking communication and queue updates
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
     * Send tasks to scheduler gRPC server (non-blocking)
     * This method should be called when tasks are added to unscheduled queue
     */
    public void sendTasksToScheduler() {
        if (unscheduledQueue.isEmpty()) {
            return;
        }

        // Get tasks to send (limit batch size)
        List<UnscheduledQueue.TaskInfo> tasksToSend = getTasksForScheduler();
        if (tasksToSend.isEmpty()) {
            return;
        }

        logger.info("Sending " + tasksToSend.size() + " tasks to scheduler");

        // Send asynchronously to avoid blocking simulation
        CompletableFuture.runAsync(() -> {
            try {
                // Convert tasks to proto format
                List<Task> protoTasks = convertTasksToProto(tasksToSend);
                List<FogNode> availableNodes = getCurrentFogNodeState();
                SchedulingPolicy policy = createSchedulingPolicy();

                // Send to scheduler gRPC server
                List<AddTaskToQueueResponse> responses = schedulerClient.addTasksToQueue(
                        protoTasks, availableNodes, policy);

                // Process response and update scheduled queue
                processSchedulerResponses(responses);

            } catch (Exception e) {
                logger.severe("Failed to send tasks to scheduler: " + e.getMessage());
                // Fallback: move tasks to scheduled queue without scheduler decision
                fallbackToScheduledQueue(tasksToSend);
            }
        });
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
     * Process scheduler responses and update scheduled queue
     * 
     * @param responses The responses from scheduler
     */
    private void processSchedulerResponses(List<AddTaskToQueueResponse> responses) {
        logger.info("Processing scheduler responses for " + responses.size() + " tasks");

        for (AddTaskToQueueResponse taskResponse : responses) {
            if (taskResponse.getSuccess()) {
                // Remove from unscheduled queue
                UnscheduledQueue.TaskInfo unscheduledTask = unscheduledQueue.removeTask(taskResponse.getTaskId());

                if (unscheduledTask != null) {
                    // Check if task is cached
                    if (taskResponse.getIsCachedTask()) {
                        // Task is cached - skip processing
                        logger.info("Task " + taskResponse.getTaskId() + " served from cache");
                        // Mark as completed immediately
                        markTaskCompleted(unscheduledTask);
                    } else {
                        // Task needs processing - add to scheduled queue
                        ScheduledQueue.TaskInfo scheduledTask = new ScheduledQueue.TaskInfo(
                                unscheduledTask.getTuple(),
                                unscheduledTask.getModuleId(),
                                CloudSim.clock(),
                                "fallback-node", // No assigned node in AddTaskToQueueResponse
                                (long) (CloudSim.clock() + taskResponse.getEstimatedWaitTimeMs()),
                                (long) (CloudSim.clock() + taskResponse.getEstimatedWaitTimeMs() + 1000), // Default
                                                                                                          // execution
                                                                                                          // time
                                taskResponse.getIsCachedTask(),
                                taskResponse.getCacheKey());

                        scheduledQueue.addTask(scheduledTask);
                        logger.info("Task " + taskResponse.getTaskId() + " added to scheduled queue");
                    }
                }
            } else {
                logger.warning("Scheduler failed for task " + taskResponse.getTaskId() +
                        ": " + taskResponse.getErrorMessage());
            }
        }
    }

    /**
     * Fallback method when scheduler is unavailable
     * 
     * @param tasks Tasks to move to scheduled queue
     */
    private void fallbackToScheduledQueue(List<UnscheduledQueue.TaskInfo> tasks) {
        logger.info("Fallback: Moving " + tasks.size() + " tasks to scheduled queue");

        for (UnscheduledQueue.TaskInfo taskInfo : tasks) {
            // Remove from unscheduled queue
            unscheduledQueue.removeTask(taskInfo.getTaskId());

            // Add to scheduled queue with default values
            ScheduledQueue.TaskInfo scheduledTask = new ScheduledQueue.TaskInfo(
                    taskInfo.getTuple(),
                    taskInfo.getModuleId(),
                    CloudSim.clock(),
                    String.valueOf(deviceId), // Assign to this device
                    System.currentTimeMillis(),
                    System.currentTimeMillis() + 1000, // 1 second execution time
                    false, // Not cached
                    null // No cache key
            );

            scheduledQueue.addTask(scheduledTask);
        }
    }

    /**
     * Mark a task as completed
     * 
     * @param taskInfo The task to mark as completed
     */
    private void markTaskCompleted(UnscheduledQueue.TaskInfo taskInfo) {
        // This would typically involve updating task status and metrics
        logger.info("Task " + taskInfo.getTaskId() + " marked as completed");
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
