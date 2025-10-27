package org.patch.models;

import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.entities.FogDevice;
import org.fog.entities.Tuple;

import java.util.*;
// import java.util.logging.Logger;

/**
 * Collects state information for RL agents
 */
public class StateCollector {
    // private static final Logger logger =
    // Logger.getLogger(StateCollector.class.getName());

    // Device being monitored
    private FogDevice device;

    // Tracking device metrics over time
    private List<Double> cpuUtilizationHistory = new ArrayList<>();
    private List<Double> ramUtilizationHistory = new ArrayList<>();
    private List<Double> bwUtilizationHistory = new ArrayList<>();
    private List<Double> energyConsumptionHistory = new ArrayList<>();

    // Track task processing metrics
    private int totalTasksProcessed = 0;
    private int totalTasksReceived = 0;
    private double totalProcessingTime = 0;
    private double totalExecutionTime = 0;

    // Track network metrics
    private Map<Integer, NetworkMetrics> networkMetrics = new HashMap<>();

    // Window size for moving averages
    private int historyWindowSize = 10;

    /**
     * Constructor
     * 
     * @param device FogDevice to collect state from
     */
    public StateCollector(FogDevice device) {
        this.device = device;
    }

    /**
     * Constructor with window size
     * 
     * @param device            FogDevice to collect state from
     * @param historyWindowSize Size of history window for moving averages
     */
    public StateCollector(FogDevice device, int historyWindowSize) {
        this.device = device;
        this.historyWindowSize = historyWindowSize;
    }

    /**
     * Collect current device state
     * 
     * @return Map containing state information
     */
    public Map<String, Object> collectState() {
        Map<String, Object> state = new HashMap<>();

        // Device identification
        state.put("deviceId", device.getId());
        state.put("deviceName", device.getName());
        state.put("simulationTime", CloudSim.clock());

        // Current resource utilization
        double cpuUtilization = device.getHost().getUtilizationOfCpu();
        double ramUtilization = device.getHost().getUtilizationOfRam();
        double bwUtilization = device.getHost().getUtilizationOfBw();

        state.put("cpuUtilization", cpuUtilization);
        state.put("ramUtilization", ramUtilization);
        state.put("bwUtilization", bwUtilization);

        // Update history
        updateUtilizationHistory(cpuUtilization, ramUtilization, bwUtilization);

        // Add moving averages
        state.put("cpuUtilizationAvg", calculateAverage(cpuUtilizationHistory));
        state.put("ramUtilizationAvg", calculateAverage(ramUtilizationHistory));
        state.put("bwUtilizationAvg", calculateAverage(bwUtilizationHistory));

        // Task processing metrics
        state.put("totalTasksProcessed", totalTasksProcessed);
        state.put("totalTasksReceived", totalTasksReceived);

        double avgProcessingTime = totalTasksProcessed > 0 ? totalProcessingTime / totalTasksProcessed : 0;
        double avgExecutionTime = totalTasksProcessed > 0 ? totalExecutionTime / totalTasksProcessed : 0;

        state.put("avgProcessingTime", avgProcessingTime);
        state.put("avgExecutionTime", avgExecutionTime);

        // Network metrics
        Map<Integer, Map<String, Object>> networkStatsMap = new HashMap<>();
        for (Map.Entry<Integer, NetworkMetrics> entry : networkMetrics.entrySet()) {
            networkStatsMap.put(entry.getKey(), entry.getValue().toMap());
        }
        state.put("networkMetrics", networkStatsMap);

        // Energy metrics
        double currentEnergyConsumption = device.getEnergyConsumption();
        energyConsumptionHistory.add(currentEnergyConsumption);
        if (energyConsumptionHistory.size() > historyWindowSize) {
            energyConsumptionHistory.remove(0);
        }

        state.put("energyConsumption", currentEnergyConsumption);
        state.put("energyConsumptionRate", calculateEnergyRate());

        return state;
    }

    /**
     * Update task processing metrics when a task is received
     * 
     * @param tuple The received tuple
     * @param time  Current simulation time
     */
    public void taskReceived(Tuple tuple, double time) {
        totalTasksReceived++;
    }

