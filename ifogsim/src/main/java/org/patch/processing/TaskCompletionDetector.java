package org.patch.processing;

import org.fog.entities.Tuple;
import org.fog.entities.FogDevice;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.patch.client.SchedulerClient;
import org.patch.models.ScheduledQueue;
import org.patch.utils.TaskCacheManager;
import org.patch.utils.ExtendedFogEvents;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Task Completion Detector for RL-aware task completion reporting
 * 
 * This class monitors task execution and detects when tasks complete,
 * then reports completion status to the gRPC scheduler for delayed reward
 * calculation and task removal from scheduled queues.
 * 
 * Key Features:
 * - Monitors task execution progress
 * - Detects task completion (success/failure)
 * - Reports completion to gRPC scheduler
 * - Handles delayed reward calculation
 * - Prevents duplicate task processing
 * - Integrates with iFogSim's event system
 * 
 * @author Younes Shafiee
 */
public class TaskCompletionDetector {
    private static final Logger logger = Logger.getLogger(TaskCompletionDetector.class.getName());

    // Core components
    private final FogDevice fogDevice;
    private final SchedulerClient schedulerClient;
    private final TaskCacheManager cacheManager;

    // Task tracking
    private final Map<String, TaskExecutionState> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> taskStartTimes = new ConcurrentHashMap<>();

    // Statistics - delegate to existing components to avoid duplication
    private long totalTasksCompleted = 0;
    private long totalTasksFailed = 0;

    // Configuration
    private final long completionCheckInterval = 1000; // 1 second
    private final long maxExecutionTime = 30000; // 30 seconds timeout
    private final boolean enableDelayedRewards = true;

    /**
     * Constructor
     * 
     * @param fogDevice       The fog device this detector monitors
     * @param schedulerClient The gRPC client for reporting completions
     * @param cacheManager    The cache manager for task results
     */
    public TaskCompletionDetector(FogDevice fogDevice, SchedulerClient schedulerClient,
            TaskCacheManager cacheManager) {
        this.fogDevice = fogDevice;
        this.schedulerClient = schedulerClient;
        this.cacheManager = cacheManager;

        logger.info("TaskCompletionDetector initialized for device: " + fogDevice.getId());
    }

    /**
     * Start monitoring task execution
     */
    public void startMonitoring() {
        logger.info("Starting task completion monitoring for device: " + fogDevice.getId());

        // Schedule periodic completion checks
        scheduleCompletionCheck();
    }

    /**
     * Stop monitoring task execution
     */
    public void stopMonitoring() {
        logger.info("Stopping task completion monitoring for device: " + fogDevice.getId());

        // Clear active tasks
        activeTasks.clear();
        taskStartTimes.clear();
    }

