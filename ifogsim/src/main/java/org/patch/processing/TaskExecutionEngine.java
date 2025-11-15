package org.patch.processing;

import org.fog.entities.Tuple;
import org.fog.entities.FogDevice;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.models.ScheduledQueue;
import org.patch.client.SchedulerClient;
import org.patch.client.AllocationClient;
import org.patch.utils.TaskCacheManager;
import org.patch.utils.ExtendedFogEvents;
import org.patch.proto.IfogsimCommon.CacheAction;
import org.fog.utils.TimeKeeper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Task Execution Engine for processing tasks from ScheduledQueue
 * 
 * This class bridges the gap between the RL scheduling system and iFogSim's
 * core tuple processing, ensuring tasks are properly executed using both
 * RL decisions and iFogSim's built-in mechanisms.
 * 
 * Key Features:
 * - Processes tasks from ScheduledQueue in RL-determined order
 * - Integrates with RLTupleProcessing for RL-aware execution
 * - Uses iFogSim's core tuple processing mechanisms
 * - Handles task completion detection and reporting
 * - Manages cache integration for performance optimization
 * 
 * @author Younes Shafiee
 */
public class TaskExecutionEngine {
    private static final Logger logger = Logger.getLogger(TaskExecutionEngine.class.getName());

    // Core components
    private final FogDevice fogDevice;
    private final ScheduledQueue scheduledQueue;
    private final RLTupleProcessing rlTupleProcessing;
    private final TaskCacheManager cacheManager;

    // gRPC clients
    private final SchedulerClient schedulerClient;

    // Task execution state
    private final Map<String, TaskExecutionState> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> taskStartTimes = new ConcurrentHashMap<>();

    // Performance metrics
    private long totalTasksExecuted = 0;
    private long totalExecutionTime = 0;
    private long totalEnergyConsumed = 0;
    private double totalCost = 0.0;
    private int successfulExecutions = 0;
    private int failedExecutions = 0;

    // Configuration
    private boolean cacheEnabled = true;

    /**
     * Constructor for TaskExecutionEngine
     * 
     * @param fogDevice        The fog device this engine belongs to
     * @param scheduledQueue   The scheduled queue to process tasks from
     * @param schedulerClient  Scheduler client for RL communication
     * @param allocationClient Allocation client for cloud communication
     * @param cacheManager     Cache manager for performance optimization
     */
    public TaskExecutionEngine(FogDevice fogDevice,
            ScheduledQueue scheduledQueue,
            SchedulerClient schedulerClient,
            AllocationClient allocationClient,
            TaskCacheManager cacheManager) {
        this.fogDevice = fogDevice;
        this.scheduledQueue = scheduledQueue;
        this.schedulerClient = schedulerClient;
        this.cacheManager = cacheManager;

        // Initialize RL tuple processing
        this.rlTupleProcessing = new RLTupleProcessing();

        // Configure RL clients
        Map<Integer, SchedulerClient> schedulerClients = new HashMap<>();
        schedulerClients.put(fogDevice.getId(), schedulerClient);
        rlTupleProcessing.configureRLClients(null, schedulerClients); // allocationClient will be null for fog devices
        rlTupleProcessing.enableRL();

        logger.info("TaskExecutionEngine initialized for device: " + fogDevice.getName());
    }

