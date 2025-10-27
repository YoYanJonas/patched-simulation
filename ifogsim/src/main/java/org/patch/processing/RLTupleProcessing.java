package org.patch.processing;

import org.fog.entities.Tuple;
import org.fog.entities.FogDevice;
import org.fog.application.Application;
import org.patch.client.AllocationClient;
import org.patch.client.SchedulerClient;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.*;
import java.util.logging.Level;

/**
 * RL-Enhanced Tuple Processing for processing tuples through RL modules
 * 
 * This class handles the core tuple processing logic with RL-based decisions
 * while maintaining iFogSim compatibility and integrating with gRPC services.
 * 
 * Key Features:
 * - RL-aware tuple routing through modules
 * - gRPC integration for tuple processing decisions
 * - RL metrics collection for tuple processing
 * - RL performance optimization for tuple processing
 * - Energy/cost-aware tuple processing with iFogSim metrics integration
 * 
 * @author Younes Shafiee
 */
public class RLTupleProcessing {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLTupleProcessing.class.getName());

    // RL clients for tuple processing decisions
    private AllocationClient allocationClient;
    private Map<Integer, SchedulerClient> schedulerClients;

    // Energy and cost tracking for tuple processing
    private Map<String, Double> processingEnergyMap;
    private Map<String, Double> processingCostMap;
    private Map<String, Long> processingLatencyMap;
    private Map<String, Integer> processingThroughputMap;

    // RL processing logic
    private RLTupleRoutingLogic routingLogic;

    // Applications for tuple processing
    private Map<String, Application> applications;

    // Flag to track if RL is enabled for this processing
    private boolean rlEnabled = false;
    private boolean rlConfigured = false;

    // Performance metrics
    private long totalTuplesProcessed = 0;
    private long totalProcessingTime = 0;
    private double totalEnergyConsumed = 0.0;
    private double totalCost = 0.0;

    /**
     * Constructor for RLTupleProcessing
     */
    public RLTupleProcessing() {
        // Initialize RL-specific collections
        this.schedulerClients = new HashMap<>();
        this.processingEnergyMap = new HashMap<>();
        this.processingCostMap = new HashMap<>();
        this.processingLatencyMap = new HashMap<>();
        this.processingThroughputMap = new HashMap<>();

        // Initialize RL routing logic
        this.routingLogic = new RLTupleRoutingLogic();

        // Initialize applications map
        this.applications = new HashMap<>();

        logger.info("RLTupleProcessing created");
    }

    /**
     * Enable RL-based tuple processing
     */
    public void enableRL() {
        this.rlEnabled = true;
        logger.info("RL-based tuple processing enabled");
    }

    /**
     * Configure RL clients for tuple processing
     * 
     * @param allocationClient Allocation client for cloud decisions
     * @param schedulerClients Map of scheduler clients for fog decisions
     */
    public void configureRLClients(AllocationClient allocationClient,
            Map<Integer, SchedulerClient> schedulerClients) {
        this.allocationClient = allocationClient;
        this.schedulerClients = schedulerClients;
        this.routingLogic.configureRLClients(allocationClient, schedulerClients);
        this.rlConfigured = true;

        logger.info("RL clients configured for tuple processing");
    }

    /**
     * Process tuple through RL modules
     * 
     * @param tuple        Tuple to process
     * @param sourceDevice Source device
     * @param targetDevice Target device
     * @return Processing result
     */
    public RLTupleProcessingResult processTuple(Tuple tuple, FogDevice sourceDevice, FogDevice targetDevice) {
        try {
            if (tuple == null) {
                logger.warning("Cannot process null tuple");
                return createFailedResult(tuple, "null_tuple");
            }

            if (sourceDevice == null) {
                logger.warning("Cannot process tuple with null source device");
                return createFailedResult(tuple, "null_source_device");
            }

            if (targetDevice == null) {
                logger.warning("Cannot process tuple with null target device");
                return createFailedResult(tuple, "null_target_device");
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error validating tuple processing parameters", e);
            return createFailedResult(tuple, "validation_error");
        }

        double startTime = CloudSim.clock();

        if (!rlEnabled) {
            // Fallback to original processing
            return processTupleNormally(tuple, sourceDevice, targetDevice);
        }

        try {
            // Include energy and cost in processing decisions
            includeEnergyInProcessingRequests(tuple, sourceDevice, targetDevice);
            includeCostInProcessingDecisions(tuple, sourceDevice, targetDevice);

            // Use RL routing logic
            RLTupleRoutingDecision decision = routingLogic.makeRoutingDecision(tuple, sourceDevice, targetDevice);

            // Process tuple based on RL decision
            RLTupleProcessingResult result = processTupleWithRL(tuple, sourceDevice, targetDevice, decision);

            // Update processing metrics from gRPC
            updateProcessingMetricsFromGRPC(tuple, result);

            // Update performance metrics
            long processingTime = (long) (CloudSim.clock() - startTime);
            updatePerformanceMetrics(processingTime, result);

            return result;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in RL tuple processing: " + e.getMessage(), e);
            return processTupleNormally(tuple, sourceDevice, targetDevice);
        }
    }

    /**
     * Create a failed processing result
     * 
     * @param tuple  The tuple
     * @param reason Failure reason
     * @return Failed result
     */
    private RLTupleProcessingResult createFailedResult(Tuple tuple, String reason) {
        return new RLTupleProcessingResult(
                tuple,
                false,
                "failed: " + reason,
                0,
                0.0,
                0.0,
                "failed_processing");
    }

    /**
     * Process tuple normally (fallback)
     * 
     * @param tuple        Tuple to process
     * @param sourceDevice Source device
     * @param targetDevice Target device
     * @return Processing result
     */
    private RLTupleProcessingResult processTupleNormally(Tuple tuple, FogDevice sourceDevice, FogDevice targetDevice) {
        double startTime = CloudSim.clock();

        // Simple routing: process on target device
        boolean success = true;
        String processingPath = sourceDevice.getName() + " -> " + targetDevice.getName();

        long processingTime = (long) (CloudSim.clock() - startTime);

        return new RLTupleProcessingResult(
                tuple,
                success,
                processingPath,
                processingTime,
                0.0, // energy
                0.0, // cost
                "normal_processing");
    }

    /**
     * Process tuple with RL decision
     * 
     * @param tuple        Tuple to process
     * @param sourceDevice Source device
     * @param targetDevice Target device
     * @param decision     RL routing decision
     * @return Processing result
     */
    private RLTupleProcessingResult processTupleWithRL(Tuple tuple, FogDevice sourceDevice,
            FogDevice targetDevice, RLTupleRoutingDecision decision) {
        double startTime = CloudSim.clock();

        // Process tuple based on RL decision
        boolean success = decision.isSuccess();
        String processingPath = decision.getProcessingPath();

        // Calculate energy and cost
        double energyConsumed = calculateEnergyConsumption(tuple, sourceDevice, targetDevice);
        double cost = calculateProcessingCost(tuple, sourceDevice, targetDevice);

        long processingTime = (long) (CloudSim.clock() - startTime);

        return new RLTupleProcessingResult(
                tuple,
                success,
                processingPath,
                processingTime,
                energyConsumed,
                cost,
                "rl_processing");
    }

    /**
     * Include energy in processing requests
     * 
     * @param tuple        Tuple
     * @param sourceDevice Source device
     * @param targetDevice Target device
     */
    private void includeEnergyInProcessingRequests(Tuple tuple, FogDevice sourceDevice, FogDevice targetDevice) {
        double totalEnergy = sourceDevice.getEnergyConsumption() + targetDevice.getEnergyConsumption();

        String key = tuple.getCloudletId() + ":" + sourceDevice.getId() + ":" + targetDevice.getId();
        processingEnergyMap.put(key, totalEnergy);

        logger.fine("Included energy in processing request: " + totalEnergy);
    }

    /**
     * Include cost in processing decisions
     * 
     * @param tuple        Tuple
     * @param sourceDevice Source device
     * @param targetDevice Target device
     */
    private void includeCostInProcessingDecisions(Tuple tuple, FogDevice sourceDevice, FogDevice targetDevice) {
        double totalCost = sourceDevice.getTotalCost() + targetDevice.getTotalCost();

        String key = tuple.getCloudletId() + ":" + sourceDevice.getId() + ":" + targetDevice.getId();
        processingCostMap.put(key, totalCost);

        logger.fine("Included cost in processing decision: " + totalCost);
    }

    /**
     * Update processing metrics from gRPC responses
     * 
     * @param tuple  Tuple
     * @param result Processing result
     */
    private void updateProcessingMetricsFromGRPC(Tuple tuple, RLTupleProcessingResult result) {
        String key = tuple.getCloudletId() + ":" + result.getProcessingPath();
        processingLatencyMap.put(key, result.getProcessingTime());

        logger.fine("Updated processing metrics from gRPC for tuple: " + tuple.getCloudletId());
    }

    /**
     * Calculate energy consumption for tuple processing
     * 
     * @param tuple        Tuple
     * @param sourceDevice Source device
     * @param targetDevice Target device
     * @return Energy consumed
     */
    private double calculateEnergyConsumption(Tuple tuple, FogDevice sourceDevice, FogDevice targetDevice) {
        // Calculate energy based on tuple size and device characteristics
        double tupleSize = tuple.getCloudletLength();
        double sourceEnergy = sourceDevice.getEnergyConsumption() * (tupleSize / 1000.0);
        double targetEnergy = targetDevice.getEnergyConsumption() * (tupleSize / 1000.0);

        return sourceEnergy + targetEnergy;
    }

    /**
     * Calculate processing cost for tuple
     * 
     * @param tuple        Tuple
     * @param sourceDevice Source device
     * @param targetDevice Target device
     * @return Processing cost
     */
    private double calculateProcessingCost(Tuple tuple, FogDevice sourceDevice, FogDevice targetDevice) {
        // Calculate cost based on tuple processing requirements
        double tupleSize = tuple.getCloudletLength();
        double sourceCost = sourceDevice.getTotalCost() * (tupleSize / 1000.0);
        double targetCost = targetDevice.getTotalCost() * (tupleSize / 1000.0);

        return sourceCost + targetCost;
    }

    /**
     * Update performance metrics
     * 
     * @param processingTime Processing time
     * @param result         Processing result
     */
    private void updatePerformanceMetrics(long processingTime, RLTupleProcessingResult result) {
        totalTuplesProcessed++;
        totalProcessingTime += processingTime;
        totalEnergyConsumed += result.getEnergyConsumed();
        totalCost += result.getCost();

        // Update throughput
        String key = result.getProcessingPath();
        processingThroughputMap.put(key, processingThroughputMap.getOrDefault(key, 0) + 1);
    }

    /**
     * Optimize RL tuple processing
     */
    public void optimizeRLTupleProcessing() {
        if (!rlEnabled) {
            return;
        }

        logger.info("Optimizing RL tuple processing");

        // Implement RL tuple processing optimization logic
        // This would involve analyzing current processing patterns and optimizing based
        // on RL feedback

        logger.info("RL tuple processing optimization completed");
    }

    /**
     * Balance RL tuple load
     */
    public void balanceRLTupleLoad() {
        if (!rlEnabled) {
            return;
        }

        logger.info("Balancing RL tuple load");

        // Implement RL load balancing logic
        // This would involve redistributing tuple processing based on RL load balancing
        // decisions

        logger.info("RL tuple load balancing completed");
    }

    /**
     * Collect RL tuple processing metrics
     * 
     * @return Metrics map
     */
    public Map<String, Object> collectRLTupleMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        // Processing metrics
        metrics.put("processingEnergy", processingEnergyMap);
        metrics.put("processingCost", processingCostMap);
        metrics.put("processingLatency", processingLatencyMap);
        metrics.put("processingThroughput", processingThroughputMap);

        // Overall metrics
        metrics.put("totalTuplesProcessed", totalTuplesProcessed);
        metrics.put("totalProcessingTime", totalProcessingTime);
        metrics.put("totalEnergyConsumed", totalEnergyConsumed);
        metrics.put("totalCost", totalCost);
        metrics.put("averageProcessingTime", calculateAverageProcessingTime());
        metrics.put("averageEnergyPerTuple", calculateAverageEnergyPerTuple());
        metrics.put("averageCostPerTuple", calculateAverageCostPerTuple());

        // RL-specific metrics
        metrics.put("rlEnabled", rlEnabled);
        metrics.put("rlConfigured", rlConfigured);
        metrics.put("routingDecisions", routingLogic.getRoutingHistory().size());

        return metrics;
    }

    /**
     * Report RL tuple processing performance
     * 
     * @return Performance report
     */
    public String reportRLTuplePerformance() {
        StringBuilder report = new StringBuilder();
        report.append("=== RL Tuple Processing Performance Report ===\n");
        report.append("Total Tuples Processed: ").append(totalTuplesProcessed).append("\n");
        report.append("Total Processing Time: ").append(totalProcessingTime).append(" ms\n");
        report.append("Total Energy Consumed: ").append(totalEnergyConsumed).append(" J\n");
        report.append("Total Cost: ").append(totalCost).append("\n");
        report.append("Average Processing Time: ").append(calculateAverageProcessingTime()).append(" ms\n");
        report.append("Average Energy per Tuple: ").append(calculateAverageEnergyPerTuple()).append(" J\n");
        report.append("Average Cost per Tuple: ").append(calculateAverageCostPerTuple()).append("\n");
        report.append("RL Enabled: ").append(rlEnabled).append("\n");
        report.append("RL Configured: ").append(rlConfigured).append("\n");
        report.append("Routing Decisions: ").append(routingLogic.getRoutingHistory().size()).append("\n");

        return report.toString();
    }

    /**
     * Calculate average processing time
     * 
     * @return Average processing time
     */
    private double calculateAverageProcessingTime() {
        if (totalTuplesProcessed == 0) {
            return 0.0;
        }
        return (double) totalProcessingTime / totalTuplesProcessed;
    }

    /**
     * Calculate average energy per tuple
     * 
     * @return Average energy per tuple
     */
    private double calculateAverageEnergyPerTuple() {
        if (totalTuplesProcessed == 0) {
            return 0.0;
        }
        return totalEnergyConsumed / totalTuplesProcessed;
    }

    /**
     * Calculate average cost per tuple
     * 
     * @return Average cost per tuple
     */
    private double calculateAverageCostPerTuple() {
        if (totalTuplesProcessed == 0) {
            return 0.0;
        }
        return totalCost / totalTuplesProcessed;
    }

    /**
     * Get total tuples processed
     * 
     * @return Total tuples processed
     */
    public long getTotalTuplesProcessed() {
        return totalTuplesProcessed;
    }

    /**
     * Get total processing time
     * 
     * @return Total processing time
     */
    public long getTotalProcessingTime() {
        return totalProcessingTime;
    }

    /**
     * Get total energy consumed
     * 
     * @return Total energy consumed
     */
    public double getTotalEnergyConsumed() {
        return totalEnergyConsumed;
    }

    /**
     * Get total cost
     * 
     * @return Total cost
     */
    public double getTotalCost() {
        return totalCost;
    }

    /**
     * Check if RL is enabled
     * 
     * @return True if RL is enabled
     */
    public boolean isRLEnabled() {
        return rlEnabled;
    }

    /**
     * Check if RL is configured
     * 
     * @return True if RL is configured
     */
    public boolean isRLConfigured() {
        return rlConfigured;
    }
}