    /**
     * Register a task for completion monitoring
     * 
     * @param taskId The task ID
     * @param tuple  The tuple being executed
     * @param vmId   The VM ID where task is executing
     */
    public void registerTask(String taskId, Tuple tuple, int vmId) {
        try {
            if (taskId == null || taskId.trim().isEmpty()) {
                logger.warning("Cannot register task with null or empty task ID");
                return;
            }

            if (tuple == null) {
                logger.warning("Cannot register task with null tuple for task ID: " + taskId);
                return;
            }

            if (vmId < 0) {
                logger.warning("Cannot register task with invalid VM ID: " + vmId + " for task ID: " + taskId);
                return;
            }

            TaskExecutionState state = new TaskExecutionState(taskId, tuple, vmId, CloudSim.clock());
            activeTasks.put(taskId, state);
            taskStartTimes.put(taskId, (long) CloudSim.clock());

            logger.fine("Registered task for monitoring: " + taskId + " on VM: " + vmId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error registering task for monitoring: " + taskId, e);
        }
    }

    /**
     * Mark a task as completed
     * 
     * @param taskId         The task ID
     * @param success        Whether the task completed successfully
     * @param executionTime  The execution time in milliseconds
     * @param energyConsumed The energy consumed
     * @param cost           The execution cost
     */
    public void markTaskCompleted(String taskId, boolean success, long executionTime,
            long energyConsumed, double cost) {
        TaskExecutionState state = activeTasks.remove(taskId);
        taskStartTimes.remove(taskId);

        if (state == null) {
            logger.warning("Attempted to complete unregistered task: " + taskId);
            return;
        }

        // Update statistics
        if (success) {
            totalTasksCompleted++;
        } else {
            totalTasksFailed++;
        }

        // Note: Energy and cost statistics are tracked by RLTupleProcessing and
        // TaskExecutionEngine
        // to avoid duplication. This class only tracks completion counts.

        // Report completion to scheduler
        if (enableDelayedRewards && schedulerClient != null) {
            reportTaskCompletion(taskId, success, executionTime, energyConsumed, cost);
        }

        // Store result in cache
        if (cacheManager != null) {
            storeTaskResult(taskId, success, executionTime, energyConsumed, cost);
        }

        logger.fine("Task completed: " + taskId + " (success: " + success +
                ", time: " + executionTime + "ms)");
    }

    /**
     * Check for completed tasks
     */
    public void checkTaskCompletions() {
        double currentTime = CloudSim.clock();

        // Check for timeout tasks
        for (Map.Entry<String, TaskExecutionState> entry : activeTasks.entrySet()) {
            String taskId = entry.getKey();
            TaskExecutionState state = entry.getValue();

            // Check if task has timed out
            if (currentTime - state.getStartTime() > maxExecutionTime) {
                logger.warning("Task timed out: " + taskId);
                markTaskCompleted(taskId, false, maxExecutionTime, 0, 0.0);
            }
        }

        // Schedule next check
        scheduleCompletionCheck();
    }

    /**
     * Schedule the next completion check
     */
    private void scheduleCompletionCheck() {
        if (fogDevice != null) {
            fogDevice.schedule(fogDevice.getId(), completionCheckInterval,
                    ExtendedFogEvents.TASK_COMPLETION_CHECK);
        }
    }

    /**
     * Report task completion to gRPC scheduler
     * 
     * @param taskId         The task ID
     * @param success        Whether the task completed successfully
     * @param executionTime  The execution time
     * @param energyConsumed The energy consumed
     * @param cost           The execution cost
     */
    private void reportTaskCompletion(String taskId, boolean success, long executionTime,
            long energyConsumed, double cost) {
        try {
            // Create completion report
            org.patch.proto.IfogsimScheduler.CompletedTask completedTask = org.patch.proto.IfogsimScheduler.CompletedTask
                    .newBuilder()
                    .setTaskId(taskId)
                    .setAssignedNodeId(String.valueOf(fogDevice.getId()))
                    .setActualExecutionTimeMs(executionTime)
                    .setActualLatencyMs(executionTime) // Using execution time as latency
                    .setEnergyConsumed(energyConsumed)
                    .setDeadlineMet(success)
                    .setStartTime((long) CloudSim.clock())
                    .setCompletionTime((long) CloudSim.clock())
                    .build();

            org.patch.proto.IfogsimScheduler.TaskCompletionReport request = org.patch.proto.IfogsimScheduler.TaskCompletionReport
                    .newBuilder()
                    .setTaskId(taskId)
                    .addTasks(completedTask)
                    .setCompletionTimestamp((long) CloudSim.clock())
                    .build();

            // Send completion report
            schedulerClient.reportTaskCompletion(request);

            logger.fine("Reported task completion: " + taskId + " to scheduler");

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to report task completion: " + taskId, e);
        }
    }

    /**
     * Store task result in cache
     * 
     * @param taskId         The task ID
     * @param success        Whether the task completed successfully
     * @param executionTime  The execution time
     * @param energyConsumed The energy consumed
     * @param cost           The execution cost
     */
    private void storeTaskResult(String taskId, boolean success, long executionTime,
            long energyConsumed, double cost) {
        try {
            Map<String, Object> result = new ConcurrentHashMap<>();
            result.put("taskId", taskId);
            result.put("success", success);
            result.put("executionTime", executionTime);
            result.put("energyConsumed", energyConsumed);
            result.put("cost", cost);
            result.put("timestamp", CloudSim.clock());
            result.put("deviceId", fogDevice.getId());

            cacheManager.storeInCache(taskId, result);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to store task result in cache: " + taskId, e);
        }
    }

    /**
     * Get completion statistics
     * 
     * @return Map of completion statistics
     */
    public Map<String, Object> getCompletionStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();

        stats.put("totalTasksCompleted", totalTasksCompleted);
        stats.put("totalTasksFailed", totalTasksFailed);
        stats.put("activeTasks", activeTasks.size());
        stats.put("successRate",
                totalTasksCompleted + totalTasksFailed > 0
                        ? (double) totalTasksCompleted / (totalTasksCompleted + totalTasksFailed)
                        : 0.0);

        // Note: For detailed execution metrics, use
        // TaskExecutionEngine.getExecutionStatistics()
        // and RLTupleProcessing.getTotalEnergyConsumed()/getTotalCost() to avoid
        // duplication

        return stats;
    }

    /**
     * Reset statistics
     */
    public void resetStatistics() {
        totalTasksCompleted = 0;
        totalTasksFailed = 0;

        logger.info("Reset completion statistics for device: " + fogDevice.getId());
    }

    /**
     * Get active task count
     * 
     * @return Number of active tasks
     */
    public int getActiveTaskCount() {
        return activeTasks.size();
    }

    /**
     * Check if a task is being monitored
     * 
     * @param taskId The task ID
     * @return True if task is being monitored
     */
    public boolean isTaskMonitored(String taskId) {
        return activeTasks.containsKey(taskId);
    }

    /**
     * Inner class representing task execution state
     */
    private static class TaskExecutionState {
        private final String taskId;
        private final Tuple tuple;
        private final int vmId;
        private final double startTime;

        public TaskExecutionState(String taskId, Tuple tuple, int vmId, double startTime) {
            this.taskId = taskId;
            this.tuple = tuple;
            this.vmId = vmId;
            this.startTime = startTime;
        }

        public String getTaskId() {
            return taskId;
        }

        public Tuple getTuple() {
            return tuple;
        }

        public int getVmId() {
            return vmId;
        }

        public double getStartTime() {
            return startTime;
        }
    }
}