    /**
     * Process the next task from the scheduled queue
     * 
     * @return true if a task was processed, false if queue is empty
     */
    public boolean processNextTask() {
        ScheduledQueue.TaskInfo taskInfo = null;
        double currentTime = CloudSim.clock();

        try {
            if (scheduledQueue == null) {
                System.err.println(String.format(
                        "[FLOW-FOG-EXECUTE] Time: %.2f - FogNode (ID:%d) - ERROR: Scheduled queue is NULL!",
                        currentTime, fogDevice.getId()));
                logger.warning("Scheduled queue is null, cannot process tasks");
                return false;
            }

            if (scheduledQueue.isEmpty()) {
                // [DEBUG] Log empty queue (only occasionally to avoid spam)
                if (totalTasksExecuted == 0 || totalTasksExecuted % 100 == 0) {
                    System.out.println(String.format(
                            "[FLOW-FOG-EXECUTE] Time: %.2f - FogNode (ID:%d) - Scheduled queue is EMPTY (total executed: %d)",
                            currentTime, fogDevice.getId(), totalTasksExecuted));
                }
                return false;
            }

            // [DEBUG] Log queue status before processing
            System.out.println(String.format(
                    "[FLOW-FOG-EXECUTE] Time: %.2f - FogNode (ID:%d) - Scheduled queue has %d tasks, processing next...",
                    currentTime, fogDevice.getId(), scheduledQueue.size()));

            // Get the next task from the head of the queue
            taskInfo = scheduledQueue.getNextTask();
            if (taskInfo == null) {
                System.err.println(String.format(
                        "[FLOW-FOG-EXECUTE] Time: %.2f - FogNode (ID:%d) - ERROR: getNextTask() returned NULL (queue size: %d)",
                        currentTime, fogDevice.getId(), scheduledQueue.size()));
                return false;
            }
        } catch (Exception e) {
            System.err.println(String.format(
                    "[FLOW-FOG-EXECUTE] Time: %.2f - FogNode (ID:%d) - ERROR checking scheduled queue: %s",
                    currentTime, fogDevice.getId(), e.getMessage()));
            logger.log(Level.SEVERE, "Error checking scheduled queue state", e);
            return false;
        }

        String taskId = taskInfo.getTaskId();
        // currentTime already defined above, reuse it

        // [DEBUG] Log task execution from scheduled queue
        System.out.println(String.format(
                "[FLOW-FOG-EXECUTE] Time: %.2f - FogNode (ID:%d) - Processing task %s from SCHEDULED queue (scheduled queue size after pop: %d)",
                currentTime, fogDevice.getId(), taskId, scheduledQueue.size()));

        logger.fine("Processing task: " + taskId + " on device: " + fogDevice.getName());

        // CRITICAL: Handle cache actions before processing
        CacheAction cacheAction = taskInfo.getCacheAction();
        
        // Handle CACHE_ACTION_STORE: If cache exists, we still execute to update it
        // STORE means "execute and cache result" - even if cache exists, we execute to update
        if (cacheAction == CacheAction.CACHE_ACTION_STORE && 
            cacheEnabled && cacheManager != null) {
            Object existingCache = cacheManager.getCachedResult(taskId);
            if (existingCache != null) {
                // Cache exists but STORE action means we should execute and update cache
                logger.info(String.format(
                    "[CACHE-STORE-UPDATE] Time: %.2f - FogNode (ID:%d) - Task %s: STORE action with existing cache - will execute and update cache",
                    currentTime, fogDevice.getId(), taskId));
                System.out.println(String.format(
                    "[CACHE-STORE-UPDATE] Time: %.2f - FogNode (ID:%d) - Task %s: Cache exists but STORE action - executing to update cache",
                    currentTime, fogDevice.getId(), taskId));
                // Continue to execute normally (don't use cache)
            }
        }
        
        // Handle CACHE_ACTION_INVALIDATE - delete cache entry before processing
        // When scheduler says INVALIDATE, we must delete the cache entry in iFogSim
        // If cache doesn't exist, report as cache miss
        if (cacheAction == CacheAction.CACHE_ACTION_INVALIDATE && 
            cacheEnabled && cacheManager != null) {
            // Check if cache entry exists before attempting to delete
            Object cachedResult = cacheManager.getCachedResult(taskId);
            boolean cacheExisted = (cachedResult != null);
            
            if (cacheExisted) {
                // Cache entry exists - delete it as instructed by scheduler
                cacheManager.invalidateCache(taskId);
                logger.info(String.format(
                    "[CACHE-INVALIDATE] Time: %.2f - FogNode (ID:%d) - Task %s: Cache entry DELETED per scheduler INVALIDATE action",
                    currentTime, fogDevice.getId(), taskId));
                System.out.println(String.format(
                    "[CACHE-INVALIDATE] Time: %.2f - FogNode (ID:%d) - Task %s: Cache entry DELETED (CACHE_ACTION_INVALIDATE) - will execute normally",
                    currentTime, fogDevice.getId(), taskId));
            } else {
                // Cache entry doesn't exist - report as cache miss
                logger.warning(String.format(
                    "[CACHE-INVALIDATE-MISS] Time: %.2f - FogNode (ID:%d) - Task %s: INVALIDATE requested but cache entry NOT FOUND - reporting as cache miss",
                    currentTime, fogDevice.getId(), taskId));
                System.out.println(String.format(
                    "[CACHE-INVALIDATE-MISS] Time: %.2f - FogNode (ID:%d) - Task %s: INVALIDATE requested but cache entry NOT FOUND (cache miss) - will execute normally",
                    currentTime, fogDevice.getId(), taskId));
                // Record as cache miss in TaskCacheManager
                cacheManager.checkCache(taskId); // This records the miss
            }
            // After invalidation (or miss), task should be processed normally (not cached)
        }

        // Check if task is cached (ONLY scheduler decision - server is source of truth)
        boolean isCachedByScheduler = taskInfo.isCachedTask();
        boolean cacheExists = false;
        
        // CRITICAL: If scheduler says "use cache", verify local cache actually has the result
        if (isCachedByScheduler && cacheEnabled && cacheManager != null) {
            // Verify cache actually exists before treating as cached
            Object cachedResult = cacheManager.getCachedResult(taskId);
            cacheExists = (cachedResult != null);
            
            if (!cacheExists) {
                // Scheduler said cached but local cache doesn't have it - cache miss!
                logger.warning(String.format(
                    "[CACHE-MISS-VERIFY] Time: %.2f - FogNode (ID:%d) - Task %s: Scheduler said CACHED but local cache MISS - executing normally and reporting as cache miss",
                    currentTime, fogDevice.getId(), taskId));
                System.out.println(String.format(
                    "[CACHE-MISS-VERIFY] Time: %.2f - FogNode (ID:%d) - Task %s: Scheduler marked as cached but cache entry NOT FOUND - executing normally",
                    currentTime, fogDevice.getId(), taskId));
                // Don't treat as cached - execute normally
                isCachedByScheduler = false;
            } else {
                // Cache exists - valid cache hit
                logger.info(String.format(
                    "[CACHE-VERIFY-HIT] Time: %.2f - FogNode (ID:%d) - Task %s: Scheduler said cached and local cache CONFIRMED - using cached result",
                    currentTime, fogDevice.getId(), taskId));
            }
        }
        
        // Optional: Detect cache mismatch (for debugging and synchronization)
        // If scheduler says NOT cached, check if local cache has it (mismatch detection)
        if (!isCachedByScheduler && cacheEnabled && cacheManager != null) {
            TaskCacheManager.CacheResult localCacheResult = cacheManager.checkCache(taskId);
            if (localCacheResult == TaskCacheManager.CacheResult.HIT_VALID) {
                // Mismatch detected: server says NOT cached, but local cache has it
                // Trust server's decision, invalidate local cache to sync
                logger.warning(String.format(
                    "[CACHE-MISMATCH] Time: %.2f - FogNode (ID:%d) - Task %s: Server says NOT cached, but local cache has HIT - trusting server, invalidating local cache",
                    currentTime, fogDevice.getId(), taskId));
                System.out.println(String.format(
                    "[CACHE-MISMATCH] Time: %.2f - FogNode (ID:%d) - Task %s: Cache mismatch detected (server=NOT cached, local=HIT) - invalidating local cache to sync with server",
                    currentTime, fogDevice.getId(), taskId));
                cacheManager.invalidateCache(taskId); // Sync with server
            }
        }
        
        // Handle cached task ONLY if scheduler says cached AND cache exists
        // NO local fallback - server is source of truth
        boolean isCached = isCachedByScheduler && cacheExists;
        if (isCached && cacheEnabled) {
            System.out.println(String.format(
                    "[FLOW-FOG-EXECUTE-CACHE] Time: %.2f - FogNode (ID:%d) - Task %s is CACHED (scheduler=YES, verified=%s) - Skipping execution, using cached result",
                    currentTime, fogDevice.getId(), taskId, cacheExists ? "YES" : "NO"));
            logger.info("Task " + taskId + " is cached (scheduler: YES, verified: " + cacheExists + ") - handling cached task");
            return handleCachedTask(taskInfo);
        }

        // Execute the task (not cached or cache missing)
        if (isCachedByScheduler && !cacheExists) {
            logger.warning(String.format(
                "[CACHE-MISS-EXECUTE] Time: %.2f - FogNode (ID:%d) - Task %s: Executing normally due to cache miss (scheduler said cached but cache not found)",
                currentTime, fogDevice.getId(), taskId));
        }
        return executeTask(taskInfo);
    }

