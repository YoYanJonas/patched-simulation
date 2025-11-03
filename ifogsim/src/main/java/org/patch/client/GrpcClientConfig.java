package org.patch.client;

import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;

/**
 * Configuration class for gRPC client settings
 * Handles connection, retry, channel and load balancing configurations
 */
public class GrpcClientConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    // Basic connection parameters
    private final String host;
    private final int port;
    private final boolean usePlaintext;
    private final long connectTimeout;
    private final TimeUnit connectTimeoutUnit;

    // Retry mechanism settings
    private final int maxRetries;
    private final long retryDelay;
    private final long maxRetryDelay;
    private final boolean enableRetry;

    // Channel configuration
    private final int maxInboundMessageSize;
    private final boolean enableKeepAlive;
    private final long keepAliveTime;
    private final long keepAliveTimeout;
    private final boolean keepAliveWithoutCalls;

    // Load balancing configuration
    private final Map<String, Object> loadBalancingConfig;

    /**
     * Private constructor used by Builder
     */
    private GrpcClientConfig(Builder builder) {
        // Initialize all fields from builder
        this.host = builder.host;
        this.port = builder.port;
        this.usePlaintext = builder.usePlaintext;
        this.connectTimeout = builder.connectTimeout;
        this.connectTimeoutUnit = builder.connectTimeoutUnit;
        this.maxRetries = builder.maxRetries;
        this.retryDelay = builder.retryDelay;
        this.maxRetryDelay = builder.maxRetryDelay;
        this.enableRetry = builder.enableRetry;
        this.maxInboundMessageSize = builder.maxInboundMessageSize;
        this.enableKeepAlive = builder.enableKeepAlive;
        this.keepAliveTime = builder.keepAliveTime;
        this.keepAliveTimeout = builder.keepAliveTimeout;
        this.keepAliveWithoutCalls = builder.keepAliveWithoutCalls;
        this.loadBalancingConfig = builder.loadBalancingConfig;
    }

    /**
     * Builder class for GrpcClientConfig
     * Provides fluent API for configuration
     */
    public static class Builder {
        // Required parameters
        private final String host;
        private final int port;

        // Optional parameters with sensible defaults
        private boolean usePlaintext = false;
        private long connectTimeout = 10;
        private TimeUnit connectTimeoutUnit = TimeUnit.SECONDS;
        private int maxRetries = 3;
        private long retryDelay = 1000;
        private long maxRetryDelay = 30000;
        private boolean enableRetry = true;
        private int maxInboundMessageSize = 4 * 1024 * 1024; // 4MB
        private boolean enableKeepAlive = true;
        private long keepAliveTime = 30;
        private long keepAliveTimeout = 10;
        private boolean keepAliveWithoutCalls = true;
        private Map<String, Object> loadBalancingConfig = new HashMap<>();

        public Builder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        // Builder methods with method chaining
        public Builder usePlaintext(boolean usePlaintext) {
            this.usePlaintext = usePlaintext;
            return this;
        }

        public Builder connectTimeout(long timeout, TimeUnit unit) {
            this.connectTimeout = timeout;
            this.connectTimeoutUnit = unit;
            return this;
        }

        // ... other builder methods remain the same ...

        public GrpcClientConfig build() {
            return new GrpcClientConfig(this);
        }

        public Builder retryConfig(int maxRetries, long retryDelay, long maxRetryDelay) {
            this.maxRetries = maxRetries;
            this.retryDelay = retryDelay;
            this.maxRetryDelay = maxRetryDelay;
            return this;
        }

        public Builder keepAliveConfig(boolean enableKeepAlive, long keepAliveTime,
                long keepAliveTimeout, boolean keepAliveWithoutCalls) {
            this.enableKeepAlive = enableKeepAlive;
            this.keepAliveTime = keepAliveTime;
            this.keepAliveTimeout = keepAliveTimeout;
            this.keepAliveWithoutCalls = keepAliveWithoutCalls;
            return this;
        }
    }

    // Getters (remain the same)

    /**
     * Applies configuration to a ManagedChannelBuilder
     * 
     * @param builder The channel builder to configure
     */
    public void applyToChannelBuilder(ManagedChannelBuilder<?> builder) {
        // Configure plaintext/TLS
        if (usePlaintext) {
            builder.usePlaintext();
        }

        // Apply basic channel configuration
        builder.maxInboundMessageSize(maxInboundMessageSize)
                .maxRetryAttempts(maxRetries)
                .defaultServiceConfig(createServiceConfig());

        // Configure keep-alive if enabled
        if (enableKeepAlive) {
            builder.keepAliveTime(keepAliveTime, TimeUnit.SECONDS)
                    .keepAliveTimeout(keepAliveTimeout, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(keepAliveWithoutCalls);
        }
    }

    /**
     * Creates service configuration map including retry and load balancing settings
     * Note: gRPC requires methodConfig to be an array/list of method configs
     */
    private Map<String, Object> createServiceConfig() {
        Map<String, Object> serviceConfig = new HashMap<>();

        // Add retry configuration if enabled
        // methodConfig must be a list/array, not a single object
        if (enableRetry) {
            List<Map<String, Object>> methodConfigList = new ArrayList<>();
            Map<String, Object> methodConfig = new HashMap<>();
            methodConfig.put("retryPolicy", createRetryPolicy());
            methodConfigList.add(methodConfig);
            serviceConfig.put("methodConfig", methodConfigList);
        }

        // Add load balancing configuration if specified
        if (!loadBalancingConfig.isEmpty()) {
            serviceConfig.put("loadBalancingConfig", loadBalancingConfig);
        }

        return serviceConfig;
    }

    /**
     * Creates retry policy configuration
     * Note: gRPC requires all service config values to be strings
     * Duration format must be in seconds (s) - gRPC expects format like "1s", "0.1s", "30s"
     * NOT "1000ms" or "1000m" (which would be interpreted as minutes)
     * retryableStatusCodes is REQUIRED in gRPC retry policy
     */
    private Map<String, Object> createRetryPolicy() {
        Map<String, Object> retryPolicy = new HashMap<>();
        // Convert all numeric values to strings as required by gRPC
        retryPolicy.put("maxAttempts", String.valueOf(maxRetries));

        // Convert milliseconds to seconds for gRPC duration format
        // Use integer format when whole seconds, otherwise decimal format
        double initialBackoffSeconds = retryDelay / 1000.0;
        double maxBackoffSeconds = maxRetryDelay / 1000.0;
        
        // Format as integer seconds if whole number, otherwise use decimal
        String initialBackoffStr = (initialBackoffSeconds == (long) initialBackoffSeconds) 
            ? String.format("%ds", (long) initialBackoffSeconds)
            : String.format("%.1fs", initialBackoffSeconds);
        String maxBackoffStr = (maxBackoffSeconds == (long) maxBackoffSeconds)
            ? String.format("%ds", (long) maxBackoffSeconds)
            : String.format("%.1fs", maxBackoffSeconds);
            
        retryPolicy.put("initialBackoff", initialBackoffStr);
        retryPolicy.put("maxBackoff", maxBackoffStr);
        retryPolicy.put("backoffMultiplier", String.valueOf(2.0));
        
        // REQUIRED: retryableStatusCodes - list of gRPC status codes that should trigger retry
        List<String> retryableStatusCodes = new ArrayList<>();
        retryableStatusCodes.add("UNAVAILABLE");      // Server unavailable
        retryableStatusCodes.add("DEADLINE_EXCEEDED"); // Request deadline exceeded
        retryableStatusCodes.add("RESOURCE_EXHAUSTED"); // Resource exhausted (can retry)
        retryPolicy.put("retryableStatusCodes", retryableStatusCodes);
        
        return retryPolicy;
    }

    /**
     * getters
     */
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelay() {
        return retryDelay;
    }

    public long getMaxRetryDelay() {
        return maxRetryDelay;
    }

    public long getConnectTimeout() {
        return connectTimeout;
    }

    public TimeUnit getConnectTimeoutUnit() {
        return connectTimeoutUnit;
    }
}