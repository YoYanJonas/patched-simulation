package org.patch.utils;

import org.patch.client.GrpcClient;
import java.util.logging.Logger;
import java.util.logging.Level;
import io.github.cdimascio.dotenv.Dotenv;

/**
 * Configuration management for Reinforcement Learning integration
 * Handles RL feature flags, server addresses, and timing configurations
 */
public class RLConfig {
    private static final Logger logger = Logger.getLogger(RLConfig.class.getName());

    // Environment configuration
    private static final Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

    // Feature flags
    public static final String ENABLE_FOG_RL = "enableFogRL";
    public static final String ENABLE_CLOUD_RL = "enableCloudRL";
    public static final String ENABLE_EXTERNAL_TASKS = "enableExternalTasks";
    public static final String ENABLE_PLACEMENT_RL = "enablePlacementRL"; // Added for placement RL
    public static final String ENABLE_BROKER_RL = "enableBrokerRL"; // Added for broker RL
    public static final String ENABLE_CONTROLLER_RL = "enableControllerRL"; // Added for controller RL

    // gRPC server addresses
    public static final String CLOUD_RL_SERVER_HOST = "cloudRlServerHost";
    public static final String CLOUD_RL_SERVER_PORT = "cloudRlServerPort";
    public static final String CLOUD_RL_SERVER_HOST_PREFIX = "cloudRlServerHost.";
    public static final String CLOUD_RL_SERVER_PORT_PREFIX = "cloudRlServerPort.";
    public static final String FOG_RL_SERVER_HOST_PREFIX = "fogRlServerHost.";
    public static final String FOG_RL_SERVER_PORT_PREFIX = "fogRlServerPort.";
    public static final String EXTERNAL_TASK_SERVER_HOST = "externalTaskServerHost";
    public static final String EXTERNAL_TASK_SERVER_PORT = "externalTaskServerPort";
    public static final String PLACEMENT_RL_SERVER_HOST = "placementRlServerHost"; // Added for placement RL
    public static final String PLACEMENT_RL_SERVER_PORT = "placementRlServerPort"; // Added for placement RL

    // Timing configurations
    public static final String STATE_REPORT_INTERVAL = "stateReportInterval";
    public static final String CLOUD_STATE_REPORT_INTERVAL = "cloudStateReportInterval";
    public static final String PLACEMENT_UPDATE_INTERVAL = "placementUpdateInterval";
    public static final String EXTERNAL_TASK_CHECK_INTERVAL = "externalTaskCheckInterval";
    public static final String BROKER_STATE_REPORT_INTERVAL = "brokerStateReportInterval";
    public static final String CONTROLLER_STATE_REPORT_INTERVAL = "controllerStateReportInterval";
    public static final String METRICS_COLLECTION_INTERVAL = "metricsCollectionInterval";
    public static final String ENERGY_TRACKING_INTERVAL = "energyTrackingInterval";
    public static final String RESOURCE_MANAGEMENT_INTERVAL = "resourceManagementInterval";

    // Default values
    public static final double DEFAULT_STATE_REPORT_INTERVAL = 1.0; // 1 second
    public static final double DEFAULT_CLOUD_STATE_REPORT_INTERVAL = 5.0; // 5 seconds
    public static final double DEFAULT_PLACEMENT_UPDATE_INTERVAL = 10.0; // 10 seconds
    public static final double DEFAULT_EXTERNAL_TASK_CHECK_INTERVAL = 0.5; // 0.5 seconds
    public static final double DEFAULT_BROKER_STATE_REPORT_INTERVAL = 2.0; // 2 seconds
    public static final double DEFAULT_CONTROLLER_STATE_REPORT_INTERVAL = 3.0; // 3 seconds
    public static final double DEFAULT_METRICS_COLLECTION_INTERVAL = 5.0; // 5 seconds
    public static final double DEFAULT_ENERGY_TRACKING_INTERVAL = 1.0; // 1 second
    public static final double DEFAULT_RESOURCE_MANAGEMENT_INTERVAL = 10.0; // 10 seconds
    public static final int DEFAULT_GRPC_PORT = 50051;

