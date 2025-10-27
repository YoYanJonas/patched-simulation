package org.patch.client;

import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;
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
     */
    private Map<String, Object> createServiceConfig() {
        Map<String, Object> serviceConfig = new HashMap<>();

        // Add retry configuration if enabled
        if (enableRetry) {
            Map<String, Object> methodConfig = new HashMap<>();
            methodConfig.put("retryPolicy", createRetryPolicy());
            serviceConfig.put("methodConfig", methodConfig);
        }

        // Add load balancing configuration if specified
        if (!loadBalancingConfig.isEmpty()) {
            serviceConfig.put("loadBalancingConfig", loadBalancingConfig);
        }

        return serviceConfig;
    }

    /**
     * Creates retry policy configuration
     */
    private Map<String, Object> createRetryPolicy() {
        Map<String, Object> retryPolicy = new HashMap<>();
        retryPolicy.put("maxAttempts", maxRetries);
        retryPolicy.put("initialBackoff", retryDelay + "ms");
        retryPolicy.put("maxBackoff", maxRetryDelay + "ms");
        retryPolicy.put("backoffMultiplier", 2.0);
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