package org.patch.examples;

import org.patch.utils.SimulationLogger;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Example demonstrating how to use the new logging system for simulation runs
 */
public class LoggingExample {
    private static final Logger logger = Logger.getLogger(LoggingExample.class.getName());

    public static void main(String[] args) {
        // Example simulation run
        runSimulation("FogComputing_Test_1");
    }

    /**
     * Example simulation run with proper logging
     */
    public static void runSimulation(String simulationName) {
        try {
            // 1. Start simulation with fresh log files
            SimulationLogger.startSimulation(simulationName);

            // 2. Log simulation milestones
            SimulationLogger.logMilestone("INITIALIZATION", "Starting fog computing simulation");

            // 3. Simulate some work with logging
            simulateFogNodes();
            simulateTaskScheduling();
            simulateLoadBalancing();

            // 4. Log performance metrics
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("fog_nodes", 4);
            metrics.put("tasks_processed", 100);
            metrics.put("cache_hits", 25);
            metrics.put("success_rate", 0.95);

            SimulationLogger.logPerformance("SIMULATION_COMPLETE", 5000, metrics);

            // 5. End simulation
            SimulationLogger.endSimulation();

        } catch (Exception e) {
            SimulationLogger.logError("Simulation failed", e);
        }
    }

    /**
     * Simulate fog node operations
     */
    private static void simulateFogNodes() {
        logger.info("Initializing fog nodes...");

        // Simulate fog node setup
        for (int i = 1; i <= 4; i++) {
            logger.info("Setting up fog node " + i);
            // Simulate some work
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        SimulationLogger.logMilestone("FOG_NODES_READY", "4 fog nodes initialized successfully");
    }

    /**
     * Simulate task scheduling operations
     */
    private static void simulateTaskScheduling() {
        logger.info("Starting task scheduling simulation...");

        // Simulate task scheduling
        for (int i = 1; i <= 20; i++) {
            logger.info("Processing task " + i);
            // Simulate some work
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        SimulationLogger.logMilestone("TASK_SCHEDULING_COMPLETE", "20 tasks scheduled successfully");
    }

    /**
     * Simulate load balancing operations
     */
    private static void simulateLoadBalancing() {
        logger.info("Starting load balancing simulation...");

        // Simulate load balancing
        for (int i = 1; i <= 10; i++) {
            logger.info("Balancing load for round " + i);
            // Simulate some work
            try {
                Thread.sleep(75);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        SimulationLogger.logMilestone("LOAD_BALANCING_COMPLETE", "Load balancing completed successfully");
    }
}
