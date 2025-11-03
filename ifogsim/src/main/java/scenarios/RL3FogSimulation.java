package scenarios;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.Vm;
import org.fog.application.AppEdge;
import org.fog.application.AppModule;
import org.patch.application.RLApplication;
import org.patch.application.RLAppModule;
import org.patch.application.RLAppEdge;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.ModulePlacement;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;
import org.patch.broker.RLFogBroker;
import org.patch.config.EnhancedConfigurationLoader;
import org.patch.controller.RLController;
import org.patch.core.ExternalTaskGenerator;
import org.patch.devices.RLCloudDevice;
import org.patch.devices.RLFogDevice;
import org.patch.placement.RLModulePlacement;
import org.patch.utils.RLConfig;

import java.util.*;
import java.util.logging.Logger;

/**
 * RL-Enhanced 3-Fog Node Simulation
 * 
 * This simulation demonstrates:
 * 1. Cloud device with external task generation
 * 2. 3 fog nodes connected to cloud
 * 3. RL-based task allocation via go-grpc-server (cloud → fog selection)
 * 4. RL-based task scheduling via grpc-task-scheduler (per-fog node, 1-1
 * connection)
 * 5. Proper metric collection and reporting
 * 
 * Architecture:
 * - Cloud (RLCloudDevice) → Allocation Server (go-grpc-server) → Select Fog
 * Node
 * - Each Fog Node (RLFogDevice) → Scheduler Server (grpc-task-scheduler) →
 * Streaming Queue
 * - External tasks generated periodically and sent to cloud
 * - Internal tasks from sensors sent to fog nodes
 * - All tasks scheduled and executed with RL-based decisions
 * 
 * gRPC Server Requirements:
 * - 1x go-grpc-server instance (localhost:50051) for allocation
 * - 3x grpc-task-scheduler instances (localhost:50052, 50053, 50054) for
 * scheduling
 * 
 * @author Younes Shafiee
 */
public class RL3FogSimulation {
    private static final Logger logger = Logger.getLogger(RL3FogSimulation.class.getName());

    // Simulation parameters
    private static final int NUM_FOG_NODES = 3;
    private static final int SENSORS_PER_FOG = 2;
    private static final double SIMULATION_TIME = 300.0; // 5 minutes

    // Device IDs
    private static int cloudId;
    private static List<Integer> fogDeviceIds = new ArrayList<>();
    private static List<Integer> sensorIds = new ArrayList<>();

    // Devices
    private static RLCloudDevice cloud;
    private static List<RLFogDevice> fogDevices = new ArrayList<>();
    private static List<Sensor> sensors = new ArrayList<>();

    // Application
    private static String appId = "rl_3fog_app";
    private static RLFogBroker broker;
    private static RLController controller;

