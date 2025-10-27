package org.patch.integration;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.entities.FogDevice;
import org.fog.entities.Tuple;
import org.fog.utils.FogEvents;
import org.patch.devices.RLFogDevice;
import org.patch.devices.RLCloudDevice;
import org.patch.controller.RLController;
import org.patch.utils.ExtendedFogEvents;
import org.patch.utils.RLConfig;
import org.patch.config.EnhancedConfigurationLoader;
import org.patch.utils.RLStatisticsManager;

import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * RL Simulation Bridge for iFogSim Integration
 * 
 * This class provides a comprehensive bridge between RL components and
 * iFogSim's
 * core event system. It handles event routing, coordination, and ensures proper
 * integration between RL-aware components and the simulation engine.
 * 
 * Key Features:
 * - Event routing and coordination
 * - RL component lifecycle management
 * - Simulation state synchronization
 * - Error handling and recovery
 * - Performance monitoring
 * 
 * @author Younes Shafiee
 */
public class RLSimulationBridge {
    private static final Logger logger = Logger.getLogger(RLSimulationBridge.class.getName());

    // Simulation components
    private List<FogDevice> fogDevices = new ArrayList<>();
    private RLController rlController;
    private RLCloudDevice cloudDevice;

    // Event coordination
    private Map<String, Object> simulationState = new HashMap<>();

    // Statistics manager for centralized tracking
    private RLStatisticsManager statisticsManager;

    /**
     * Initialize the RL simulation bridge
     */
    public RLSimulationBridge() {
        logger.info("Initializing RL Simulation Bridge");
        this.statisticsManager = RLStatisticsManager.getInstance();
        initializeSimulationState();
    }

    /**
     * Initialize the RL simulation bridge with components
     */
    public RLSimulationBridge(List<FogDevice> fogDevices, RLController controller, RLCloudDevice cloud) {
        this.fogDevices = fogDevices != null ? fogDevices : new ArrayList<>();
        this.rlController = controller;
        this.cloudDevice = cloud;
        this.statisticsManager = RLStatisticsManager.getInstance();

        logger.info("Initializing RL Simulation Bridge with " + this.fogDevices.size() + " fog devices");
        initializeSimulationState();
    }

    /**
     * Initialize simulation state
     */
    private void initializeSimulationState() {
        simulationState.put("simulationStartTime", CloudSim.clock());
        simulationState.put("rlEnabled", RLConfig.isFogRLEnabled() || RLConfig.isCloudRLEnabled());
        simulationState.put("externalTasksEnabled",
                EnhancedConfigurationLoader.getSimulationConfigBoolean("simulation.external-tasks.enabled", false));
        simulationState.put("totalDevices", fogDevices.size());
        simulationState.put("bridgeInitialized", true);

        logger.info("Simulation state initialized");
    }

    /**
     * Process simulation event with RL integration
     * This method properly integrates with iFogSim's event system
     */
    public void processEvent(SimEvent event) {
        try {
            statisticsManager.incrementEventsProcessed();

            // Route event to appropriate handler based on iFogSim event system
            switch (event.getTag()) {
                case FogEvents.TUPLE_ARRIVAL:
                    handleTupleArrival(event);
                    break;
                case FogEvents.TUPLE_FINISHED:
                    handleTupleFinished(event);
                    break;
                case FogEvents.APP_SUBMIT:
                    handleAppSubmit(event);
                    break;
                case FogEvents.CONTROLLER_RESOURCE_MANAGE:
                    handleResourceManagement(event);
                    break;
                case FogEvents.RESOURCE_MGMT:
                    handleResourceManagement(event);
                    break;
                case ExtendedFogEvents.ALLOC_REQUEST_SENT:
                    handleAllocationRequest(event);
                    break;
                case ExtendedFogEvents.ALLOC_RESPONSE_RECEIVED:
                    handleAllocationResponse(event);
                    break;
                case ExtendedFogEvents.SCHEDULER_REQUEST_SENT:
                    handleSchedulerRequest(event);
                    break;
                case ExtendedFogEvents.SCHEDULER_CACHE_HIT:
                    handleSchedulerCacheHit(event);
                    break;
                case ExtendedFogEvents.SCHEDULER_CACHE_MISS:
                    handleSchedulerCacheMiss(event);
                    break;
                case ExtendedFogEvents.TASK_COMPLETE:
                    handleTaskComplete(event);
                    break;
                case ExtendedFogEvents.TASK_FORWARDED:
                    handleTaskForwarded(event);
                    break;
                case ExtendedFogEvents.METRICS_COLLECTION:
                    handleMetricsCollection(event);
                    break;
                case ExtendedFogEvents.TASK_COMPLETION_CHECK:
                    handleTaskCompletionCheck(event);
                    break;
                default:
                    handleDefaultEvent(event);
                    break;
            }

        } catch (Exception e) {
            statisticsManager.incrementErrorCount();
            logger.log(Level.SEVERE, "Error processing event: " + event.getTag(), e);
        }
    }