    /**
     * Handle a cached task (task result is already available)
     * 
     * @param taskInfo The cached task information
     * @return true if task was handled successfully
     */
    private boolean handleCachedTask(ScheduledQueue.TaskInfo taskInfo) {
        String taskId = taskInfo.getTaskId();
        Tuple tuple = taskInfo.getTuple();
        long cloudletId = tuple.getCloudletId();
        double startTime = CloudSim.clock();
        
        logger.info("Handling cached task: " + taskId);

        // CRITICAL: Check if this cloudletId is already being processed (including cached tasks)
        // This prevents duplicate processing when the same task is re-added from the server
        if (isCloudletIdActive(cloudletId)) {
            logger.warning(String.format(
                "[DUPLICATE-CACHED-TASK-SKIP] Time: %.2f - FogNode (ID:%d) - SKIPPING duplicate cached task: cloudletId=%d, taskId=%s (already in activeTasks)",
                startTime, fogDevice.getId(), cloudletId, taskId));
            System.out.println(String.format(
                "[DUPLICATE-CACHED-TASK-SKIP] Time: %.2f - FogNode (ID:%d) - SKIPPING duplicate cached task: cloudletId=%d, taskId=%s (already processing)",
                startTime, fogDevice.getId(), cloudletId, taskId));
            return false; // Don't process duplicate
        }

        try {
            // CRITICAL: Add cached task to activeTasks BEFORE processing
            // This ensures duplicate check in StreamingQueueObserver works correctly
            // Create execution state for cached task
            TaskExecutionState cachedState = new TaskExecutionState(taskInfo, (long) startTime);
            cachedState.setCached(true); // Mark as cached task
            cachedState.setSuccess(true); // Cached tasks are always successful
            cachedState.setExecutionTime(0); // Instant execution
            // FIX (Issue 4): Use cloudletId as key instead of taskId
            activeTasks.put(String.valueOf(cloudletId), cachedState);
            
            // [DEBUG] Log addition to activeTasks
            System.out.println(String.format(
                    "[CACHE-ACTIVE-TASKS-ADD] Time: %.2f - FogNode (ID:%d) - Added cached task %s (cloudletId=%d) to activeTasks (size now: %d)",
                    startTime, fogDevice.getId(), taskId, cloudletId, activeTasks.size()));

            // Remove from scheduled queue
            scheduledQueue.removeTask(taskId);

            // Mark as completed with cached result
            markTaskCompleted(taskInfo, true, 0, "cached_result");

            // Update metrics
            totalTasksExecuted++;
            successfulExecutions++;

            // Report completion to scheduler with isCached=true and executionTime=0
            // This indicates a successful cache hit (instant execution)
            // Cached tasks don't use resources, so utilization is 0
            if (fogDevice instanceof org.patch.devices.RLFogDevice) {
                String cacheKey = taskInfo.getCacheKey();
                
                // Cached tasks execute instantly without resource allocation
                double cachedCpuUtilization = 0.0;
                double cachedRamUtilization = 0.0;
                
                // [DEBUG] Log cache data before reporting cached task completion
                System.out.println(String.format(
                        "[CACHE-COMPLETION-PREP] Task=%s, isCachedTask()=true, cacheKey=%s, executionTime=0 ms, success=true, cpuUtil=0.0%%, ramUtil=0.0%%",
                        taskId, cacheKey != null ? cacheKey : "null"));
                
                // Report completion to scheduler and get ACK
                boolean ackSuccess = ((org.patch.devices.RLFogDevice) fogDevice).reportTaskCompletion(
                        tuple, true, 0, true, cachedCpuUtilization, cachedRamUtilization);
                // success=true, executionTime=0, isCached=true

                // Use ACK to confirm server processed completion
                if (ackSuccess) {
                    // Server confirmed: task is removed from server's queue
                    // Mark as reported and remove from activeTasks immediately
                    cachedState.setReportedCompletion(true);
                    removeTaskAfterCompletion(cloudletId);
                    
                    System.out.println(String.format(
                            "[FLOW-FOG-COMPLETE-CACHE-ACK] Time: %.2f - FogNode (ID:%d) - CACHED task %s completion confirmed by server (ACK success), removed from activeTasks",
                            CloudSim.clock(), fogDevice.getId(), taskId));
                } else {
                    // ACK failed: keep in activeTasks, might retry later
                    // Duplicate check will prevent re-processing
                    logger.warning(String.format(
                        "[FLOW-FOG-COMPLETE-CACHE-ACK-FAIL] Time: %.2f - FogNode (ID:%d) - CACHED task %s completion NOT confirmed by server (ACK failed), keeping in activeTasks",
                        CloudSim.clock(), fogDevice.getId(), taskId));
                    System.out.println(String.format(
                            "[FLOW-FOG-COMPLETE-CACHE-ACK-FAIL] Time: %.2f - FogNode (ID:%d) - CACHED task %s completion report rejected, task stays in activeTasks",
                            CloudSim.clock(), fogDevice.getId(), taskId));
                }
            }

            logger.info("Cached task " + taskId + " completed successfully");
            return true;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling cached task " + taskId, e);
            // Remove from activeTasks on error
            // FIX (Issue 4): Use cloudletId as key instead of taskId
            activeTasks.remove(String.valueOf(cloudletId));
            return false;
        }
    }

