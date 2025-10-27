package org.patch.core;

import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.entities.Tuple;
import org.fog.utils.FogEvents;
import org.patch.config.EnhancedConfigurationLoader;
import org.patch.utils.TupleFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Entity for generating external tasks and sending them to cloud for RL
 * allocation
 * This integrates with iFogSim's core architecture:
 * 1. Generates external tasks
 * 2. Sends tasks to cloud device
 * 3. Cloud uses RL allocation to decide fog node
 * 4. Cloud forwards task to allocated fog node
 */
public class ExternalTaskGenerator extends SimEntity {
    private static final Logger logger = Logger.getLogger(ExternalTaskGenerator.class.getName());

    // Custom event types
    private static final int GENERATE_EXTERNAL_TASK = 30001;

    // Cloud device ID to send tasks to for allocation
    private int cloudDeviceId;

    // Task generation parameters
    private double taskGenerationRate; // tasks per second
    private int taskCounter = 0;

    /**
     * Constructor for external task generator
     * 
     * @param name               Entity name
     * @param cloudDeviceId      Cloud device ID to send tasks to for allocation
     * @param taskGenerationRate Tasks per second to generate
     */
    public ExternalTaskGenerator(String name, int cloudDeviceId, double taskGenerationRate) {
        super(name);
        this.cloudDeviceId = cloudDeviceId;
        this.taskGenerationRate = taskGenerationRate;

        // Initialize configuration
        EnhancedConfigurationLoader.initialize();

        // Schedule task generation if rate is specified
        if (taskGenerationRate > 0) {
            long initialDelay = EnhancedConfigurationLoader.getExternalTaskConfigLong(
                    "external.tasks.generation.initial.delay", 1000);
            schedule(getId(), initialDelay, GENERATE_EXTERNAL_TASK);
        }

        logger.info("External task generator created - will send tasks to cloud device " + cloudDeviceId
                + " for RL allocation");
    }

