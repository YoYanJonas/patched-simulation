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
import org.cloudbus.cloudsim.power.models.PowerModel;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.fog.utils.FogEvents;
import org.fog.utils.Logger;
import org.cloudbus.cloudsim.Vm;
import org.fog.application.AppModule;
import org.patch.proto.IfogsimScheduler.*;
import org.patch.proto.IfogsimCommon.*;
import org.patch.models.PendingSchedulingRequest;
import org.patch.utils.NetworkLatencyConverter;
import org.patch.utils.NetworkEnergyCostCalculator;
import org.patch.utils.SystemMetricsCalculator;
import org.patch.config.EnhancedConfigurationLoader;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.entities.FogDeviceCharacteristics;
import org.cloudbus.cloudsim.power.PowerHost;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.Config;
import org.fog.utils.FogUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
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

    private int internalTaskCount = 0;
    private int externalTaskCount = 0;

    // Pending async scheduling requests
    private Map<String, PendingSchedulingRequest> pendingSchedulingRequests = new HashMap<>();

    // RL metrics tracking - now using centralized statistics manager
    // Note: Individual device metrics are tracked via RLStatisticsManager

    /**
     * Wrapper class to hold device components for initialization
     */
    private static class DeviceComponents {
        final FogDeviceCharacteristics characteristics;
        final VmAllocationPolicy vmAllocationPolicy;
        final LinkedList<Storage> storageList;

        DeviceComponents(FogDeviceCharacteristics characteristics,
                VmAllocationPolicy vmAllocationPolicy,
                LinkedList<Storage> storageList) {
            this.characteristics = characteristics;
            this.vmAllocationPolicy = vmAllocationPolicy;
            this.storageList = storageList;
        }
    }

    /**
     * Helper method to create host and characteristics for proper initialization
     */
    private static DeviceComponents createDeviceComponents(String name, long mips, int ram,
            PowerModelLinear powerModel) {
        List<Pe> peList = new ArrayList<Pe>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int hostId = FogUtils.generateEntityId();
        long storage = 1000000;
        int bw = 10000;

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                powerModel);

        List<Host> hostList = new ArrayList<Host>();
        hostList.add(host);

        VmAllocationPolicy vmAllocationPolicy = new AppModuleAllocationPolicy(hostList);

        String arch = Config.FOG_DEVICE_ARCH;
        String os = Config.FOG_DEVICE_OS;
        String vmm = Config.FOG_DEVICE_VMM;
        double time_zone = Config.FOG_DEVICE_TIMEZONE;
        double cost = Config.FOG_DEVICE_COST;
        double costPerMem = Config.FOG_DEVICE_COST_PER_MEMORY;
        double costPerStorage = Config.FOG_DEVICE_COST_PER_STORAGE;
        double costPerBw = Config.FOG_DEVICE_COST_PER_BW;

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                arch, os, vmm, host, time_zone, cost, costPerMem,
                costPerStorage, costPerBw);

        LinkedList<Storage> storageList = new LinkedList<Storage>();

        return new DeviceComponents(characteristics, vmAllocationPolicy, storageList);
    }

    /**
     * Constructor matching the parent FogDevice constructor
     * Creates host and characteristics first, then calls full FogDevice constructor
     */
    public RLFogDevice(String name, long mips, int ram,
            double uplinkBandwidth, double downlinkBandwidth,
            double ratePerMips, double busyPower, double idlePower,
            String rlServerHost, int rlServerPort) throws Exception {
        // Call full FogDevice constructor - super() must be first, so everything is
        // inlined
        super(name,
                createDeviceComponents(name, mips, ram,
                        new PowerModelLinear(busyPower, idlePower)).characteristics,
                createDeviceComponents(name, mips, ram,
                        new PowerModelLinear(busyPower, idlePower)).vmAllocationPolicy,
                createDeviceComponents(name, mips, ram,
                        new PowerModelLinear(busyPower, idlePower)).storageList,
                10.0, // schedulingInterval
                uplinkBandwidth, downlinkBandwidth, 0.0, // uplinkLatency default
                ratePerMips);

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
        // Set RLFogDevice reference for storing pending async requests
        this.schedulerIntegration.setRLFogDevice(this);

        // Initialize task execution engine
        this.taskExecutionEngine = new TaskExecutionEngine(
                this, scheduledQueue, schedulerClient, null, cacheManager);

        // Initialize streaming queue observer
        this.streamingObserver = new StreamingQueueObserver(
                schedulerClient, scheduledQueue, getId());

        // Set device entity for CloudSim event scheduling
        this.streamingObserver.setDeviceEntity(this);

        // Set TaskExecutionEngine reference for checking active tasks
        this.streamingObserver.setTaskExecutionEngine(this.taskExecutionEngine);

        // Set callback to trigger task processing when queue is updated
        this.streamingObserver.setQueueUpdateCallback(this::onQueueUpdated);

        // Initialize task completion detector
        this.completionDetector = new TaskCompletionDetector(
                this, schedulerClient, cacheManager);

        // Check if global RL is enabled - force check config
        org.patch.config.EnhancedConfigurationLoader.initialize();
        boolean fogRLEnabled = RLConfig.isFogRLEnabled();
        boolean placementRLFromConfig = org.patch.config.EnhancedConfigurationLoader
                .getRLConfigBoolean("rl.servers.placement.enabled", true);

        // Enable RL if config says so
        if (fogRLEnabled || placementRLFromConfig) {
            if (!fogRLEnabled && placementRLFromConfig) {
                // Enable it now if it wasn't already
                String placementHost = org.patch.config.EnhancedConfigurationLoader
                        .getRLConfig("rl.servers.placement.host", rlServerHost);
                int placementPort = org.patch.config.EnhancedConfigurationLoader
                        .getRLConfigInt("rl.servers.placement.port", rlServerPort);
                RLConfig.enablePlacementRL(placementHost, placementPort);
                org.patch.utils.ServiceRegistry.setConfig(RLConfig.ENABLE_FOG_RL, true);
                logger.info(
                        "Enabled Fog RL from config during device creation at " + placementHost + ":" + placementPort);
            }
            enableRL();
            logger.info("RL enabled for fog device: " + getName() + " (ID: " + getId() + ")");
        } else {
            logger.info("RL NOT enabled for fog device: " + getName() + " - config says disabled");
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
            case ExtendedFogEvents.TUPLE_COMPLETE:
                handleTupleComplete(ev);
                break;
            case ExtendedFogEvents.GRPC_SCHEDULER_RESPONSE:
                handleGrpcSchedulerResponse(ev);
                break;
            case ExtendedFogEvents.GRPC_SCHEDULER_TIMEOUT:
                handleGrpcSchedulerTimeout(ev);
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
     * Override processOtherEvent to intercept tuple arrivals and module deployment
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
            case FogEvents.LAUNCH_MODULE:
                // Explicitly handle LAUNCH_MODULE to ensure processModuleArrival is called
                processModuleArrival(ev);
                break;
            case FogEvents.APP_SUBMIT:
                // Explicitly handle APP_SUBMIT to ensure processAppSubmit is called
                processAppSubmit(ev);
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
            case ExtendedFogEvents.STREAMING_QUEUE_UPDATE:
                if (rlEnabled && streamingObserver != null) {
                    // Poll queue from scheduler (event-driven, not Thread.sleep!)
                    streamingObserver.pollQueueFromScheduler();
                }
                break;
            case ExtendedFogEvents.GRPC_SCHEDULER_RESPONSE:
                handleGrpcSchedulerResponse(ev);
                break;
            case ExtendedFogEvents.GRPC_SCHEDULER_TIMEOUT:
                handleGrpcSchedulerTimeout(ev);
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
        String sourceName = CloudSim.getEntityName(ev.getSource());
        boolean isFromCloud = sourceName != null && sourceName.contains("cloud");
        boolean isFromSensor = sourceName != null && sourceName.contains("sensor");

        // Log task arrival (every 10th task or first 20 to avoid log bloat)

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

        // Check if this is an external task from cloud
        // External tasks have destModuleName="external_task" or tupleType="EXTERNAL"
        // OR if destModuleName is null/empty and source is cloud
        boolean isExternalTask = (tuple.getTupleType() != null && tuple.getTupleType().equals("EXTERNAL")) ||
                (tuple.getDestModuleName() != null && tuple.getDestModuleName().equals("external_task")) ||
                ((tuple.getDestModuleName() == null || tuple.getDestModuleName().isEmpty()) && isFromCloud);

        if (isExternalTask) {
            externalTaskCount++;

            // Process external task arrival
            processExternalTaskArrival(ev);
            return;
        }

        // Check if this tuple's destination module is on this device
        boolean appIdInMap = appToModulesMap.containsKey(tuple.getAppId());
        boolean moduleInApp = appIdInMap && appToModulesMap.get(tuple.getAppId()).contains(tuple.getDestModuleName());


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
            internalTaskCount++;
            double queueAddTime = CloudSim.clock();
            unscheduledQueue.addTask(tuple, vmId, queueAddTime);


            // Send tasks to scheduler gRPC server (non-blocking)
            // IMPORTANT: Tasks are NOT removed from unscheduled queue here!
            schedulerIntegration.sendTasksToScheduler();

            // Schedule task processing from scheduled queue
            if (scheduledQueue.size() > 0) {
                schedule(getId(), 0, RL_PROCESS_NEXT_TASK);
            }
        } else if (tuple.getDestModuleName() != null) {
            // Module not found on this device - forward it
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
        double currentTime = CloudSim.clock();
        int queueSize = updatedQueue != null ? updatedQueue.size() : -1;

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
        double currentTime = CloudSim.clock();

        if (scheduledQueue == null) {
            logger.warning("Scheduled queue is NULL!");
            return;
        }

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

        // Resource utilization (normalized to percentages [0.0, 1.0] for consistency)
        // CPU: getUtilizationOfCpu() returns percentage [0.0, 1.0] - use directly
        double cpuUtilization = getHost().getUtilizationOfCpu();

        // Memory: getUtilizationOfRam() returns MB USED (not percentage!), convert to
        // percentage [0.0, 1.0]
        double ramUsedMb = getHost().getUtilizationOfRam();
        int totalRamMb = getHost().getRam();
        double ramUtilization = (totalRamMb > 0) ? (ramUsedMb / totalRamMb) : 0.0;
        // Clamp to valid range
        if (ramUtilization < 0.0)
            ramUtilization = 0.0;
        if (ramUtilization > 1.0)
            ramUtilization = 1.0;

        state.put("cpuUtilization", cpuUtilization); // Percentage [0.0, 1.0]
        state.put("ramUtilization", ramUtilization); // Percentage [0.0, 1.0]
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
     * 
     * @param tuple         The tuple that was processed
     * @param success       Whether the task completed successfully
     * @param executionTime Execution time in milliseconds (0 for cached tasks)
     * @param isCached      Whether this task was served from cache (instant
     *                      execution)
     */
    /**
     * Report task completion to scheduler
     * 
     * @param tuple          The completed tuple
     * @param taskInfo       The task information (contains pattern-based taskId)
     * @param success        Whether task completed successfully
     * @param executionTime  Execution time in milliseconds
     * @param isCached       Whether task was cached
     * @param cpuUtilization CPU utilization (percentage 0.0-1.0)
     * @param ramUtilization RAM utilization (percentage 0.0-1.0)
     * @return true if server confirmed (ACK success), false otherwise
     */
    public boolean reportTaskCompletion(Tuple tuple, org.patch.models.ScheduledQueue.TaskInfo taskInfo, boolean success,
            long executionTime, boolean isCached,
            double cpuUtilization, double ramUtilization) {
        if (schedulerClient == null || !schedulerClient.isConnected()) {
            return false; // Not connected, can't report
        }

        // For cached tasks, report with executionTime = 0 to indicate instant cache hit
        // For non-cached tasks, report actual execution time
        // Proto field expects double, so convert long to double
        double reportedExecutionTime = isCached ? 0.0 : (double) executionTime;

        try {

            // Use captured CPU/Memory utilization from VM scheduler (passed as parameters)
            // These values were captured DURING task execution, before resources were
            // released
            // This is much more accurate than reading after completion (which returns 0)
            int cpuCores = getHost().getNumberOfPes();
            int memoryMb = getHost().getRam();

            // Ensure utilization values are in valid range [0.0, 1.0]
            if (cpuUtilization < 0.0)
                cpuUtilization = 0.0;
            if (cpuUtilization > 1.0)
                cpuUtilization = 1.0;
            if (ramUtilization < 0.0)
                ramUtilization = 0.0;
            if (ramUtilization > 1.0)
                ramUtilization = 1.0;

            // Create ResourceUsage (CPU/Memory as percentages)
            // cpuUtilization is already percentage [0.0, 1.0]
            // ramUtilization is already percentage [0.0, 1.0]
            // Convert to actual MB used for MemoryUsageMb field
            long actualMemoryUsedMb = Math.round(ramUtilization * memoryMb);

            ResourceUsage currentUsage = ResourceUsage.newBuilder()
                    .setCpuUsage((long) (cpuUtilization * 100)) // Convert to percentage (0-100)
                    .setMemoryUsageMb(actualMemoryUsedMb) // Actual MB used (calculated from percentage)
                    .build();

            // Create ResourceCapacity
            ResourceCapacity capacity = ResourceCapacity.newBuilder()
                    .setCpuCores(cpuCores)
                    .setMemoryMb(memoryMb)
                    .build();

            // Create FogNode with real status
            FogNode nodeStatus = FogNode.newBuilder()
                    .setNodeId(String.valueOf(getId()))
                    .setNodeName(getName())
                    .setStatus(NodeStatus.NODE_STATUS_ACTIVE)
                    .setCapacity(capacity)
                    .setCurrentUsage(currentUsage)
                    .build();

            // Log node status being sent
            logger.info(String.format(
                    "[NODE-STATUS-SEND] Task=%s, CPU=%.2f%%, Memory=%.2f%% (%d/%d MB), Cores=%d",
                    tuple.getCloudletId(), cpuUtilization * 100, ramUtilization * 100,
                    (long) (ramUtilization * memoryMb), memoryMb, cpuCores));


            // Extract cloudletId (unique instance identifier) from tuple
            long cloudletId = tuple.getCloudletId();
            String cloudletIdStr = String.valueOf(cloudletId);

            // Extract taskId (pattern-based, for caching/fingerprinting) from TaskInfo
            // CRITICAL: taskId and cloudletId are separate - taskId is pattern-based, cloudletId is unique
            // NO FALLBACK to cloudletId - taskId must come from TaskInfo
            String taskId = null;
            if (taskInfo != null) {
                taskId = taskInfo.getTaskId(); // Pattern-based taskId from server response
            }

            // CRITICAL: If taskId not found, log error and use empty string (do NOT use cloudletId as fallback)
            // taskId and cloudletId are separate values - taskId is pattern-based, cloudletId is unique identifier
            if (taskId == null || taskId.isEmpty()) {
                logger.severe(String.format(
                        "[COMPLETION-REPORT-ERROR] TaskId not found in TaskInfo for cloudletId=%s. " +
                        "taskId and cloudletId are separate - taskId must come from TaskInfo.",
                        cloudletIdStr));
                taskId = ""; // Use empty string, but still send cloudletId (which is required)
            }


            // Later Feature: deadline-aware tracking disabled
            // if (!success) {
            // RLStatisticsManager.getInstance().incrementDeadlineMisses();
            // logger.fine(String.format("[SYSTEM-METRICS] Task %s failed, incrementing
            // deadline misses", taskIdToSend));
            // }

            // Calculate system metrics from iFogSim data
            SystemPerformanceMetrics metrics = SystemMetricsCalculator.calculateMetrics(
                    this, // Current fog device
                    null // Optional: all fog devices (can enhance later for fairness)
            );

            // ⚠️ CRITICAL: Use CloudSim.clock() for completionTimestamp, NOT
            // System.currentTimeMillis()
            // Convert simulation time to milliseconds if proto requires it
            long completionTimestampMs = (long) (CloudSim.clock() * 1000); // ✅ Simulation time in ms

            // Report to grpc-task-scheduler for learning
            // Send both task_id (pattern-based, for caching) and cloudlet_id (unique
            // instance, for experience lookup)
            TaskCompletionReport report = TaskCompletionReport.newBuilder()
                    .setTaskId(taskId) // Pattern-based taskId (for backward compatibility)
                    .setCloudletId(cloudletIdStr) // REQUIRED: Unique cloudletId (for experience lookup)
                    .addTasks(CompletedTask.newBuilder()
                            .setTaskId(taskId) // Pattern-based taskId
                            .setCloudletId(cloudletIdStr) // REQUIRED: Unique cloudletId
                            .setAssignedNodeId(String.valueOf(getId()))
                            .setActualExecutionTimeMs(reportedExecutionTime)
                            .setDeadlineMet(true) // Later Feature: deadline-aware disabled (always true)
                            .build())
                    .setCompletionTimestamp(completionTimestampMs) // ✅ Simulation time, not real-world time
                    .setNodeStatus(nodeStatus) // Real node status
                    .setMetrics(metrics) // ✅ NEW: Add calculated system metrics
                    .build();


            logger.info(String.format(
                    "[CACHE-COMPLETION-REPORT] Sending completion report: TaskId=%s, CloudletId=%s, ActualExecutionTimeMs=%.2f, DeadlineMet=%s, NodeStatus.CPU=%.2f%%, NodeStatus.Memory=%d MB",
                    report.getTaskId(), report.getCloudletId(), report.getTasks(0).getActualExecutionTimeMs(),
                    report.getTasks(0).getDeadlineMet(),
                    (double) nodeStatus.getCurrentUsage().getCpuUsage(),
                    nodeStatus.getCurrentUsage().getMemoryUsageMb()));

            // Send completion report and get ACK response
            TaskCompletionAck ack = schedulerClient.reportTaskCompletion(report);

            // Check ACK to confirm server processed the completion
            if (ack != null && ack.getSuccess()) {
                // Server confirmed: task is removed from server's queue
                logger.info(String.format(
                        "[TASK-COMPLETION-ACK-SUCCESS] Task %s completion confirmed by server (ACK success)",
                        tuple.getCloudletId()));
                if (isCached) {
                    logger.info("Reported CACHED task completion to scheduler (instant): " + tuple.getCloudletId());
                } else {
                    logger.info("Reported task completion to scheduler (executed): " + tuple.getCloudletId());
                }
                // Return true to indicate success
                return true;
            } else {
                // Server rejected or ACK failed
                String errorMsg = (ack != null) ? ack.getMessage() : "ACK is null";
                logger.warning(String.format(
                        "[TASK-COMPLETION-ACK-FAILURE] Task %s completion NOT confirmed by server: %s",
                        tuple.getCloudletId(), errorMsg));
                // Return false to indicate failure
                return false;
            }

        } catch (ClassCastException e) {
            logger.severe(String.format(
                    "Type casting error in completion report for task %s: %s",
                    tuple.getCloudletId(), e.getMessage()));
            logger.severe("reportedExecutionTime type: " + Double.class.getName());
            logger.severe("reportedExecutionTime value: " + reportedExecutionTime);
            logger.severe("executionTime (original): " + executionTime);
            logger.severe("isCached: " + isCached);
            e.printStackTrace();
            return false; // Return false on error
        } catch (Exception e) {
            logger.severe(String.format(
                    "Failed to report task completion to scheduler for task %s: %s",
                    tuple.getCloudletId(), e.getMessage()));
            logger.severe("Exception type: " + e.getClass().getName());
            e.printStackTrace();
            return false; // Return false on error
        }
    }

    /**
     * Report task completion for RL learning to grpc-task-scheduler (legacy method
     * for backward compatibility)
     * Assumes task was not cached (executionTime > 0)
     * 
     * Note: This method is called after resource release, so host utilization will
     * be 0.
     * This is expected behavior - we report the actual state (0% utilization after
     * release).
     */
    public void reportTaskCompletion(Tuple tuple, boolean success, long executionTime) {
        // Get actual host utilization (will be 0 after resource release, which is
        // correct)
        // CPU: getUtilizationOfCpu() returns percentage [0.0, 1.0] - use directly
        double cpuUtilization = getHost().getUtilizationOfCpu();

        // Memory: getUtilizationOfRam() returns MB USED (not percentage!), convert to
        // percentage [0.0, 1.0]
        double ramUsedMb = getHost().getUtilizationOfRam();
        int totalRamMb = getHost().getRam();
        double ramUtilization = (totalRamMb > 0) ? (ramUsedMb / totalRamMb) : 0.0;
        // Clamp to valid range
        if (ramUtilization < 0.0)
            ramUtilization = 0.0;
        if (ramUtilization > 1.0)
            ramUtilization = 1.0;

        reportTaskCompletion(tuple, null, success, executionTime, false, cpuUtilization, ramUtilization);
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
     * Uses scheduling duration from first to last decision for accurate calculation
     */
    public double getSchedulingThroughput() {
        long totalDecisions = getTotalSchedulingDecisions();
        if (totalDecisions == 0) {
            return 0.0;
        }

        // Get scheduling duration from statistics manager
        double schedulingDuration = RLStatisticsManager.getInstance().getSchedulingDuration();
        if (schedulingDuration <= 0) {
            // Fallback: use current simulation time or config
            double simulationTime = CloudSim.clock();
            if (simulationTime <= 0) {
                // Use config value as last resort
                simulationTime = org.fog.utils.Config.SIMULATION_TIME;
            }
            if (simulationTime <= 0) {
                // Final fallback: use MAX_SIMULATION_TIME
                simulationTime = org.fog.utils.Config.MAX_SIMULATION_TIME;
            }
            if (simulationTime <= 0) {
                return 0.0;
            }
            return totalDecisions / simulationTime;
        }

        // Throughput = decisions / simulation_seconds
        return totalDecisions / schedulingDuration;
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
     * Handle gRPC scheduler response event
     * 
     * PURPOSE: This handler ONLY confirms that a task was successfully added to the
     * server's queue.
     * It does NOT handle cache operations or trigger task execution.
     * 
     * FLOW 1 (AddTaskToQueue Response):
     * - Task sent to server via AddTaskToQueue
     * - Response confirms task was added to server's queue
     * - We only need to: confirm success, record metrics, cleanup
     * PendingSchedulingRequest
     * 
     * NOTE: Task execution happens in FLOW 2 (Scheduled Queue Polling) via
     * GetSortedQueue
     */
    private void handleGrpcSchedulerResponse(SimEvent ev) {
        PendingSchedulingRequest pending = (PendingSchedulingRequest) ev.getData();
        double currentTime = CloudSim.clock();

        // Extract cloudletId from response (unique identifier) or fallback to task
        // metadata
        String cloudletId = null;
        try {
            AddTaskToQueueResponse response = pending.getFuture().get();
            if (response != null && !response.getCloudletId().isEmpty()) {
                cloudletId = response.getCloudletId();
            }
        } catch (Exception e) {
            // Response not ready yet, will extract later
        }

        // Fallback: Extract from task metadata if not in response
        if (cloudletId == null || cloudletId.isEmpty()) {
            if (pending.getTask() != null && pending.getTask().getMetadataMap() != null) {
                cloudletId = pending.getTask().getMetadataMap().get("cloudlet_id");
            }
        }

        // Final fallback: use taskId (should not happen, but safety check)
        if (cloudletId == null || cloudletId.isEmpty()) {
            cloudletId = pending.getTaskId();
            logger.warning(String.format(
                    "[GRPC-SCHEDULER-RESPONSE] cloudlet_id not found in response or metadata, using taskId as fallback: %s",
                    cloudletId));
        }

        logger.info(String.format(
                "[GRPC-SCHEDULER-RESPONSE] Time: %.2f - Processing async scheduler response for cloudletId: %s",
                currentTime, cloudletId));

        // State Management - Validate pending request exists using cloudletId
        PendingSchedulingRequest storedPending = pendingSchedulingRequests.get(cloudletId);
        if (storedPending == null) {
            logger.warning("Pending request not found for cloudletId: " + cloudletId + " (may be orphaned, total pending: " + pendingSchedulingRequests.size() + ")");
        } else if (storedPending != pending) {
            logger.warning("Pending request mismatch for cloudletId: " + cloudletId + " (stored != event)");
        }

        try {
            // Check if future completed successfully
            if (pending.getFuture().isCompletedExceptionally()) {
                logger.severe(String.format(
                        "[GRPC-SCHEDULER-RESPONSE] Time: %.2f - Async scheduler call failed for cloudletId: %s",
                        currentTime, cloudletId));
                pendingSchedulingRequests.remove(cloudletId);
                return;
            }

            // Get response (should be available now)
            AddTaskToQueueResponse response = pending.getFuture().get();

            // Update cloudletId from response if available (more reliable than metadata)
            if (response != null && !response.getCloudletId().isEmpty()) {
                cloudletId = response.getCloudletId();
            }

            // Calculate actual latency and energy/cost
            // NOTE: These metrics are recorded in RLStatisticsManager for
            // statistics/reporting only.
            // They do NOT affect the simulation execution - they're just metrics tracking.
            long realLatency = System.currentTimeMillis() - pending.getRealStartTime();
            double simulationLatency = NetworkLatencyConverter.convertToSimulationTime(realLatency);

            // Estimate message size (same as in SchedulerClient)
            long messageSizeBytes = estimateMessageSize(pending.getTask());
            double actualEnergy = NetworkEnergyCostCalculator.calculateNetworkEnergy(
                    simulationLatency, messageSizeBytes);
            double actualCost = NetworkEnergyCostCalculator.calculateNetworkCost(
                    simulationLatency, messageSizeBytes);

            // Record energy and cost in statistics (for reporting/metrics only - does not
            // affect simulation)
            RLStatisticsManager.getInstance().addSchedulingEnergy(actualEnergy);
            RLStatisticsManager.getInstance().addSchedulingCost(actualCost);
            RLStatisticsManager.getInstance().addSchedulingLatency(realLatency);
            RLStatisticsManager.getInstance().recordSchedulingDecision();

            logger.info(String.format(
                    "[GRPC-SCHEDULER-RESPONSE] Time: %.2f - cloudletId: %s, Success: %s, Latency: %dms (sim: %.4f sec), Energy: %.6f J, Cost: %.8f $",
                    currentTime, cloudletId, response.getSuccess(), realLatency, simulationLatency, actualEnergy,
                    actualCost));

            // Confirm task was added to server's queue
            if (response.getSuccess()) {
                logger.info(String.format(
                        "[GRPC-SCHEDULER-RESPONSE] Time: %.2f - cloudletId %s successfully added to server's queue",
                        currentTime, cloudletId));
            } else {
                logger.warning(String.format(
                        "[GRPC-SCHEDULER-RESPONSE] Time: %.2f - cloudletId %s FAILED to be added to server's queue: %s",
                        currentTime, cloudletId, response.getErrorMessage()));
            }

            // Remove from pending requests using cloudletId
            pendingSchedulingRequests.remove(cloudletId);

        } catch (Exception e) {
            logger.severe(String.format(
                    "[GRPC-SCHEDULER-RESPONSE] Time: %.2f - Error processing async scheduler response for cloudletId: %s - %s",
                    currentTime, cloudletId, e.getMessage()));
            e.printStackTrace();
            pendingSchedulingRequests.remove(cloudletId);
        }
    }

    /**
     * Handle gRPC scheduler timeout event
     * Processes timeout for async scheduling request and falls back to local
     * scheduling
     */
    private void handleGrpcSchedulerTimeout(SimEvent ev) {
        PendingSchedulingRequest pending = (PendingSchedulingRequest) ev.getData();

        // Extract cloudletId from task metadata (unique identifier)
        String cloudletId = null;
        if (pending.getTask() != null && pending.getTask().getMetadataMap() != null) {
            cloudletId = pending.getTask().getMetadataMap().get("cloudlet_id");
        }

        // Fallback to taskId if cloudletId not found
        if (cloudletId == null || cloudletId.isEmpty()) {
            cloudletId = pending.getTaskId();
            logger.warning(String.format(
                    "[GRPC-SCHEDULER-TIMEOUT] cloudlet_id not found in metadata, using taskId as fallback: %s",
                    cloudletId));
        }

        double currentTime = CloudSim.clock();

        logger.warning(String.format(
                "[GRPC-SCHEDULER-TIMEOUT] Time: %.2f - Scheduler call timed out for cloudletId: %s",
                currentTime, cloudletId));

        // Check if request already completed (race condition: response arrived before
        // timeout)
        if (pending.getFuture().isDone() && !pending.getFuture().isCompletedExceptionally()) {
            logger.info(String.format(
                    "[GRPC-SCHEDULER-TIMEOUT] Time: %.2f - cloudletId %s already completed, ignoring timeout",
                    currentTime, cloudletId));
            return;
        }

        // Clean up pending request
        pendingSchedulingRequests.remove(cloudletId);
    }

    /**
     * Create fallback scheduling response
     */
    private AddTaskToQueueResponse createFallbackSchedulingResponse(Task task) {
        // Use first available node as fallback
        List<FogNode> availableNodes = getAvailableFogNodes();
        String fallbackNodeId = availableNodes.isEmpty() ? "fallback-node-1" : availableNodes.get(0).getNodeId();

        long schedulingDelay = EnhancedConfigurationLoader.getGrpcConfigLong(
                "grpc.fallback.scheduling.delay", 1000);

        return AddTaskToQueueResponse.newBuilder()
                .setTaskId(task.getTaskId())
                .setSuccess(true)
                .setQueuePosition(1)
                .setEstimatedWaitTimeMs(schedulingDelay)
                .setIsCachedTask(false)
                .setCacheAction(CacheAction.CACHE_ACTION_NONE)
                .setErrorMessage("Using fallback scheduling - gRPC timeout")
                .build();
    }

    /**
     * Get available fog nodes (helper for fallback)
     */
    private List<FogNode> getAvailableFogNodes() {
        // Return current device as available node
        ResourceCapacity capacity = ResourceCapacity.newBuilder()
                .setCpuCores(getHost().getNumberOfPes())
                .setMemoryMb(getHost().getRam())
                .build();

        ResourceUsage usage = ResourceUsage.newBuilder()
                .setCpuUsage(0)
                .setMemoryUsageMb(0)
                .build();

        FogNode node = FogNode.newBuilder()
                .setNodeId(String.valueOf(getId()))
                .setNodeName(getName())
                .setCapacity(capacity)
                .setCurrentUsage(usage)
                .setStatus(NodeStatus.NODE_STATUS_ACTIVE)
                .build();

        List<FogNode> nodes = new ArrayList<>();
        nodes.add(node);
        return nodes;
    }

    /**
     * Estimate message size for energy/cost calculation (helper method)
     */
    private long estimateMessageSize(Task task) {
        // Rough estimation: task proto size
        long size = 100; // Base overhead
        size += task.getSerializedSize(); // Task size
        return size;
    }

    // ===== STATE MANAGEMENT HELPERS =====

    /**
     * Store pending scheduling request
     * Should be called when async scheduling request is made
     * 
     * @param pending Pending scheduling request to store
     */
    public void storePendingSchedulingRequest(PendingSchedulingRequest pending) {
        if (pending != null) {
            // Extract cloudletId from task metadata (unique identifier)
            String cloudletId = null;
            if (pending.getTask() != null && pending.getTask().getMetadataMap() != null) {
                cloudletId = pending.getTask().getMetadataMap().get("cloudlet_id");
            }

            // Fallback to taskId if cloudletId not found (should not happen, but safety
            // check)
            if (cloudletId == null || cloudletId.isEmpty()) {
                cloudletId = pending.getTaskId();
                logger.warning("cloudlet_id not found in metadata, using taskId as fallback: " + cloudletId);
            }

            pendingSchedulingRequests.put(cloudletId, pending);
        } else {
            logger.warning("Attempted to store null pending request");
        }
    }

    /**
     * Get pending scheduling request
     * 
     * @param cloudletId Cloudlet identifier (unique instance ID)
     * @return Pending request or null if not found
     */
    public PendingSchedulingRequest getPendingSchedulingRequest(String cloudletId) {
        PendingSchedulingRequest pending = pendingSchedulingRequests.get(cloudletId);
        if (pending == null) {
            logger.warning("Pending request not found for cloudletId: " + cloudletId + " (total pending: " + pendingSchedulingRequests.size() + ")");
        }
        return pending;
    }

    /**
     * Cleanup orphaned pending requests
     * Removes requests that are older than specified timeout
     * 
     * @param timeoutMs Timeout in milliseconds
     */
    public void cleanupOrphanedPendingRequests(long timeoutMs) {
        long currentTime = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, PendingSchedulingRequest> entry : pendingSchedulingRequests.entrySet()) {
            PendingSchedulingRequest pending = entry.getValue();
            long age = currentTime - pending.getRealStartTime();
            if (age > timeoutMs) {
                toRemove.add(entry.getKey());
                logger.warning(String.format(
                        "[STATE-MGMT] Cleaning up orphaned pending request for task: %s (age: %dms)",
                        entry.getKey(), age));
            }
        }

        for (String cloudletId : toRemove) {
            pendingSchedulingRequests.remove(cloudletId);
        }

        if (!toRemove.isEmpty()) {
            logger.info(String.format(
                    "[STATE-MGMT] Cleaned up %d orphaned pending requests (remaining: %d)",
                    toRemove.size(), pendingSchedulingRequests.size()));
        }
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
     * Handle TUPLE_COMPLETE event - update fog device status when tuple completes
     * This ensures CPU/memory/energy utilization is updated after execution
     * IMPORTANT: Only called for non-cached tasks (cached tasks skip execution
     * entirely)
     * 
     * Now also reports task completion to scheduler with actual execution metrics
     */
    private void handleTupleComplete(SimEvent ev) {
        Tuple completedTuple = (Tuple) ev.getData();
        if (completedTuple == null) {
            logger.warning("TUPLE_COMPLETE event received with null tuple");
            return;
        }


        // Mark tuple as completed using iFogSim's TimeKeeper
        org.fog.utils.TimeKeeper.getInstance().tupleEndedExecution(completedTuple);

        // Find the VM that processed this tuple
        int vmId = completedTuple.getVmId();
        Vm targetVm = null;
        for (Vm vm : getHost().getVmList()) {
            if (vm.getId() == vmId) {
                targetVm = vm;
                break;
            }
        }

        // Update fog device status when tuple completes
        // This ensures CPU/memory/energy utilization is updated after execution
        if (targetVm != null && targetVm instanceof org.fog.application.AppModule) {
            // Update VM processing status (AppModule has updateVmProcessing method)
            org.fog.application.AppModule appModule = (org.fog.application.AppModule) targetVm;
            appModule.updateVmProcessing(CloudSim.clock(),
                    getHost().getVmScheduler().getAllocatedMipsForVm(targetVm));

            // Update energy consumption (this will be handled by iFogSim's internal
            // mechanisms)
            // Note: updateEnergyConsumption() is private in FogDevice, but it's called
            // internally
            // by iFogSim when VM processing is updated
            logger.fine("Updated fog device status after tuple " + completedTuple.getCloudletId() + " completion");
        } else {
            logger.warning(
                    "Could not find VM with ID " + vmId + " for completed tuple " + completedTuple.getCloudletId());
        }

        // Report task completion to scheduler with actual execution metrics
        if (taskExecutionEngine != null) {
            try {
                long cloudletId = completedTuple.getCloudletId();

                // Get task execution state by cloudletId
                org.patch.processing.TaskExecutionEngine.TaskExecutionState state = taskExecutionEngine
                        .getTaskByCloudletId(cloudletId);

                if (state != null) {
                    // Check if already reported (prevent duplicates)
                    if (state.isReportedCompletion()) {
                        logger.warning("Task " + cloudletId
                                + " completion already reported, skipping duplicate");
                        return;
                    }

                    // Calculate actual execution time from tuple's execution times
                    // These are set by CloudSim scheduler during actual execution
                    double execStartTime = completedTuple.getExecStartTime();
                    double finishTime = completedTuple.getFinishTime();
                    long executionTime = 0;
                    boolean success = false;

                    if (finishTime > 0 && execStartTime > 0) {
                        executionTime = (long) (finishTime - execStartTime);
                        success = executionTime > 0;
                    } else {
                        // Fallback: use current time - start time from state
                        executionTime = (long) (CloudSim.clock() - state.getStartTime());
                        success = executionTime > 0;
                        logger.warning("Tuple execution times not set, using fallback calculation for task "
                                + cloudletId);
                    }

                    // Use captured utilization if available (actual usage during execution)
                    double cpuUtilization;
                    double ramUtilization;

                    if (state.isUtilizationCaptured()) {
                        cpuUtilization = state.getCapturedCpuUtilization();
                        ramUtilization = state.getCapturedRamUtilization();
                    } else {
                        // Fallback: calculate from task requirements (approximation)
                        double[] utilization = taskExecutionEngine
                                .calculateUtilizationFromTaskRequirements(completedTuple);
                        cpuUtilization = utilization[0];
                        ramUtilization = utilization[1];
                        logger.warning("Utilization not captured for task " + cloudletId + ", using calculated values");
                    }

                    // Get TaskInfo from state
                    org.patch.models.ScheduledQueue.TaskInfo taskInfo = state.getTaskInfo();

                    // Determine if task was cached
                    boolean isCached = taskInfo.isCachedTask();

                    // Get cache action to check if we should store result
                    org.patch.proto.IfogsimCommon.CacheAction cacheAction = taskInfo.getCacheAction();

                    // CRITICAL: Store actual execution result in cache if scheduler said
                    // CACHE_ACTION_STORE
                    // This stores the REAL execution result (not the scheduling response)
                    if (cacheAction == org.patch.proto.IfogsimCommon.CacheAction.CACHE_ACTION_STORE &&
                            taskExecutionEngine != null && taskExecutionEngine.getCacheManager() != null) {
                        try {
                            // Create result map with actual execution data
                            java.util.Map<String, Object> executionResult = new java.util.concurrent.ConcurrentHashMap<>();
                            executionResult.put("taskId", String.valueOf(cloudletId));
                            executionResult.put("success", success);
                            executionResult.put("executionTime", executionTime);
                            executionResult.put("cpuUtilization", cpuUtilization);
                            executionResult.put("ramUtilization", ramUtilization);
                            executionResult.put("timestamp", CloudSim.clock());
                            executionResult.put("deviceId", getId());
                            executionResult.put("nodeName", getName());

                            // Store actual execution result (not scheduling response)
                            taskExecutionEngine.getCacheManager().storeInCache(String.valueOf(cloudletId),
                                    executionResult);

                            logger.info(String.format(
                                    "[CACHE-STORE-RESULT] Time: %.2f - FogNode (ID:%d) - Stored ACTUAL execution result for task %d in cache (execTime=%d ms, success=%s)",
                                    CloudSim.clock(), getId(), cloudletId, executionTime, success));
                        } catch (Exception e) {
                            logger.warning("Failed to store execution result in cache for task " + cloudletId + ": "
                                    + e.getMessage());
                        }
                    }

                    // Report completion to scheduler and get ACK
                    boolean ackSuccess = reportTaskCompletion(completedTuple, taskInfo, success, executionTime,
                            isCached,
                            cpuUtilization,
                            ramUtilization);

                    // Use ACK to confirm server processed completion
                    if (ackSuccess) {
                        // Server confirmed: task is removed from server's queue
                        // Mark as reported and remove from activeTasks immediately
                        state.setReportedCompletion(true);
                        taskExecutionEngine.removeTaskAfterCompletion(cloudletId);

                    } else {
                        // ACK failed: keep in activeTasks, might retry later
                        // Duplicate check will prevent re-processing
                        logger.warning(String.format(
                                "[TUPLE-COMPLETE-ACK-FAIL] Time: %.2f - FogNode (ID:%d) - Task %d completion NOT confirmed by server (ACK failed), keeping in activeTasks",
                                CloudSim.clock(), getId(), cloudletId));
                    }

                    logger.info("Task " + cloudletId
                            + " completion reported to scheduler (execTime: " + executionTime + "ms, CPU: "
                            + String.format("%.2f", cpuUtilization * 100) + "%, Memory: "
                            + String.format("%.2f", ramUtilization * 100) + "%)");
                } else {
                    logger.warning("Task execution state not found for cloudletId: " + cloudletId
                            + " - completion not reported");
                }
            } catch (Exception e) {
                logger.severe("Error reporting task completion in TUPLE_COMPLETE handler for task "
                        + completedTuple.getCloudletId() + ": " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            logger.warning("TaskExecutionEngine not available for completion reporting");
        }
    }

    /**
     * Handle cached task result - task is resolved with cached value
     * (Legacy method - kept for compatibility)
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
     * Uses TaskCacheManager for accurate cache hit/miss tracking
     */
    public Map<String, Object> getCacheStatistics() {
        // Use TaskCacheManager statistics (new system) instead of old taskCache
        Map<String, Object> cacheManagerStats = cacheManager.getCacheStats();

        Object hits = cacheManagerStats.get("cacheHits");
        Object misses = cacheManagerStats.get("cacheMisses");
        Object hitRate = cacheManagerStats.get("hitRate");
        Object uniqueTasks = cacheManagerStats.get("uniqueTasks");
        Object repeatRate = cacheManagerStats.get("repeatRate");
        Object uniqueHitRate = cacheManagerStats.get("uniqueHitRate");
        logger.info(String.format(
                "[DEBUG-CACHE-STATS] Device: %s (ID:%d) - Retrieving cache stats: Hits=%s, Misses=%s, HitRate=%.2f%%, UniqueTasks=%s, RepeatRate=%.2f%%, UniqueHitRate=%.2f%%, Size=%s",
                getName(), getId(), hits, misses,
                hitRate != null ? ((Double) hitRate * 100) : 0.0,
                uniqueTasks,
                repeatRate != null ? ((Double) repeatRate * 100) : 0.0,
                uniqueHitRate != null ? ((Double) uniqueHitRate * 100) : 0.0,
                cacheManagerStats.get("cacheSize")));

        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", cacheManagerStats.get("cacheSize"));
        stats.put("maxCacheSize", MAX_CACHE_SIZE);
        // Map TaskCacheManager keys to expected keys
        stats.put("cacheHitCount", hits);
        stats.put("cacheMissCount", misses);
        stats.put("cacheHitRate", hitRate);
        // New metrics
        stats.put("uniqueTasks", uniqueTasks);
        stats.put("repeatRate", repeatRate);
        stats.put("uniqueHitRate", uniqueHitRate);
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
        double currentTime = CloudSim.clock();

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
        // IMPORTANT: Tasks are NOT removed from unscheduled queue here!
        schedulerIntegration.sendTasksToScheduler();

        // Schedule queue update from scheduler
        schedule(getId(), 100, RL_UPDATE_SCHEDULED_QUEUE);

        logger.info("External task " + externalTask.getCloudletId() + " added to unscheduled queue");
    }

    /**
     * Override processModuleArrival to ensure proper VM creation and
     * appToModulesMap population
     * Verifies VM allocation success and adds debug logging
     */
    @Override
    protected void processModuleArrival(SimEvent ev) {
        AppModule module = (AppModule) ev.getData();
        String appId = module.getAppId();

        // Check for duplicate modules before creating
        if (appToModulesMap.containsKey(appId) &&
                appToModulesMap.get(appId).contains(module.getName())) {
            logger.warning("Module " + module.getName() + " already deployed on " + getName());
            return;
        }

        // Initialize appToModulesMap if needed
        if (!appToModulesMap.containsKey(appId)) {
            appToModulesMap.put(appId, new ArrayList<String>());
        }
        appToModulesMap.get(appId).add(module.getName());

        // Call parent's processVmCreate
        processVmCreate(ev, false);

        // Verify VM allocation succeeded
        boolean vmAllocated = getVmAllocationPolicy().allocateHostForVm(module);

        if (vmAllocated) {
            // Verify VM is in the VM list
            if (!getHost().getVmList().contains(module)) {
                getHost().getVmList().add(module);
            }

            if (module.isBeingInstantiated()) {
                module.setBeingInstantiated(false);
            }

            initializePeriodicTuples(module);

            module.updateVmProcessing(CloudSim.clock(), getVmAllocationPolicy().getHost(module).getVmScheduler()
                    .getAllocatedMipsForVm(module));

            logger.info("Module " + module.getName() + " successfully deployed on " + getName() + " (VM ID: "
                    + module.getId() + ")");
        } else {
            logger.severe("VM allocation failed for module " + module.getName() + " on " + getName());
            // Remove from appToModulesMap since VM creation failed
            appToModulesMap.get(appId).remove(module.getName());
            if (appToModulesMap.get(appId).isEmpty()) {
                appToModulesMap.remove(appId);
            }
        }
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
        // Extract cloudletId from metadata (same as TupleFactory for consistency)
        int cloudletId;
        if (protoTask.getMetadataMap() != null && protoTask.getMetadataMap().containsKey("cloudlet_id")) {
            String cloudletIdStr = protoTask.getMetadataMap().get("cloudlet_id");
            if (cloudletIdStr != null && !cloudletIdStr.isEmpty()) {
                try {
                    cloudletId = Integer.parseInt(cloudletIdStr);
                } catch (NumberFormatException e) {
                    logger.severe(String.format(
                            "[RLFOGDEVICE] Failed to parse cloudlet_id='%s' for TaskId=%s",
                            cloudletIdStr, protoTask.getTaskId()));
                    return null; // Cannot create without valid cloudletId
                }
            } else {
                logger.severe(String.format(
                        "[RLFOGDEVICE] cloudlet_id in metadata is null/empty for TaskId=%s",
                        protoTask.getTaskId()));
                return null;
            }
        } else {
            logger.severe(String.format(
                    "[RLFOGDEVICE] cloudlet_id not found in metadata for TaskId=%s",
                    protoTask.getTaskId()));
            return null;
        }

        // Convert cpu_requirement (MI) to cloudletLength (MI) - direct mapping
        long cloudletLength = protoTask.getCpuRequirement();
        if (cloudletLength <= 0) {
            logger.warning(String.format(
                    "[RLFOGDEVICE] TaskId=%s has invalid cpu_requirement=%d, using default 1000 MI",
                    protoTask.getTaskId(), cloudletLength));
            cloudletLength = 1000; // Default minimum
        }

        // Convert memory_requirement (MB) to cloudletFileSize (bytes)
        long cloudletFileSize = protoTask.getMemoryRequirement() * 1024 * 1024;
        if (cloudletFileSize <= 0) {
            logger.warning(String.format(
                    "[RLFOGDEVICE] TaskId=%s has invalid memory_requirement=%d MB, using default 1 MB",
                    protoTask.getTaskId(), protoTask.getMemoryRequirement()));
            cloudletFileSize = 1024 * 1024; // Default 1 MB in bytes
        }

        // Use output_size from proto Task (in bytes)
        long cloudletOutputSize = protoTask.getOutputSize();
        if (cloudletOutputSize <= 0) {
            // Fallback: estimate from memory_requirement if output_size not provided
            logger.warning(String.format(
                    "[RLFOGDEVICE] TaskId=%s has invalid output_size=%d, estimating from memory_requirement",
                    protoTask.getTaskId(), cloudletOutputSize));
            cloudletOutputSize = protoTask.getMemoryRequirement() * 1024 * 1024; // Estimate from input
            if (cloudletOutputSize <= 0) {
                cloudletOutputSize = 1024 * 1024; // Default 1 MB in bytes
            }
        }

        // Create tuple with correct parameters in constructor
        Tuple mockTuple = new Tuple(
                "external-app", // appId
                cloudletId, // ✅ Extracted from metadata (not TaskId!)
                Tuple.UP, // direction
                cloudletLength, // ✅ cpu_requirement (MI)
                1, // pesNumber
                cloudletFileSize, // ✅ memory_requirement converted to bytes
                cloudletOutputSize, // ⚠️ Estimated from memory_requirement
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
                "", // No cache key
                String.valueOf(mockTuple.getCloudletId()), // ✅ Use cloudletId as taskId (fallback for external tasks)
                org.patch.proto.IfogsimCommon.CacheAction.CACHE_ACTION_NONE // ✅ Default cache action
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