    /**
     * Execute a task using RL-aware processing
     * 
     * @param taskInfo The task to execute
     * @return true if task was executed successfully
     */
    private boolean executeTask(ScheduledQueue.TaskInfo taskInfo) {
        String taskId = taskInfo.getTaskId();
        Tuple tuple = taskInfo.getTuple();
        long cloudletId = tuple.getCloudletId();
        double startTime = CloudSim.clock();

        // CRITICAL: Check if this cloudletId is already being processed
        // This prevents duplicate processing when the same task is re-added from the server
        if (isCloudletIdActive(cloudletId)) {
            logger.warning(String.format(
                "[DUPLICATE-TASK-SKIP] Time: %.2f - FogNode (ID:%d) - SKIPPING duplicate task: cloudletId=%d, taskId=%s (already in activeTasks)",
                startTime, fogDevice.getId(), cloudletId, taskId));
            System.out.println(String.format(
                "[DUPLICATE-TASK-SKIP] Time: %.2f - FogNode (ID:%d) - SKIPPING duplicate task: cloudletId=%d, taskId=%s (already executing)",
                startTime, fogDevice.getId(), cloudletId, taskId));
            return false; // Don't process duplicate
        }

        // [DEBUG] Log task execution start
        System.out.println(String.format(
                "[FLOW-FOG-EXECUTE] Time: %.2f - FogNode (ID:%d) - EXECUTING task %s (tuple ID: %d, CPU: %d, Mem: %d)",
                startTime, fogDevice.getId(), taskId, cloudletId,
                tuple.getCloudletLength(), tuple.getCloudletFileSize()));

        logger.fine("Executing task: " + taskId + " with tuple: " + cloudletId);

        try {
            // Record start time
            taskStartTimes.put(taskId, (long) startTime);

            // Create execution state
            TaskExecutionState state = new TaskExecutionState(taskInfo, (long) startTime);
            // FIX (Issue 4): Use cloudletId as key instead of taskId
            activeTasks.put(String.valueOf(cloudletId), state);

            // Remove from scheduled queue
            scheduledQueue.removeTask(taskId);

            // [DEBUG] Log removal from scheduled queue
            System.out.println(String.format(
                    "[FLOW-FOG-EXECUTE] Time: %.2f - FogNode (ID:%d) - Task %s removed from scheduled queue (new size: %d)",
                    CloudSim.clock(), fogDevice.getId(), taskId, scheduledQueue.size()));

            // Process tuple using RL-aware processing
            // Result is used by CloudSim scheduler for actual execution
            processTupleWithRL(tuple, taskInfo);

            // Note: Completion reporting and resource utilization capture are now done
            // in TUPLE_COMPLETE event handler (handleTupleComplete) after actual execution
            // Task remains in activeTasks until TUPLE_COMPLETE event fires

            logger.info("Task " + taskId + " scheduled for execution (will complete via TUPLE_COMPLETE event)");
            return true;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error executing task " + taskId, e);

            // Mark as failed
            markTaskCompleted(taskInfo, false, 0, "execution_error");

            // Update metrics
            totalTasksExecuted++;
            failedExecutions++;

            // Clean up
            // FIX (Issue 4): Use cloudletId as key instead of taskId
            activeTasks.remove(String.valueOf(cloudletId));
            taskStartTimes.remove(taskId);

            return false;
        }
    }

    /**
     * Process tuple using RL-aware processing
     * 
     * @param tuple    The tuple to process
     * @param taskInfo The task information
     * @return Processing result
     */
    private RLTupleProcessingResult processTupleWithRL(Tuple tuple, ScheduledQueue.TaskInfo taskInfo) {
        // Find the target VM for this task using module name from tuple
        String moduleName = tuple.getDestModuleName();
        if (moduleName == null) {
            logger.warning("No destination module name found in tuple");
            return createFailedResult(tuple, "no_dest_module");
        }

        Vm targetVm = findTargetVm(moduleName);
        if (targetVm == null) {
            logger.warning("No target VM found for module name: " + moduleName);
            return createFailedResult(tuple, "no_target_vm");
        }

        // Set VM ID for the tuple
        tuple.setVmId(targetVm.getId());

        // Check cache action - only USE skips execution
        // isCachedTask() == true means CACHE_ACTION_USE (skip execution, NO fog node
        // effects)
        // isCachedTask() == false means STORE/NONE/INVALIDATE (process normally)
        //
        // When cache is invalidated/deleted (expired, not in TTL), scheduler returns:
        // - CACHE_ACTION_INVALIDATE → cache entry deleted → isCachedTask() = false →
        // process normally
        // This is correct: after cache deletion, task should be processed normally
        if (taskInfo.isCachedTask()) {
            // Task is cached (USE action) - skip execution, return cached result, NO fog
            // node effects
            logger.info("Task " + taskInfo.getTaskId()
                    + " is cached (USE action) - skipping execution, no fog node effects");
            return new RLTupleProcessingResult(
                    tuple,
                    true,
                    "cached_task",
                    0, // No execution time
                    0.0, // No energy consumed
                    0.0, // No cost
                    "cached_execution");
        }

        // For non-cached tasks (STORE/NONE/INVALIDATE), process normally using iFogSim
        // mechanisms
        // Always use processTupleNormally() which has correct iFogSim implementation
        logger.info("Task " + taskInfo.getTaskId() + " is not cached - processing normally using iFogSim mechanisms");
        return processTupleNormally(tuple, targetVm, taskInfo);
    }

