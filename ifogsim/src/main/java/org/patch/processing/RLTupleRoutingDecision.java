package org.patch.processing;

import org.fog.entities.Tuple;

/**
 * RL Tuple Routing Decision
 * 
 * This class represents a routing decision made by the RL system
 * for tuple processing.
 * 
 * @author Younes Shafiee
 */
public class RLTupleRoutingDecision {
    private final Tuple tuple;
    private final boolean success;
    private final String processingPath;
    private final double confidence;
    private final String decisionType;

    /**
     * Constructor for RLTupleRoutingDecision
     * 
     * @param tuple          Tuple being routed
     * @param success        Whether routing was successful
     * @param processingPath Path for processing
     * @param confidence     Confidence in the decision
     * @param decisionType   Type of decision made
     */
    public RLTupleRoutingDecision(Tuple tuple, boolean success, String processingPath,
            double confidence, String decisionType) {
        this.tuple = tuple;
        this.success = success;
        this.processingPath = processingPath;
        this.confidence = confidence;
        this.decisionType = decisionType;
    }

    /**
     * Get the tuple
     * 
     * @return Tuple
     */
    public Tuple getTuple() {
        return tuple;
    }

    /**
     * Check if routing was successful
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
     * Get confidence in decision
     * 
     * @return Confidence value
     */
    public double getConfidence() {
        return confidence;
    }

    /**
     * Get decision type
     * 
     * @return Decision type
     */
    public String getDecisionType() {
        return decisionType;
    }

    @Override
    public String toString() {
        return "RLTupleRoutingDecision{" +
                "tuple=" + tuple.getCloudletId() +
                ", success=" + success +
                ", processingPath='" + processingPath + '\'' +
                ", confidence=" + confidence +
                ", decisionType='" + decisionType + '\'' +
                '}';
    }
}
