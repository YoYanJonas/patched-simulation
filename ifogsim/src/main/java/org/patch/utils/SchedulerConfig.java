package org.patch.utils;

import org.patch.config.EnhancedConfigurationLoader;
import org.patch.config.EnhancedConfigurationLoader.SchedulerInstance;
import java.util.logging.Logger;

/**
 * Configuration utility for scheduler host/port assignment
 * Maps fog node IDs to appropriate scheduler instances
 */
public class SchedulerConfig {
    private static final Logger logger = Logger.getLogger(SchedulerConfig.class.getName());

    // Default scheduler configuration (used as fallback)
    private static final String DEFAULT_SCHEDULER_HOST = "localhost";
    private static final int DEFAULT_SCHEDULER_PORT = 40040;

    // Scheduler instance configuration (kept for fallback compatibility)
    private static final String SCHEDULER_1_HOST = "grpc-task-scheduler-1";
    private static final int SCHEDULER_PORT = 40040;

    // Environment variable keys (kept for fallback compatibility)
    private static final String ENV_SCHEDULER_HOST_1 = "IFOGSIM_SCHEDULER_HOST_1";
    private static final String ENV_SCHEDULER_PORT_1 = "IFOGSIM_SCHEDULER_PORT_1";

    private static final String ENV_ALLOCATION_HOST = "IFOGSIM_ALLOCATION_HOST";
    private static final String ENV_ALLOCATION_PORT = "IFOGSIM_ALLOCATION_PORT";

    // Default allocation configuration
    private static final String DEFAULT_ALLOCATION_HOST = "go-grpc-server";
    private static final int DEFAULT_ALLOCATION_PORT = 50051;

    /**
     * Get scheduler host for a specific fog node ID
     * 
     * @param fogNodeId The fog node ID (1-12)
     * @return The scheduler host for this fog node
     */
    public static String getSchedulerHostForFogNode(int fogNodeId) {
        try {
            SchedulerInstance instance = EnhancedConfigurationLoader.getSchedulerForFogNode(fogNodeId);
            return instance.getHost();
        } catch (Exception e) {
            logger.warning(
                    "Failed to get scheduler host for fog node " + fogNodeId + ", using fallback: " + e.getMessage());
            return getFallbackSchedulerHost();
        }
    }

    /**
     * Get scheduler port for a specific fog node ID
     * 
     * @param fogNodeId The fog node ID (1-12)
     * @return The scheduler port for this fog node
     */
    public static int getSchedulerPortForFogNode(int fogNodeId) {
        try {
            SchedulerInstance instance = EnhancedConfigurationLoader.getSchedulerForFogNode(fogNodeId);
            return instance.getPort();
        } catch (Exception e) {
            logger.warning(
                    "Failed to get scheduler port for fog node " + fogNodeId + ", using fallback: " + e.getMessage());
            return getFallbackSchedulerPort();
        }
    }

    /**
     * Get allocation host for cloud devices
     * 
     * @return The allocation service host
     */
    public static String getAllocationHost() {
        try {
            return EnhancedConfigurationLoader.getAllocationConfig("allocation.service.host", DEFAULT_ALLOCATION_HOST);
        } catch (Exception e) {
            logger.warning("Failed to get allocation host from configuration, using fallback: " + e.getMessage());
            return getEnv(ENV_ALLOCATION_HOST, DEFAULT_ALLOCATION_HOST);
        }
    }

    /**
     * Get allocation port for cloud devices
     * 
     * @return The allocation service port
     */
    public static int getAllocationPort() {
        try {
            return EnhancedConfigurationLoader.getAllocationConfigInt("allocation.service.port",
                    DEFAULT_ALLOCATION_PORT);
        } catch (Exception e) {
            logger.warning("Failed to get allocation port from configuration, using fallback: " + e.getMessage());
            return getIntEnv(ENV_ALLOCATION_PORT, DEFAULT_ALLOCATION_PORT);
        }
    }

    /**
     * Get default scheduler host (for fallback)
     * 
     * @return Default scheduler host
     */
    public static String getDefaultSchedulerHost() {
        return getEnv(ENV_SCHEDULER_HOST_1, SCHEDULER_1_HOST);
    }

    /**
     * Get default scheduler port (for fallback)
     * 
     * @return Default scheduler port
     */
    public static int getDefaultSchedulerPort() {
        return getIntEnv(ENV_SCHEDULER_PORT_1, SCHEDULER_PORT);
    }

    /**
     * Get fallback scheduler host (for development/testing)
     * 
     * @return Fallback scheduler host
     */
    public static String getFallbackSchedulerHost() {
        return DEFAULT_SCHEDULER_HOST;
    }

    /**
     * Get fallback scheduler port (for development/testing)
     * 
     * @return Fallback scheduler port
     */
    public static int getFallbackSchedulerPort() {
        return DEFAULT_SCHEDULER_PORT;
    }

    /**
     * Get scheduler configuration for a fog node
     * 
     * @param fogNodeId The fog node ID
     * @return Array with [host, port] as String and Integer
     */
    public static Object[] getSchedulerConfigForFogNode(int fogNodeId) {
        String host = getSchedulerHostForFogNode(fogNodeId);
        int port = getSchedulerPortForFogNode(fogNodeId);

        logger.info("Fog node " + fogNodeId + " assigned to scheduler: " + host + ":" + port);

        return new Object[] { host, port };
    }

    /**
     * Get allocation configuration for cloud devices
     * 
     * @return Array with [host, port] as String and Integer
     */
    public static Object[] getAllocationConfig() {
        String host = getAllocationHost();
        int port = getAllocationPort();

        logger.info("Cloud device assigned to allocation service: " + host + ":" + port);

        return new Object[] { host, port };
    }

    // Utility methods for environment variables
    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value;
    }

    private static int getIntEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }
}
