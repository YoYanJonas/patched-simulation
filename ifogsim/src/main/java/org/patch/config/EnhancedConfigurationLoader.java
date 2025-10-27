package org.patch.config;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Enhanced configuration loader with YAML support and fog node mapping
 * Follows best practices for configuration management
 */
public class EnhancedConfigurationLoader {
    private static final Logger logger = Logger.getLogger(EnhancedConfigurationLoader.class.getName());

    // Fog node to scheduler mapping
    private static Map<Integer, String> fogNodeToSchedulerMap = new HashMap<>();
    private static Map<String, SchedulerInstance> schedulerInstances = new HashMap<>();

    private static boolean initialized = false;

    /**
     * Scheduler instance configuration
     */
    public static class SchedulerInstance {
        private final String name;
        private final String host;
        private final int port;
        private final int maxFogNodes;

        public SchedulerInstance(String name, String host, int port, int maxFogNodes) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.maxFogNodes = maxFogNodes;
        }

        public String getName() {
            return name;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public int getMaxFogNodes() {
            return maxFogNodes;
        }

        @Override
        public String toString() {
            return String.format("SchedulerInstance{name='%s', host='%s', port=%d, maxFogNodes=%d}",
                    name, host, port, maxFogNodes);
        }
    }

    /**
     * Initialize configuration by loading all properties files and setting up fog
     * node mapping
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        try {
            // Initialize fog node to scheduler mapping
            initializeFogNodeMapping();

            initialized = true;
            logger.info("Enhanced configuration loaded successfully");
            logger.info("Fog node mapping initialized: " + fogNodeToSchedulerMap.size() + " mappings");
            logger.info("Scheduler instances: " + schedulerInstances.size());

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load enhanced configuration", e);
            throw new RuntimeException("Enhanced configuration initialization failed", e);
        }
    }

    /**
     * Initialize fog node to scheduler mapping
     */
    private static void initializeFogNodeMapping() {
        // Initialize scheduler instances
        schedulerInstances.put("scheduler-1", new SchedulerInstance(
                "grpc-task-scheduler-1",
                getEnvString("SCHEDULER_1_HOST", "localhost"),
                getEnvInt("SCHEDULER_1_PORT", 50051),
                4));

        schedulerInstances.put("scheduler-2", new SchedulerInstance(
                "grpc-task-scheduler-2",
                getEnvString("SCHEDULER_2_HOST", "localhost"),
                getEnvInt("SCHEDULER_2_PORT", 50052),
                4));

        schedulerInstances.put("scheduler-3", new SchedulerInstance(
                "grpc-task-scheduler-3",
                getEnvString("SCHEDULER_3_HOST", "localhost"),
                getEnvInt("SCHEDULER_3_PORT", 50053),
                4));

        // Map fog nodes to schedulers (1-4 -> scheduler-1, 5-8 -> scheduler-2, 9-12 ->
        // scheduler-3)
        for (int fogNodeId = 1; fogNodeId <= 4; fogNodeId++) {
            fogNodeToSchedulerMap.put(fogNodeId, "scheduler-1");
        }
        for (int fogNodeId = 5; fogNodeId <= 8; fogNodeId++) {
            fogNodeToSchedulerMap.put(fogNodeId, "scheduler-2");
        }
        for (int fogNodeId = 9; fogNodeId <= 12; fogNodeId++) {
            fogNodeToSchedulerMap.put(fogNodeId, "scheduler-3");
        }
    }

    /**
     * Get scheduler configuration for a specific fog node
     */
    public static SchedulerInstance getSchedulerForFogNode(int fogNodeId) {
        ensureInitialized();

        String schedulerName = fogNodeToSchedulerMap.get(fogNodeId);
        if (schedulerName == null) {
            logger.warning("No scheduler mapping found for fog node " + fogNodeId + ", using default");
            schedulerName = "scheduler-1"; // Default fallback
        }

        SchedulerInstance instance = schedulerInstances.get(schedulerName);
        if (instance == null) {
            logger.severe("Scheduler instance not found: " + schedulerName);
            throw new RuntimeException("Scheduler instance not found: " + schedulerName);
        }

        logger.fine("Fog node " + fogNodeId + " mapped to " + instance);
        return instance;
    }