    public static void main(String[] args) {
        try {
            logger.info("================================================================================");
            logger.info("Starting RL-Enhanced 3-Fog Node Simulation");
            logger.info("================================================================================");

            // Step 1: Load configuration
            loadConfiguration();

            // Step 2: Initialize CloudSim
            initializeCloudSim();

            // Step 2.5: Set SIMULATION_TIME and MAX_SIMULATION_TIME BEFORE creating any
            // entities
            // SIMULATION_TIME: When to stop generating NEW tasks (sensors, external tasks)
            // MAX_SIMULATION_TIME: Hard termination cap (SIMULATION_TIME + buffer for
            // queued events)
            final int PROCESSING_BUFFER = 60; // Buffer time (seconds) to process queued events after SIMULATION_TIME
            org.fog.utils.Config.SIMULATION_TIME = (int) SIMULATION_TIME;
            org.fog.utils.Config.MAX_SIMULATION_TIME = (int) SIMULATION_TIME + PROCESSING_BUFFER;
            logger.info(String.format("SIMULATION_TIME set to: %d seconds (stop generating new tasks)",
                    org.fog.utils.Config.SIMULATION_TIME));
            logger.info(String.format("MAX_SIMULATION_TIME set to: %d seconds (hard termination cap)",
                    org.fog.utils.Config.MAX_SIMULATION_TIME));

            // Step 3: Create broker and controller
            createBrokerAndController();

            // Step 4: Create cloud device
            createCloudDevice();

            // Step 5: Create fog nodes
            createFogNodes();

            // Step 6: Create sensors
            createSensors();

            // Step 7: Create and deploy application
            createApplication();

            // Step 8: Setup external task generation
            setupExternalTaskGeneration();

            // Step 9: Start simulation
            startSimulation();

            // Step 10: Print results
            printResults();

            logger.info("================================================================================");
            logger.info("Simulation completed successfully");
            logger.info("================================================================================");

        } catch (Exception e) {
            logger.severe("Simulation failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Load configuration from application.yml
     */
    private static void loadConfiguration() {
        logger.info("Loading configuration from application.yml...");

        // Verify gRPC configuration - unified allocator server config
        // Use consistent naming: allocationHost and allocationPort
        String allocationHost = EnhancedConfigurationLoader.getGrpcConfig("grpc.allocation.host", "localhost");
        int allocationPort = EnhancedConfigurationLoader.getGrpcConfigInt("grpc.allocation.port", 50051);

        logger.info(String.format("Allocation Server: %s:%d", allocationHost, allocationPort));

        // Verify scheduler configuration
        String schedHost = EnhancedConfigurationLoader.getGrpcConfig("grpc.scheduler.host", "localhost");
        int schedBasePort = EnhancedConfigurationLoader.getGrpcConfigInt("grpc.scheduler.port", 50052);

        logger.info(String.format("Scheduler Servers: %s:%d-%d", schedHost, schedBasePort,
                schedBasePort + NUM_FOG_NODES - 1));

        // Verify RL configuration - force check after EnhancedConfigurationLoader
        // initialization
        EnhancedConfigurationLoader.initialize(); // Ensure config is loaded

        // Check RL enabled flags from both config and direct checks
        boolean fogRLEnabled = RLConfig.isFogRLEnabled();
        boolean cloudRLEnabled = RLConfig.isCloudRLEnabled();
        boolean allocatorRLEnabled = EnhancedConfigurationLoader
                .getAllocationConfigBoolean("allocation.rl-agent.enabled", true);
        boolean cloudRLFromConfig = EnhancedConfigurationLoader
                .getRLConfigBoolean("rl.servers.cloud.enabled", true);
        boolean placementRLFromConfig = EnhancedConfigurationLoader
                .getRLConfigBoolean("rl.servers.placement.enabled", true);

        logger.info(String.format("RL Enabled - Fog: %b, Cloud: %b, Allocator RL: %b",
                fogRLEnabled, cloudRLEnabled, allocatorRLEnabled));
        logger.info(String.format("RL Config - Cloud from YAML: %b, Placement from YAML: %b",
                cloudRLFromConfig, placementRLFromConfig));

        // If config says enabled but RLConfig doesn't have it, enable it
        if (cloudRLFromConfig && !cloudRLEnabled) {
            String cloudHost = EnhancedConfigurationLoader.getRLConfig("rl.servers.cloud.host", "localhost");
            int cloudPort = EnhancedConfigurationLoader.getRLConfigInt("rl.servers.cloud.port", 50051);
            RLConfig.enableCloudRL(cloudHost, cloudPort);
            logger.info(String.format("Enabled Cloud RL from config at %s:%d", cloudHost, cloudPort));
            cloudRLEnabled = true;
        }

        if (placementRLFromConfig && !fogRLEnabled) {
            String placementHost = EnhancedConfigurationLoader.getRLConfig("rl.servers.placement.host", "localhost");
            int placementPort = EnhancedConfigurationLoader.getRLConfigInt("rl.servers.placement.port", 50051);
            RLConfig.enablePlacementRL(placementHost, placementPort);
            org.patch.utils.ServiceRegistry.setConfig(RLConfig.ENABLE_FOG_RL, true);
            logger.info(String.format("Enabled Fog RL from config at %s:%d", placementHost, placementPort));
            fogRLEnabled = true;
        }

        logger.info(String.format("Final RL Status - Fog: %b, Cloud: %b", fogRLEnabled, cloudRLEnabled));

        // Verify external task configuration
        boolean externalTasksEnabled = EnhancedConfigurationLoader
                .getSimulationConfigBoolean("simulation.external-tasks.enabled", false);
        double taskRate = EnhancedConfigurationLoader
                .getSimulationConfigDouble("simulation.external-tasks.generation-rate", 2.0);

        logger.info(
                String.format("External Tasks - Enabled: %b, Rate: %.2f tasks/sec", externalTasksEnabled, taskRate));
    }

    /**
     * Initialize CloudSim framework
     */
    private static void initializeCloudSim() throws Exception {
        logger.info("Initializing CloudSim framework...");

        int numUsers = 1;
        Calendar calendar = Calendar.getInstance();
        boolean traceFlag = false;

        CloudSim.init(numUsers, calendar, traceFlag);

        logger.info("CloudSim initialized successfully");
    }

    /**
     * Create broker and controller
     */
    private static void createBrokerAndController() throws Exception {
        logger.info("Creating broker and controller...");

        // Create RL-aware broker
        broker = new RLFogBroker("rl_broker");

        // Note: Controller will be created after devices are created
        // RLController requires List<FogDevice>, List<Sensor>, List<Actuator>

        logger.info(String.format("Broker ID: %d", broker.getId()));
    }

    /**
     * Create cloud device with allocation server connection
     */
    private static void createCloudDevice() throws Exception {
        logger.info("Creating cloud device...");

        // Cloud characteristics
        long mips = 44800; // High processing power
        int ram = 40000; // 40 GB
        double uplinkBandwidth = 10000; // 10 Gbps
        double downlinkBandwidth = 10000;
        double ratePerMips = 0.01;

        // Power model
        PowerModelLinear powerModel = new PowerModelLinear(107.339, 83.4333);

        // Allocation server connection - unified naming
        String allocationHost = EnhancedConfigurationLoader.getGrpcConfig("grpc.allocation.host", "localhost");
        int allocationPort = EnhancedConfigurationLoader.getGrpcConfigInt("grpc.allocation.port", 50051);

        // Create cloud device
        cloud = new RLCloudDevice(
                "cloud",
                mips,
                ram,
                uplinkBandwidth,
                downlinkBandwidth,
                ratePerMips,
                powerModel,
                allocationHost,
                allocationPort);

        cloudId = cloud.getId();
        cloud.setParentId(-1); // No parent

        logger.info(String.format("Cloud created - ID: %d, MIPS: %d, RAM: %d MB", cloudId, mips, ram));
    }

    /**
     * Create 3 fog nodes with individual scheduler server connections
     */
    private static void createFogNodes() throws Exception {
        logger.info("Creating fog nodes...");

        // Fog node base characteristics
        long mips = 2800; // Moderate processing power
        int ram = 4000; // 4 GB
        double uplinkBandwidth = 10000;
        double downlinkBandwidth = 10000;
        double ratePerMips = 0.01;

        // Scheduler server base configuration
        String schedHost = EnhancedConfigurationLoader.getGrpcConfig("grpc.scheduler.host", "localhost");
        int schedBasePort = EnhancedConfigurationLoader.getGrpcConfigInt("grpc.scheduler.port", 50052);

        // Create fog nodes
        for (int i = 0; i < NUM_FOG_NODES; i++) {
            // Each fog node connects to its own scheduler server instance (1-1 mapping)
            int schedulerPort = schedBasePort + i;

            // Power model values
            double busyPower = 107.339;
            double idlePower = 83.4333;

            // Create fog device
            RLFogDevice fogDevice = new RLFogDevice(
                    String.format("fog-node-%d", i),
                    mips,
                    ram,
                    uplinkBandwidth,
                    downlinkBandwidth,
                    ratePerMips,
                    busyPower,
                    idlePower,
                    schedHost,
                    schedulerPort);

            // Set parent to cloud
            fogDevice.setParentId(cloudId);

            // Add to cloud's children
            cloud.getChildrenIds().add(fogDevice.getId());

            // Store references
            fogDevices.add(fogDevice);
            fogDeviceIds.add(fogDevice.getId());

            logger.info(String.format("Fog Node %d created - ID: %d, Scheduler: %s:%d",
                    i, fogDevice.getId(), schedHost, schedulerPort));
        }

        logger.info(String.format("Created %d fog nodes successfully", NUM_FOG_NODES));
    }

    /**
     * Create sensors connected to fog nodes
     */
    private static void createSensors() throws Exception {
        logger.info("Creating sensors...");

        for (int fogIdx = 0; fogIdx < NUM_FOG_NODES; fogIdx++) {
            RLFogDevice fogDevice = fogDevices.get(fogIdx);

            for (int sensorIdx = 0; sensorIdx < SENSORS_PER_FOG; sensorIdx++) {
                // Create sensor
                String sensorName = String.format("sensor-fog%d-%d", fogIdx, sensorIdx);

                Sensor sensor = new Sensor(
                        sensorName,
                        "SENSOR_DATA", // Tuple type
                        broker.getId(),
                        appId,
                        new DeterministicDistribution(5.0) // Send every 5 seconds
                );

                // Connect sensor to fog device
                sensor.setGatewayDeviceId(fogDevice.getId());
                sensor.setLatency(10.0); // 10ms latency to fog

                // Set GeoLocation (required for Sensor.startEntity())
                sensor.setGeoLocation(new org.fog.utils.GeoLocation(fogIdx * 10.0, fogIdx * 10.0));

                sensors.add(sensor);
                sensorIds.add(sensor.getId());

                logger.info(String.format("Sensor %s created - ID: %d, connected to Fog Node %d",
                        sensorName, sensor.getId(), fogIdx));
            }
        }

        // Now create controller with all devices and sensors
        controller = new RLController(
                "rl_controller",
                new ArrayList<FogDevice>(fogDevices),
                sensors,
                new ArrayList<>() // No actuators for now
        );

        // Set controller ID for all devices
        cloud.setControllerId(controller.getId());
        for (RLFogDevice fogDevice : fogDevices) {
            fogDevice.setControllerId(controller.getId());
        }

        logger.info(String.format("Controller created - ID: %d", controller.getId()));

        logger.info(String.format("Created %d sensors successfully", sensors.size()));
    }

    /**
     * Create and deploy application
     */
    private static void createApplication() throws Exception {
        logger.info("Creating application...");

        // Create application using RLApplication for RL-enhanced features
        RLApplication application = new RLApplication(appId, broker.getId());

        // Add RL application modules with energy and cost characteristics
        // Using default energy/cost values (can be configured later)
        double defaultEnergyConsumption = 1.0; // J per execution
        double defaultCostPerExecution = 0.1; // $ per execution

        // Note: addRLAppModule takes (moduleName, ram, energyConsumption,
        // costPerExecution)
        // For now, use RAM=10 (will be used for module sizing), and default RL
        // characteristics
        application.addRLAppModule("processing_module", 10, defaultEnergyConsumption, defaultCostPerExecution);
        application.addRLAppModule("aggregation_module", 10, defaultEnergyConsumption, defaultCostPerExecution);

        // Enable RL for the application
        application.enableRL();

        // Add RL application edges (data flow) with energy and cost characteristics
        // Using default energy/cost values for data transfer
        double defaultEnergyCost = 0.001; // J per data transfer
        double defaultNetworkCost = 0.0001; // $ per data transfer

        // Add RL edges: addRLAppEdge(source, destination, tupleCpuLength,
        // tupleNwLength,
        // tupleType, direction, edgeType, energyCost, networkCost)
        application.addRLAppEdge("SENSOR_DATA", "processing_module", 1000, 500, "PROCESSED_DATA",
                Tuple.UP, AppEdge.SENSOR, defaultEnergyCost, defaultNetworkCost);
        application.addRLAppEdge("processing_module", "aggregation_module", 2000, 500, "AGGREGATED_DATA",
                Tuple.UP, AppEdge.MODULE, defaultEnergyCost, defaultNetworkCost);

        // Add tuple mapping (for processing)
        // Note: FractionalSelectivity is used for tuple processing efficiency
        application.addTupleMapping("processing_module", "SENSOR_DATA", "PROCESSED_DATA",
                new FractionalSelectivity(1.0));
        application.addTupleMapping("aggregation_module", "PROCESSED_DATA", "AGGREGATED_DATA",
                new FractionalSelectivity(1.0));

        // Note: Application loops are added automatically by iFogSim based on edges

        // Create list of all fog devices (cloud + fog nodes)
        List<FogDevice> allDevices = new ArrayList<>();
        allDevices.add(cloud);
        allDevices.addAll(fogDevices);

        // Deploy application using RLModulePlacement
        ModulePlacement placement = new RLModulePlacement(
                allDevices,
                sensors,
                new ArrayList<>(), // No actuators
                application);

        // [DEBUG] Log application edges before submission
        logger.info("[APP-DEBUG] Application edges:");
        for (AppEdge edge : application.getEdges()) {
            if (edge instanceof RLAppEdge) {
                RLAppEdge rlEdge = (RLAppEdge) edge;
                logger.info(String.format(
                        "[APP-DEBUG]   RL Edge: %s -> %s (Type:%s, CPU:%.0f, NW:%.0f, Energy:%.4f J, Cost:$%.6f)",
                        rlEdge.getSource(), rlEdge.getDestination(),
                        rlEdge.getEdgeType() == AppEdge.SENSOR ? "SENSOR" : "MODULE",
                        rlEdge.getTupleCpuLength(), rlEdge.getTupleNwLength(),
                        rlEdge.getEnergyCost(), rlEdge.getNetworkCost()));
            } else {
                logger.info(String.format("[APP-DEBUG]   Edge: %s -> %s (Type:%s, CPU:%.0f, NW:%.0f)",
                        edge.getSource(), edge.getDestination(),
                        edge.getEdgeType() == AppEdge.SENSOR ? "SENSOR" : "MODULE",
                        edge.getTupleCpuLength(), edge.getTupleNwLength()));
            }
        }

        logger.info("[APP-DEBUG] Application modules:");
        for (AppModule module : application.getModules()) {
            if (module instanceof RLAppModule) {
                RLAppModule rlModule = (RLAppModule) module;
                logger.info(String.format("[APP-DEBUG]   RL Module: %s (MIPS:%.0f, Energy:%.2f J, Cost:$%.4f)",
                        rlModule.getName(), rlModule.getMips(),
                        rlModule.getEnergyConsumption(), rlModule.getCostPerExecution()));
            } else {
                logger.info(String.format("[APP-DEBUG]   Module: %s (MIPS:%.0f)", module.getName(), module.getMips()));
            }
        }

        // Submit application to controller
        controller.submitApplication(application, placement);

        // [DEBUG] Log module placement after submission
        logger.info("[APP-DEBUG] Module placement after submission:");
        for (FogDevice device : allDevices) {
            List<String> modulesOnDevice = new ArrayList<>();
            List<String> rlModulesOnDevice = new ArrayList<>();
            for (Vm vm : device.getHost().getVmList()) {
                if (vm instanceof RLAppModule) {
                    RLAppModule rlModule = (RLAppModule) vm;
                    modulesOnDevice.add(rlModule.getName());
                    rlModulesOnDevice.add(rlModule.getName() + "(RL)");
                } else if (vm instanceof AppModule) {
                    modulesOnDevice.add(((AppModule) vm).getName());
                }
            }
            if (!modulesOnDevice.isEmpty()) {
                logger.info(String.format("[APP-DEBUG]   Device %s (ID:%d) has modules: %s (RL modules: %s)",
                        device.getName(), device.getId(), modulesOnDevice, rlModulesOnDevice));
            } else {
                logger.info(String.format("[APP-DEBUG]   Device %s (ID:%d) has NO modules",
                        device.getName(), device.getId()));
            }
        }

        logger.info("Application created and deployed successfully");
    }

    /**
     * Setup external task generation
     */
    private static void setupExternalTaskGeneration() {
        logger.info("Setting up external task generation...");

        boolean enabled = EnhancedConfigurationLoader.getSimulationConfigBoolean("simulation.external-tasks.enabled",
                false);

        if (!enabled) {
            logger.warning("External task generation is DISABLED in configuration");
            logger.warning("To enable, set 'simulation.external-tasks.enabled: true' in application.yml");
            return;
        }

        double taskRate = EnhancedConfigurationLoader
                .getSimulationConfigDouble("simulation.external-tasks.generation-rate", 2.0);
        long initialDelay = EnhancedConfigurationLoader
                .getSimulationConfigLong("simulation.external-tasks.initial-delay", 1000);

        // Create external task generator (will start automatically)
        new ExternalTaskGenerator(
                "external_task_gen",
                cloudId,
                taskRate);

        logger.info(String.format("External task generation configured - Rate: %.2f tasks/sec, Initial Delay: %d ms",
                taskRate, initialDelay));
        logger.info("Note: External task generation will start automatically during simulation");
    }

    /**
     * Start simulation
     */
    private static void startSimulation() {
        logger.info("Starting simulation...");
        logger.info(String.format("Simulation time: %.2f seconds", SIMULATION_TIME));
        logger.info(String.format("SIMULATION_TIME (stop generation): %d seconds",
                org.fog.utils.Config.SIMULATION_TIME));
        logger.info(String.format("MAX_SIMULATION_TIME (hard termination): %d seconds",
                org.fog.utils.Config.MAX_SIMULATION_TIME));

        // Verify values are set correctly
        if (org.fog.utils.Config.SIMULATION_TIME != (int) SIMULATION_TIME) {
            logger.warning(String.format(
                    "SIMULATION_TIME mismatch! Expected: %d, Actual: %d. Setting to correct value.",
                    (int) SIMULATION_TIME, org.fog.utils.Config.SIMULATION_TIME));
            org.fog.utils.Config.SIMULATION_TIME = (int) SIMULATION_TIME;
        }
        if (org.fog.utils.Config.MAX_SIMULATION_TIME <= org.fog.utils.Config.SIMULATION_TIME) {
            logger.warning(String.format(
                    "MAX_SIMULATION_TIME (%d) should be > SIMULATION_TIME (%d). Adding buffer.",
                    org.fog.utils.Config.MAX_SIMULATION_TIME, org.fog.utils.Config.SIMULATION_TIME));
            org.fog.utils.Config.MAX_SIMULATION_TIME = org.fog.utils.Config.SIMULATION_TIME + 60;
        }

        // Start and stop simulation
        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        logger.info("Simulation completed");
    }

    /**
     * Print simulation results and generate report files
     */
    private static void printResults() {
        logger.info("================================================================================");
        logger.info("SIMULATION RESULTS");
        logger.info("================================================================================");

        // Print cloud statistics
        printCloudStatistics();

        // Print fog node statistics
        printFogStatistics();

        // Print sensor statistics
        printSensorStatistics();

        // Print application statistics
        printApplicationStatistics();

        // Print RL allocation statistics
        printRLAllocationStatistics();

        // Print RL scheduling statistics
        printRLSchedulingStatistics();

        // Print cache statistics
        printCacheStatistics();

        // Print energy and cost
        printEnergyAndCost();

        logger.info("================================================================================");

        // Generate report files
        generateReportFiles();
    }

    /**
     * Generate report files from simulation results
     */
    private static void generateReportFiles() {
        try {
            org.patch.utils.ReportGenerator reportGenerator = new org.patch.utils.ReportGenerator();

            // Collect cloud device data
            Map<String, Object> cloudData = collectCloudData();

            // Collect fog devices data
            List<Map<String, Object>> fogData = collectFogDevicesData();

            // Generate reports with collected data
            reportGenerator.generateReports(cloudData, fogData, CloudSim.clock());

        } catch (Exception e) {
            logger.severe("Error generating report files: " + e.getMessage());
        }
    }

    /**
     * Collect cloud device data for reporting
     */
    private static Map<String, Object> collectCloudData() {
        Map<String, Object> cloudData = new HashMap<>();
        cloudData.put("device_id", cloud.getId());
        cloudData.put("device_name", cloud.getName());
        cloudData.put("energy_consumption", cloud.getEnergyConsumption());
        cloudData.put("cost", cloud.getTotalCost());

        // RL allocation statistics
        if (cloud.getAllocationClient() != null && cloud.isRlEnabled()) {
            Map<String, Object> allocationStats = new HashMap<>();
            allocationStats.put("total_decisions", cloud.getTotalAllocationDecisions());
            allocationStats.put("successful_allocations", cloud.getSuccessfulAllocations());
            allocationStats.put("success_rate", cloud.getAllocationSuccessRate());
            allocationStats.put("total_energy", cloud.getTotalAllocationEnergy());
            allocationStats.put("total_cost", cloud.getTotalAllocationCost());
            allocationStats.put("average_latency_ms", cloud.getAverageAllocationLatency());
            allocationStats.put("throughput_per_sec", cloud.getAllocationThroughput());
            cloudData.put("allocation_stats", allocationStats);
        }

        return cloudData;
    }

    /**
     * Collect fog devices data for reporting
     */
    private static List<Map<String, Object>> collectFogDevicesData() {
        List<Map<String, Object>> fogDevicesData = new ArrayList<>();

        for (int i = 0; i < fogDevices.size(); i++) {
            RLFogDevice fogDevice = fogDevices.get(i);
            Map<String, Object> fogData = new HashMap<>();

            fogData.put("node_id", i);
            fogData.put("device_id", fogDevice.getId());
            fogData.put("device_name", fogDevice.getName());
            fogData.put("energy_consumption", fogDevice.getEnergyConsumption());
            fogData.put("cost", fogDevice.getTotalCost());
            fogData.put("unscheduled_queue_size", fogDevice.getUnscheduledQueue().size());
            fogData.put("scheduled_queue_size", fogDevice.getScheduledQueue().size());

            // RL scheduling statistics
            if (fogDevice.getSchedulerClient() != null && fogDevice.isRlEnabled()) {
                Map<String, Object> schedulingStats = new HashMap<>();
                schedulingStats.put("total_decisions", fogDevice.getTotalSchedulingDecisions());
                schedulingStats.put("success_rate", fogDevice.getSchedulingSuccessRate());
                schedulingStats.put("total_energy", fogDevice.getTotalSchedulingEnergy());
                schedulingStats.put("total_cost", fogDevice.getTotalSchedulingCost());
                schedulingStats.put("average_latency_ms", fogDevice.getAverageSchedulingLatency());
                schedulingStats.put("throughput_per_sec", fogDevice.getSchedulingThroughput());
                fogData.put("scheduling_stats", schedulingStats);
            }

            // Cache statistics
            Map<String, Object> deviceCacheStats = fogDevice.getCacheStatistics();
            Map<String, Object> cacheStats = new HashMap<>();
            cacheStats.put("cache_size", deviceCacheStats.get("cacheSize"));
            cacheStats.put("max_cache_size", deviceCacheStats.get("maxCacheSize"));
            cacheStats.put("hits", deviceCacheStats.get("cacheHitCount"));
            cacheStats.put("misses", deviceCacheStats.get("cacheMissCount"));
            cacheStats.put("hit_rate", deviceCacheStats.get("cacheHitRate"));
            fogData.put("cache_stats", cacheStats);

            fogDevicesData.add(fogData);
        }

        return fogDevicesData;
    }

    private static void printCloudStatistics() {
        logger.info("\n[CLOUD DEVICE STATISTICS]");
        logger.info(String.format("Device: %s (ID: %d)", cloud.getName(), cloud.getId()));
        logger.info(String.format("Total Energy Consumed: %.2f J", cloud.getEnergyConsumption()));
        logger.info(String.format("Total Cost: $%.4f", cloud.getTotalCost()));

        // Get RL statistics if available
        if (cloud.getAllocationClient() != null) {
            logger.info("RL Allocation: ENABLED");
        }
    }

    private static void printFogStatistics() {
        logger.info("\n[FOG NODE STATISTICS]");

        for (int i = 0; i < fogDevices.size(); i++) {
            RLFogDevice fogDevice = fogDevices.get(i);
            logger.info(String.format("\nFog Node %d: %s (ID: %d)", i, fogDevice.getName(), fogDevice.getId()));
            logger.info(String.format("  Total Energy Consumed: %.2f J", fogDevice.getEnergyConsumption()));
            logger.info(String.format("  Total Cost: $%.4f", fogDevice.getTotalCost()));

            // Queue statistics
            logger.info(String.format("  Unscheduled Queue Size: %d", fogDevice.getUnscheduledQueue().size()));
            logger.info(String.format("  Scheduled Queue Size: %d", fogDevice.getScheduledQueue().size()));

            // RL scheduling status
            if (fogDevice.getSchedulerClient() != null) {
                logger.info("  RL Scheduling: ENABLED");
                logger.info(String.format("  Streaming Observer Active: %b", fogDevice.getStreamingObserver() != null));
            }
        }
    }

    private static void printSensorStatistics() {
        logger.info("\n[SENSOR STATISTICS]");
        logger.info(String.format("Total Sensors: %d", sensors.size()));

        for (Sensor sensor : sensors) {
            logger.info(String.format("  %s (ID: %d) - Gateway: %d",
                    sensor.getName(), sensor.getId(), sensor.getGatewayDeviceId()));
        }
    }

    private static void printApplicationStatistics() {
        logger.info("\n[APPLICATION STATISTICS]");
        logger.info(String.format("Application ID: %s", appId));
        logger.info(String.format("Broker ID: %d", broker.getId()));
        logger.info(String.format("Controller ID: %d", controller.getId()));

        // TimeKeeper statistics
        Map<Integer, Double> loopIdToCurrentAverage = TimeKeeper.getInstance().getLoopIdToCurrentAverage();
        if (!loopIdToCurrentAverage.isEmpty()) {
            logger.info("\n[LOOP LATENCIES]");
            for (Map.Entry<Integer, Double> entry : loopIdToCurrentAverage.entrySet()) {
                logger.info(String.format("  Loop %d: %.2f ms", entry.getKey(), entry.getValue()));
            }
        }
    }

    private static void printEnergyAndCost() {
        logger.info("\n[OVERALL ENERGY & COST]");

        // Calculate total energy
        double totalEnergy = cloud.getEnergyConsumption();
        for (RLFogDevice fogDevice : fogDevices) {
            totalEnergy += fogDevice.getEnergyConsumption();
        }

        // Calculate total cost
        double totalCost = cloud.getTotalCost();
        for (RLFogDevice fogDevice : fogDevices) {
            totalCost += fogDevice.getTotalCost();
        }

        logger.info(String.format("Total Energy Consumption: %.2f J", totalEnergy));
        logger.info(String.format("Total Cost: $%.4f", totalCost));
        logger.info(String.format("Average Energy per Device: %.2f J", totalEnergy / (NUM_FOG_NODES + 1)));
        logger.info(String.format("Average Cost per Device: $%.4f", totalCost / (NUM_FOG_NODES + 1)));
    }

    private static void printRLAllocationStatistics() {
        logger.info("\n[RL ALLOCATION STATISTICS]");

        if (cloud.getAllocationClient() != null && cloud.isRlEnabled()) {
            logger.info(String.format("Total Allocation Decisions: %d", cloud.getTotalAllocationDecisions()));
            logger.info(String.format("Successful Allocations: %d", cloud.getSuccessfulAllocations()));
            logger.info(String.format("Allocation Success Rate: %.2f%%", cloud.getAllocationSuccessRate() * 100));
            logger.info(String.format("Total Allocation Energy: %.2f J", cloud.getTotalAllocationEnergy()));
            logger.info(String.format("Total Allocation Cost: $%.4f", cloud.getTotalAllocationCost()));
            logger.info(String.format("Average Allocation Latency: %.2f ms", cloud.getAverageAllocationLatency()));
            logger.info(String.format("Allocation Throughput: %.2f decisions/sec", cloud.getAllocationThroughput()));
        } else {
            logger.info("RL Allocation: DISABLED");
        }
    }

    private static void printRLSchedulingStatistics() {
        logger.info("\n[RL SCHEDULING STATISTICS]");

        for (int i = 0; i < fogDevices.size(); i++) {
            RLFogDevice fogDevice = fogDevices.get(i);

            if (fogDevice.getSchedulerClient() != null && fogDevice.isRlEnabled()) {
                logger.info(String.format("\nFog Node %d: %s", i, fogDevice.getName()));
                logger.info(String.format("  Total Scheduling Decisions: %d", fogDevice.getTotalSchedulingDecisions()));
                logger.info(
                        String.format("  Scheduling Success Rate: %.2f%%", fogDevice.getSchedulingSuccessRate() * 100));
                logger.info(String.format("  Total Scheduling Energy: %.2f J", fogDevice.getTotalSchedulingEnergy()));
                logger.info(String.format("  Total Scheduling Cost: $%.4f", fogDevice.getTotalSchedulingCost()));
                logger.info(String.format("  Average Scheduling Latency: %.2f ms",
                        fogDevice.getAverageSchedulingLatency()));
                logger.info(String.format("  Scheduling Throughput: %.2f decisions/sec",
                        fogDevice.getSchedulingThroughput()));
                logger.info(String.format("  Streaming Observer Status: %s",
                        fogDevice.getStreamingObserver() != null ? "ACTIVE" : "INACTIVE"));
            }
        }
    }

    private static void printCacheStatistics() {
        logger.info("\n[CACHE STATISTICS]");

        for (int i = 0; i < fogDevices.size(); i++) {
            RLFogDevice fogDevice = fogDevices.get(i);

            Map<String, Object> cacheStats = fogDevice.getCacheStatistics();
            logger.info(String.format("\nFog Node %d: %s", i, fogDevice.getName()));
            logger.info(String.format("  Cache Size: %s / %s", cacheStats.get("cacheSize"),
                    cacheStats.get("maxCacheSize")));
            logger.info(String.format("  Cache Hits: %s", cacheStats.get("cacheHitCount")));
            logger.info(String.format("  Cache Misses: %s", cacheStats.get("cacheMissCount")));
            logger.info(String.format("  Cache Hit Rate: %.2f%%",
                    ((Double) cacheStats.get("cacheHitRate")) * 100));
        }
    }
}
