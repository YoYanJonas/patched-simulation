package org.patch.metrics;

import org.patch.broker.RLFogBroker;
import org.patch.controller.RLController;
import org.patch.application.RLApplication;
import org.patch.placement.RLModulePlacement;
import org.patch.processing.RLTupleProcessing;
import org.patch.devices.RLCloudDevice;
import org.patch.devices.RLFogDevice;
import org.patch.client.AllocationClient;
import org.patch.client.SchedulerClient;
import org.patch.utils.RLConfig;
import org.patch.config.EnhancedConfigurationLoader;

import java.util.*;
import java.util.logging.Level;

/**
 * RL Metrics Collection for comprehensive RL-specific metrics
 * 
 * This class collects and aggregates metrics from all RL components
 * in the iFogSim simulation, providing comprehensive performance
 * monitoring and evaluation capabilities.
 * 
 * Key Features:
 * - RL-specific metrics collection (allocation, scheduling, module, tuple
 * processing)
 * - gRPC performance metrics collection and reporting
 * - RL learning metrics collection and reporting
 * - Simulation validation metrics collection
 * - iFogSim integration metrics (energy, cost, latency, throughput)
 * 
 * @author Younes Shafiee
 */
public class RLMetricsCollection {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLMetricsCollection.class.getName());

    // RL components for metrics collection
    private Map<Integer, RLFogBroker> rlBrokers;
    private Map<Integer, RLController> rlControllers;
    private Map<String, RLApplication> rlApplications;
    private Map<Integer, RLModulePlacement> rlModulePlacements;
    private Map<Integer, RLTupleProcessing> rlTupleProcessings;
    private Map<Integer, RLCloudDevice> rlCloudDevices;
    private Map<Integer, RLFogDevice> rlFogDevices;

    // gRPC clients for performance monitoring
    private Map<Integer, AllocationClient> allocationClients;
    private Map<Integer, SchedulerClient> schedulerClients;

    // Metrics storage
    private Map<String, Object> rlAllocationMetrics;
    private Map<String, Object> rlSchedulingMetrics;
    private Map<String, Object> rlModuleMetrics;
    private Map<String, Object> rlTupleProcessingMetrics;
    private Map<String, Object> grpcPerformanceMetrics;
    private Map<String, Object> rlLearningMetrics;
    private Map<String, Object> simulationValidationMetrics;
    private Map<String, Object> iFogSimIntegrationMetrics;

    // Flag to track if collection is enabled
    private boolean collectionEnabled = false;
    private boolean collectionConfigured = false;

    // Collection timing
    private long lastCollectionTime = 0;
    private long collectionInterval = 1000; // 1 second default

    /**
     * Constructor for RLMetricsCollection
     */
    public RLMetricsCollection() {
        // Initialize RL components maps
        this.rlBrokers = new HashMap<>();
        this.rlControllers = new HashMap<>();
        this.rlApplications = new HashMap<>();
        this.rlModulePlacements = new HashMap<>();
        this.rlTupleProcessings = new HashMap<>();
        this.rlCloudDevices = new HashMap<>();
        this.rlFogDevices = new HashMap<>();

        // Initialize gRPC clients maps
        this.allocationClients = new HashMap<>();
        this.schedulerClients = new HashMap<>();

        // Initialize metrics storage
        this.rlAllocationMetrics = new HashMap<>();
        this.rlSchedulingMetrics = new HashMap<>();
        this.rlModuleMetrics = new HashMap<>();
        this.rlTupleProcessingMetrics = new HashMap<>();
        this.grpcPerformanceMetrics = new HashMap<>();
        this.rlLearningMetrics = new HashMap<>();
        this.simulationValidationMetrics = new HashMap<>();
        this.iFogSimIntegrationMetrics = new HashMap<>();

        logger.info("RLMetricsCollection created");
    }

    /**
     * Enable metrics collection
     */
    public void enableCollection() {
        this.collectionEnabled = true;
        this.lastCollectionTime = System.currentTimeMillis();
        logger.info("RL metrics collection enabled");
    }

    /**
     * Configure metrics collection
     * 
     * @param collectionInterval Collection interval in milliseconds
     */
    public void configureCollection(long collectionInterval) {
        this.collectionInterval = collectionInterval;
        this.collectionConfigured = true;
        logger.info("RL metrics collection configured with interval: " + collectionInterval + "ms");
    }

    /**
     * Register RL broker for metrics collection
     * 
     * @param brokerId Broker ID
     * @param broker   RL broker
     */
    public void registerRLBroker(int brokerId, RLFogBroker broker) {
        this.rlBrokers.put(brokerId, broker);
        logger.fine("Registered RL broker: " + brokerId);
    }

    /**
     * Register RL controller for metrics collection
     * 
     * @param controllerId Controller ID
     * @param controller   RL controller
     */
    public void registerRLController(int controllerId, RLController controller) {
        this.rlControllers.put(controllerId, controller);
        logger.fine("Registered RL controller: " + controllerId);
    }

    /**
     * Register RL application for metrics collection
     * 
     * @param appId       Application ID
     * @param application RL application
     */
    public void registerRLApplication(String appId, RLApplication application) {
        this.rlApplications.put(appId, application);
        logger.fine("Registered RL application: " + appId);
    }

    /**
     * Register RL module placement for metrics collection
     * 
     * @param placementId Placement ID
     * @param placement   RL module placement
     */
    public void registerRLModulePlacement(int placementId, RLModulePlacement placement) {
        this.rlModulePlacements.put(placementId, placement);
        logger.fine("Registered RL module placement: " + placementId);
    }

    /**
     * Register RL tuple processing for metrics collection
     * 
     * @param processingId Processing ID
     * @param processing   RL tuple processing
     */
    public void registerRLTupleProcessing(int processingId, RLTupleProcessing processing) {
        this.rlTupleProcessings.put(processingId, processing);
        logger.fine("Registered RL tuple processing: " + processingId);
    }

    /**
     * Register RL cloud device for metrics collection
     * 
     * @param deviceId Device ID
     * @param device   RL cloud device
     */
    public void registerRLCloudDevice(int deviceId, RLCloudDevice device) {
        this.rlCloudDevices.put(deviceId, device);
        logger.fine("Registered RL cloud device: " + deviceId);
    }

    /**
     * Register RL fog device for metrics collection
     * 
     * @param deviceId Device ID
     * @param device   RL fog device
     */
    public void registerRLFogDevice(int deviceId, RLFogDevice device) {
        this.rlFogDevices.put(deviceId, device);
        logger.fine("Registered RL fog device: " + deviceId);
    }

    /**
     * Register gRPC clients for performance monitoring
     * 
     * @param allocationClients Allocation clients
     * @param schedulerClients  Scheduler clients
     */
    public void registerGRPCClients(Map<Integer, AllocationClient> allocationClients,
            Map<Integer, SchedulerClient> schedulerClients) {
        this.allocationClients = allocationClients;
        this.schedulerClients = schedulerClients;
        logger.info("Registered gRPC clients for performance monitoring");
    }

    /**
     * Collect all RL metrics
     * 
     * @return Complete metrics map
     */
    public Map<String, Object> collectAllRLMetrics() {
        if (!collectionEnabled) {
            logger.warning("Metrics collection not enabled");
            return new HashMap<>();
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCollectionTime < collectionInterval) {
            // Return cached metrics if collection interval not reached
            return getCachedMetrics();
        }

        logger.fine("Collecting RL metrics...");

        // Collect metrics from all RL components
        collectRLAllocationMetrics();
        collectRLSchedulingMetrics();
        collectRLModuleMetrics();
        collectRLTupleProcessingMetrics();
        collectGRPCMetrics();
        collectRLLearningMetrics();
        collectSimulationValidationMetrics();
        collectiFogSimIntegrationMetrics();

        this.lastCollectionTime = currentTime;

        return getAllMetrics();
    }

    /**
     * Collect RL allocation metrics
     */
    private void collectRLAllocationMetrics() {
        Map<String, Object> allocationMetrics = new HashMap<>();

        // Collect from RL cloud devices
        for (Map.Entry<Integer, RLCloudDevice> entry : rlCloudDevices.entrySet()) {
            int deviceId = entry.getKey();
            RLCloudDevice device = entry.getValue();

            // Use real methods from RLCloudDevice
            allocationMetrics.put("device_" + deviceId + "_allocation_decisions", device.getTotalAllocationDecisions());
            allocationMetrics.put("device_" + deviceId + "_allocation_success_rate", device.getAllocationSuccessRate());
            allocationMetrics.put("device_" + deviceId + "_allocation_energy", device.getTotalAllocationEnergy());
            allocationMetrics.put("device_" + deviceId + "_allocation_cost", device.getTotalAllocationCost());
        }

        // Collect from allocation clients
        for (Map.Entry<Integer, AllocationClient> entry : allocationClients.entrySet()) {
            int clientId = entry.getKey();
            AllocationClient client = entry.getValue();

            // Use real methods from AllocationClient
            allocationMetrics.put("allocation_client_" + clientId + "_requests", client.getTotalRequests());
            allocationMetrics.put("allocation_client_" + clientId + "_success_rate", client.getSuccessRate());
            allocationMetrics.put("allocation_client_" + clientId + "_avg_latency", client.getAverageLatency());
        }

        this.rlAllocationMetrics = allocationMetrics;
        logger.fine("Collected RL allocation metrics: " + allocationMetrics.size() + " entries");
    }

    /**
     * Collect RL scheduling metrics
     */
    private void collectRLSchedulingMetrics() {
        Map<String, Object> schedulingMetrics = new HashMap<>();

        // Collect from RL fog devices
        for (Map.Entry<Integer, RLFogDevice> entry : rlFogDevices.entrySet()) {
            int deviceId = entry.getKey();
            RLFogDevice device = entry.getValue();

            // Use real methods from RLFogDevice
            schedulingMetrics.put("device_" + deviceId + "_scheduling_decisions", device.getTotalSchedulingDecisions());
            schedulingMetrics.put("device_" + deviceId + "_scheduling_success_rate", device.getSchedulingSuccessRate());
            schedulingMetrics.put("device_" + deviceId + "_scheduling_energy", device.getTotalSchedulingEnergy());
            schedulingMetrics.put("device_" + deviceId + "_scheduling_cost", device.getTotalSchedulingCost());
            schedulingMetrics.put("device_" + deviceId + "_queue_length", device.getUnscheduledQueueSize());
        }

        // Collect from scheduler clients
        for (Map.Entry<Integer, SchedulerClient> entry : schedulerClients.entrySet()) {
            int clientId = entry.getKey();
            SchedulerClient client = entry.getValue();

            // Use real methods from SchedulerClient
            schedulingMetrics.put("scheduler_client_" + clientId + "_requests", client.getTotalRequests());
            schedulingMetrics.put("scheduler_client_" + clientId + "_success_rate", client.getSuccessRate());
            schedulingMetrics.put("scheduler_client_" + clientId + "_avg_latency", client.getAverageLatency());
        }

        this.rlSchedulingMetrics = schedulingMetrics;
        logger.fine("Collected RL scheduling metrics: " + schedulingMetrics.size() + " entries");
    }

    /**
     * Collect RL module metrics
     */
    private void collectRLModuleMetrics() {
        Map<String, Object> moduleMetrics = new HashMap<>();

        // Collect from RL module placements
        for (Map.Entry<Integer, RLModulePlacement> entry : rlModulePlacements.entrySet()) {
            int placementId = entry.getKey();
            RLModulePlacement placement = entry.getValue();

            // Use real methods from RLModulePlacement
            Map<String, Object> placementMetrics = placement.collectRLPlacementMetrics();
            moduleMetrics.put("placement_" + placementId, placementMetrics);
        }

        // Collect from RL applications
        for (Map.Entry<String, RLApplication> entry : rlApplications.entrySet()) {
            String appId = entry.getKey();
            RLApplication application = entry.getValue();

            // Use real methods from RLApplication
            Map<String, Object> appMetrics = application.collectRLApplicationMetrics();
            moduleMetrics.put("application_" + appId, appMetrics);
        }

        this.rlModuleMetrics = moduleMetrics;
        logger.fine("Collected RL module metrics: " + moduleMetrics.size() + " entries");
    }

    /**
     * Collect RL tuple processing metrics
     */
    private void collectRLTupleProcessingMetrics() {
        Map<String, Object> tupleProcessingMetrics = new HashMap<>();

        // Collect from RL tuple processings
        for (Map.Entry<Integer, RLTupleProcessing> entry : rlTupleProcessings.entrySet()) {
            int processingId = entry.getKey();
            RLTupleProcessing processing = entry.getValue();

            // Use real methods from RLTupleProcessing
            Map<String, Object> processingMetrics = processing.collectRLTupleMetrics();
            tupleProcessingMetrics.put("processing_" + processingId, processingMetrics);
        }

        this.rlTupleProcessingMetrics = tupleProcessingMetrics;
        logger.fine("Collected RL tuple processing metrics: " + tupleProcessingMetrics.size() + " entries");
    }

    /**
     * Collect gRPC performance metrics
     */
    private void collectGRPCMetrics() {
        Map<String, Object> grpcMetrics = new HashMap<>();

        // Collect from allocation clients
        for (Map.Entry<Integer, AllocationClient> entry : allocationClients.entrySet()) {
            int clientId = entry.getKey();
            AllocationClient client = entry.getValue();

            // Use real methods from AllocationClient
            grpcMetrics.put("allocation_client_" + clientId + "_total_requests", client.getTotalRequests());
            grpcMetrics.put("allocation_client_" + clientId + "_successful_requests", client.getSuccessfulRequests());
            grpcMetrics.put("allocation_client_" + clientId + "_failed_requests", client.getFailedRequests());
            grpcMetrics.put("allocation_client_" + clientId + "_success_rate", client.getSuccessRate());
            grpcMetrics.put("allocation_client_" + clientId + "_average_latency", client.getAverageLatency());
            grpcMetrics.put("allocation_client_" + clientId + "_max_latency", client.getMaxLatency());
            grpcMetrics.put("allocation_client_" + clientId + "_is_connected", client.isConnected());
        }

        // Collect from scheduler clients
        for (Map.Entry<Integer, SchedulerClient> entry : schedulerClients.entrySet()) {
            int clientId = entry.getKey();
            SchedulerClient client = entry.getValue();

            // Use real methods from SchedulerClient
            grpcMetrics.put("scheduler_client_" + clientId + "_total_requests", client.getTotalRequests());
            grpcMetrics.put("scheduler_client_" + clientId + "_successful_requests", client.getSuccessfulRequests());
            grpcMetrics.put("scheduler_client_" + clientId + "_failed_requests", client.getFailedRequests());
            grpcMetrics.put("scheduler_client_" + clientId + "_success_rate", client.getSuccessRate());
            grpcMetrics.put("scheduler_client_" + clientId + "_average_latency", client.getAverageLatency());
            grpcMetrics.put("scheduler_client_" + clientId + "_max_latency", client.getMaxLatency());
            grpcMetrics.put("scheduler_client_" + clientId + "_is_connected", client.isConnected());
        }

        this.grpcPerformanceMetrics = grpcMetrics;
        logger.fine("Collected gRPC performance metrics: " + grpcMetrics.size() + " entries");
    }

    /**
     * Collect RL learning metrics
     */
    private void collectRLLearningMetrics() {
        Map<String, Object> learningMetrics = new HashMap<>();

        // Collect learning metrics from all RL components
        learningMetrics.put("total_rl_components", getTotalRLComponents());
        learningMetrics.put("enabled_rl_components", getEnabledRLComponents());
        learningMetrics.put("configured_rl_components", getConfiguredRLComponents());

        // Collect learning progress metrics
        learningMetrics.put("total_allocation_decisions", getTotalAllocationDecisions());
        learningMetrics.put("total_scheduling_decisions", getTotalSchedulingDecisions());
        learningMetrics.put("total_module_placements", getTotalModulePlacements());
        learningMetrics.put("total_tuple_processings", getTotalTupleProcessings());

        this.rlLearningMetrics = learningMetrics;
        logger.fine("Collected RL learning metrics: " + learningMetrics.size() + " entries");
    }

    /**
     * Collect simulation validation metrics
     */
    private void collectSimulationValidationMetrics() {
        Map<String, Object> validationMetrics = new HashMap<>();

        // Collect validation metrics
        validationMetrics.put("simulation_accuracy", calculateSimulationAccuracy());
        validationMetrics.put("rl_effectiveness", calculateRLEffectiveness());
        validationMetrics.put("performance_improvement", calculatePerformanceImprovement());
        validationMetrics.put("energy_efficiency", calculateEnergyEfficiency());
        validationMetrics.put("cost_effectiveness", calculateCostEffectiveness());

        this.simulationValidationMetrics = validationMetrics;
        logger.fine("Collected simulation validation metrics: " + validationMetrics.size() + " entries");
    }

    /**
     * Collect iFogSim integration metrics
     */
    private void collectiFogSimIntegrationMetrics() {
        Map<String, Object> integrationMetrics = new HashMap<>();

        // Collect iFogSim integration metrics
        integrationMetrics.put("total_energy_consumed", getTotalEnergyConsumed());
        integrationMetrics.put("total_cost", getTotalCost());
        integrationMetrics.put("average_latency", getAverageLatency());
        integrationMetrics.put("total_throughput", getTotalThroughput());
        integrationMetrics.put("resource_utilization", getResourceUtilization());

        this.iFogSimIntegrationMetrics = integrationMetrics;
        logger.fine("Collected iFogSim integration metrics: " + integrationMetrics.size() + " entries");
    }

    /**
     * Get all collected metrics
     * 
     * @return Complete metrics map
     */
    public Map<String, Object> getAllMetrics() {
        Map<String, Object> allMetrics = new HashMap<>();
        allMetrics.put("rl_allocation_metrics", rlAllocationMetrics);
        allMetrics.put("rl_scheduling_metrics", rlSchedulingMetrics);
        allMetrics.put("rl_module_metrics", rlModuleMetrics);
        allMetrics.put("rl_tuple_processing_metrics", rlTupleProcessingMetrics);
        allMetrics.put("grpc_performance_metrics", grpcPerformanceMetrics);
        allMetrics.put("rl_learning_metrics", rlLearningMetrics);
        allMetrics.put("simulation_validation_metrics", simulationValidationMetrics);
        allMetrics.put("ifogsim_integration_metrics", iFogSimIntegrationMetrics);
        allMetrics.put("collection_timestamp", System.currentTimeMillis());
        return allMetrics;
    }

    /**
     * Get cached metrics
     * 
     * @return Cached metrics map
     */
    private Map<String, Object> getCachedMetrics() {
        return getAllMetrics();
    }

    // Helper methods for metric calculations
    private int getTotalRLComponents() {
        return rlBrokers.size() + rlControllers.size() + rlApplications.size() +
                rlModulePlacements.size() + rlTupleProcessings.size() +
                rlCloudDevices.size() + rlFogDevices.size();
    }

    private int getEnabledRLComponents() {
        // Count components that have RL enabled
        int enabledCount = 0;

        // Check brokers
        for (RLFogBroker broker : rlBrokers.values()) {
            if (broker.isRLEnabled())
                enabledCount++;
        }

        // Check controllers
        for (RLController controller : rlControllers.values()) {
            if (controller.isRLEnabled())
                enabledCount++;
        }

        // Check applications
        for (RLApplication app : rlApplications.values()) {
            if (app.isRLEnabled())
                enabledCount++;
        }

        // Check module placements
        for (RLModulePlacement placement : rlModulePlacements.values()) {
            if (placement.isRLEnabled())
                enabledCount++;
        }

        // Check tuple processings
        for (RLTupleProcessing processing : rlTupleProcessings.values()) {
            if (processing.isRLEnabled())
                enabledCount++;
        }

        // Check cloud devices
        for (RLCloudDevice device : rlCloudDevices.values()) {
            if (device.isRlEnabled())
                enabledCount++;
        }

        // Check fog devices
        for (RLFogDevice device : rlFogDevices.values()) {
            if (device.isRlEnabled())
                enabledCount++;
        }

        return enabledCount;
    }

    private int getConfiguredRLComponents() {
        // Count components that have RL configured
        int configuredCount = 0;

        // Check brokers
        for (RLFogBroker broker : rlBrokers.values()) {
            if (broker.isRLConfigured())
                configuredCount++;
        }

        // Check controllers
        for (RLController controller : rlControllers.values()) {
            if (controller.isRLConfigured())
                configuredCount++;
        }

        // Check applications
        for (RLApplication app : rlApplications.values()) {
            if (app.isRLConfigured())
                configuredCount++;
        }

        // Check module placements
        for (RLModulePlacement placement : rlModulePlacements.values()) {
            if (placement.isRLConfigured())
                configuredCount++;
        }

        // Check tuple processings
        for (RLTupleProcessing processing : rlTupleProcessings.values()) {
            if (processing.isRLConfigured())
                configuredCount++;
        }

        // Check cloud devices
        for (RLCloudDevice device : rlCloudDevices.values()) {
            if (device.isRLConfigured())
                configuredCount++;
        }

        // Check fog devices
        for (RLFogDevice device : rlFogDevices.values()) {
            if (device.isRLConfigured())
                configuredCount++;
        }

        return configuredCount;
    }

    private long getTotalAllocationDecisions() {
        // Sum up allocation decisions from all RL cloud devices
        long total = 0;
        for (RLCloudDevice device : rlCloudDevices.values()) {
            total += device.getTotalAllocationDecisions();
        }
        return total;
    }

    private long getTotalSchedulingDecisions() {
        // Sum up scheduling decisions from all RL fog devices
        long total = 0;
        for (RLFogDevice device : rlFogDevices.values()) {
            total += device.getTotalSchedulingDecisions();
        }
        return total;
    }

    private long getTotalModulePlacements() {
        // Sum up placement decisions from all RL module placements
        long total = 0;
        for (RLModulePlacement placement : rlModulePlacements.values()) {
            // Get placement decisions from the placement metrics
            Map<String, Object> metrics = placement.collectRLPlacementMetrics();
            Object decisions = metrics.get("total_placement_decisions");
            if (decisions instanceof Number) {
                total += ((Number) decisions).longValue();
            }
        }
        return total;
    }

    private long getTotalTupleProcessings() {
        // Sum up tuple processings from all RL tuple processing instances
        long total = 0;
        for (RLTupleProcessing processing : rlTupleProcessings.values()) {
            // Get tuple processing count from the processing metrics
            Map<String, Object> metrics = processing.collectRLTupleMetrics();
            Object processed = metrics.get("tuples_processed");
            if (processed instanceof Number) {
                total += ((Number) processed).longValue();
            }
        }
        return total;
    }

    private double calculateSimulationAccuracy() {
        // Calculate simulation accuracy based on RL decisions
        double totalSuccessRate = 0.0;
        int count = 0;

        // Average success rates from all devices
        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalSuccessRate += device.getAllocationSuccessRate();
            count++;
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalSuccessRate += device.getSchedulingSuccessRate();
            count++;
        }

        return count > 0 ? totalSuccessRate / count : 0.0;
    }

    private double calculateRLEffectiveness() {
        // Calculate RL effectiveness based on decision quality
        double totalDecisions = getTotalAllocationDecisions() + getTotalSchedulingDecisions();
        double successfulDecisions = 0.0;

        for (RLCloudDevice device : rlCloudDevices.values()) {
            successfulDecisions += device.getTotalAllocationDecisions() * device.getAllocationSuccessRate();
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            successfulDecisions += device.getTotalSchedulingDecisions() * device.getSchedulingSuccessRate();
        }

        return totalDecisions > 0 ? successfulDecisions / totalDecisions : 0.0;
    }

    private double calculatePerformanceImprovement() {
        // Calculate performance improvement based on throughput
        double totalThroughput = 0.0;
        int count = 0;

        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalThroughput += device.getAllocationThroughput();
            count++;
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalThroughput += device.getSchedulingThroughput();
            count++;
        }

        // Get baseline throughput from configuration
        double baselineThroughput = EnhancedConfigurationLoader.getSimulationConfigDouble(
                "simulation.performance.baseline-throughput", 10.0);
        double avgThroughput = count > 0 ? totalThroughput / count : 0.0;

        return baselineThroughput > 0 ? (avgThroughput - baselineThroughput) / baselineThroughput : 0.0;
    }

    private double calculateEnergyEfficiency() {
        // Calculate energy efficiency based on energy per decision
        double totalEnergy = 0.0;
        double totalDecisions = 0.0;

        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalEnergy += device.getTotalAllocationEnergy();
            totalDecisions += device.getTotalAllocationDecisions();
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalEnergy += device.getTotalSchedulingEnergy();
            totalDecisions += device.getTotalSchedulingDecisions();
        }

        // Energy efficiency = decisions per unit energy (higher is better)
        return totalEnergy > 0 ? totalDecisions / totalEnergy : 0.0;
    }

    private double calculateCostEffectiveness() {
        // Calculate cost effectiveness based on decisions per unit cost
        double totalCost = 0.0;
        double totalDecisions = 0.0;

        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalCost += device.getTotalAllocationCost();
            totalDecisions += device.getTotalAllocationDecisions();
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalCost += device.getTotalSchedulingCost();
            totalDecisions += device.getTotalSchedulingDecisions();
        }

        // Cost effectiveness = decisions per unit cost (higher is better)
        return totalCost > 0 ? totalDecisions / totalCost : 0.0;
    }

    private double getTotalEnergyConsumed() {
        // Sum up energy consumption from all devices
        double totalEnergy = 0.0;

        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalEnergy += device.getTotalEnergyConsumed();
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalEnergy += device.getTotalEnergyConsumed();
        }

        return totalEnergy;
    }

    private double getTotalCost() {
        // Sum up cost from all devices
        double totalCost = 0.0;

        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalCost += device.getTotalCost();
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalCost += device.getTotalCost();
        }

        return totalCost;
    }

    private double getAverageLatency() {
        // Calculate average latency across all components
        double totalLatency = 0.0;
        int count = 0;

        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalLatency += device.getAverageAllocationLatency();
            count++;
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalLatency += device.getAverageSchedulingLatency();
            count++;
        }

        return count > 0 ? totalLatency / count : 0.0;
    }

    private double getTotalThroughput() {
        // Calculate total throughput across all devices
        double totalThroughput = 0.0;

        for (RLCloudDevice device : rlCloudDevices.values()) {
            totalThroughput += device.getAllocationThroughput();
        }
        for (RLFogDevice device : rlFogDevices.values()) {
            totalThroughput += device.getSchedulingThroughput();
        }

        return totalThroughput;
    }

    private double getResourceUtilization() {
        // Calculate resource utilization based on queue sizes and processing rates
        double totalQueueSize = 0.0;
        double totalProcessingRate = 0.0;

        for (RLFogDevice device : rlFogDevices.values()) {
            totalQueueSize += device.getUnscheduledQueueSize() + device.getScheduledQueueSize();
            totalProcessingRate += device.getSchedulingThroughput();
        }

        // Resource utilization = processing rate / (processing rate + queue size)
        // Higher processing rate and lower queue size = higher utilization
        double denominator = totalProcessingRate + totalQueueSize;
        return denominator > 0 ? totalProcessingRate / denominator : 0.0;
    }

    /**
     * Generate comprehensive metrics report
     * 
     * @return Metrics report string
     */
    public String generateMetricsReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== RL Metrics Collection Report ===\n");
        report.append("Collection Time: ").append(new Date()).append("\n");
        report.append("Total RL Components: ").append(getTotalRLComponents()).append("\n");
        report.append("Enabled RL Components: ").append(getEnabledRLComponents()).append("\n");
        report.append("Configured RL Components: ").append(getConfiguredRLComponents()).append("\n");
        report.append("Total Allocation Decisions: ").append(getTotalAllocationDecisions()).append("\n");
        report.append("Total Scheduling Decisions: ").append(getTotalSchedulingDecisions()).append("\n");
        report.append("Total Module Placements: ").append(getTotalModulePlacements()).append("\n");
        report.append("Total Tuple Processings: ").append(getTotalTupleProcessings()).append("\n");
        report.append("Total Energy Consumed: ").append(getTotalEnergyConsumed()).append(" J\n");
        report.append("Total Cost: ").append(getTotalCost()).append("\n");
        report.append("Simulation Accuracy: ").append(calculateSimulationAccuracy()).append("\n");
        report.append("RL Effectiveness: ").append(calculateRLEffectiveness()).append("\n");
        report.append("Performance Improvement: ").append(calculatePerformanceImprovement()).append("\n");
        report.append("Energy Efficiency: ").append(calculateEnergyEfficiency()).append("\n");
        report.append("Cost Effectiveness: ").append(calculateCostEffectiveness()).append("\n");
        return report.toString();
    }

    /**
     * Check if collection is enabled
     * 
     * @return True if collection is enabled
     */
    public boolean isCollectionEnabled() {
        return collectionEnabled;
    }

    /**
     * Check if collection is configured
     * 
     * @return True if collection is configured
     */
    public boolean isCollectionConfigured() {
        return collectionConfigured;
    }
}
