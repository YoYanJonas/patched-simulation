package org.patch.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.FogDevice;
import org.fog.entities.Tuple;
import org.fog.placement.ModulePlacement;
import org.fog.placement.ModuleMapping;
import org.fog.utils.FogEvents;
import org.fog.utils.Logger;
import org.patch.client.GrpcClient;
import org.patch.models.RLAwareApplication;
import org.patch.models.RLDecision;
import org.patch.models.RLState;
import org.patch.utils.RLConfig;
import org.patch.utils.ServiceRegistry;

/**
 * Module placement logic that integrates with RL decisions
 */
public class RLAwarePlacementLogic extends ModulePlacement {

    // Store previous placement decisions for comparison
    private Map<String, Integer> currentModulePlacements;

    // Track if RL is being used for placement decisions
    private boolean rlEnabled = false;

    // Frequency of asking RL agent for placement decisions
    private double placementUpdateInterval = 60.0; // seconds

    // Last time RL agent was consulted
    private double lastUpdateTime = 0.0;

    // Flag to track if RL configuration is complete
    private boolean rlConfigured = false;

    // gRPC client for RL communication
    private GrpcClient grpcClient;

    private ModuleMapping moduleMapping;

    /**
     * Constructor
     * 
     * @param fogDevices    List of fog devices
     * @param application   Application to place modules for
     * @param moduleMapping Initial module mapping
     */
    public RLAwarePlacementLogic(List<FogDevice> fogDevices,
            Application application,
            ModuleMapping moduleMapping) {
        // Set fields directly instead of using super constructor
        this.setFogDevices(fogDevices);
        this.setApplication(application);
        this.moduleMapping = moduleMapping; // Store moduleMapping

        this.currentModulePlacements = new HashMap<>();

        // Check if the application is RL-aware
        if (application instanceof RLAwareApplication) {
            if (((RLAwareApplication) application).isDynamicPlacementEnabled()) {
                this.rlEnabled = true;
            }
        }

        // Initialize with default placement update interval
        if (RLConfig.isPlacementRLEnabled()) {
            this.rlEnabled = true;
            this.placementUpdateInterval = RLConfig.getPlacementUpdateInterval();

            // Get RL client
            if (RLConfig.isPlacementRLServerConfigured()) {
                configureRLServer(
                        RLConfig.getPlacementRLServerHost(),
                        RLConfig.getPlacementRLServerPort());
            }
        }
    }

    // Add getter and setter for moduleMapping
    public ModuleMapping getModuleMapping() {
        return moduleMapping;
    }

    public void setModuleMapping(ModuleMapping moduleMapping) {
        this.moduleMapping = moduleMapping;
    }

    /**
     * Configure RL server connection
     * 
     * @param host RL server host
     * @param port RL server port
     */
    public void configureRLServer(String host, int port) {
        try {
            grpcClient = ServiceRegistry.getOrCreateGrpcClient(
                    "placementRL",
                    host,
                    port);
            rlConfigured = true;
        } catch (Exception e) {
            Logger.error("RLAwarePlacementLogic", "Failed to configure RL server: " + e.getMessage());
        }
    }

    /**
     * Set the placement update interval
     * 
     * @param interval Interval in seconds
     */
    public void setPlacementUpdateInterval(double interval) {
        this.placementUpdateInterval = interval;
    }

    /**
     * Enable or disable RL for placement decisions
     * 
     * @param enabled True to enable, false to disable
     */
    public void setRlEnabled(boolean enabled) {
        this.rlEnabled = enabled;
    }

    @Override
    protected void mapModules() {
        // First do the initial mapping based on the provided module mapping

        // For each application module
        for (String deviceName : moduleMapping.getModuleMapping().keySet()) {
            for (String moduleName : moduleMapping.getModuleMapping().get(deviceName)) {
                // Find the device where the module is supposed to be placed
                for (FogDevice fogDevice : getFogDevices()) {
                    if (fogDevice.getName().equals(deviceName)) {
                        // Get the module from the application
                        AppModule module = getApplication().getModuleByName(moduleName);

                        // Create the module instance on the device
                        if (createModuleInstanceOnDevice(module, fogDevice)) {
                            // Remember the placement
                            currentModulePlacements.put(moduleName, fogDevice.getId());

                            // Log the placement
                            Logger.debug(getClass().getName(),
                                    "Initial placement: Module " + moduleName +
                                            " -> Device " + fogDevice.getName());
                        }
                        break;
                    }
                }
            }
        }

        // If the application is RL-aware, get any pre-defined RL placements
        if (getApplication() instanceof RLAwareApplication) {
            RLAwareApplication rlApp = (RLAwareApplication) getApplication();
            Map<String, Integer> rlPlacements = rlApp.getRLModulePlacements();

            if (!rlPlacements.isEmpty()) {
                Logger.debug(getClass().getName(),
                        "Applying " + rlPlacements.size() + " RL-defined initial placements");

                for (Map.Entry<String, Integer> placement : rlPlacements.entrySet()) {
                    updateModulePlacement(placement.getKey(), placement.getValue());
                }
            }
        }
    }

