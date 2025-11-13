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
import org.patch.models.PendingAllocationRequest;
import org.patch.models.PendingOutcomeRequest;
import org.patch.utils.NetworkLatencyConverter;
import org.patch.utils.NetworkEnergyCostCalculator;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.entities.FogDeviceCharacteristics;
import org.cloudbus.cloudsim.power.PowerHost;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.Config;
import org.fog.utils.FogUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
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

    // Pending async allocation requests ()
    private Map<String, PendingAllocationRequest> pendingAllocationRequests = new HashMap<>();
    private Map<String, PendingOutcomeRequest> pendingOutcomeRequests = new HashMap<>();

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

    // Debug counter for task forwarding
    private int forwardTaskCount = 0;

    /**
     * Wrapper class to hold device components for initialization
     */
    private static class DeviceComponents {
        final FogDeviceCharacteristics characteristics;
        final VmAllocationPolicy vmAllocationPolicy;
        final LinkedList<Storage> storageList;

        DeviceComponents(FogDeviceCharacteristics characteristics,
                VmAllocationPolicy vmAllocationPolicy,
                LinkedList<Storage> storageList) {
            this.characteristics = characteristics;
            this.vmAllocationPolicy = vmAllocationPolicy;
            this.storageList = storageList;
        }
    }

    /**
     * Helper method to create host and characteristics for proper initialization
     */
    private static DeviceComponents createDeviceComponents(String name, long mips, int ram, PowerModel powerModel) {
        List<Pe> peList = new ArrayList<Pe>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int hostId = FogUtils.generateEntityId();
        long storage = 1000000;
        int bw = 10000;

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                powerModel);

        List<Host> hostList = new ArrayList<Host>();
        hostList.add(host);

        VmAllocationPolicy vmAllocationPolicy = new AppModuleAllocationPolicy(hostList);

        String arch = Config.FOG_DEVICE_ARCH;
        String os = Config.FOG_DEVICE_OS;
        String vmm = Config.FOG_DEVICE_VMM;
        double time_zone = Config.FOG_DEVICE_TIMEZONE;
        double cost = Config.FOG_DEVICE_COST;
        double costPerMem = Config.FOG_DEVICE_COST_PER_MEMORY;
        double costPerStorage = Config.FOG_DEVICE_COST_PER_STORAGE;
        double costPerBw = Config.FOG_DEVICE_COST_PER_BW;

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                arch, os, vmm, host, time_zone, cost, costPerMem,
                costPerStorage, costPerBw);

        LinkedList<Storage> storageList = new LinkedList<Storage>();

        return new DeviceComponents(characteristics, vmAllocationPolicy, storageList);
    }

    /**
     * Constructor matching the parent FogDevice constructor
     * Creates host and characteristics first, then calls full FogDevice constructor
     */
    public RLCloudDevice(String name, long mips, int ram,
            double uplinkBandwidth, double downlinkBandwidth,
            double ratePerMips, PowerModel powerModel,
            String allocationHost, int allocationPort) throws Exception {
        // Call full FogDevice constructor - super() must be first, so we inline the
        // helper call
        super(name,
                createDeviceComponents(name, mips, ram, powerModel).characteristics,
                createDeviceComponents(name, mips, ram, powerModel).vmAllocationPolicy,
                createDeviceComponents(name, mips, ram, powerModel).storageList,
                10.0, // schedulingInterval
                uplinkBandwidth, downlinkBandwidth, 0.0, // uplinkLatency default
                ratePerMips);

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

        // Check if global RL is enabled - force check config
        org.patch.config.EnhancedConfigurationLoader.initialize();
        boolean cloudRLEnabled = RLConfig.isCloudRLEnabled();

        // Also check allocation RL agent enabled - default to true
        boolean allocatorRLEnabled = org.patch.config.EnhancedConfigurationLoader
                .getAllocationConfigBoolean("allocation.rl-agent.enabled", true);
        boolean cloudRLFromConfig = org.patch.config.EnhancedConfigurationLoader
                .getRLConfigBoolean("rl.servers.cloud.enabled", true);

        // Enable RL if config says so OR if allocator RL is enabled
        if (cloudRLEnabled || allocatorRLEnabled || cloudRLFromConfig) {
            if (!cloudRLEnabled && (allocatorRLEnabled || cloudRLFromConfig)) {
                // Enable it now if it wasn't already
                String cloudHost = org.patch.config.EnhancedConfigurationLoader.getRLConfig("rl.servers.cloud.host",
                        allocationHost);
                int cloudPort = org.patch.config.EnhancedConfigurationLoader.getRLConfigInt("rl.servers.cloud.port",
                        allocationPort);
                RLConfig.enableCloudRL(cloudHost, cloudPort);
                logger.info("Enabled Cloud RL from config during device creation at " + cloudHost + ":" + cloudPort);
            }
            enableRL();
            logger.info("RL enabled for cloud device: " + getName() + " (ID: " + getId() + ")");
        } else {
            logger.info("RL NOT enabled for cloud device: " + getName() + " - config says disabled");
        }
    }

    /**
     * Constructor with busy/idle power
     * Creates host and characteristics first, then calls full FogDevice constructor
     */
    public RLCloudDevice(String name, long mips, int ram,
            double uplinkBandwidth, double downlinkBandwidth,
            double ratePerMips, double busyPower, double idlePower,
            String allocationHost, int allocationPort) throws Exception {
        // Call full FogDevice constructor - super() must be first, so everything is
        // inlined
        super(name,
                createDeviceComponents(name, mips, ram,
                        new org.cloudbus.cloudsim.power.models.PowerModelLinear(busyPower, idlePower)).characteristics,
                createDeviceComponents(name, mips, ram,
                        new org.cloudbus.cloudsim.power.models.PowerModelLinear(busyPower,
                                idlePower)).vmAllocationPolicy,
                createDeviceComponents(name, mips, ram,
                        new org.cloudbus.cloudsim.power.models.PowerModelLinear(busyPower, idlePower)).storageList,
                10.0, // schedulingInterval
                uplinkBandwidth, downlinkBandwidth, 0.0, // uplinkLatency default
                ratePerMips);

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

        // Check if global RL is enabled - force check config
        org.patch.config.EnhancedConfigurationLoader.initialize();
        boolean cloudRLEnabled = RLConfig.isCloudRLEnabled();

        // Also check allocation RL agent enabled - default to true
        boolean allocatorRLEnabled = org.patch.config.EnhancedConfigurationLoader
                .getAllocationConfigBoolean("allocation.rl-agent.enabled", true);
        boolean cloudRLFromConfig = org.patch.config.EnhancedConfigurationLoader
                .getRLConfigBoolean("rl.servers.cloud.enabled", true);

        // Enable RL if config says so OR if allocator RL is enabled
        if (cloudRLEnabled || allocatorRLEnabled || cloudRLFromConfig) {
            if (!cloudRLEnabled && (allocatorRLEnabled || cloudRLFromConfig)) {
                // Enable it now if it wasn't already
                String cloudHost = org.patch.config.EnhancedConfigurationLoader.getRLConfig("rl.servers.cloud.host",
                        allocationHost);
                int cloudPort = org.patch.config.EnhancedConfigurationLoader.getRLConfigInt("rl.servers.cloud.port",
                        allocationPort);
                RLConfig.enableCloudRL(cloudHost, cloudPort);
                logger.info("Enabled Cloud RL from config during device creation at " + cloudHost + ":" + cloudPort);
            }
            enableRL();
            logger.info("RL enabled for cloud device: " + getName() + " (ID: " + getId() + ")");
        } else {
            logger.info("RL NOT enabled for cloud device: " + getName() + " - config says disabled");
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

        // Schedule first state report and immediate fog node registration
        if (CloudSim.running()) {
            schedule(getId(), RLConfig.getCloudStateReportInterval(), RL_CLOUD_STATE_REPORT);
            // Register fog nodes immediately after a short delay to ensure allocator is
            // ready
            schedule(getId(), 1.0, RL_CLOUD_STATE_REPORT);
            logger.info(String.format("[FLOW-FOG-REGISTRY] Cloud (ID:%d) - Scheduled initial fog node registration",
                    getId()));
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
            case ExtendedFogEvents.ALLOC_OUTCOME_REPORT:
                handleAllocOutcomeReport(ev);
                break;
            case ExtendedFogEvents.GRPC_ALLOCATOR_RESPONSE:
                handleGrpcAllocatorResponse(ev);
                break;
            case ExtendedFogEvents.GRPC_ALLOCATOR_OUTCOME_RESPONSE:
                handleGrpcAllocatorOutcomeResponse(ev);
                break;
            case ExtendedFogEvents.GRPC_ALLOCATOR_TIMEOUT:
                handleGrpcAllocatorTimeout(ev);
                break;
            case ExtendedFogEvents.GRPC_ALLOCATOR_OUTCOME_TIMEOUT:
                handleGrpcAllocatorOutcomeTimeout(ev);
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
        double currentTime = CloudSim.clock();
        String sourceName = CloudSim.getEntityName(ev.getSource());

        // [DEBUG] Log external task arrival at cloud
        boolean isExternalTask = (tuple.getTupleType() != null && tuple.getTupleType().equals("EXTERNAL")) ||
                (tuple.getDestModuleName() != null && tuple.getDestModuleName().equals("external_task"));

        if (isExternalTask) {
            System.out.println(String.format(
                    "[FLOW-CLOUD-ARRIVAL] Time: %.2f - Cloud (ID:%d) received EXTERNAL task %d from %s (TupleType:%s, DestModule:%s) - Starting allocation process",
                    currentTime, getId(), tuple.getCloudletId(), sourceName,
                    tuple.getTupleType(), tuple.getDestModuleName()));
        }

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
            // [DEBUG] Log that we need to use RL allocation
            if (isExternalTask) {
                System.out.println(String.format(
                        "[FLOW-CLOUD-ALLOC-START] Time: %.2f - Cloud (ID:%d) - External task %d needs allocation decision (DestModule:%s, CPU:%.0f, Mem:%.0f) - Calling allocator",
                        CloudSim.clock(), getId(), tuple.getCloudletId(), tuple.getDestModuleName(),
                        tuple.getCloudletLength(), tuple.getCloudletFileSize()));
            }

            // Use RL allocation for placement decision
            int targetNodeId = getRLAllocationDecision(tuple);

            if (targetNodeId > 0) {
                // [DEBUG] Log allocation decision
                if (isExternalTask) {
                    System.out.println(String.format(
                            "[FLOW-CLOUD-ALLOC-SUCCESS] Time: %.2f - Cloud (ID:%d) - Allocator selected fog node %d for external task %d - Forwarding to fog node",
                            CloudSim.clock(), getId(), targetNodeId, tuple.getCloudletId()));
                }

                // Forward to selected fog node using enhanced forwarding
                forwardTaskToFogNode(tuple, String.valueOf(targetNodeId));
            } else {
                // [DEBUG] Log allocation failure
                if (isExternalTask) {
                    System.out.println(String.format(
                            "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Allocation FAILED for external task %d, using fallback routing",
                            CloudSim.clock(), getId(), tuple.getCloudletId()));
                }

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
        double currentTime = CloudSim.clock();

        // [DEBUG] Check allocator state before allocation
        if (!rlConfigured || allocationClient == null) {
            logger.info(String.format(
                    "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Allocator NOT available (rlConfigured:%s, client:%s)",
                    currentTime, getId(), rlConfigured,
                    allocationClient != null ? "exists" : "null"));
            return 0; // No allocation decision
        }

        // Check connection state - ensure not idle
        if (!allocationClient.isConnected()) {
            logger.warning(String.format(
                    "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Allocator client NOT connected, attempting reconnect...",
                    currentTime, getId()));
            ensureAllocationConnection();
            if (!allocationClient.isConnected()) {
                logger.warning(String.format(
                        "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Allocator reconnect failed, skipping allocation",
                        currentTime, getId()));
                return 0;
            }
        }

        // Check service health (not idle)
        if (!allocationClient.isServiceHealthy()) {
            logger.warning(String.format(
                    "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Allocator server NOT healthy (idle/unavailable), skipping allocation",
                    currentTime, getId()));
            return 0;
        }

        long startTime = System.currentTimeMillis();
        totalAllocationDecisions++;

        try {
            // [DEBUG] Log allocation request - detailed info
            System.out.println(String.format(
                    "[FLOW-CLOUD-ALLOC-REQUEST] Time: %.2f - Cloud (ID:%d) - Requesting allocation for task %d (CPU=%.0f, Mem=%.0f, BW=%.0f, Priority=1) - Calling allocator service",
                    currentTime, getId(), tuple.getCloudletId(), tuple.getCloudletLength(),
                    tuple.getCloudletFileSize(), tuple.getCloudletOutputSize()));

            logger.info(String.format(
                    "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Task %d arrived at cloud, requesting allocation (CPU=%.2f, Mem=%.2f)",
                    currentTime, getId(), tuple.getCloudletId(), tuple.getCloudletLength(),
                    tuple.getCloudletFileSize()));

            // Emit allocation request event
            schedule(getId(), 0, ExtendedFogEvents.ALLOC_REQUEST_SENT, tuple);

            // Request allocation decision
            logger.info(String.format(
                    "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Calling allocationClient.allocateTask for task %d",
                    currentTime, getId(), tuple.getCloudletId()));

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

            // [DEBUG] Log allocation response from allocator
            System.out.println(String.format(
                    "[FLOW-CLOUD-ALLOC-RESPONSE] Time: %.2f - Cloud (ID:%d) - Allocator response: task %d -> node %s, success=%s, latency=%dms",
                    currentTime, getId(), tuple.getCloudletId(), response.getAllocatedNodeId(), response.getSuccess(),
                    latency));

            logger.info(String.format(
                    "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Allocation response received: task %d -> node %s, success=%s, latency=%dms",
                    currentTime, getId(), tuple.getCloudletId(), response.getAllocatedNodeId(), response.getSuccess(),
                    latency));

            if (response.getSuccess()) {
                successfulAllocations++;

                // Calculate energy and cost for this allocation
                double energyCost = calculateAllocationEnergy(tuple, latency);
                double monetaryCost = calculateAllocationCost(tuple, latency);

                totalAllocationEnergy += energyCost;
                totalAllocationCost += monetaryCost;

                // Emit allocation response event
                schedule(getId(), 0, ExtendedFogEvents.ALLOC_RESPONSE_RECEIVED, response);
                logger.info(String.format(
                        "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Task %d allocated to node %s (energy=%.4f, cost=%.4f)",
                        currentTime, getId(), tuple.getCloudletId(), response.getAllocatedNodeId(), energyCost,
                        monetaryCost));

                int allocatedNodeId = Integer.parseInt(response.getAllocatedNodeId());
                logger.info(String.format(
                        "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Forwarding task %d to fog node %d",
                        currentTime, getId(), tuple.getCloudletId(), allocatedNodeId));

                return allocatedNodeId;
            } else {
                logger.warning(String.format(
                        "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Allocation failed for task %d: %s",
                        currentTime, getId(), tuple.getCloudletId(), response.getMessage()));
                return 0;
            }

        } catch (Exception e) {
            logger.severe(String.format(
                    "[FLOW-CLOUD-ALLOC] Time: %.2f - Cloud (ID:%d) - Exception during allocation for task %d: %s",
                    currentTime, getId(), tuple.getCloudletId(), e.getMessage()));
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
        forwardTaskCount++;
        double currentTime = CloudSim.clock();

        try {
            // Find fog node by ID
            int fogNodeId = findFogNodeById(allocatedNodeId);
            if (fogNodeId > 0) {
                // [DEBUG] Log forwarding
                System.out.println(String.format(
                        "[FLOW-CLOUD-FORWARD] Time: %.2f - Cloud (ID:%d) forwarding external task %d to fog node %d (allocated: %s, Total forwarded: %d)",
                        currentTime, getId(), task.getCloudletId(), fogNodeId, allocatedNodeId, forwardTaskCount));

                // Send task to fog node's unscheduled queue
                send(fogNodeId, 0, FogEvents.TUPLE_ARRIVAL, task);

                // Log forwarding (first 100, then every 50th)
                if (forwardTaskCount <= 100 || forwardTaskCount % 50 == 0) {
                    System.out.println(String.format(
                            "[CLOUD-FORWARD] Cloud (ID:%d) - Task %d forwarded to fog node %d (allocated: %s) at time %.2f (Total forwarded: %d)",
                            getId(), task.getCloudletId(), fogNodeId, allocatedNodeId, CloudSim.clock(),
                            forwardTaskCount));
                }

                logger.info("Task " + task.getCloudletId() + " forwarded to fog node " + fogNodeId + " (allocated: "
                        + allocatedNodeId + ")");

                // [DEBUG] Confirm forwarding
                System.out.println(String.format(
                        "[FLOW-CLOUD-FORWARD] Time: %.2f - Cloud (ID:%d) - External task %d successfully sent to fog node %d",
                        CloudSim.clock(), getId(), task.getCloudletId(), fogNodeId));

                // Emit forwarding event for monitoring
                schedule(getId(), 0, ExtendedFogEvents.TASK_FORWARDED, task);
            } else {
                // [DEBUG] Log fog node not found
                System.out.println(String.format(
                        "[FLOW-CLOUD-FORWARD] Time: %.2f - Cloud (ID:%d) - Fog node %s NOT FOUND, using fallback routing for task %d",
                        currentTime, getId(), allocatedNodeId, task.getCloudletId()));

                logger.warning("Fog node " + allocatedNodeId + " not found, using fallback routing");
                // Fallback to default routing
                sendDown(task, Integer.parseInt(allocatedNodeId));
            }
        } catch (Exception e) {
            // [DEBUG] Log forwarding error
            System.out.println(String.format(
                    "[FLOW-CLOUD-FORWARD] Time: %.2f - Cloud (ID:%d) - ERROR forwarding task %d to fog node %s: %s",
                    CloudSim.clock(), getId(), task.getCloudletId(), allocatedNodeId, e.getMessage()));

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
            // Get actual host utilization for task outcome reporting
            // CPU: getUtilizationOfCpu() returns percentage [0.0, 1.0] - use directly
            double cpuUtilization = getHost().getUtilizationOfCpu();
            
            // Memory: getUtilizationOfRam() returns MB USED (not percentage!), convert to percentage [0.0, 1.0]
            double ramUsedMb = getHost().getUtilizationOfRam();
            int totalRamMb = getHost().getRam();
            double ramUtilization = (totalRamMb > 0) ? (ramUsedMb / totalRamMb) : 0.0;
            // Clamp to valid range
            if (ramUtilization < 0.0) ramUtilization = 0.0;
            if (ramUtilization > 1.0) ramUtilization = 1.0;
            
            allocationClient.reportTaskOutcome(
                    String.valueOf(tuple.getCloudletId()),
                    String.valueOf(getId()),
                    success,
                    executionTime,
                    cpuUtilization, // Percentage [0.0, 1.0]
                    ramUtilization, // Percentage [0.0, 1.0]
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
            
            // Resource utilization (normalized to percentages [0.0, 1.0] for consistency)
            // CPU: getUtilizationOfCpu() returns percentage [0.0, 1.0] - use directly
            double cpuUtilization = getHost().getUtilizationOfCpu();
            
            // Memory: getUtilizationOfRam() returns MB USED (not percentage!), convert to percentage [0.0, 1.0]
            double ramUsedMb = getHost().getUtilizationOfRam();
            int totalRamMb = getHost().getRam();
            double ramUtilization = (totalRamMb > 0) ? (ramUsedMb / totalRamMb) : 0.0;
            // Clamp to valid range
            if (ramUtilization < 0.0) ramUtilization = 0.0;
            if (ramUtilization > 1.0) ramUtilization = 1.0;
            
            state.put("cpuUtilization", cpuUtilization); // Percentage [0.0, 1.0]
            state.put("ramUtilization", ramUtilization); // Percentage [0.0, 1.0]
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

            // [DEBUG] Log fog node collection
            double currentTime = CloudSim.clock();
            logger.info(String.format(
                    "[FLOW-FOG-REGISTRY] Time: %.2f - Cloud (ID:%d) - Collected %d fog nodes for registration",
                    currentTime, getId(), fogNodes.size()));

            if (fogNodes.isEmpty()) {
                logger.warning("No connected fog nodes found for state reporting");
                return;
            }

            // [DEBUG] Check server state before sending
            if (!allocationClient.isServiceHealthy()) {
                logger.warning(String.format(
                        "[FLOW-FOG-REGISTRY] Time: %.2f - Cloud (ID:%d) - Allocator server NOT healthy, cannot send fog nodes",
                        currentTime, getId()));
                return;
            }

            logger.info(String.format(
                    "[FLOW-FOG-REGISTRY] Time: %.2f - Cloud (ID:%d) - Allocator server is healthy, sending %d fog nodes",
                    currentTime, getId(), fogNodes.size()));

            // Send node states to allocation service for RL learning
            int successfullyReported = 0;
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

                    // CPU: CpuUsage is 0-100 percentage, convert to [0.0, 1.0]
                    double cpuUtil = fogNode.getCurrentUsage().getCpuUsage() / 100.0;
                    
                    // Memory: MemoryUsageMb is actual MB used, convert to percentage [0.0, 1.0]
                    double memUtil = 0.0;
                    if (fogNode.getCapacity() != null && fogNode.getCapacity().getMemoryMb() > 0) {
                        memUtil = (double) fogNode.getCurrentUsage().getMemoryUsageMb() / (double) fogNode.getCapacity().getMemoryMb();
                        // Clamp to [0.0, 1.0]
                        if (memUtil < 0.0) memUtil = 0.0;
                        if (memUtil > 1.0) memUtil = 1.0;
                    }

                    // Create node state request
                    NodeStateRequest nodeStateRequest = NodeStateRequest.newBuilder()
                            .setNodeId(fogNode.getNodeId())
                            .setCpuUtilization(cpuUtil) // Convert percentage to decimal
                            .setMemoryUtilization(memUtil) // Convert percentage to decimal
                            .setNetworkBandwidth(fogNode.getCapacity().getNetworkBandwidthMbps())
                            .setTaskCount(taskCount) // Get actual task count from device
                            .build();

                    // [DEBUG] Log before sending
                    logger.info(String.format(
                            "[FLOW-FOG-REGISTRY] Time: %.2f - Cloud (ID:%d) - Sending fog node state: nodeId=%s, CPU=%.2f, Mem=%.2f, Tasks=%d",
                            currentTime, getId(), fogNode.getNodeId(), cpuUtil, memUtil, taskCount));

                    // Actually send state to allocation service
                    allocationClient.reportNodeState(nodeStateRequest);

                    // [DEBUG] Log after successful send
                    logger.info(String.format(
                            "[FLOW-FOG-REGISTRY] Time: %.2f - Cloud (ID:%d) - Successfully sent fog node state: nodeId=%s",
                            currentTime, getId(), fogNode.getNodeId()));
                    successfullyReported++;

                } catch (Exception e) {
                    logger.warning(String.format(
                            "[FLOW-FOG-REGISTRY] Time: %.2f - Cloud (ID:%d) - Failed to report state for fog node %s: %s",
                            currentTime, getId(), fogNode.getNodeId(), e.getMessage()));
                    logger.log(Level.WARNING, "Failed to report state for fog node " + fogNode.getNodeId(), e);
                }
            }

            logger.info(String.format(
                    "[FLOW-FOG-REGISTRY] Time: %.2f - Cloud (ID:%d) - Updated fog nodes information: %d/%d nodes successfully reported to allocation service",
                    currentTime, getId(), successfullyReported, fogNodes.size()));

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
            // CPU: getUtilizationOfCpu() returns percentage [0.0, 1.0], convert to 0-100 integer
            // Memory: getUtilizationOfRam() returns MB USED (not percentage!), use directly
            double ramUsedMb = device.getHost().getUtilizationOfRam();
            
            ResourceUsage currentUsage = ResourceUsage.newBuilder()
                    .setCpuUsage(Math.round(device.getHost().getUtilizationOfCpu() * 100)) // Convert percentage to 0-100
                    .setMemoryUsageMb(Math.round(ramUsedMb)) // Already in MB, use directly
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
     * Get successful allocation count
     */
    public long getSuccessfulAllocations() {
        return successfulAllocations;
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
        // [DEBUG] Check allocator state before external task routing
        double currentTime = CloudSim.clock();
        if (allocationClient == null || !allocationClient.isConnected()) {
            logger.warning(String.format(
                    "[FLOW-EXT-TASK] Time: %.2f - Cloud (ID:%d) - Allocator NOT available (client:%s, connected:%s), using fallback",
                    currentTime, getId(),
                    allocationClient != null ? "exists" : "null",
                    allocationClient != null && allocationClient.isConnected()));
            // Fallback to simple round-robin or first available fog node
            forwardTaskToFirstAvailableFogNode(task);
            return;
        }

        // Check service health (not idle) before routing
        if (!allocationClient.isServiceHealthy()) {
            logger.warning(String.format(
                    "[FLOW-EXT-TASK] Time: %.2f - Cloud (ID:%d) - Allocator server NOT healthy (idle/unavailable), using fallback",
                    currentTime, getId()));
            forwardTaskToFirstAvailableFogNode(task);
            return;
        }

        logger.info(String.format(
                "[FLOW-EXT-TASK] Time: %.2f - Cloud (ID:%d) - Allocator available and healthy, routing external task",
                currentTime, getId()));

        try {
            // Store task for later retrieval
            String taskId = String.valueOf(task.getCloudletId());
            pendingAllocations.put(taskId, task);

            // [DEBUG] Log before allocation request
            logger.info(String.format(
                    "[FLOW-EXT-TASK] Time: %.2f - Cloud (ID:%d) - Requesting allocation for task: taskId=%s, CPU=%.2f, Mem=%.2f",
                    CloudSim.clock(), getId(), taskId, task.getCloudletLength(), task.getCloudletFileSize()));

            // Send allocation request using the correct method signature
            TaskAllocationResponse response = allocationClient.allocateTask(
                    taskId,
                    task.getCloudletLength(), // CPU requirement
                    task.getCloudletFileSize(), // Memory requirement
                    EnhancedConfigurationLoader.getSimulationConfigDouble("simulation.allocation.default-bandwidth",
                            0.0), // Bandwidth requirement
                    EnhancedConfigurationLoader.getSimulationConfigInt("simulation.allocation.default-priority", 1), // Priority
                    0L, // Later Feature: deadline-aware disabled
                    createTaskMetadata(task));

            // [DEBUG] Log allocation response
            logger.info(String.format(
                    "[FLOW-EXT-TASK] Time: %.2f - Cloud (ID:%d) - Allocation response: taskId=%s, allocatedNode=%s, success=%s",
                    CloudSim.clock(), getId(), taskId, response.getAllocatedNodeId(), response.getSuccess()));

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
     * Handle gRPC allocator response event ()
     * Processes async allocation response, records energy/cost, and updates task
     * state
     */
    private void handleGrpcAllocatorResponse(SimEvent ev) {
        PendingAllocationRequest pending = (PendingAllocationRequest) ev.getData();
        String taskId = pending.getTaskId();
        double currentTime = CloudSim.clock();

        logger.info(String.format(
                "[GRPC-ALLOCATOR-RESPONSE] Time: %.2f - Processing async allocator response for task: %s",
                currentTime, taskId));

        // Validate pending request state
        PendingAllocationRequest storedPending = pendingAllocationRequests.get(taskId);
        if (storedPending == null) {
            // Request not found in map - might be orphaned or already processed
            logger.warning(String.format(
                    "[DEBUG-PENDING-ALLOCATION] Time: %.2f - Device: %s (ID:%d) - Pending request not found for task: %s (may be orphaned, total pending: %d)",
                    currentTime, getName(), getId(), taskId, pendingAllocationRequests.size()));
            // Still try to process the response from the event data
        } else if (storedPending != pending) {
            // Different pending request found - log warning but continue
            logger.warning(String.format(
                    "[DEBUG-PENDING-ALLOCATION] Time: %.2f - Device: %s (ID:%d) - Pending request mismatch for task: %s (stored != event)",
                    currentTime, getName(), getId(), taskId));
        } else {
            logger.info(String.format(
                    "[DEBUG-PENDING-ALLOCATION] Time: %.2f - Device: %s (ID:%d) - Pending request found and validated for task: %s",
                    currentTime, getName(), getId(), taskId));
        }

        try {
            // Check if future completed successfully
            if (pending.getFuture().isCompletedExceptionally()) {
                logger.severe(String.format(
                        "[GRPC-ALLOCATOR-RESPONSE] Time: %.2f - Async allocator call failed for task: %s",
                        currentTime, taskId));
                pendingAllocationRequests.remove(taskId);
                return;
            }

            // Get response
            TaskAllocationResponse response = pending.getFuture().get();

            // Calculate actual latency and energy/cost
            long realLatency = System.currentTimeMillis() - pending.getRealStartTime();
            double simulationLatency = NetworkLatencyConverter.convertToSimulationTime(realLatency);

            // Estimate message size
            long messageSizeBytes = estimateAllocationMessageSize(taskId);
            double actualEnergy = NetworkEnergyCostCalculator.calculateNetworkEnergy(
                    simulationLatency, messageSizeBytes);
            double actualCost = NetworkEnergyCostCalculator.calculateNetworkCost(
                    simulationLatency, messageSizeBytes);

            // Record energy and cost in statistics
            RLStatisticsManager.getInstance().addAllocationEnergy(actualEnergy);
            RLStatisticsManager.getInstance().addAllocationCost(actualCost);
            RLStatisticsManager.getInstance().addAllocationLatency(realLatency);
            RLStatisticsManager.getInstance().incrementAllocationDecisions();

            logger.info(String.format(
                    "[GRPC-ALLOCATOR-RESPONSE] Time: %.2f - Task: %s, Success: %s, Node: %s, Latency: %dms (sim: %.4f sec), Energy: %.6f J, Cost: %.8f $",
                    currentTime, taskId, response.getSuccess(), response.getAllocatedNodeId(),
                    realLatency, simulationLatency, actualEnergy, actualCost));

            // 
            org.fog.entities.Tuple tuple = pending.getTuple();
            if (tuple != null) {
                // Update pendingAllocations map if needed
                pendingAllocations.put(taskId, tuple);
            }

            // Process the response (use existing handleAllocationResponse method)
            handleAllocationResponse(response);

            // 
            pendingAllocationRequests.remove(taskId);
            pendingAllocations.remove(taskId); // Also remove from old pendingAllocations map

        } catch (Exception e) {
            logger.severe(String.format(
                    "[GRPC-ALLOCATOR-RESPONSE] Time: %.2f - Error processing async allocator response for task: %s - %s",
                    currentTime, taskId, e.getMessage()));
            e.printStackTrace();
            pendingAllocationRequests.remove(taskId);
        }
    }

    /**
     * Handle gRPC allocator outcome response event ()
     * Processes async outcome reporting response and records energy/cost
     */
    private void handleGrpcAllocatorOutcomeResponse(SimEvent ev) {
        PendingOutcomeRequest pending = (PendingOutcomeRequest) ev.getData();
        String taskId = pending.getTaskId();
        double currentTime = CloudSim.clock();

        logger.info(String.format(
                "[GRPC-ALLOCATOR-OUTCOME-RESPONSE] Time: %.2f - Processing async outcome response for task: %s",
                currentTime, taskId));

        // Validate pending request state
        PendingOutcomeRequest storedPending = pendingOutcomeRequests.get(taskId);
        if (storedPending == null) {
            logger.warning(String.format(
                    "[DEBUG-PENDING-OUTCOME] Time: %.2f - Device: %s (ID:%d) - Pending request not found for task: %s (may be orphaned, total pending: %d)",
                    currentTime, getName(), getId(), taskId, pendingOutcomeRequests.size()));
        } else if (storedPending != pending) {
            logger.warning(String.format(
                    "[DEBUG-PENDING-OUTCOME] Time: %.2f - Device: %s (ID:%d) - Pending request mismatch for task: %s (stored != event)",
                    currentTime, getName(), getId(), taskId));
        } else {
            logger.info(String.format(
                    "[DEBUG-PENDING-OUTCOME] Time: %.2f - Device: %s (ID:%d) - Pending request found and validated for task: %s",
                    currentTime, getName(), getId(), taskId));
        }

        try {
            // Check if future completed successfully
            if (pending.getFuture().isCompletedExceptionally()) {
                logger.warning(String.format(
                        "[GRPC-ALLOCATOR-OUTCOME-RESPONSE] Time: %.2f - Async outcome call failed for task: %s",
                        currentTime, taskId));
                pendingOutcomeRequests.remove(taskId);
                return;
            }

            // Get response
            TaskOutcomeResponse response = pending.getFuture().get();

            // Calculate actual latency and energy/cost
            long realLatency = System.currentTimeMillis() - pending.getRealStartTime();
            double simulationLatency = NetworkLatencyConverter.convertToSimulationTime(realLatency);

            // Estimate message size
            long messageSizeBytes = estimateOutcomeMessageSize(taskId);
            double actualEnergy = NetworkEnergyCostCalculator.calculateNetworkEnergy(
                    simulationLatency, messageSizeBytes);
            double actualCost = NetworkEnergyCostCalculator.calculateNetworkCost(
                    simulationLatency, messageSizeBytes);

            // Record energy and cost in statistics
            RLStatisticsManager.getInstance().addAllocationEnergy(actualEnergy);
            RLStatisticsManager.getInstance().addAllocationCost(actualCost);

            logger.info(String.format(
                    "[GRPC-ALLOCATOR-OUTCOME-RESPONSE] Time: %.2f - Task: %s, Latency: %dms (sim: %.4f sec), Energy: %.6f J, Cost: %.8f $",
                    currentTime, taskId, realLatency, simulationLatency, actualEnergy, actualCost));

            // Remove from pending requests
            pendingOutcomeRequests.remove(taskId);

        } catch (Exception e) {
            logger.warning(String.format(
                    "[GRPC-ALLOCATOR-OUTCOME-RESPONSE] Time: %.2f - Error processing async outcome response for task: %s - %s",
                    currentTime, taskId, e.getMessage()));
            pendingOutcomeRequests.remove(taskId);
        }
    }

    /**
     * Estimate message size for allocation request (helper method)
     */
    private long estimateAllocationMessageSize(String taskId) {
        long size = 100; // Base overhead
        size += (taskId != null ? taskId.length() : 0) * 2;
        return size;
    }

    /**
     * Estimate message size for outcome report (helper method)
     */
    private long estimateOutcomeMessageSize(String taskId) {
        long size = 50; // Base overhead
        size += (taskId != null ? taskId.length() : 0) * 2;
        return size;
    }

    // ===== PHASE 4: STATE MANAGEMENT HELPERS =====

    /**
     * Store pending allocation request ()
     * Should be called when async allocation request is made
     * 
     * @param pending Pending allocation request to store
     */
    public void storePendingAllocationRequest(PendingAllocationRequest pending) {
        if (pending != null) {
            pendingAllocationRequests.put(pending.getTaskId(), pending);
            logger.fine(String.format(
                    "[STATE-MGMT] Stored pending allocation request for task: %s (total pending: %d)",
                    pending.getTaskId(), pendingAllocationRequests.size()));
        }
    }

    /**
     * Store pending outcome request ()
     * Should be called when async outcome request is made
     * 
     * @param pending Pending outcome request to store
     */
    public void storePendingOutcomeRequest(PendingOutcomeRequest pending) {
        if (pending != null) {
            pendingOutcomeRequests.put(pending.getTaskId(), pending);
            logger.fine(String.format(
                    "[STATE-MGMT] Stored pending outcome request for task: %s (total pending: %d)",
                    pending.getTaskId(), pendingOutcomeRequests.size()));
        }
    }

    /**
     * Get pending allocation request ()
     * 
     * @param taskId Task identifier
     * @return Pending request or null if not found
     */
    public PendingAllocationRequest getPendingAllocationRequest(String taskId) {
        return pendingAllocationRequests.get(taskId);
    }

    /**
     * Get pending outcome request ()
     * 
     * @param taskId Task identifier
     * @return Pending request or null if not found
     */
    public PendingOutcomeRequest getPendingOutcomeRequest(String taskId) {
        return pendingOutcomeRequests.get(taskId);
    }

    /**
     * Cleanup orphaned pending requests ()
     * Removes requests that are older than specified timeout
     * 
     * @param timeoutMs Timeout in milliseconds
     */
    public void cleanupOrphanedPendingRequests(long timeoutMs) {
        long currentTime = System.currentTimeMillis();
        List<String> allocationToRemove = new ArrayList<>();
        List<String> outcomeToRemove = new ArrayList<>();

        // Cleanup allocation requests
        for (Map.Entry<String, PendingAllocationRequest> entry : pendingAllocationRequests.entrySet()) {
            PendingAllocationRequest pending = entry.getValue();
            long age = currentTime - pending.getRealStartTime();
            if (age > timeoutMs) {
                allocationToRemove.add(entry.getKey());
                logger.warning(String.format(
                        "[STATE-MGMT] Cleaning up orphaned pending allocation request for task: %s (age: %dms)",
                        entry.getKey(), age));
            }
        }

        // Cleanup outcome requests
        for (Map.Entry<String, PendingOutcomeRequest> entry : pendingOutcomeRequests.entrySet()) {
            PendingOutcomeRequest pending = entry.getValue();
            long age = currentTime - pending.getRealStartTime();
            if (age > timeoutMs) {
                outcomeToRemove.add(entry.getKey());
                logger.warning(String.format(
                        "[STATE-MGMT] Cleaning up orphaned pending outcome request for task: %s (age: %dms)",
                        entry.getKey(), age));
            }
        }

        // Remove orphaned requests
        for (String taskId : allocationToRemove) {
            pendingAllocationRequests.remove(taskId);
        }
        for (String taskId : outcomeToRemove) {
            pendingOutcomeRequests.remove(taskId);
        }

        if (!allocationToRemove.isEmpty() || !outcomeToRemove.isEmpty()) {
            logger.info(String.format(
                    "[STATE-MGMT] Cleaned up %d orphaned allocation requests and %d orphaned outcome requests (remaining: %d allocation, %d outcome)",
                    allocationToRemove.size(), outcomeToRemove.size(),
                    pendingAllocationRequests.size(), pendingOutcomeRequests.size()));
        }
    }

    /**
     * Handle gRPC allocator timeout event ()
     * Processes timeout for async allocation request and falls back to local
     * allocation
     */
    private void handleGrpcAllocatorTimeout(SimEvent ev) {
        PendingAllocationRequest pending = (PendingAllocationRequest) ev.getData();
        String taskId = pending.getTaskId();
        double currentTime = CloudSim.clock();

        logger.warning(String.format(
                "[DEBUG-ALLOCATOR-TIMEOUT] Time: %.2f - Device: %s (ID:%d) - Allocator call timed out for task: %s",
                currentTime, getName(), getId(), taskId));

        // Check if request already completed (race condition: response arrived before
        // timeout)
        if (pending.getFuture().isDone() && !pending.getFuture().isCompletedExceptionally()) {
            logger.info(String.format(
                    "[DEBUG-ALLOCATOR-TIMEOUT] Time: %.2f - Device: %s (ID:%d) - Task %s already completed, ignoring timeout",
                    currentTime, getName(), getId(), taskId));
            return;
        }
        
        logger.info(String.format(
                "[DEBUG-ALLOCATOR-TIMEOUT] Time: %.2f - Device: %s (ID:%d) - Timeout confirmed, proceeding with fallback for task: %s",
                currentTime, getName(), getId(), taskId));

        // 
        try {
            org.fog.entities.Tuple tuple = pending.getTuple();
            if (tuple != null) {
                logger.info(String.format(
                        "[GRPC-ALLOCATOR-TIMEOUT] Time: %.2f - Falling back to local allocation for task: %s",
                        currentTime, taskId));

                // Create fallback response
                TaskAllocationResponse fallbackResponse = createFallbackAllocationResponse(
                        taskId, tuple.getCloudletLength(), tuple.getCloudletFileSize());

                // Process fallback response
                handleAllocationResponse(fallbackResponse);
            } else {
                logger.warning(String.format(
                        "[GRPC-ALLOCATOR-TIMEOUT] Time: %.2f - No tuple available for fallback, task: %s",
                        currentTime, taskId));
            }
        } catch (Exception e) {
            logger.severe(String.format(
                    "[GRPC-ALLOCATOR-TIMEOUT] Time: %.2f - Error in fallback allocation for task: %s - %s",
                    currentTime, taskId, e.getMessage()));
            e.printStackTrace();
        } finally {
            // Clean up pending request
            pendingAllocationRequests.remove(taskId);
            pendingAllocations.remove(taskId);
        }
    }

    /**
     * Handle gRPC allocator outcome timeout event (
     * Timeouts)
     * Processes timeout for async outcome reporting (best-effort, no fallback
     * needed)
     */
    private void handleGrpcAllocatorOutcomeTimeout(SimEvent ev) {
        PendingOutcomeRequest pending = (PendingOutcomeRequest) ev.getData();
        String taskId = pending.getTaskId();
        double currentTime = CloudSim.clock();

        logger.warning(String.format(
                "[DEBUG-OUTCOME-TIMEOUT] Time: %.2f - Device: %s (ID:%d) - Outcome report timed out for task: %s",
                currentTime, getName(), getId(), taskId));

        // Check if request already completed
        if (pending.getFuture().isDone() && !pending.getFuture().isCompletedExceptionally()) {
            logger.info(String.format(
                    "[DEBUG-OUTCOME-TIMEOUT] Time: %.2f - Device: %s (ID:%d) - Task %s already completed, ignoring timeout",
                    currentTime, getName(), getId(), taskId));
            return;
        }

        // Outcome reporting is best-effort, just log and clean up
        logger.info(String.format(
                "[DEBUG-OUTCOME-TIMEOUT] Time: %.2f - Device: %s (ID:%d) - Outcome report timeout for task: %s (best-effort, no fallback)",
                currentTime, getName(), getId(), taskId));

        // Clean up pending request
        pendingOutcomeRequests.remove(taskId);
    }

    /**
     * Create fallback allocation response ()
     */
    private TaskAllocationResponse createFallbackAllocationResponse(String taskId,
            double cpuRequirement, double memoryRequirement) {
        String fallbackNodeId = EnhancedConfigurationLoader.getGrpcConfig(
                "grpc.fallback.node.id", "fallback-node-1");
        long executionTime = EnhancedConfigurationLoader.getGrpcConfigLong(
                "grpc.fallback.execution.time", 5000);
        long currentTime = System.currentTimeMillis();

        return TaskAllocationResponse.newBuilder()
                .setSuccess(true)
                .setAllocatedNodeId(fallbackNodeId)
                .setExpectedCompletionTimeMs(currentTime + executionTime)
                .setMessage("Using fallback allocation - gRPC timeout")
                .build();
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

    /**
     * Handle allocation outcome report from fog device
     * This is called when a fog device reports task completion for an external task
     */
    private void handleAllocOutcomeReport(SimEvent ev) {
        try {
            Object[] data = (Object[]) ev.getData();
            Tuple tuple = (Tuple) data[0];
            boolean success = (boolean) data[1];
            long executionTime = (long) data[2];

            // Report to go-grpc-server allocator
            reportTaskOutcome(tuple, success, executionTime);
            logger.info("Allocation outcome reported for task: " + tuple.getCloudletId());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling allocation outcome report", e);
        }
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