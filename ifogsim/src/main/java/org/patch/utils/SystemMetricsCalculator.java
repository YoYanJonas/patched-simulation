package org.patch.utils;

import org.patch.devices.RLFogDevice;
import org.patch.proto.IfogsimScheduler.SystemPerformanceMetrics;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * System Metrics Calculator for iFogSim
 * 
 * This utility class calculates SystemPerformanceMetrics from iFogSim fog node data
 * for RL learning. All metrics are calculated from simulation data using CloudSim.clock()
 * (simulation time) to ensure accuracy.
 * 
 * Key Features:
 * - Calculates all 6 SystemPerformanceMetrics fields
 * - Uses simulation time (CloudSim.clock()) for all time-based calculations
 * - Handles edge cases (zero values, missing data)
 * - Thread-safe (uses RLStatisticsManager singleton)
 * 
 * @author Younes Shafiee
 */
public class SystemMetricsCalculator {
    private static final Logger logger = Logger.getLogger(SystemMetricsCalculator.class.getName());

    /**
     * Calculate system performance metrics from iFogSim fog node data
     * 
     * @param fogDevice The fog device to calculate metrics for
     * @param allFogDevices Optional: List of all fog devices for fairness calculation (can be null)
     * @return SystemPerformanceMetrics populated with calculated values
     */
    public static SystemPerformanceMetrics calculateMetrics(
            RLFogDevice fogDevice,
            java.util.List<RLFogDevice> allFogDevices) {
        
        if (fogDevice == null) {
            logger.warning("[SYSTEM-METRICS] fogDevice is null, returning default metrics");
            return createDefaultMetrics();
        }

        try {
            return SystemPerformanceMetrics.newBuilder()
                .setTotalThroughput(calculateThroughput())
                .setAverageLatencyMs(calculateAverageLatency())
                .setEnergyEfficiency(calculateEnergyEfficiency())
                .setResourceUtilization(calculateResourceUtilization(fogDevice))
                .setDeadlineMisses(0) // Later Feature: deadline-aware disabled
                .setFairnessIndex(calculateFairnessIndex(fogDevice, allFogDevices))
                .build();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[SYSTEM-METRICS] Error calculating metrics, returning defaults", e);
            return createDefaultMetrics();
        }
    }

    /**
     * Calculate total throughput (tasks completed per second)
     * Uses simulation duration (includes all task execution time)
     */
    private static double calculateThroughput() {
        RLStatisticsManager stats = RLStatisticsManager.getInstance();
        long totalTasks = stats.getTotalTasksProcessed();
        
        // ✅ Use simulation duration (includes all task execution time)
        // NOT getSchedulingDuration() which excludes execution time
        double duration = stats.getSimulationDuration(); // Simulation time in seconds
        
        // Avoid division by zero
        if (duration <= 0) {
            return 0.0;
        }
        
        double throughput = totalTasks / duration; // tasks per second (simulation time)
        
        logger.fine(String.format("[SYSTEM-METRICS] Throughput: %d tasks / %.2f sec = %.4f tasks/sec",
                totalTasks, duration, throughput));
        
        return throughput;
    }

    /**
     * Calculate average end-to-end latency (scheduling + execution time)
     * Converts scheduling latency from real-world time to simulation time if needed
     */
    private static double calculateAverageLatency() {
        RLStatisticsManager stats = RLStatisticsManager.getInstance();
        
        // Get scheduling latency (time from submission to scheduling decision)
        // NOTE: getAverageSchedulingLatency() returns real-world milliseconds
        // We need to convert to simulation time milliseconds
        double schedulingLatencyRealMs = stats.getAverageSchedulingLatency(); // Real-world ms
        
        // Convert real-world latency to simulation time (seconds), then back to ms
        double schedulingLatencySimSec = NetworkLatencyConverter.convertToSimulationTime(
                (long) schedulingLatencyRealMs);
        double schedulingLatencySimMs = schedulingLatencySimSec * 1000.0; // Convert to ms
        
        // Get execution time (time from execution start to completion)
        // NOTE: getAverageExecutionTime() returns simulation time milliseconds
        double executionTime = stats.getAverageExecutionTime(); // Simulation time ms
        
        // End-to-end latency = scheduling + execution (both in simulation time ms)
        double averageLatency = schedulingLatencySimMs + executionTime;
        
        logger.fine(String.format(
                "[SYSTEM-METRICS] Average Latency: scheduling=%.2fms (real) -> %.2fms (sim) + execution=%.2fms (sim) = %.2fms",
                schedulingLatencyRealMs, schedulingLatencySimMs, executionTime, averageLatency));
        
        return averageLatency;
    }

