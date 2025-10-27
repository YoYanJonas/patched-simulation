package org.patch.models;

import org.fog.entities.Tuple;
import java.util.*;
// import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * Queue for managing tasks to be scheduled by RL
 * Enhanced with statistics and priority-based scheduling
 */
public class TaskQueue {
    private static final Logger logger = Logger.getLogger(TaskQueue.class.getName());

    // Original data structure - maintain for backward compatibility
    private final List<TaskInfo> tasks;

    // Enhanced data structures for advanced features
    private Map<Integer, Integer> taskPriorities = new HashMap<>();
    private Map<Integer, Double> taskEntryTimes = new HashMap<>();

    // Flag to track if a task is currently being processed (from original
    // implementation)
    private boolean processing;

    // Statistics
    private int totalTasksProcessed = 0;
    private int totalTasksAdded = 0;
    private double totalWaitTime = 0;
    private double maxWaitTime = 0;

    // Current simulation time
    private double currentTime = 0;

    /**
     * Constructor
     */
    public TaskQueue() {
        this.tasks = new ArrayList<>();
        this.processing = false;
    }

    /**
     * Add a task to the queue (original API)
     * 
     * @param tuple    The tuple to be processed
     * @param moduleId The module ID that should process this tuple
     */
    public void addTask(Tuple tuple, int moduleId) {
        tasks.add(new TaskInfo(tuple, moduleId));
        taskPriorities.put(tuple.getCloudletId(), 0); // Default priority
        taskEntryTimes.put(tuple.getCloudletId(), currentTime);
        totalTasksAdded++;

        logger.fine("Task " + tuple.getCloudletId() + " added to queue at time " + currentTime);
    }

    /**
     * Add a task to the queue with current simulation time
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
     * Get a task from the queue without removing it (original API)
     * 
     * @param index Index of the task
     * @return TaskInfo object
     */
    public TaskInfo getTask(int index) {
        if (index < 0 || index >= tasks.size()) {
            throw new IndexOutOfBoundsException("Invalid task index: " + index);
        }
        return tasks.get(index);
    }

    /**
     * Remove a task from the queue (original API)
     * 
     * @param index Index of the task to remove
     * @return The removed TaskInfo object
     */
    public TaskInfo removeTask(int index) {
        if (index < 0 || index >= tasks.size()) {
            throw new IndexOutOfBoundsException("Invalid task index: " + index);
        }

        TaskInfo taskInfo = tasks.remove(index);
        Tuple tuple = taskInfo.getTuple();
        int taskId = tuple.getCloudletId();

        // Update statistics
        if (taskEntryTimes.containsKey(taskId)) {
            double waitTime = currentTime - taskEntryTimes.get(taskId);
            totalTasksProcessed++;
            totalWaitTime += waitTime;
            maxWaitTime = Math.max(maxWaitTime, waitTime);

            // Clean up
            taskEntryTimes.remove(taskId);
            taskPriorities.remove(taskId);
        }

        return taskInfo;
    }

    /**
     * Check if the queue is empty (original API)
     * 
     * @return true if the queue is empty
     */
    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /**
     * Get the number of tasks in the queue (original API)
     * 
     * @return Number of tasks
     */
    public int size() {
        return tasks.size();
    }

    /**
     * Check if a task is currently being processed (original API)
     * 
     * @return true if a task is being processed
     */
    public boolean isProcessing() {
        return processing;
    }

    /**
     * Set the processing flag (original API)
     * 
     * @param processing true if a task is being processed
     */
    public void setProcessing(boolean processing) {
        this.processing = processing;
    }

    /**
     * Clear all tasks from the queue (original API)
     */
    public void clear() {
        tasks.clear();
        taskPriorities.clear();
        taskEntryTimes.clear();
    }

    /**
     * Get all tasks in the queue (original API)
     * 
     * @return List of TaskInfo objects
     */
    public List<TaskInfo> getAllTasks() {
        return new ArrayList<>(tasks);
    }

    /**
     * Reorder tasks based on RL decisions (new feature)
     * 
     * @param taskPriorities Map of task IDs to priority values
     */
    public void reorderTasks(Map<Integer, Integer> newPriorities) {
        // Update priorities
        this.taskPriorities.putAll(newPriorities);

        // Sort tasks based on priorities (higher values first)
        tasks.sort((t1, t2) -> {
            int p1 = taskPriorities.getOrDefault(t1.getTuple().getCloudletId(), 0);
            int p2 = taskPriorities.getOrDefault(t2.getTuple().getCloudletId(), 0);
            return Integer.compare(p2, p1); // Descending order
        });

        logger.fine("Task queue reordered based on priorities");
    }

    /**
     * Update the current simulation time (new feature)
     * 
     * @param simulationTime Current simulation time
     */
    public void updateTime(double simulationTime) {
        this.currentTime = simulationTime;
    }

    /**
     * Get queue statistics (new feature)
     * 
     * @return Map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("queueSize", tasks.size());
        stats.put("totalTasksAdded", totalTasksAdded);
        stats.put("totalTasksProcessed", totalTasksProcessed);

        // Calculate average wait time
        double avgWaitTime = totalTasksProcessed > 0 ? totalWaitTime / totalTasksProcessed : 0;
        stats.put("averageWaitTime", avgWaitTime);
        stats.put("maxWaitTime", maxWaitTime);

        // Calculate current wait times for tasks in queue
        List<Double> currentWaitTimes = new ArrayList<>();
        for (TaskInfo task : tasks) {
            int taskId = task.getTuple().getCloudletId();
            double waitTime = currentTime - taskEntryTimes.getOrDefault(taskId, currentTime);
            currentWaitTimes.add(waitTime);
        }
        stats.put("currentWaitTimes", currentWaitTimes);

        return stats;
    }

    /**
     * Get priority of a task (new feature)
     * 
     * @param taskId ID of the task
     * @return Priority value
     */
    public int getTaskPriority(int taskId) {
        return taskPriorities.getOrDefault(taskId, 0);
    }

    /**
     * Set priority of a task (new feature)
     * 
     * @param taskId   ID of the task
     * @param priority Priority value
     */
    public void setTaskPriority(int taskId, int priority) {
        taskPriorities.put(taskId, priority);
    }

    /**
     * Inner class to hold task information (original API)
     */
    public static class TaskInfo {
        private final Tuple tuple;
        private final int moduleId;

        public TaskInfo(Tuple tuple, int moduleId) {
            this.tuple = tuple;
            this.moduleId = moduleId;
        }

        public Tuple getTuple() {
            return tuple;
        }

        public int getModuleId() {
            return moduleId;
        }
    }
}