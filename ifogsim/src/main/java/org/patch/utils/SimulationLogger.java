package org.patch.utils;

import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Utility class for managing simulation logging
 * Provides easy-to-use methods for starting simulation runs and managing logs
 */
public class SimulationLogger {
    private static final Logger logger = Logger.getLogger(SimulationLogger.class.getName());
    private static final LoggingConfig loggingConfig = LoggingConfig.getInstance();

    /**
     * Start a new simulation run with fresh log files
     * Call this at the beginning of each simulation
     */
    public static void startSimulation(String simulationName) {
        loggingConfig.startSimulationRun(simulationName);

        logger.info("=== SIMULATION STARTED ===");
        logger.info("Simulation ID: " + loggingConfig.getCurrentSimulationId());
        logger.info("Log Directory: " + loggingConfig.getCurrentLogDir());
        logger.info("Timestamp: " + java.time.LocalDateTime.now());
    }

    /**
     * End the current simulation run
     * Call this at the end of each simulation
     */
    public static void endSimulation() {
        logger.info("=== SIMULATION ENDED ===");
        logger.info("Simulation ID: " + loggingConfig.getCurrentSimulationId());
        logger.info("Timestamp: " + java.time.LocalDateTime.now());

        // List all generated log files
        Map<String, String> logFiles = loggingConfig.listLogFiles();
        if (!logFiles.isEmpty()) {
            logger.info("Generated log files:");
            for (Map.Entry<String, String> entry : logFiles.entrySet()) {
                logger.info("  - " + entry.getKey() + " -> " + entry.getValue());
            }
        }
    }

    /**
     * Get current simulation ID
     */
    public static String getCurrentSimulationId() {
        return loggingConfig.getCurrentSimulationId();
    }

    /**
     * Get current log directory
     */
    public static String getCurrentLogDir() {
        return loggingConfig.getCurrentLogDir();
    }

    /**
     * List all log files for current simulation
     */
    public static Map<String, String> getLogFiles() {
        return loggingConfig.listLogFiles();
    }

    /**
     * Log simulation milestone
     */
    public static void logMilestone(String milestone, String details) {
        logger.info("=== MILESTONE: " + milestone + " ===");
        logger.info("Details: " + details);
        logger.info("Timestamp: " + java.time.LocalDateTime.now());
    }

    /**
     * Log simulation error
     */
    public static void logError(String error, Throwable throwable) {
        logger.log(Level.SEVERE, "=== SIMULATION ERROR ===", throwable);
        logger.severe("Error: " + error);
        logger.severe("Timestamp: " + java.time.LocalDateTime.now());
    }

    /**
     * Log simulation warning
     */
    public static void logWarning(String warning) {
        logger.warning("=== SIMULATION WARNING ===");
        logger.warning("Warning: " + warning);
        logger.warning("Timestamp: " + java.time.LocalDateTime.now());
    }

    /**
     * Log performance metrics
     */
    public static void logPerformance(String operation, long durationMs, Map<String, Object> metrics) {
        logger.info("=== PERFORMANCE METRICS ===");
        logger.info("Operation: " + operation);
        logger.info("Duration: " + durationMs + "ms");
        if (metrics != null && !metrics.isEmpty()) {
            logger.info("Metrics: " + metrics.toString());
        }
        logger.info("Timestamp: " + java.time.LocalDateTime.now());
    }
}