    /**
     * Initialize configuration with environment variables and defaults
     */
    public static void initialize() {
        // Set defaults
        ServiceRegistry.setConfig(ENABLE_FOG_RL, getBooleanEnv("ENABLE_FOG_RL", false));
        ServiceRegistry.setConfig(ENABLE_CLOUD_RL, getBooleanEnv("ENABLE_CLOUD_RL", false));
        ServiceRegistry.setConfig(ENABLE_EXTERNAL_TASKS, getBooleanEnv("ENABLE_EXTERNAL_TASKS", false));
        ServiceRegistry.setConfig(ENABLE_PLACEMENT_RL, getBooleanEnv("ENABLE_PLACEMENT_RL", false)); // Added for
                                                                                                     // placement RL

        // Timing configurations
        ServiceRegistry.setConfig(STATE_REPORT_INTERVAL,
                getDoubleEnv("STATE_REPORT_INTERVAL", DEFAULT_STATE_REPORT_INTERVAL));
        ServiceRegistry.setConfig(CLOUD_STATE_REPORT_INTERVAL,
                getDoubleEnv("CLOUD_STATE_REPORT_INTERVAL", DEFAULT_CLOUD_STATE_REPORT_INTERVAL));
        ServiceRegistry.setConfig(PLACEMENT_UPDATE_INTERVAL,
                getDoubleEnv("PLACEMENT_UPDATE_INTERVAL", DEFAULT_PLACEMENT_UPDATE_INTERVAL));
        ServiceRegistry.setConfig(EXTERNAL_TASK_CHECK_INTERVAL,
                getDoubleEnv("EXTERNAL_TASK_CHECK_INTERVAL", DEFAULT_EXTERNAL_TASK_CHECK_INTERVAL));

        // Set server addresses from environment if available
        if (getBooleanEnv("ENABLE_CLOUD_RL", false)) {
            String host = getEnv("CLOUD_RL_SERVER_HOST", "localhost");
            int port = getIntEnv("CLOUD_RL_SERVER_PORT", DEFAULT_GRPC_PORT);
            enableCloudRL(host, port);
        }

        if (getBooleanEnv("ENABLE_EXTERNAL_TASKS", false)) {
            String host = getEnv("EXTERNAL_TASK_SERVER_HOST", "localhost");
            int port = getIntEnv("EXTERNAL_TASK_SERVER_PORT", DEFAULT_GRPC_PORT);
            enableExternalTasks(host, port);
        }

        // Initialize placement RL if enabled
        if (getBooleanEnv("ENABLE_PLACEMENT_RL", false)) {
            String host = getEnv("PLACEMENT_RL_SERVER_HOST", "localhost");
            int port = getIntEnv("PLACEMENT_RL_SERVER_PORT", DEFAULT_GRPC_PORT);
            enablePlacementRL(host, port);
        }

        logger.info("RL configuration initialized");
    }

    /**
     * Enable fog-level RL
     * 
     * @param host gRPC server host for fog RL agent
     * @param port gRPC server port for fog RL agent
     */
    public static void enableFogRL(String host, int port) {
        ServiceRegistry.setConfig(ENABLE_FOG_RL, true);
        ServiceRegistry.setConfig(FOG_RL_SERVER_HOST_PREFIX + "default", host);
        ServiceRegistry.setConfig(FOG_RL_SERVER_PORT_PREFIX + "default", port);

        logger.info("Fog-level RL enabled with default server at " + host + ":" + port);
    }

    /**
     * Enable cloud-level RL
     * 
     * @param host gRPC server host for cloud RL agent
     * @param port gRPC server port for cloud RL agent
     */
    public static void enableCloudRL(String host, int port) {
        try {
            if (host == null || host.trim().isEmpty()) {
                logger.warning("Cannot enable cloud RL with null or empty host");
                return;
            }

            if (port <= 0 || port > 65535) {
                logger.warning("Cannot enable cloud RL with invalid port: " + port);
                return;
            }

            ServiceRegistry.setConfig(ENABLE_CLOUD_RL, true);
            ServiceRegistry.setConfig(CLOUD_RL_SERVER_HOST, host);
            ServiceRegistry.setConfig(CLOUD_RL_SERVER_PORT, port);

            // Create gRPC client for cloud RL
            GrpcClient client = ServiceRegistry.getOrCreateGrpcClient("cloudRL", host, port);
            logger.info("Cloud-level RL enabled with server at " + host + ":" + port);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to enable cloud RL", e);
        }
    }

    /**
     * Enable placement-level RL
     * 
     * @param host gRPC server host for placement RL agent
     * @param port gRPC server port for placement RL agent
     */
    public static void enablePlacementRL(String host, int port) {
        ServiceRegistry.setConfig(ENABLE_PLACEMENT_RL, true);
        ServiceRegistry.setConfig(PLACEMENT_RL_SERVER_HOST, host);
        ServiceRegistry.setConfig(PLACEMENT_RL_SERVER_PORT, port);

        // Create gRPC client for placement RL
        try {
            GrpcClient client = ServiceRegistry.getOrCreateGrpcClient("placementRL", host, port);
            logger.info("Placement-level RL enabled with server at " + host + ":" + port);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to connect to placement RL server", e);
        }
    }

