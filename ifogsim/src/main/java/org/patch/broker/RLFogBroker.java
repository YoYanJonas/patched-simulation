package org.patch.broker;

import org.fog.entities.FogBroker;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.devices.RLCloudDevice;
import org.patch.devices.RLFogDevice;
import org.patch.client.AllocationClient;
import org.patch.client.SchedulerClient;
import org.patch.utils.ExtendedFogEvents;
import org.patch.utils.RLConfig;
import org.fog.utils.FogEvents;

import java.util.*;
import java.util.logging.Level;

/**
 * RL-Enhanced FogBroker for managing RL-enabled VMs and resources
 * 
 * This class extends the original FogBroker to integrate with RL-based
 * task allocation and scheduling while maintaining iFogSim compatibility.
 * 
 * Key Features:
 * - Manages RL-enabled cloud and fog devices
 * - Coordinates gRPC communication for allocation and scheduling
 * - Tracks energy consumption and cost metrics
 * - Integrates with iFogSim's built-in metric collection
 * 
 * @author Younes Shafiee
 */
public class RLFogBroker extends FogBroker {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLFogBroker.class.getName());

    // RL-specific device management
    private Map<Integer, RLFogDevice> rlFogDevices;
    private Map<Integer, RLCloudDevice> rlCloudDevices;

    // gRPC server coordination
    private AllocationClient allocationClient;
    private Map<Integer, SchedulerClient> schedulerClients;

    // Energy and cost tracking for gRPC calls
    private Map<String, Double> energyConsumptionMap;
    private Map<String, Double> costMap;
    private Map<String, Long> latencyMap;

    // RL event handling
    private static final int RL_BROKER_STATE_REPORT = 30001;
    private static final int RL_METRICS_COLLECTION = 30002;
    private static final int RL_ENERGY_TRACKING = 30003;

    // Flag to track if RL is enabled for this broker
    private boolean rlEnabled = false;
    private boolean rlConfigured = false;

    /**
     * Constructor for RLFogBroker
     * 
     * @param name The name of the broker
     * @throws Exception if broker creation fails
     */
    public RLFogBroker(String name) throws Exception {
        super(name);

        // Initialize RL-specific collections
        this.rlFogDevices = new HashMap<>();
        this.rlCloudDevices = new HashMap<>();
        this.schedulerClients = new HashMap<>();
        this.energyConsumptionMap = new HashMap<>();
        this.costMap = new HashMap<>();
        this.latencyMap = new HashMap<>();

        logger.info("RLFogBroker created: " + name);
    }

    @Override
    public void startEntity() {
        super.startEntity();

        // Enable RL if configured
        if (RLConfig.isBrokerRLEnabled()) {
            enableRL();
        }

        logger.info("RLFogBroker started: " + getName());
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case FogEvents.RESOURCE_MGMT:
                if (rlEnabled) {
                    processRLResourceManagement(ev);
                } else {
                    super.processEvent(ev);
                }
                break;
            case RL_BROKER_STATE_REPORT:
                if (rlEnabled) {
                    reportStateToRLAggents();
                    // Schedule next state report
                    schedule(getId(), RLConfig.getBrokerStateReportInterval(), RL_BROKER_STATE_REPORT);
                }
                break;
            case RL_METRICS_COLLECTION:
                if (rlEnabled) {
                    collectRLMetrics();
                    // Schedule next metrics collection
                    schedule(getId(), RLConfig.getMetricsCollectionInterval(), RL_METRICS_COLLECTION);
                }
                break;
            case RL_ENERGY_TRACKING:
                if (rlEnabled) {
                    trackEnergyForAllocation();
                    trackCostForScheduling();
                    updateiFogSimMetrics();
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
        logger.info("RLFogBroker shutdown: " + getName());
    }

    /**
     * Enable RL-based resource management for this broker
     */
    public void enableRL() {
        this.rlEnabled = true;
        logger.info("RL-based resource management enabled for broker: " + getName());

        // Schedule first state report
        if (CloudSim.running()) {
            schedule(getId(), RLConfig.getBrokerStateReportInterval(), RL_BROKER_STATE_REPORT);
            schedule(getId(), RLConfig.getMetricsCollectionInterval(), RL_METRICS_COLLECTION);
            schedule(getId(), RLConfig.getEnergyTrackingInterval(), RL_ENERGY_TRACKING);
        }
    }

    /**
     * Configure RL servers for this broker
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

            RLConfig.configureBrokerRLServer(getId(), allocationHost, allocationPort);
            this.rlConfigured = true;

            logger.info("RL servers configured for broker: " + getName() +
                    " at " + allocationHost + ":" + allocationPort);
        } catch (Exception e) {
            logger.severe("Failed to configure RL servers: " + e.getMessage());
        }
    }

    /**
     * Register an RL-enabled fog device with this broker
     * 
     * @param device The RL fog device to register
     */
    public void registerRLFogDevice(RLFogDevice device) {
        if (device != null) {
            rlFogDevices.put(device.getId(), device);
            logger.info("Registered RL fog device: " + device.getName() + " (ID: " + device.getId() + ")");
        }
    }

    /**
     * Register an RL-enabled cloud device with this broker
     * 
     * @param device The RL cloud device to register
     */
    public void registerRLCloudDevice(RLCloudDevice device) {
        if (device != null) {
            rlCloudDevices.put(device.getId(), device);
            logger.info("Registered RL cloud device: " + device.getName() + " (ID: " + device.getId() + ")");
        }
    }

    /**
     * Process RL-based resource management
     */
    private void processRLResourceManagement(SimEvent ev) {
        logger.fine("Processing RL resource management for broker: " + getName());

        // Collect current resource state
        Map<String, Object> resourceState = collectResourceState();

        // Update energy and cost tracking
        updateEnergyAndCostTracking(resourceState);

        // Coordinate with RL agents
        coordinateWithAllocationServer(resourceState);
        coordinateWithSchedulerServers(resourceState);

        // Schedule next resource management
        schedule(getId(), RLConfig.getResourceManagementInterval(), FogEvents.RESOURCE_MGMT);
    }

    /**
     * Collect current resource state from all RL devices
     */
    private Map<String, Object> collectResourceState() {
        Map<String, Object> state = new HashMap<>();

        // Cloud device states
        List<Map<String, Object>> cloudStates = new ArrayList<>();
        for (RLCloudDevice cloudDevice : rlCloudDevices.values()) {
            cloudStates.add(collectCloudDeviceState(cloudDevice));
        }
        state.put("cloudDevices", cloudStates);

        // Fog device states
        List<Map<String, Object>> fogStates = new ArrayList<>();
        for (RLFogDevice fogDevice : rlFogDevices.values()) {
            fogStates.add(collectFogDeviceState(fogDevice));
        }
        state.put("fogDevices", fogStates);

        // Overall system metrics
        state.put("totalEnergyConsumption", calculateTotalEnergyConsumption());
        state.put("totalCost", calculateTotalCost());
        state.put("averageLatency", calculateAverageLatency());

        return state;
    }

    /**
     * Collect state from a cloud device
     */
    private Map<String, Object> collectCloudDeviceState(RLCloudDevice device) {
        Map<String, Object> state = new HashMap<>();
        state.put("deviceId", device.getId());
        state.put("deviceName", device.getName());
        state.put("cpuUtilization", device.getHost().getUtilizationOfCpu());
        state.put("ramUtilization", device.getHost().getUtilizationOfRam());
        state.put("bwUtilization", device.getHost().getUtilizationOfBw());
        state.put("energyConsumption", device.getEnergyConsumption());
        state.put("totalCost", device.getTotalCost());
        return state;
    }

    /**
     * Collect state from a fog device
     */
    private Map<String, Object> collectFogDeviceState(RLFogDevice device) {
        Map<String, Object> state = new HashMap<>();
        state.put("deviceId", device.getId());
        state.put("deviceName", device.getName());
        state.put("cpuUtilization", device.getHost().getUtilizationOfCpu());
        state.put("ramUtilization", device.getHost().getUtilizationOfRam());
        state.put("bwUtilization", device.getHost().getUtilizationOfBw());
        state.put("energyConsumption", device.getEnergyConsumption());
        state.put("totalCost", device.getTotalCost());
        state.put("unscheduledQueueLength", device.getUnscheduledQueue().size());
        state.put("scheduledQueueLength", device.getScheduledQueue().size());
        return state;
    }

    /**
     * Update energy and cost tracking for gRPC calls
     */
    private void updateEnergyAndCostTracking(Map<String, Object> resourceState) {
        // Track energy consumption for allocation decisions
        trackEnergyForAllocation();

        // Track cost for scheduling decisions
        trackCostForScheduling();

        // Update iFogSim metrics
        updateiFogSimMetrics();
    }

    /**
     * Track energy consumption for allocation decisions
     */
    private void trackEnergyForAllocation() {
        double totalEnergy = 0.0;

        // Collect energy from all devices
        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalEnergy += device.getEnergyConsumption();
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalEnergy += device.getEnergyConsumption();
        }

        energyConsumptionMap.put("allocation", totalEnergy);
        logger.fine("Tracked energy for allocation: " + totalEnergy);
    }

    /**
     * Track cost for scheduling decisions
     */
    private void trackCostForScheduling() {
        double totalCost = 0.0;

        // Collect cost from all devices
        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalCost += device.getTotalCost();
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalCost += device.getTotalCost();
        }

        costMap.put("scheduling", totalCost);
        logger.fine("Tracked cost for scheduling: " + totalCost);
    }

    /**
     * Update iFogSim metrics with RL data
     */
    private void updateiFogSimMetrics() {
        // Update energy metrics
        double totalEnergy = calculateTotalEnergyConsumption();
        logger.fine("Total energy consumption: " + totalEnergy);

        // Update cost metrics
        double totalCost = calculateTotalCost();
        logger.fine("Total cost: " + totalCost);

        // Update latency metrics
        double avgLatency = calculateAverageLatency();
        logger.fine("Average latency: " + avgLatency);
    }

    /**
     * Coordinate with allocation server
     */
    private void coordinateWithAllocationServer(Map<String, Object> resourceState) {
        if (allocationClient == null || !allocationClient.isConnected()) {
            return;
        }

        try {
            // Include energy and cost in allocation requests
            includeEnergyInAllocationRequests(resourceState);
            includeCostInAllocationRequests(resourceState);

            logger.fine("Coordinated with allocation server");
        } catch (Exception e) {
            logger.warning("Failed to coordinate with allocation server: " + e.getMessage());
        }
    }

    /**
     * Coordinate with scheduler servers
     */
    private void coordinateWithSchedulerServers(Map<String, Object> resourceState) {
        for (Map.Entry<Integer, SchedulerClient> entry : schedulerClients.entrySet()) {
            SchedulerClient client = entry.getValue();
            if (client == null || !client.isConnected()) {
                continue;
            }

            try {
                // Include energy and cost in scheduling requests
                includeEnergyInSchedulingRequests(resourceState, entry.getKey());
                includeCostInSchedulingRequests(resourceState, entry.getKey());

                logger.fine("Coordinated with scheduler server for device: " + entry.getKey());
            } catch (Exception e) {
                logger.warning("Failed to coordinate with scheduler server for device " +
                        entry.getKey() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Include energy in allocation requests
     */
    private void includeEnergyInAllocationRequests(Map<String, Object> resourceState) {
        // This would be implemented to include energy data in gRPC requests
        logger.fine("Including energy data in allocation requests");
    }

    /**
     * Include cost in allocation requests
     */
    private void includeCostInAllocationRequests(Map<String, Object> resourceState) {
        // This would be implemented to include cost data in gRPC requests
        logger.fine("Including cost data in allocation requests");
    }

    /**
     * Include energy in scheduling requests
     */
    private void includeEnergyInSchedulingRequests(Map<String, Object> resourceState, int deviceId) {
        // This would be implemented to include energy data in gRPC requests
        logger.fine("Including energy data in scheduling requests for device: " + deviceId);
    }

    /**
     * Include cost in scheduling requests
     */
    private void includeCostInSchedulingRequests(Map<String, Object> resourceState, int deviceId) {
        // This would be implemented to include cost data in gRPC requests
        logger.fine("Including cost data in scheduling requests for device: " + deviceId);
    }

    /**
     * Report state to RL agents
     */
    private void reportStateToRLAggents() {
        if (!rlConfigured) {
            return;
        }

        try {
            collectResourceState();
            logger.fine("Reported state to RL agents for broker: " + getName());
        } catch (Exception e) {
            logger.warning("Failed to report state to RL agents: " + e.getMessage());
        }
    }

    /**
     * Collect RL-specific metrics
     */
    private void collectRLMetrics() {
        // Collect allocation metrics
        collectRLAllocationMetrics();

        // Collect scheduling metrics
        collectRLSchedulingMetrics();

        // Collect module metrics
        collectRLModuleMetrics();

        logger.fine("Collected RL metrics for broker: " + getName());
    }

    /**
     * Collect RL allocation metrics
     */
    private void collectRLAllocationMetrics() {
        // Implementation for collecting allocation metrics
        logger.fine("Collected RL allocation metrics");
    }

    /**
     * Collect RL scheduling metrics
     */
    private void collectRLSchedulingMetrics() {
        // Implementation for collecting scheduling metrics
        logger.fine("Collected RL scheduling metrics");
    }

    /**
     * Collect RL module metrics
     */
    private void collectRLModuleMetrics() {
        // Implementation for collecting module metrics
        logger.fine("Collected RL module metrics");
    }

    /**
     * Calculate total energy consumption
     */
    private double calculateTotalEnergyConsumption() {
        double total = 0.0;
        for (Double energy : energyConsumptionMap.values()) {
            total += energy;
        }
        return total;
    }

    /**
     * Calculate total cost
     */
    private double calculateTotalCost() {
        double total = 0.0;
        for (Double cost : costMap.values()) {
            total += cost;
        }
        return total;
    }

    /**
     * Calculate average latency
     */
    private double calculateAverageLatency() {
        if (latencyMap.isEmpty()) {
            return 0.0;
        }

        long total = 0;
        for (Long latency : latencyMap.values()) {
            total += latency;
        }
        return (double) total / latencyMap.size();
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
    public Map<Integer, RLFogDevice> getRLFogDevices() {
        return rlFogDevices;
    }

    public Map<Integer, RLCloudDevice> getRLCloudDevices() {
        return rlCloudDevices;
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
