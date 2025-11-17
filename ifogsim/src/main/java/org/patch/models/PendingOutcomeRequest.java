package org.patch.models;

import org.patch.proto.IfogsimAllocation.TaskOutcomeResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a pending outcome reporting request for event-based gRPC operations.
 * 
 * <p>
 * This class tracks the state of an async outcome reporting request, including:
 * - Task information
 * - CompletableFuture for the response
 * - Timing information (real and simulation)
 * - Estimated energy and cost
 * </p>
 * 
 * <p>
 * 
 * </p>
 * 
 * @author Younes Shafiee
 * @version 1.0.0
 * @since 1.0.0
 */
public class PendingOutcomeRequest {
    private final String taskId;
    private final CompletableFuture<TaskOutcomeResponse> future;
    private final long realStartTime;
    private final double simulationStartTime;
    private final double estimatedEnergy;
    private final double estimatedCost;
    
    /**
     * Constructor
     * 
     * @param taskId Task identifier
     * @param future CompletableFuture for the response
     * @param realStartTime Real-world start time (milliseconds)
     * @param simulationStartTime Simulation start time (seconds)
     * @param estimatedEnergy Estimated energy consumption (Joules)
     * @param estimatedCost Estimated cost (dollars)
     */
    public PendingOutcomeRequest(
            String taskId,
            CompletableFuture<TaskOutcomeResponse> future,
            long realStartTime,
            double simulationStartTime,
            double estimatedEnergy,
            double estimatedCost) {
        this.taskId = taskId;
        this.future = future;
        this.realStartTime = realStartTime;
        this.simulationStartTime = simulationStartTime;
        this.estimatedEnergy = estimatedEnergy;
        this.estimatedCost = estimatedCost;
    }
    
    public String getTaskId() {
        return taskId;
    }
    
    public CompletableFuture<TaskOutcomeResponse> getFuture() {
        return future;
    }
    
    public long getRealStartTime() {
        return realStartTime;
    }
    
    public double getSimulationStartTime() {
        return simulationStartTime;
    }
    
    public double getEstimatedEnergy() {
        return estimatedEnergy;
    }
    
    public double getEstimatedCost() {
        return estimatedCost;
    }
}