    /**
     * Enable external task generation
     * 
     * @param host gRPC server host for external task generator
     * @param port gRPC server port for external task generator
     */
    public static void enableExternalTasks(String host, int port) {
        ServiceRegistry.setConfig(ENABLE_EXTERNAL_TASKS, true);
        ServiceRegistry.setConfig(EXTERNAL_TASK_SERVER_HOST, host);
        ServiceRegistry.setConfig(EXTERNAL_TASK_SERVER_PORT, port);

        // Create gRPC client for external tasks
        try {
            GrpcClient client = ServiceRegistry.getOrCreateGrpcClient("externalTasks", host, port);
            logger.info("External task generation enabled with server at " + host + ":" + port);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to connect to external task server", e);
        }
    }

    /**
     * Configure fog-specific RL server
     * 
     * @param fogDeviceId ID of fog device
     * @param host        gRPC server host for this fog's RL agent
     * @param port        gRPC server port for this fog's RL agent
     */
    public static void configureFogRLServer(int fogDeviceId, String host, int port) {
        ServiceRegistry.setConfig(FOG_RL_SERVER_HOST_PREFIX + fogDeviceId, host);
        ServiceRegistry.setConfig(FOG_RL_SERVER_PORT_PREFIX + fogDeviceId, port);

        // Create gRPC client for this fog device
        try {
            GrpcClient client = ServiceRegistry.getOrCreateGrpcClient("fogRL." + fogDeviceId, host, port);
            logger.info("Configured RL server for fog device " + fogDeviceId + " at " + host + ":" + port);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to connect to fog RL server for device " + fogDeviceId, e);
        }
    }

    /**
     * Configure cloud-specific RL server
     * 
     * @param cloudDeviceId ID of cloud device
     * @param host          gRPC server host for this cloud's RL agent
     * @param port          gRPC server port for this cloud's RL agent
     */
    public static void configureCloudRLServer(int cloudDeviceId, String host, int port) {
        ServiceRegistry.setConfig(CLOUD_RL_SERVER_HOST_PREFIX + cloudDeviceId, host);
        ServiceRegistry.setConfig(CLOUD_RL_SERVER_PORT_PREFIX + cloudDeviceId, port);

        // Create gRPC client for this cloud device
        try {
            GrpcClient client = ServiceRegistry.getOrCreateGrpcClient("cloudRL." + cloudDeviceId, host, port);
            logger.info("Configured RL server for cloud device " + cloudDeviceId + " at " + host + ":" + port);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to connect to cloud RL server for device " + cloudDeviceId, e);
        }
    }

    /**
     * Check if cloud-level RL is enabled
     */
    public static boolean isCloudRLEnabled() {
        return ServiceRegistry.getConfig(ENABLE_CLOUD_RL, false);
    }

    /**
     * Check if fog-level RL is enabled
     */
    public static boolean isFogRLEnabled() {
        return ServiceRegistry.getConfig(ENABLE_FOG_RL, false);
    }

    /**
     * Check if placement-level RL is enabled
     */
    public static boolean isPlacementRLEnabled() {
        return ServiceRegistry.getConfig(ENABLE_PLACEMENT_RL, false);
    }

    /**
     * Check if external tasks are enabled
     */
    public static boolean areExternalTasksEnabled() {
        return ServiceRegistry.getConfig(ENABLE_EXTERNAL_TASKS, false);
    }

    /**
     * Check if placement RL server is configured
     */
    public static boolean isPlacementRLServerConfigured() {
        return ServiceRegistry.getConfig(PLACEMENT_RL_SERVER_HOST, null) != null &&
                ServiceRegistry.getConfig(PLACEMENT_RL_SERVER_PORT, null) != null;
    }

    /**
     * Get placement RL server host
     */
    public static String getPlacementRLServerHost() {
        return ServiceRegistry.getConfig(PLACEMENT_RL_SERVER_HOST, "localhost");
    }

    /**
     * Get placement RL server port
     */
    public static int getPlacementRLServerPort() {
        return ServiceRegistry.getConfig(PLACEMENT_RL_SERVER_PORT, DEFAULT_GRPC_PORT);
    }

    /**
     * Get state reporting interval for fog devices
     */
    public static double getStateReportInterval() {
        return ServiceRegistry.getConfig(STATE_REPORT_INTERVAL, DEFAULT_STATE_REPORT_INTERVAL);
    }

