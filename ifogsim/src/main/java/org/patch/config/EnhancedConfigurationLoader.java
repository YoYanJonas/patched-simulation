package org.patch.config;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Enhanced configuration loader with YAML support and fog node mapping
 * Follows best practices for configuration management
 * 
 * Priority order:
 * 1. YAML config file (application.yml) - primary source
 * 2. Environment variables - for runtime-specific settings (hostnames, ports,
 * paths)
 */
public class EnhancedConfigurationLoader {
    private static final Logger logger = Logger.getLogger(EnhancedConfigurationLoader.class.getName());

    // Fog node to scheduler mapping
    private static Map<Integer, String> fogNodeToSchedulerMap = new HashMap<>();
    private static Map<String, SchedulerInstance> schedulerInstances = new HashMap<>();

    private static boolean initialized = false;
    private static boolean initializing = false; // Track if we're currently initializing to prevent recursion

    // YAML config file path
    private static String yamlConfigPath = null;

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
     * Initialize configuration by loading YAML file and setting up fog node mapping
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        if (initializing) {
            // Already initializing, don't recurse
            return;
        }

        initializing = true;
        try {
            // Load YAML configuration first
            loadYamlConfiguration();

            // Initialize fog node to scheduler mapping (uses YAML + env vars)
            initializeFogNodeMapping();

            // Initialize RL configuration if enabled in config (uses YAML + env vars)
            initializeRLConfiguration();

            initialized = true;
            initializing = false;
            logger.info("Enhanced configuration loaded successfully");
            logger.info("Fog node mapping initialized: " + fogNodeToSchedulerMap.size() + " mappings");
            logger.info("Scheduler instances: " + schedulerInstances.size());
            if (yamlConfigPath != null) {
                logger.info("YAML config loaded from: " + yamlConfigPath);
            }

        } catch (Exception e) {
            initializing = false;
            logger.log(Level.SEVERE, "Failed to load enhanced configuration", e);
            throw new RuntimeException("Enhanced configuration initialization failed", e);
        }
    }

    /**
     * Load YAML configuration file
     * Tries multiple paths in order:
     * 1. CONFIG_DIR environment variable + /application.yml
     * 2. config/rl-full-feature/simulation/application.yml (relative to project
     * root)
     * 3. ifogsim/config/application.yml (fallback)
     */
    private static void loadYamlConfiguration() {
        // Priority 1: CONFIG_DIR env var (runtime-specific)
        String configDir = System.getenv("CONFIG_DIR");
        if (configDir != null && !configDir.isEmpty()) {
            String configPath = configDir + File.separator + "application.yml";
            if (YamlConfigLoader.loadConfig(configPath)) {
                yamlConfigPath = configPath;
                logger.info("Loaded YAML config from CONFIG_DIR: " + configPath);
                return;
            }
        }

        // Priority 2: Try to find config relative to project root
        // This assumes we're running from project root or can traverse up
        String[] possiblePaths = {
                "config/rl-full-feature/simulation/application.yml",
                "../config/rl-full-feature/simulation/application.yml",
                "../../config/rl-full-feature/simulation/application.yml"
        };

        for (String path : possiblePaths) {
            File configFile = new File(path);
            if (configFile.exists() && configFile.isFile()) {
                if (YamlConfigLoader.loadConfig(path)) {
                    yamlConfigPath = path;
                    logger.info("Loaded YAML config from: " + path);
                    return;
                }
            }
        }

        // Priority 3: Fallback to ifogsim/config/application.yml
        String fallbackPath = "ifogsim/config/application.yml";
        File fallbackFile = new File(fallbackPath);
        if (!fallbackFile.exists()) {
            fallbackPath = "config/application.yml";
            fallbackFile = new File(fallbackPath);
        }

        if (fallbackFile.exists() && fallbackFile.isFile()) {
            if (YamlConfigLoader.loadConfig(fallbackPath)) {
                yamlConfigPath = fallbackPath;
                logger.info("Loaded YAML config from fallback: " + fallbackPath);
                return;
            }
        }

        // If no config file found, log warning but continue (will use env vars +
        // defaults)
        logger.warning("No YAML configuration file found. Using environment variables and defaults only.");
    }

    /**
     * Initialize RL configuration from environment variables (set from YAML)
     */
    private static void initializeRLConfiguration() {
        try {
            // Check if cloud RL is enabled - default to true
            boolean cloudRLEnabled = getRLConfigBoolean("rl.servers.cloud.enabled", true);
            if (cloudRLEnabled) {
                String cloudHost = getRLConfig("rl.servers.cloud.host", "localhost");
                int cloudPort = getRLConfigInt("rl.servers.cloud.port", 50051);
                org.patch.utils.RLConfig.enableCloudRL(cloudHost, cloudPort);
                logger.info("RL configuration initialized - Cloud RL enabled at " + cloudHost + ":" + cloudPort);
            }

            // Check if fog/placement RL is enabled - default to true
            boolean placementRLEnabled = getRLConfigBoolean("rl.servers.placement.enabled", true);
            if (placementRLEnabled) {
                String placementHost = getRLConfig("rl.servers.placement.host", "localhost");
                int placementPort = getRLConfigInt("rl.servers.placement.port", 50051);
                org.patch.utils.RLConfig.enablePlacementRL(placementHost, placementPort);
                logger.info("RL configuration initialized - Placement RL enabled at " + placementHost + ":"
                        + placementPort);
            }

            // Enable fog RL if placement RL is enabled (they're related)
            if (placementRLEnabled) {
                org.patch.utils.ServiceRegistry.setConfig(org.patch.utils.RLConfig.ENABLE_FOG_RL, true);
                logger.info("RL configuration initialized - Fog RL enabled (via placement RL)");
            }

            // Also check allocation service RL agent enabled flag - default to true
            boolean allocatorRLEnabled = getAllocationConfigBoolean("allocation.rl-agent.enabled", true);
            if (allocatorRLEnabled) {
                logger.info("RL configuration initialized - Allocator RL agent enabled");
                // This enables cloud RL since allocator is used by cloud
                if (!cloudRLEnabled) {
                    String allocHost = getGrpcConfig("grpc.allocation.host", "localhost");
                    int allocPort = getGrpcConfigInt("grpc.allocation.port", 50051);
                    org.patch.utils.RLConfig.enableCloudRL(allocHost, allocPort);
                    logger.info("RL configuration initialized - Cloud RL enabled via allocator at " + allocHost + ":"
                            + allocPort);
                }
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to initialize RL configuration from EnhancedConfigurationLoader", e);
        }
    }

    /**
     * Initialize fog node to scheduler mapping from YAML config
     */
    private static void initializeFogNodeMapping() {
        // Try to load scheduler instances from YAML first
        if (YamlConfigLoader.isLoaded()) {
            // Try to get scheduler instances from YAML
            Map<String, Object> schedulersMap = YamlConfigLoader.getMap("schedulers.instances");

            if (!schedulersMap.isEmpty()) {
                // Load scheduler instances from YAML
                for (String schedulerName : schedulersMap.keySet()) {
                    try {
                        String host = YamlConfigLoader.getValue("schedulers.instances." + schedulerName + ".host",
                                null);
                        int port = YamlConfigLoader.getInt("schedulers.instances." + schedulerName + ".port", 0);
                        int maxFogNodes = YamlConfigLoader
                                .getInt("schedulers.instances." + schedulerName + ".max-fog-nodes", 1);
                        String name = YamlConfigLoader.getValue("schedulers.instances." + schedulerName + ".name",
                                schedulerName);

                        // Fall back to env vars if YAML values are missing
                        if (host == null || host.isEmpty()) {
                            host = getEnvString(
                                    "SCHEDULER_" + schedulerName.replace("scheduler-", "").toUpperCase() + "_HOST",
                                    "localhost");
                        }
                        if (port == 0) {
                            int basePort = getEnvInt(
                                    "SCHEDULER_" + schedulerName.replace("scheduler-", "").toUpperCase() + "_PORT", 0);
                            if (basePort == 0) {
                                // Calculate default port based on scheduler number
                                int schedulerNum = Integer.parseInt(schedulerName.replace("scheduler-", ""));
                                port = 50051 + schedulerNum;
                            } else {
                                port = basePort;
                            }
                        }

                        schedulerInstances.put(schedulerName, new SchedulerInstance(name, host, port, maxFogNodes));
                        logger.info(
                                "Loaded scheduler instance from YAML: " + schedulerName + " -> " + host + ":" + port);
                    } catch (Exception e) {
                        logger.warning("Failed to load scheduler instance from YAML: " + schedulerName + " - "
                                + e.getMessage());
                    }
                }

                // Load fog node mapping from YAML
                Map<String, Object> mapping = YamlConfigLoader.getMap("schedulers.fog-node-mapping");
                if (!mapping.isEmpty()) {
                    for (Map.Entry<String, Object> entry : mapping.entrySet()) {
                        try {
                            int fogNodeId = Integer.parseInt(entry.getKey());
                            String schedulerName = entry.getValue().toString();
                            fogNodeToSchedulerMap.put(fogNodeId, schedulerName);
                            logger.fine("Mapped fog node " + fogNodeId + " to " + schedulerName);
                        } catch (Exception e) {
                            logger.warning(
                                    "Failed to parse fog node mapping: " + entry.getKey() + " -> " + entry.getValue());
                        }
                    }
                }
            }
        }

        // Fallback: Initialize scheduler instances from environment variables if YAML
        // didn't provide them
        if (schedulerInstances.isEmpty()) {
            schedulerInstances.put("scheduler-1", new SchedulerInstance(
                    "grpc-task-scheduler-1",
                    getEnvString("SCHEDULER_1_HOST", "localhost"),
                    getEnvInt("SCHEDULER_1_PORT", 50052),
                    1));

            schedulerInstances.put("scheduler-2", new SchedulerInstance(
                    "grpc-task-scheduler-2",
                    getEnvString("SCHEDULER_2_HOST", "localhost"),
                    getEnvInt("SCHEDULER_2_PORT", 50053),
                    1));

            schedulerInstances.put("scheduler-3", new SchedulerInstance(
                    "grpc-task-scheduler-3",
                    getEnvString("SCHEDULER_3_HOST", "localhost"),
                    getEnvInt("SCHEDULER_3_PORT", 50054),
                    1));
        }

        // Fallback: Map fog nodes to schedulers if YAML didn't provide mapping (1:1 for
        // 3 fog nodes)
        if (fogNodeToSchedulerMap.isEmpty()) {
            fogNodeToSchedulerMap.put(1, "scheduler-1");
            fogNodeToSchedulerMap.put(2, "scheduler-2");
            fogNodeToSchedulerMap.put(3, "scheduler-3");
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

    /**
     * Helper to get value from YAML or environment variable
     * Priority: 1. YAML config, 2. Environment variable, 3. Default value
     */
    private static String getConfigValue(String yamlPath, String envKey, String defaultValue) {
        // Try YAML first
        if (YamlConfigLoader.isLoaded()) {
            String yamlValue = YamlConfigLoader.getValue(yamlPath, null);
            if (yamlValue != null && !yamlValue.isEmpty() && !yamlValue.equals("null")) {
                return yamlValue;
            }
        }

        // Fall back to environment variable (for runtime-specific settings like
        // hostnames/ports)
        if (envKey != null) {
            String envValue = System.getenv(envKey);
            if (envValue != null && !envValue.isEmpty()) {
                return envValue;
            }
        }

        return defaultValue;
    }

    // Environment variable helpers (kept for backward compatibility and runtime
    // settings)
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
                return getConfigValue("grpc.global.connection.timeout", "GRPC_CONNECTION_TIMEOUT", defaultValue);
            case "grpc.retry.max.attempts":
                return getConfigValue("grpc.global.retry.max-attempts", "GRPC_RETRY_MAX_ATTEMPTS", defaultValue);
            case "grpc.retry.delay":
                return getConfigValue("grpc.global.retry.delay", "GRPC_RETRY_DELAY", defaultValue);
            case "grpc.circuit.breaker.failure.threshold":
                return getConfigValue("grpc.global.circuit-breaker.failure-threshold",
                        "GRPC_CIRCUIT_BREAKER_FAILURE_THRESHOLD", defaultValue);
            case "grpc.circuit.breaker.open.duration":
                return getConfigValue("grpc.global.circuit-breaker.open-duration", "GRPC_CIRCUIT_BREAKER_OPEN_DURATION",
                        defaultValue);
            case "grpc.fallback.node.id":
                return getConfigValue("grpc.global.fallback.node-id", "GRPC_FALLBACK_NODE_ID", defaultValue);
            case "grpc.fallback.execution.time":
                return getConfigValue("grpc.global.fallback.execution-time", "GRPC_FALLBACK_EXECUTION_TIME",
                        defaultValue);
            case "grpc.fallback.scheduling.delay":
                return getConfigValue("grpc.global.fallback.scheduling-delay", "GRPC_FALLBACK_SCHEDULING_DELAY",
                        defaultValue);
            case "grpc.allocation.host":
                // Unified allocator host - YAML first, then env vars
                return getConfigValue("allocation.service.host", "ALLOCATION_HOST",
                        getConfigValue("allocation.service.host", "ALLOCATION_SERVICE_HOST", defaultValue));
            case "grpc.allocation.port":
                // Unified allocator port - YAML first, then env vars
                return getConfigValue("allocation.service.port", "ALLOCATION_PORT",
                        getConfigValue("allocation.service.port", "ALLOCATION_SERVICE_PORT", defaultValue));
            case "grpc.scheduler.host":
                // Scheduler host from YAML schedulers.instances or env
                return getConfigValue("schedulers.instances.scheduler-1.host", "SCHEDULER_1_HOST",
                        getConfigValue("schedulers.instances.scheduler-1.host", "SCHEDULER_HOST", defaultValue));
            case "grpc.scheduler.port":
                // Scheduler port from YAML schedulers.instances or env
                return getConfigValue("schedulers.instances.scheduler-1.port", "SCHEDULER_1_PORT",
                        getConfigValue("schedulers.instances.scheduler-1.port", "SCHEDULER_PORT", defaultValue));
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

        // Handle common allocation configuration keys - YAML first, then env vars
        switch (key) {
            case "allocation.service.host":
                return getConfigValue("allocation.service.host", "ALLOCATION_HOST",
                        getConfigValue("allocation.service.host", "ALLOCATION_SERVICE_HOST", defaultValue));
            case "allocation.service.port":
                return getConfigValue("allocation.service.port", "ALLOCATION_PORT",
                        getConfigValue("allocation.service.port", "ALLOCATION_SERVICE_PORT", defaultValue));
            case "allocation.health.check.enabled":
                return getConfigValue("allocation.health-check.enabled", "ALLOCATION_HEALTH_CHECK_ENABLED",
                        defaultValue);
            case "allocation.fallback.enabled":
                return getConfigValue("allocation.fallback.enabled", "ALLOCATION_FALLBACK_ENABLED", defaultValue);
            case "allocation.fallback.node.id":
                return getConfigValue("allocation.fallback.node-id", "ALLOCATION_FALLBACK_NODE_ID", defaultValue);
            case "allocation.fallback.execution.time":
                return getConfigValue("allocation.fallback.execution-time", "ALLOCATION_FALLBACK_EXECUTION_TIME",
                        defaultValue);
            case "allocation.rl-agent.enabled":
                return getConfigValue("allocation.rl-agent.enabled", "ALLOCATION_RL_AGENT_ENABLED", defaultValue);
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
        if (!initialized && !initializing) {
            initialize();
        }
        // If we're already initializing, just return and let the current initialization
        // complete
    }

    // ===== EXTERNAL TASK CONFIGURATION METHODS =====

    /**
     * Get external task configuration value
     */
    public static String getExternalTaskConfig(String key, String defaultValue) {
        ensureInitialized();

        // Map old keys to new YAML paths
        switch (key) {
            case "external.tasks.generation.enabled":
                // Check simulation.external-tasks.enabled first (primary location in YAML)
                String enabled1 = getConfigValue("simulation.external-tasks.enabled", null, null);
                if (enabled1 != null && !enabled1.isEmpty() && !enabled1.equals("null")) {
                    return enabled1;
                }
                // Fallback to external-tasks.generation.enabled
                return getConfigValue("external-tasks.generation.enabled", "EXTERNAL_TASKS_GENERATION_ENABLED",
                        defaultValue);
            case "external.tasks.generation.default.rate":
                // Check simulation.external-tasks.generation-rate first (primary location in
                // YAML)
                String rate1 = getConfigValue("simulation.external-tasks.generation-rate", null, null);
                if (rate1 != null && !rate1.isEmpty() && !rate1.equals("null")) {
                    return rate1;
                }
                // Fallback to external-tasks.generation.default-rate
                return getConfigValue("external-tasks.generation.default-rate", "EXTERNAL_TASKS_DEFAULT_RATE",
                        defaultValue);
            case "external.tasks.generation.initial.delay":
                // Check simulation.external-tasks.initial-delay first (primary location in
                // YAML)
                String delay1 = getConfigValue("simulation.external-tasks.initial-delay", null, null);
                if (delay1 != null && !delay1.isEmpty() && !delay1.equals("null")) {
                    return delay1;
                }
                // Fallback to external-tasks.generation.initial-delay
                return getConfigValue("external-tasks.generation.initial-delay", "EXTERNAL_TASKS_INITIAL_DELAY",
                        defaultValue);
            case "external.tasks.parameters.app.id":
                return getConfigValue("external-tasks.parameters.app-id", "EXTERNAL_TASKS_APP_ID", defaultValue);
            case "external.tasks.parameters.user.id":
                return getConfigValue("external-tasks.parameters.user-id", "EXTERNAL_TASKS_USER_ID", defaultValue);
            case "external.tasks.parameters.number.of.pes":
                return getConfigValue("external-tasks.parameters.number-of-pes", "EXTERNAL_TASKS_NUMBER_OF_PES",
                        defaultValue);
            case "external.tasks.parameters.cpu.min":
                return getConfigValue("external-tasks.parameters.cpu.min", "EXTERNAL_TASKS_CPU_MIN", defaultValue);
            case "external.tasks.parameters.cpu.max":
                return getConfigValue("external-tasks.parameters.cpu.max", "EXTERNAL_TASKS_CPU_MAX", defaultValue);
            case "external.tasks.parameters.memory.min":
                return getConfigValue("external-tasks.parameters.memory.min", "EXTERNAL_TASKS_MEMORY_MIN",
                        defaultValue);
            case "external.tasks.parameters.memory.max":
                return getConfigValue("external-tasks.parameters.memory.max", "EXTERNAL_TASKS_MEMORY_MAX",
                        defaultValue);
            case "external.tasks.parameters.output.min":
                return getConfigValue("external-tasks.parameters.output.min", "EXTERNAL_TASKS_OUTPUT_MIN",
                        defaultValue);
            case "external.tasks.parameters.output.max":
                return getConfigValue("external-tasks.parameters.output.max", "EXTERNAL_TASKS_OUTPUT_MAX",
                        defaultValue);
            case "external.tasks.properties.tuple.type":
                return getConfigValue("external-tasks.properties.tuple-type", "EXTERNAL_TASKS_TUPLE_TYPE",
                        defaultValue);
            case "external.tasks.properties.module.name":
                return getConfigValue("external-tasks.properties.module-name", "EXTERNAL_TASKS_MODULE_NAME",
                        defaultValue);
            case "external.tasks.properties.direction":
                return getConfigValue("external-tasks.properties.direction", "EXTERNAL_TASKS_DIRECTION", defaultValue);
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
        ensureInitialized();

        // Map RL config keys to YAML paths - YAML first, then env vars
        switch (key) {
            case "rl.servers.cloud.host":
                return getConfigValue("rl.servers.cloud.host", "CLOUD_RL_SERVER_HOST", defaultValue);
            case "rl.servers.cloud.port":
                return getConfigValue("rl.servers.cloud.port", "CLOUD_RL_SERVER_PORT", defaultValue);
            case "rl.servers.cloud.enabled":
                return getConfigValue("rl.servers.cloud.enabled", "RL_SERVERS_CLOUD_ENABLED",
                        getConfigValue("rl.servers.cloud.enabled", "ENABLE_CLOUD_RL", defaultValue));
            case "rl.servers.external-task.host":
                return getConfigValue("rl.servers.external-task.host", "EXTERNAL_TASK_SERVER_HOST", defaultValue);
            case "rl.servers.external-task.port":
                return getConfigValue("rl.servers.external-task.port", "EXTERNAL_TASK_SERVER_PORT", defaultValue);
            case "rl.servers.external-task.enabled":
                return getConfigValue("rl.servers.external-task.enabled", "RL_SERVERS_EXTERNAL_TASK_ENABLED",
                        getConfigValue("rl.servers.external-task.enabled", "ENABLE_EXTERNAL_TASKS", defaultValue));
            case "rl.servers.placement.host":
                return getConfigValue("rl.servers.placement.host", "PLACEMENT_RL_SERVER_HOST", defaultValue);
            case "rl.servers.placement.port":
                return getConfigValue("rl.servers.placement.port", "PLACEMENT_RL_SERVER_PORT", defaultValue);
            case "rl.servers.placement.enabled":
                return getConfigValue("rl.servers.placement.enabled", "RL_SERVERS_PLACEMENT_ENABLED",
                        getConfigValue("rl.servers.placement.enabled", "ENABLE_PLACEMENT_RL", defaultValue));
            case "rl.algorithm.learning-rate":
                return getConfigValue("rl.algorithm.learning-rate", "RL_LEARNING_RATE", defaultValue);
            case "rl.algorithm.exploration-rate":
                return getConfigValue("rl.algorithm.exploration-rate", "RL_EXPLORATION_RATE", defaultValue);
            case "rl.training.update-interval":
                return getConfigValue("rl.training.update-interval", "RL_UPDATE_INTERVAL", defaultValue);
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
        ensureInitialized();

        // Map simulation config keys to YAML paths - YAML first, then env vars
        switch (key) {
            case "simulation.allocation.default-deadline":
                return getConfigValue("simulation.allocation.default-deadline", "SIMULATION_ALLOCATION_DEADLINE",
                        defaultValue);
            case "simulation.allocation.default-priority":
                return getConfigValue("simulation.allocation.default-priority", "SIMULATION_ALLOCATION_PRIORITY",
                        defaultValue);
            case "simulation.allocation.default-bandwidth":
                return getConfigValue("simulation.allocation.default-bandwidth", "SIMULATION_ALLOCATION_BANDWIDTH",
                        defaultValue);
            case "simulation.allocation.fallback-node-id":
                return getConfigValue("simulation.allocation.fallback-node-id", "SIMULATION_FALLBACK_NODE_ID",
                        defaultValue);
            case "simulation.energy.base-allocation":
                return getConfigValue("simulation.energy.base-allocation", "SIMULATION_ENERGY_BASE_ALLOCATION",
                        defaultValue);
            case "simulation.energy.complexity-factor":
                return getConfigValue("simulation.energy.complexity-factor", "SIMULATION_ENERGY_COMPLEXITY_FACTOR",
                        defaultValue);
            case "simulation.energy.latency-factor":
                return getConfigValue("simulation.energy.latency-factor", "SIMULATION_ENERGY_LATENCY_FACTOR",
                        defaultValue);
            case "simulation.energy.base-execution":
                return getConfigValue("simulation.energy.base-execution", "SIMULATION_ENERGY_BASE_EXECUTION",
                        defaultValue);
            case "simulation.cost.base-allocation":
                return getConfigValue("simulation.cost.base-allocation", "SIMULATION_COST_BASE_ALLOCATION",
                        defaultValue);
            case "simulation.cost.complexity-factor":
                return getConfigValue("simulation.cost.complexity-factor", "SIMULATION_COST_COMPLEXITY_FACTOR",
                        defaultValue);
            case "simulation.cost.latency-factor":
                return getConfigValue("simulation.cost.latency-factor", "SIMULATION_COST_LATENCY_FACTOR", defaultValue);
            case "simulation.cost.base-execution":
                return getConfigValue("simulation.cost.base-execution", "SIMULATION_COST_BASE_EXECUTION", defaultValue);
            case "simulation.external-tasks.enabled":
                return getConfigValue("simulation.external-tasks.enabled", "SIMULATION_EXTERNAL_TASKS_ENABLED",
                        defaultValue);
            case "simulation.external-tasks.generation-rate":
                return getConfigValue("simulation.external-tasks.generation-rate", "SIMULATION_EXTERNAL_TASKS_RATE",
                        defaultValue);
            case "simulation.external-tasks.initial-delay":
                return getConfigValue("simulation.external-tasks.initial-delay", "SIMULATION_EXTERNAL_TASKS_DELAY",
                        defaultValue);
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
        initializing = false;
        fogNodeToSchedulerMap.clear();
        schedulerInstances.clear();
        initialize();
    }
}
