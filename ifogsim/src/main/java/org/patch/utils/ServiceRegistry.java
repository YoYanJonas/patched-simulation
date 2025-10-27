
package org.patch.utils;

import org.patch.client.GrpcClient;
import org.patch.client.GrpcClientConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central registry for services and configuration
 * Provides access to shared services and configuration settings
 */
public class ServiceRegistry {
    private static final Logger logger = Logger.getLogger(ServiceRegistry.class.getName());

    // Thread-safe maps for services and configuration
    private static final Map<String, Object> services = new ConcurrentHashMap<>();
    private static final Map<String, Object> config = new ConcurrentHashMap<>();

    // Private constructor to prevent instantiation
    private ServiceRegistry() {
    }

    /**
     * Register a service
     * 
     * @param serviceName Name of the service
     * @param service     Service instance
     */
    public static void registerService(String serviceName, Object service) {
        try {
            if (serviceName == null || serviceName.trim().isEmpty()) {
                throw new IllegalArgumentException("Service name cannot be null or empty");
            }

            if (service == null) {
                throw new IllegalArgumentException("Cannot register null service");
            }

            Object previous = services.put(serviceName, service);
            if (previous != null) {
                logger.info("Service '" + serviceName + "' has been replaced");
            } else {
                logger.info("Service '" + serviceName + "' has been registered");
            }
        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE, "Error registering service: " + serviceName, e);
            throw e;
        }
    }

    /**
     * Get a registered service
     * 
     * @param serviceName Name of the service
     * @return Service instance or null if not found
     */
    @SuppressWarnings("unchecked")
    public static <T> T getService(String serviceName) {
        return (T) services.get(serviceName);
    }

    /**
     * Check if a service is registered
     * 
     * @param serviceName Name of the service
     * @return true if service exists, false otherwise
     */
    public static boolean hasService(String serviceName) {
        return services.containsKey(serviceName);
    }

    /**
     * Get or create a gRPC client for a specific endpoint
     * 
     * @param endpointName Name to identify this endpoint
     * @param host         Host address
     * @param port         Port number
     * @return GrpcClient instance
     */
    public static GrpcClient getOrCreateGrpcClient(String endpointName, String host, int port) {
        String serviceName = "grpcClient." + endpointName;

        GrpcClient client = getService(serviceName);
        if (client == null) {
            GrpcClientConfig config = new GrpcClientConfig.Builder(host, port)
                    .usePlaintext(true)
                    .build();

            client = new GrpcClient(config);
            registerService(serviceName, client);
        }

        return client;
    }

    /**
     * Set a configuration value
     * 
     * @param key   Configuration key
     * @param value Configuration value
     */
    public static void setConfig(String key, Object value) {
        config.put(key, value);
    }

    /**
     * Get a configuration value
     * 
     * @param key Configuration key
     * @return Configuration value or null if not found
     */
    @SuppressWarnings("unchecked")
    public static <T> T getConfig(String key) {
        return (T) config.get(key);
    }

    /**
     * Get a configuration value with default
     * 
     * @param key          Configuration key
     * @param defaultValue Default value if not found
     * @return Configuration value or default if not found
     */
    @SuppressWarnings("unchecked")
    public static <T> T getConfig(String key, T defaultValue) {
        T value = (T) config.get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Check if a configuration key exists
     * 
     * @param key Configuration key
     * @return true if configuration exists, false otherwise
     */
    public static boolean hasConfig(String key) {
        return config.containsKey(key);
    }

    /**
     * Clean up all registered services
     * Especially important for closing gRPC clients
     */
    public static void shutdown() {
        for (Object service : services.values()) {
            if (service instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) service).close();
                } catch (Exception e) {
                    logger.warning("Error closing service: " + e.getMessage());
                }
            }
        }
        services.clear();
        config.clear();
    }
}