    /**
     * Get all scheduler instances
     */
    public static Map<String, SchedulerInstance> getAllSchedulerInstances() {
        ensureInitialized();
        return new HashMap<>(schedulerInstances);
    }

    /**
     * Get fog node to scheduler mapping
     */
    public static Map<Integer, String> getFogNodeMapping() {
        ensureInitialized();
        return new HashMap<>(fogNodeToSchedulerMap);
    }

    // Environment variable helpers
    private static String getEnvString(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }

    private static int getEnvInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                logger.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            }
        }
        return defaultValue;
    }

    // Configuration methods using environment variables and defaults
    public static String getGrpcConfig(String key, String defaultValue) {
        ensureInitialized();

        // Handle common gRPC configuration keys
        switch (key) {
            case "grpc.connection.timeout":
                return getEnvString("GRPC_CONNECTION_TIMEOUT", defaultValue);
            case "grpc.retry.max.attempts":
                return getEnvString("GRPC_RETRY_MAX_ATTEMPTS", defaultValue);
            case "grpc.retry.delay":
                return getEnvString("GRPC_RETRY_DELAY", defaultValue);
            case "grpc.circuit.breaker.failure.threshold":
                return getEnvString("GRPC_CIRCUIT_BREAKER_FAILURE_THRESHOLD", defaultValue);
            case "grpc.circuit.breaker.open.duration":
                return getEnvString("GRPC_CIRCUIT_BREAKER_OPEN_DURATION", defaultValue);
            case "grpc.fallback.node.id":
                return getEnvString("GRPC_FALLBACK_NODE_ID", defaultValue);
            case "grpc.fallback.execution.time":
                return getEnvString("GRPC_FALLBACK_EXECUTION_TIME", defaultValue);
            case "grpc.fallback.scheduling.delay":
                return getEnvString("GRPC_FALLBACK_SCHEDULING_DELAY", defaultValue);
            default:
                return defaultValue;
        }
    }

    public static int getGrpcConfigInt(String key, int defaultValue) {
        String value = getGrpcConfig(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static long getGrpcConfigLong(String key, long defaultValue) {
        String value = getGrpcConfig(key, String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid long value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static boolean getGrpcConfigBoolean(String key, boolean defaultValue) {
        String value = getGrpcConfig(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    public static String getSchedulerConfig(String key, String defaultValue) {
        ensureInitialized();

        // Handle common scheduler configuration keys
        switch (key) {
            case "scheduler.service.host":
                return getEnvString("SCHEDULER_SERVICE_HOST", defaultValue);
            case "scheduler.service.port":
                return getEnvString("SCHEDULER_SERVICE_PORT", defaultValue);
            case "scheduler.health.check.enabled":
                return getEnvString("SCHEDULER_HEALTH_CHECK_ENABLED", defaultValue);
            case "scheduler.fallback.enabled":
                return getEnvString("SCHEDULER_FALLBACK_ENABLED", defaultValue);
            case "scheduler.fallback.execution.time.default":
                return getEnvString("SCHEDULER_FALLBACK_EXECUTION_TIME", defaultValue);
            case "scheduler.fallback.scheduling.delay":
                return getEnvString("SCHEDULER_FALLBACK_SCHEDULING_DELAY", defaultValue);
            default:
                return defaultValue;
        }
    }

    public static int getSchedulerConfigInt(String key, int defaultValue) {
        String value = getSchedulerConfig(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static long getSchedulerConfigLong(String key, long defaultValue) {
        String value = getSchedulerConfig(key, String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid long value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static boolean getSchedulerConfigBoolean(String key, boolean defaultValue) {
        String value = getSchedulerConfig(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    public static String getAllocationConfig(String key, String defaultValue) {
        ensureInitialized();

        // Handle common allocation configuration keys
        switch (key) {
            case "allocation.service.host":
                return getEnvString("ALLOCATION_SERVICE_HOST", defaultValue);
            case "allocation.service.port":
                return getEnvString("ALLOCATION_SERVICE_PORT", defaultValue);
            case "allocation.health.check.enabled":
                return getEnvString("ALLOCATION_HEALTH_CHECK_ENABLED", defaultValue);
            case "allocation.fallback.enabled":
                return getEnvString("ALLOCATION_FALLBACK_ENABLED", defaultValue);
            case "allocation.fallback.node.id":
                return getEnvString("ALLOCATION_FALLBACK_NODE_ID", defaultValue);
            case "allocation.fallback.execution.time":
                return getEnvString("ALLOCATION_FALLBACK_EXECUTION_TIME", defaultValue);
            default:
                return defaultValue;
        }
    }

    public static int getAllocationConfigInt(String key, int defaultValue) {
        String value = getAllocationConfig(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static long getAllocationConfigLong(String key, long defaultValue) {
        String value = getAllocationConfig(key, String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid long value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static boolean getAllocationConfigBoolean(String key, boolean defaultValue) {
        String value = getAllocationConfig(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    public static double getAllocationConfigDouble(String key, double defaultValue) {
        String value = getAllocationConfig(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid double value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Ensure configuration is initialized
     */
    private static void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    // ===== EXTERNAL TASK CONFIGURATION METHODS =====

    /**
     * Get external task configuration value
     */
    public static String getExternalTaskConfig(String key, String defaultValue) {
        switch (key) {
            case "external.tasks.generation.enabled":
                return getEnvString("EXTERNAL_TASKS_GENERATION_ENABLED", defaultValue);
            case "external.tasks.generation.default.rate":
                return getEnvString("EXTERNAL_TASKS_DEFAULT_RATE", defaultValue);
            case "external.tasks.generation.initial.delay":
                return getEnvString("EXTERNAL_TASKS_INITIAL_DELAY", defaultValue);
            case "external.tasks.parameters.app.id":
                return getEnvString("EXTERNAL_TASKS_APP_ID", defaultValue);
            case "external.tasks.parameters.user.id":
                return getEnvString("EXTERNAL_TASKS_USER_ID", defaultValue);
            case "external.tasks.parameters.number.of.pes":
                return getEnvString("EXTERNAL_TASKS_NUMBER_OF_PES", defaultValue);
            case "external.tasks.parameters.cpu.min":
                return getEnvString("EXTERNAL_TASKS_CPU_MIN", defaultValue);
            case "external.tasks.parameters.cpu.max":
                return getEnvString("EXTERNAL_TASKS_CPU_MAX", defaultValue);
            case "external.tasks.parameters.memory.min":
                return getEnvString("EXTERNAL_TASKS_MEMORY_MIN", defaultValue);
            case "external.tasks.parameters.memory.max":
                return getEnvString("EXTERNAL_TASKS_MEMORY_MAX", defaultValue);
            case "external.tasks.parameters.output.min":
                return getEnvString("EXTERNAL_TASKS_OUTPUT_MIN", defaultValue);
            case "external.tasks.parameters.output.max":
                return getEnvString("EXTERNAL_TASKS_OUTPUT_MAX", defaultValue);
            case "external.tasks.properties.tuple.type":
                return getEnvString("EXTERNAL_TASKS_TUPLE_TYPE", defaultValue);
            case "external.tasks.properties.module.name":
                return getEnvString("EXTERNAL_TASKS_MODULE_NAME", defaultValue);
            case "external.tasks.properties.direction":
                return getEnvString("EXTERNAL_TASKS_DIRECTION", defaultValue);
            default:
                return defaultValue;
        }
    }

    public static int getExternalTaskConfigInt(String key, int defaultValue) {
        String value = getExternalTaskConfig(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static long getExternalTaskConfigLong(String key, long defaultValue) {
        String value = getExternalTaskConfig(key, String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid long value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static double getExternalTaskConfigDouble(String key, double defaultValue) {
        String value = getExternalTaskConfig(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid double value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static boolean getExternalTaskConfigBoolean(String key, boolean defaultValue) {
        String value = getExternalTaskConfig(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    // ===== RL CONFIGURATION METHODS =====

    /**
     * Get RL configuration value
     */
    public static String getRLConfig(String key, String defaultValue) {
        switch (key) {
            case "rl.servers.cloud.host":
                return getEnvString("CLOUD_RL_SERVER_HOST", defaultValue);
            case "rl.servers.cloud.port":
                return getEnvString("CLOUD_RL_SERVER_PORT", defaultValue);
            case "rl.servers.external-task.host":
                return getEnvString("EXTERNAL_TASK_SERVER_HOST", defaultValue);
            case "rl.servers.external-task.port":
                return getEnvString("EXTERNAL_TASK_SERVER_PORT", defaultValue);
            case "rl.servers.placement.host":
                return getEnvString("PLACEMENT_RL_SERVER_HOST", defaultValue);
            case "rl.servers.placement.port":
                return getEnvString("PLACEMENT_RL_SERVER_PORT", defaultValue);
            case "rl.algorithm.learning-rate":
                return getEnvString("RL_LEARNING_RATE", defaultValue);
            case "rl.algorithm.exploration-rate":
                return getEnvString("RL_EXPLORATION_RATE", defaultValue);
            case "rl.training.update-interval":
                return getEnvString("RL_UPDATE_INTERVAL", defaultValue);
            default:
                return defaultValue;
        }
    }

    public static int getRLConfigInt(String key, int defaultValue) {
        String value = getRLConfig(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static double getRLConfigDouble(String key, double defaultValue) {
        String value = getRLConfig(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid double value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static boolean getRLConfigBoolean(String key, boolean defaultValue) {
        String value = getRLConfig(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    // ===== DEVICE CONFIGURATION METHODS =====

    /**
     * Get device configuration value
     */
    public static String getDeviceConfig(String key, String defaultValue) {
        switch (key) {
            case "devices.fog.default-mips":
                return getEnvString("FOG_DEFAULT_MIPS", defaultValue);
            case "devices.fog.default-ram":
                return getEnvString("FOG_DEFAULT_RAM", defaultValue);
            case "devices.fog.default-uplink-bw":
                return getEnvString("FOG_DEFAULT_UPLINK_BW", defaultValue);
            case "devices.fog.default-downlink-bw":
                return getEnvString("FOG_DEFAULT_DOWNLINK_BW", defaultValue);
            case "devices.cloud.default-mips":
                return getEnvString("CLOUD_DEFAULT_MIPS", defaultValue);
            case "devices.cloud.default-ram":
                return getEnvString("CLOUD_DEFAULT_RAM", defaultValue);
            case "devices.cloud.default-uplink-bw":
                return getEnvString("CLOUD_DEFAULT_UPLINK_BW", defaultValue);
            case "devices.cloud.default-downlink-bw":
                return getEnvString("CLOUD_DEFAULT_DOWNLINK_BW", defaultValue);
            default:
                return defaultValue;
        }
    }

    public static int getDeviceConfigInt(String key, int defaultValue) {
        String value = getDeviceConfig(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static double getDeviceConfigDouble(String key, double defaultValue) {
        String value = getDeviceConfig(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid double value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    // ===== PLACEMENT CONFIGURATION METHODS =====

    /**
     * Get placement configuration value
     */
    public static String getPlacementConfig(String key, String defaultValue) {
        switch (key) {
            case "placement.rl.enabled":
                return getEnvString("PLACEMENT_RL_ENABLED", defaultValue);
            case "placement.rl.update-interval":
                return getEnvString("PLACEMENT_RL_UPDATE_INTERVAL", defaultValue);
            case "placement.scoring.cpu-weight":
                return getEnvString("PLACEMENT_CPU_WEIGHT", defaultValue);
            case "placement.scoring.ram-weight":
                return getEnvString("PLACEMENT_RAM_WEIGHT", defaultValue);
            case "placement.scoring.bandwidth-weight":
                return getEnvString("PLACEMENT_BANDWIDTH_WEIGHT", defaultValue);
            default:
                return defaultValue;
        }
    }

    public static boolean getPlacementConfigBoolean(String key, boolean defaultValue) {
        String value = getPlacementConfig(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    public static double getPlacementConfigDouble(String key, double defaultValue) {
        String value = getPlacementConfig(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid double value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    // ===== SIMULATION CONFIGURATION METHODS =====

    /**
     * Get simulation configuration value
     */
    public static String getSimulationConfig(String key, String defaultValue) {
        switch (key) {
            case "simulation.allocation.default-deadline":
                return getEnvString("SIMULATION_ALLOCATION_DEADLINE", defaultValue);
            case "simulation.allocation.default-priority":
                return getEnvString("SIMULATION_ALLOCATION_PRIORITY", defaultValue);
            case "simulation.allocation.default-bandwidth":
                return getEnvString("SIMULATION_ALLOCATION_BANDWIDTH", defaultValue);
            case "simulation.allocation.fallback-node-id":
                return getEnvString("SIMULATION_FALLBACK_NODE_ID", defaultValue);
            case "simulation.energy.base-allocation":
                return getEnvString("SIMULATION_ENERGY_BASE_ALLOCATION", defaultValue);
            case "simulation.energy.complexity-factor":
                return getEnvString("SIMULATION_ENERGY_COMPLEXITY_FACTOR", defaultValue);
            case "simulation.energy.latency-factor":
                return getEnvString("SIMULATION_ENERGY_LATENCY_FACTOR", defaultValue);
            case "simulation.energy.base-execution":
                return getEnvString("SIMULATION_ENERGY_BASE_EXECUTION", defaultValue);
            case "simulation.cost.base-allocation":
                return getEnvString("SIMULATION_COST_BASE_ALLOCATION", defaultValue);
            case "simulation.cost.complexity-factor":
                return getEnvString("SIMULATION_COST_COMPLEXITY_FACTOR", defaultValue);
            case "simulation.cost.latency-factor":
                return getEnvString("SIMULATION_COST_LATENCY_FACTOR", defaultValue);
            case "simulation.cost.base-execution":
                return getEnvString("SIMULATION_COST_BASE_EXECUTION", defaultValue);
            case "simulation.external-tasks.enabled":
                return getEnvString("SIMULATION_EXTERNAL_TASKS_ENABLED", defaultValue);
            case "simulation.external-tasks.generation-rate":
                return getEnvString("SIMULATION_EXTERNAL_TASKS_RATE", defaultValue);
            case "simulation.external-tasks.initial-delay":
                return getEnvString("SIMULATION_EXTERNAL_TASKS_DELAY", defaultValue);
            case "simulation.statistics.reset-interval":
                return getEnvString("SIMULATION_STATISTICS_RESET_INTERVAL", defaultValue);
            case "simulation.statistics.percentage-calculation":
                return getEnvString("SIMULATION_STATISTICS_PERCENTAGE", defaultValue);
            default:
                return defaultValue;
        }
    }

    public static int getSimulationConfigInt(String key, int defaultValue) {
        String value = getSimulationConfig(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static long getSimulationConfigLong(String key, long defaultValue) {
        String value = getSimulationConfig(key, String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid long value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static double getSimulationConfigDouble(String key, double defaultValue) {
        String value = getSimulationConfig(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid double value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static boolean getSimulationConfigBoolean(String key, boolean defaultValue) {
        String value = getSimulationConfig(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    /**
     * Reload configuration (useful for runtime updates)
     */
    public static synchronized void reload() {
        initialized = false;
        fogNodeToSchedulerMap.clear();
        schedulerInstances.clear();
        initialize();
    }
}