    /**
     * Calculate energy efficiency (tasks per joule)
     */
    private static double calculateEnergyEfficiency() {
        RLStatisticsManager stats = RLStatisticsManager.getInstance();
        double totalEnergy = stats.getTotalExecutionEnergy(); // Joules
        long totalTasks = stats.getTotalTasksProcessed();
        
        if (totalEnergy <= 0 || totalTasks <= 0) {
            return 0.0;
        }
        
        double efficiency = totalTasks / totalEnergy; // tasks per joule
        
        logger.fine(String.format("[SYSTEM-METRICS] Energy Efficiency: %d tasks / %.2f J = %.4f tasks/J",
                totalTasks, totalEnergy, efficiency));
        
        return efficiency;
    }

    /**
     * Calculate resource utilization (average of CPU and Memory utilization)
     * Uses current device state (not per-task captured values)
     */
    private static double calculateResourceUtilization(RLFogDevice fogDevice) {
        if (fogDevice == null || fogDevice.getHost() == null) {
            logger.warning("[SYSTEM-METRICS] fogDevice or host is null, returning 0.0 for resource utilization");
            return 0.0;
        }
        
        // CPU utilization (already normalized [0.0, 1.0])
        double cpuUtilization = fogDevice.getHost().getUtilizationOfCpu();
        
        // Memory utilization (convert MB to percentage [0.0, 1.0])
        double ramUsedMb = fogDevice.getHost().getUtilizationOfRam();
        int totalRamMb = fogDevice.getHost().getRam();
        double ramUtilization = (totalRamMb > 0) ? (ramUsedMb / totalRamMb) : 0.0;
        
        // Clamp to valid range
        ramUtilization = Math.max(0.0, Math.min(1.0, ramUtilization));
        
        // Average of CPU and Memory
        double resourceUtilization = (cpuUtilization + ramUtilization) / 2.0;
        
        logger.fine(String.format(
                "[SYSTEM-METRICS] Resource Utilization: CPU=%.2f%%, Memory=%.2f%% -> Average=%.2f%%",
                cpuUtilization * 100, ramUtilization * 100, resourceUtilization * 100));
        
        return resourceUtilization;
    }

    /**
     * Calculate deadline misses count
     * Later Feature: deadline-aware calculation disabled
     */
    private static int calculateDeadlineMisses() {
        return 0; // Deadline-aware disabled
    }

    /**
     * Calculate fairness index (Jain's fairness index or default 1.0)
     * For initial implementation, uses default 1.0 (can be enhanced later)
     */
    private static double calculateFairnessIndex(RLFogDevice fogDevice, java.util.List<RLFogDevice> allFogDevices) {
        // For initial implementation, use default value
        // Can be enhanced later to calculate actual fairness from allFogDevices
        double fairnessIndex = 1.0; // Assume fair distribution
        
        logger.fine(String.format("[SYSTEM-METRICS] Fairness Index: %.2f (default)", fairnessIndex));
        
        return fairnessIndex;
    }

    /**
     * Create default metrics (all zeros/ones) for error cases
     */
    private static SystemPerformanceMetrics createDefaultMetrics() {
        return SystemPerformanceMetrics.newBuilder()
            .setTotalThroughput(0.0)
            .setAverageLatencyMs(0.0)
            .setEnergyEfficiency(0.0)
            .setResourceUtilization(0.0)
            .setDeadlineMisses(0)
            .setFairnessIndex(1.0)
            .build();
    }
}

