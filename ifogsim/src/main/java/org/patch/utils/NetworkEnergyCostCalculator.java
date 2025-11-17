package org.patch.utils;

import org.patch.config.EnhancedConfigurationLoader;
import java.util.logging.Logger;

/**
 * Utility class for calculating network energy consumption and cost for gRPC operations.
 * 
 * <p>
 * This class provides methods to calculate energy and cost for network operations
 * based on simulation latency and message size. These calculations are essential
 * for complete simulation compatibility where network operations must consume
 * resources and incur costs.
 * </p>
 * 
 * <p>
 * Energy Calculation Formula:
 * </p>
 * <pre>
 * energy = base + (latency * latencyFactor) + (size * sizeFactor)
 * </pre>
 * 
 * <p>
 * Cost Calculation Formula:
 * </p>
 * <pre>
 * cost = base + (latency * latencyFactor) + (size * sizeFactor)
 * </pre>
 * 
 * <p>
 * Configuration:
 * </p>
 * <ul>
 * <li><b>Energy</b>:
 *   <ul>
 *   <li>{@code simulation.network.energy.base}: Base energy per operation (default: 0.001 J)</li>
 *   <li>{@code simulation.network.energy.latency-factor}: Energy per simulation second (default: 0.00001 J/sec)</li>
 *   <li>{@code simulation.network.energy.size-factor}: Energy per byte (default: 0.0000001 J/byte)</li>
 *   </ul>
 * </li>
 * <li><b>Cost</b>:
 *   <ul>
 *   <li>{@code simulation.network.cost.base}: Base cost per operation (default: 0.0001 $)</li>
 *   <li>{@code simulation.network.cost.latency-factor}: Cost per simulation second (default: 0.000001 $/sec)</li>
 *   <li>{@code simulation.network.cost.size-factor}: Cost per byte (default: 0.00000001 $/byte)</li>
 *   </ul>
 * </li>
 * </ul>
 * 
 * <p>
 * 
 * </p>
 * 
 * @author Younes Shafiee
 * @version 1.0.0
 * @since 1.0.0
 */
public class NetworkEnergyCostCalculator {
    private static final Logger logger = Logger.getLogger(NetworkEnergyCostCalculator.class.getName());
    
    // Cache configuration values for performance
    private static volatile double cachedEnergyBase = -1.0;
    private static volatile double cachedEnergyLatencyFactor = -1.0;
    private static volatile double cachedEnergySizeFactor = -1.0;
    private static volatile double cachedCostBase = -1.0;
    private static volatile double cachedCostLatencyFactor = -1.0;
    private static volatile double cachedCostSizeFactor = -1.0;
    
    /**
     * Calculate network energy consumption for a gRPC operation.
     * 
     * <p>
     * Formula: energy = base + (latency * latencyFactor) + (size * sizeFactor)
     * </p>
     * 
     * @param simulationLatencySec Network latency in simulation seconds
     * @param messageSizeBytes Message size in bytes
     * @return Energy consumed in Joules
     */
    public static double calculateNetworkEnergy(double simulationLatencySec, long messageSizeBytes) {
        if (simulationLatencySec < 0) {
            logger.warning("Negative simulation latency detected: " + simulationLatencySec + "sec, using 0");
            simulationLatencySec = 0.0;
        }
        
        if (messageSizeBytes < 0) {
            logger.warning("Negative message size detected: " + messageSizeBytes + "bytes, using 0");
            messageSizeBytes = 0;
        }
        
        double base = getEnergyBase();
        double latencyFactor = getEnergyLatencyFactor();
        double sizeFactor = getEnergySizeFactor();
        
        double energy = base + (simulationLatencySec * latencyFactor) + (messageSizeBytes * sizeFactor);
        
        if (energy < 0) {
            logger.warning("Calculated negative energy: " + energy + ", using 0");
            return 0.0;
        }
        
        return energy;
    }
    
