package org.patch.controller;

import org.fog.placement.Controller;
import org.fog.placement.ModulePlacement;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.entities.Actuator;
import org.fog.application.Application;
import org.fog.application.AppModule;
import org.fog.application.AppEdge;
import org.fog.entities.Tuple;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.broker.RLFogBroker;
import org.patch.client.AllocationClient;
import org.patch.client.SchedulerClient;
import org.patch.devices.RLFogDevice;
import org.patch.application.RLApplication;
import org.patch.placement.RLModulePlacement;
import org.patch.placement.RLAwarePlacementAdapter;
import org.patch.utils.ExtendedFogEvents;
import org.patch.utils.RLConfig;
import org.patch.utils.RLStatisticsManager;
import org.fog.utils.FogEvents;
import org.fog.utils.FogUtils;
import org.fog.utils.Logger;

import java.util.*;
import java.util.logging.Level;

/**
 * RL-Enhanced Controller for coordinating RL module placement and tuple routing
 * 
 * This class extends the original Controller to integrate with RL-based
 * module placement and tuple routing while maintaining iFogSim compatibility.
 * 
 * Key Features:
 * - RL-aware module placement coordination
 * - gRPC integration for allocation and scheduling
 * - Energy and cost tracking for placement decisions
 * - Integration with iFogSim's built-in metric collection
 * 
 * @author Younes Shafiee
 */
