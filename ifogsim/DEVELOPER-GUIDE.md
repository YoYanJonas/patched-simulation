# iFogSim gRPC Integration - Developer Guide

## Extending the API

### Adding New gRPC Services

#### 1. Define Proto File

```protobuf
syntax = "proto3";

package thesis;

service NewService {
    rpc NewMethod(NewRequest) returns (NewResponse);
}

message NewRequest {
    string request_id = 1;
    // Add your fields
}

message NewResponse {
    bool success = 1;
    string message = 2;
    // Add your fields
}
```

#### 2. Generate Java Classes

```bash
./scripts/generate_proto.sh
```

#### 3. Create Client Class

```java
public class NewServiceClient extends GrpcClient {
    private NewServiceGrpc.NewServiceBlockingStub stub;

    public NewServiceClient(String host, int port) {
        super(host, port);
        this.stub = NewServiceGrpc.newBlockingStub(getChannel());
    }

    public NewResponse callNewMethod(NewRequest request) {
        try {
            return stub.newMethod(request);
        } catch (StatusRuntimeException e) {
            handleGrpcException(e);
            return NewResponse.getDefaultInstance();
        }
    }
}
```

### Adding New RL Algorithms

#### 1. Extend Algorithm Enum

```java
public enum Algorithm {
    Q_LEARNING,
    SARSA,
    DQN,        // New algorithm
    PPO;        // New algorithm
}
```

#### 2. Update Policy Classes

```java
public class PolicyBuilder {
    public PolicyBuilder setAlgorithm(Algorithm algorithm) {
        // Handle new algorithms
        switch (algorithm) {
            case DQN:
                return setDqnParameters();
            case PPO:
                return setPpoParameters();
            // ... existing cases
        }
    }
}
```

#### 3. Update Client Logic

```java
public class SchedulerClient {
    private void validatePolicy(SchedulingPolicy policy) {
        Algorithm algorithm = policy.getAlgorithm();
        switch (algorithm) {
            case DQN:
                validateDqnPolicy(policy);
                break;
            case PPO:
                validatePpoPolicy(policy);
                break;
            // ... existing cases
        }
    }
}
```

### Adding New Device Types

#### 1. Create Device Class

```java
public class RLNewDevice extends FogDevice {
    private NewServiceClient newServiceClient;
    private boolean rlEnabled = false;

    public RLNewDevice(String name, long mips, int ram,
                      long storage, double bandwidth, String os,
                      double costPerBw, double costPerSec,
                      double costPerMem, double costPerStorage) {
        super(name, mips, ram, storage, bandwidth, os,
              costPerBw, costPerSec, costPerMem, costPerStorage);

        if (RLConfig.isNewDeviceRLEnabled()) {
            enableRL();
        }
    }

    public void enableRL() {
        this.rlEnabled = true;
        // Initialize RL components
    }

    public void configureRLServer(String host, int port) {
        this.newServiceClient = new NewServiceClient(host, port);
    }
}
```

#### 2. Update Device Factory

```java
public class DeviceFactory {
    public static RLNewDevice createRLNewDevice(String name,
                                                 Map<String, Object> properties) {
        // Extract properties and create device
        long mips = (Long) properties.getOrDefault("mips", 1000L);
        int ram = (Integer) properties.getOrDefault("ram", 512);
        // ... other properties

        RLNewDevice device = new RLNewDevice(name, mips, ram,
                                            storage, bandwidth, os,
                                            costPerBw, costPerSec,
                                            costPerMem, costPerStorage);

        // Configure RL if enabled
        if (RLConfig.isNewDeviceRLEnabled()) {
            String host = RLConfig.getNewDeviceRLServerHost();
            int port = RLConfig.getNewDeviceRLServerPort();
            device.configureRLServer(host, port);
        }

        return device;
    }
}
```

### Adding New Configuration Options

#### 1. Update Configuration Class

```java
public class RLConfig {
    // New configuration options
    private static boolean newDeviceRLEnabled = false;
    private static String newDeviceRLServerHost = "localhost";
    private static int newDeviceRLServerPort = 50053;

    public static boolean isNewDeviceRLEnabled() {
        return newDeviceRLEnabled;
    }

    public static String getNewDeviceRLServerHost() {
        return newDeviceRLServerHost;
    }

    public static int getNewDeviceRLServerPort() {
        return newDeviceRLServerPort;
    }
}
```

#### 2. Update YAML Configuration

```yaml
rl:
  fog:
    enabled: true
  cloud:
    enabled: true
  new_device: # New device type
    enabled: true
    server:
      host: 'localhost'
      port: 50053
```

#### 3. Update Configuration Loader

```java
public class ConfigurationLoader {
    private void loadRLConfig(Map<String, Object> config) {
        Map<String, Object> rlConfig = (Map<String, Object>) config.get("rl");
        if (rlConfig != null) {
            // Load new device configuration
            Map<String, Object> newDeviceConfig =
                (Map<String, Object>) rlConfig.get("new_device");
            if (newDeviceConfig != null) {
                RLConfig.setNewDeviceRLEnabled(
                    (Boolean) newDeviceConfig.getOrDefault("enabled", false));
                // ... other new device config
            }
        }
    }
}
```

### Adding New Logging Features

#### 1. Extend StructuredLogger

