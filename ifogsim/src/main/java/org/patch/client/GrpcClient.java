package org.patch.client;

import io.github.cdimascio.dotenv.Dotenv;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.patch.config.EnhancedConfigurationLoader;
import org.patch.utils.LoggingConfig;
import org.patch.utils.StructuredLogger;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Enhanced gRPC client with comprehensive retry mechanism, circuit breaker
 * pattern,
 * and configuration management for iFogSim gRPC integration.
 * 
 * <p>
 * This client provides robust communication capabilities with external gRPC
 * services
 * including automatic retry logic, circuit breaker pattern for fault tolerance,
 * connection health monitoring, and structured logging with correlation
 * tracking.
 * </p>
 * 
 * <p>
 * Key Features:
 * </p>
 * <ul>
 * <li>Automatic retry with exponential backoff</li>
 * <li>Circuit breaker pattern for fault tolerance</li>
 * <li>Connection health monitoring and automatic reconnection</li>
 * <li>Structured logging with correlation IDs</li>
 * <li>Graceful degradation with fallback mechanisms</li>
 * <li>Performance metrics collection</li>
 * </ul>
 * 
 * <p>
 * Usage Example:
 * </p>
 * 
 * <pre>{@code
 * // Create client with configuration
 * GrpcClientConfig config = new GrpcClientConfig.Builder()
 *         .host("localhost")
 *         .port(50051)
 *         .build();
 * 
 * GrpcClient client = new GrpcClient(config);
 * 
 * // Use client for gRPC operations
 * if (client.connect()) {
 *     // Perform gRPC operations
 * }
 * 
 * // Always close the client
 * client.close();
 * }</pre>
 * 
 * @author Younes Shafiee
 * @version 1.0.0
 * @since 1.0.0
 * @see GrpcClientConfig
 * @see StructuredLogger
 * @see LoggingConfig
 */
