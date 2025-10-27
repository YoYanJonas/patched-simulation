# iFogSim gRPC Integration - Quick Reference

## Quick Start

### 1. Initialize Clients

```java
// Task Scheduler
SchedulerClient scheduler = new SchedulerClient("localhost", 50051);

// Resource Allocator
AllocationClient allocator = new AllocationClient("localhost", 50052);
```

### 2. Basic Task Scheduling

```java
Task task = createTask();
List<FogNode> nodes = getAvailableNodes();
SchedulingPolicy policy = new SchedulingPolicy(Algorithm.Q_LEARNING);

ScheduleTaskResponse response = scheduler.scheduleTask(task, nodes, policy);
if (response.getSuccess()) {
    int nodeId = response.getSelectedNodeId();
    // Process with selected node
}
```

### 3. Resource Allocation

```java
AllocationPolicy policy = new AllocationPolicy(Algorithm.SARSA);
AllocationResponse response = allocator.allocateTask(task, nodes, policy);
if (response.getSuccess()) {
    int targetNodeId = response.getTargetNodeId();
    // Allocate to target node
}
```

## Configuration

### Environment Variables

```bash
export GRPC_SCHEDULER_HOST=localhost
export GRPC_SCHEDULER_PORT=50051
export GRPC_ALLOCATION_HOST=localhost
export GRPC_ALLOCATION_PORT=50052
```

### YAML Configuration

```yaml
grpc:
  scheduler:
    host: 'localhost'
    port: 50051
  allocation:
    host: 'localhost'
    port: 50052
```

## Error Handling

### Check Service Availability

```java
if (scheduler.isServiceAvailable()) {
    // Make gRPC call
} else {
    // Use fallback
}
```

### Circuit Breaker State

```java
CircuitBreakerState state = scheduler.getCircuitBreakerState();
// CLOSED, OPEN, or HALF_OPEN
```

## Common Patterns

### Batch Operations

```java
List<Task> tasks = getTasks();
BatchScheduleResponse response = scheduler.scheduleBatchTasks(tasks, nodes, policy);
```

### Health Checking

```java
boolean healthy = scheduler.performHealthCheck();
```

### Logging

```java
StructuredLogger logger = new StructuredLogger();
logger.info("Operation completed", fields);
```

## Troubleshooting

| Issue                | Solution                   |
| -------------------- | -------------------------- |
| Connection failed    | Check service is running   |
| Timeout errors       | Increase timeout values    |
| Circuit breaker open | Wait for service recovery  |
| No response          | Check network connectivity |

## Service Endpoints

| Service        | Host      | Port  | Purpose                      |
| -------------- | --------- | ----- | ---------------------------- |
| Task Scheduler | localhost | 50051 | RL-based task scheduling     |
| Fog Allocation | localhost | 50052 | RL-based resource allocation |

## Data Flow

```
Task → SchedulerClient → gRPC Service → RL Agent → Response → iFogSim
```

## Key Classes

- `GrpcClient`: Base gRPC client with circuit breaker
- `SchedulerClient`: Task scheduling operations
- `AllocationClient`: Resource allocation operations
- `RLFogDevice`: RL-enabled fog device
- `RLCloudDevice`: RL-enabled cloud device

## Logging Levels

- `DEBUG`: Detailed debugging information
- `INFO`: General information
- `WARNING`: Warning messages
- `ERROR`: Error conditions
- `SEVERE`: Critical errors

## Performance Tips

1. **Reuse clients** - Don't create new clients for each request
2. **Use batch operations** - Group multiple tasks together
3. **Enable caching** - Cache frequently accessed data
4. **Monitor timeouts** - Set appropriate timeout values
5. **Check health** - Monitor service health regularly

---

_For detailed documentation, see [API-DOCUMENTATION.md](API-DOCUMENTATION.md)_
