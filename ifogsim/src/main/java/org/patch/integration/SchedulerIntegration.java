package org.patch.integration;

import org.patch.client.SchedulerClient;
import org.patch.models.UnscheduledQueue;
import org.patch.models.ScheduledQueue;
import org.patch.utils.TaskCacheManager;
import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;
import org.fog.entities.Tuple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.config.EnhancedConfigurationLoader;

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
    // Optional reference to RLFogDevice for storing pending async requests
    private org.patch.devices.RLFogDevice rlFogDevice;

    // Configuration
    private final int maxBatchSize;

    private int sendAttemptCount = 0;

    // Repeated task generation support for sensors
    private java.util.Random random = new java.util.Random();
    private java.util.Map<String, String> taskPatternToId = new java.util.HashMap<>(); // Pattern -> TaskId for reuse
    private int taskIdCounter = 1; // Counter for generating pattern-based taskIds (not cloudletId)
    // Repeated task probability - configurable, default 0.6 (60%) for realistic IoT scenarios
    // IoT sensors typically send similar data repeatedly (temperature, motion, etc.)
    private double repeatedTaskProbability;
    // Calculate max unique patterns from config: CPU options × Memory options
    // Default: 6 CPU × 2 Memory = 12 patterns (will start reuse after seeing all
    // patterns)
    private int maxUniqueTasks;

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
        this.rlFogDevice = null;

        // Configuration
        this.maxBatchSize = 10; // Maximum 10 tasks per batch

        // Load repeated task probability from config (default: 0.6 = 60% for realistic IoT scenarios)
        this.repeatedTaskProbability = EnhancedConfigurationLoader.getSimulationConfigDouble(
            "sensors.parameters.repeated-task-probability", 0.6);

        // Calculate max unique patterns from config
        this.maxUniqueTasks = calculateMaxUniquePatterns();
    }

    /**
     * Set RLFogDevice reference for storing pending async requests
     * 
     * @param rlFogDevice The RLFogDevice instance
     */
    public void setRLFogDevice(org.patch.devices.RLFogDevice rlFogDevice) {
        this.rlFogDevice = rlFogDevice;

        logger.info(String.format("SchedulerIntegration: maxUniquePatterns=%d, reuseProbability=%.1f%%",
                maxUniqueTasks, repeatedTaskProbability * 100));
    }

    /**
     * Calculate maximum unique patterns from CPU and Memory options
     * Formula: CPU options count × Memory options count
     */
    private int calculateMaxUniquePatterns() {
        // Use getSensorConfigList for sensor configuration
        java.util.List<Long> cpuOptions = EnhancedConfigurationLoader
                .getSensorConfigList("sensors.parameters.cpu.options");
        java.util.List<Long> memoryOptions = EnhancedConfigurationLoader
                .getSensorConfigList("sensors.parameters.memory.options");

        int cpuCount = (cpuOptions != null && !cpuOptions.isEmpty()) ? cpuOptions.size() : 1;
        int memoryCount = (memoryOptions != null && !memoryOptions.isEmpty()) ? memoryOptions.size() : 1;

        int maxPatterns = cpuCount * memoryCount;
        // Use exact number: once map size = maxPatterns, we've seen all unique patterns
        return maxPatterns;
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
            logger.severe("Scheduler client is null - cannot send tasks to scheduler");
            return;
        }

        int queueSize = unscheduledQueue.size();
        if (queueSize == 0) {
            // Only log first few empty queue cases to avoid log bloat
            logger.fine("Unscheduled queue is empty - nothing to send");
            return;
        }

        // Get tasks to send (limit batch size)
        List<UnscheduledQueue.TaskInfo> tasksToSend = getTasksForScheduler();
        if (tasksToSend.isEmpty()) {
            logger.fine("No tasks found to send (after filtering)");
            return;
        }

        logger.info("Sending " + tasksToSend.size() + " tasks to scheduler");

        // Check if scheduler service is available
        if (!schedulerClient.isConnected()) {
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

            logger.info("Calling schedulerClient.addTasksToQueue with " + protoTasks.size() + " tasks (queue_size="
                    + totalQueueSize + ")");

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
                    logger.info("Removed task " + taskId + " from unscheduled queue before sending to scheduler");
                } else {
                    logger.warning("Task " + taskId + " not found in unscheduled queue before sending");
                }
            }

            // EVENT-BASED ASYNC: Send each task individually using async method
            // This allows network latency to advance simulation time and record energy/cost
            List<org.patch.models.PendingSchedulingRequest> pendingRequests = new ArrayList<>();
            
            for (Task protoTask : protoTasks) {
                // Call async method for each task
                org.patch.models.PendingSchedulingRequest pending = schedulerClient.addTaskToQueueAsync(
                        protoTask, availableNodes, policy, queueContext, deviceId);
                
                // Store pending request in RLFogDevice if available
                if (rlFogDevice != null) {
                    rlFogDevice.storePendingSchedulingRequest(pending);
                } else {
                    // Fallback: store locally (but responses won't be processed correctly)
                    pendingRequests.add(pending);
                    logger.warning("RLFogDevice not set - pending request for task: " + protoTask.getTaskId() + " stored locally only");
                }
            }
            
            // Note: Responses will be handled via GRPC_SCHEDULER_RESPONSE events
            // We don't wait for responses here - they arrive asynchronously via CloudSim events
            logger.info("Initiated " + protoTasks.size() + " async scheduling requests");

            // Note: Responses are processed asynchronously via GRPC_SCHEDULER_RESPONSE events
            // in RLFogDevice.handleGrpcSchedulerResponse()
            // Scheduled queue will be updated via StreamingQueueObserver (GetSortedQueue)
            // processSchedulerResponses() is no longer called here - handled in event handler

        } catch (Exception e) {
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

        for (int i = 0; i < tasks.size(); i++) {
            UnscheduledQueue.TaskInfo taskInfo = tasks.get(i);
            Tuple tuple = taskInfo.getTuple();

            // Map tuple type to TaskType (if needed, use helper)
            TaskType taskType = mapTupleTypeToTaskType(tuple.getTupleType());
            // Assign random priority: 1, 2, or 3 (for priority-based actions to work)
            int priority = random.nextInt(3) + 1; // Random value: 1, 2, or 3

            // Create pattern key for task reuse (CPU-Memory pattern)
            String patternKey = String.format("%d-%d", tuple.getCloudletLength(), tuple.getCloudletFileSize());
            String taskId;

            // Decide whether to reuse existing task ID or create new one
            boolean shouldReuse = random.nextDouble() < repeatedTaskProbability &&
                    taskPatternToId.size() >= maxUniqueTasks &&
                    taskPatternToId.containsKey(patternKey);

            if (shouldReuse) {
                // Reuse existing task ID for this pattern (pattern-based, not cloudletId)
                taskId = taskPatternToId.get(patternKey);
                logger.info(String.format(
                        "[REPEATED-TASK-SENSOR] Reusing task ID %s for pattern (CPU:%d, Mem:%d) - Tuple cloudletId: %d",
                        taskId, tuple.getCloudletLength(), tuple.getCloudletFileSize(), tuple.getCloudletId()));
            } else {
                // Generate NEW pattern-based taskId (NOT cloudletId - cloudletId is unique and sent separately in metadata)
                // taskId is pattern-based and can be reused for similar tasks
                taskId = String.valueOf(taskIdCounter++);
                taskPatternToId.put(patternKey, taskId);
                logger.info(String.format(
                        "[NEW-TASK-PATTERN] Generated pattern-based taskId=%s for pattern (CPU:%d, Mem:%d) - Tuple cloudletId: %d",
                        taskId, tuple.getCloudletLength(), tuple.getCloudletFileSize(), tuple.getCloudletId()));
                if (taskPatternToId.size() > maxUniqueTasks * 2) {
                    // Cleanup: remove oldest entries to prevent memory growth
                    java.util.Iterator<java.util.Map.Entry<String, String>> it = taskPatternToId.entrySet().iterator();
                    int toRemove = taskPatternToId.size() - maxUniqueTasks;
                    while (it.hasNext() && toRemove > 0) {
                        it.next();
                        it.remove();
                        toRemove--;
                    }
                }
            }

            // Check local cache status before creating proto task
            boolean localCacheExists = false;
            if (cacheManager != null) {
                TaskCacheManager.CacheResult cacheResult = cacheManager.checkCache(taskId);
                localCacheExists = (cacheResult == TaskCacheManager.CacheResult.HIT_VALID);
                logger.fine(String.format(
                    "[LOCAL-CACHE-CHECK] Task %s: local_cache_exists=%s (cacheResult=%s)",
                    taskId, localCacheExists, cacheResult));
            }

            long cloudletId = tuple.getCloudletId();
            String cloudletIdStr = String.valueOf(cloudletId);

            // Get VM MIPS for execution time calculation
            // Try to get from RLFogDevice if available, otherwise use default
            long vmMips = 1000; // Default MIPS (from config: devices.fog.default-mips)
            if (rlFogDevice != null && rlFogDevice.getHost() != null && !rlFogDevice.getHost().getVmList().isEmpty()) {
                // Get MIPS from first available VM
                org.cloudbus.cloudsim.Vm vm = rlFogDevice.getHost().getVmList().get(0);
                vmMips = (long) vm.getMips();
                if (vmMips <= 0) {
                    vmMips = 1000; // Fallback to default
                }
            } else {
                // Try to get from configuration
                vmMips = (long) EnhancedConfigurationLoader.getDeviceConfigDouble(
                    "devices.fog.default-mips", 1000.0);
            }

            // Calculate execution time from cloudletLength and VM MIPS
            // execution_time (ms) = (cloudletLength (MI) / VM_MIPS) * 1000
            long cloudletLength = tuple.getCloudletLength();
            long executionTimeMs = (cloudletLength * 1000) / vmMips;
            if (executionTimeMs <= 0) {
                executionTimeMs = 1; // Minimum 1ms to pass server validation
            }

            // Convert cloudletFileSize (bytes) to memory_requirement (MB)
            long cloudletFileSize = tuple.getCloudletFileSize();
            long memoryRequirementMb = cloudletFileSize / (1024 * 1024);
            if (memoryRequirementMb <= 0) {
                memoryRequirementMb = 1; // Minimum 1 MB to pass server validation
            }

            // Convert cloudletOutputSize (bytes) to output_size (bytes) - direct mapping
            long cloudletOutputSize = tuple.getCloudletOutputSize();
            if (cloudletOutputSize <= 0) {
                // Fallback: estimate from memory_requirement if output size is not set
                cloudletOutputSize = tuple.getCloudletFileSize(); // Use input size as estimate
                if (cloudletOutputSize <= 0) {
                    cloudletOutputSize = 1024 * 1024; // Default 1 MB in bytes
                }
            }

            Task protoTask = Task.newBuilder()
                    .setTaskId(taskId)  // Reused TaskId (e.g., "1") - still used for cache key
                    .setTaskName(tuple.getTupleType())
                    .setTaskType(taskType)
                    .setCpuRequirement(tuple.getCloudletLength())  // ✅ CORRECT: MI → MI
                    .setMemoryRequirement(memoryRequirementMb)      // ✅ CORRECT: bytes → MB
                    .setExecutionTime(executionTimeMs)              // ✅ CORRECT: Calculated from MI and MIPS
                    .setOutputSize(cloudletOutputSize)              // ✅ CORRECT: bytes → bytes
                    .setPriority(priority) // Default priority (Tuple doesn't have priority field)
                    .setDeadline(0) // Later Feature: deadline-aware disabled
                    .putMetadata("cloudlet_id", cloudletIdStr)  // ✅ Store unique cloudletId
                    .setLocalCacheExists(localCacheExists)  // ✅ NEW: Local cache status for server (proper proto field)
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
        logger.info("Processing scheduler responses for " + responses.size()
                + " tasks (tasks already removed from unscheduled queue)");

        for (AddTaskToQueueResponse taskResponse : responses) {
            String taskId = taskResponse.getTaskId();
            boolean isCached = taskResponse.getIsCachedTask();
            CacheAction cacheAction = taskResponse.getCacheAction();

            if (taskResponse.getSuccess()) {
                // Task successfully sent to scheduler - already removed from unscheduled queue
                if (taskIdsSent.contains(taskId)) {
                    logger.info("Task " + taskId
                            + " successfully sent to scheduler (already removed from unscheduled queue)");

                    // All tasks (cached or not) go through the queue via streaming endpoint
                    // Cache decision is made during execution in TaskExecutionEngine
                    if (isCached && cacheAction == CacheAction.CACHE_ACTION_USE) {
                        logger.info("Task " + taskId + " is cached - will be handled during execution");
                    } else {
                        logger.fine("Task " + taskId + " will be queued for execution");
                    }

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
                    null, // No cache key
                    taskInfo.getTaskId(),  // ✅ Use scheduler-assigned TaskId
                    org.patch.proto.IfogsimCommon.CacheAction.CACHE_ACTION_NONE  // ✅ Default cache action (fallback)
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
