package org.patch.processing;

import org.fog.entities.Tuple;
import org.fog.entities.FogDevice;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.models.ScheduledQueue;
import org.patch.client.SchedulerClient;
import org.patch.client.AllocationClient;
import org.patch.utils.TaskCacheManager;
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

        try {
            if (scheduledQueue == null) {
                logger.warning("Scheduled queue is null, cannot process tasks");
                return false;
            }

            if (scheduledQueue.isEmpty()) {
                return false;
            }

            // Get the next task from the head of the queue
            taskInfo = scheduledQueue.getNextTask();
            if (taskInfo == null) {
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error checking scheduled queue state", e);
            return false;
        }

        String taskId = taskInfo.getTaskId();
        logger.fine("Processing task: " + taskId + " on device: " + fogDevice.getName());

        // Check cache first if enabled
        if (cacheEnabled) {
            TaskCacheManager.CacheResult cacheResult = cacheManager.checkCache(taskId);
            if (cacheResult == TaskCacheManager.CacheResult.HIT_VALID) {
                return handleCachedTask(taskInfo);
            } else if (cacheResult == TaskCacheManager.CacheResult.HIT_INVALID) {
                cacheManager.invalidateCache(taskId);
            }
        }

        // Execute the task
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
        logger.info("Handling cached task: " + taskId);

        try {
            // Remove from scheduled queue
            scheduledQueue.removeTask(taskId);

            // Mark as completed with cached result
            markTaskCompleted(taskInfo, true, 0, "cached_result");

            // Update metrics
            totalTasksExecuted++;
            successfulExecutions++;

            logger.info("Cached task " + taskId + " completed successfully");
            return true;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling cached task " + taskId, e);
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

        logger.fine("Executing task: " + taskId + " with tuple: " + tuple.getCloudletId());

        try {
            // Record start time
            double startTime = CloudSim.clock();
            taskStartTimes.put(taskId, (long) startTime);

            // Create execution state
            TaskExecutionState state = new TaskExecutionState(taskInfo, (long) startTime);
            activeTasks.put(taskId, state);

            // Remove from scheduled queue
            scheduledQueue.removeTask(taskId);

            // Process tuple using RL-aware processing
            RLTupleProcessingResult result = processTupleWithRL(tuple, taskInfo);

            // Calculate execution metrics
            long executionTime = (long) (CloudSim.clock() - startTime);
            double energyConsumed = rlTupleProcessing.getTotalEnergyConsumed();
            double cost = rlTupleProcessing.getTotalCost();

            // Update execution state
            state.setCompleted(true);
            state.setExecutionTime(executionTime);
            state.setEnergyConsumed(energyConsumed);
            state.setCost(cost);
            state.setSuccess(result.isSuccess());

            // Update metrics
            updateExecutionMetrics(executionTime, energyConsumed, cost, result.isSuccess());

            // Report task completion to RL agents
            reportTaskCompletion(taskInfo, result, executionTime);

            // Store in cache if needed
            if (taskInfo.isCachedTask() && cacheEnabled) {
                storeTaskResult(taskId, result);
            }

            // Clean up
            activeTasks.remove(taskId);
            taskStartTimes.remove(taskId);

            logger.info("Task " + taskId + " executed successfully in " + executionTime + "ms");
            return true;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error executing task " + taskId, e);

            // Mark as failed
            markTaskCompleted(taskInfo, false, 0, "execution_error");

            // Update metrics
            totalTasksExecuted++;
            failedExecutions++;

            // Clean up
            activeTasks.remove(taskId);
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
        // Find the target VM for this task using module name from tuple (iFogSim
        // compatible)
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

        // Use RL tuple processing
        RLTupleProcessingResult result = rlTupleProcessing.processTuple(tuple, fogDevice, fogDevice);

        // If RL processing failed, fall back to normal iFogSim processing
        if (!result.isSuccess()) {
            logger.warning("RL processing failed, falling back to normal processing for task: " + taskInfo.getTaskId());
            result = processTupleNormally(tuple, targetVm);
        }

        return result;
    }

    /**
     * Process tuple using normal iFogSim mechanisms
     * 
     * @param tuple    The tuple to process
     * @param targetVm The target VM
     * @return Processing result
     */
    private RLTupleProcessingResult processTupleNormally(Tuple tuple, Vm targetVm) {
        double startTime = CloudSim.clock();

        try {
            // Use iFogSim's core tuple processing mechanisms
            // This integrates with iFogSim's cloudlet scheduler and VM processing

            // Set VM ID for the tuple (required by iFogSim)
            tuple.setVmId(targetVm.getId());

            // Use iFogSim's TimeKeeper for proper timing
            TimeKeeper.getInstance().tupleStartedExecution(tuple);

            // Submit tuple as cloudlet to VM's scheduler (iFogSim core mechanism)
            targetVm.getCloudletScheduler().cloudletSubmit(tuple);

            // Update allocated MIPS (iFogSim core mechanism)
            fogDevice.getHost().getVmScheduler().deallocatePesForVm(targetVm);
            fogDevice.getHost().getVmScheduler().allocatePesForVm(targetVm,
                    java.util.Arrays.asList((double) fogDevice.getHost().getTotalMips()));

            // Simulate processing time based on iFogSim's cloudlet scheduler
            double processingTime = calculateProcessingTime(tuple, targetVm);

            // Wait for actual processing (iFogSim handles this internally)
            Thread.sleep((long) processingTime);

            // Mark tuple as completed using iFogSim's TimeKeeper
            TimeKeeper.getInstance().tupleEndedExecution(tuple);

            long executionTime = (long) (CloudSim.clock() - startTime);
            double energyConsumed = rlTupleProcessing.getTotalEnergyConsumed();
            double cost = rlTupleProcessing.getTotalCost();

            return new RLTupleProcessingResult(
                    tuple,
                    true,
                    "ifogsim_normal_processing",
                    executionTime,
                    energyConsumed,
                    cost,
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

        // Convert to milliseconds (assuming MIPS is in millions of instructions per
        // second)
        return (tupleSize / vmCapacity) * 1000;
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
            long executionTime) {
        String taskId = taskInfo.getTaskId();
        Tuple tuple = taskInfo.getTuple();
        boolean success = result.isSuccess();

        // Report to grpc-task-scheduler (if available)
        if (schedulerClient != null && schedulerClient.isConnected()) {
            try {
                // Use the existing reportTaskCompletion method from RLFogDevice
                if (fogDevice instanceof org.patch.devices.RLFogDevice) {
                    ((org.patch.devices.RLFogDevice) fogDevice).reportTaskCompletion(
                            tuple, success, executionTime);
                    logger.fine("Task completion reported to scheduler: " + taskId);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to report task completion to scheduler: " + taskId, e);
            }
        }

        // Report to go-grpc-server allocator (if available)
        if (fogDevice instanceof org.patch.devices.RLCloudDevice) {
            try {
                org.patch.devices.RLCloudDevice cloudDevice = (org.patch.devices.RLCloudDevice) fogDevice;
                cloudDevice.reportTaskOutcome(tuple, success, executionTime);
                logger.fine("Task completion reported to allocator: " + taskId);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to report task completion to allocator: " + taskId, e);
            }
        }
    }

    /**
     * Store task result in cache
     * 
     * @param taskId The task ID
     * @param result The processing result
     */
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
    }
}