    /**
     * Process tuple using normal iFogSim mechanisms
     * 
     * @param tuple    The tuple to process
     * @param targetVm The target VM
     * @param taskInfo The task information (for accessing taskId and state)
     * @return Processing result
     */
    private RLTupleProcessingResult processTupleNormally(Tuple tuple, Vm targetVm, ScheduledQueue.TaskInfo taskInfo) {
        try {
            // Set VM ID for the tuple (required by iFogSim)
            tuple.setVmId(targetVm.getId());

            // Use iFogSim's TimeKeeper for proper timing
            TimeKeeper.getInstance().tupleStartedExecution(tuple);

            // Update allocated MIPS (iFogSim core mechanism)
            fogDevice.getHost().getVmScheduler().deallocatePesForVm(targetVm);
            fogDevice.getHost().getVmScheduler().allocatePesForVm(targetVm,
                    java.util.Arrays.asList((double) fogDevice.getHost().getTotalMips()));

            // Submit tuple as cloudlet to VM's scheduler (iFogSim core mechanism)
            // CRITICAL: cloudletSubmit() returns DURATION (processing time), NOT absolute finish time
            // It returns: cloudletLength / capacity (e.g., 1.43 seconds)
            double estimatedDuration = targetVm.getCloudletScheduler().cloudletSubmit(tuple, 0.0);

            // CRITICAL: Trigger VM processing to start cloudlet execution
            // This is required for CloudSim to actually execute the cloudlet and advance time
            if (targetVm instanceof org.fog.application.AppModule) {
                org.fog.application.AppModule appModule = (org.fog.application.AppModule) targetVm;
                appModule.updateVmProcessing(
                    CloudSim.clock(),
                    fogDevice.getHost().getVmScheduler().getAllocatedMipsForVm(targetVm)
                );
            }

            // Capture actual utilization DURING execution (while task is running)
            // FIX: Calculate from task's actual CPU requirement vs VM's allocated capacity
            // This gives the real CPU usage percentage (e.g., 500 MI task on 2800 MIPS = 17.86%)
            String taskId = taskInfo.getTaskId();
            // FIX (Issue 4): Use cloudletId as key instead of taskId
            TaskExecutionState state = activeTasks.get(String.valueOf(tuple.getCloudletId()));
            if (state != null) {
                // Get task's CPU requirement (cloudletLength in MI - Million Instructions)
                long taskCpuRequirement = tuple.getCloudletLength();
                
                // Get VM's allocated MIPS capacity
                double allocatedMips = fogDevice.getHost().getVmScheduler().getTotalAllocatedMipsForVm(targetVm);
                
                // CPU utilization: task requirement / allocated capacity
                // Example: 500 MI task on 2800 MIPS VM = 500/2800 = 17.86%
                double actualCpuUtilization = 0.0;
                if (allocatedMips > 0 && taskCpuRequirement > 0) {
                    actualCpuUtilization = (double) taskCpuRequirement / allocatedMips;
                }
                
                // Memory utilization: Calculate from VM's allocated RAM / total host RAM
                // Use VM's RAM allocation (what was allocated to the VM)
                int vmRamMb = targetVm.getRam(); // VM's allocated RAM
                int totalRamMb = fogDevice.getHost().getRam();
                double actualRamUtilization = (totalRamMb > 0) ? ((double) vmRamMb / totalRamMb) : 0.0;
                
                // Clamp to valid range [0.0, 1.0]
                if (actualCpuUtilization < 0.0) actualCpuUtilization = 0.0;
                if (actualCpuUtilization > 1.0) actualCpuUtilization = 1.0;
                if (actualRamUtilization < 0.0) actualRamUtilization = 0.0;
                if (actualRamUtilization > 1.0) actualRamUtilization = 1.0;
                
                state.setCapturedCpuUtilization(actualCpuUtilization);
                state.setCapturedRamUtilization(actualRamUtilization);
                state.setUtilizationCaptured(true);
                
                // Log for debugging
                logger.info(String.format(
                    "[TASK-EXEC-CAPTURE] Time: %.2f - cloudletId: %d, taskId: %s, Captured utilization: CPU=%.2f%% (task=%d MI / allocated=%.2f MIPS), Memory=%.2f%% (allocated=%d MB / total=%d MB)",
                    CloudSim.clock(), tuple.getCloudletId(), taskId, 
                    actualCpuUtilization * 100, taskCpuRequirement, allocatedMips,
                    actualRamUtilization * 100, vmRamMb, totalRamMb));
            } else {
                logger.warning(String.format(
                    "[TASK-EXEC-CAPTURE] Time: %.2f - cloudletId: %d, taskId: %s, WARNING: TaskExecutionState not found, cannot capture utilization",
                    CloudSim.clock(), tuple.getCloudletId(), taskId));
            }

            // Use CloudSim's estimated duration directly (it's already the processing time)
            // estimatedDuration is the time needed to complete the cloudlet (e.g., 1.43 seconds)
            double processingTime = estimatedDuration;

            // Validate processingTime before scheduling event
            if (processingTime <= 0 || Double.isNaN(processingTime) || Double.isInfinite(processingTime)) {
                // Fallback to calculated processing time
                processingTime = calculateProcessingTime(tuple, targetVm);
                logger.warning(String.format(
                    "[TASK-EXEC] Invalid estimatedDuration from cloudletSubmit() for cloudletId: %d (value: %.2f), using calculated time: %.2f",
                    tuple.getCloudletId(), estimatedDuration, processingTime));
            }

            // Ensure processingTime is at least minimum time between events
            if (processingTime < CloudSim.getMinTimeBetweenEvents()) {
                processingTime = CloudSim.getMinTimeBetweenEvents();
            }

            // Calculate absolute finish time for logging (current time + duration)
            double estimatedFinishTime = CloudSim.clock() + processingTime;

            // Log execution start for debugging
            logger.info(String.format(
                "[TASK-EXEC-START] Time: %.2f - cloudletId: %d, estimatedDuration: %.2f, processingTime: %.2f, estimatedFinishTime: %.2f",
                CloudSim.clock(), tuple.getCloudletId(), estimatedDuration, processingTime, estimatedFinishTime));

            // Schedule tuple completion event using validated processingTime
            // This allows CloudSim to advance time and process the tuple
            CloudSim.send(fogDevice.getId(), fogDevice.getId(), processingTime,
                    org.patch.utils.ExtendedFogEvents.TUPLE_COMPLETE, tuple);

            // Update fog device status immediately
            // This ensures CPU/memory/energy utilization is updated
            // Note: Fog device status will be updated when TUPLE_COMPLETE event is
            // processed

            // Return result with calculated processing time
            // Execution time will be updated when TUPLE_COMPLETE event is processed
            return new RLTupleProcessingResult(
                    tuple,
                    true,
                    "ifogsim_normal_processing",
                    (long) processingTime, // Use calculated processing time
                    calculateEnergyFromProcessing(processingTime, targetVm),
                    calculateCostFromProcessing(processingTime, targetVm),
                    "ifogsim_normal_processing");

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in iFogSim normal tuple processing", e);
            return createFailedResult(tuple, "ifogsim_processing_error");
        }
    }

    /**
     * Find the target VM for a module name (iFogSim compatible)
     * 
     * @param moduleName The module name
     * @return The target VM or null if not found
     */
    private Vm findTargetVm(String moduleName) {
        for (Vm vm : fogDevice.getHost().getVmList()) {
            if (vm instanceof org.fog.application.AppModule) {
                org.fog.application.AppModule appModule = (org.fog.application.AppModule) vm;
                if (appModule.getName().equals(moduleName)) {
                    return vm;
                }
            }
        }
        return null;
    }

    /**
     * Get task execution state by cloudletId
     * Used by TUPLE_COMPLETE event handler to find task info
     * 
     * @param cloudletId The cloudlet ID to search for
     * @return TaskExecutionState if found, null otherwise
     */
    public TaskExecutionState getTaskByCloudletId(long cloudletId) {
        // [DEBUG] Log lookup attempt
        logger.fine("Looking up task by cloudletId: " + cloudletId + " (activeTasks size: " + activeTasks.size() + ")");
        
        // FIX (Issue 4): Use direct lookup with cloudletId as key (O(1) instead of O(n))
        TaskExecutionState state = activeTasks.get(String.valueOf(cloudletId));
        if (state != null) {
            logger.fine("Found task state for cloudletId: " + cloudletId + " (taskId: " + state.getTaskInfo().getTaskId() + ")");
            return state;
        }
        
        logger.warning("Task state not found for cloudletId: " + cloudletId + " (activeTasks size: " + activeTasks.size() + ")");
        return null;
    }

    /**
     * Check if a cloudletId is already being processed (active)
     * Used to prevent duplicate processing of the same task instance
     * 
     * @param cloudletId The cloudlet ID to check
     * @return true if cloudletId is already in activeTasks, false otherwise
     */
    public boolean isCloudletIdActive(long cloudletId) {
        return getTaskByCloudletId(cloudletId) != null;
    }