    /**
     * Calculate network cost for a gRPC operation.
     * 
     * <p>
     * Formula: cost = base + (latency * latencyFactor) + (size * sizeFactor)
     * </p>
     * 
     * @param simulationLatencySec Network latency in simulation seconds
     * @param messageSizeBytes Message size in bytes
     * @return Cost in dollars
     */
    public static double calculateNetworkCost(double simulationLatencySec, long messageSizeBytes) {
        if (simulationLatencySec < 0) {
            logger.warning("Negative simulation latency detected: " + simulationLatencySec + "sec, using 0");
            simulationLatencySec = 0.0;
        }
        
        if (messageSizeBytes < 0) {
            logger.warning("Negative message size detected: " + messageSizeBytes + "bytes, using 0");
            messageSizeBytes = 0;
        }
        
        double base = getCostBase();
        double latencyFactor = getCostLatencyFactor();
        double sizeFactor = getCostSizeFactor();
        
        double cost = base + (simulationLatencySec * latencyFactor) + (messageSizeBytes * sizeFactor);
        
        if (cost < 0) {
            logger.warning("Calculated negative cost: " + cost + ", using 0");
            return 0.0;
        }
        
        return cost;
    }
    
    /**
     * Calculate network energy and cost together (more efficient).
     * 
     * @param simulationLatencySec Network latency in simulation seconds
     * @param messageSizeBytes Message size in bytes
     * @return Array with [energy, cost]
     */
    public static double[] calculateNetworkEnergyAndCost(double simulationLatencySec, long messageSizeBytes) {
        double energy = calculateNetworkEnergy(simulationLatencySec, messageSizeBytes);
        double cost = calculateNetworkCost(simulationLatencySec, messageSizeBytes);
        return new double[]{energy, cost};
    }
    
    // ===== Configuration Getters (with caching) =====
    
    private static double getEnergyBase() {
        if (cachedEnergyBase < 0) {
            synchronized (NetworkEnergyCostCalculator.class) {
                if (cachedEnergyBase < 0) {
                    cachedEnergyBase = EnhancedConfigurationLoader.getSimulationConfigDouble(
                        "simulation.network.energy.base", 0.001);
                }
            }
        }
        return cachedEnergyBase;
    }
    
    private static double getEnergyLatencyFactor() {
        if (cachedEnergyLatencyFactor < 0) {
            synchronized (NetworkEnergyCostCalculator.class) {
                if (cachedEnergyLatencyFactor < 0) {
                    cachedEnergyLatencyFactor = EnhancedConfigurationLoader.getSimulationConfigDouble(
                        "simulation.network.energy.latency-factor", 0.00001);
                }
            }
        }
        return cachedEnergyLatencyFactor;
    }
    
    private static double getEnergySizeFactor() {
        if (cachedEnergySizeFactor < 0) {
            synchronized (NetworkEnergyCostCalculator.class) {
                if (cachedEnergySizeFactor < 0) {
                    cachedEnergySizeFactor = EnhancedConfigurationLoader.getSimulationConfigDouble(
                        "simulation.network.energy.size-factor", 0.0000001);
                }
            }
        }
        return cachedEnergySizeFactor;
    }
    
    private static double getCostBase() {
        if (cachedCostBase < 0) {
            synchronized (NetworkEnergyCostCalculator.class) {
                if (cachedCostBase < 0) {
                    cachedCostBase = EnhancedConfigurationLoader.getSimulationConfigDouble(
                        "simulation.network.cost.base", 0.0001);
                }
            }
        }
        return cachedCostBase;
    }
    
    private static double getCostLatencyFactor() {
        if (cachedCostLatencyFactor < 0) {
            synchronized (NetworkEnergyCostCalculator.class) {
                if (cachedCostLatencyFactor < 0) {
                    cachedCostLatencyFactor = EnhancedConfigurationLoader.getSimulationConfigDouble(
                        "simulation.network.cost.latency-factor", 0.000001);
                }
            }
        }
        return cachedCostLatencyFactor;
    }
    
    private static double getCostSizeFactor() {
        if (cachedCostSizeFactor < 0) {
            synchronized (NetworkEnergyCostCalculator.class) {
                if (cachedCostSizeFactor < 0) {
                    cachedCostSizeFactor = EnhancedConfigurationLoader.getSimulationConfigDouble(
                        "simulation.network.cost.size-factor", 0.00000001);
                }
            }
        }
        return cachedCostSizeFactor;
    }
    
    /**
     * Reset cached configuration values.
     * Useful when configuration is reloaded at runtime.
     */
    public static void resetCache() {
        synchronized (NetworkEnergyCostCalculator.class) {
            cachedEnergyBase = -1.0;
            cachedEnergyLatencyFactor = -1.0;
            cachedEnergySizeFactor = -1.0;
            cachedCostBase = -1.0;
            cachedCostLatencyFactor = -1.0;
            cachedCostSizeFactor = -1.0;
        }
    }
}

