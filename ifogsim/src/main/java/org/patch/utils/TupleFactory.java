package org.patch.utils;

import org.fog.entities.Tuple;
import org.fog.utils.FogUtils;
import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Factory class for creating iFogSim-compatible Tuple objects
 * 
 * This class centralizes tuple creation logic to eliminate duplication
 * across the patch module and ensure consistent iFogSim integration.
 * 
 * @author Younes Shafiee
 */
public class TupleFactory {

    private static final Logger logger = Logger.getLogger(TupleFactory.class.getName());

    /**
     * Create a tuple from proto Task (for scheduler tasks)
     * 
     * CRITICAL: Extracts cloudlet_id from task metadata to preserve the original
     * unique instance identifier. This ensures completion reports can match tasks
     * stored in the server's scheduledTasks map.
     * 
     * @param task     The proto task (must have cloudlet_id in metadata)
     * @param deviceId The device ID where the task originates
     * @return Tuple or null if creation failed (e.g., missing cloudlet_id)
     */
    public static Tuple createFromProtoTask(org.patch.proto.IfogsimCommon.Task task, int deviceId) {
        try {
            // CRITICAL: Extract cloudlet_id from metadata (required for ACK matching)
            // Server stores tasks in scheduledTasks using cloudletId as key
            // Completion reports must use the same cloudletId to find the task
            int cloudletId;
            if (task.getMetadataMap() != null && task.getMetadataMap().containsKey("cloudlet_id")) {
                String cloudletIdStr = task.getMetadataMap().get("cloudlet_id");
                if (cloudletIdStr != null && !cloudletIdStr.isEmpty()) {
                    try {
                        cloudletId = Integer.parseInt(cloudletIdStr);
                        logger.fine(String.format(
                                "[TUPLE-FACTORY] Extracted cloudletId=%d from metadata for TaskId=%s",
                                cloudletId, task.getTaskId()));
                    } catch (NumberFormatException e) {
                        logger.severe(String.format(
                                "[TUPLE-FACTORY-ERROR] Failed to parse cloudlet_id='%s' from metadata for TaskId=%s: %s",
                                cloudletIdStr, task.getTaskId(), e.getMessage()));
                        return null; // Cannot create tuple without valid cloudletId
                    }
                } else {
                    logger.severe(String.format(
                            "[TUPLE-FACTORY-ERROR] cloudlet_id in metadata is null or empty for TaskId=%s",
                            task.getTaskId()));
                    return null; // Cannot create tuple without cloudletId
                }
            } else {
                logger.severe(String.format(
                        "[TUPLE-FACTORY-ERROR] cloudlet_id not found in metadata for TaskId=%s. " +
                                "Metadata is required for task tracking. Task will be skipped.",
                        task.getTaskId()));
                return null; // Cannot create tuple without cloudletId - this should never happen
            }

            // Convert cpu_requirement (MI) to cloudletLength (MI) - direct mapping
            long cloudletLength = task.getCpuRequirement();
            if (cloudletLength <= 0) {
                logger.warning(String.format(
                        "[TUPLE-FACTORY] TaskId=%s has invalid cpu_requirement=%d, using default 1000 MI",
                        task.getTaskId(), cloudletLength));
                cloudletLength = 1000; // Default minimum
            }

            // Convert memory_requirement (MB) to cloudletFileSize (bytes)
            long cloudletFileSize = task.getMemoryRequirement() * 1024 * 1024;
            if (cloudletFileSize <= 0) {
                logger.warning(String.format(
                        "[TUPLE-FACTORY] TaskId=%s has invalid memory_requirement=%d MB, using default 1 MB",
                        task.getTaskId(), task.getMemoryRequirement()));
                cloudletFileSize = 1024 * 1024; // Default 1 MB in bytes
            }

            // Use output_size from proto Task (in bytes)
            long cloudletOutputSize = task.getOutputSize();
            if (cloudletOutputSize <= 0) {
                // Fallback: estimate from memory_requirement if output_size not provided
                logger.warning(String.format(
                        "[TUPLE-FACTORY] TaskId=%s has invalid output_size=%d, estimating from memory_requirement",
                        task.getTaskId(), cloudletOutputSize));
                cloudletOutputSize = task.getMemoryRequirement() * 1024 * 1024; // Estimate from input
                if (cloudletOutputSize <= 0) {
                    cloudletOutputSize = 1024 * 1024; // Default 1 MB in bytes
                }
            }

            // Use iFogSim's proper tuple creation pattern with extracted cloudletId
            Tuple tuple = new Tuple(
                    "scheduler-app", // appId - scheduler-generated tasks
                    cloudletId, // ✅ Use extracted cloudletId from metadata (preserves original ID)
                    Tuple.UP, // direction - default to UP
                    cloudletLength, // ✅ cpu_requirement (MI) - represents computational work
                    1, // pesNumber - not in proto, use 1
                    cloudletFileSize, // ✅ memory_requirement converted to bytes
                    cloudletOutputSize, // ⚠️ Estimated from memory_requirement
                    new UtilizationModelFull(), // utilizationModelCpu
                    new UtilizationModelFull(), // utilizationModelRam
                    new UtilizationModelFull() // utilizationModelBw
            );

            // Set properties following iFogSim conventions
            tuple.setTupleType("SCHEDULER_TASK"); // Specific type for scheduler tasks
            //
            // The application only has "processing_module" and "aggregation_module"
            // deployed
            tuple.setDestModuleName("processing_module"); // Use existing deployed module
            tuple.setSrcModuleName("scheduler"); // Source is scheduler
            tuple.setDirection(Tuple.UP); // Default direction
            tuple.setAppId("scheduler-app"); // Consistent app ID
            tuple.setUserId(0); // Default user ID
            tuple.setSourceDeviceId(deviceId); // Set source device

            return tuple;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error creating tuple from proto task", e);
            return null;
        }
    }