```java
public class StructuredLogger {
    public void logCustomEvent(String eventType, Map<String, Object> fields) {
        if (shouldLog(Level.INFO)) {
            Map<String, Object> logFields = new HashMap<>();
            logFields.put("event_type", eventType);
            logFields.put("timestamp", System.currentTimeMillis());
            if (fields != null) {
                logFields.putAll(fields);
            }
            log(Level.INFO, "Custom event: " + eventType, logFields, null);
        }
    }
}
```

#### 2. Add Custom Log Handlers

```java
public class LogFileManager {
    public FileHandler createCustomLogHandler(String loggerName, Level level) {
        return createFileHandler(loggerName, level);
    }
}
```

### Testing Extensions

#### 1. Unit Tests

```java
@Test
public void testNewServiceClient() {
    NewServiceClient client = new NewServiceClient("localhost", 50053);
    NewRequest request = NewRequest.newBuilder()
        .setRequestId("test_001")
        .build();

    NewResponse response = client.callNewMethod(request);
    assertTrue(response.getSuccess());
}
```

#### 2. Integration Tests

```java
@Test
public void testRLNewDevice() {
    RLNewDevice device = DeviceFactory.createRLNewDevice("test_device", properties);
    assertTrue(device.isRLEnabled());

    device.configureRLServer("localhost", 50053);
    // Test RL functionality
}
```

### Performance Optimization

#### 1. Connection Pooling

```java
public class ConnectionPool {
    private static final int MAX_POOL_SIZE = 10;
    private Queue<GrpcClient> availableClients = new ConcurrentLinkedQueue<>();
    private Queue<GrpcClient> usedClients = new ConcurrentLinkedQueue<>();

    public GrpcClient getClient(String host, int port) {
        GrpcClient client = availableClients.poll();
        if (client == null) {
            client = new GrpcClient(host, port);
        }
        usedClients.offer(client);
        return client;
    }

    public void returnClient(GrpcClient client) {
        usedClients.remove(client);
        availableClients.offer(client);
    }
}
```

#### 2. Caching

```java
public class ResponseCache {
    private Map<String, Object> cache = new ConcurrentHashMap<>();
    private long ttl = 60000; // 1 minute TTL

    public <T> T get(String key, Class<T> type) {
        CacheEntry entry = (CacheEntry) cache.get(key);
        if (entry != null && !entry.isExpired()) {
            return type.cast(entry.getValue());
        }
        return null;
    }

    public void put(String key, Object value) {
        cache.put(key, new CacheEntry(value, System.currentTimeMillis() + ttl));
    }
}
```

### Monitoring and Metrics

#### 1. Custom Metrics

```java
public class MetricsCollector {
    private Map<String, Long> counters = new ConcurrentHashMap<>();
    private Map<String, Double> gauges = new ConcurrentHashMap<>();

    public void incrementCounter(String name) {
        counters.merge(name, 1L, Long::sum);
    }

    public void setGauge(String name, double value) {
        gauges.put(name, value);
    }

    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("counters", counters);
        metrics.put("gauges", gauges);
        return metrics;
    }
}
```

#### 2. Health Checks

```java
public class HealthChecker {
    public HealthStatus checkServiceHealth(String serviceName) {
        try {
            // Perform health check
            boolean healthy = performHealthCheck(serviceName);
            return new HealthStatus(healthy, "Service is " + (healthy ? "healthy" : "unhealthy"));
        } catch (Exception e) {
            return new HealthStatus(false, "Health check failed: " + e.getMessage());
        }
    }
}
```

## Best Practices

### 1. Error Handling

- Always check service availability before making requests
- Implement proper fallback mechanisms
- Use circuit breaker patterns for fault tolerance
- Log errors with sufficient context

### 2. Configuration Management

- Use environment variables for deployment-specific settings
- Provide sensible defaults for all configuration options
- Validate configuration on startup
- Support both YAML and environment variable configuration

### 3. Logging

- Use structured logging with correlation IDs
- Include relevant context in log messages
- Use appropriate log levels
- Enable performance logging for optimization

### 4. Testing

- Write unit tests for all new functionality
- Include integration tests for gRPC services
- Test error conditions and edge cases
- Use mocking for external dependencies

### 5. Documentation

- Document all public APIs
- Include usage examples
- Update documentation when adding new features
- Provide troubleshooting guides

## Common Pitfalls

### 1. Resource Leaks

```java
// BAD: Not closing clients
GrpcClient client = new GrpcClient(host, port);
// ... use client
// client is not closed

// GOOD: Proper resource management
try (GrpcClient client = new GrpcClient(host, port)) {
    // ... use client
} // client is automatically closed
```

### 2. Blocking Operations

```java
// BAD: Blocking in main thread
ScheduleTaskResponse response = scheduler.scheduleTask(task, nodes, policy);

// GOOD: Use async operations
CompletableFuture<ScheduleTaskResponse> future =
    scheduler.scheduleTaskAsync(task, nodes, policy);
```

### 3. Configuration Issues

```java
// BAD: Hardcoded values
SchedulerClient scheduler = new SchedulerClient("localhost", 50051);

// GOOD: Use configuration
String host = ConfigurationLoader.getSchedulerHost();
int port = ConfigurationLoader.getSchedulerPort();
SchedulerClient scheduler = new SchedulerClient(host, port);
```

---

_This guide provides comprehensive information for extending the iFogSim gRPC integration. For additional help, refer to the API documentation and source code._