    /**
     * Update task processing metrics when a task is completed
     * 
     * @param tuple          The processed tuple
     * @param processingTime Time taken to process the tuple
     * @param executionTime  Actual execution time of the tuple
     */
    public void taskProcessed(Tuple tuple, double processingTime, double executionTime) {
        totalTasksProcessed++;
        totalProcessingTime += processingTime;
        totalExecutionTime += executionTime;
    }

    /**
     * Update network metrics for communication with another device
     * 
     * @param deviceId ID of the other device
     * @param latency  Network latency in ms
     * @param dataSize Size of data transferred in bytes
     * @param isUplink True if uplink, false if downlink
     */
    public void updateNetworkMetrics(int deviceId, double latency, long dataSize, boolean isUplink) {
        NetworkMetrics metrics = networkMetrics.getOrDefault(deviceId, new NetworkMetrics(deviceId));

        if (isUplink) {
            metrics.updateUplink(latency, dataSize);
        } else {
            metrics.updateDownlink(latency, dataSize);
        }

        networkMetrics.put(deviceId, metrics);
    }

    /**
     * Update utilization history
     */
    private void updateUtilizationHistory(double cpu, double ram, double bw) {
        cpuUtilizationHistory.add(cpu);
        ramUtilizationHistory.add(ram);
        bwUtilizationHistory.add(bw);

        // Keep history within window size
        if (cpuUtilizationHistory.size() > historyWindowSize) {
            cpuUtilizationHistory.remove(0);
            ramUtilizationHistory.remove(0);
            bwUtilizationHistory.remove(0);
        }
    }

    /**
     * Calculate average of a list of values
     */
    private double calculateAverage(List<Double> values) {
        if (values.isEmpty())
            return 0;

        double sum = 0;
        for (Double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    /**
     * Calculate energy consumption rate
     */
    private double calculateEnergyRate() {
        if (energyConsumptionHistory.size() < 2)
            return 0;

        double latest = energyConsumptionHistory.get(energyConsumptionHistory.size() - 1);
        double previous = energyConsumptionHistory.get(energyConsumptionHistory.size() - 2);

        return latest - previous;
    }

    /**
     * Reset all metrics
     */
    public void reset() {
        cpuUtilizationHistory.clear();
        ramUtilizationHistory.clear();
        bwUtilizationHistory.clear();
        energyConsumptionHistory.clear();

        totalTasksProcessed = 0;
        totalTasksReceived = 0;
        totalProcessingTime = 0;
        totalExecutionTime = 0;

        networkMetrics.clear();
    }

    /**
     * Inner class to track network metrics between devices
     */
    private static class NetworkMetrics {
        private int deviceId;
        private double avgUplinkLatency = 0;
        private double avgDownlinkLatency = 0;
        private long totalUplinkData = 0;
        private long totalDownlinkData = 0;
        private int uplinkCount = 0;
        private int downlinkCount = 0;

        public NetworkMetrics(int deviceId) {
            this.deviceId = deviceId;
        }

        public void updateUplink(double latency, long dataSize) {
            // Update running average
            avgUplinkLatency = (avgUplinkLatency * uplinkCount + latency) / (uplinkCount + 1);
            uplinkCount++;
            totalUplinkData += dataSize;
        }

        public void updateDownlink(double latency, long dataSize) {
            // Update running average
            avgDownlinkLatency = (avgDownlinkLatency * downlinkCount + latency) / (downlinkCount + 1);
            downlinkCount++;
            totalDownlinkData += dataSize;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("deviceId", deviceId);
            map.put("avgUplinkLatency", avgUplinkLatency);
            map.put("avgDownlinkLatency", avgDownlinkLatency);
            map.put("totalUplinkData", totalUplinkData);
            map.put("totalDownlinkData", totalDownlinkData);
            map.put("uplinkCount", uplinkCount);
            map.put("downlinkCount", downlinkCount);
            return map;
        }
    }
}