    /**
     * Update module placement from RL decision
     * 
     * @param moduleName Module to move
     * @param deviceId   Destination device ID
     * @return True if successful, false otherwise
     */
    public boolean updateModulePlacement(String moduleName, int deviceId) {
        // Check if the module exists in the application
        AppModule module = getApplication().getModuleByName(moduleName);
        if (module == null) {
            Logger.debug(getClass().getName(), "Module " + moduleName + " not found");
            return false;
        }

        // Get target device
        FogDevice targetDevice = null;
        for (FogDevice device : getFogDevices()) {
            if (device.getId() == deviceId) {
                targetDevice = device;
                break;
            }
        }

        if (targetDevice == null) {
            Logger.debug(getClass().getName(), "Target device ID " + deviceId + " not found");
            return false;
        }

        // Check if the module is already on this device
        if (currentModulePlacements.containsKey(moduleName) &&
                currentModulePlacements.get(moduleName) == deviceId) {
            Logger.debug(getClass().getName(),
                    "Module " + moduleName + " is already on device " + deviceId);
            return true; // No need to move
        }

        // If the module is currently placed elsewhere, remove it first
        if (currentModulePlacements.containsKey(moduleName)) {
            int currentDeviceId = currentModulePlacements.get(moduleName);
            for (FogDevice device : getFogDevices()) {
                if (device.getId() == currentDeviceId) {
                    // Remove the module from the current device
                    AppModule moduleToRemove = null;
                    for (Vm vm : device.getVmList()) {
                        if (vm instanceof AppModule) {
                            AppModule appModule = (AppModule) vm;
                            if (appModule.getName().equals(moduleName)) {
                                moduleToRemove = appModule;
                                break;
                            }
                        }
                    }

                    if (moduleToRemove != null) {
                        device.getVmAllocationPolicy().deallocateHostForVm(moduleToRemove);
                        Logger.debug(getClass().getName(),
                                "Removed module " + moduleName + " from device " + device.getName());
                    }
                    break;
                }
            }
        }

        // Install the module on the target device
        if (createModuleInstanceOnDevice(module, targetDevice)) {
            // Update placement record
            currentModulePlacements.put(moduleName, deviceId);

            Logger.debug(getClass().getName(),
                    "Placed module " + moduleName + " on device " + targetDevice.getName());

            return true;
        } else {
            Logger.error(getClass().getName(),
                    "Failed to place module " + moduleName + " on device " + targetDevice.getName());
            return false;
        }
    }

    /**
     * Process tuple with RL-aware routing decisions
     * 
     * @param tuple          Tuple to route
     * @param sourceDeviceId Device sending the tuple
     */
    public void processTuple(Tuple tuple, int sourceDeviceId) {
        // If RL is enabled and application is RL-aware, use RL routing
        if (rlEnabled && getApplication() instanceof RLAwareApplication) {
            RLAwareApplication rlApp = (RLAwareApplication) getApplication();

            // Check if there's an RL preference for this tuple
            int preferredDeviceId = rlApp.getPreferredDeviceForTuple(tuple);

            if (preferredDeviceId >= 0) {
                // Send tuple to the preferred device
                for (FogDevice device : getFogDevices()) {
                    if (device.getId() == preferredDeviceId) {
                        tuple.setDestinationDeviceId(preferredDeviceId);

                        // Send the tuple to the device
                        CloudSim.send(sourceDeviceId, device.getId(), 0.1,
                                FogEvents.TUPLE_ARRIVAL, tuple);

                        Logger.debug(getClass().getName(),
                                "RL routing: Tuple " + tuple.getTupleType() +
                                        " sent to device " + device.getName());
                        return;
                    }
                }
            }
        }

        // Default routing logic - use current placements
        processTupleDefault(tuple, sourceDeviceId);
    }

    /**
     * Default tuple processing based on current module placements
     * 
     * @param tuple          Tuple to route
     * @param sourceDeviceId Device sending the tuple
     */
    protected void processTupleDefault(Tuple tuple, int sourceDeviceId) {
        // Get destination module
        String destModuleName = tuple.getDestModuleName();

        // Check if we know the placement of the destination module
        if (currentModulePlacements.containsKey(destModuleName)) {
            int destDeviceId = currentModulePlacements.get(destModuleName);

            // Send tuple to the device hosting the module
            tuple.setDestinationDeviceId(destDeviceId);
            CloudSim.send(sourceDeviceId, destDeviceId, 0.1,
                    FogEvents.TUPLE_ARRIVAL, tuple);

            Logger.debug(getClass().getName(),
                    "Default routing: Tuple " + tuple.getTupleType() +
                            " sent to device " + destDeviceId);
        } else {
            Logger.error(getClass().getName(),
                    "Cannot route tuple - destination module not placed: " + destModuleName);
        }
    }