public class GrpcClient implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(GrpcClient.class.getName());
    private final ManagedChannel channel;
    private final GrpcClientConfig config;
    private volatile boolean isConnected;

    // Enhanced logging
    private final StructuredLogger structuredLogger;
    private final LoggingConfig loggingConfig;

    // Circuit breaker state
    private volatile CircuitBreakerState circuitBreakerState = CircuitBreakerState.CLOSED;
    private volatile long lastFailureTime = 0;
    private volatile int consecutiveFailures = 0;
    private final int failureThreshold;
    private final long circuitOpenDuration;

    // Performance tracking
    private long totalRequests = 0;
    private long successfulRequests = 0;
    private long failedRequests = 0;
    private double totalLatency = 0.0;
    private double maxLatency = 0.0;
    private double totalEnergyConsumed = 0.0;
    private double totalCost = 0.0;

    // Environment configuration
    private static final Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

    /**
     * Constructor using GrpcClientConfig
     */
    public GrpcClient(GrpcClientConfig config) {
        this.config = config;
        this.isConnected = false;

        // Initialize configuration loader
        // Note: EnhancedConfigurationLoader doesn't need explicit initialization

        // Initialize enhanced logging
        this.loggingConfig = LoggingConfig.getInstance();
        this.structuredLogger = loggingConfig.createLoggerWithCorrelation(GrpcClient.class);

        // Load circuit breaker configuration
        this.failureThreshold = EnhancedConfigurationLoader.getGrpcConfigInt("grpc.circuit.breaker.failure.threshold",
                5);
        this.circuitOpenDuration = EnhancedConfigurationLoader.getGrpcConfigLong("grpc.circuit.breaker.open.duration",
                30000);

        // Initialize channel using config
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(
                config.getHost(),
                config.getPort());

        // Apply all configurations
        config.applyToChannelBuilder(builder);
        this.channel = builder.build();

        // Attempt initial connection
        connect();
    }

    /**
     * Legacy constructor (maintained for backward compatibility)
     */
    public GrpcClient(String serverAddress, int serverPort) {
        this(new GrpcClientConfig.Builder(serverAddress, serverPort)
                .usePlaintext(EnhancedConfigurationLoader.getGrpcConfigBoolean("grpc.use.plaintext", true))
                .connectTimeout(
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.connection.timeout", 5000),
                        TimeUnit.MILLISECONDS)
                .retryConfig(
                        EnhancedConfigurationLoader.getGrpcConfigInt("grpc.retry.max.attempts", 3),
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.retry.delay", 1000),
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.retry.max.delay", 30000))
                .keepAliveConfig(
                        EnhancedConfigurationLoader.getGrpcConfigBoolean("grpc.keepalive.enabled", true),
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.keepalive.time", 30),
                        EnhancedConfigurationLoader.getGrpcConfigLong("grpc.keepalive.timeout", 10),
                        EnhancedConfigurationLoader.getGrpcConfigBoolean("grpc.keepalive.without.calls", true))
                .build());
    }

    /**
     * Attempts to establish a connection with comprehensive health check validation
     * and structured logging.
     * 
     * <p>
     * This method performs the following operations:
     * </p>
     * <ul>
     * <li>Checks if already connected to avoid duplicate connections</li>
     * <li>Validates channel state and connectivity</li>
     * <li>Performs structured logging with correlation tracking</li>
     * <li>Records performance metrics for connection timing</li>
     * <li>Handles connection failures gracefully</li>
     * </ul>
     * 
     * <p>
     * The connection process includes:
     * </p>
     * <ul>
     * <li>Channel state verification (READY state required)</li>
     * <li>Performance timing measurement</li>
     * <li>Structured logging with correlation IDs</li>
     * <li>Error handling with detailed logging</li>
     * </ul>
     * 
     * @return {@code true} if connection is successfully established and validated,
     *         {@code false} if connection fails or channel is not ready
     * 
     * @throws RuntimeException if connection process encounters unexpected errors
     * 
     * @see #isConnectionHealthy()
     * @see #disconnect()
     * 
     * @since 1.0.0
     */
    public boolean connect() {
        if (!isConnected) {
            long startTime = System.currentTimeMillis();
            Map<String, Object> connectionFields = new HashMap<>();
            connectionFields.put("host", config.getHost());
            connectionFields.put("port", config.getPort());
            connectionFields.put("correlation_id", structuredLogger.getCorrelationId());

            structuredLogger.grpcRequestStart("GrpcClient", "connect", connectionFields);

            try {
                // Check channel state first
                io.grpc.ConnectivityState state = channel.getState(true);
                if (state == io.grpc.ConnectivityState.READY) {
                    isConnected = true;
                    long duration = System.currentTimeMillis() - startTime;

                    Map<String, Object> successFields = new HashMap<>();
                    successFields.put("state", state.toString());
                    successFields.put("duration_ms", duration);

                    structuredLogger.info("Connected to gRPC server", successFields);
                    structuredLogger.grpcRequestComplete("GrpcClient", "connect", duration, true);

                    if (loggingConfig.isPerformanceLoggingEnabled()) {
                        structuredLogger.performance("grpc_connection", duration, connectionFields);
                    }

                    return true;
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    Map<String, Object> errorFields = new HashMap<>();
                    errorFields.put("state", state.toString());
                    errorFields.put("duration_ms", duration);

                    structuredLogger.warning("Channel not ready", errorFields);
                    structuredLogger.grpcRequestComplete("GrpcClient", "connect", duration, false);
                    return false;
                }
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                Map<String, Object> errorFields = new HashMap<>();
                errorFields.put("duration_ms", duration);
                errorFields.put("exception_type", e.getClass().getSimpleName());

                structuredLogger.error("Failed to connect to gRPC server", errorFields, e);
                structuredLogger.grpcRequestComplete("GrpcClient", "connect", duration, false);
                return false;
            }
        }
        return true;
    }

    /**
     * Performs a health check on the gRPC server
     * This method should be overridden by specific clients to use their health
     * endpoints
     */
    public boolean isConnectionHealthy() {
        try {
            if (!isConnected()) {
                return false;
            }

            // Check channel state
            io.grpc.ConnectivityState state = channel.getState(false);
            if (state != io.grpc.ConnectivityState.READY) {
                logger.fine("Channel not ready for health check, state: " + state);
                return false;
            }

            return true;
        } catch (Exception e) {
            logger.fine("Health check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ensures a healthy connection before operations
     * Attempts reconnection if needed
     */
    public boolean ensureHealthyConnection() {
        if (isConnectionHealthy()) {
            return true;
        }

        logger.warning("Connection unhealthy, attempting reconnection...");
        isConnected = false; // Reset connection state

        // Attempt reconnection with exponential backoff
        int maxReconnectAttempts = EnhancedConfigurationLoader.getGrpcConfigInt("grpc.reconnection.max.attempts", 3);
        for (int attempt = 1; attempt <= maxReconnectAttempts; attempt++) {
            try {
                if (connect() && isConnectionHealthy()) {
                    logger.info("Reconnection successful on attempt " + attempt);
                    return true;
                }

                if (attempt < maxReconnectAttempts) {
                    long delay = config.getRetryDelay() * attempt;
                    logger.fine("Reconnection attempt " + attempt + " failed, retrying in " + delay + "ms");
                    Thread.sleep(delay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning("Reconnection interrupted");
                return false;
            } catch (Exception e) {
                logger.warning("Reconnection attempt " + attempt + " failed: " + e.getMessage());
            }
        }

        logger.severe("Failed to establish healthy connection after " + maxReconnectAttempts + " attempts");
        return false;
    }

    /**
     * Sends a gRPC request with enhanced retry mechanism, health checks, and
     * circuit breaker
     */
    public <T, R> R sendRequestWithRetry(GrpcRequest<T, R> request, T requestData) {
        // Check circuit breaker state first
        if (isCircuitBreakerOpen()) {
            throw new StatusRuntimeException(io.grpc.Status.UNAVAILABLE.withDescription("Circuit breaker is OPEN"));
        }

        int attempt = 0;
        while (attempt < config.getMaxRetries()) {
            try {
                // Ensure healthy connection before making request
                if (!ensureHealthyConnection()) {
                    throw new StatusRuntimeException(
                            io.grpc.Status.UNAVAILABLE.withDescription("Connection unhealthy"));
                }

                R result = request.call(requestData);
                // Record successful operation
                recordSuccess();
                return result;

            } catch (StatusRuntimeException e) {
                attempt++;
                logger.warning("Request failed (attempt " + attempt + "/" +
                        config.getMaxRetries() + "): " + e.getMessage());

                // Record failure for circuit breaker
                recordFailure();

                // Check if this is a connection-related error
                if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE ||
                        e.getStatus().getCode() == io.grpc.Status.Code.UNAUTHENTICATED) {
                    logger.warning("Connection error detected, resetting connection state");
                    isConnected = false;
                }

                if (attempt >= config.getMaxRetries()) {
                    logger.severe("Max retry attempts reached for request");
                    throw e;
                }

                try {
                    // Calculate exponential backoff delay
                    long delay = Math.min(
                            config.getRetryDelay() * (long) Math.pow(2, attempt - 1),
                            config.getMaxRetryDelay());
                    logger.fine("Retrying request in " + delay + "ms");
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
        throw new RuntimeException("Request failed after maximum retries.");
    }

    /**
     * Get scheduling decision from RL agent
     * 
     * @param clientId    Client identifier
     * @param deviceId    Device ID
     * @param deviceState Current device state
     * @return Index of task to process next
     */
    public int getSchedulingDecision(String clientId, int deviceId, Map<String, Object> deviceState) {
        try {
            logger.info("Requesting scheduling decision for device " + deviceId);

            // Simple round-robin scheduling as fallback
            // In a real implementation, this would call the actual gRPC service
            // Using simplified decision logic based on device state
            if (deviceState.containsKey("queue_size")) {
                Object queueSize = deviceState.get("queue_size");
                if (queueSize instanceof Number) {
                    int size = ((Number) queueSize).intValue();
                    // Return index of next task to process (0-based)
                    return size > 0 ? 0 : -1; // Process first task if queue not empty
                }
            }

            // Default: process first available task
            return 0;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to get scheduling decision from RL agent", e);
            return 0; // Default to FIFO scheduling
        }
    }

    /**
     * Creates an AddTaskToQueueRequest for the scheduler service
     * 
     * @param task           The task to be scheduled
     * @param availableNodes List of available fog nodes for scheduling
     * @return AddTaskToQueueRequest object
     */
    private org.patch.proto.IfogsimScheduler.AddTaskToQueueRequest createAddTaskToQueueRequest(
            org.patch.proto.IfogsimCommon.Task task,
            java.util.List<org.patch.proto.IfogsimCommon.FogNode> availableNodes) {

        return org.patch.proto.IfogsimScheduler.AddTaskToQueueRequest.newBuilder()
                .setTask(task)
                .addAllAvailableNodes(availableNodes)
                .build();
    }

    /**
     * Helper method to convert state map to JSON string
     */
    private String convertStateToJson(Map<String, Object> state) {
        // Use your JSON library (e.g., Gson, Jackson) to convert the map to JSON
        // Example with simple string building (not recommended for production)
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

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Gracefully shuts down the channel
     */
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(
                        config.getConnectTimeout(),
                        config.getConnectTimeoutUnit());
                isConnected = false;
                logger.info("gRPC client shutdown successfully");
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, "Channel shutdown interrupted", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    // Static utility methods for environment variables
    private static int getEnvInt(String key, int defaultValue) {
        String value = dotenv.get(key);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    private static long getEnvLong(String key, long defaultValue) {
        String value = dotenv.get(key);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    // Getters
    public ManagedChannel getChannel() {
        return channel;
    }

    public boolean isConnected() {
        return isConnected && !channel.isShutdown();
    }

    public GrpcClientConfig getConfig() {
        return config;
    }

    /**
     * Circuit breaker states
     */
    public enum CircuitBreakerState {
        CLOSED, // Normal operation
        OPEN, // Circuit is open, requests are blocked
        HALF_OPEN // Testing if service is back
    }

    /**
     * Check circuit breaker state and handle transitions
     */
    private boolean isCircuitBreakerOpen() {
        long currentTime = System.currentTimeMillis();

        switch (circuitBreakerState) {
            case CLOSED:
                // Circuit is closed - normal operation, allow requests
                return false;
            case OPEN:
                // Circuit is open - check if enough time has passed to try half-open
                // This implements the timeout mechanism for circuit breaker recovery
                if (currentTime - lastFailureTime > circuitOpenDuration) {
                    // Timeout expired - transition to HALF_OPEN state for testing
                    String previousState = circuitBreakerState.toString();
                    circuitBreakerState = CircuitBreakerState.HALF_OPEN;

                    // Log the state transition with timing information
                    Map<String, Object> transitionFields = new HashMap<>();
                    transitionFields.put("time_since_failure_ms", currentTime - lastFailureTime);
                    transitionFields.put("circuit_open_duration_ms", circuitOpenDuration);

                    structuredLogger.circuitBreakerStateChange(previousState, "HALF_OPEN", "Timeout expired");
                    return false; // Allow one request to test if service is back
                }
                return true; // Still in OPEN state, block requests
            case HALF_OPEN:
                // Half-open state - allow limited requests to test service health
                return false;
            default:
                // Unknown state - default to allowing requests
                return false;
        }
    }

    /**
     * Record a successful operation
     */
    private void recordSuccess() {
        // Reset failure counter on successful operation
        consecutiveFailures = 0;

        // If we were in HALF_OPEN state and got a success, transition to CLOSED
        // This indicates the service has recovered and is working normally
        if (circuitBreakerState == CircuitBreakerState.HALF_OPEN) {
            String previousState = circuitBreakerState.toString();
            circuitBreakerState = CircuitBreakerState.CLOSED;

            // Log the recovery with current state information
            Map<String, Object> recoveryFields = new HashMap<>();
            recoveryFields.put("consecutive_failures", consecutiveFailures);
            recoveryFields.put("failure_threshold", failureThreshold);

            structuredLogger.circuitBreakerStateChange(previousState, "CLOSED", "Service recovered");
        }
    }

    /**
     * Record a failed operation
     */
    private void recordFailure() {
        // Increment failure counter and update timestamp
        consecutiveFailures++;
        lastFailureTime = System.currentTimeMillis();

        // Check if we've exceeded the failure threshold and circuit is currently CLOSED
        // This triggers the circuit breaker to OPEN state, blocking future requests
        if (consecutiveFailures >= failureThreshold && circuitBreakerState == CircuitBreakerState.CLOSED) {
            String previousState = circuitBreakerState.toString();
            circuitBreakerState = CircuitBreakerState.OPEN;

            // Log the circuit breaker opening with detailed failure information
            Map<String, Object> failureFields = new HashMap<>();
            failureFields.put("consecutive_failures", consecutiveFailures);
            failureFields.put("failure_threshold", failureThreshold);
            failureFields.put("circuit_open_duration_ms", circuitOpenDuration);

            structuredLogger.circuitBreakerStateChange(previousState, "OPEN",
                    "Threshold exceeded: " + consecutiveFailures + " consecutive failures");
        }
    }

    /**
     * Get current circuit breaker state
     */
    public CircuitBreakerState getCircuitBreakerState() {
        return circuitBreakerState;
    }

    /**
     * Get consecutive failure count
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * Check if service is available (not in circuit breaker open state)
     */
    public boolean isServiceAvailable() {
        return !isCircuitBreakerOpen() && isConnectionHealthy();
    }

    /**
     * Get service status for monitoring
     */
    public String getServiceStatus() {
        if (isCircuitBreakerOpen()) {
            return "CIRCUIT_OPEN";
        } else if (!isConnectionHealthy()) {
            return "CONNECTION_UNHEALTHY";
        } else {
            return "HEALTHY";
        }
    }

    // ===== PERFORMANCE TRACKING METHODS =====

    /**
     * Get total number of requests made
     */
    public long getTotalRequests() {
        return totalRequests;
    }

    /**
     * Get number of successful requests
     */
    public long getSuccessfulRequests() {
        return successfulRequests;
    }

    /**
     * Get number of failed requests
     */
    public long getFailedRequests() {
        return failedRequests;
    }

    /**
     * Get success rate
     */
    public double getSuccessRate() {
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) successfulRequests / totalRequests;
    }

    /**
     * Get average latency
     */
    public double getAverageLatency() {
        if (totalRequests == 0) {
            return 0.0;
        }
        return totalLatency / totalRequests;
    }

    /**
     * Get maximum latency
     */
    public double getMaxLatency() {
        return maxLatency;
    }

    /**
     * Get total energy consumed by this client
     */
    public double getTotalEnergyConsumed() {
        return totalEnergyConsumed;
    }

    /**
     * Get total cost of this client
     */
    public double getTotalCost() {
        return totalCost;
    }

    @FunctionalInterface
    public interface GrpcRequest<T, R> {
        R call(T requestData) throws StatusRuntimeException;
    }
}