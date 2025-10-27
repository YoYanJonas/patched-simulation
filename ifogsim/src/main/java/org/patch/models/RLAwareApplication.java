package org.patch.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fog.application.Application;
import org.fog.application.AppModule;
// import org.fog.entities.FogDevice;
import org.fog.entities.Tuple;
import org.fog.utils.Logger;
import org.patch.devices.RLFogDevice;
// import org.patch.utils.RLConfig;

/**
 * Extends the Application class to work with RL-based scheduling
 * and dynamic module placement
 */
public class RLAwareApplication extends Application {

    // Track RL-enabled fog devices this application runs on
    private List<RLFogDevice> rlFogDevices;

    // Track module placement changes from RL
    private Map<String, Integer> rlModulePlacements;

    // Flag to enable/disable dynamic module placement changes
    private boolean dynamicPlacementEnabled = false;

    /**
     * Constructor
     * 
     * @param appId Application ID
     */
    public RLAwareApplication(String appId, int userId) {
        super(appId, userId);
        rlFogDevices = new ArrayList<>();
        rlModulePlacements = new HashMap<>();
    }

    /**
     * Enable dynamic module placement changes based on RL decisions
     * 
     * @param enabled True to enable, false to disable
     */
    public void setDynamicPlacementEnabled(boolean enabled) {
        this.dynamicPlacementEnabled = enabled;
    }

    /**
     * Check if dynamic module placement is enabled
     * 
     * @return True if enabled, false otherwise
     */
    public boolean isDynamicPlacementEnabled() {
        return dynamicPlacementEnabled;
    }

    /**
     * Register an RL-enabled fog device for this application
     * 
     * @param device The RLFogDevice to register
     */
    public void registerRLFogDevice(RLFogDevice device) {
        if (!rlFogDevices.contains(device)) {
            rlFogDevices.add(device);
        }
    }

    /**
     * Update module placement based on RL decision
     * 
     * @param moduleName Name of the module
     * @param deviceId   ID of the device to place the module on
     * @return True if placement was successful, false otherwise
     */
    public boolean updateModulePlacement(String moduleName, int deviceId) {
        if (!dynamicPlacementEnabled) {
            Logger.debug(getAppId(), "Dynamic module placement is disabled");
            return false;
        }

        // Verify module exists in this application
        if (getModuleByName(moduleName) == null) {
            Logger.debug(getAppId(), "Module " + moduleName + " not found in application");
            return false;
        }

        // Find target device
        RLFogDevice targetDevice = findRLFogDeviceById(deviceId);
        if (targetDevice == null) {
            Logger.debug(getAppId(), "Target device " + deviceId + " not found or not RL-enabled");
            return false;
        }

        // Store the RL-based placement decision
        rlModulePlacements.put(moduleName, deviceId);
        Logger.debug(getAppId(), "RL-based placement decision: Module " + moduleName + " -> Device " + deviceId);

        return true;
    }

    /**
     * Get the current RL-based module placements
     * 
     * @return Map of module names to device IDs
     */
    public Map<String, Integer> getRLModulePlacements() {
        return new HashMap<>(rlModulePlacements);
    }

    /**
     * Handle tuple based on RL placement decisions if available
     * This method would be called by the placement logic
     * 
     * @param tuple Tuple to handle
     * @return Preferred device ID for this tuple based on RL decisions, or -1 if no
     *         preference
     */
    public int getPreferredDeviceForTuple(Tuple tuple) {
        // If the tuple is destined for a module that has an RL placement decision,
        // return the decided device ID
        if (tuple.getDestModuleName() != null && rlModulePlacements.containsKey(tuple.getDestModuleName())) {
            return rlModulePlacements.get(tuple.getDestModuleName());
        }

        return -1; // No RL-based preference
    }

    /**
     * Find RLFogDevice by ID
     * 
     * @param deviceId Device ID to search for
     * @return RLFogDevice if found, null otherwise
     */
    private RLFogDevice findRLFogDeviceById(int deviceId) {
        for (RLFogDevice device : rlFogDevices) {
            if (device.getId() == deviceId) {
                return device;
            }
        }
        return null;
    }

    /**
     * Get all RL fog devices this application runs on
     * 
     * @return List of RLFogDevice instances
     */
    public List<RLFogDevice> getRlFogDevices() {
        return new ArrayList<>(rlFogDevices);
    }

    /**
     * Collect state information about this application
     * 
     * @return Map containing application state
     */
    public Map<String, Object> collectApplicationState() {
        Map<String, Object> state = new HashMap<>();

        state.put("appId", getAppId());
        state.put("moduleCount", getModules().size());
        state.put("dynamicPlacementEnabled", dynamicPlacementEnabled);

        // Add module information
        List<Map<String, Object>> moduleStates = new ArrayList<>();
        for (AppModule module : getModules()) {
            String moduleName = module.getName();
            Map<String, Object> moduleState = new HashMap<>();
            moduleState.put("name", moduleName);

            // Add placement info if available
            if (rlModulePlacements.containsKey(moduleName)) {
                moduleState.put("deviceId", rlModulePlacements.get(moduleName));
            } else {
                moduleState.put("deviceId", -1); // Not explicitly placed by RL
            }

            moduleStates.add(moduleState);
        }

        state.put("modules", moduleStates);

        return state;
    }
}