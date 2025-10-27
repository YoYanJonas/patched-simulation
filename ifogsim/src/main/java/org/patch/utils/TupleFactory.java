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
     * @param task     The proto task
     * @param deviceId The device ID where the task originates
     * @return Tuple or null if creation failed
     */
    public static Tuple createFromProtoTask(org.patch.proto.IfogsimCommon.Task task, int deviceId) {
        try {
            // Use iFogSim's proper tuple creation pattern
            Tuple tuple = new Tuple(
                    "scheduler-app", // appId - scheduler-generated tasks
                    FogUtils.generateTupleId(), // cloudletId - use iFogSim's ID generator
                    Tuple.UP, // direction - default to UP
                    task.getExecutionTime(), // cloudletLength - use execution time
                    1, // pesNumber - not in proto, use 1
                    task.getMemoryRequirement(), // cloudletFileSize - use memory requirement
                    task.getCpuRequirement(), // cloudletOutputSize - use CPU requirement
                    new UtilizationModelFull(), // utilizationModelCpu
                    new UtilizationModelFull(), // utilizationModelRam
                    new UtilizationModelFull() // utilizationModelBw
            );

            // Set properties following iFogSim conventions
            tuple.setTupleType("SCHEDULER_TASK"); // Specific type for scheduler tasks
            tuple.setDestModuleName("scheduler-module"); // Will be updated by actual processing
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