    /**
     * Handle tuple arrival events - properly integrated with iFogSim
     */
    private void handleTupleArrival(SimEvent event) {
        Tuple tuple = (Tuple) event.getData();
        FogDevice destination = (FogDevice) CloudSim.getEntity(event.getDestination());

        if (destination instanceof RLFogDevice) {
            RLFogDevice rlDevice = (RLFogDevice) destination;
            rlDevice.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        } else if (destination instanceof RLCloudDevice) {
            RLCloudDevice rlCloud = (RLCloudDevice) destination;
            rlCloud.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        } else {
            // Handle regular iFogSim devices
            destination.processEvent(event);
        }

        logger.fine("Tuple arrival processed for device: " + destination.getName());
    }

    /**
     * Handle tuple finished events - properly integrated with iFogSim
     */
    private void handleTupleFinished(SimEvent event) {
        Tuple tuple = (Tuple) event.getData();

        // Notify controller for RL learning
        if (rlController != null) {
            rlController.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        // Update task processing statistics
        statisticsManager.incrementTasksProcessed();
        if (tuple.getFinishTime() > 0) {
            statisticsManager.incrementSuccessfulTasks();
        } else {
            statisticsManager.incrementFailedTasks();
        }

        logger.fine("Tuple finished processed: " + tuple.getCloudletId());
    }

    /**
     * Handle application submission events - properly integrated with iFogSim
     */
    private void handleAppSubmit(SimEvent event) {
        if (rlController != null) {
            rlController.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        logger.fine("Application submission processed");
    }

    /**
     * Handle resource management events - properly integrated with iFogSim
     */
    private void handleResourceManagement(SimEvent event) {
        if (rlController != null) {
            rlController.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        logger.fine("Resource management processed");
    }

    /**
     * Handle allocation request events
     */
    private void handleAllocationRequest(SimEvent event) {
        if (cloudDevice != null) {
            cloudDevice.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        logger.fine("Allocation request processed");
    }

    /**
     * Handle allocation response events
     */
    private void handleAllocationResponse(SimEvent event) {
        if (cloudDevice != null) {
            cloudDevice.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        logger.fine("Allocation response processed");
    }

    /**
     * Handle scheduler request events
     */
    private void handleSchedulerRequest(SimEvent event) {
        FogDevice source = (FogDevice) CloudSim.getEntity(event.getSource());

        if (source instanceof RLFogDevice) {
            RLFogDevice rlDevice = (RLFogDevice) source;
            rlDevice.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        logger.fine("Scheduler request processed");
    }

    /**
     * Handle scheduler cache hit events
     */
    private void handleSchedulerCacheHit(SimEvent event) {
        FogDevice source = (FogDevice) CloudSim.getEntity(event.getSource());

        if (source instanceof RLFogDevice) {
            RLFogDevice rlDevice = (RLFogDevice) source;
            rlDevice.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        logger.fine("Scheduler cache hit processed");
    }

    /**
     * Handle scheduler cache miss events
     */
    private void handleSchedulerCacheMiss(SimEvent event) {
        FogDevice source = (FogDevice) CloudSim.getEntity(event.getSource());

        if (source instanceof RLFogDevice) {
            RLFogDevice rlDevice = (RLFogDevice) source;
            rlDevice.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        logger.fine("Scheduler cache miss processed");
    }

    /**
     * Handle task complete events
     */
    private void handleTaskComplete(SimEvent event) {
        // Notify all RL components about task completion
        for (FogDevice device : fogDevices) {
            if (device instanceof RLFogDevice) {
                RLFogDevice rlDevice = (RLFogDevice) device;
                rlDevice.processEvent(event);
            }
        }

        if (cloudDevice != null) {
            cloudDevice.processEvent(event);
        }

        statisticsManager.incrementRLEventsProcessed();
        logger.fine("Task complete processed");
    }

    /**
     * Handle task forwarded events
     */
    private void handleTaskForwarded(SimEvent event) {
        if (cloudDevice != null) {
            cloudDevice.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        logger.fine("Task forwarded processed");
    }

    /**
     * Handle metrics collection events
     */
    private void handleMetricsCollection(SimEvent event) {
        // Collect metrics from all RL components
        for (FogDevice device : fogDevices) {
            if (device instanceof RLFogDevice) {
                RLFogDevice rlDevice = (RLFogDevice) device;
                rlDevice.processEvent(event);
            }
        }

        if (cloudDevice != null) {
            cloudDevice.processEvent(event);
        }

        if (rlController != null) {
            rlController.processEvent(event);
        }

        statisticsManager.incrementRLEventsProcessed();
        logger.fine("Metrics collection processed");
    }

    /**
     * Handle task completion check events
     */
    private void handleTaskCompletionCheck(SimEvent event) {
        FogDevice source = (FogDevice) CloudSim.getEntity(event.getSource());

        if (source instanceof RLFogDevice) {
            RLFogDevice rlDevice = (RLFogDevice) source;
            rlDevice.processEvent(event);
            statisticsManager.incrementRLEventsProcessed();
        }

        logger.fine("Task completion check processed");
    }

    /**
     * Handle default events
     */
    private void handleDefaultEvent(SimEvent event) {
        // Route to appropriate component based on event source/destination
        FogDevice source = (FogDevice) CloudSim.getEntity(event.getSource());
        FogDevice destination = (FogDevice) CloudSim.getEntity(event.getDestination());

        if (destination instanceof RLFogDevice) {
            ((RLFogDevice) destination).processEvent(event);
        } else if (destination instanceof RLCloudDevice) {
            ((RLCloudDevice) destination).processEvent(event);
        }

        logger.fine("Default event processed: " + event.getTag());
    }

    /**
     * Schedule RL-specific events
     */
    public void scheduleRLEvent(int entityId, double delay, int eventType, Object data) {
        try {
            CloudSim.send(entityId, entityId, delay, eventType, data);
            logger.fine("RL event scheduled: " + eventType + " for entity: " + entityId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to schedule RL event", e);
        }
    }

    /**
     * Get simulation statistics
     */
    public Map<String, Object> getSimulationStatistics() {
        Map<String, Object> stats = statisticsManager.getComprehensiveStatistics();

        // Add bridge-specific statistics
        stats.put("fogDevicesCount", fogDevices.size());
        stats.put("rlEnabled", RLConfig.isFogRLEnabled() || RLConfig.isCloudRLEnabled());
        stats.put("bridgeInitialized", simulationState.get("bridgeInitialized"));
        stats.put("externalTasksEnabled", simulationState.get("externalTasksEnabled"));

        return stats;
    }

    /**
     * Reset simulation statistics
     */
    public void resetStatistics() {
        statisticsManager.resetStatistics();
        simulationState.put("simulationStartTime", CloudSim.clock());

        logger.info("Simulation statistics reset");
    }

    // Getters and setters
    public List<FogDevice> getFogDevices() {
        return fogDevices;
    }

    public void setFogDevices(List<FogDevice> fogDevices) {
        this.fogDevices = fogDevices;
    }

    public RLController getRLController() {
        return rlController;
    }

    public void setRLController(RLController rlController) {
        this.rlController = rlController;
    }

    public RLCloudDevice getCloudDevice() {
        return cloudDevice;
    }

    public void setCloudDevice(RLCloudDevice cloudDevice) {
        this.cloudDevice = cloudDevice;
    }
}
