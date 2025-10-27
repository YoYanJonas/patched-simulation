package org.patch.processing;

import org.fog.entities.Tuple;

/**
 * RL Tuple Processing Result
 * 
 * This class represents the result of tuple processing
 * with RL-based decisions.
 * 
 * @author Younes Shafiee
 */
public class RLTupleProcessingResult {
    private final Tuple tuple;
    private final boolean success;
    private final String processingPath;
    private final long processingTime;
    private final double energyConsumed;
    private final double cost;
    private final String processingType;

    /**
     * Constructor for RLTupleProcessingResult
     * 
     * @param tuple          Processed tuple
     * @param success        Whether processing was successful
     * @param processingPath Path used for processing
     * @param processingTime Time taken for processing
     * @param energyConsumed Energy consumed during processing
     * @param cost           Cost of processing
     * @param processingType Type of processing used
     */
    public RLTupleProcessingResult(Tuple tuple, boolean success, String processingPath,
            long processingTime, double energyConsumed, double cost,
            String processingType) {
        this.tuple = tuple;
        this.success = success;
        this.processingPath = processingPath;
        this.processingTime = processingTime;
        this.energyConsumed = energyConsumed;
        this.cost = cost;
        this.processingType = processingType;
    }

    /**
     * Get the processed tuple
     * 
     * @return Tuple
     */
    public Tuple getTuple() {
        return tuple;
    }

    /**
     * Check if processing was successful
     * 
     * @return True if successful
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Get processing path
     * 
     * @return Processing path
     */
    public String getProcessingPath() {
        return processingPath;
    }

    /**
     * Get processing time
     * 
     * @return Processing time in milliseconds
     */
    public long getProcessingTime() {
        return processingTime;
    }

    /**
     * Get energy consumed
     * 
     * @return Energy consumed in Joules
     */
    public double getEnergyConsumed() {
        return energyConsumed;
    }

    /**
     * Get cost
     * 
     * @return Cost of processing
     */
    public double getCost() {
        return cost;
    }

    /**
     * Get processing type
     * 
     * @return Processing type
     */
    public String getProcessingType() {
        return processingType;
    }

    @Override
    public String toString() {
        return "RLTupleProcessingResult{" +
                "tuple=" + tuple.getCloudletId() +
                ", success=" + success +
                ", processingPath='" + processingPath + '\'' +
                ", processingTime=" + processingTime +
                ", energyConsumed=" + energyConsumed +
                ", cost=" + cost +
                ", processingType='" + processingType + '\'' +
                '}';
    }
}
