package org.patch.utils;

import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.config.EnhancedConfigurationLoader;

import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Centralized RL Statistics Manager for iFogSim Integration
 * 
 * This class provides centralized statistics tracking for all RL components,
 * eliminating duplication and ensuring consistent metrics collection across
 * the iFogSim simulation environment.
 * 
 * Key Features:
 * - Centralized statistics tracking
 * - Thread-safe operations
 * - iFogSim integration
 * - Performance monitoring
 * - Energy and cost calculation utilities
 * 
 * @author Younes Shafiee
 */
public class RLStatisticsManager {
    private static final Logger logger = Logger.getLogger(RLStatisticsManager.class.getName());

    // Singleton instance
    private static volatile RLStatisticsManager instance;

    // Event statistics
    private final AtomicLong totalEventsProcessed = new AtomicLong(0);
    private final AtomicLong rlEventsProcessed = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    // Allocation statistics
    private final AtomicLong totalAllocationDecisions = new AtomicLong(0);
    private final AtomicLong successfulAllocations = new AtomicLong(0);
    private volatile double totalAllocationEnergy = 0.0;
    private volatile double totalAllocationCost = 0.0;
    private volatile double totalAllocationLatency = 0.0;

    // Scheduling statistics
    private final AtomicLong totalSchedulingDecisions = new AtomicLong(0);
    private final AtomicLong successfulScheduling = new AtomicLong(0);
    private volatile double totalSchedulingEnergy = 0.0;
    private volatile double totalSchedulingCost = 0.0;
    private volatile double totalSchedulingLatency = 0.0;

    // Task processing statistics
    private final AtomicLong totalTasksProcessed = new AtomicLong(0);
    private final AtomicLong successfulTasks = new AtomicLong(0);
    private final AtomicLong failedTasks = new AtomicLong(0);
    private volatile double totalExecutionTime = 0.0;
    private volatile double totalExecutionEnergy = 0.0;
    private volatile double totalExecutionCost = 0.0;

    // Simulation state
    private volatile double simulationStartTime = 0.0;
    private final Map<String, Object> customMetrics = new HashMap<>();

    /**
     * Private constructor for singleton
     */
    private RLStatisticsManager() {
        simulationStartTime = CloudSim.clock();
        logger.info("RL Statistics Manager initialized");
    }

    /**
     * Get singleton instance
     */
    public static RLStatisticsManager getInstance() {
        if (instance == null) {
            synchronized (RLStatisticsManager.class) {
                if (instance == null) {
                    instance = new RLStatisticsManager();
                }
            }
        }
        return instance;
    }

    // ===== EVENT STATISTICS =====

    public void incrementEventsProcessed() {
        totalEventsProcessed.incrementAndGet();
    }

    public void incrementRLEventsProcessed() {
        rlEventsProcessed.incrementAndGet();
    }

    public void incrementErrorCount() {
        errorCount.incrementAndGet();
    }

    public long getTotalEventsProcessed() {
        return totalEventsProcessed.get();
    }

    public long getRLEventsProcessed() {
        return rlEventsProcessed.get();
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public double getRLEventPercentage() {
        long total = totalEventsProcessed.get();
        if (total == 0)
            return 0.0;
        return (double) rlEventsProcessed.get() / total *
                EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.statistics.percentage-calculation",
                        100);
    }

    // ===== ALLOCATION STATISTICS =====

    public void incrementAllocationDecisions() {
        totalAllocationDecisions.incrementAndGet();
    }

    public void incrementSuccessfulAllocations() {
        successfulAllocations.incrementAndGet();
    }

    public synchronized void addAllocationEnergy(double energy) {
        totalAllocationEnergy += energy;
    }

    public synchronized void addAllocationCost(double cost) {
        totalAllocationCost += cost;
    }

    public synchronized void addAllocationLatency(double latency) {
        totalAllocationLatency += latency;
    }

    public long getTotalAllocationDecisions() {
        return totalAllocationDecisions.get();
    }

    public long getSuccessfulAllocations() {
        return successfulAllocations.get();
    }

