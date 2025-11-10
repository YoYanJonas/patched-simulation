package org.patch.models;

import org.patch.proto.IfogsimAllocation.TaskAllocationResponse;
import org.fog.entities.Tuple;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a pending allocation request for event-based gRPC operations.
 * 
 * <p>
 * This class tracks the state of an async allocation request, including:
 * - Task/Tuple information
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
public class PendingAllocationRequest {
    private final String taskId;
    private final Tuple tuple;
    private final CompletableFuture<TaskAllocationResponse> future;
    private final long realStartTime;
    private final double simulationStartTime;
    private final double estimatedEnergy;
    private final double estimatedCost;
    
    /**
     * Constructor
     * 
     * @param taskId Task identifier
     * @param tuple Tuple object
     * @param future CompletableFuture for the response
     * @param realStartTime Real-world start time (milliseconds)
     * @param simulationStartTime Simulation start time (seconds)
     * @param estimatedEnergy Estimated energy consumption (Joules)
     * @param estimatedCost Estimated cost (dollars)
     */
    public PendingAllocationRequest(
            String taskId,
            Tuple tuple,
            CompletableFuture<TaskAllocationResponse> future,
            long realStartTime,
            double simulationStartTime,
            double estimatedEnergy,
            double estimatedCost) {
        this.taskId = taskId;
        this.tuple = tuple;
        this.future = future;
        this.realStartTime = realStartTime;
        this.simulationStartTime = simulationStartTime;
        this.estimatedEnergy = estimatedEnergy;
        this.estimatedCost = estimatedCost;
    }
    
    public String getTaskId() {
        return taskId;
    }
    
    public Tuple getTuple() {
        return tuple;
    }
    
    public CompletableFuture<TaskAllocationResponse> getFuture() {
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

