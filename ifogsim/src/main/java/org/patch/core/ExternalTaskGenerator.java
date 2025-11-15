package org.patch.core;

import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.entities.Tuple;
import org.fog.utils.FogEvents;
import org.fog.utils.Config;
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

    // Repeated task generation support
    private java.util.Random random = new java.util.Random();
    private java.util.Map<String, Integer> taskPatternToId = new java.util.HashMap<>(); // Pattern -> TaskId for reuse
    // Repeated task probability - configurable, default 0.6 (60%) for realistic IoT scenarios
    // IoT systems typically have high repetition: sensors send similar data repeatedly
    private double repeatedTaskProbability;
    // Calculate max unique patterns from config: CPU options × Memory options
    // Default: 6 CPU × 2 Memory = 12 patterns (will start reuse after seeing all patterns)
    private int maxUniqueTasks;

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
        
        // Load repeated task probability from config (default: 0.6 = 60% for realistic IoT scenarios)
        this.repeatedTaskProbability = EnhancedConfigurationLoader.getExternalTaskConfigDouble(
            "external-tasks.parameters.repeated-task-probability", 0.6);
        
        // Calculate max unique patterns from config (after config is loaded)
        this.maxUniqueTasks = calculateMaxUniquePatterns();

        // Schedule task generation if rate is specified
        if (taskGenerationRate > 0) {
            long initialDelayMs = EnhancedConfigurationLoader.getExternalTaskConfigLong(
                    "external.tasks.generation.initial.delay", 1000);
            // Convert milliseconds to seconds for CloudSim (CloudSim uses seconds as time unit)
            double initialDelaySeconds = initialDelayMs / 1000.0;
            schedule(getId(), initialDelaySeconds, GENERATE_EXTERNAL_TASK);
            logger.info(String.format("Scheduled first external task generation at time %.2f seconds (from %d ms delay)",
                    initialDelaySeconds, initialDelayMs));
        }

        logger.info("External task generator created - will send tasks to cloud device " + cloudDeviceId
                + " for RL allocation");
        logger.info(String.format("Repeated task generation: maxUniquePatterns=%d, reuseProbability=%.1f%%", 
            maxUniqueTasks, repeatedTaskProbability * 100));
    }
    
    /**
     * Calculate maximum unique patterns from CPU and Memory options
     * Formula: CPU options count × Memory options count
     */
    private int calculateMaxUniquePatterns() {
        java.util.List<Long> cpuOptions = EnhancedConfigurationLoader.getExternalTaskConfigList("external-tasks.parameters.cpu.options");
        java.util.List<Long> memoryOptions = EnhancedConfigurationLoader.getExternalTaskConfigList("external-tasks.parameters.memory.options");
        
        int cpuCount = (cpuOptions != null && !cpuOptions.isEmpty()) ? cpuOptions.size() : 1;
        int memoryCount = (memoryOptions != null && !memoryOptions.isEmpty()) ? memoryOptions.size() : 1;
        
        int maxPatterns = cpuCount * memoryCount;
        // Use exact number: once map size = maxPatterns, we've seen all unique patterns
        return maxPatterns;
    }

    @Override
    public void startEntity() {
        logger.info("External task generator started - generating tasks at " + taskGenerationRate + " tasks/second");
        System.out.println(String.format(
                "[FLOW-EXTERNAL-GEN-START] Time: %.2f - External task generator started (ID:%d) - Rate: %.2f tasks/sec, CloudDevice: %d",
                CloudSim.clock(), getId(), taskGenerationRate, cloudDeviceId));
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case GENERATE_EXTERNAL_TASK:
                double currentTime = CloudSim.clock();
                double simulationTime = Config.SIMULATION_TIME;
                double maxSimulationTime = Config.MAX_SIMULATION_TIME;

                // Stop generating NEW tasks once we've reached SIMULATION_TIME
                // MAX_SIMULATION_TIME is a hard cap (should not reach here if working correctly)
                if (currentTime >= simulationTime) {
                    logger.info(String.format(
                            "External task generator stopping - Current time %.2f >= SIMULATION_TIME %.2f. Generated %d tasks total.",
                            currentTime, simulationTime, taskCounter));
                    return;
                }

                // Safety check: Also stop if we somehow exceeded MAX_SIMULATION_TIME
                if (currentTime >= maxSimulationTime) {
                    logger.warning(String.format(
                            "External task generator HARD STOP - Current time %.2f >= MAX_SIMULATION_TIME %.2f. Generated %d tasks total.",
                            currentTime, maxSimulationTime, taskCounter));
                    return;
                }

                generateExternalTask();
                // Schedule next generation based on rate
                // CloudSim time units are in seconds, so convert tasks/sec to seconds delay
                if (taskGenerationRate > 0) {
                    double nextGenerationTime = 1.0 / taskGenerationRate; // Time between tasks in seconds
                    double nextEventTime = currentTime + nextGenerationTime;

                    // Only schedule if the next event would occur before SIMULATION_TIME
                    if (nextEventTime < simulationTime) {
                        schedule(getId(), nextGenerationTime, GENERATE_EXTERNAL_TASK);
                    } else {
                        logger.info(String.format(
                                "External task generator stopping scheduling - Next event time %.2f >= SIMULATION_TIME %.2f. Generated %d tasks total.",
                                nextEventTime, simulationTime, taskCounter));
                    }
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
        double currentTime = CloudSim.clock();
        
        // Create a new external task
        ExternalTask task = createRandomExternalTask();

        // Convert to tuple
        Tuple tuple = convertToTuple(task);

        System.out.println(String.format(
                "[FLOW-EXTERNAL-GEN-CREATE] Time: %.2f - Generated external task %d (AppId:%s, CPU:%d, Mem:%d, Out:%d) - Will send to cloud device %d (Total generated: %d)",
                currentTime, task.getId(), task.getAppId(), task.getCloudletLength(), 
                task.getInputSize(), task.getOutputSize(), cloudDeviceId, taskCounter));

        System.out.println(String.format(
                "[FLOW-EXTERNAL-GEN-SEND] Time: %.2f - Sending external task %d to cloud device %d for RL allocation (TaskID: %d)",
                CloudSim.clock(), task.getId(), cloudDeviceId, task.getId()));

        // Send directly to cloud device for RL allocation
        sendNow(cloudDeviceId, FogEvents.TUPLE_ARRIVAL, tuple);

        logger.info("Generated external task " + task.getId() + " and sent to cloud device " + cloudDeviceId
                + " for allocation");
        
        System.out.println(String.format(
                "[FLOW-EXTERNAL-GEN-SENT] Time: %.2f - External task %d successfully sent to cloud device %d for allocation (Total generated: %d)",
                CloudSim.clock(), task.getId(), cloudDeviceId, taskCounter));
    }

    /**
     * Create a random external task for simulation
     * Supports repeated tasks: reuses task IDs with same CPU/memory patterns
     */
    private ExternalTask createRandomExternalTask() {
        taskCounter++;

        // Generate random task parameters using configuration
        long cloudletLength;
        long cloudletFileSize;
        
        // Try to get CPU from options first, fallback to min/max range
        java.util.List<Long> cpuOptions = EnhancedConfigurationLoader.getExternalTaskConfigList("external-tasks.parameters.cpu.options");
        if (cpuOptions != null && !cpuOptions.isEmpty()) {
            // Use discrete options
            cloudletLength = cpuOptions.get(random.nextInt(cpuOptions.size()));
        } else {
            // Fallback to range-based (backward compatibility)
            long cpuMin = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.cpu.min", 1000);
            long cpuMax = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.cpu.max", 10000);
            cloudletLength = cpuMin + (long) (random.nextDouble() * (cpuMax - cpuMin));
        }

        // Try to get Memory from options first, fallback to min/max range
        java.util.List<Long> memoryOptions = EnhancedConfigurationLoader.getExternalTaskConfigList("external-tasks.parameters.memory.options");
        if (memoryOptions != null && !memoryOptions.isEmpty()) {
            // Use discrete options
            cloudletFileSize = memoryOptions.get(random.nextInt(memoryOptions.size()));
        } else {
            // Fallback to range-based (backward compatibility)
            long memoryMin = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.memory.min", 100);
            long memoryMax = EnhancedConfigurationLoader.getExternalTaskConfigLong("external.tasks.parameters.memory.max", 1000);
            cloudletFileSize = memoryMin + (long) (random.nextDouble() * (memoryMax - memoryMin));
        }
        
        // Create pattern key for task reuse
        String patternKey = String.format("%d-%d", cloudletLength, cloudletFileSize);
        int taskId;
        
        // Decide whether to reuse existing task ID or create new one
        boolean shouldReuse = random.nextDouble() < repeatedTaskProbability && 
                             taskPatternToId.size() >= maxUniqueTasks &&
                             taskPatternToId.containsKey(patternKey);
        
        if (shouldReuse) {
            // Reuse existing task ID for this pattern
            taskId = taskPatternToId.get(patternKey);
            logger.info(String.format("[REPEATED-TASK] Reusing task ID %d for pattern (CPU:%d, Mem:%d)", 
                taskId, cloudletLength, cloudletFileSize));
        } else {
            // Create new task ID
            taskId = taskCounter;
            taskPatternToId.put(patternKey, taskId);
            if (taskPatternToId.size() > maxUniqueTasks * 2) {
                // Cleanup: remove oldest entries to prevent memory growth
                java.util.Iterator<java.util.Map.Entry<String, Integer>> it = taskPatternToId.entrySet().iterator();
                int toRemove = taskPatternToId.size() - maxUniqueTasks;
                while (it.hasNext() && toRemove > 0) {
                    it.next();
                    it.remove();
                    toRemove--;
                }
            }
        }
        
        int appId = EnhancedConfigurationLoader.getExternalTaskConfigInt("external.tasks.parameters.app.id", 1);
        int userId = EnhancedConfigurationLoader.getExternalTaskConfigInt("external.tasks.parameters.user.id", 1);

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
        // 
        // The application only has "processing_module" and "aggregation_module" deployed
        String moduleName = EnhancedConfigurationLoader.getExternalTaskConfig("external.tasks.properties.module.name",
                "processing_module");
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