    public double getTotalAllocationEnergy() {
        return totalAllocationEnergy;
    }

    public double getTotalAllocationCost() {
        return totalAllocationCost;
    }

    public double getTotalAllocationLatency() {
        return totalAllocationLatency;
    }

    public double getAllocationSuccessRate() {
        long total = totalAllocationDecisions.get();
        if (total == 0)
            return 0.0;
        return (double) successfulAllocations.get() / total;
    }

    public double getAverageAllocationEnergy() {
        long total = totalAllocationDecisions.get();
        if (total == 0)
            return 0.0;
        return totalAllocationEnergy / total;
    }

    public double getAverageAllocationCost() {
        long total = totalAllocationDecisions.get();
        if (total == 0)
            return 0.0;
        return totalAllocationCost / total;
    }

    public double getAverageAllocationLatency() {
        long total = totalAllocationDecisions.get();
        if (total == 0)
            return 0.0;
        return totalAllocationLatency / total;
    }

    // ===== SCHEDULING STATISTICS =====

    public void incrementSchedulingDecisions() {
        totalSchedulingDecisions.incrementAndGet();
    }

    public void incrementSuccessfulScheduling() {
        successfulScheduling.incrementAndGet();
    }

    public synchronized void addSchedulingEnergy(double energy) {
        totalSchedulingEnergy += energy;
    }

    public synchronized void addSchedulingCost(double cost) {
        totalSchedulingCost += cost;
    }

    public synchronized void addSchedulingLatency(double latency) {
        totalSchedulingLatency += latency;
    }

    public long getTotalSchedulingDecisions() {
        return totalSchedulingDecisions.get();
    }

    public long getSuccessfulScheduling() {
        return successfulScheduling.get();
    }

    public double getTotalSchedulingEnergy() {
        return totalSchedulingEnergy;
    }

    public double getTotalSchedulingCost() {
        return totalSchedulingCost;
    }

    public double getTotalSchedulingLatency() {
        return totalSchedulingLatency;
    }

    public double getSchedulingSuccessRate() {
        long total = totalSchedulingDecisions.get();
        if (total == 0)
            return 0.0;
        return (double) successfulScheduling.get() / total;
    }

    public double getAverageSchedulingEnergy() {
        long total = totalSchedulingDecisions.get();
        if (total == 0)
            return 0.0;
        return totalSchedulingEnergy / total;
    }

    public double getAverageSchedulingCost() {
        long total = totalSchedulingDecisions.get();
        if (total == 0)
            return 0.0;
        return totalSchedulingCost / total;
    }

    public double getAverageSchedulingLatency() {
        long total = totalSchedulingDecisions.get();
        if (total == 0)
            return 0.0;
        return totalSchedulingLatency / total;
    }

    // ===== TASK PROCESSING STATISTICS =====

    public void incrementTasksProcessed() {
        totalTasksProcessed.incrementAndGet();
    }

    public void incrementSuccessfulTasks() {
        successfulTasks.incrementAndGet();
    }

    public void incrementFailedTasks() {
        failedTasks.incrementAndGet();
    }

    public synchronized void addExecutionTime(double time) {
        totalExecutionTime += time;
    }

    public synchronized void addExecutionEnergy(double energy) {
        totalExecutionEnergy += energy;
    }

    public synchronized void addExecutionCost(double cost) {
        totalExecutionCost += cost;
    }

    public long getTotalTasksProcessed() {
        return totalTasksProcessed.get();
    }

    public long getSuccessfulTasks() {
        return successfulTasks.get();
    }

    public long getFailedTasks() {
        return failedTasks.get();
    }

    public double getTotalExecutionTime() {
        return totalExecutionTime;
    }

    public double getTotalExecutionEnergy() {
        return totalExecutionEnergy;
    }

    public double getTotalExecutionCost() {
        return totalExecutionCost;
    }

    public double getTaskSuccessRate() {
        long total = totalTasksProcessed.get();
        if (total == 0)
            return 0.0;
        return (double) successfulTasks.get() / total;
    }

