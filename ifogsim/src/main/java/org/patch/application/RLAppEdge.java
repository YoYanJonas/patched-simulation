package org.patch.application;

import org.fog.application.AppEdge;

/**
 * RL-Enhanced AppEdge with energy and cost characteristics
 * 
 * This class extends the original AppEdge to include RL-specific
 * characteristics like energy cost and network cost for data transfer.
 * 
 * @author Younes Shafiee
 */
public class RLAppEdge extends AppEdge {
    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger(RLAppEdge.class.getName());

    // RL-specific characteristics
    private double energyCost;
    private double networkCost;
    private double dataTransferEfficiency;
    private double networkEfficiency;

    // Performance tracking
    private long transferCount;
    private double totalEnergyCost;
    private double totalNetworkCost;
    private long totalTransferTime;

    /**
     * Constructor for RLAppEdge
     * 
     * @param source         Source module
     * @param destination    Destination module
     * @param tupleCpuLength Tuple CPU length
     * @param tupleNwLength  Tuple network length
     * @param tupleType      Tuple type
     * @param direction      Direction
     * @param edgeType       Edge type
     * @param energyCost     Energy cost for data transfer
     * @param networkCost    Network cost for data transfer
     */
    public RLAppEdge(String source, String destination, double tupleCpuLength,
            double tupleNwLength, String tupleType, int direction, int edgeType,
            double energyCost, double networkCost) {
        super(source, destination, tupleCpuLength, tupleNwLength, tupleType, direction, edgeType);

        this.energyCost = energyCost;
        this.networkCost = networkCost;
        this.dataTransferEfficiency = 1.0; // Default efficiency
        this.networkEfficiency = 1.0; // Default efficiency

        this.transferCount = 0;
        this.totalEnergyCost = 0.0;
        this.totalNetworkCost = 0.0;
        this.totalTransferTime = 0;

        logger.fine("RLAppEdge created: " + source + " -> " + destination +
                " (energy: " + energyCost + ", network: " + networkCost + ")");
    }

    /**
     * Constructor from existing AppEdge
     * 
     * @param edge Existing AppEdge
     */
    public RLAppEdge(AppEdge edge) {
        super(edge.getSource(), edge.getDestination(), edge.getTupleCpuLength(),
                edge.getTupleNwLength(), edge.getTupleType(), edge.getDirection(), edge.getEdgeType());

        // Set default RL characteristics
        this.energyCost = 0.1; // Default energy cost
        this.networkCost = 0.05; // Default network cost
        this.dataTransferEfficiency = 1.0;
        this.networkEfficiency = 1.0;

        this.transferCount = 0;
        this.totalEnergyCost = 0.0;
        this.totalNetworkCost = 0.0;
        this.totalTransferTime = 0;

        logger.fine("RLAppEdge created from AppEdge: " + edge.getSource() + " -> " + edge.getDestination());
    }

    /**
     * Process data transfer with RL characteristics
     * 
     * @param tupleSize    Size of data being transferred
     * @param transferTime Transfer time in milliseconds
     */
    public void processDataTransfer(long tupleSize, long transferTime) {
        transferCount++;
        totalTransferTime += transferTime;

        // Calculate actual energy cost based on tuple size and efficiency
        double actualEnergyCost = energyCost * (tupleSize / 1000.0) * dataTransferEfficiency;
        totalEnergyCost += actualEnergyCost;

        // Calculate actual network cost based on tuple size and efficiency
        double actualNetworkCost = networkCost * (tupleSize / 1000.0) * networkEfficiency;
        totalNetworkCost += actualNetworkCost;

        logger.fine("Processed data transfer for edge " + getTupleType() +
                " (size: " + tupleSize +
                ", time: " + transferTime +
                ", energy: " + actualEnergyCost +
                ", network: " + actualNetworkCost + ")");
    }

    /**
     * Get energy cost for data transfer
     * 
     * @return Energy cost
     */
    public double getEnergyCost() {
        return energyCost;
    }