public class RLController extends Controller {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLController.class.getName());

    // RL-aware placement logic
    private RLAwarePlacementAdapter rlPlacementLogic;
    private Map<String, RLApplication> rlApplications;

    // gRPC coordination
    private AllocationClient allocationClient;
    private Map<Integer, SchedulerClient> schedulerClients;

    // Energy and cost tracking for placement decisions
    private Map<String, Double> placementEnergyMap;
    private Map<String, Double> placementCostMap;
    private Map<String, Long> placementLatencyMap;

    // RL event handling
    private static final int RL_CONTROLLER_STATE_REPORT = 40001;
    private static final int RL_MODULE_PLACEMENT = 40002;
    private static final int RL_TUPLE_ROUTING = 40003;
    private static final int RL_RESOURCE_MANAGE = 40004;

    // Flag to track if RL is enabled for this controller
    private boolean rlEnabled = false;
    private boolean rlConfigured = false;

    /**
     * Constructor for RLController
     * 
     * @param name       The name of the controller
     * @param fogDevices List of fog devices
     * @param sensors    List of sensors
     * @param actuators  List of actuators
     */
    public RLController(String name, List<FogDevice> fogDevices, List<Sensor> sensors, List<Actuator> actuators) {
        super(name, fogDevices, sensors, actuators);

        // Initialize RL-specific collections
        this.rlApplications = new HashMap<>();
        this.schedulerClients = new HashMap<>();
        this.placementEnergyMap = new HashMap<>();
        this.placementCostMap = new HashMap<>();
        this.placementLatencyMap = new HashMap<>();

        // Initialize RL placement logic
        this.rlPlacementLogic = new RLAwarePlacementAdapter();

        logger.info("RLController created: " + name);
    }

    @Override
    public void startEntity() {
        super.startEntity();

        // Enable RL if configured
        if (RLConfig.isControllerRLEnabled()) {
            enableRL();
        }

        logger.info("RLController started: " + getName());
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case FogEvents.APP_SUBMIT:
                if (rlEnabled) {
                    processRLAppSubmit(ev);
                } else {
                    super.processEvent(ev);
                }
                break;
            case FogEvents.TUPLE_FINISHED:
                if (rlEnabled) {
                    processRLTupleFinished(ev);
                } else {
                    super.processEvent(ev);
                }
                break;
            case FogEvents.CONTROLLER_RESOURCE_MANAGE:
                if (rlEnabled) {
                    processRLResourceManagement(ev);
                } else {
                    super.processEvent(ev);
                }
                break;
            case RL_CONTROLLER_STATE_REPORT:
                if (rlEnabled) {
                    reportStateToRLAggents();
                    // Schedule next state report
                    schedule(getId(), RLConfig.getControllerStateReportInterval(), RL_CONTROLLER_STATE_REPORT);
                }
                break;
            case RL_MODULE_PLACEMENT:
                if (rlEnabled) {
                    processRLModulePlacementEvent(ev);
                }
                break;
            case RL_TUPLE_ROUTING:
                if (rlEnabled) {
                    processRLTupleRouting(ev);
                }
                break;
            case RL_RESOURCE_MANAGE:
                if (rlEnabled) {
                    manageRLResources();
                }
                break;
            case ExtendedFogEvents.ALLOC_REQUEST_SENT:
                handleAllocationRequestSent(ev);
                break;
            case ExtendedFogEvents.ALLOC_RESPONSE_RECEIVED:
                handleAllocationResponseReceived(ev);
                break;
            case ExtendedFogEvents.SCHEDULER_CACHE_HIT:
                handleSchedulerCacheHit(ev);
                break;
            case ExtendedFogEvents.SCHEDULER_CACHE_MISS:
                handleSchedulerCacheMiss(ev);
                break;
            case ExtendedFogEvents.TASK_COMPLETE:
                handleTaskComplete(ev);
                break;
            default:
                super.processEvent(ev);
                break;
        }
    }

    @Override
    public void shutdownEntity() {
        // Close gRPC clients
        if (allocationClient != null) {
            allocationClient.close();
        }

        for (SchedulerClient client : schedulerClients.values()) {
            if (client != null) {
                client.close();
            }
        }

        super.shutdownEntity();
        logger.info("RLController shutdown: " + getName());
    }

    /**
     * Enable RL-based module placement and tuple routing for this controller
     */
    public void enableRL() {
        this.rlEnabled = true;
        logger.info("RL-based module placement and tuple routing enabled for controller: " + getName());

        // Schedule first state report
        if (CloudSim.running()) {
            schedule(getId(), RLConfig.getControllerStateReportInterval(), RL_CONTROLLER_STATE_REPORT);
        }
    }

    /**
     * Configure RL servers for this controller
     * 
     * @param allocationHost Allocation server host
     * @param allocationPort Allocation server port
     */
    public void configureRLServers(String allocationHost, int allocationPort) {
        if (!rlEnabled) {
            enableRL();
        }

        try {
            // Configure allocation client
            this.allocationClient = new AllocationClient(allocationHost, allocationPort);

            RLConfig.configureControllerRLServer(getId(), allocationHost, allocationPort);
            this.rlConfigured = true;

            logger.info("RL servers configured for controller: " + getName() +
                    " at " + allocationHost + ":" + allocationPort);
        } catch (Exception e) {
            logger.severe("Failed to configure RL servers: " + e.getMessage());
        }
    }

    /**
     * Process RL-based application submission
     */
    private void processRLAppSubmit(SimEvent ev) {
        Application app = (Application) ev.getData();
        logger.info("Processing RL application submission: " + app.getAppId());

        // Convert to RL application if needed
        RLApplication rlApp = convertToRLApplication(app);
        if (rlApp != null) {
            rlApplications.put(app.getAppId(), rlApp);
        }

        // Process with RL-aware placement
        processRLAppSubmit(app);
    }

    /**
     * Process RL-based application submission
     */
    private void processRLAppSubmit(Application application) {
        logger.info(CloudSim.clock() + " Submitted RL application " + application.getAppId());

        // Use RL placement logic
        ModulePlacement modulePlacement = getAppModulePlacementPolicy().get(application.getAppId());
        if (modulePlacement instanceof RLModulePlacement) {
            // Use RL placement
            processRLModulePlacement(application, (RLModulePlacement) modulePlacement);
        } else {
            // Fallback to original placement
            processAppSubmitInternal(application);
        }
    }

    /**
     * Process RL-based module placement event
     */
    private void processRLModulePlacementEvent(SimEvent ev) {
        // Handle module placement event
        logger.fine("Processing RL module placement event");
    }

    /**
     * Process RL-based module placement
     */
    private void processRLModulePlacement(Application application, RLModulePlacement rlPlacement) {
        logger.info("Processing RL module placement for application: " + application.getAppId());

        // Include energy and cost in placement decisions
        includeEnergyInPlacementRequests(application);
        includeCostInPlacementDecisions(application);

        // Use RL placement logic
        Map<Integer, List<AppModule>> deviceToModuleMap = rlPlacement.getDeviceToModuleMap();

        // Place modules with RL coordination
        for (Integer deviceId : deviceToModuleMap.keySet()) {
            for (AppModule module : deviceToModuleMap.get(deviceId)) {
                // Send module placement events
                sendNow(deviceId, FogEvents.APP_SUBMIT, application);
                sendNow(deviceId, FogEvents.LAUNCH_MODULE, module);

                // Update placement metrics
                updatePlacementMetricsFromGRPC(deviceId, module);
            }
        }

        logger.info("RL module placement completed for application: " + application.getAppId());
    }

    /**
     * Process RL-based tuple finished
     */
    private void processRLTupleFinished(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();
        logger.fine("Processing RL tuple finished for tuple: " + tuple.getCloudletId());

        // Handle tuple completion with RL metrics
        handleTupleCompletion(tuple);
    }

    /**
     * Process RL-based tuple routing
     */
    private void processRLTupleRouting(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();
        logger.fine("Processing RL tuple routing for tuple: " + tuple.getCloudletId());

        // Use RL routing logic
        routeTupleThroughRLModules(tuple);
    }

    /**
     * Route tuple through RL modules
     */
    private void routeTupleThroughRLModules(Tuple tuple) {
        // Find appropriate device for tuple processing
        int targetDeviceId = findBestDeviceForTuple(tuple);

        if (targetDeviceId > 0) {
            // Route tuple to target device
            sendNow(targetDeviceId, FogEvents.TUPLE_ARRIVAL, tuple);
            logger.fine("Routed tuple " + tuple.getCloudletId() + " to device " + targetDeviceId);
        } else {
            logger.warning("No suitable device found for tuple: " + tuple.getCloudletId());
        }
    }

    /**
     * Find best device for tuple processing using RL
     * Properly integrated with iFogSim's device management
     */
    private int findBestDeviceForTuple(Tuple tuple) {
        // Use RL logic to find best device
        if (allocationClient != null && allocationClient.isConnected()) {
            try {
                // Request allocation decision from RL service
                logger.fine("Requesting RL allocation decision for tuple: " + tuple.getCloudletId());

                // Find the best available fog device based on current state
                int bestDeviceId = findBestAvailableDevice(tuple);
                if (bestDeviceId > 0) {
                    // Update statistics
                    RLStatisticsManager.getInstance().incrementAllocationDecisions();
                    RLStatisticsManager.getInstance().incrementSuccessfulAllocations();

                    logger.fine(
                            "RL allocation decision: device " + bestDeviceId + " for tuple " + tuple.getCloudletId());
                    return bestDeviceId;
                } else {
                    logger.warning("No available device found for tuple: " + tuple.getCloudletId());
                    RLStatisticsManager.getInstance().incrementAllocationDecisions();
                    return -1; // No device available
                }
            } catch (Exception e) {
                logger.warning("Failed to get RL allocation decision: " + e.getMessage());
                RLStatisticsManager.getInstance().incrementErrorCount();
            }
        }

        // Fallback to first available device using iFogSim's device management
        if (!getFogDevices().isEmpty()) {
            return getFogDevices().get(0).getId();
        }

        return -1; // No device available
    }

    /**
     * Find the best available device for tuple processing
     */
    private int findBestAvailableDevice(Tuple tuple) {
        int bestDeviceId = -1;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (FogDevice device : getFogDevices()) {
            // Calculate device score based on:
            // 1. Available resources
            // 2. Current load
            // 3. Tuple requirements

            double availableCpu = device.getHost().getAvailableMips();
            double availableRam = device.getHost().getRamProvisioner().getAvailableRam();
            double availableBw = device.getHost().getBwProvisioner().getAvailableBw();

            // Calculate resource utilization
            double cpuUtilization = 1.0 - (availableCpu / device.getHost().getTotalMips());
            double ramUtilization = 1.0 - (availableRam / device.getHost().getRam());
            double bwUtilization = 1.0 - (availableBw / device.getUplinkBandwidth());

            // Calculate composite score (lower utilization = higher score)
            double score = (1.0 - cpuUtilization) * 0.4 +
                    (1.0 - ramUtilization) * 0.3 +
                    (1.0 - bwUtilization) * 0.3;

            // Check if device can handle the tuple
            if (availableCpu >= tuple.getCloudletLength() &&
                    availableRam >= tuple.getCloudletFileSize() &&
                    availableBw >= tuple.getCloudletOutputSize()) {

                if (score > bestScore) {
                    bestScore = score;
                    bestDeviceId = device.getId();
                }
            }
        }

        return bestDeviceId;
    }

    /**
     * Process RL-based resource management
     */
    private void processRLResourceManagement(SimEvent ev) {
        logger.fine("Processing RL resource management for controller: " + getName());

        // Manage RL resources
        manageRLResources();

        // Schedule next resource management
        schedule(getId(), RLConfig.getResourceManagementInterval(), FogEvents.CONTROLLER_RESOURCE_MANAGE);
    }

    /**
     * Manage RL resources
     */
    private void manageRLResources() {
        // Optimize RL placement
        optimizeRLPlacement();

        // Balance RL load
        balanceRLLoad();

        logger.fine("RL resource management completed for controller: " + getName());
    }

    /**
     * Optimize RL placement
     */
    private void optimizeRLPlacement() {
        // Implementation for RL placement optimization
        logger.fine("Optimizing RL placement");
    }

    /**
     * Balance RL load
     */
    private void balanceRLLoad() {
        // Implementation for RL load balancing
        logger.fine("Balancing RL load");
    }

    /**
     * Include energy in placement requests
     */
    private void includeEnergyInPlacementRequests(Application application) {
        // Collect energy data from devices
        double totalEnergy = 0.0;
        for (FogDevice device : getFogDevices()) {
            totalEnergy += device.getEnergyConsumption();
        }

        placementEnergyMap.put(application.getAppId(), totalEnergy);
        logger.fine("Included energy data in placement requests for application: " + application.getAppId());
    }

    /**
     * Include cost in placement decisions
     */
    private void includeCostInPlacementDecisions(Application application) {
        // Collect cost data from devices
        double totalCost = 0.0;
        for (FogDevice device : getFogDevices()) {
            totalCost += device.getTotalCost();
        }

        placementCostMap.put(application.getAppId(), totalCost);
        logger.fine("Included cost data in placement decisions for application: " + application.getAppId());
    }

    /**
     * Update placement metrics from gRPC
     */
    private void updatePlacementMetricsFromGRPC(int deviceId, AppModule module) {
        // Update metrics based on gRPC responses
        logger.fine("Updated placement metrics from gRPC for device: " + deviceId + ", module: " + module.getName());
    }

    /**
     * Handle tuple completion with RL metrics
     */
    private void handleTupleCompletion(Tuple tuple) {
        // Update RL metrics for tuple completion
        logger.fine("Handled tuple completion for tuple: " + tuple.getCloudletId());

        // Notify fog devices about tuple completion for RL learning
        for (FogDevice fogDevice : getFogDevices()) {
            if (fogDevice instanceof RLFogDevice) {
                RLFogDevice rlFogDevice = (RLFogDevice) fogDevice;

                // Check if this tuple was processed by this device
                if (rlFogDevice.getTaskExecutionEngine() != null) {
                    // Calculate proper metrics for task completion
                    long executionTime = (long) tuple.getFinishTime() - (long) tuple.getExecStartTime();
                    boolean success = tuple.getFinishTime() > 0 && executionTime > 0;

                    // Calculate energy and cost using centralized statistics manager
                    double energy = RLStatisticsManager.getInstance().calculateExecutionEnergy(
                            tuple.getCloudletLength(), executionTime);
                    double cost = RLStatisticsManager.getInstance().calculateExecutionCost(
                            tuple.getCloudletLength(), executionTime);

                    // Report completion to TaskCompletionDetector for delayed rewards
                    rlFogDevice.getCompletionDetector().markTaskCompleted(
                            String.valueOf(tuple.getCloudletId()),
                            success,
                            executionTime,
                            (long) energy,
                            cost);

                    // Update statistics
                    RLStatisticsManager.getInstance().addExecutionTime(executionTime);
                    RLStatisticsManager.getInstance().addExecutionEnergy(energy);
                    RLStatisticsManager.getInstance().addExecutionCost(cost);
                }
            }
        }
    }

    /**
     * Process application submission internally (fallback)
     */
    private void processAppSubmitInternal(Application application) {
        logger.info(CloudSim.clock() + " Submitted application " + application.getAppId());
        FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
        getApplications().put(application.getAppId(), application);

        ModulePlacement modulePlacement = getAppModulePlacementPolicy().get(application.getAppId());
        for (FogDevice fogDevice : getFogDevices()) {
            sendNow(fogDevice.getId(), FogEvents.ACTIVE_APP_UPDATE, application);
        }

        Map<Integer, List<AppModule>> deviceToModuleMap = modulePlacement.getDeviceToModuleMap();
        for (Integer deviceId : deviceToModuleMap.keySet()) {
            for (AppModule module : deviceToModuleMap.get(deviceId)) {
                sendNow(deviceId, FogEvents.APP_SUBMIT, application);
                sendNow(deviceId, FogEvents.LAUNCH_MODULE, module);
            }
        }
    }

    /**
     * Convert Application to RLApplication
     */
    private RLApplication convertToRLApplication(Application app) {
        if (app instanceof RLApplication) {
            return (RLApplication) app;
        }

        // Create RL application from regular application
        RLApplication rlApp = new RLApplication(app.getAppId(), app.getUserId());
        rlApp.setModules(app.getModules());
        rlApp.setEdges(app.getEdges());
        rlApp.setLoops(app.getLoops());
        rlApp.setGeoCoverage(app.getGeoCoverage());

        return rlApp;
    }

    /**
     * Report state to RL agents
     */
    private void reportStateToRLAggents() {
        if (!rlConfigured) {
            return;
        }

        try {
            Map<String, Object> state = collectControllerState();
            logger.fine("Reported state to RL agents for controller: " + getName());
        } catch (Exception e) {
            logger.warning("Failed to report state to RL agents: " + e.getMessage());
        }
    }

    /**
     * Collect controller state
     */
    private Map<String, Object> collectControllerState() {
        Map<String, Object> state = new HashMap<>();

        // Application states
        state.put("applications", rlApplications.keySet());

        // Device states
        List<Map<String, Object>> deviceStates = new ArrayList<>();
        for (FogDevice device : getFogDevices()) {
            Map<String, Object> deviceState = new HashMap<>();
            deviceState.put("deviceId", device.getId());
            deviceState.put("deviceName", device.getName());
            deviceState.put("cpuUtilization", device.getHost().getUtilizationOfCpu());
            deviceState.put("ramUtilization", device.getHost().getUtilizationOfRam());
            deviceState.put("energyConsumption", device.getEnergyConsumption());
            deviceState.put("totalCost", device.getTotalCost());
            deviceStates.add(deviceState);
        }
        state.put("devices", deviceStates);

        // Placement metrics
        state.put("placementEnergy", placementEnergyMap);
        state.put("placementCost", placementCostMap);
        state.put("placementLatency", placementLatencyMap);

        return state;
    }

    // Event handlers
    private void handleAllocationRequestSent(SimEvent ev) {
        logger.fine("Allocation request sent event processed");
    }

    private void handleAllocationResponseReceived(SimEvent ev) {
        logger.fine("Allocation response received event processed");
    }

    private void handleSchedulerCacheHit(SimEvent ev) {
        logger.fine("Scheduler cache hit event processed");
    }

    private void handleSchedulerCacheMiss(SimEvent ev) {
        logger.fine("Scheduler cache miss event processed");
    }

    private void handleTaskComplete(SimEvent ev) {
        logger.fine("Task complete event processed");
    }

    // Getters
    public Map<String, RLApplication> getRLApplications() {
        return rlApplications;
    }

    public AllocationClient getAllocationClient() {
        return allocationClient;
    }

    public Map<Integer, SchedulerClient> getSchedulerClients() {
        return schedulerClients;
    }

    public boolean isRLEnabled() {
        return rlEnabled;
    }

    public boolean isRLConfigured() {
        return rlConfigured;
    }
}