    public double getAverageExecutionTime() {
        long total = totalTasksProcessed.get();
        if (total == 0)
            return 0.0;
        return totalExecutionTime / total;
    }

    public double getAverageExecutionEnergy() {
        long total = totalTasksProcessed.get();
        if (total == 0)
            return 0.0;
        return totalExecutionEnergy / total;
    }

    public double getAverageExecutionCost() {
        long total = totalTasksProcessed.get();
        if (total == 0)
            return 0.0;
        return totalExecutionCost / total;
    }

    // ===== ENERGY AND COST CALCULATION UTILITIES =====

    /**
     * Calculate energy cost for allocation decision
     */
    public double calculateAllocationEnergy(double tupleLength, double latency) {
        double baseEnergy = EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.energy.base-allocation",
                0.001);
        double complexityEnergy = tupleLength * EnhancedConfigurationLoader
                .getSimulationConfigDouble("simulation.energy.complexity-factor", 0.000001);
        double latencyEnergy = latency
                * EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.energy.latency-factor", 0.00001);

        return baseEnergy + complexityEnergy + latencyEnergy;
    }

    /**
     * Calculate monetary cost for allocation decision
     */
    public double calculateAllocationCost(double tupleLength, double latency) {
        double baseCost = EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.cost.base-allocation",
                0.0001);
        double complexityCost = tupleLength
                * EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.cost.complexity-factor", 0.0000001);
        double latencyCost = latency
                * EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.cost.latency-factor", 0.000001);

        return baseCost + complexityCost + latencyCost;
    }

    /**
     * Calculate energy cost for task execution
     */
    public double calculateExecutionEnergy(double tupleLength, double executionTime) {
        double baseEnergy = EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.energy.base-execution",
                0.0001);
        double complexityEnergy = tupleLength * EnhancedConfigurationLoader
                .getSimulationConfigDouble("simulation.energy.complexity-factor", 0.000001);
        double timeEnergy = executionTime
                * EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.energy.latency-factor", 0.00001);

        return baseEnergy + complexityEnergy + timeEnergy;
    }

    /**
     * Calculate monetary cost for task execution
     */
    public double calculateExecutionCost(double tupleLength, double executionTime) {
        double baseCost = EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.cost.base-execution",
                0.00001);
        double complexityCost = tupleLength
                * EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.cost.complexity-factor", 0.0000001);
        double timeCost = executionTime
                * EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.cost.latency-factor", 0.000001);

        return baseCost + complexityCost + timeCost;
    }

    // ===== CUSTOM METRICS =====

    public void setCustomMetric(String key, Object value) {
        customMetrics.put(key, value);
    }

    public Object getCustomMetric(String key) {
        return customMetrics.get(key);
    }

    public Map<String, Object> getAllCustomMetrics() {
        return new HashMap<>(customMetrics);
    }

    // ===== SIMULATION STATE =====

    public double getSimulationTime() {
        return CloudSim.clock();
    }

    public double getSimulationDuration() {
        return CloudSim.clock() - simulationStartTime;
    }

    // ===== COMPREHENSIVE STATISTICS =====

    /**
     * Get comprehensive statistics for iFogSim integration
     */
    public Map<String, Object> getComprehensiveStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // Event statistics
        stats.put("totalEventsProcessed", getTotalEventsProcessed());
        stats.put("rlEventsProcessed", getRLEventsProcessed());
        stats.put("errorCount", getErrorCount());
        stats.put("rlEventPercentage", getRLEventPercentage());

        // Allocation statistics
        stats.put("totalAllocationDecisions", getTotalAllocationDecisions());
        stats.put("successfulAllocations", getSuccessfulAllocations());
        stats.put("allocationSuccessRate", getAllocationSuccessRate());
        stats.put("totalAllocationEnergy", getTotalAllocationEnergy());
        stats.put("averageAllocationEnergy", getAverageAllocationEnergy());
        stats.put("totalAllocationCost", getTotalAllocationCost());
        stats.put("averageAllocationCost", getAverageAllocationCost());
        stats.put("totalAllocationLatency", getTotalAllocationLatency());
        stats.put("averageAllocationLatency", getAverageAllocationLatency());

        // Scheduling statistics
        stats.put("totalSchedulingDecisions", getTotalSchedulingDecisions());
        stats.put("successfulScheduling", getSuccessfulScheduling());
        stats.put("schedulingSuccessRate", getSchedulingSuccessRate());
        stats.put("totalSchedulingEnergy", getTotalSchedulingEnergy());
        stats.put("averageSchedulingEnergy", getAverageSchedulingEnergy());
        stats.put("totalSchedulingCost", getTotalSchedulingCost());
        stats.put("averageSchedulingCost", getAverageSchedulingCost());
        stats.put("totalSchedulingLatency", getTotalSchedulingLatency());
        stats.put("averageSchedulingLatency", getAverageSchedulingLatency());

        // Task processing statistics
        stats.put("totalTasksProcessed", getTotalTasksProcessed());
        stats.put("successfulTasks", getSuccessfulTasks());
        stats.put("failedTasks", getFailedTasks());
        stats.put("taskSuccessRate", getTaskSuccessRate());
        stats.put("totalExecutionTime", getTotalExecutionTime());
        stats.put("averageExecutionTime", getAverageExecutionTime());
        stats.put("totalExecutionEnergy", getTotalExecutionEnergy());
        stats.put("averageExecutionEnergy", getAverageExecutionEnergy());
        stats.put("totalExecutionCost", getTotalExecutionCost());
        stats.put("averageExecutionCost", getAverageExecutionCost());

        // Simulation state
        stats.put("simulationTime", getSimulationTime());
        stats.put("simulationDuration", getSimulationDuration());

        // Custom metrics
        stats.putAll(getAllCustomMetrics());

        return stats;
    }

    /**
     * Reset all statistics
     */
    public synchronized void resetStatistics() {
        totalEventsProcessed.set(0);
        rlEventsProcessed.set(0);
        errorCount.set(0);

        totalAllocationDecisions.set(0);
        successfulAllocations.set(0);
        totalAllocationEnergy = 0.0;
        totalAllocationCost = 0.0;
        totalAllocationLatency = 0.0;

        totalSchedulingDecisions.set(0);
        successfulScheduling.set(0);
        totalSchedulingEnergy = 0.0;
        totalSchedulingCost = 0.0;
        totalSchedulingLatency = 0.0;

        totalTasksProcessed.set(0);
        successfulTasks.set(0);
        failedTasks.set(0);
        totalExecutionTime = 0.0;
        totalExecutionEnergy = 0.0;
        totalExecutionCost = 0.0;

        simulationStartTime = CloudSim.clock();
        customMetrics.clear();

        logger.info("RL Statistics reset");
    }

    /**
     * Print comprehensive statistics to console
     */
    public void printStatistics() {
        Map<String, Object> stats = getComprehensiveStatistics();

        logger.info("=== RL Statistics ===");
        logger.info("Events: " + stats.get("totalEventsProcessed") + " total, " +
                stats.get("rlEventsProcessed") + " RL (" +
                String.format("%.2f", stats.get("rlEventPercentage")) + "%)");
        logger.info("Allocations: " + stats.get("totalAllocationDecisions") + " total, " +
                stats.get("successfulAllocations") + " successful (" +
                String.format("%.2f", stats.get("allocationSuccessRate")) + "%)");
        logger.info("Scheduling: " + stats.get("totalSchedulingDecisions") + " total, " +
                stats.get("successfulScheduling") + " successful (" +
                String.format("%.2f", stats.get("schedulingSuccessRate")) + "%)");
        logger.info("Tasks: " + stats.get("totalTasksProcessed") + " total, " +
                stats.get("successfulTasks") + " successful (" +
                String.format("%.2f", stats.get("taskSuccessRate")) + "%)");
        logger.info("Simulation Time: " + String.format("%.2f", stats.get("simulationTime")) +
                " (Duration: " + String.format("%.2f", stats.get("simulationDuration")) + ")");
    }
}