    /**
     * Get state reporting interval for cloud devices
     */
    public static double getCloudStateReportInterval() {
        return ServiceRegistry.getConfig(CLOUD_STATE_REPORT_INTERVAL, DEFAULT_CLOUD_STATE_REPORT_INTERVAL);
    }

    /**
     * Get placement update interval for cloud devices
     */
    public static double getPlacementUpdateInterval() {
        return ServiceRegistry.getConfig(PLACEMENT_UPDATE_INTERVAL, DEFAULT_PLACEMENT_UPDATE_INTERVAL);
    }

    /**
     * Get external task check interval
     */
    public static double getExternalTaskCheckInterval() {
        return ServiceRegistry.getConfig(EXTERNAL_TASK_CHECK_INTERVAL, DEFAULT_EXTERNAL_TASK_CHECK_INTERVAL);
    }

    /**
     * Check if broker-level RL is enabled
     */
    public static boolean isBrokerRLEnabled() {
        return ServiceRegistry.getConfig(ENABLE_BROKER_RL, false);
    }

    /**
     * Check if controller-level RL is enabled
     */
    public static boolean isControllerRLEnabled() {
        return ServiceRegistry.getConfig(ENABLE_CONTROLLER_RL, false);
    }

    /**
     * Get broker state report interval
     */
    public static double getBrokerStateReportInterval() {
        return ServiceRegistry.getConfig(BROKER_STATE_REPORT_INTERVAL, DEFAULT_BROKER_STATE_REPORT_INTERVAL);
    }

    /**
     * Get controller state report interval
     */
    public static double getControllerStateReportInterval() {
        return ServiceRegistry.getConfig(CONTROLLER_STATE_REPORT_INTERVAL, DEFAULT_CONTROLLER_STATE_REPORT_INTERVAL);
    }

    /**
     * Get metrics collection interval
     */
    public static double getMetricsCollectionInterval() {
        return ServiceRegistry.getConfig(METRICS_COLLECTION_INTERVAL, DEFAULT_METRICS_COLLECTION_INTERVAL);
    }

    /**
     * Get energy tracking interval
     */
    public static double getEnergyTrackingInterval() {
        return ServiceRegistry.getConfig(ENERGY_TRACKING_INTERVAL, DEFAULT_ENERGY_TRACKING_INTERVAL);
    }

    /**
     * Get resource management interval
     */
    public static double getResourceManagementInterval() {
        return ServiceRegistry.getConfig(RESOURCE_MANAGEMENT_INTERVAL, DEFAULT_RESOURCE_MANAGEMENT_INTERVAL);
    }

    /**
     * Configure broker RL server
     * 
     * @param brokerId Broker ID
     * @param host     gRPC server host
     * @param port     gRPC server port
     */
    public static void configureBrokerRLServer(int brokerId, String host, int port) {
        ServiceRegistry.setConfig(CLOUD_RL_SERVER_HOST_PREFIX + brokerId, host);
        ServiceRegistry.setConfig(CLOUD_RL_SERVER_PORT_PREFIX + brokerId, port);

        // Create gRPC client for this broker
        try {
            GrpcClient client = ServiceRegistry.getOrCreateGrpcClient("brokerRL." + brokerId, host, port);
            logger.info("Configured RL server for broker " + brokerId + " at " + host + ":" + port);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to connect to broker RL server for broker " + brokerId, e);
        }
    }

    /**
     * Configure controller RL server
     * 
     * @param controllerId Controller ID
     * @param host         gRPC server host
     * @param port         gRPC server port
     */
    public static void configureControllerRLServer(int controllerId, String host, int port) {
        ServiceRegistry.setConfig(PLACEMENT_RL_SERVER_HOST + "." + controllerId, host);
        ServiceRegistry.setConfig(PLACEMENT_RL_SERVER_PORT + "." + controllerId, port);

        // Create gRPC client for this controller
        try {
            GrpcClient client = ServiceRegistry.getOrCreateGrpcClient("controllerRL." + controllerId, host, port);
            logger.info("Configured RL server for controller " + controllerId + " at " + host + ":" + port);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to connect to controller RL server for controller " + controllerId, e);
        }
    }

    // Utility methods for environment variables
    private static String getEnv(String key, String defaultValue) {
        String value = dotenv.get(key);
        return value != null ? value : defaultValue;
    }

    private static boolean getBooleanEnv(String key, boolean defaultValue) {
        String value = dotenv.get(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    private static int getIntEnv(String key, int defaultValue) {
        String value = dotenv.get(key);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    private static double getDoubleEnv(String key, double defaultValue) {
        String value = dotenv.get(key);
        return value != null ? Double.parseDouble(value) : defaultValue;
    }
}