package org.patch.application;

import org.fog.application.AppModule;
import org.fog.scheduler.TupleScheduler;
import org.fog.application.selectivity.SelectivityModel;
import org.fog.entities.Tuple;
import org.apache.commons.math3.util.Pair;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.HashMap;
import java.util.Map;

/**
 * RL-Enhanced AppModule with energy and cost characteristics
 * 
 * This class extends the original AppModule to include RL-specific
 * characteristics like energy consumption and cost per execution.
 * 
 * @author Younes Shafiee
 */
public class RLAppModule extends AppModule {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLAppModule.class.getName());

    // RL-specific characteristics
    private double energyConsumption;
    private double costPerExecution;
    private double cpuEfficiency;
    private double memoryEfficiency;

    // Performance tracking
    private long executionCount;
    private double totalEnergyConsumed;
    private double totalCostIncurred;
    private long totalExecutionTime;

    /**
     * Constructor for RLAppModule
     * 
     * @param id                Module ID
     * @param name              Module name
     * @param appId             Application ID
     * @param userId            User ID
     * @param mips              MIPS
     * @param ram               RAM
     * @param bw                Bandwidth
     * @param size              Size
     * @param vmm               VMM
     * @param energyConsumption Energy consumption per execution
     * @param costPerExecution  Cost per execution
     */
    public RLAppModule(int id, String name, String appId, int userId, int mips, int ram,
            long bw, long size, String vmm, double energyConsumption, double costPerExecution) {
        super(id, name, appId, userId, mips, ram, bw, size, vmm,
                new TupleScheduler(mips, 1), new HashMap<Pair<String, String>, SelectivityModel>());

        this.energyConsumption = energyConsumption;
        this.costPerExecution = costPerExecution;
        this.cpuEfficiency = 1.0; // Default efficiency
        this.memoryEfficiency = 1.0; // Default efficiency

        this.executionCount = 0;
        this.totalEnergyConsumed = 0.0;
        this.totalCostIncurred = 0.0;
        this.totalExecutionTime = 0;

        logger.fine("RLAppModule created: " + name + " (energy: " + energyConsumption +
                ", cost: " + costPerExecution + ")");
    }

    /**
     * Constructor from existing AppModule
     * 
     * @param module Existing AppModule
     */
    public RLAppModule(AppModule module) {
        super(module.getId(), module.getName(), module.getAppId(), module.getUserId(),
                module.getMips(), module.getRam(), module.getBw(), module.getSize(), module.getVmm(),
                new TupleScheduler(module.getMips(), 1), module.getSelectivityMap());

        // Set default RL characteristics
        this.energyConsumption = 1.0; // Default energy consumption
        this.costPerExecution = 0.1; // Default cost per execution
        this.cpuEfficiency = 1.0;
        this.memoryEfficiency = 1.0;

        this.executionCount = 0;
        this.totalEnergyConsumed = 0.0;
        this.totalCostIncurred = 0.0;
        this.totalExecutionTime = 0;

        logger.fine("RLAppModule created from AppModule: " + module.getName());
    }

    /**
     * Execute tuple with RL characteristics
     * 
     * @param tuple Input tuple
     * @return Execution result
     */
    public boolean executeTuple(Tuple tuple) {
        double startTime = CloudSim.clock();

        // Execute using parent class method
        // Note: AppModule doesn't have executeTuple method, so we'll simulate execution
        boolean result = true; // Simulate successful execution

        long executionTime = (long) (CloudSim.clock() - startTime);

        // Update RL metrics
        updateRLMetrics(executionTime);

        return result;
    }

    /**
     * Update RL metrics after execution
     * 
     * @param executionTime Execution time in milliseconds
     */
    private void updateRLMetrics(long executionTime) {
        executionCount++;
        totalExecutionTime += executionTime;

        // Calculate actual energy consumption based on execution time and efficiency
        double actualEnergy = energyConsumption * (executionTime / 1000.0) * cpuEfficiency;
        totalEnergyConsumed += actualEnergy;

        // Calculate actual cost based on execution time and efficiency
        double actualCost = costPerExecution * (executionTime / 1000.0) * memoryEfficiency;
        totalCostIncurred += actualCost;

        logger.fine("Updated RL metrics for module " + getName() +
                " (executions: " + executionCount +
                ", energy: " + actualEnergy +
                ", cost: " + actualCost + ")");
    }

    /**
     * Get energy consumption per execution
     * 
     * @return Energy consumption
     */
    public double getEnergyConsumption() {
        return energyConsumption;
    }

    /**
     * Set energy consumption per execution
     * 
     * @param energyConsumption Energy consumption
     */
    public void setEnergyConsumption(double energyConsumption) {
        this.energyConsumption = energyConsumption;
    }

    /**
     * Get cost per execution
     * 
     * @return Cost per execution
     */
    public double getCostPerExecution() {
        return costPerExecution;
    }

    /**
     * Set cost per execution
     * 
     * @param costPerExecution Cost per execution
     */
    public void setCostPerExecution(double costPerExecution) {
        this.costPerExecution = costPerExecution;
    }

    /**
     * Get CPU efficiency
     * 
     * @return CPU efficiency
     */
    public double getCpuEfficiency() {
        return cpuEfficiency;
    }

    /**
     * Set CPU efficiency
     * 
     * @param cpuEfficiency CPU efficiency
     */
    public void setCpuEfficiency(double cpuEfficiency) {
        this.cpuEfficiency = cpuEfficiency;
    }

    /**
     * Get memory efficiency
     * 
     * @return Memory efficiency
     */
    public double getMemoryEfficiency() {
        return memoryEfficiency;
    }

    /**
     * Set memory efficiency
     * 
     * @param memoryEfficiency Memory efficiency
     */
    public void setMemoryEfficiency(double memoryEfficiency) {
        this.memoryEfficiency = memoryEfficiency;
    }

    /**
     * Get execution count
     * 
     * @return Execution count
     */
    public long getExecutionCount() {
        return executionCount;
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
     * Get total cost incurred
     * 
     * @return Total cost incurred
     */
    public double getTotalCostIncurred() {
        return totalCostIncurred;
    }

    /**
     * Get total execution time
     * 
     * @return Total execution time
     */
    public long getTotalExecutionTime() {
        return totalExecutionTime;
    }

    /**
     * Get average execution time
     * 
     * @return Average execution time
     */
    public double getAverageExecutionTime() {
        return executionCount > 0 ? (double) totalExecutionTime / executionCount : 0.0;
    }

    /**
     * Get average energy consumption per execution
     * 
     * @return Average energy consumption
     */
    public double getAverageEnergyConsumption() {
        return executionCount > 0 ? totalEnergyConsumed / executionCount : 0.0;
    }

    /**
     * Get average cost per execution
     * 
     * @return Average cost per execution
     */
    public double getAverageCostPerExecution() {
        return executionCount > 0 ? totalCostIncurred / executionCount : 0.0;
    }

    /**
     * Reset RL metrics
     */
    public void resetRLMetrics() {
        executionCount = 0;
        totalEnergyConsumed = 0.0;
        totalCostIncurred = 0.0;
        totalExecutionTime = 0;

        logger.fine("Reset RL metrics for module: " + getName());
    }

    /**
     * Get RL performance summary
     * 
     * @return Performance summary
     */
    public String getRLPerformanceSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("RL Module Performance Summary for ").append(getName()).append("\n");
        summary.append("==========================================\n");
        summary.append("Execution Count: ").append(executionCount).append("\n");
        summary.append("Total Energy Consumed: ").append(totalEnergyConsumed).append("\n");
        summary.append("Total Cost Incurred: ").append(totalCostIncurred).append("\n");
        summary.append("Total Execution Time: ").append(totalExecutionTime).append(" ms\n");
        summary.append("Average Execution Time: ").append(getAverageExecutionTime()).append(" ms\n");
        summary.append("Average Energy Consumption: ").append(getAverageEnergyConsumption()).append("\n");
        summary.append("Average Cost Per Execution: ").append(getAverageCostPerExecution()).append("\n");
        summary.append("CPU Efficiency: ").append(cpuEfficiency).append("\n");
        summary.append("Memory Efficiency: ").append(memoryEfficiency).append("\n");

        return summary.toString();
    }
}
