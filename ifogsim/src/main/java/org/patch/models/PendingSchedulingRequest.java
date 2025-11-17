package org.patch.models;

import org.patch.proto.IfogsimScheduler.AddTaskToQueueResponse;
import org.cloudbus.cloudsim.core.SimEvent;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a pending scheduling request for event-based gRPC operations.
 * 
 * <p>
 * This class tracks the state of an async scheduling request, including:
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
public class PendingSchedulingRequest {
    private final String taskId;
    private final org.patch.proto.IfogsimCommon.Task task;
    private final CompletableFuture<AddTaskToQueueResponse> future;
    private final long realStartTime;
    private final double simulationStartTime;
    private final double estimatedEnergy;
    private final double estimatedCost;
    private SimEvent timeoutEvent; // Store timeout event for cancellation
    
    /**
     * Constructor
     * 
     * @param taskId Task identifier
     * @param task Task proto object
     * @param future CompletableFuture for the response
     * @param realStartTime Real-world start time (milliseconds)
     * @param simulationStartTime Simulation start time (seconds)
     * @param estimatedEnergy Estimated energy consumption (Joules)
     * @param estimatedCost Estimated cost (dollars)
     */
    public PendingSchedulingRequest(
            String taskId,
            org.patch.proto.IfogsimCommon.Task task,
            CompletableFuture<AddTaskToQueueResponse> future,
            long realStartTime,
            double simulationStartTime,
            double estimatedEnergy,
            double estimatedCost) {
        this.taskId = taskId;
        this.task = task;
        this.future = future;
        this.realStartTime = realStartTime;
        this.simulationStartTime = simulationStartTime;
        this.estimatedEnergy = estimatedEnergy;
        this.estimatedCost = estimatedCost;
    }
    
    public String getTaskId() {
        return taskId;
    }
    
    public org.patch.proto.IfogsimCommon.Task getTask() {
        return task;
    }
    
    public CompletableFuture<AddTaskToQueueResponse> getFuture() {
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
    
    public void setTimeoutEvent(SimEvent event) {
        this.timeoutEvent = event;
    }
    
    public SimEvent getTimeoutEvent() {
        return timeoutEvent;
    }
}

