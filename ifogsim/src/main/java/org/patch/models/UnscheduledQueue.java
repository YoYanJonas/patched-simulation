package org.patch.models;

import org.fog.entities.Tuple;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Queue for managing tasks waiting for scheduler decisions
 * Tasks are added here when they arrive at the fog node
 * and removed when scheduler processes them
 */
public class UnscheduledQueue {
    private static final Logger logger = Logger.getLogger(UnscheduledQueue.class.getName());

    // Thread-safe collections for concurrent access
    private final List<TaskInfo> tasks = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    // Statistics
    private int totalTasksAdded = 0;
    private int totalTasksRemoved = 0;
    private double currentTime = 0;

    /**
     * Add a task to the unscheduled queue
     * 
     * @param tuple    The tuple to be processed
     * @param moduleId The module ID that should process this tuple
     */
    public void addTask(Tuple tuple, int moduleId) {
        TaskInfo taskInfo = new TaskInfo(tuple, moduleId);
        tasks.add(taskInfo);
        taskMap.put(String.valueOf(tuple.getCloudletId()), taskInfo);
        totalTasksAdded++;

        logger.fine("Task " + tuple.getCloudletId() + " added to unscheduled queue (total: " + tasks.size() + ")");
    }

    /**
     * Add a task with current simulation time
     * 
     * @param tuple          The tuple to be processed
     * @param moduleId       The module ID that should process this tuple
     * @param simulationTime Current simulation time
     */
    public void addTask(Tuple tuple, int moduleId, double simulationTime) {
        currentTime = simulationTime;
        addTask(tuple, moduleId);
    }

    /**
     * Get all tasks in the queue
     * 
     * @return List of TaskInfo objects
     */
    public List<TaskInfo> getAllTasks() {
        return new ArrayList<>(tasks);
    }

    /**
     * Remove a specific task from the queue
     * 
     * @param taskId The task ID to remove
     * @return The removed TaskInfo object, or null if not found
     */
    public TaskInfo removeTask(String taskId) {
        TaskInfo taskInfo = taskMap.remove(String.valueOf(taskId));
        if (taskInfo != null) {
            tasks.remove(taskInfo);
            totalTasksRemoved++;
            logger.fine("Task " + taskId + " removed from unscheduled queue");
        }
        return taskInfo;
    }

    /**
     * Remove a task by index
     * 
     * @param index The index of the task to remove
     * @return The removed TaskInfo object
     */
    public TaskInfo removeTask(int index) {
        if (index < 0 || index >= tasks.size()) {
            return null;
        }

        TaskInfo taskInfo = tasks.remove(index);
        taskMap.remove(String.valueOf(taskInfo.getTuple().getCloudletId()));
        totalTasksRemoved++;

        logger.fine("Task " + taskInfo.getTuple().getCloudletId() + " removed from unscheduled queue (index: " + index
                + ")");
        return taskInfo;
    }

    /**
     * Get a task by index without removing it
     * 
     * @param index The index of the task
     * @return The TaskInfo object, or null if index is invalid
     */
    public TaskInfo getTask(int index) {
        if (index < 0 || index >= tasks.size()) {
            return null;
        }
        return tasks.get(index);
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
        logger.info("Unscheduled queue cleared");
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
        stats.put("totalTasksRemoved", totalTasksRemoved);
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
     * Inner class to hold task information with entry time tracking
     */
    public static class TaskInfo {
        private final Tuple tuple;
        private final int moduleId;
        private final double entryTime;

        public TaskInfo(Tuple tuple, int moduleId) {
            this.tuple = tuple;
            this.moduleId = moduleId;
            this.entryTime = System.currentTimeMillis(); // Use current time as entry time
        }

        public TaskInfo(Tuple tuple, int moduleId, double simulationTime) {
            this.tuple = tuple;
            this.moduleId = moduleId;
            this.entryTime = simulationTime;
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
            return String.valueOf(tuple.getCloudletId());
        }
    }
}
