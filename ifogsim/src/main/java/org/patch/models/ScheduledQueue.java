package org.patch.models;

import org.fog.entities.Tuple;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Queue for managing tasks that have been scheduled by the scheduler
 * Tasks are processed from the head of this queue in FIFO order
 */
public class ScheduledQueue {
    private static final Logger logger = Logger.getLogger(ScheduledQueue.class.getName());

    // Thread-safe collections for concurrent access
    private final List<TaskInfo> tasks = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    // Statistics
    private int totalTasksAdded = 0;
    private int totalTasksProcessed = 0;
    private double currentTime = 0;

    /**
     * Add a scheduled task to the queue
     * 
     * @param taskInfo The task information from scheduler
     */
    public void addTask(TaskInfo taskInfo) {
        tasks.add(taskInfo);
        taskMap.put(taskInfo.getTaskId(), taskInfo);
        totalTasksAdded++;

        logger.fine("Task " + taskInfo.getTaskId() + " added to scheduled queue (total: " + tasks.size() + ")");
    }

    /**
     * Get the next task from the head of the queue without removing it
     * 
     * @return The next TaskInfo object, or null if queue is empty
     */
    public TaskInfo getNextTask() {
        if (tasks.isEmpty()) {
            return null;
        }
        return tasks.get(0);
    }

    /**
     * Remove and return the next task from the head of the queue
     * 
     * @return The next TaskInfo object, or null if queue is empty
     */
    public TaskInfo removeNextTask() {
        if (tasks.isEmpty()) {
            return null;
        }

        TaskInfo taskInfo = tasks.remove(0);
        taskMap.remove(taskInfo.getTaskId());
        totalTasksProcessed++;

        logger.fine("Task " + taskInfo.getTaskId() + " removed from scheduled queue (processed)");
        return taskInfo;
    }

    /**
     * Remove a specific task from the queue
     * 
     * @param taskId The task ID to remove
     * @return The removed TaskInfo object, or null if not found
     */
    public TaskInfo removeTask(String taskId) {
        TaskInfo taskInfo = taskMap.remove(taskId);
        if (taskInfo != null) {
            tasks.remove(taskInfo);
            totalTasksProcessed++;
            logger.fine("Task " + taskId + " removed from scheduled queue");
        }
        return taskInfo;
    }

    /**
     * Get a task by ID without removing it
     * 
     * @param taskId The task ID
     * @return The TaskInfo object, or null if not found
     */
    public TaskInfo getTask(String taskId) {
        return taskMap.get(taskId);
    }

    /**
     * Check if the queue is empty
     * 
     * @return true if the queue is empty
     */
    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /**
     * Get the number of tasks in the queue
     * 
     * @return Number of tasks
     */
    public int size() {
        return tasks.size();
    }

    /**
     * Clear all tasks from the queue
     */
    public void clear() {
        tasks.clear();
        taskMap.clear();
        logger.info("Scheduled queue cleared");
    }

    /**
     * Update the current simulation time
     * 
     * @param simulationTime Current simulation time
     */
    public void updateTime(double simulationTime) {
        this.currentTime = simulationTime;
    }

    /**
     * Get queue statistics
     * 
     * @return Map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("queueSize", tasks.size());
        stats.put("totalTasksAdded", totalTasksAdded);
        stats.put("totalTasksProcessed", totalTasksProcessed);
        stats.put("currentTime", currentTime);

        // Calculate average wait time for tasks in queue
        List<Double> waitTimes = new ArrayList<>();
        for (TaskInfo task : tasks) {
            double waitTime = currentTime - task.getEntryTime();
            waitTimes.add(waitTime);
        }
        stats.put("currentWaitTimes", waitTimes);

        return stats;
    }

    /**
     * Inner class to hold scheduled task information
     */
    public static class TaskInfo {
        private final Tuple tuple;
        private final int moduleId;
        private final double entryTime;
        private final String taskId;
        private final String assignedNodeId;
        private final long estimatedStartTime;
        private final long estimatedCompletionTime;
        private final boolean isCachedTask;
        private final String cacheKey;

        public TaskInfo(Tuple tuple, int moduleId, String assignedNodeId,
                long estimatedStartTime, long estimatedCompletionTime,
                boolean isCachedTask, String cacheKey) {
            this.tuple = tuple;
            this.moduleId = moduleId;
            this.entryTime = System.currentTimeMillis();
            this.taskId = String.valueOf(tuple.getCloudletId());
            this.assignedNodeId = assignedNodeId;
            this.estimatedStartTime = estimatedStartTime;
            this.estimatedCompletionTime = estimatedCompletionTime;
            this.isCachedTask = isCachedTask;
            this.cacheKey = cacheKey;
        }

        public TaskInfo(Tuple tuple, int moduleId, double simulationTime, String assignedNodeId,
                long estimatedStartTime, long estimatedCompletionTime,
                boolean isCachedTask, String cacheKey) {
            this.tuple = tuple;
            this.moduleId = moduleId;
            this.entryTime = simulationTime;
            this.taskId = String.valueOf(tuple.getCloudletId());
            this.assignedNodeId = assignedNodeId;
            this.estimatedStartTime = estimatedStartTime;
            this.estimatedCompletionTime = estimatedCompletionTime;
            this.isCachedTask = isCachedTask;
            this.cacheKey = cacheKey;
        }

        public Tuple getTuple() {
            return tuple;
        }

        public int getModuleId() {
            return moduleId;
        }

        public double getEntryTime() {
            return entryTime;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getAssignedNodeId() {
            return assignedNodeId;
        }

        public long getEstimatedStartTime() {
            return estimatedStartTime;
        }

        public long getEstimatedCompletionTime() {
            return estimatedCompletionTime;
        }

        public boolean isCachedTask() {
            return isCachedTask;
        }

        public String getCacheKey() {
            return cacheKey;
        }
    }
}
