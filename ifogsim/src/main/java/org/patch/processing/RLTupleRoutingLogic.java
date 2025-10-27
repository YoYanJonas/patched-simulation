package org.patch.processing;

import org.fog.entities.Tuple;
import org.fog.entities.FogDevice;
import org.patch.client.AllocationClient;
import org.patch.client.SchedulerClient;

import java.util.*;

/**
 * RL Tuple Routing Logic for making routing decisions
 * 
 * This class handles the RL-based decision making for tuple routing
 * through the fog computing network.
 * 
 * @author Younes Shafiee
 */
public class RLTupleRoutingLogic {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLTupleRoutingLogic.class.getName());

    // RL clients
    private AllocationClient allocationClient;
    private Map<Integer, SchedulerClient> schedulerClients;

    // Routing history for learning
    private List<RLTupleRoutingDecision> routingHistory;

    // Flag to track if RL is configured
    private boolean rlConfigured = false;

    /**
     * Constructor for RLTupleRoutingLogic
     */
    public RLTupleRoutingLogic() {
        this.routingHistory = new ArrayList<>();
    }

    /**
     * Configure RL clients
     * 
     * @param allocationClient Allocation client
     * @param schedulerClients Scheduler clients
     */
    public void configureRLClients(AllocationClient allocationClient,
            Map<Integer, SchedulerClient> schedulerClients) {
        this.allocationClient = allocationClient;
        this.schedulerClients = schedulerClients;
        this.rlConfigured = true;

        logger.info("RL clients configured for tuple routing logic");
    }

    /**
     * Make routing decision for tuple
     * 
     * @param tuple        Tuple to route
     * @param sourceDevice Source device
     * @param targetDevice Target device
     * @return Routing decision
     */
    public RLTupleRoutingDecision makeRoutingDecision(Tuple tuple, FogDevice sourceDevice, FogDevice targetDevice) {
        if (!rlConfigured) {
            // Fallback to simple routing
            return createSimpleRoutingDecision(tuple, sourceDevice, targetDevice);
        }

        try {
            // Use RL-based routing decision
            return createRLRoutingDecision(tuple, sourceDevice, targetDevice);
        } catch (Exception e) {
            logger.warning("Error in RL routing decision: " + e.getMessage());
            return createSimpleRoutingDecision(tuple, sourceDevice, targetDevice);
        }
    }

    /**
     * Create simple routing decision (fallback)
     * 
     * @param tuple        Tuple
     * @param sourceDevice Source device
     * @param targetDevice Target device
     * @return Simple routing decision
     */
    private RLTupleRoutingDecision createSimpleRoutingDecision(Tuple tuple, FogDevice sourceDevice,
            FogDevice targetDevice) {
        boolean success = true;
        String processingPath = sourceDevice.getName() + " -> " + targetDevice.getName();
        double confidence = 0.8; // Default confidence

        RLTupleRoutingDecision decision = new RLTupleRoutingDecision(
                tuple,
                success,
                processingPath,
                confidence,
                "simple_routing");

        // Add to history
        routingHistory.add(decision);

        return decision;
    }

    /**
     * Create RL-based routing decision
     * 
     * @param tuple        Tuple
     * @param sourceDevice Source device
     * @param targetDevice Target device
     * @return RL routing decision
     */
    private RLTupleRoutingDecision createRLRoutingDecision(Tuple tuple, FogDevice sourceDevice,
            FogDevice targetDevice) {
        // Implement simplified RL decision logic
        // In production, this would consult the actual RL agent service

        boolean success = true;
        String processingPath = sourceDevice.getName() + " -> " + targetDevice.getName();
        double confidence = 0.9; // Higher confidence for RL decisions

        RLTupleRoutingDecision decision = new RLTupleRoutingDecision(
                tuple,
                success,
                processingPath,
                confidence,
                "rl_routing");

        // Add to history
        routingHistory.add(decision);

        logger.fine("Created RL routing decision for tuple: " + tuple.getCloudletId());

        return decision;
    }

    /**
     * Get routing history
     * 
     * @return List of routing decisions
     */
    public List<RLTupleRoutingDecision> getRoutingHistory() {
        return new ArrayList<>(routingHistory);
    }

    /**
     * Check if RL is configured
     * 
     * @return True if RL is configured
     */
    public boolean isRLConfigured() {
        return rlConfigured;
    }
}