    /**
     * Remove task from active tasks after completion
     * Called from TUPLE_COMPLETE handler after reporting completion
     * 
     * NOTE: This method is now DEPRECATED for normal completion flow.
     * Tasks should stay in activeTasks until server confirms (two-stage removal).
     * This method is kept for error handling cases.
     * 
     * @param cloudletId The cloudlet ID of the task to remove
     */
    public void removeTaskAfterCompletion(long cloudletId) {
        // FIX (Issue 4): Use direct lookup and removal with cloudletId as key
        TaskExecutionState state = activeTasks.remove(String.valueOf(cloudletId));
        if (state != null) {
            String taskId = state.getTaskInfo().getTaskId();
            taskStartTimes.remove(taskId);
            logger.fine("Removed task from activeTasks: cloudletId=" + cloudletId + ", taskId=" + taskId + " (activeTasks size now: " + activeTasks.size() + ")");
        } else {
            logger.warning("Cannot remove task: cloudletId " + cloudletId + " not found in activeTasks");
        }
    }

    /**
     * Remove tasks from activeTasks that were reported as completed
     * and are confirmed by server (not in server's queue response)
     * 
     * This implements Stage 2 of two-stage removal:
     * - Stage 1: Mark reportedCompletion=true (don't remove yet)
     * - Stage 2: Remove when server confirms (task not in server's response)
     * 
     * @param serverTaskIds Set of taskIds from server's GetSortedQueue response
     * @return Number of tasks removed
     */
    public int removeConfirmedCompletedTasks(Set<String> serverTaskIds) {
        List<String> toRemove = new ArrayList<>();
        
        // FIX (Issue 4): activeTasks now uses cloudletId as key, but serverTaskIds are taskIds
        // So we need to iterate and check taskId from state
        for (Map.Entry<String, TaskExecutionState> entry : activeTasks.entrySet()) {
            String cloudletIdKey = entry.getKey(); // This is now cloudletId (as String)
            TaskExecutionState state = entry.getValue();
            String taskId = state.getTaskInfo().getTaskId(); // Extract taskId from state
            
            // Check if task was reported AND server confirmed (not in queue)
            if (state.isReportedCompletion() && !serverTaskIds.contains(taskId)) {
                toRemove.add(cloudletIdKey); // Remove using cloudletId key
            }
        }
        
        // Remove confirmed completed tasks
        int removedCount = 0;
        for (String cloudletIdKey : toRemove) {
            TaskExecutionState state = activeTasks.remove(cloudletIdKey);
            if (state != null) {
                String taskId = state.getTaskInfo().getTaskId();
                long cloudletId = state.getTaskInfo().getTuple().getCloudletId();
                taskStartTimes.remove(taskId);
                removedCount++;
                logger.info(String.format(
                    "[TASK-CONFIRMED-REMOVED] Task %s (cloudletId=%d) confirmed completed by server, removed from activeTasks",
                    taskId, cloudletId));
                System.out.println(String.format(
                    "[TASK-CONFIRMED-REMOVED] Time: %.2f - FogNode (ID:%d) - Task %s (cloudletId=%d) confirmed completed by server, removed from activeTasks (size now: %d)",
                    CloudSim.clock(), fogDevice.getId(), taskId, cloudletId, activeTasks.size()));
            }
        }
        
        return removedCount;
    }

    /**
     * Calculate CPU and Memory utilization from task requirements
     * Used in TUPLE_COMPLETE handler to calculate utilization from task size vs host capacity
     * 
     * @param tuple The tuple/task
     * @return Array with [cpuUtilization, ramUtilization] in range [0.0, 1.0]
     */
    public double[] calculateUtilizationFromTaskRequirements(Tuple tuple) {
        double cpuUtilization = 0.0;
        double ramUtilization = 0.0;
        
        // Factor to account for overhead (5% overhead)
        double overheadFactor = 1.05;
        
        // CPU utilization: based on number of processing elements (cores) required
        int taskPes = tuple.getNumberOfPes();
        int hostPes = fogDevice.getHost().getNumberOfPes();
        
        if (hostPes > 0 && taskPes > 0) {
            // Calculate utilization: (task cores / host cores) * overhead factor
            cpuUtilization = ((double) taskPes / hostPes) * overheadFactor;
            // Clamp to [0.0, 1.0]
            if (cpuUtilization > 1.0) cpuUtilization = 1.0;
            if (cpuUtilization < 0.0) cpuUtilization = 0.0;
        }
        
        // Memory utilization: based on task file size (input data size)
        long taskMemoryBytes = tuple.getCloudletFileSize();
        int hostRamMB = fogDevice.getHost().getRam();
        long hostRamBytes = (long) hostRamMB * 1024L * 1024L; // Convert MB to bytes
        
        if (hostRamBytes > 0 && taskMemoryBytes > 0) {
            // Calculate utilization: (task memory / host memory) * overhead factor
            ramUtilization = ((double) taskMemoryBytes / hostRamBytes) * overheadFactor;
            // Clamp to [0.0, 1.0]
            if (ramUtilization > 1.0) ramUtilization = 1.0;
            if (ramUtilization < 0.0) ramUtilization = 0.0;
        }
        
        return new double[] { cpuUtilization, ramUtilization };
    }

    /**
     * Calculate processing time for a tuple on a VM
     * 
     * @param tuple The tuple
     * @param vm    The VM
     * @return Processing time in milliseconds
     */
    private double calculateProcessingTime(Tuple tuple, Vm vm) {
        // Calculate based on tuple size and VM capacity
        double tupleSize = tuple.getCloudletLength();
        double vmCapacity = vm.getMips();

        // Validate inputs
        if (tupleSize <= 0 || vmCapacity <= 0) {
            logger.warning(String.format(
                "[TASK-EXEC] Invalid tuple size (%.2f) or VM capacity (%.2f), using minimum time",
                tupleSize, vmCapacity));
            return CloudSim.getMinTimeBetweenEvents();
        }

        // Convert to milliseconds (assuming MIPS is in millions of instructions per second)
        double processingTime = (tupleSize / vmCapacity) * 1000;

        // Validate result
        if (processingTime <= 0 || Double.isNaN(processingTime) || Double.isInfinite(processingTime)) {
            logger.warning(String.format(
                "[TASK-EXEC] Invalid processingTime calculated (%.2f), using minimum time",
                processingTime));
            return CloudSim.getMinTimeBetweenEvents();
        }

        // Ensure minimum time between events
        if (processingTime < CloudSim.getMinTimeBetweenEvents()) {
            processingTime = CloudSim.getMinTimeBetweenEvents();
        }

        return processingTime;
    }