    /**
     * Create a tuple from external task (for external task generator)
     * 
     * @param appId          Application ID
     * @param taskId         Task ID
     * @param direction      Tuple direction
     * @param cloudletLength Cloudlet length
     * @param inputSize      Input size
     * @param outputSize     Output size
     * @param tupleType      Tuple type
     * @param moduleName     Module name
     * @param sourceDeviceId Source device ID
     * @param destDeviceId   Destination device ID
     * @return Tuple or null if creation failed
     */
    public static Tuple createFromExternalTask(String appId, int taskId, int direction,
            long cloudletLength, long inputSize, long outputSize, String tupleType,
            String moduleName, int sourceDeviceId, int destDeviceId) {
        try {
            // Create utilization models
            UtilizationModel utilizationModel = new UtilizationModelFull();

            // Create the tuple with the appropriate constructor
            Tuple tuple = new Tuple(
                    appId, // appId
                    taskId, // cloudletId
                    direction, // direction
                    cloudletLength, // cloudletLength
                    1, // pesNumber (default to 1)
                    inputSize, // cloudletFileSize
                    outputSize, // cloudletOutputSize
                    utilizationModel, // utilizationModelCpu
                    utilizationModel, // utilizationModelRam
                    utilizationModel // utilizationModelBw
            );

            // Set additional attributes
            tuple.setTupleType(tupleType);
            tuple.setDestModuleName(moduleName);
            tuple.setSourceDeviceId(sourceDeviceId);
            tuple.setDestinationDeviceId(destDeviceId);

            // Set direction
            if (direction == Tuple.DOWN) {
                tuple.setDirection(Tuple.DOWN);
            } else if (direction == Tuple.UP) {
                tuple.setDirection(Tuple.UP);
            }

            return tuple;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error creating tuple from external task", e);
            return null;
        }
    }

    /**
     * Create a tuple for RL application edge processing
     * 
     * @param appId          Application ID
     * @param direction      Edge direction
     * @param cpuLength      CPU length
     * @param nwLength       Network length
     * @param outputSize     Output size
     * @param inputTuple     Input tuple for reference
     * @param destModule     Destination module name
     * @param srcModule      Source module name
     * @param tupleType      Tuple type
     * @param sourceModuleId Source module ID
     * @return Tuple or null if creation failed
     */
    public static Tuple createForRLEdge(String appId, int direction, long cpuLength,
            long nwLength, long outputSize, Tuple inputTuple, String destModule,
            String srcModule, String tupleType, int sourceModuleId) {
        try {
            // Create tuple with RL characteristics
            Tuple tuple = new Tuple(appId, FogUtils.generateTupleId(), direction,
                    cpuLength,
                    inputTuple.getNumberOfPes(),
                    nwLength,
                    outputSize,
                    inputTuple.getUtilizationModelCpu(),
                    inputTuple.getUtilizationModelRam(),
                    inputTuple.getUtilizationModelBw());

            // Set tuple properties
            tuple.setActualTupleId(inputTuple.getActualTupleId());
            tuple.setUserId(inputTuple.getUserId());
            tuple.setAppId(inputTuple.getAppId());
            tuple.setDestModuleName(destModule);
            tuple.setSrcModuleName(srcModule);
            tuple.setDirection(direction);
            tuple.setTupleType(tupleType);
            tuple.setSourceModuleId(sourceModuleId);
            tuple.setTraversedMicroservices(inputTuple.getTraversed());

            return tuple;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error creating tuple for RL edge", e);
            return null;
        }
    }

    /**
     * Create a basic tuple with minimal parameters
     * 
     * @param appId          Application ID
     * @param direction      Tuple direction
     * @param cloudletLength Cloudlet length
     * @param tupleType      Tuple type
     * @param destModule     Destination module
     * @param srcModule      Source module
     * @return Tuple or null if creation failed
     */
    public static Tuple createBasicTuple(String appId, int direction, long cloudletLength,
            String tupleType, String destModule, String srcModule) {
        try {
            Tuple tuple = new Tuple(
                    appId,
                    FogUtils.generateTupleId(),
                    direction,
                    cloudletLength,
                    1, // pesNumber
                    100, // cloudletFileSize
                    100, // cloudletOutputSize
                    new UtilizationModelFull(),
                    new UtilizationModelFull(),
                    new UtilizationModelFull());

            tuple.setTupleType(tupleType);
            tuple.setDestModuleName(destModule);
            tuple.setSrcModuleName(srcModule);
            tuple.setDirection(direction);

            return tuple;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error creating basic tuple", e);
            return null;
        }
    }
}
