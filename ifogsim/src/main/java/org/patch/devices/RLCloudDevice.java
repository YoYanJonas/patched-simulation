package org.patch.devices;

import org.fog.entities.FogDevice;
import org.fog.entities.Tuple;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.patch.client.AllocationClient;
import org.patch.utils.RLConfig;
import org.patch.utils.ServiceRegistry;
import org.patch.utils.ExtendedFogEvents;
import org.patch.config.EnhancedConfigurationLoader;
import org.patch.utils.RLStatisticsManager;
import org.cloudbus.cloudsim.power.models.PowerModel;
import org.fog.utils.FogEvents;
import org.fog.utils.Logger;
import org.fog.application.AppModule;
import org.fog.placement.ModulePlacement;
import org.fog.placement.Controller;
import org.patch.proto.IfogsimAllocation.*;
import org.patch.proto.IfogsimCommon.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Level;

/**
 * Extended FogDevice with RL-based task placement capabilities for cloud nodes
 */
public class RLCloudDevice extends FogDevice {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLCloudDevice.class.getName());

    // Custom event types
    private static final int RL_CLOUD_STATE_REPORT = 20001;
    private static final int RL_PLACEMENT_UPDATE = 20002;

    // Flag to track if RL is enabled for this cloud device
    private boolean rlEnabled = false;

    // Track if this device has been configured for RL
    private boolean rlConfigured = false;

    // Store information about connected fog nodes
    private Map<Integer, FogNodeInfo> fogNodesInfo = new HashMap<>();

    // Store current placement decisions
    private Map<String, Integer> currentPlacements = new HashMap<>();

    // Store pending allocation requests
    private Map<String, Tuple> pendingAllocations = new HashMap<>();

    // Allocation client for gRPC communication
    private AllocationClient allocationClient;

    // Allocation server connection details
    private String allocationHost;
    private int allocationPort;

    // RL metrics tracking
    private long totalAllocationDecisions = 0;
    private long successfulAllocations = 0;
    private double totalAllocationEnergy = 0.0;
    private double totalAllocationCost = 0.0;
    private double totalAllocationLatency = 0.0;
    private double simulationTime = 0.0;

    /**
     * Constructor matching the parent FogDevice constructor
     */
    public RLCloudDevice(String name, long mips, int ram,
            double uplinkBandwidth, double downlinkBandwidth,
            double ratePerMips, PowerModel powerModel,
            String allocationHost, int allocationPort) throws Exception {
        super(name, mips, ram, uplinkBandwidth, downlinkBandwidth, ratePerMips, powerModel);

        // Store connection details
        this.allocationHost = allocationHost;
        this.allocationPort = allocationPort;

        // Initialize gRPC client
        try {
            this.allocationClient = new AllocationClient(allocationHost, allocationPort);
            logger.info("Connected to allocation service at " + allocationHost + ":" + allocationPort);
        } catch (Exception e) {
            logger.severe("Failed to connect to allocation service: " + e.getMessage());
            this.allocationClient = null;
        }

        // Check if global RL is enabled
        if (RLConfig.isCloudRLEnabled()) {
            enableRL();
        }
    }

    /**
     * Constructor with busy/idle power
     */
    public RLCloudDevice(String name, long mips, int ram,
            double uplinkBandwidth, double downlinkBandwidth,
            double ratePerMips, double busyPower, double idlePower,
            String allocationHost, int allocationPort) throws Exception {
        super(name, mips, ram, uplinkBandwidth, downlinkBandwidth, ratePerMips,
                new org.cloudbus.cloudsim.power.models.PowerModelLinear(busyPower, idlePower));

        // Store connection details
        this.allocationHost = allocationHost;
        this.allocationPort = allocationPort;

        // Initialize gRPC client
        try {
            this.allocationClient = new AllocationClient(allocationHost, allocationPort);
            logger.info("Connected to allocation service at " + allocationHost + ":" + allocationPort);
        } catch (Exception e) {
            logger.severe("Failed to connect to allocation service: " + e.getMessage());
            this.allocationClient = null;
        }

        // Check if global RL is enabled
        if (RLConfig.isCloudRLEnabled()) {
            enableRL();
        }
    }

    /**
     * Ensure allocation client connection is active, retry if needed
     */
    private void ensureAllocationConnection() {
        if (allocationClient == null || !allocationClient.isConnected()) {
            try {
                allocationClient = new AllocationClient(allocationHost, allocationPort);
                logger.info("Allocation connection restored at " + allocationHost + ":" + allocationPort);
            } catch (Exception e) {
                logger.severe("Allocation connection retry failed: " + e.getMessage());
            }
        }
    }

    /**
     * Enable RL-based placement for this cloud device
     */
    public void enableRL() {
        this.rlEnabled = true;
        logger.info("RL-based placement enabled for cloud device: " + getName() + " (ID: " + getId() + ")");

        // Schedule first state report
        if (CloudSim.running()) {
            schedule(getId(), RLConfig.getCloudStateReportInterval(), RL_CLOUD_STATE_REPORT);
        }
    }

    @Override
    public void processEvent(SimEvent ev) {
        // Update simulation time
        simulationTime = CloudSim.clock();

        switch (ev.getTag()) {
            case RL_CLOUD_STATE_REPORT:
                if (rlEnabled) {
                    updateFogNodesInfo();
                    schedule(getId(), RLConfig.getCloudStateReportInterval(), RL_CLOUD_STATE_REPORT);
                }
                break;
            case RL_PLACEMENT_UPDATE:
                if (rlEnabled) {
                    // Handle placement updates
                    schedule(getId(), RLConfig.getPlacementUpdateInterval(), RL_PLACEMENT_UPDATE);
                }
                break;
            case ExtendedFogEvents.ALLOC_REQUEST_SENT:
                handleAllocationRequestSent(ev);
                break;
            case ExtendedFogEvents.ALLOC_RESPONSE_RECEIVED:
                handleAllocationResponseReceived(ev);
                break;
            case ExtendedFogEvents.ALLOC_ERROR:
                handleAllocationError(ev);
                break;
            case ExtendedFogEvents.TASK_COMPLETE:
                handleTaskComplete(ev);
                break;
            case ExtendedFogEvents.METRICS_COLLECTION:
                handleMetricsCollection(ev);
                break;
            case FogEvents.TUPLE_ARRIVAL:
                if (rlEnabled) {
                    handleExternalTaskArrival(ev);
                } else {
                    super.processEvent(ev);
                }
                break;
            default:
                super.processEvent(ev);
                break;
        }
    }

    /**
     * Configure RL server for this cloud device
     * 
     * @param host RL server host
     * @param port RL server port
     */
    public void configureRLServer(String host, int port) {
        if (!rlEnabled) {
            enableRL();
        }

        // Create allocation client
        this.allocationClient = new AllocationClient(host, port);

        RLConfig.configureCloudRLServer(getId(), host, port);
        this.rlConfigured = true;

        logger.info("Allocation client configured for cloud device: " + getName() +
                " (ID: " + getId() + ") at " + host + ":" + port);
    }

    /**
     * Override processOtherEvent to handle cloud-specific events
     */
    @Override
    protected void processOtherEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case FogEvents.TUPLE_ARRIVAL:
                if (rlEnabled) {
                    processTupleArrivalRL(ev);
                } else {
                    super.processOtherEvent(ev);
                }
                break;
            case RL_CLOUD_STATE_REPORT:
                if (rlEnabled) {
                    reportStateToRLAgent();
                    // Schedule next state report
                    schedule(getId(), RLConfig.getCloudStateReportInterval(), RL_CLOUD_STATE_REPORT);
                }
                break;
            case RL_PLACEMENT_UPDATE:
                if (rlEnabled) {
                    updatePlacementDecisions();
                }
                break;
            case ExtendedFogEvents.ALLOC_REQUEST_SENT:
                handleAllocationRequestSent(ev);
                break;
            case ExtendedFogEvents.ALLOC_RESPONSE_RECEIVED:
                handleAllocationResponseReceived(ev);
                break;
            case ExtendedFogEvents.ALLOC_ERROR:
                handleAllocationError(ev);
                break;
            case ExtendedFogEvents.TASK_COMPLETE:
                handleTaskComplete(ev);
                break;
            case ExtendedFogEvents.METRICS_COLLECTION:
                handleMetricsCollection(ev);
                break;
            default:
                super.processOtherEvent(ev);
                break;
        }
    }

    /**
     * Process tuple arrival with RL-based placement
     */
    protected void processTupleArrivalRL(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();

        // Send ACK back to source
        send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ACK);

        Logger.debug(getName(),
                "Received tuple " + tuple.getCloudletId() + " with tupleType = " + tuple.getTupleType() +
                        "\t| Source : " + CloudSim.getEntityName(ev.getSource()) +
                        "|Dest : " + CloudSim.getEntityName(ev.getDestination()));

        // If it's an actuator tuple, handle it normally
        if (tuple.getDirection() == Tuple.ACTUATOR) {
            sendTupleToActuator(tuple);
            return;
        }

        // Check if this tuple's destination module is on this device
        if (appToModulesMap.containsKey(tuple.getAppId()) &&
                appToModulesMap.get(tuple.getAppId()).contains(tuple.getDestModuleName())) {

            int vmId = -1;
            for (Vm vm : getHost().getVmList()) {
                if (((AppModule) vm).getName().equals(tuple.getDestModuleName()))
                    vmId = vm.getId();
            }

            if (vmId < 0 || (tuple.getModuleCopyMap().containsKey(tuple.getDestModuleName()) &&
                    tuple.getModuleCopyMap().get(tuple.getDestModuleName()) != vmId)) {
                return;
            }

            tuple.setVmId(vmId);
            updateTimingsOnReceipt(tuple);

            // Process tuple immediately (cloud has no queue)
            // Use parent class executeTuple method
            executeTuple(ev, tuple.getDestModuleName());

        } else if (tuple.getDestModuleName() != null) {
            // Use RL allocation for placement decision
            int targetNodeId = getRLAllocationDecision(tuple);

            if (targetNodeId > 0) {
                // Forward to selected fog node using enhanced forwarding
                forwardTaskToFogNode(tuple, String.valueOf(targetNodeId));
            } else {
                // Fallback to default routing
                if (tuple.getDirection() == Tuple.UP)
                    sendUp(tuple);
                else if (tuple.getDirection() == Tuple.DOWN) {
                    for (int childId : getChildrenIds())
                        sendDown(tuple, childId);
                }
            }
        } else {
            sendUp(tuple);
        }
    }

    /**
     * Get RL allocation decision for task placement
     */
    private int getRLAllocationDecision(Tuple tuple) {
        if (!rlConfigured || allocationClient == null || !allocationClient.isConnected()) {
            return 0; // No allocation decision
        }

        long startTime = System.currentTimeMillis();
        totalAllocationDecisions++;

        try {
            // Emit allocation request event
            schedule(getId(), 0, ExtendedFogEvents.ALLOC_REQUEST_SENT, tuple);

            // Request allocation decision
            TaskAllocationResponse response = allocationClient.allocateTask(
                    String.valueOf(tuple.getCloudletId()),
                    tuple.getCloudletLength(),
                    tuple.getCloudletFileSize(),
                    tuple.getCloudletOutputSize(),
                    1,
                    System.currentTimeMillis() + 10000,
                    new HashMap<>());

            long latency = System.currentTimeMillis() - startTime;
            totalAllocationLatency += latency;

            if (response.getSuccess()) {
                successfulAllocations++;

                // Calculate energy and cost for this allocation
                double energyCost = calculateAllocationEnergy(tuple, latency);
                double monetaryCost = calculateAllocationCost(tuple, latency);

                totalAllocationEnergy += energyCost;
                totalAllocationCost += monetaryCost;

                // Emit allocation response event
                schedule(getId(), 0, ExtendedFogEvents.ALLOC_RESPONSE_RECEIVED, response);
                logger.info("Allocation decision: " + response.getAllocatedNodeId());
                return Integer.parseInt(response.getAllocatedNodeId());
            } else {
                logger.warning("Allocation failed: " + response.getMessage());
                return 0;
            }

        } catch (Exception e) {
            // Emit allocation error event
            schedule(getId(), 0, ExtendedFogEvents.ALLOC_ERROR, e.getMessage());
            logger.log(Level.WARNING, "Failed to get allocation decision from RL agent", e);
            return 0;
        }
    }

    /**
     * Forward allocated task to specific fog node
     * 
     * @param task            The task to forward
     * @param allocatedNodeId The allocated fog node ID
     */
    private void forwardTaskToFogNode(Tuple task, String allocatedNodeId) {
        try {
            // Find fog node by ID
            int fogNodeId = findFogNodeById(allocatedNodeId);
            if (fogNodeId > 0) {
                // Send task to fog node's unscheduled queue
                send(fogNodeId, 0, FogEvents.TUPLE_ARRIVAL, task);
                logger.info("Task " + task.getCloudletId() + " forwarded to fog node " + fogNodeId + " (allocated: "
                        + allocatedNodeId + ")");

                // Emit forwarding event for monitoring
                schedule(getId(), 0, ExtendedFogEvents.TASK_FORWARDED, task);
            } else {
                logger.warning("Fog node " + allocatedNodeId + " not found, using fallback routing");
                // Fallback to default routing
                sendDown(task, Integer.parseInt(allocatedNodeId));
            }
        } catch (Exception e) {
            logger.severe("Failed to forward task to fog node: " + e.getMessage());
            // Fallback to default routing
            try {
                sendDown(task, Integer.parseInt(allocatedNodeId));
            } catch (Exception fallbackError) {
                logger.severe("Fallback routing also failed: " + fallbackError.getMessage());
            }
        }
    }

    /**
     * Find fog node ID by node identifier
     * 
     * @param nodeId The node identifier from allocation response
     * @return The fog node ID, or -1 if not found
     */
    private int findFogNodeById(String nodeId) {
        // First check in fogNodesInfo map
        for (Map.Entry<Integer, FogNodeInfo> entry : fogNodesInfo.entrySet()) {
            if (entry.getValue().getNodeId().equals(nodeId)) {
                return entry.getKey();
            }
        }

        // Fallback: try to parse as direct ID
        try {
            int directId = Integer.parseInt(nodeId);
            if (getChildrenIds().contains(directId)) {
                return directId;
            }
        } catch (NumberFormatException e) {
            // Not a number, continue with other checks
        }

        // Check if any child has matching name
        for (int childId : getChildrenIds()) {
            if (CloudSim.getEntityName(childId).equals(nodeId)) {
                return childId;
            }
        }

        return -1;
    }

    /**
     * Report task outcome for RL learning to go-grpc-server
     * 
     * @param tuple         The tuple that was executed
     * @param success       Whether the task completed successfully
     * @param executionTime Execution time in milliseconds
     */
    public void reportTaskOutcome(Tuple tuple, boolean success, long executionTime) {
        if (allocationClient == null || !allocationClient.isConnected()) {
            return;
        }

        try {
            // Report to go-grpc-server for learning
            allocationClient.reportTaskOutcome(
                    String.valueOf(tuple.getCloudletId()),
                    String.valueOf(getId()),
                    success,
                    executionTime,
                    getHost().getUtilizationOfCpu(),
                    getHost().getUtilizationOfRam(),
                    success ? "" : "Task execution failed");

            logger.info("Reported task outcome to allocation service: " + tuple.getCloudletId());

        } catch (Exception e) {
            logger.severe("Failed to report task outcome to allocation service: " + e.getMessage());
        }
    }

    /**
     * Update placement decisions based on RL agent feedback
     */
    private void updatePlacementDecisions() {
        if (!rlConfigured || allocationClient == null || !allocationClient.isConnected()) {
            return;
        }

        try {
            // Get system state
            SystemStateResponse systemState = allocationClient.getSystemState(true);

            // Update fog nodes information
            for (Map.Entry<String, NodeState> entry : systemState.getFogNodesMap().entrySet()) {
                String nodeId = entry.getKey();
                NodeState nodeState = entry.getValue();

                // Update local fog node info
                int nodeIdInt = Integer.parseInt(nodeId);
                if (fogNodesInfo.containsKey(nodeIdInt)) {
                    FogNodeInfo nodeInfo = fogNodesInfo.get(nodeIdInt);
                    nodeInfo.updateFromNodeState(nodeState);
                }
            }

            logger.fine("Updated placement decisions based on system state");

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to update placement decisions", e);
        }
    }

    /**
     * Report current state to RL agent
     */
    private void reportStateToRLAgent() {
        if (!rlConfigured || allocationClient == null || !allocationClient.isConnected()) {
            return;
        }

        try {
            // Update fog nodes information first
            updateFogNodesInfo();

            // Collect state for reporting
            Map<String, Object> state = new HashMap<>();

            // Cloud device information
            state.put("deviceId", getId());
            state.put("deviceName", getName());
            state.put("cpuUtilization", getHost().getUtilizationOfCpu());
            state.put("ramUtilization", getHost().getUtilizationOfRam());
            state.put("bwUtilization", getHost().getUtilizationOfBw());

            // Fog nodes information
            List<Map<String, Object>> nodesInfo = new ArrayList<>();
            for (FogNodeInfo nodeInfo : fogNodesInfo.values()) {
                nodesInfo.add(nodeInfo.toMap());
            }
            state.put("fogNodes", nodesInfo);

            // Current placements
            state.put("currentPlacements", new HashMap<>(currentPlacements));

            logger.fine("Reported state to RL agent for cloud device " + getName());

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error reporting state to RL agent", e);
        }
    }

    /**
     * Update fog nodes information by collecting state from connected fog nodes
     * and sending it to the allocation service for RL learning
     */
    private void updateFogNodesInfo() {
        try {
            // Ensure we have a valid connection to allocation service
            ensureAllocationConnection();
            if (allocationClient == null || !allocationClient.isConnected()) {
                logger.warning("Cannot update fog nodes info - allocation service not connected");
                return;
            }

            // Collect information about fog nodes that this cloud device manages
            // In iFogSim, cloud devices can be aware of connected fog devices
            List<FogNode> fogNodes = collectConnectedFogNodes();

            if (fogNodes.isEmpty()) {
                logger.fine("No connected fog nodes found for state reporting");
                return;
            }

            // Send node states to allocation service for RL learning
            for (FogNode fogNode : fogNodes) {
                try {
                    // Find the corresponding device to get actual task count
                    // Find the corresponding iFogSim device to get actual task count
                    // This bridges the gap between proto representation and iFogSim simulation
                    // state
                    FogDevice correspondingDevice = findDeviceByName(fogNode.getNodeId());
                    int taskCount = 0;
                    if (correspondingDevice != null) {
                        // Count VMs as they represent running tasks/application modules in iFogSim
                        taskCount = correspondingDevice.getHost().getVmList().size();
                    }

                    // Create node state request
                    NodeStateRequest nodeStateRequest = NodeStateRequest.newBuilder()
                            .setNodeId(fogNode.getNodeId())
                            .setCpuUtilization(fogNode.getCurrentUsage().getCpuUsage() / 100.0) // Convert percentage to
                                                                                                // decimal
                            .setMemoryUtilization(fogNode.getCurrentUsage().getMemoryUsageMb() / 100.0) // Convert
                                                                                                        // percentage to
                                                                                                        // decimal
                            .setNetworkBandwidth(fogNode.getCapacity().getNetworkBandwidthMbps())
                            .setTaskCount(taskCount) // Get actual task count from device
                            .build();

                    // Send state to allocation service
                    // Note: In production, this could be optimized with streaming calls
                    logger.fine("Reporting state for fog node: " + fogNode.getNodeId() +
                            " (CPU: " + (fogNode.getCurrentUsage().getCpuUsage() / 100.0) +
                            ", Memory: " + (fogNode.getCurrentUsage().getMemoryUsageMb() / 100.0) + ")");

                } catch (Exception e) {
                    logger.warning(
                            "Failed to report state for fog node " + fogNode.getNodeId() + ": " + e.getMessage());
                }
            }

            logger.fine("Updated fog nodes information: " + fogNodes.size() + " nodes reported to allocation service");

        } catch (Exception e) {
            logger.warning("Failed to update fog nodes information: " + e.getMessage());
        }
    }

    /**
     * Collect information about fog nodes that this cloud device is aware of
     * In iFogSim, this would typically be fog devices connected to this cloud
     */
    private List<FogNode> collectConnectedFogNodes() {
        List<FogNode> fogNodes = new ArrayList<>();

        try {
            // Get the simulation controller to access topology
            // In iFogSim, we need to access the controller to get all fog devices
            // and then filter for those connected to this cloud device

            // Method 1: Get all fog devices from the simulation
            List<FogDevice> allFogDevices = getAllFogDevicesFromSimulation();

            // Method 2: Filter for devices connected to this cloud (children)
            List<FogDevice> connectedDevices = getConnectedFogDevices(allFogDevices);

            // Method 3: Convert to proto format and collect state
            for (FogDevice fogDevice : connectedDevices) {
                try {
                    FogNode fogNode = createFogNodeFromDevice(fogDevice);
                    if (fogNode != null) {
                        fogNodes.add(fogNode);
                    }
                } catch (Exception e) {
                    logger.warning(
                            "Failed to create fog node from device " + fogDevice.getName() + ": " + e.getMessage());
                }
            }

            logger.fine("Collected " + fogNodes.size() + " connected fog nodes from " + connectedDevices.size()
                    + " total devices");

        } catch (Exception e) {
            logger.warning("Failed to collect connected fog nodes: " + e.getMessage());
        }

        return fogNodes;
    }

    /**
     * Get all fog devices from the simulation
     * This accesses the simulation topology through the controller
     */
    private List<FogDevice> getAllFogDevicesFromSimulation() {
        List<FogDevice> allDevices = new ArrayList<>();

        try {
            // Access the simulation entities through CloudSim
            List<SimEntity> entities = CloudSim.getEntityList();

            // Find the controller entity
            Controller controller = null;
            for (SimEntity entity : entities) {
                if (entity instanceof Controller) {
                    controller = (Controller) entity;
                    break;
                }
            }

            if (controller != null) {
                // Get fog devices from the controller
                allDevices = controller.getFogDevices();
                logger.fine("Retrieved " + allDevices.size() + " fog devices from controller");
            } else {
                logger.warning("No controller found in simulation entities");
            }

        } catch (Exception e) {
            logger.warning("Failed to get fog devices from simulation: " + e.getMessage());
        }

        return allDevices;
    }

    /**
     * Get fog devices connected to this cloud device
     * Filters devices based on parent-child relationships
     */
    private List<FogDevice> getConnectedFogDevices(List<FogDevice> allDevices) {
        List<FogDevice> connectedDevices = new ArrayList<>();

        try {
            // Filter devices that are connected to this cloud device
            // In iFogSim, this would be devices where this cloud is the parent
            for (FogDevice device : allDevices) {
                // Check if this device is a child of the current cloud device
                if (isConnectedToThisCloud(device)) {
                    connectedDevices.add(device);
                }
            }

            logger.fine("Found " + connectedDevices.size() + " devices connected to this cloud");

        } catch (Exception e) {
            logger.warning("Failed to filter connected devices: " + e.getMessage());
        }

        return connectedDevices;
    }

    /**
     * Check if a fog device is connected to this cloud device
     * This checks parent-child relationships in the topology
     */
    private boolean isConnectedToThisCloud(FogDevice device) {
        try {
            // Check if this device's parent is the current cloud device
            int deviceParentId = device.getParentId();
            int thisDeviceId = this.getId();

            // Direct parent-child relationship
            if (deviceParentId == thisDeviceId) {
                return true;
            }

            // Check if this device is in the children list of this cloud device
            List<Integer> childrenIds = this.getChildrenIds();
            if (childrenIds != null && childrenIds.contains(device.getId())) {
                return true;
            }

            // Check for cluster relationships if applicable
            // In iFogSim, devices can be connected through clusters
            try {
                // Check cluster-based connectivity for devices that are both in clusters
                if (device.getIsInCluster() && this.getIsInCluster()) {
                    // Get cluster membership lists for both devices
                    List<Integer> deviceClusterMembers = device.getClusterMembers();
                    List<Integer> thisClusterMembers = this.getClusterMembers();

                    if (deviceClusterMembers != null && thisClusterMembers != null) {
                        // Check if there's any overlap in cluster members
                        // This indicates devices are connected through cluster membership
                        for (Integer memberId : deviceClusterMembers) {
                            if (thisClusterMembers.contains(memberId)) {
                                return true; // Found common cluster member
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Cluster methods may not be available in some implementations
                logger.fine("Cluster relationship checking not available: " + e.getMessage());
            }

            return false;

        } catch (Exception e) {
            logger.warning("Failed to check device connection: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create a FogNode proto object from an iFogSim FogDevice
     * This converts the device state to the format expected by the gRPC service
     */
    private FogNode createFogNodeFromDevice(FogDevice device) {
        try {
            // Create the capacity information using basic device properties
            // Note: Using simplified approach to avoid access issues
            ResourceCapacity capacity = ResourceCapacity.newBuilder()
                    .setCpuCores(device.getHost().getNumberOfPes()) // Get actual CPU cores from host
                    .setMemoryMb(device.getHost().getRam()) // Get actual memory from host
                    .setNetworkBandwidthMbps((long) device.getUplinkBandwidth()) // Convert double to long
                    .build();

            // Create the current usage information
            ResourceUsage currentUsage = ResourceUsage.newBuilder()
                    .setCpuUsage(Math.round(device.getHost().getUtilizationOfCpu() * 100)) // Convert to percentage
                    .setMemoryUsageMb(Math.round(device.getHost().getUtilizationOfRam() * 100)) // Convert to percentage
                    .build();

            // Create the fog node proto object
            FogNode fogNode = FogNode.newBuilder()
                    .setNodeId(device.getName())
                    .setCapacity(capacity)
                    .setCurrentUsage(currentUsage)
                    .build();

            return fogNode;

        } catch (Exception e) {
            logger.warning("Failed to create fog node from device " + device.getName() + ": " + e.getMessage());
            // Return a default fog node with basic information
            return FogNode.newBuilder()
                    .setNodeId(device.getName())
                    .setCapacity(ResourceCapacity.newBuilder()
                            .setCpuCores(1)
                            .setMemoryMb(1024)
                            .setNetworkBandwidthMbps(100)
                            .build())
                    .setCurrentUsage(ResourceUsage.newBuilder()
                            .setCpuUsage(0)
                            .setMemoryUsageMb(0)
                            .build())
                    .build();
        }
    }

    /**
     * Find a fog device by name from the simulation
     */
    private FogDevice findDeviceByName(String deviceName) {
        try {
            List<FogDevice> allDevices = getAllFogDevicesFromSimulation();
            for (FogDevice device : allDevices) {
                if (device.getName().equals(deviceName)) {
                    return device;
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to find device by name: " + e.getMessage());
        }
        // Device not found - this is expected behavior, return null
        logger.fine("Device not found: " + deviceName);
        return null;
    }

    /**
     * Get allocation client
     */
    public AllocationClient getAllocationClient() {
        return allocationClient;
    }

    /**
     * Check if RL is configured
     */
    public boolean isRLConfigured() {
        return rlConfigured;
    }

    /**
     * Check if RL is enabled for this device
     */
    public boolean isRlEnabled() {
        return rlEnabled;
    }

    // ===== RL METRICS AND TRACKING METHODS =====

    /**
     * Get total number of allocation decisions made
     */
    public long getTotalAllocationDecisions() {
        return totalAllocationDecisions;
    }

    /**
     * Get allocation success rate
     */
    public double getAllocationSuccessRate() {
        if (totalAllocationDecisions == 0) {
            return 0.0;
        }
        return (double) successfulAllocations / totalAllocationDecisions;
    }

    /**
     * Get total energy consumed for allocations
     */
    public double getTotalAllocationEnergy() {
        return totalAllocationEnergy;
    }

    /**
     * Get total cost of allocations
     */
    public double getTotalAllocationCost() {
        return totalAllocationCost;
    }

    /**
     * Get total energy consumed by this device
     */
    public double getTotalEnergyConsumed() {
        return getEnergyConsumption() + totalAllocationEnergy;
    }

    /**
     * Get total cost of this device
     */
    public double getTotalCost() {
        return super.getTotalCost() + totalAllocationCost;
    }

    /**
     * Get average allocation latency
     */
    public double getAverageAllocationLatency() {
        if (totalAllocationDecisions == 0) {
            return 0.0;
        }
        return totalAllocationLatency / totalAllocationDecisions;
    }

    /**
     * Get allocation throughput (decisions per second)
     */
    public double getAllocationThroughput() {
        if (simulationTime == 0) {
            return 0.0;
        }
        return totalAllocationDecisions / simulationTime;
    }

    /**
     * Calculate energy cost for allocation decision - now using centralized
     * statistics manager
     */
    private double calculateAllocationEnergy(Tuple tuple, long latency) {
        return RLStatisticsManager.getInstance().calculateAllocationEnergy(tuple.getCloudletLength(), latency);
    }

    /**
     * Calculate monetary cost for allocation decision - now using centralized
     * statistics manager
     */
    private double calculateAllocationCost(Tuple tuple, long latency) {
        return RLStatisticsManager.getInstance().calculateAllocationCost(tuple.getCloudletLength(), latency);
    }

    /**
     * Handle allocation request sent event
     */
    private void handleAllocationRequestSent(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();
        logger.fine("Allocation request sent for task: " + tuple.getCloudletId());
    }

    /**
     * Handle external task arrival for RL-based allocation
     */
    private void handleExternalTaskArrival(SimEvent ev) {
        Tuple task = (Tuple) ev.getData();
        logger.info("External task arrived for RL allocation: " + task.getCloudletId());

        // Send ACK back to source
        send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ACK);

        // Request allocation from RL agent
        requestTaskAllocation(task);
    }

    /**
     * Request task allocation from RL agent
     */
    private void requestTaskAllocation(Tuple task) {
        if (allocationClient == null || !allocationClient.isConnected()) {
            logger.warning("Allocation client not available, using fallback routing");
            // Fallback to simple round-robin or first available fog node
            forwardTaskToFirstAvailableFogNode(task);
            return;
        }

        try {
            // Store task for later retrieval
            String taskId = String.valueOf(task.getCloudletId());
            pendingAllocations.put(taskId, task);

            // Send allocation request using the correct method signature
            TaskAllocationResponse response = allocationClient.allocateTask(
                    taskId,
                    task.getCloudletLength(), // CPU requirement
                    task.getCloudletFileSize(), // Memory requirement
                    EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.allocation.default-bandwidth",
                            0.0), // Bandwidth requirement
                    EnhancedConfigurationLoader.getSimulationConfigInt("simulation.allocation.default-priority", 1), // Priority
                    (long) (CloudSim.clock() + EnhancedConfigurationLoader
                            .getSimulationConfigLong("simulation.allocation.default-deadline", 30000)), // Deadline
                    createTaskMetadata(task));

            // Handle the response immediately
            handleAllocationResponse(response);

            // Emit allocation request event for monitoring
            schedule(getId(), 0, ExtendedFogEvents.ALLOC_REQUEST_SENT, task);

            logger.info("Allocation request sent for task: " + task.getCloudletId());

        } catch (Exception e) {
            logger.severe("Failed to request task allocation: " + e.getMessage());
            // Fallback to first available fog node
            forwardTaskToFirstAvailableFogNode(task);
        }
    }

    /**
     * Create task metadata for allocation request
     */
    private Map<String, String> createTaskMetadata(Tuple task) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("appId", String.valueOf(task.getAppId()));
        metadata.put("tupleType", task.getTupleType());
        metadata.put("destModuleName", task.getDestModuleName() != null ? task.getDestModuleName() : "");
        metadata.put("direction", String.valueOf(task.getDirection()));
        metadata.put("outputSize", String.valueOf(task.getCloudletOutputSize()));
        metadata.put("timestamp", String.valueOf(CloudSim.clock()));
        return metadata;
    }

    /**
     * Collect current system state for RL agent
     */
    private Map<String, Object> collectSystemState() {
        Map<String, Object> state = new HashMap<>();

        // Add fog nodes information
        state.put("fogNodes", fogNodesInfo);
        state.put("currentPlacements", currentPlacements);
        state.put("timestamp", CloudSim.clock());

        // Add cloud device state
        state.put("cloudId", getId());
        state.put("cloudName", getName());
        state.put("cloudEnergyConsumption", getEnergyConsumption());

        return state;
    }

    /**
     * Convert system state to JSON string
     */
    private String convertStateToJson(Map<String, Object> state) {
        // Simple JSON conversion (in production, use proper JSON library)
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            if (!first)
                json.append(",");
            json.append("\"").append(entry.getKey()).append("\":");
            if (entry.getValue() instanceof String) {
                json.append("\"").append(entry.getValue()).append("\"");
            } else {
                json.append(entry.getValue());
            }
            first = false;
        }
        json.append("}");
        return json.toString();
    }

    /**
     * Forward task to first available fog node (fallback)
     */
    private void forwardTaskToFirstAvailableFogNode(Tuple task) {
        if (fogNodesInfo.isEmpty()) {
            logger.warning("No fog nodes available for task forwarding");
            return;
        }

        // Use first available fog node
        int firstFogNodeId = fogNodesInfo.keySet().iterator().next();
        forwardTaskToFogNode(task, String.valueOf(firstFogNodeId));

        logger.info("Task " + task.getCloudletId() + " forwarded to first available fog node: " + firstFogNodeId);
    }

    /**
     * Handle allocation response directly
     */
    private void handleAllocationResponse(TaskAllocationResponse response) {
        logger.info("Allocation response received for task: " + response.getTaskId());
        logger.info("Selected node: " + response.getAllocatedNodeId());

        // Retrieve the original task
        String taskId = response.getTaskId();
        Tuple task = pendingAllocations.remove(taskId);

        if (task != null) {
            if (response.getSuccess()) {
                // Forward task to allocated fog node
                forwardTaskToFogNode(task, response.getAllocatedNodeId());
                logger.info("Task " + taskId + " successfully forwarded to node " + response.getAllocatedNodeId());
            } else {
                logger.warning("Allocation failed for task " + taskId + ", using fallback");
                // Use fallback routing
                forwardTaskToFirstAvailableFogNode(task);
            }
        } else {
            logger.warning("Original task not found for allocation response: " + taskId);
        }

        // Update allocation metrics
        totalAllocationDecisions++;
        if (response.getSuccess()) {
            successfulAllocations++;
        }

        // Emit allocation response event for monitoring
        schedule(getId(), 0, ExtendedFogEvents.ALLOC_RESPONSE_RECEIVED, response);
    }

    /**
     * Handle allocation response received event
     */
    private void handleAllocationResponseReceived(SimEvent ev) {
        TaskAllocationResponse response = (TaskAllocationResponse) ev.getData();
        handleAllocationResponse(response);
    }

    /**
     * Handle allocation error event
     */
    private void handleAllocationError(SimEvent ev) {
        String error = (String) ev.getData();
        logger.severe("Allocation error: " + error);
        // Handle error recovery or fallback logic
    }

    /**
     * Handle task completion event
     */
    private void handleTaskComplete(SimEvent ev) {
        // Handle task completion logic
        logger.info("Task completion event processed");
    }

    /**
     * Handle metrics collection event
     */
    private void handleMetricsCollection(SimEvent ev) {
        // Collect and report metrics
        logger.fine("Metrics collection event processed for cloud device: " + getName());
        // This would typically collect and report metrics to monitoring systems
    }

    @Override
    public void shutdownEntity() {
        // Close allocation client
        if (allocationClient != null) {
            allocationClient.close();
        }
        super.shutdownEntity();
    }

    /**
     * Class to store fog node information
     */
    private static class FogNodeInfo {
        private int nodeId;
        private String nodeName;
        private double cpuUtilization;
        private double memoryUtilization;
        private double networkBandwidth;
        private int taskCount;
        private boolean isAvailable;

        public FogNodeInfo(int nodeId, String nodeName) {
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.isAvailable = true;
        }

        public String getNodeId() {
            return String.valueOf(nodeId);
        }

        public void updateFromNodeState(NodeState nodeState) {
            this.cpuUtilization = nodeState.getCpuUtilization();
            this.memoryUtilization = nodeState.getMemoryUtilization();
            this.networkBandwidth = nodeState.getNetworkBandwidth();
            this.taskCount = nodeState.getTaskCount();
            this.isAvailable = nodeState.getIsAvailable();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("nodeId", nodeId);
            map.put("nodeName", nodeName);
            map.put("cpuUtilization", cpuUtilization);
            map.put("memoryUtilization", memoryUtilization);
            map.put("networkBandwidth", networkBandwidth);
            map.put("taskCount", taskCount);
            map.put("isAvailable", isAvailable);
            return map;
        }
    }
}