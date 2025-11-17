package org.patch.utils;

import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.config.EnhancedConfigurationLoader;
import java.util.logging.Logger;

/**
 * Utility class for converting real-world network latency to simulation time.
 * 
 * <p>
 * This class provides methods to convert real-world network latency (measured in
 * milliseconds) to simulation time (measured in simulation seconds). This is essential
 * for event-based gRPC operations where network delays need to advance the simulation
 * clock correctly.
 * </p>
 * 
 * <p>
 * Conversion Models:
 * </p>
 * <ul>
 * <li><b>1to1</b>: Direct 1:1 mapping (15ms → 0.015 sec) - Default</li>
 * <li><b>scaled</b>: Uses configurable scale factor (15ms * scale → simulation sec)</li>
 * <li><b>model-based</b>: Future: More sophisticated latency modeling</li>
 * </ul>
 * 
 * <p>
 * Configuration:
 * </p>
 * <ul>
 * <li>{@code simulation.network.latency.scale-factor}: Scaling factor (default: 1.0)</li>
 * <li>{@code simulation.network.latency.model}: Model type (default: "1to1")</li>
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
public class NetworkLatencyConverter {
    private static final Logger logger = Logger.getLogger(NetworkLatencyConverter.class.getName());
    
    // Cache configuration values for performance
    private static volatile double cachedScaleFactor = -1.0;
    private static volatile String cachedModel = null;
    
    /**
     * Convert real-world network latency (milliseconds) to simulation time (seconds).
     * 
     * <p>
     * Default behavior: 1:1 mapping (15ms → 0.015 sec)
     * </p>
     * 
     * @param realLatencyMs Real-world latency in milliseconds
     * @return Simulation time in seconds
     */
    public static double convertToSimulationTime(long realLatencyMs) {
        if (realLatencyMs < 0) {
            logger.warning("Negative latency detected: " + realLatencyMs + "ms, using 0");
            return 0.0;
        }
        
        String model = getModel();
        double scaleFactor = getScaleFactor();
        
        switch (model) {
            case "1to1":
                // Direct 1:1 mapping: 15ms → 0.015 sec
                return realLatencyMs / 1000.0;
                
            case "scaled":
                // Scaled mapping: 15ms * scale → simulation sec
                return (realLatencyMs / 1000.0) * scaleFactor;
                
            case "model-based":
                // Future: More sophisticated latency modeling
                // For now, fall back to scaled
                logger.fine("Model-based conversion not yet implemented, using scaled");
                return (realLatencyMs / 1000.0) * scaleFactor;
                
            default:
                logger.warning("Unknown latency model: " + model + ", using 1:1 mapping");
                return realLatencyMs / 1000.0;
        }
    }
    
    /**
     * Convert real-world network latency (milliseconds) to simulation time (seconds)
     * with explicit scale factor.
     * 
     * @param realLatencyMs Real-world latency in milliseconds
     * @param scaleFactor Custom scale factor (overrides configuration)
     * @return Simulation time in seconds
     */
    public static double convertToSimulationTime(long realLatencyMs, double scaleFactor) {
        if (realLatencyMs < 0) {
            logger.warning("Negative latency detected: " + realLatencyMs + "ms, using 0");
            return 0.0;
        }
        
        if (scaleFactor <= 0) {
            logger.warning("Invalid scale factor: " + scaleFactor + ", using 1.0");
            scaleFactor = 1.0;
        }
        
        return (realLatencyMs / 1000.0) * scaleFactor;
    }
    
    /**
     * Get the configured latency model.
     * 
     * @return Model name ("1to1", "scaled", or "model-based")
     */
    private static String getModel() {
        if (cachedModel == null) {
            synchronized (NetworkLatencyConverter.class) {
                if (cachedModel == null) {
                    cachedModel = EnhancedConfigurationLoader.getSimulationConfig(
                        "simulation.network.latency.model", "1to1");
                }
            }
        }
        return cachedModel;
    }
    
    /**
     * Get the configured scale factor.
     * 
     * @return Scale factor (default: 1.0)
     */
    private static double getScaleFactor() {
        if (cachedScaleFactor < 0) {
            synchronized (NetworkLatencyConverter.class) {
                if (cachedScaleFactor < 0) {
                    cachedScaleFactor = EnhancedConfigurationLoader.getSimulationConfigDouble(
                        "simulation.network.latency.scale-factor", 1.0);
                }
            }
        }
        return cachedScaleFactor;
    }
    
    /**
     * Reset cached configuration values.
     * Useful when configuration is reloaded at runtime.
     */
    public static void resetCache() {
        synchronized (NetworkLatencyConverter.class) {
            cachedScaleFactor = -1.0;
            cachedModel = null;
        }
    }
    
    /**
     * Ensures a delay is valid for CloudSim event scheduling.
     * 
     * <p>
     * This method guarantees that:
     * </p>
     * <ul>
     * <li>Delay is non-negative</li>
     * <li>Delay is at least CloudSim.getMinTimeBetweenEvents()</li>
     * <li>Event will be scheduled in the future</li>
     * </ul>
     * 
     * <p>
     * This is critical for async gRPC callbacks where real-world latency (2-5ms)
     * converts to tiny simulation delays (0.002-0.005 sec). Without this validation,
     * the simulation clock may advance past the event time, causing "Past event detected" errors.
     * </p>
     * 
     * @param calculatedDelay The delay calculated from real-world latency
     * @return Valid delay for CloudSim.send() (guaranteed >= minTimeBetweenEvents)
     */
    public static double ensureValidEventDelay(double calculatedDelay) {
        double minDelay = CloudSim.getMinTimeBetweenEvents();
        
        // Ensure delay is at least minimum time between events
        if (calculatedDelay < minDelay) {
            logger.fine(String.format(
                "Delay %.6f sec is less than minimum (%.6f sec), using minimum",
                calculatedDelay, minDelay));
            return minDelay;
        }
        
        // Ensure delay is non-negative and finite
        if (calculatedDelay < 0 || Double.isNaN(calculatedDelay) || Double.isInfinite(calculatedDelay)) {
            logger.warning(String.format(
                "Invalid delay detected: %.6f, using minimum: %.6f",
                calculatedDelay, minDelay));
            return minDelay;
        }
        
        return calculatedDelay;
    }
}