    /**
     * Check for placement updates from RL agent
     */
    public void checkForPlacementUpdates() {
        double currentTime = CloudSim.clock();

        // Check if it's time to update placement
        if (rlEnabled && rlConfigured &&
                currentTime >= lastUpdateTime + placementUpdateInterval) {
            lastUpdateTime = currentTime;

            try {
                // Collect state information
                RLState state = collectStateForRL();

                // Get RL decision - adapt to use existing GrpcClient methods
                Map<String, Object> stateMap = state.toMap();
                Map<String, Object> decisionMap = getPlacementDecision(stateMap);

                // Parse decision
                RLDecision decision = RLDecision.fromMap(decisionMap);

                // If this is a placement decision, apply it
                if (decision.isPlacementDecision()) {
                    Map<String, Integer> newPlacements = decision.getModulePlacements();

                    for (Map.Entry<String, Integer> placement : newPlacements.entrySet()) {
                        updateModulePlacement(placement.getKey(), placement.getValue());
                    }

                    Logger.debug(getClass().getName(),
                            "Applied " + newPlacements.size() + " placement updates from RL agent");
                }

            } catch (Exception e) {
                Logger.error(getClass().getName(),
                        "Error getting placement updates from RL agent: " + e.getMessage());
            }
        }
    }

    /**
     * Helper method to get placement decisions using the GrpcClient
     * 
     * @param stateMap Current system state
     * @return Decision from the RL agent
     */
    private Map<String, Object> getPlacementDecision(Map<String, Object> stateMap) {
        if (grpcClient == null) {
            // Return empty decision if no client
            return new HashMap<>();
        }

        // Use gRPC client to get placement decision
        int deviceId = 0; // We're using a global view for placement
        try {
            // Create a proper placement decision based on current state
            Map<String, Object> decision = new HashMap<>();
            decision.put("type", "placement");
            decision.put("timestamp", System.currentTimeMillis());

            // Get scheduling decision to communicate with the server
            int result = grpcClient.getSchedulingDecision("placementRL", deviceId, stateMap);

            // Create placement map based on available devices and current state
            Map<String, Integer> placements = new HashMap<>();
            List<FogDevice> devices = getFogDevices();
            if (devices != null && !devices.isEmpty()) {
                // Simple round-robin placement based on device availability
                int deviceIndex = result % devices.size();
                if (deviceIndex >= 0 && deviceIndex < devices.size()) {
                    placements.put("selected_device", devices.get(deviceIndex).getId());
                }
            }

            decision.put("placements", placements);
            decision.put("decision_result", result);

            return decision;
        } catch (Exception e) {
            Logger.error(getClass().getName(), "Failed to get placement decision: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Collect state information for RL agent
     * 
     * @return RLState object
     */
    protected RLState collectStateForRL() {
        // Create basic state object
        RLState state = new RLState();
        state.setDeviceType("placement");
        state.setSimulationTime(CloudSim.clock());

        // Add device information
        List<RLState.DeviceInfo> deviceInfos = new ArrayList<>();
        for (FogDevice device : getFogDevices()) {
            RLState.DeviceInfo deviceInfo = new RLState.DeviceInfo(
                    device.getId(),
                    device.getName(),
                    device.getHost().getUtilizationOfCpu(),
                    device.getHost().getUtilizationOfRam(),
                    device.getHost().getUtilizationOfBw(),
                    0 // No queue size information at this level
            );

            // Add currently hosted modules
            List<String> hostedModules = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : currentModulePlacements.entrySet()) {
                if (entry.getValue() == device.getId()) {
                    hostedModules.add(entry.getKey());
                }
            }

            for (String module : hostedModules) {
                deviceInfo.addHostedModule(module);
            }

            deviceInfos.add(deviceInfo);
        }

        // Set the child devices (these represent all fog devices in this case)
        state.setChildDevices(deviceInfos);

        // Add application information if available
        if (getApplication() instanceof RLAwareApplication) {
            RLAwareApplication rlApp = (RLAwareApplication) getApplication();
            Map<String, Object> appState = rlApp.collectApplicationState();
            // We could add this to the state object if needed
        }

        return state;
    }

    /**
     * Get the current module placements
     * 
     * @return Map of module names to device IDs
     */
    public Map<String, Integer> getCurrentModulePlacements() {
        return new HashMap<>(currentModulePlacements);
    }
}