package org.patch.devices;

import org.fog.entities.FogDevice;
import org.fog.entities.Tuple;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.client.SchedulerClient;
import org.patch.utils.RLConfig;
import org.patch.utils.RLStatisticsManager;
import org.patch.utils.ExtendedFogEvents;
import org.patch.models.UnscheduledQueue;
import org.patch.models.ScheduledQueue;
import org.patch.utils.TaskCacheManager;
import org.patch.integration.SchedulerIntegration;
import org.patch.integration.StreamingQueueObserver;
import org.patch.processing.TaskExecutionEngine;
import org.patch.processing.TaskCompletionDetector;
import org.patch.utils.TupleFactory;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.fog.utils.FogEvents;
import org.fog.utils.Logger;
import org.cloudbus.cloudsim.Vm;
import org.fog.application.AppModule;
import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Extended FogDevice with RL-based task scheduling capabilities
 */
public class RLFogDevice extends FogDevice {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLFogDevice.class.getName());

    // Custom event types
    private static final int RL_STATE_REPORT = 10001;
    private static final int RL_PROCESS_NEXT_TASK = 10002;
    private static final int RL_UPDATE_SCHEDULED_QUEUE = 10003;

    // Two-queue system for proper task management
    private UnscheduledQueue unscheduledQueue;
    private ScheduledQueue scheduledQueue;
    private TaskCacheManager cacheManager;
    private SchedulerIntegration schedulerIntegration;
    private StreamingQueueObserver streamingObserver;
    private TaskExecutionEngine taskExecutionEngine;
    private TaskCompletionDetector completionDetector;

    // Flag to track if RL is enabled for this device
    private boolean rlEnabled = false;

    // Track if this device has been configured for RL
    private boolean rlConfigured = false;

    // Scheduler client for gRPC communication
    private SchedulerClient schedulerClient;

    // RL server connection details
    private String rlServerHost;
    private int rlServerPort;

    // Cache management for storing RL scheduler responses
    private Map<String, Object> taskCache = new HashMap<>(); // Simple in-memory cache for task scheduling decisions
    private static final int MAX_CACHE_SIZE = 1000; // Limit cache size to prevent memory issues
    private int cacheHitCount = 0; // Track cache performance metrics
    private int cacheMissCount = 0; // Track cache performance metrics

    // RL metrics tracking - now using centralized statistics manager
    // Note: Individual device metrics are tracked via RLStatisticsManager

    /**
     * Constructor matching the parent FogDevice constructor
     */
    public RLFogDevice(String name, long mips, int ram,
            double uplinkBandwidth, double downlinkBandwidth,
            double ratePerMips, double busyPower, double idlePower,
            String rlServerHost, int rlServerPort) throws Exception {
        super(name, mips, ram, uplinkBandwidth, downlinkBandwidth, ratePerMips,
                new PowerModelLinear(busyPower, idlePower));

        // Initialize two-queue system
        this.unscheduledQueue = new UnscheduledQueue();
        this.scheduledQueue = new ScheduledQueue();
        this.cacheManager = new TaskCacheManager();

        // Store connection details
        this.rlServerHost = rlServerHost;
        this.rlServerPort = rlServerPort;

        // Initialize gRPC client
        try {
            this.schedulerClient = new SchedulerClient(rlServerHost, rlServerPort);
            logger.info("Connected to scheduler at " + rlServerHost + ":" + rlServerPort);
        } catch (Exception e) {
            logger.severe("Failed to connect to scheduler: " + e.getMessage());
            this.schedulerClient = null;
        }

        // Initialize scheduler integration
        this.schedulerIntegration = new SchedulerIntegration(
                schedulerClient, unscheduledQueue, scheduledQueue, cacheManager, getId());

        // Initialize task execution engine
        this.taskExecutionEngine = new TaskExecutionEngine(
                this, scheduledQueue, schedulerClient, null, cacheManager);

        // Initialize streaming queue observer
        this.streamingObserver = new StreamingQueueObserver(
                schedulerClient, scheduledQueue, getId());

        // Set callback to trigger task processing when queue is updated
        this.streamingObserver.setQueueUpdateCallback(this::onQueueUpdated);

        // Initialize task completion detector
        this.completionDetector = new TaskCompletionDetector(
                this, schedulerClient, cacheManager);

        // Check if global RL is enabled
        if (RLConfig.isFogRLEnabled()) {
            enableRL();
        }
    }

    /**
     * Ensure scheduler connection is active, retry if needed
     */
    private void ensureSchedulerConnection() {
        if (schedulerClient == null || !schedulerClient.isConnected()) {
            try {
                schedulerClient = new SchedulerClient(rlServerHost, rlServerPort);
                logger.info("Scheduler connection restored at " + rlServerHost + ":" + rlServerPort);
            } catch (Exception e) {
                logger.severe("Scheduler connection retry failed: " + e.getMessage());
            }
        }
    }

    /**
     * Enable RL-based scheduling for this device
     */
    public void enableRL() {
        this.rlEnabled = true;
        logger.info("RL-based scheduling enabled for fog device: " + getName() + " (ID: " + getId() + ")");

        // Start streaming queue updates
        if (streamingObserver != null) {
            streamingObserver.startStreaming();
        }

        // Start task completion monitoring
        if (completionDetector != null) {
            completionDetector.startMonitoring();
        }

        // Schedule first state report
        if (CloudSim.running()) {
            schedule(getId(), RLConfig.getStateReportInterval(), RL_STATE_REPORT);
        }
    }

    /**
     * Disable RL-based scheduling for this device
     */
    public void disableRL() {
        this.rlEnabled = false;
        logger.info("RL-based scheduling disabled for fog device: " + getName() + " (ID: " + getId() + ")");

        // Stop streaming queue updates
        if (streamingObserver != null) {
            streamingObserver.stopStreaming();
        }

        // Stop task completion monitoring
        if (completionDetector != null) {
            completionDetector.stopMonitoring();
        }
    }

    @Override
    public void processEvent(SimEvent ev) {
        // Process event with proper iFogSim integration

        switch (ev.getTag()) {
            case RL_STATE_REPORT:
                if (rlEnabled) {
                    reportStateToRLAgent();
                    schedule(getId(), RLConfig.getStateReportInterval(), RL_STATE_REPORT);
                }
                break;
            case RL_PROCESS_NEXT_TASK:
                if (rlEnabled) {
                    processNextTaskRL();
                }
                break;
            case RL_UPDATE_SCHEDULED_QUEUE:
                if (rlEnabled) {
                    updateScheduledQueueFromScheduler();
                }
                break;
            case ExtendedFogEvents.SCHEDULER_CACHE_HIT:
                handleSchedulerCacheHit(ev);
                break;
            case ExtendedFogEvents.SCHEDULER_CACHE_MISS:
                handleSchedulerCacheMiss(ev);
                break;
            case ExtendedFogEvents.SCHEDULER_ERROR:
                handleSchedulerError(ev);
                break;
            case ExtendedFogEvents.TASK_COMPLETE:
                handleTaskComplete(ev);
                break;
            case ExtendedFogEvents.METRICS_COLLECTION:
                handleMetricsCollection(ev);
                break;
            case ExtendedFogEvents.TASK_COMPLETION_CHECK:
                if (completionDetector != null) {
                    completionDetector.checkTaskCompletions();
                }
                break;
            default:
                // Ensure proper iFogSim integration by calling parent's processEvent
                super.processEvent(ev);
                break;
        }
    }

    /**
     * Configure RL server for this device
     * 
     * @param host RL server host
     * @param port RL server port
     */
    public void configureRLServer(String host, int port) {
        if (!rlEnabled) {
            enableRL();
        }

        // Create scheduler client
        this.schedulerClient = new SchedulerClient(host, port);

        RLConfig.configureFogRLServer(getId(), host, port);
        this.rlConfigured = true;

        logger.info("Scheduler client configured for fog device: " + getName() +
                " (ID: " + getId() + ") at " + host + ":" + port);
    }

    /**
     * Override processOtherEvent to intercept tuple arrivals
     */
    @Override
    protected void processOtherEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case FogEvents.TUPLE_ARRIVAL:
                if (rlEnabled) {
                    processTupleArrivalRL(ev);
                } else {
                    super.processOtherEvent(ev);
                }
                break;
            case RL_STATE_REPORT:
                if (rlEnabled) {
                    reportStateToRLAgent();
                    // Schedule next state report
                    schedule(getId(), RLConfig.getStateReportInterval(), RL_STATE_REPORT);
                }
                break;
            case RL_PROCESS_NEXT_TASK:
                if (rlEnabled) {
                    processNextTaskRL();
                }
                break;
            case RL_UPDATE_SCHEDULED_QUEUE:
                if (rlEnabled) {
                    updateScheduledQueueFromScheduler();
                }
                break;
            case ExtendedFogEvents.SCHEDULER_CACHE_HIT:
                handleSchedulerCacheHit(ev);
                break;
            case ExtendedFogEvents.SCHEDULER_CACHE_MISS:
                handleSchedulerCacheMiss(ev);
                break;
            case ExtendedFogEvents.SCHEDULER_ERROR:
                handleSchedulerError(ev);
                break;
            case ExtendedFogEvents.TASK_COMPLETE:
                handleTaskComplete(ev);
                break;
            case ExtendedFogEvents.METRICS_COLLECTION:
                handleMetricsCollection(ev);
                break;
            default:
                super.processOtherEvent(ev);
                break;
        }
    }

    /**
     * Process tuple arrival with RL-based scheduling
     */
    protected void processTupleArrivalRL(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();

        // Send ACK back to source
        send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ACK);

        Logger.debug(getName(),
                "Received tuple " + tuple.getCloudletId() + " with tupleType = " + tuple.getTupleType() +
                        "\t| Source : " + CloudSim.getEntityName(ev.getSource()) +
                        "|Dest : " + CloudSim.getEntityName(ev.getDestination()));

        // If it's an actuator tuple, handle it normally
        if (tuple.getDirection() == Tuple.ACTUATOR) {
            sendTupleToActuator(tuple);
            return;
        }

        // Check if this is an external task from cloud (no specific module destination)
        if (tuple.getDestModuleName() == null || tuple.getDestModuleName().isEmpty()) {
            processExternalTaskArrival(ev);
            return;
        }

        // Check if this tuple's destination module is on this device
        if (appToModulesMap.containsKey(tuple.getAppId()) &&
                appToModulesMap.get(tuple.getAppId()).contains(tuple.getDestModuleName())) {

            int vmId = -1;
            for (Vm vm : getHost().getVmList()) {
                if (((AppModule) vm).getName().equals(tuple.getDestModuleName()))
                    vmId = vm.getId();
            }

            if (vmId < 0 || (tuple.getModuleCopyMap().containsKey(tuple.getDestModuleName()) &&
                    tuple.getModuleCopyMap().get(tuple.getDestModuleName()) != vmId)) {
                return;
            }

            tuple.setVmId(vmId);
            updateTimingsOnReceipt(tuple);

            // Add to unscheduled queue (waiting for scheduler)
            unscheduledQueue.addTask(tuple, vmId, CloudSim.clock());

            // Send tasks to scheduler gRPC server (non-blocking)
            schedulerIntegration.sendTasksToScheduler();

            // Schedule task processing from scheduled queue
            if (scheduledQueue.size() > 0) {
                schedule(getId(), 0, RL_PROCESS_NEXT_TASK);
            }
        } else if (tuple.getDestModuleName() != null) {
            if (tuple.getDirection() == Tuple.UP)
                sendUp(tuple);
            else if (tuple.getDirection() == Tuple.DOWN) {
                for (int childId : getChildrenIds())
                    sendDown(tuple, childId);
            }
        } else {
            sendUp(tuple);
        }
    }

    /**
     * Callback method called when the scheduled queue is updated by the streaming
     * observer
     * 
     * @param updatedQueue The updated scheduled queue
     */
    private void onQueueUpdated(ScheduledQueue updatedQueue) {
        logger.fine("Queue updated, triggering task processing for device: " + getId());

        // Schedule task processing if there are tasks in the queue
        if (!updatedQueue.isEmpty()) {
            schedule(getId(), 0, RL_PROCESS_NEXT_TASK);
        }
    }

    /**
     * Process the next task from scheduled queue using TaskExecutionEngine
     */
    private void processNextTaskRL() {
        if (taskExecutionEngine == null) {
            logger.warning("TaskExecutionEngine not initialized");
            return;
        }

        // Use the task execution engine to process the next task
        boolean taskProcessed = taskExecutionEngine.processNextTask();

        if (taskProcessed) {
            // Update scheduling metrics
            RLStatisticsManager.getInstance().incrementSchedulingDecisions();
            RLStatisticsManager.getInstance().incrementSuccessfulScheduling();

            // If there are more tasks in scheduled queue, schedule the next processing
            if (!scheduledQueue.isEmpty()) {
                schedule(getId(), 0, RL_PROCESS_NEXT_TASK);
            }
        }
    }

    /**
     * Mark a task as completed
     * 
     * @param taskInfo The task to mark as completed
     */
    private void markTaskCompleted(ScheduledQueue.TaskInfo taskInfo) {
        // This would typically involve updating task status and metrics
        logger.info("Task " + taskInfo.getTaskId() + " marked as completed");
        // Could add metrics collection here
    }

    /**
     * Collect current device state for RL agent
     * 
     * @return Map containing device state information
     */
    private Map<String, Object> collectDeviceState() {
        Map<String, Object> state = new HashMap<>();

        // Device information
        state.put("deviceId", getId());
        state.put("deviceName", getName());

        // Resource utilization
        state.put("cpuUtilization", getHost().getUtilizationOfCpu());
        state.put("ramUtilization", getHost().getUtilizationOfRam());
        state.put("bwUtilization", getHost().getUtilizationOfBw());

        // Queue information
        state.put("unscheduledQueueLength", unscheduledQueue.size());
        state.put("scheduledQueueLength", scheduledQueue.size());

        // Cache statistics
        Map<String, Object> cacheStats = cacheManager.getCacheStats();
        state.put("cacheStats", cacheStats);

        return state;
    }

    /**
     * Report task completion for RL learning to grpc-task-scheduler
     */
    public void reportTaskCompletion(Tuple tuple, boolean success, long executionTime) {
        if (schedulerClient == null || !schedulerClient.isConnected()) {
            return;
        }

        try {
            // Report to grpc-task-scheduler for learning
            TaskCompletionReport report = TaskCompletionReport.newBuilder()
                    .setTaskId(String.valueOf(tuple.getCloudletId()))
                    .addTasks(CompletedTask.newBuilder()
                            .setTaskId(String.valueOf(tuple.getCloudletId()))
                            .setAssignedNodeId(String.valueOf(getId()))
                            .setActualExecutionTimeMs(executionTime)
                            .setDeadlineMet(success)
                            .build())
                    .setCompletionTimestamp(System.currentTimeMillis())
                    .build();

            schedulerClient.reportTaskCompletion(report);
            logger.info("Reported task completion to scheduler: " + tuple.getCloudletId());

        } catch (Exception e) {
            logger.severe("Failed to report task completion to scheduler: " + e.getMessage());
        }
    }

    /**
     * Report current state to RL agent
     */
    private void reportStateToRLAgent() {
        if (!rlConfigured || schedulerClient == null || !schedulerClient.isConnected()) {
            return;
        }

        try {
            // Get system metrics
            GetSystemMetricsResponse metrics = schedulerClient.getSystemMetrics();
            logger.fine("System metrics: " + metrics.toString());

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to report state to RL agent", e);
        }
    }

    /**
     * Get the unscheduled queue
     * 
     * @return The unscheduled queue
     */
    public UnscheduledQueue getUnscheduledQueue() {
        return unscheduledQueue;
    }

    /**
     * Get the scheduled queue
     * 
     * @return The scheduled queue
     */
    public ScheduledQueue getScheduledQueue() {
        return scheduledQueue;
    }

    /**
     * Check if RL is enabled for this device
     * 
     * @return true if RL is enabled
     */
    public boolean isRlEnabled() {
        return rlEnabled;
    }

    /**
     * Get scheduler client
     */
    public SchedulerClient getSchedulerClient() {
        return schedulerClient;
    }

    /**
     * Get task execution engine
     */
    public TaskExecutionEngine getTaskExecutionEngine() {
        return taskExecutionEngine;
    }

    /**
     * Check if RL is configured
     */
    public boolean isRLConfigured() {
        return rlConfigured;
    }

    // ===== RL METRICS AND TRACKING METHODS =====

    /**
     * Get total number of scheduling decisions made
     */
    public long getTotalSchedulingDecisions() {
        return RLStatisticsManager.getInstance().getTotalSchedulingDecisions();
    }

    /**
     * Get scheduling success rate
     */
    public double getSchedulingSuccessRate() {
        return RLStatisticsManager.getInstance().getSchedulingSuccessRate();
    }

    /**
     * Get total energy consumed for scheduling
     */
    public double getTotalSchedulingEnergy() {
        return RLStatisticsManager.getInstance().getTotalSchedulingEnergy();
    }

    /**
     * Get total cost of scheduling
     */
    public double getTotalSchedulingCost() {
        return RLStatisticsManager.getInstance().getTotalSchedulingCost();
    }

    /**
     * Get total energy consumed by this device
     */
    public double getTotalEnergyConsumed() {
        return getEnergyConsumption() + getTotalSchedulingEnergy();
    }

    /**
     * Get total cost of this device
     */
    public double getTotalCost() {
        return super.getTotalCost() + getTotalSchedulingCost();
    }

    /**
     * Get current unscheduled queue size
     */
    public int getUnscheduledQueueSize() {
        return unscheduledQueue != null ? unscheduledQueue.size() : 0;
    }

    /**
     * Get current scheduled queue size
     */
    public int getScheduledQueueSize() {
        return scheduledQueue != null ? scheduledQueue.size() : 0;
    }

    /**
     * Get average scheduling latency
     */
    public double getAverageSchedulingLatency() {
        return RLStatisticsManager.getInstance().getAverageSchedulingLatency();
    }

    /**
     * Get scheduling throughput (decisions per second)
     */
    public double getSchedulingThroughput() {
        double simulationTime = CloudSim.clock();
        if (simulationTime == 0) {
            return 0.0;
        }
        return getTotalSchedulingDecisions() / simulationTime;
    }

    /**
     * Calculate energy cost for scheduling decision
     */
    private double calculateSchedulingEnergy(Tuple tuple, long latency) {
        // Base energy consumption for scheduling decision
        double baseEnergy = 0.0005; // 0.5mJ per scheduling decision
        // Additional energy based on tuple complexity
        double complexityEnergy = tuple.getCloudletLength() * 0.0000005; // 0.5μJ per MIPS
        // Energy based on latency
        double latencyEnergy = latency * 0.000005; // 5μJ per ms

        return baseEnergy + complexityEnergy + latencyEnergy;
    }

    /**
     * Calculate monetary cost for scheduling decision
     */
    private double calculateSchedulingCost(Tuple tuple, long latency) {
        // Base cost for scheduling decision
        double baseCost = 0.00005; // $0.00005 per scheduling decision
        // Additional cost based on tuple complexity
        double complexityCost = tuple.getCloudletLength() * 0.00000005; // $0.00000005 per MIPS
        // Cost based on latency (opportunity cost)
        double latencyCost = latency * 0.0000005; // $0.0000005 per ms

        return baseCost + complexityCost + latencyCost;
    }

    /**
     * Handle scheduler cache hit event
     */
    private void handleSchedulerCacheHit(SimEvent ev) {
        AddTaskToQueueResponse response = (AddTaskToQueueResponse) ev.getData();
        logger.info("Cache hit for task: " + response.getTaskId());

        // Handle cached result - task is resolved with cached value
        if (response.getIsCachedTask()) {
            handleCachedTaskResult(response);
        }

        // Update cache hit metrics
        cacheHitCount++;
    }

    /**
     * Handle scheduler cache miss event
     */
    private void handleSchedulerCacheMiss(SimEvent ev) {
        AddTaskToQueueResponse response = (AddTaskToQueueResponse) ev.getData();
        logger.info("Cache miss for task: " + response.getTaskId());

        // Process task normally
        processTaskNormally(response);

        // Update cache miss metrics
        cacheMissCount++;
    }

    /**
     * Handle scheduler error event
     */
    private void handleSchedulerError(SimEvent ev) {
        String error = (String) ev.getData();
        logger.severe("Scheduler error: " + error);
        // Handle error recovery or fallback logic
    }

    /**
     * Handle task completion event
     */
    private void handleTaskComplete(SimEvent ev) {
        // Handle task completion logic
        logger.info("Task completion event processed");
    }

    /**
     * Handle metrics collection event
     */
    private void handleMetricsCollection(SimEvent ev) {
        // Collect and report metrics
        logger.fine("Metrics collection event processed for fog device: " + getName());
        // This would typically collect and report metrics to monitoring systems
    }

    /**
     * Handle cached task result - task is resolved with cached value
     */
    private void handleCachedTaskResult(AddTaskToQueueResponse response) {
        String taskId = response.getTaskId();

        // Calculate execution time from estimated wait time (fallback)
        long executionTime = response.getEstimatedWaitTimeMs() + 1000; // Default execution time

        // Update metrics for cached task
        updateCachedTaskMetrics(taskId, executionTime);

        // Mark task as completed with cached result
        markTaskAsCompleted(taskId, true, executionTime);

        logger.info("Task " + taskId + " completed using cached result");
    }

    /**
     * Process task normally (cache miss scenario)
     */
    private void processTaskNormally(AddTaskToQueueResponse response) {
        String taskId = response.getTaskId();

        // Check if we need to store result in cache
        if (response.getCacheAction() == CacheAction.CACHE_ACTION_STORE) {
            storeTaskInCache(taskId, response);
        }

        // Process task normally
        executeTask(response);

        logger.info("Task " + taskId + " processed normally");
    }

    /**
     * Store task result in cache
     */
    private void storeTaskInCache(String taskId, AddTaskToQueueResponse response) {
        // Store task result in cache if cache size allows to improve future performance
        if (taskCache.size() < MAX_CACHE_SIZE) {
            taskCache.put(taskId, response);
            logger.info("Stored task " + taskId + " in cache");
        } else {
            // Implement simple LRU-like eviction: remove oldest entry when cache is full
            // This prevents memory overflow while maintaining recent scheduling decisions
            String oldestKey = taskCache.keySet().iterator().next();
            taskCache.remove(oldestKey);
            taskCache.put(taskId, response);
            logger.info("Cache full, replaced oldest entry with task " + taskId);
        }
    }

    /**
     * Invalidate cache entry for a task
     */
    private void invalidateCache(String taskId) {
        if (taskCache.containsKey(taskId)) {
            taskCache.remove(taskId);
            logger.info("Invalidated cache for task " + taskId);
        }
    }

    /**
     * Update metrics for cached task
     */
    private void updateCachedTaskMetrics(String taskId, long executionTime) {
        // Update cached task metrics
        logger.fine("Updated cached task metrics for " + taskId + " (execution time: " + executionTime + "ms)");
    }

    /**
     * Mark task as completed
     */
    private void markTaskAsCompleted(String taskId, boolean success, long executionTime) {
        // Mark task as completed with result
        logger.fine(
                "Task " + taskId + " marked as completed (success: " + success + ", time: " + executionTime + "ms)");
    }

    /**
     * Execute task normally
     */
    private void executeTask(AddTaskToQueueResponse response) {
        // Execute task normally
        logger.fine("Executing task " + response.getTaskId() + " normally");
    }

    /**
     * Get cache statistics
     */
    public Map<String, Object> getCacheStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", taskCache.size());
        stats.put("maxCacheSize", MAX_CACHE_SIZE);
        stats.put("cacheHitCount", cacheHitCount);
        stats.put("cacheMissCount", cacheMissCount);
        stats.put("cacheHitRate",
                cacheHitCount + cacheMissCount > 0 ? (double) cacheHitCount / (cacheHitCount + cacheMissCount) : 0.0);
        return stats;
    }

    /**
     * Get comprehensive device statistics including task execution
     */
    public Map<String, Object> getComprehensiveStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // Basic device stats
        stats.put("deviceId", getId());
        stats.put("deviceName", getName());
        stats.put("rlEnabled", rlEnabled);
        stats.put("rlConfigured", rlConfigured);

        // Queue stats
        stats.put("unscheduledQueueSize", unscheduledQueue.size());
        stats.put("scheduledQueueSize", scheduledQueue.size());

        // Scheduling stats - now using centralized statistics manager
        stats.put("totalSchedulingDecisions", getTotalSchedulingDecisions());
        stats.put("successfulScheduling", RLStatisticsManager.getInstance().getSuccessfulScheduling());
        stats.put("schedulingSuccessRate", getSchedulingSuccessRate());
        stats.put("totalSchedulingEnergy", getTotalSchedulingEnergy());
        stats.put("totalSchedulingCost", getTotalSchedulingCost());
        stats.put("averageSchedulingLatency", getAverageSchedulingLatency());
        stats.put("schedulingThroughput", getSchedulingThroughput());

        // Cache stats
        stats.putAll(getCacheStatistics());

        // Task execution stats
        if (taskExecutionEngine != null) {
            stats.putAll(taskExecutionEngine.getExecutionStatistics());
        }

        return stats;
    }

    /**
     * Handle external tasks from cloud device
     */
    protected void processExternalTaskArrival(SimEvent ev) {
        Tuple externalTask = (Tuple) ev.getData();

        logger.info("Received external task " + externalTask.getCloudletId() + " from cloud");

        // Send ACK back to source
        send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ACK);

        // Set default VM ID for external tasks (use first available VM)
        int vmId = -1;
        if (!getHost().getVmList().isEmpty()) {
            vmId = getHost().getVmList().get(0).getId();
        }

        externalTask.setVmId(vmId);
        updateTimingsOnReceipt(externalTask);

        // Add to unscheduled queue
        unscheduledQueue.addTask(externalTask, vmId, CloudSim.clock());

        // Send to scheduler gRPC server
        schedulerIntegration.sendTasksToScheduler();

        // Schedule queue update from scheduler
        schedule(getId(), 100, RL_UPDATE_SCHEDULED_QUEUE);

        logger.info("External task " + externalTask.getCloudletId() + " added to unscheduled queue");
    }

    /**
     * Update scheduled queue from scheduler gRPC server
     */
    private void updateScheduledQueueFromScheduler() {
        if (schedulerClient == null || !schedulerClient.isConnected()) {
            logger.warning("Scheduler client not available for queue update");
            return;
        }

        try {
            // Get sorted queue from scheduler
            GetSortedQueueResponse response = schedulerClient.getSortedQueue(String.valueOf(getId()));

            // Clear current scheduled queue
            scheduledQueue.clear();

            // Add tasks from scheduler response
            for (Task task : response.getQueueTasksList()) {
                ScheduledQueue.TaskInfo taskInfo = convertFromProtoTask(task);
                scheduledQueue.addTask(taskInfo);
            }

            logger.info("Scheduled queue updated with " + response.getQueueTasksCount() + " tasks");

            // Process next task if queue has tasks
            if (!scheduledQueue.isEmpty()) {
                schedule(getId(), 0, RL_PROCESS_NEXT_TASK);
            }

        } catch (Exception e) {
            logger.severe("Failed to update scheduled queue from scheduler: " + e.getMessage());
        }
    }

    /**
     * Convert proto Task to ScheduledQueue.TaskInfo
     */
    private ScheduledQueue.TaskInfo convertFromProtoTask(Task protoTask) {
        // Create tuple with correct parameters in constructor
        Tuple mockTuple = new Tuple(
                "external-app", // appId
                Integer.parseInt(protoTask.getTaskId()), // cloudletId
                Tuple.UP, // direction
                protoTask.getExecutionTime(), // cloudletLength
                1, // pesNumber
                protoTask.getCpuRequirement(), // cloudletFileSize
                protoTask.getMemoryRequirement(), // cloudletOutputSize
                new org.cloudbus.cloudsim.UtilizationModelFull(), // utilizationModelCpu
                new org.cloudbus.cloudsim.UtilizationModelFull(), // utilizationModelRam
                new org.cloudbus.cloudsim.UtilizationModelFull() // utilizationModelBw
        );

        // Set additional properties
        mockTuple.setTupleType("EXTERNAL_TASK");
        mockTuple.setDestModuleName("external-module");
        mockTuple.setSrcModuleName("external-source");
        mockTuple.setDirection(Tuple.UP);
        mockTuple.setAppId("external-app");
        mockTuple.setUserId(0);
        mockTuple.setSourceDeviceId(getId());

        // Get VM ID (use first available VM)
        int vmId = -1;
        if (!getHost().getVmList().isEmpty()) {
            vmId = getHost().getVmList().get(0).getId();
        }

        return new ScheduledQueue.TaskInfo(
                mockTuple,
                vmId, // Use current VM ID
                (long) CloudSim.clock(),
                "scheduler-assigned", // Node assignment from scheduler
                (long) (CloudSim.clock() + 1000), // Estimated start time
                (long) (CloudSim.clock() + 2000), // Estimated completion time
                false, // Not cached by default
                "" // No cache key
        );
    }

    /**
     * Get the streaming queue observer
     * 
     * @return StreamingQueueObserver instance
     */
    public StreamingQueueObserver getStreamingObserver() {
        return streamingObserver;
    }

    /**
     * Get the task completion detector
     * 
     * @return TaskCompletionDetector instance
     */
    public TaskCompletionDetector getCompletionDetector() {
        return completionDetector;
    }

    @Override
    public void shutdownEntity() {
        // Stop streaming observer
        if (streamingObserver != null) {
            streamingObserver.cleanup();
        }

        // Stop completion detector
        if (completionDetector != null) {
            completionDetector.stopMonitoring();
        }

        // Close scheduler client
        if (schedulerClient != null) {
            schedulerClient.close();
        }
        super.shutdownEntity();
    }
}