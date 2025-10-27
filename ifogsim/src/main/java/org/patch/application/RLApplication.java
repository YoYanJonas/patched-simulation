package org.patch.application;

import org.fog.application.Application;
import org.fog.application.AppModule;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.entities.Tuple;
import org.fog.utils.GeoCoverage;
import org.patch.client.AllocationClient;
import org.patch.client.SchedulerClient;
import org.fog.utils.FogUtils;
import org.patch.utils.TupleFactory;

import java.util.*;

/**
 * RL-Enhanced Application for defining RL-aware modules and data flow
 * 
 * This class extends the original Application to integrate with RL-based
 * module execution and data flow while maintaining iFogSim compatibility.
 * 
 * Key Features:
 * - RL module definitions with energy/cost characteristics
 * - gRPC-aware data flow processing
 * - Energy and cost tracking for module execution
 * - Integration with iFogSim's built-in metric collection
 * 
 * @author Younes Shafiee
 */
public class RLApplication extends Application {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLApplication.class.getName());

    // RL module definitions
    private List<RLAppModule> rlModules;
    private Map<String, RLAppEdge> rlEdges;

    // gRPC integration
    private AllocationClient allocationClient;
    private SchedulerClient schedulerClient;

    // Energy and cost tracking for module execution
    private Map<String, Double> moduleEnergyMap;
    private Map<String, Double> moduleCostMap;
    private Map<String, Long> moduleLatencyMap;

    // RL event handling (reserved for future use)
    // private static final int RL_MODULE_EXECUTION = 50001;
    // private static final int RL_DATA_FLOW = 50002;
    // private static final int RL_METRICS_COLLECTION = 50003;

    // Flag to track if RL is enabled for this application
    private boolean rlEnabled = false;
    private boolean rlConfigured = false;

    /**
     * Constructor for RLApplication
     * 
     * @param appId  Application ID
     * @param userId User ID
     */
    public RLApplication(String appId, int userId) {
        super(appId, userId);

        // Initialize RL-specific collections
        this.rlModules = new ArrayList<>();
        this.rlEdges = new HashMap<>();
        this.moduleEnergyMap = new HashMap<>();
        this.moduleCostMap = new HashMap<>();
        this.moduleLatencyMap = new HashMap<>();

        logger.info("RLApplication created: " + appId);
    }

    /**
     * Constructor for RLApplication with modules and edges
     * 
     * @param appId       Application ID
     * @param modules     List of modules
     * @param edges       List of edges
     * @param loops       List of loops
     * @param geoCoverage Geographic coverage
     */
    public RLApplication(String appId, List<AppModule> modules, List<AppEdge> edges,
            List<AppLoop> loops, GeoCoverage geoCoverage) {
        super(appId, modules, edges, loops, geoCoverage);

        // Initialize RL-specific collections
        this.rlModules = new ArrayList<>();
        this.rlEdges = new HashMap<>();
        this.moduleEnergyMap = new HashMap<>();
        this.moduleCostMap = new HashMap<>();
        this.moduleLatencyMap = new HashMap<>();

        // Convert modules to RL modules
        for (AppModule module : modules) {
            RLAppModule rlModule = new RLAppModule(module);
            rlModules.add(rlModule);
        }

        // Convert edges to RL edges
        for (AppEdge edge : edges) {
            RLAppEdge rlEdge = new RLAppEdge(edge);
            rlEdges.put(edge.getTupleType(), rlEdge);
        }

        logger.info("RLApplication created with modules and edges: " + appId);
    }

    /**
     * Enable RL-based module execution and data flow for this application
     */
    public void enableRL() {
        this.rlEnabled = true;
        logger.info("RL-based module execution and data flow enabled for application: " + getAppId());
    }

    /**
     * Configure RL servers for this application
     * 
     * @param allocationClient Allocation client
     * @param schedulerClient  Scheduler client
     */
    public void configureRLServers(AllocationClient allocationClient, SchedulerClient schedulerClient) {
        if (!rlEnabled) {
            enableRL();
        }

        this.allocationClient = allocationClient;
        this.schedulerClient = schedulerClient;
        this.rlConfigured = true;

        logger.info("RL servers configured for application: " + getAppId());
    }

    /**
     * Add an RL-aware application module
     * 
     * @param moduleName        Module name
     * @param ram               RAM requirement
     * @param energyConsumption Energy consumption characteristic
     * @param costPerExecution  Cost per execution
     */
    public void addRLAppModule(String moduleName, int ram, double energyConsumption, double costPerExecution) {
        int mips = 1000;
        long size = 10000;
        long bw = 1000;
        String vmm = "Xen";

        RLAppModule module = new RLAppModule(FogUtils.generateEntityId(), moduleName, getAppId(), getUserId(),
                mips, ram, bw, size, vmm, energyConsumption, costPerExecution);

        rlModules.add(module);
        getModules().add(module); // Add to parent's modules list

        logger.info("Added RL module: " + moduleName + " to application: " + getAppId());
    }

    /**
     * Add an RL-aware application edge
     * 
     * @param source         Source module
     * @param destination    Destination module
     * @param tupleCpuLength Tuple CPU length
     * @param tupleNwLength  Tuple network length
     * @param tupleType      Tuple type
     * @param direction      Direction
     * @param edgeType       Edge type
     * @param energyCost     Energy cost for data transfer
     * @param networkCost    Network cost for data transfer
     */
    public void addRLAppEdge(String source, String destination, double tupleCpuLength,
            double tupleNwLength, String tupleType, int direction, int edgeType,
            double energyCost, double networkCost) {
        RLAppEdge edge = new RLAppEdge(source, destination, tupleCpuLength, tupleNwLength,
                tupleType, direction, edgeType, energyCost, networkCost);

        rlEdges.put(tupleType, edge);
        getEdges().add(edge); // Add to parent's edges list

        logger.info("Added RL edge: " + source + " -> " + destination + " to application: " + getAppId());
    }

    /**
     * Get RL modules
     * 
     * @return List of RL modules
     */
    public List<RLAppModule> getRLModules() {
        return rlModules;
    }

    /**
     * Get RL edges
     * 
     * @return Map of RL edges
     */
    public Map<String, RLAppEdge> getRLEdges() {
        return rlEdges;
    }

    /**
     * Get RL module by name
     * 
     * @param name Module name
     * @return RL module or null if not found
     */
    public RLAppModule getRLModuleByName(String name) {
        for (RLAppModule module : rlModules) {
            if (module.getName().equals(name)) {
                return module;
            }
        }
        return null;
    }

    /**
     * Get RL edge by tuple type
     * 
     * @param tupleType Tuple type
     * @return RL edge or null if not found
     */
    public RLAppEdge getRLEdgeByTupleType(String tupleType) {
        return rlEdges.get(tupleType);
    }

    /**
     * Process tuple through RL modules
     * 
     * @param tuple          Input tuple
     * @param sourceDeviceId Source device ID
     * @param sourceModuleId Source module ID
     * @return List of resultant tuples
     */
    public List<Tuple> processTupleThroughRLModules(Tuple tuple, int sourceDeviceId, int sourceModuleId) {
        if (!rlEnabled) {
            return super.getResultantTuples(tuple.getSrcModuleName(), tuple, sourceDeviceId, sourceModuleId);
        }

        logger.fine("Processing tuple through RL modules: " + tuple.getCloudletId());

        // Track energy and cost for module execution
        trackModuleEnergyConsumption(tuple.getSrcModuleName());
        calculateModuleExecutionCost(tuple.getSrcModuleName());

        // Process tuple through RL modules
        List<Tuple> resultantTuples = new ArrayList<>();

        for (RLAppEdge edge : rlEdges.values()) {
            if (edge.getSource().equals(tuple.getSrcModuleName())) {
                // Process edge with RL characteristics
                Tuple resultantTuple = processRLEdge(edge, tuple, sourceDeviceId, sourceModuleId);
                if (resultantTuple != null) {
                    resultantTuples.add(resultantTuple);
                }
            }
        }

        // Update iFogSim module metrics
        updateiFogSimModuleMetrics(tuple.getSrcModuleName());

        return resultantTuples;
    }

    /**
     * Process RL edge
     * 
     * @param edge           RL edge
     * @param inputTuple     Input tuple
     * @param sourceDeviceId Source device ID
     * @param sourceModuleId Source module ID
     * @return Resultant tuple or null
     */
    private Tuple processRLEdge(RLAppEdge edge, Tuple inputTuple, int sourceDeviceId, int sourceModuleId) {
        // Use TupleFactory to create tuple with RL characteristics
        Tuple tuple = TupleFactory.createForRLEdge(
                getAppId(),
                edge.getDirection(),
                (long) (edge.getTupleCpuLength()),
                (long) (edge.getTupleNwLength()),
                inputTuple.getCloudletOutputSize(),
                inputTuple,
                edge.getDestination(),
                edge.getSource(),
                edge.getTupleType(),
                sourceModuleId);

        // Track energy and cost for data transfer
        trackDataTransferEnergy(edge, tuple);
        trackDataTransferCost(edge, tuple);

        return tuple;
    }

    /**
     * Track module energy consumption
     * 
     * @param moduleName Module name
     */
    private void trackModuleEnergyConsumption(String moduleName) {
        RLAppModule module = getRLModuleByName(moduleName);
        if (module != null) {
            double energy = module.getEnergyConsumption();
            moduleEnergyMap.put(moduleName, energy);
            logger.fine("Tracked energy consumption for module " + moduleName + ": " + energy);
        }
    }

    /**
     * Calculate module execution cost
     * 
     * @param moduleName Module name
     */
    private void calculateModuleExecutionCost(String moduleName) {
        RLAppModule module = getRLModuleByName(moduleName);
        if (module != null) {
            double cost = module.getCostPerExecution();
            moduleCostMap.put(moduleName, cost);
            logger.fine("Calculated execution cost for module " + moduleName + ": " + cost);
        }
    }

    /**
     * Track data transfer energy
     * 
     * @param edge  RL edge
     * @param tuple Tuple
     */
    private void trackDataTransferEnergy(RLAppEdge edge, Tuple tuple) {
        double energy = edge.getEnergyCost();
        moduleEnergyMap.put(edge.getTupleType(), energy);
        logger.fine("Tracked data transfer energy for edge " + edge.getTupleType() + ": " + energy);
    }

    /**
     * Track data transfer cost
     * 
     * @param edge  RL edge
     * @param tuple Tuple
     */
    private void trackDataTransferCost(RLAppEdge edge, Tuple tuple) {
        double cost = edge.getNetworkCost();
        moduleCostMap.put(edge.getTupleType(), cost);
        logger.fine("Tracked data transfer cost for edge " + edge.getTupleType() + ": " + cost);
    }

    /**
     * Update iFogSim module metrics
     * 
     * @param moduleName Module name
     */
    private void updateiFogSimModuleMetrics(String moduleName) {
        // Update energy metrics
        double totalEnergy = calculateTotalModuleEnergy();
        logger.fine("Total module energy consumption: " + totalEnergy);

        // Update cost metrics
        double totalCost = calculateTotalModuleCost();
        logger.fine("Total module cost: " + totalCost);

        // Update latency metrics
        double avgLatency = calculateAverageModuleLatency();
        logger.fine("Average module latency: " + avgLatency);
    }

    /**
     * Calculate total module energy consumption
     * 
     * @return Total energy consumption
     */
    private double calculateTotalModuleEnergy() {
        double total = 0.0;
        for (Double energy : moduleEnergyMap.values()) {
            total += energy;
        }
        return total;
    }

    /**
     * Calculate total module cost
     * 
     * @return Total cost
     */
    private double calculateTotalModuleCost() {
        double total = 0.0;
        for (Double cost : moduleCostMap.values()) {
            total += cost;
        }
        return total;
    }

    /**
     * Calculate average module latency
     * 
     * @return Average latency
     */
    private double calculateAverageModuleLatency() {
        if (moduleLatencyMap.isEmpty()) {
            return 0.0;
        }

        long total = 0;
        for (Long latency : moduleLatencyMap.values()) {
            total += latency;
        }
        return (double) total / moduleLatencyMap.size();
    }

    /**
     * Collect RL application metrics
     * 
     * @return Map of metrics
     */
    public Map<String, Object> collectRLApplicationMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        // Module metrics
        metrics.put("moduleEnergy", moduleEnergyMap);
        metrics.put("moduleCost", moduleCostMap);
        metrics.put("moduleLatency", moduleLatencyMap);

        // Overall metrics
        metrics.put("totalEnergy", calculateTotalModuleEnergy());
        metrics.put("totalCost", calculateTotalModuleCost());
        metrics.put("averageLatency", calculateAverageModuleLatency());

        // RL-specific metrics
        metrics.put("rlEnabled", rlEnabled);
        metrics.put("rlConfigured", rlConfigured);
        metrics.put("rlModuleCount", rlModules.size());
        metrics.put("rlEdgeCount", rlEdges.size());

        return metrics;
    }

    /**
     * Report RL performance
     * 
     * @return Performance report
     */
    public String reportRLPerformance() {
        StringBuilder report = new StringBuilder();
        report.append("RL Application Performance Report for ").append(getAppId()).append("\n");
        report.append("==========================================\n");
        report.append("Total Energy Consumption: ").append(calculateTotalModuleEnergy()).append("\n");
        report.append("Total Cost: ").append(calculateTotalModuleCost()).append("\n");
        report.append("Average Latency: ").append(calculateAverageModuleLatency()).append("\n");
        report.append("RL Modules: ").append(rlModules.size()).append("\n");
        report.append("RL Edges: ").append(rlEdges.size()).append("\n");
        report.append("RL Enabled: ").append(rlEnabled).append("\n");
        report.append("RL Configured: ").append(rlConfigured).append("\n");

        return report.toString();
    }

    // Getters
    public AllocationClient getAllocationClient() {
        return allocationClient;
    }

    public SchedulerClient getSchedulerClient() {
        return schedulerClient;
    }

    public boolean isRLEnabled() {
        return rlEnabled;
    }

    public boolean isRLConfigured() {
        return rlConfigured;
    }
}
