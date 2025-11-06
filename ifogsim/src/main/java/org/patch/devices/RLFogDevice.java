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

    // Debug counters for task tracking
    private int internalTaskCount = 0;
    private int externalTaskCount = 0;

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

        // Initialize task execution engine
        this.taskExecutionEngine = new TaskExecutionEngine(
                this, scheduledQueue, schedulerClient, null, cacheManager);

        // Initialize streaming queue observer
        this.streamingObserver = new StreamingQueueObserver(
                schedulerClient, scheduledQueue, getId());

        // Set device entity for CloudSim event scheduling
        this.streamingObserver.setDeviceEntity(this);

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
        if ((isFromCloud && (externalTaskCount < 20 || externalTaskCount % 10 == 0)) ||
                (isFromSensor && (internalTaskCount < 20 || internalTaskCount % 10 == 0))) {
            System.out.println(String.format(
                    "[TASK-FLOW] FogNode %s (ID:%d) - Received task %d at time %.2f - Source: %s (Cloud:%s, Sensor:%s)",
                    getName(), getId(), tuple.getCloudletId(), CloudSim.clock(), sourceName, isFromCloud,
                    isFromSensor));
        }

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

            // [DEBUG] Log external task arrival at fog node
            System.out.println(String.format(
                    "[FLOW-FOG-ARRIVAL-EXTERNAL] Time: %.2f - FogNode %s (ID:%d) received EXTERNAL task %d from cloud (TupleType:%s, DestModule:%s, Total external: %d) - Task allocated by cloud allocator",
                    CloudSim.clock(), getName(), getId(), tuple.getCloudletId(),
                    tuple.getTupleType(), tuple.getDestModuleName(), externalTaskCount));

            // Process external task arrival
            processExternalTaskArrival(ev);
            return;
        }

        // Check if this tuple's destination module is on this device
        // Debug: Log module matching for internal tasks
        boolean appIdInMap = appToModulesMap.containsKey(tuple.getAppId());
        boolean moduleInApp = appIdInMap && appToModulesMap.get(tuple.getAppId()).contains(tuple.getDestModuleName());

        if (isFromSensor && (internalTaskCount <= 20 || internalTaskCount % 10 == 0)) {
            System.out.println(String.format(
                    "[MODULE-MATCH] FogNode %s (ID:%d) - Task %d: AppId='%s', DestModule='%s', AppIdInMap=%s, ModuleInApp=%s, AppModules=%s",
                    getName(), getId(), tuple.getCloudletId(), tuple.getAppId(), tuple.getDestModuleName(),
                    appIdInMap, moduleInApp,
                    appIdInMap ? appToModulesMap.get(tuple.getAppId()).toString() : "N/A"));
        }

        if (appToModulesMap.containsKey(tuple.getAppId()) &&
                appToModulesMap.get(tuple.getAppId()).contains(tuple.getDestModuleName())) {

            int vmId = -1;
            for (Vm vm : getHost().getVmList()) {
                if (((AppModule) vm).getName().equals(tuple.getDestModuleName()))
                    vmId = vm.getId();
            }

            if (vmId < 0 || (tuple.getModuleCopyMap().containsKey(tuple.getDestModuleName()) &&
                    tuple.getModuleCopyMap().get(tuple.getDestModuleName()) != vmId)) {
                // Log why VM matching failed
                if (isFromSensor && (internalTaskCount <= 20 || internalTaskCount % 10 == 0)) {
                    System.out.println(String.format(
                            "[MODULE-MATCH] FogNode %s (ID:%d) - Task %d: VM matching FAILED - vmId=%d, ModuleCopyMap=%s",
                            getName(), getId(), tuple.getCloudletId(), vmId, tuple.getModuleCopyMap()));
                }
                return;
            }

            tuple.setVmId(vmId);
            updateTimingsOnReceipt(tuple);

            // Add to unscheduled queue (waiting for scheduler)
            internalTaskCount++;
            double queueAddTime = CloudSim.clock();
            unscheduledQueue.addTask(tuple, vmId, queueAddTime);

            // [DEBUG] Log unscheduled queue addition (internal task)
            System.out.println(String.format(
                    "[FLOW-FOG-UNSCHEDULED] Time: %.2f - FogNode %s (ID:%d) - Added INTERNAL task %d to unscheduled queue (VM:%d). Queue size: %d, Total internal: %d",
                    queueAddTime, getName(), getId(), tuple.getCloudletId(), vmId, unscheduledQueue.size(),
                    internalTaskCount));

            // Log unscheduled queue state (every 10th task or first 20)
            if (internalTaskCount <= 20 || internalTaskCount % 10 == 0) {
                System.out.println(String.format(
                        "[UNSCHEDULED-QUEUE] FogNode %s (ID:%d) - Added internal task %d to unscheduled queue. Queue size: %d, Total internal tasks: %d",
                        getName(), getId(), tuple.getCloudletId(), unscheduledQueue.size(), internalTaskCount));
            }

            // [DEBUG] Log sending to scheduler (NOTE: task stays in unscheduled queue!)
            System.out.println(String.format(
                    "[FLOW-FOG-SCHEDULER-SEND] Time: %.2f - FogNode %s (ID:%d) - Sending INTERNAL task %d to scheduler (task REMAINS in unscheduled queue, size: %d)",
                    CloudSim.clock(), getName(), getId(), tuple.getCloudletId(), unscheduledQueue.size()));

            // Send tasks to scheduler gRPC server (non-blocking)
            // IMPORTANT: Tasks are NOT removed from unscheduled queue here!
            schedulerIntegration.sendTasksToScheduler();

            // Schedule task processing from scheduled queue
            if (scheduledQueue.size() > 0) {
                schedule(getId(), 0, RL_PROCESS_NEXT_TASK);
            }
        } else if (tuple.getDestModuleName() != null) {
            // Module not found on this device - forward it
            System.out.println(String.format(
                    "[FLOW-FOG-PROCESS-TUPLE-DEBUG] Time: %.2f - FogNode %s (ID:%d) - Module '%s' not found on this device, will forward",
                    CloudSim.clock(), getName(), getId(), tuple.getDestModuleName()));
            if (isFromSensor && (internalTaskCount <= 20 || internalTaskCount % 10 == 0)) {
                System.out.println(String.format(
                        "[MODULE-MATCH] FogNode %s (ID:%d) - Task %d: Module '%s' NOT on this device, FORWARDING (dir=%d)",
                        getName(), getId(), tuple.getCloudletId(), tuple.getDestModuleName(), tuple.getDirection()));
            }
            if (tuple.getDirection() == Tuple.UP)
                sendUp(tuple);
            else if (tuple.getDirection() == Tuple.DOWN) {
                for (int childId : getChildrenIds())
                    sendDown(tuple, childId);
            }
        } else {
            if (isFromSensor && (internalTaskCount <= 20 || internalTaskCount % 10 == 0)) {
                System.out.println(String.format(
                        "[MODULE-MATCH] FogNode %s (ID:%d) - Task %d: DestModuleName is NULL, sending UP",
                        getName(), getId(), tuple.getCloudletId()));
            }
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

        // [DEBUG] Log callback invocation
        System.out.println(String.format(
                "[FLOW-FOG-QUEUE-CALLBACK] Time: %.2f - FogNode %s (ID:%d) - onQueueUpdated CALLED (queue size: %d)",
                currentTime, getName(), getId(), queueSize));

        logger.fine("Queue updated, triggering task processing for device: " + getId());

        // Schedule task processing if there are tasks in the queue
        if (!updatedQueue.isEmpty()) {
            System.out.println(String.format(
                    "[FLOW-FOG-QUEUE-CALLBACK-SCHEDULE] Time: %.2f - FogNode %s (ID:%d) - Scheduling RL_PROCESS_NEXT_TASK from callback (queue size: %d)",
                    currentTime, getName(), getId(), queueSize));
            schedule(getId(), 0, RL_PROCESS_NEXT_TASK);
        } else {
            System.out.println(String.format(
                    "[FLOW-FOG-QUEUE-CALLBACK-EMPTY] Time: %.2f - FogNode %s (ID:%d) - Queue is EMPTY in callback - NOT scheduling task processing",
                    currentTime, getName(), getId()));
        }
    }

    /**
     * Process the next task from scheduled queue using TaskExecutionEngine
     */
    private void processNextTaskRL() {
        double currentTime = CloudSim.clock();

        // [DEBUG] Log scheduled queue status before execution attempt
        if (scheduledQueue != null) {
            int scheduledQueueSize = scheduledQueue.size();
            System.out.println(String.format(
                    "[FLOW-FOG-EXECUTE-START] Time: %.2f - FogNode %s (ID:%d) - Attempting to process next task from scheduled queue (queue size: %d)",
                    currentTime, getName(), getId(), scheduledQueueSize));
        } else {
            System.err.println(String.format(
                    "[FLOW-FOG-EXECUTE-START] Time: %.2f - FogNode %s (ID:%d) - ERROR: Scheduled queue is NULL!",
                    currentTime, getName(), getId()));
        }

        if (taskExecutionEngine == null) {
            logger.warning("TaskExecutionEngine not initialized");
            return;
        }

        // Use the task execution engine to process the next task
        boolean taskProcessed = taskExecutionEngine.processNextTask();

        // [DEBUG] Log execution result
        System.out.println(String.format(
                "[FLOW-FOG-EXECUTE-RESULT] Time: %.2f - FogNode %s (ID:%d) - processNextTask returned: %s (scheduled queue size now: %d)",
                CloudSim.clock(), getName(), getId(), taskProcessed ? "SUCCESS" : "FAILED/EMPTY",
                scheduledQueue != null ? scheduledQueue.size() : -1));

        if (taskProcessed) {
            // Update scheduling metrics
            RLStatisticsManager.getInstance().incrementSchedulingDecisions();
            RLStatisticsManager.getInstance().incrementSuccessfulScheduling();

            // If there are more tasks in scheduled queue, schedule the next processing
            if (!scheduledQueue.isEmpty()) {
                System.out.println(String.format(
                        "[FLOW-FOG-SCHEDULE-NEXT] Time: %.2f - FogNode %s (ID:%d) - Scheduling next task processing (queue size: %d)",
                        CloudSim.clock(), getName(), getId(), scheduledQueue.size()));
                schedule(getId(), 0, RL_PROCESS_NEXT_TASK);
            } else {
                System.out.println(String.format(
                        "[FLOW-FOG-SCHEDULE-NEXT] Time: %.2f - FogNode %s (ID:%d) - NOT scheduling next task - queue is EMPTY",
                        CloudSim.clock(), getName(), getId()));
            }
        } else {
            // [DEBUG] Log why task wasn't processed
            if (scheduledQueue == null) {
                System.err.println(String.format(
                        "[FLOW-FOG-EXECUTE-FAIL] Time: %.2f - FogNode %s (ID:%d) - Task NOT processed - scheduled queue is NULL",
                        CloudSim.clock(), getName(), getId()));
            } else if (scheduledQueue.isEmpty()) {
                System.out.println(String.format(
                        "[FLOW-FOG-EXECUTE-FAIL] Time: %.2f - FogNode %s (ID:%d) - Task NOT processed - scheduled queue is EMPTY",
                        CloudSim.clock(), getName(), getId()));
            } else {
                System.err.println(String.format(
                        "[FLOW-FOG-EXECUTE-FAIL] Time: %.2f - FogNode %s (ID:%d) - Task NOT processed - queue has %d tasks but processNextTask returned false",
                        CloudSim.clock(), getName(), getId(), scheduledQueue.size()));
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
     * 
     * @param tuple         The tuple that was processed
     * @param success       Whether the task completed successfully
     * @param executionTime Execution time in milliseconds (0 for cached tasks)
     * @param isCached      Whether this task was served from cache (instant
     *                      execution)
     */
    public void reportTaskCompletion(Tuple tuple, boolean success, long executionTime, boolean isCached) {
        if (schedulerClient == null || !schedulerClient.isConnected()) {
            return;
        }

        try {
            // For cached tasks, report with executionTime = 0 to indicate instant cache hit
            // For non-cached tasks, report actual execution time
            long reportedExecutionTime = isCached ? 0 : executionTime;

            // Report to grpc-task-scheduler for learning
            TaskCompletionReport report = TaskCompletionReport.newBuilder()
                    .setTaskId(String.valueOf(tuple.getCloudletId()))
                    .addTasks(CompletedTask.newBuilder()
                            .setTaskId(String.valueOf(tuple.getCloudletId()))
                            .setAssignedNodeId(String.valueOf(getId()))
                            .setActualExecutionTimeMs(reportedExecutionTime)
                            .setDeadlineMet(success)
                            .build())
                    .setCompletionTimestamp(System.currentTimeMillis())
                    .build();

            schedulerClient.reportTaskCompletion(report);

            if (isCached) {
                logger.info("Reported CACHED task completion to scheduler (instant): " + tuple.getCloudletId());
            } else {
                logger.info("Reported task completion to scheduler (executed): " + tuple.getCloudletId());
            }

        } catch (Exception e) {
            logger.severe("Failed to report task completion to scheduler: " + e.getMessage());
        }
    }

    /**
     * Report task completion for RL learning to grpc-task-scheduler (legacy method
     * for backward compatibility)
     * Assumes task was not cached (executionTime > 0)
     */
    public void reportTaskCompletion(Tuple tuple, boolean success, long executionTime) {
        reportTaskCompletion(tuple, success, executionTime, false);
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
     * Get scheduling throughput (decisions per second) (FIX: Phase 2)
     * Uses scheduling duration from first to last decision for accurate calculation
     */
    public double getSchedulingThroughput() {
        long totalDecisions = getTotalSchedulingDecisions();
        if (totalDecisions == 0) {
            return 0.0;
        }
        
        // Get scheduling duration from statistics manager (FIX: Phase 2)
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

        // Note: Task completion reporting is already handled by existing completion
        // logic
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
        double currentTime = CloudSim.clock();

        logger.info("Received external task " + externalTask.getCloudletId() + " from cloud");

        // [DEBUG] Log external task processing start
        System.out.println(String.format(
                "[FLOW-FOG-EXTERNAL] Time: %.2f - FogNode %s (ID:%d) - Processing external task %d from cloud",
                currentTime, getName(), getId(), externalTask.getCloudletId()));

        // Send ACK back to source
        send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ACK);

        // Set default VM ID for external tasks (use first available VM)
        int vmId = -1;
        if (!getHost().getVmList().isEmpty()) {
            vmId = getHost().getVmList().get(0).getId();
        }

        externalTask.setVmId(vmId);
        updateTimingsOnReceipt(externalTask);

        // [DEBUG] Log before adding to unscheduled queue
        System.out.println(String.format(
                "[FLOW-FOG-UNSCHEDULED] Time: %.2f - FogNode %s (ID:%d) - Adding EXTERNAL task %d to unscheduled queue (VM:%d). Current queue size: %d",
                CloudSim.clock(), getName(), getId(), externalTask.getCloudletId(), vmId, unscheduledQueue.size()));

        // Add to unscheduled queue
        unscheduledQueue.addTask(externalTask, vmId, CloudSim.clock());

        // [DEBUG] Log after adding to unscheduled queue
        System.out.println(String.format(
                "[FLOW-FOG-UNSCHEDULED] Time: %.2f - FogNode %s (ID:%d) - EXTERNAL task %d added to unscheduled queue. New queue size: %d",
                CloudSim.clock(), getName(), getId(), externalTask.getCloudletId(), unscheduledQueue.size()));

        // [DEBUG] Log sending to scheduler (NOTE: task stays in unscheduled queue!)
        System.out.println(String.format(
                "[FLOW-FOG-SCHEDULER-SEND] Time: %.2f - FogNode %s (ID:%d) - Sending EXTERNAL task %d to scheduler (task REMAINS in unscheduled queue, size: %d)",
                CloudSim.clock(), getName(), getId(), externalTask.getCloudletId(), unscheduledQueue.size()));

        // Send to scheduler gRPC server
        // IMPORTANT: Tasks are NOT removed from unscheduled queue here!
        schedulerIntegration.sendTasksToScheduler();

        // Schedule queue update from scheduler
        schedule(getId(), 100, RL_UPDATE_SCHEDULED_QUEUE);

        logger.info("External task " + externalTask.getCloudletId() + " added to unscheduled queue");

        // [DEBUG] Confirm external task processing
        System.out.println(String.format(
                "[FLOW-FOG-EXTERNAL] Time: %.2f - FogNode %s (ID:%d) - EXTERNAL task %d processing complete, waiting for scheduler response",
                CloudSim.clock(), getName(), getId(), externalTask.getCloudletId()));
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
            System.out.println(String.format(
                    "[MODULE-DEPLOY] Device %s (ID:%d) - Module '%s' already exists for AppId='%s'",
                    getName(), getId(), module.getName(), appId));
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

            // Debug log to confirm module deployment and appToModulesMap population
            System.out.println(String.format(
                    "[MODULE-DEPLOY] Device %s (ID:%d) - Module '%s' arrived for AppId='%s'. appToModulesMap: %s",
                    getName(), getId(), module.getName(), appId, appToModulesMap.toString()));
            logger.info("Module " + module.getName() + " successfully deployed on " + getName() + " (VM ID: "
                    + module.getId() + ")");
        } else {
            logger.severe("VM allocation failed for module " + module.getName() + " on " + getName());
            System.out.println(String.format(
                    "[MODULE-DEPLOY] Device %s (ID:%d) - Module '%s' VM allocation FAILED for AppId='%s'",
                    getName(), getId(), module.getName(), appId));
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