    /**
     * Calculate energy consumed from processing time
     * 
     * @param processingTime Processing time in milliseconds
     * @param vm             The VM
     * @return Energy consumed in Joules
     */
    private double calculateEnergyFromProcessing(double processingTime, Vm vm) {
        // Use rlTupleProcessing to get energy consumed
        // This is a placeholder - actual energy calculation should be done by iFogSim's
        // energy model
        return rlTupleProcessing.getTotalEnergyConsumed();
    }

    /**
     * Calculate cost from processing time
     * 
     * @param processingTime Processing time in milliseconds
     * @param vm             The VM
     * @return Cost
     */
    private double calculateCostFromProcessing(double processingTime, Vm vm) {
        // Use rlTupleProcessing to get cost
        // This is a placeholder - actual cost calculation should be done by iFogSim's
        // cost model
        return rlTupleProcessing.getTotalCost();
    }

    /**
     * Create a failed processing result
     * 
     * @param tuple  The tuple
     * @param reason Failure reason
     * @return Failed result
     */
    private RLTupleProcessingResult createFailedResult(Tuple tuple, String reason) {
        return new RLTupleProcessingResult(
                tuple,
                false,
                "failed: " + reason,
                0,
                0.0,
                0.0,
                "failed_processing");
    }

    /**
     * Mark a task as completed
     * 
     * @param taskInfo      The task information
     * @param success       Whether the task completed successfully
     * @param executionTime Execution time in milliseconds
     * @param resultType    Type of result
     */
    private void markTaskCompleted(ScheduledQueue.TaskInfo taskInfo, boolean success,
            long executionTime, String resultType) {
        logger.fine("Task " + taskInfo.getTaskId() + " marked as completed: " +
                (success ? "SUCCESS" : "FAILED") + " (" + resultType + ")");
    }

    /**
     * Report task completion to RL agents (both scheduler and allocator)
     * 
     * @param taskInfo      The task information
     * @param result        The processing result
     * @param executionTime Execution time in milliseconds
     */
    private void reportTaskCompletion(ScheduledQueue.TaskInfo taskInfo,
            RLTupleProcessingResult result,
            long executionTime,
            double cpuUtilization,
            double ramUtilization) {
        String taskId = taskInfo.getTaskId();
        Tuple tuple = taskInfo.getTuple();
        boolean success = result.isSuccess();

        // Determine if this is an external task (sent from cloud via allocator)
        boolean isExternalTask = isExternalTask(tuple);

        // Report to grpc-task-scheduler (for ALL tasks - both external and internal)
        if (schedulerClient != null && schedulerClient.isConnected()) {
            try {
                if (fogDevice instanceof org.patch.devices.RLFogDevice) {
                    // [DEBUG] Log reporting to scheduler
                    System.out.println(String.format(
                            "[FLOW-FOG-COMPLETE] Time: %.2f - FogNode (ID:%d) - Reporting task %s completion to SCHEDULER server (success: %s, execTime: %d ms)",
                            CloudSim.clock(), fogDevice.getId(), taskId, success, executionTime));

                    // Determine if task was cached (non-cached tasks have executionTime > 0)
                    // Cache decision is made by scheduler and stored in taskInfo.isCachedTask()
                    boolean isCached = taskInfo.isCachedTask();
                    String cacheKey = taskInfo.getCacheKey();
                    
                    // [DEBUG] Log cache decision and data before reporting
                    System.out.println(String.format(
                            "[CACHE-COMPLETION-PREP] Task=%s, isCachedTask()=%s, cacheKey=%s, executionTime=%d ms, success=%s",
                            taskId, isCached, cacheKey != null ? cacheKey : "null", executionTime, success));
                    
                    ((org.patch.devices.RLFogDevice) fogDevice).reportTaskCompletion(
                            tuple, success, executionTime, isCached, cpuUtilization, ramUtilization);

                    System.out.println(String.format(
                            "[FLOW-FOG-COMPLETE] Time: %.2f - FogNode (ID:%d) - Task %s completion successfully reported to SCHEDULER",
                            CloudSim.clock(), fogDevice.getId(), taskId));

                    logger.fine("Task completion reported to scheduler: " + taskId);
                }
            } catch (Exception e) {
                System.out.println(String.format(
                        "[FLOW-FOG-COMPLETE] Time: %.2f - FogNode (ID:%d) - ERROR reporting task %s to scheduler: %s",
                        CloudSim.clock(), fogDevice.getId(), taskId, e.getMessage()));
                logger.log(Level.WARNING, "Failed to report task completion to scheduler: " + taskId, e);
            }
        }

        // Report to go-grpc-server allocator (ONLY for external tasks)
        if (isExternalTask && fogDevice instanceof org.patch.devices.RLFogDevice) {
            try {
                // [DEBUG] Log reporting to allocator for external tasks
                System.out.println(String.format(
                        "[FLOW-FOG-COMPLETE] Time: %.2f - FogNode (ID:%d) - Reporting EXTERNAL task %s completion to ALLOCATOR via cloud (success: %s, execTime: %d ms)",
                        CloudSim.clock(), fogDevice.getId(), taskId, success, executionTime));

                // For external tasks, we need to notify the cloud device to report to allocator
                // The cloud device will handle the actual gRPC call to go-grpc-server
                org.patch.devices.RLFogDevice fogDeviceImpl = (org.patch.devices.RLFogDevice) fogDevice;

                // Send event to cloud device (ID 3 is cloud in RL3FogSimulation)
                int cloudId = 3; // Cloud device ID
                CloudSim.send(fogDeviceImpl.getId(), cloudId, 0, ExtendedFogEvents.ALLOC_OUTCOME_REPORT,
                        new Object[] { tuple, success, executionTime });

                System.out.println(String.format(
                        "[FLOW-FOG-COMPLETE] Time: %.2f - FogNode (ID:%d) - EXTERNAL task %s completion event sent to cloud (ID:%d) for allocator reporting",
                        CloudSim.clock(), fogDevice.getId(), taskId, cloudId));

                logger.fine("External task completion sent to cloud for allocator reporting: " + taskId);
            } catch (Exception e) {
                System.out.println(String.format(
                        "[FLOW-FOG-COMPLETE] Time: %.2f - FogNode (ID:%d) - ERROR reporting EXTERNAL task %s to allocator: %s",
                        CloudSim.clock(), fogDevice.getId(), taskId, e.getMessage()));
                logger.log(Level.WARNING, "Failed to report external task completion to cloud: " + taskId, e);
            }
        } else if (!isExternalTask) {
            // [DEBUG] Log that we're NOT reporting to allocator for internal tasks
            System.out.println(String.format(
                    "[FLOW-FOG-COMPLETE] Time: %.2f - FogNode (ID:%d) - INTERNAL task %s - NOT reporting to allocator (only scheduler)",
                    CloudSim.clock(), fogDevice.getId(), taskId));
        }
    }