    /**
     * Set energy cost for data transfer
     * 
     * @param energyCost Energy cost
     */
    public void setEnergyCost(double energyCost) {
        this.energyCost = energyCost;
    }

    /**
     * Get network cost for data transfer
     * 
     * @return Network cost
     */
    public double getNetworkCost() {
        return networkCost;
    }

    /**
     * Set network cost for data transfer
     * 
     * @param networkCost Network cost
     */
    public void setNetworkCost(double networkCost) {
        this.networkCost = networkCost;
    }

    /**
     * Get data transfer efficiency
     * 
     * @return Data transfer efficiency
     */
    public double getDataTransferEfficiency() {
        return dataTransferEfficiency;
    }

    /**
     * Set data transfer efficiency
     * 
     * @param dataTransferEfficiency Data transfer efficiency
     */
    public void setDataTransferEfficiency(double dataTransferEfficiency) {
        this.dataTransferEfficiency = dataTransferEfficiency;
    }

    /**
     * Get network efficiency
     * 
     * @return Network efficiency
     */
    public double getNetworkEfficiency() {
        return networkEfficiency;
    }

    /**
     * Set network efficiency
     * 
     * @param networkEfficiency Network efficiency
     */
    public void setNetworkEfficiency(double networkEfficiency) {
        this.networkEfficiency = networkEfficiency;
    }

    /**
     * Get transfer count
     * 
     * @return Transfer count
     */
    public long getTransferCount() {
        return transferCount;
    }

    /**
     * Get total energy cost
     * 
     * @return Total energy cost
     */
    public double getTotalEnergyCost() {
        return totalEnergyCost;
    }

    /**
     * Get total network cost
     * 
     * @return Total network cost
     */
    public double getTotalNetworkCost() {
        return totalNetworkCost;
    }

    /**
     * Get total transfer time
     * 
     * @return Total transfer time
     */
    public long getTotalTransferTime() {
        return totalTransferTime;
    }

    /**
     * Get average transfer time
     * 
     * @return Average transfer time
     */
    public double getAverageTransferTime() {
        return transferCount > 0 ? (double) totalTransferTime / transferCount : 0.0;
    }

    /**
     * Get average energy cost per transfer
     * 
     * @return Average energy cost
     */
    public double getAverageEnergyCost() {
        return transferCount > 0 ? totalEnergyCost / transferCount : 0.0;
    }

    /**
     * Get average network cost per transfer
     * 
     * @return Average network cost
     */
    public double getAverageNetworkCost() {
        return transferCount > 0 ? totalNetworkCost / transferCount : 0.0;
    }

    /**
     * Reset RL metrics
     */
    public void resetRLMetrics() {
        transferCount = 0;
        totalEnergyCost = 0.0;
        totalNetworkCost = 0.0;
        totalTransferTime = 0;

        logger.fine("Reset RL metrics for edge: " + getTupleType());
    }

    /**
     * Get RL performance summary
     * 
     * @return Performance summary
     */
    public String getRLPerformanceSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("RL Edge Performance Summary for ").append(getTupleType()).append("\n");
        summary.append("==========================================\n");
        summary.append("Source: ").append(getSource()).append("\n");
        summary.append("Destination: ").append(getDestination()).append("\n");
        summary.append("Transfer Count: ").append(transferCount).append("\n");
        summary.append("Total Energy Cost: ").append(totalEnergyCost).append("\n");
        summary.append("Total Network Cost: ").append(totalNetworkCost).append("\n");
        summary.append("Total Transfer Time: ").append(totalTransferTime).append(" ms\n");
        summary.append("Average Transfer Time: ").append(getAverageTransferTime()).append(" ms\n");
        summary.append("Average Energy Cost: ").append(getAverageEnergyCost()).append("\n");
        summary.append("Average Network Cost: ").append(getAverageNetworkCost()).append("\n");
        summary.append("Data Transfer Efficiency: ").append(dataTransferEfficiency).append("\n");
        summary.append("Network Efficiency: ").append(networkEfficiency).append("\n");

        return summary.toString();
    }
}
