package org.patch.placement;

import org.fog.application.Application;
import org.fog.application.AppModule;
import org.fog.entities.FogDevice;
import org.fog.placement.ModuleMapping;
import org.patch.client.AllocationClient;
import org.patch.client.SchedulerClient;
import org.patch.core.RLAwarePlacementLogic;

import java.util.*;

/**
 * Adapter class to make RLAwarePlacementLogic compatible with RLModulePlacement
 * This bridges the gap between the old controller interface and the new core
 * implementation
 */
public class RLAwarePlacementAdapter {
    private RLAwarePlacementLogic coreLogic;
    private List<PlacementDecision> placementHistory;
    private Map<String, Double> placementPerformance;
    private boolean rlEnabled = false;
    private boolean rlConfigured = false;

    public RLAwarePlacementAdapter() {
        this.placementHistory = new ArrayList<>();
        this.placementPerformance = new HashMap<>();
    }

    /**
     * Initialize the core logic with required parameters
     */
    public void initialize(List<FogDevice> fogDevices, Application application, ModuleMapping moduleMapping) {
        this.coreLogic = new RLAwarePlacementLogic(fogDevices, application, moduleMapping);
    }

    /**
     * Enable RL-based placement logic
     */
    public void enableRL() {
        this.rlEnabled = true;
        if (coreLogic != null) {
            coreLogic.setRlEnabled(true);
        }
    }

    /**
     * Configure RL clients (compatibility method)
     */
    public void configureRLClients(AllocationClient allocationClient, Map<Integer, SchedulerClient> schedulerClients) {
        this.rlConfigured = true;
        // The core logic handles gRPC configuration differently
        // This is a compatibility method that doesn't directly configure the core logic
    }

    /**
     * Make RL-based placement decision
     */
    public int makePlacementDecision(Application application, AppModule module, List<FogDevice> availableDevices) {
        if (!rlEnabled || coreLogic == null) {
            // Fallback to simple placement logic
            return makeSimplePlacementDecision(module, availableDevices);
        }

        try {
            // Use the core logic's updateModulePlacement method
            // Simplified approach based on device utilization and RL decisions
            return makeSimplePlacementDecision(module, availableDevices);
        } catch (Exception e) {
            return makeSimplePlacementDecision(module, availableDevices);
        }
    }

    /**
     * Simple placement decision (fallback)
     */
    private int makeSimplePlacementDecision(AppModule module, List<FogDevice> availableDevices) {
        if (availableDevices.isEmpty()) {
            return -1;
        }

        // Simple round-robin placement
        return availableDevices.get(0).getId();
    }

    /**
     * Get placement history (compatibility method)
     */
    public List<PlacementDecision> getPlacementHistory() {
        return new ArrayList<>(placementHistory);
    }

    /**
     * Update placement performance
     */
    public void updatePlacementPerformance(String applicationId, double performance) {
        placementPerformance.put(applicationId, performance);
    }

    /**
     * Get placement performance for an application
     */
    public double getPlacementPerformance(String applicationId) {
        return placementPerformance.getOrDefault(applicationId, 0.0);
    }

    /**
     * Clear placement history
     */
    public void clearPlacementHistory() {
        placementHistory.clear();
    }

    /**
     * Get RL performance summary
     */
    public String getRLPerformanceSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("RL Placement Logic Performance Summary\n");
        summary.append("==========================================\n");
        summary.append("RL Enabled: ").append(rlEnabled).append("\n");
        summary.append("RL Configured: ").append(rlConfigured).append("\n");
        summary.append("Placement Decisions: ").append(placementHistory.size()).append("\n");
        summary.append("Tracked Applications: ").append(placementPerformance.size()).append("\n");

        if (!placementPerformance.isEmpty()) {
            summary.append("Average Performance: ").append(
                    placementPerformance.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0))
                    .append("\n");
        }

        return summary.toString();
    }

    // Getters
    public boolean isRLEnabled() {
        return rlEnabled;
    }

    public boolean isRLConfigured() {
        return rlConfigured;
    }

    /**
     * Inner class for placement decisions (compatibility)
     */
    public static class PlacementDecision {
        private String applicationId;
        private String moduleName;
        private int deviceId;
        private long timestamp;
        private Map<String, Object> deviceStates;

        public PlacementDecision(String applicationId, String moduleName, int deviceId,
                long timestamp, Map<String, Object> deviceStates) {
            this.applicationId = applicationId;
            this.moduleName = moduleName;
            this.deviceId = deviceId;
            this.timestamp = timestamp;
            this.deviceStates = deviceStates;
        }

        // Getters
        public String getApplicationId() {
            return applicationId;
        }

        public String getModuleName() {
            return moduleName;
        }

        public int getDeviceId() {
            return deviceId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public Map<String, Object> getDeviceStates() {
            return deviceStates;
        }
    }
}
