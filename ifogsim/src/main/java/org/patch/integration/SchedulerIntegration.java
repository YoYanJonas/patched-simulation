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

    // Debug counter for tracking send attempts
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
            System.out.println(String.format(
                    "[SCHEDULER-SEND] Device %d - Attempt %d: No tasks found after filtering (queue size: %d)",
                    deviceId, sendAttemptCount, queueSize));
            logger.fine("No tasks found to send (after filtering)");
            return;
        }

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

        for (UnscheduledQueue.TaskInfo taskInfo : tasksToSend) {
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND] Time: %.2f - FogNode (ID:%d) - Task details: ID=%d, CPU=%d, Mem=%d, Out=%d",
                    currentTime, deviceId, taskInfo.getTuple().getCloudletId(),
                    taskInfo.getTuple().getCloudletLength(), taskInfo.getTuple().getCloudletFileSize(),
                    taskInfo.getTuple().getCloudletOutputSize()));
        }

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

            if (sendAttemptCount <= 20 || sendAttemptCount % 10 == 0) {
                System.out.println(String.format(
                        "[SCHEDULER-SEND] Device %d - Attempt %d: Calling addTasksToQueue with %d tasks (queue_size=%d)",
                        deviceId, sendAttemptCount, protoTasks.size(), totalQueueSize));
            }
            logger.info("Calling schedulerClient.addTasksToQueue with " + protoTasks.size() + " tasks (queue_size="
                    + totalQueueSize + ")");

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
                    if (i > 0)
                        taskIds.append(",");
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

            // EVENT-BASED ASYNC: Send each task individually using async method
            // This allows network latency to advance simulation time and record energy/cost
            List<org.patch.models.PendingSchedulingRequest> pendingRequests = new ArrayList<>();
            
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND-CALL] Time: %.2f - FogNode (ID:%d) - NOW CALLING scheduler.addTaskToQueueAsync for %d tasks (ASYNC, NON-BLOCKING)",
                    sendTime, deviceId, protoTasks.size()));
            
            for (Task protoTask : protoTasks) {
                // Call async method for each task
                org.patch.models.PendingSchedulingRequest pending = schedulerClient.addTaskToQueueAsync(
                        protoTask, availableNodes, policy, queueContext, deviceId);
                
                // Store pending request in RLFogDevice if available
                if (rlFogDevice != null) {
                    rlFogDevice.storePendingSchedulingRequest(pending);
                    logger.info(String.format(
                        "[DEBUG-ASYNC-SCHEDULER] Time: %.2f - Device: %d - Stored pending request for task: %s in RLFogDevice",
                        CloudSim.clock(), deviceId, protoTask.getTaskId()));
                } else {
                    // Fallback: store locally (but responses won't be processed correctly)
                    pendingRequests.add(pending);
                    logger.warning(String.format(
                        "[DEBUG-ASYNC-SCHEDULER] Time: %.2f - Device: %d - RLFogDevice not set - pending request for task: %s stored locally only",
                        CloudSim.clock(), deviceId, protoTask.getTaskId()));
                }
            }
            
            // Note: Responses will be handled via GRPC_SCHEDULER_RESPONSE events
            // We don't wait for responses here - they arrive asynchronously via CloudSim events
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND-ASYNC] Time: %.2f - FogNode (ID:%d) - Sent %d async scheduling requests, waiting for responses via events",
                    CloudSim.clock(), deviceId, protoTasks.size()));
            
            double receiveTime = CloudSim.clock();
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND-ASYNC-INITIATED] Time: %.2f - FogNode (ID:%d) - Initiated %d async scheduling requests (responses will arrive via events)",
                    receiveTime, deviceId, protoTasks.size()));

            // Note: Response details will be logged in handleGrpcSchedulerResponse() event handler
            // No need to log here since responses arrive asynchronously

            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND-NOTE] Time: %.2f - FogNode (ID:%d) - Tasks already removed from unscheduled queue. Scheduled queue will be updated via streaming endpoint (GetSortedQueue)",
                    receiveTime, deviceId));

            if (sendAttemptCount <= 20 || sendAttemptCount % 10 == 0) {
                System.out.println(
                        String.format("[SCHEDULER-SEND] Device %d - Attempt %d: Initiated %d async scheduling requests",
                                deviceId, sendAttemptCount, protoTasks.size()));
            }
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
        double currentTime = CloudSim.clock();

        System.out.println(String.format(
                "[FLOW-FOG-PROTO-CONVERT-START] Time: %.2f - FogNode (ID:%d) - Converting %d tasks to proto format",
                currentTime, deviceId, tasks.size()));

        for (int i = 0; i < tasks.size(); i++) {
            UnscheduledQueue.TaskInfo taskInfo = tasks.get(i);
            Tuple tuple = taskInfo.getTuple();

            System.out.println(String.format(
                    "[FLOW-FOG-PROTO-CONVERT-TUPLE] Time: %.2f - FogNode (ID:%d) - Task %d/%d: Tuple ID=%d, Type=%s, CPU=%d, Mem=%d",
                    currentTime, deviceId, i + 1, tasks.size(), tuple.getCloudletId(), tuple.getTupleType(),
                    tuple.getCloudletLength(), tuple.getCloudletFileSize()));

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

            // [DEBUG-LOG] Log TaskId and cloudlet_id metadata for ACK failure investigation
            long cloudletId = tuple.getCloudletId();
            String cloudletIdStr = String.valueOf(cloudletId);
            System.out.println(String.format(
                    "[DEBUG-KEY-CREATION] SchedulerIntegration: TaskId='%s', cloudletId=%d, cloudlet_id metadata='%s', TaskId==cloudlet_id? %s",
                    taskId, cloudletId, cloudletIdStr, taskId.equals(cloudletIdStr)));
            logger.info(String.format(
                    "[DEBUG-KEY-CREATION] SchedulerIntegration: TaskId='%s', cloudletId=%d, cloudlet_id metadata='%s', TaskId==cloudlet_id? %s",
                    taskId, cloudletId, cloudletIdStr, taskId.equals(cloudletIdStr)));

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
            
            // [DEBUG-LOG] Log final proto task values
            System.out.println(String.format(
                    "[DEBUG-KEY-CREATION] SchedulerIntegration: Final protoTask.getTaskId()='%s', protoTask.getMetadataMap().get('cloudlet_id')='%s'",
                    protoTask.getTaskId(), protoTask.getMetadataMap().get("cloudlet_id")));
            logger.info(String.format(
                    "[DEBUG-KEY-CREATION] SchedulerIntegration: Final protoTask.getTaskId()='%s', protoTask.getMetadataMap().get('cloudlet_id')='%s'",
                    protoTask.getTaskId(), protoTask.getMetadataMap().get("cloudlet_id")));

            protoTasks.add(protoTask);

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

        System.out.println(String.format(
                "[FLOW-SCHEDULER-RESPONSE-PROCESS] Time: %.2f - FogNode (ID:%d) - Processing %d scheduler responses",
                currentTime, deviceId, responses.size()));

        for (AddTaskToQueueResponse taskResponse : responses) {
            String taskId = taskResponse.getTaskId();
            boolean isCached = taskResponse.getIsCachedTask();
            CacheAction cacheAction = taskResponse.getCacheAction();

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
