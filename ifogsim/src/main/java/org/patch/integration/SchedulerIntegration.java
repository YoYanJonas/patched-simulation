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
 * Scheduled queue updates are handled by StreamingQueueObserver via
 * GetSortedQueue
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
     * Blocks until all tasks are sent to scheduler (necessary for CloudSim
     * integration)
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
            System.out.println(String.format(
                    "[SCHEDULER-SEND] Device %d - Attempt %d: No tasks found after filtering (queue size: %d)",
                    deviceId, sendAttemptCount, queueSize));
            logger.fine("No tasks found to send (after filtering)");
            return;
        }

        // [DEBUG] Log sending tasks to scheduler
        double currentTime = CloudSim.clock();
        System.out.println(String.format(
                "[FLOW-FOG-SCHEDULER-SEND] Time: %.2f - FogNode (ID:%d) - Sending %d tasks to scheduler server (unscheduled queue size: %d, attempt: %d)",
                currentTime, deviceId, tasksToSend.size(), queueSize, sendAttemptCount));

        // Log every send attempt (first 20, then every 10th)
        if (sendAttemptCount <= 20 || sendAttemptCount % 10 == 0) {
            System.out.println(String.format(
                    "[SCHEDULER-SEND] Device %d - Attempt %d: Sending %d tasks to scheduler (queue size: %d)",
                    deviceId, sendAttemptCount, tasksToSend.size(), queueSize));
        }
        logger.info("Sending " + tasksToSend.size() + " tasks to scheduler");

        // [DEBUG] Log task details being sent
        for (UnscheduledQueue.TaskInfo taskInfo : tasksToSend) {
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND] Time: %.2f - FogNode (ID:%d) - Task details: ID=%d, CPU=%d, Mem=%d, Out=%d",
                    currentTime, deviceId, taskInfo.getTuple().getCloudletId(),
                    taskInfo.getTuple().getCloudletLength(), taskInfo.getTuple().getCloudletFileSize(),
                    taskInfo.getTuple().getCloudletOutputSize()));
        }

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

            // Calculate queue context from iFogSim queues
            int unscheduledSize = unscheduledQueue.size();
            int scheduledSize = scheduledQueue.size();
            int totalQueueSize = unscheduledSize + scheduledSize;

            // Create QueueContext proto
            QueueContext queueContext = QueueContext.newBuilder()
                    .setTotalQueueSize(totalQueueSize)
                    .build();

            if (sendAttemptCount <= 20 || sendAttemptCount % 10 == 0) {
                System.out.println(String.format(
                        "[SCHEDULER-SEND] Device %d - Attempt %d: Calling addTasksToQueue with %d tasks (queue_size=%d)",
                        deviceId, sendAttemptCount, protoTasks.size(), totalQueueSize));
            }
            logger.info("Calling schedulerClient.addTasksToQueue with " + protoTasks.size() + " tasks (queue_size="
                    + totalQueueSize + ")");

            // [DEBUG] Log before gRPC call - ENHANCED with proto details
            double sendTime = CloudSim.clock();
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND-START] Time: %.2f - FogNode (ID:%d) - PREPARING gRPC call to scheduler.addTasksToQueue",
                    sendTime, deviceId));
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND-DETAILS] Time: %.2f - FogNode (ID:%d) - Tasks=%d, QueueContext(total=%d, unscheduled=%d, scheduled=%d)",
                    sendTime, deviceId, protoTasks.size(), totalQueueSize, unscheduledSize, scheduledSize));
            
            // Log first 3 task IDs for tracing
            if (!protoTasks.isEmpty()) {
                StringBuilder taskIds = new StringBuilder();
                int maxTasks = Math.min(3, protoTasks.size());
                for (int i = 0; i < maxTasks; i++) {
                    if (i > 0) taskIds.append(",");
                    taskIds.append(protoTasks.get(i).getTaskId());
                }
                if (protoTasks.size() > 3) {
                    taskIds.append("... (+").append(protoTasks.size() - 3).append(" more)");
                }
                System.out.println(String.format(
                        "[FLOW-FOG-SCHEDULER-SEND-TASKIDS] Time: %.2f - FogNode (ID:%d) - Task IDs being sent: [%s]",
                        sendTime, deviceId, taskIds.toString()));
            }
            
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND-CALL] Time: %.2f - FogNode (ID:%d) - NOW CALLING scheduler.addTasksToQueue (BLOCKING gRPC call)",
                    sendTime, deviceId));

            // IMPORTANT: Store task mapping BEFORE removing from queue
            // This allows us to reconstruct TaskInfo for cached tasks
            Map<String, UnscheduledQueue.TaskInfo> taskInfoMap = new HashMap<>();
            for (UnscheduledQueue.TaskInfo taskInfo : tasksToSend) {
                String taskId = String.valueOf(taskInfo.getTuple().getCloudletId());
                taskInfoMap.put(taskId, taskInfo);
            }

            // IMPORTANT: Remove tasks from unscheduled queue BEFORE sending to avoid
            // resending
            // Tasks will be added to scheduled queue via streaming endpoint
            // (GetSortedQueue)
            // We remove them now so they don't get sent again
            List<String> taskIdsSent = new ArrayList<>();
            for (UnscheduledQueue.TaskInfo taskInfo : tasksToSend) {
                String taskId = String.valueOf(taskInfo.getTuple().getCloudletId());
                UnscheduledQueue.TaskInfo removedTask = unscheduledQueue.removeTask(taskId);
                if (removedTask != null) {
                    taskIdsSent.add(taskId);
                    System.out.println(String.format(
                            "[FLOW-FOG-SCHEDULER-SEND] Time: %.2f - FogNode (ID:%d) - Removed task %s from unscheduled queue BEFORE sending (unscheduled queue size: %d)",
                            CloudSim.clock(), deviceId, taskId, unscheduledQueue.size()));
                    logger.info("Removed task " + taskId + " from unscheduled queue before sending to scheduler");
                } else {
                    logger.warning("Task " + taskId + " not found in unscheduled queue before sending");
                }
            }

            // Send to scheduler gRPC server (BLOCKS until all responses received)
            List<AddTaskToQueueResponse> responses = schedulerClient.addTasksToQueue(
                    protoTasks, availableNodes, policy, queueContext);

            // [DEBUG] Log after gRPC call - ENHANCED
            double receiveTime = CloudSim.clock();
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND-RECEIVED] Time: %.2f - FogNode (ID:%d) - gRPC call COMPLETED - Received %d responses from scheduler",
                    receiveTime, deviceId, responses.size()));
            
            // Log response details for first 3 responses
            if (!responses.isEmpty()) {
                StringBuilder responseDetails = new StringBuilder();
                int maxResponses = Math.min(3, responses.size());
                for (int i = 0; i < maxResponses; i++) {
                    AddTaskToQueueResponse resp = responses.get(i);
                    if (i > 0) responseDetails.append(" | ");
                    responseDetails.append(String.format("TaskID=%s, Success=%s, Position=%d, Cached=%s",
                            resp.getTaskId(), resp.getSuccess(), resp.getQueuePosition(), resp.getIsCachedTask()));
                }
                if (responses.size() > 3) {
                    responseDetails.append(" ... (+").append(responses.size() - 3).append(" more)");
                }
                System.out.println(String.format(
                        "[FLOW-FOG-SCHEDULER-SEND-RESPONSE-DETAILS] Time: %.2f - FogNode (ID:%d) - Response details: [%s]",
                        receiveTime, deviceId, responseDetails.toString()));
            }
            
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND-NOTE] Time: %.2f - FogNode (ID:%d) - Tasks already removed from unscheduled queue. Scheduled queue will be updated via streaming endpoint (GetSortedQueue)",
                    receiveTime, deviceId));

            if (sendAttemptCount <= 20 || sendAttemptCount % 10 == 0) {
                System.out.println(
                        String.format("[SCHEDULER-SEND] Device %d - Attempt %d: Received %d responses from scheduler",
                                deviceId, sendAttemptCount, responses.size()));
            }
            logger.info("Received " + responses.size() + " responses from scheduler");

            // Process responses - tasks already removed from unscheduledQueue
            // Scheduled queue will be updated via StreamingQueueObserver (GetSortedQueue)
            // NOTE: Responses are informational only - scheduled queue comes from streaming
            // endpoint!
            processSchedulerResponses(responses, taskIdsSent, taskInfoMap);

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
        double currentTime = CloudSim.clock();

        System.out.println(String.format(
                "[FLOW-FOG-PROTO-CONVERT-START] Time: %.2f - FogNode (ID:%d) - Converting %d tasks to proto format",
                currentTime, deviceId, tasks.size()));

        for (int i = 0; i < tasks.size(); i++) {
            UnscheduledQueue.TaskInfo taskInfo = tasks.get(i);
            Tuple tuple = taskInfo.getTuple();

            // [DEBUG] Log tuple details before conversion
            System.out.println(String.format(
                    "[FLOW-FOG-PROTO-CONVERT-TUPLE] Time: %.2f - FogNode (ID:%d) - Task %d/%d: Tuple ID=%d, Type=%s, CPU=%d, Mem=%d",
                    currentTime, deviceId, i + 1, tasks.size(), tuple.getCloudletId(), tuple.getTupleType(),
                    tuple.getCloudletLength(), tuple.getCloudletFileSize()));

            // Map tuple type to TaskType (if needed, use helper)
            TaskType taskType = mapTupleTypeToTaskType(tuple.getTupleType());
            // Tuple doesn't have priority - use default (can be enhanced later if priority is stored elsewhere)
            int priority = 5; // Default priority

            Task protoTask = Task.newBuilder()
                    .setTaskId(String.valueOf(tuple.getCloudletId()))
                    .setTaskName(tuple.getTupleType())
                    .setTaskType(taskType)
                    .setCpuRequirement(tuple.getCloudletLength())
                    .setMemoryRequirement(tuple.getCloudletFileSize())
                    .setExecutionTime(tuple.getCloudletLength())
                    .setPriority(priority) // Default priority (Tuple doesn't have priority field)
                    .setDeadline(System.currentTimeMillis() + 300000) // 5 minutes deadline
                    .build(); // Metadata and dependencies would need to be added via TupleFactory if available

            protoTasks.add(protoTask);

            // [DEBUG] Log proto task creation
            System.out.println(String.format(
                    "[FLOW-FOG-PROTO-CONVERT-SUCCESS] Time: %.2f - FogNode (ID:%d) - Created proto task: TaskID=%s, Type=%s, CPU=%d, Mem=%d, Priority=%d, MetadataSize=%d",
                    currentTime, deviceId, protoTask.getTaskId(), protoTask.getTaskType().toString(),
                    protoTask.getCpuRequirement(), protoTask.getMemoryRequirement(), protoTask.getPriority(),
                    protoTask.getMetadataMap() != null ? protoTask.getMetadataMap().size() : 0));
        }

        System.out.println(String.format(
                "[FLOW-FOG-PROTO-CONVERT-COMPLETE] Time: %.2f - FogNode (ID:%d) - Converted %d tasks to proto format",
                currentTime, deviceId, protoTasks.size()));

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
     * Process scheduler responses - tasks already removed from unscheduledQueue
     * Scheduled queue updates are handled by StreamingQueueObserver via
     * GetSortedQueue
     * Cache actions and execution decisions are handled by the scheduler and
     * reflected in the streamed queue
     * 
     * @param responses   The responses from scheduler
     * @param taskIdsSent The task IDs that were sent (already removed from
     *                    unscheduled queue)
     * @param taskInfoMap Map of taskId to original TaskInfo for cached task
     *                    handling
     */
    private void processSchedulerResponses(List<AddTaskToQueueResponse> responses, List<String> taskIdsSent,
            Map<String, UnscheduledQueue.TaskInfo> taskInfoMap) {
        double currentTime = CloudSim.clock();
        logger.info("Processing scheduler responses for " + responses.size()
                + " tasks (tasks already removed from unscheduled queue)");

        // [DEBUG] Log all responses
        System.out.println(String.format(
                "[FLOW-SCHEDULER-RESPONSE-PROCESS] Time: %.2f - FogNode (ID:%d) - Processing %d scheduler responses",
                currentTime, deviceId, responses.size()));

        for (AddTaskToQueueResponse taskResponse : responses) {
            String taskId = taskResponse.getTaskId();
            boolean isCached = taskResponse.getIsCachedTask();
            CacheAction cacheAction = taskResponse.getCacheAction();

            // [DEBUG] Log response details
            System.out.println(String.format(
                    "[FLOW-SCHEDULER-RESPONSE-DETAIL] Time: %.2f - FogNode (ID:%d) - Task %s: Success=%s, Cached=%s, Action=%s, Position=%d",
                    currentTime, deviceId, taskId, taskResponse.getSuccess(), isCached,
                    cacheAction != null ? cacheAction.toString() : "NONE", taskResponse.getQueuePosition()));

            if (taskResponse.getSuccess()) {
                // Task successfully sent to scheduler - already removed from unscheduled queue
                if (taskIdsSent.contains(taskId)) {
                    logger.info("Task " + taskId
                            + " successfully sent to scheduler (already removed from unscheduled queue)");

                    // All tasks (cached or not) go through the queue via streaming endpoint
                    // Cache decision is made during execution in TaskExecutionEngine
                    if (isCached && cacheAction == CacheAction.CACHE_ACTION_USE) {
                        System.out.println(String.format(
                                "[FLOW-SCHEDULER-RESPONSE] Time: %.2f - FogNode (ID:%d) - Task %s is CACHED - Will appear in scheduled queue via streaming endpoint (cache will be handled during execution)",
                                currentTime, deviceId, taskId));
                        logger.info("Task " + taskId + " is cached - will be handled during execution");
                    } else {
                        System.out.println(String.format(
                                "[FLOW-SCHEDULER-RESPONSE] Time: %.2f - FogNode (ID:%d) - Task %s is NOT cached - Will appear in scheduled queue via streaming endpoint",
                                currentTime, deviceId, taskId));
                        logger.fine("Task " + taskId + " will be queued for execution");
                    }

                    // Log cache information for debugging (if available)
                    if (cacheAction != null && cacheAction != CacheAction.CACHE_ACTION_UNSPECIFIED) {
                        logger.fine("Task " + taskId + " cache action: " + cacheAction);
                    }
                } else {
                    logger.warning("Task " + taskId + " response received but was not in sent list");
                }
            } else {
                logger.warning("Scheduler failed for task " + taskId +
                        ": " + taskResponse.getErrorMessage());
                // Task already removed - cannot retry (would need to re-add to unscheduled
                // queue)
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
        logger.warning(
                "Fallback: Scheduler unavailable, moving " + tasks.size() + " tasks directly to scheduled queue");

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

    /**
     * Map tuple type name to TaskType enum
     * Attempts to infer task type from tuple type name
     * 
     * @param tupleType The tuple type string
     * @return TaskType enum
     */
    private TaskType mapTupleTypeToTaskType(String tupleType) {
        if (tupleType == null || tupleType.isEmpty()) {
            return TaskType.TASK_TYPE_COMPUTE; // Default
        }

        String lowerType = tupleType.toLowerCase();

        // Try to infer from tuple type name
        if (lowerType.contains("io") || lowerType.contains("storage") || lowerType.contains("disk")) {
            return TaskType.TASK_TYPE_IO;
        } else if (lowerType.contains("network") || lowerType.contains("communication")
                || lowerType.contains("transmit")) {
            return TaskType.TASK_TYPE_NETWORK;
        } else if (lowerType.contains("mixed") || lowerType.contains("hybrid")) {
            return TaskType.TASK_TYPE_MIXED;
        } else {
            // Default to COMPUTE for most tasks (sensor data, compute tasks, etc.)
            return TaskType.TASK_TYPE_COMPUTE;
        }
    }
}