    @Override
    public void startEntity() {
        logger.info("External task generator started - generating tasks at " + taskGenerationRate + " tasks/second");
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case GENERATE_EXTERNAL_TASK:
                generateExternalTask();
                // Schedule next generation based on rate
                if (taskGenerationRate > 0) {
                    double nextGenerationTime = 1000.0 / taskGenerationRate; // Convert to milliseconds
                    schedule(getId(), nextGenerationTime, GENERATE_EXTERNAL_TASK);
                }
                break;
        }
    }

    @Override
    public void shutdownEntity() {
        logger.info("External task generator stopped - generated " + taskCounter + " tasks total");
    }

    /**
     * Generate a new external task and send it to cloud for allocation
     */
    private void generateExternalTask() {
        // Create a new external task
        ExternalTask task = createRandomExternalTask();

        // Convert to tuple
        Tuple tuple = convertToTuple(task);

        // Send directly to cloud device for RL allocation
        sendNow(cloudDeviceId, FogEvents.TUPLE_ARRIVAL, tuple);

        logger.info("Generated external task " + task.getId() + " and sent to cloud device " + cloudDeviceId
                + " for allocation");
    }

    /**
     * Create a random external task for simulation
     */
    private ExternalTask createRandomExternalTask() {
        taskCounter++;

        // Generate random task parameters using configuration
        int taskId = taskCounter;
        int appId = EnhancedConfigurationLoader.getExternalTaskConfigInt("external.tasks.parameters.app.id", 1);
        int userId = EnhancedConfigurationLoader.getExternalTaskConfigInt("external.tasks.parameters.user.id", 1);

        // Get CPU range from configuration
        long cpuMin = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.cpu.min", 1000);
        long cpuMax = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.cpu.max", 10000);
        long cloudletLength = cpuMin + (long) (Math.random() * (cpuMax - cpuMin));

        // Get memory range from configuration
        long memoryMin = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.memory.min",
                100);
        long memoryMax = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.memory.max",
                1000);
        long cloudletFileSize = memoryMin + (long) (Math.random() * (memoryMax - memoryMin));

        // Get output range from configuration
        long outputMin = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.output.min",
                50);
        long outputMax = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.output.max",
                500);
        long cloudletOutputSize = outputMin + (long) (Math.random() * (outputMax - outputMin));

        int numberOfPes = EnhancedConfigurationLoader
                .getExternalTaskConfigInt("external.tasks.parameters.number.of.pes", 1);

        return new ExternalTask(taskId, appId, userId, cloudletLength,
                cloudletFileSize, cloudletOutputSize, numberOfPes);
    }

    /**
     * Convert external task to iFogSim tuple
     */
    private Tuple convertToTuple(ExternalTask task) {
        // Get configuration values
        String tupleType = EnhancedConfigurationLoader.getExternalTaskConfig("external.tasks.properties.tuple.type",
                "EXTERNAL");
        String moduleName = EnhancedConfigurationLoader.getExternalTaskConfig("external.tasks.properties.module.name",
                "external_task");
        String direction = EnhancedConfigurationLoader.getExternalTaskConfig("external.tasks.properties.direction",
                "DOWN");

        // Convert direction string to int
        int directionInt = "DOWN".equals(direction) ? Tuple.DOWN : Tuple.UP;

        // Use TupleFactory to create tuple
        Tuple tuple = TupleFactory.createFromExternalTask(
                task.getAppId(),
                task.getId(),
                directionInt,
                task.getCloudletLength(),
                task.getInputSize(),
                task.getOutputSize(),
                tupleType,
                moduleName,
                task.getSourceDeviceId(),
                task.getDestDeviceId());

        // For custom properties, we'll use the traversedMicroservices map
        // Properties are expected to be strings mapped to integer device IDs
        for (Map.Entry<String, Object> entry : task.getProperties().entrySet()) {
            if (entry.getValue() instanceof Integer) {
                tuple.addToTraversedMicroservices((Integer) entry.getValue(), entry.getKey());
            }
        }

        return tuple;
    }

    /**
     * Get total tasks generated
     */
    public int getTotalTasksGenerated() {
        return taskCounter;
    }

    /**
     * Class representing an external task
     */
    public static class ExternalTask {
        private int id;
        private String appId;
        private long cloudletLength;
        private long inputSize;
        private long outputSize;
        private int sourceDeviceId;
        private int destDeviceId;
        private String moduleName;
        private String tupleType;
        private int direction;
        private int userId;
        private Map<String, Object> properties = new HashMap<>();

        // Constructor
        public ExternalTask(int id, int appId, int userId, long cloudletLength,
                long inputSize, long outputSize, int numberOfPes) {
            this.id = id;
            this.appId = String.valueOf(appId);
            this.userId = userId;
            this.cloudletLength = cloudletLength;
            this.inputSize = inputSize;
            this.outputSize = outputSize;
            this.sourceDeviceId = -1; // External source
            this.destDeviceId = -1; // To be determined by allocation
            this.moduleName = "external_task";
            this.tupleType = "EXTERNAL";
            this.direction = Tuple.DOWN; // Coming from cloud
        }

        // Getters and setters
        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public long getCloudletLength() {
            return cloudletLength;
        }

        public void setCloudletLength(long cloudletLength) {
            this.cloudletLength = cloudletLength;
        }

        public long getInputSize() {
            return inputSize;
        }

        public void setInputSize(long inputSize) {
            this.inputSize = inputSize;
        }

        public long getOutputSize() {
            return outputSize;
        }

        public void setOutputSize(long outputSize) {
            this.outputSize = outputSize;
        }

        public int getSourceDeviceId() {
            return sourceDeviceId;
        }

        public void setSourceDeviceId(int sourceDeviceId) {
            this.sourceDeviceId = sourceDeviceId;
        }

        public int getDestDeviceId() {
            return destDeviceId;
        }

        public void setDestDeviceId(int destDeviceId) {
            this.destDeviceId = destDeviceId;
        }

        public String getModuleName() {
            return moduleName;
        }

        public void setModuleName(String moduleName) {
            this.moduleName = moduleName;
        }

        public String getTupleType() {
            return tupleType;
        }

        public void setTupleType(String tupleType) {
            this.tupleType = tupleType;
        }

        public int getDirection() {
            return direction;
        }

        public void setDirection(int direction) {
            this.direction = direction;
        }

        public int getUserId() {
            return userId;
        }

        public void setUserId(int userId) {
            this.userId = userId;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperty(String key, Object value) {
            properties.put(key, value);
        }
    }

}