    /**
     * Check if task is external (came from cloud via allocator)
     * 
     * @param tuple The task tuple
     * @return true if external, false if internal
     */
    private boolean isExternalTask(Tuple tuple) {
        // External tasks are identified by their tuple type
        String tupleType = tuple.getTupleType();
        return tupleType != null && tupleType.equals("external_task");
    }

    /**
     * Store task result in cache (deprecated - now using scheduler's cache key)
     * 
     * @param taskId The task ID
     * @param result The processing result
     * @deprecated Use cacheManager.storeInCache() with scheduler's cache key
     *             directly
     */
    @Deprecated
    private void storeTaskResult(String taskId, RLTupleProcessingResult result) {
        if (cacheEnabled && cacheManager != null) {
            cacheManager.storeInCache(taskId, result);
            logger.fine("Task result stored in cache: " + taskId);
        }
    }

    /**
     * Update execution metrics
     * 
     * @param executionTime  Execution time
     * @param energyConsumed Energy consumed
     * @param cost           Cost
     * @param success        Whether execution was successful
     */
    private void updateExecutionMetrics(long executionTime, double energyConsumed,
            double cost, boolean success) {
        totalTasksExecuted++;
        totalExecutionTime += executionTime;
        totalEnergyConsumed += energyConsumed;
        totalCost += cost;

        if (success) {
            successfulExecutions++;
        } else {
            failedExecutions++;
        }
    }

    /**
     * Get execution statistics
     * 
     * @return Map of execution statistics
     */
    public Map<String, Object> getExecutionStatistics() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("totalTasksExecuted", totalTasksExecuted);
        stats.put("successfulExecutions", successfulExecutions);
        stats.put("failedExecutions", failedExecutions);
        stats.put("successRate", totalTasksExecuted > 0 ? (double) successfulExecutions / totalTasksExecuted : 0.0);
        stats.put("totalExecutionTime", totalExecutionTime);
        stats.put("averageExecutionTime",
                totalTasksExecuted > 0 ? (double) totalExecutionTime / totalTasksExecuted : 0.0);
        stats.put("totalEnergyConsumed", totalEnergyConsumed);
        stats.put("averageEnergyPerTask", totalTasksExecuted > 0 ? totalEnergyConsumed / totalTasksExecuted : 0.0);
        stats.put("totalCost", totalCost);
        stats.put("averageCostPerTask", totalTasksExecuted > 0 ? totalCost / totalTasksExecuted : 0.0);
        stats.put("activeTasks", activeTasks.size());
        stats.put("queueSize", scheduledQueue.size());

        return stats;
    }

    /**
     * Get active tasks
     * 
     * @return Map of active task states
     */
    public Map<String, TaskExecutionState> getActiveTasks() {
        return new HashMap<>(activeTasks);
    }

    /**
     * Get the cache manager instance
     * Used by RLFogDevice to store execution results
     * 
     * @return TaskCacheManager instance or null if not available
     */
    public TaskCacheManager getCacheManager() {
        return cacheManager;
    }

    /**
     * Check if there are active tasks
     * 
     * @return true if there are active tasks
     */
    public boolean hasActiveTasks() {
        return !activeTasks.isEmpty();
    }

    /**
     * Enable or disable RL processing
     * 
     * @param enabled Whether RL is enabled
     */
    public void setRLEnabled(boolean enabled) {
        rlTupleProcessing.enableRL();
        logger.info("RL processing " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Enable or disable caching
     * 
     * @param enabled Whether caching is enabled
     */
    public void setCacheEnabled(boolean enabled) {
        this.cacheEnabled = enabled;
        logger.info("Caching " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Get the RL tuple processing instance
     * 
     * @return RLTupleProcessing instance
     */
    public RLTupleProcessing getRLTupleProcessing() {
        return rlTupleProcessing;
    }

    /**
     * Task execution state tracking
     */
    public static class TaskExecutionState {
        private final ScheduledQueue.TaskInfo taskInfo;
        private final long startTime;
        private boolean completed = false;
        private long executionTime = 0;
        private double energyConsumed = 0.0;
        private double cost = 0.0;
        private boolean success = false;
        // Captured resource utilization during task execution (before resources are
        // released)
        private double capturedCpuUtilization = 0.0;
        private double capturedRamUtilization = 0.0;
        private boolean utilizationCaptured = false;
        // Flag to prevent duplicate completion reports
        private boolean reportedCompletion = false;
        // Flag to indicate if this is a cached task (skips execution)
        private boolean isCached = false;

        public TaskExecutionState(ScheduledQueue.TaskInfo taskInfo, long startTime) {
            this.taskInfo = taskInfo;
            this.startTime = startTime;
        }

        // Getters and setters
        public ScheduledQueue.TaskInfo getTaskInfo() {
            return taskInfo;
        }

        public long getStartTime() {
            return startTime;
        }

        public boolean isCompleted() {
            return completed;
        }

        public void setCompleted(boolean completed) {
            this.completed = completed;
        }

        public long getExecutionTime() {
            return executionTime;
        }

        public void setExecutionTime(long executionTime) {
            this.executionTime = executionTime;
        }

        public double getEnergyConsumed() {
            return energyConsumed;
        }

        public void setEnergyConsumed(double energyConsumed) {
            this.energyConsumed = energyConsumed;
        }

        public double getCost() {
            return cost;
        }

        public void setCost(double cost) {
            this.cost = cost;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public double getCapturedCpuUtilization() {
            return capturedCpuUtilization;
        }

        public void setCapturedCpuUtilization(double capturedCpuUtilization) {
            this.capturedCpuUtilization = capturedCpuUtilization;
        }

        public double getCapturedRamUtilization() {
            return capturedRamUtilization;
        }

        public void setCapturedRamUtilization(double capturedRamUtilization) {
            this.capturedRamUtilization = capturedRamUtilization;
        }

        public boolean isUtilizationCaptured() {
            return utilizationCaptured;
        }

        public void setUtilizationCaptured(boolean utilizationCaptured) {
            this.utilizationCaptured = utilizationCaptured;
        }

        public boolean isReportedCompletion() {
            return reportedCompletion;
        }

        public void setReportedCompletion(boolean reportedCompletion) {
            this.reportedCompletion = reportedCompletion;
        }

        public boolean isCached() {
            return isCached;
        }

        public void setCached(boolean isCached) {
            this.isCached = isCached;
        }
    }
}
