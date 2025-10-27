# iFogSim gRPC Integration API Documentation

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [gRPC Services](#grpc-services)
4. [Client APIs](#client-apis)
5. [Configuration](#configuration)
6. [Usage Examples](#usage-examples)
7. [Error Handling](#error-handling)
8. [Performance Considerations](#performance-considerations)
9. [Troubleshooting](#troubleshooting)

## Overview

The iFogSim gRPC Integration provides a comprehensive API for integrating Reinforcement Learning (RL) capabilities into fog computing simulations. This integration enables intelligent task scheduling and resource allocation through external gRPC services.

### Key Features

- **RL-based Task Scheduling**: Intelligent task scheduling using external RL agents
- **Dynamic Resource Allocation**: Adaptive resource allocation based on system state
- **Fault Tolerance**: Circuit breaker patterns and graceful degradation
- **Comprehensive Logging**: Structured logging with correlation tracking
- **Configuration Management**: Centralized configuration with YAML support

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   iFogSim       │    │  gRPC Services  │    │  RL Agents      │
│   Simulation    │◄──►│                 │◄──►│                 │
│                 │    │                 │    │                 │
│ ┌─────────────┐ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ │RLFogDevice  │ │    │ │TaskScheduler│ │    │ │Q-Learning   │ │
│ │RLCloudDevice│ │    │ │Service      │ │    │ │Agent        │ │
│ └─────────────┘ │    │ └─────────────┘ │    │ └─────────────┘ │
│                 │    │                 │    │                 │
│                 │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│                 │    │ │FogAllocation│ │    │ │SARSA Agent  │ │
│                 │    │ │Service      │ │    │ │             │ │
│                 │    │ └─────────────┘ │    │ └─────────────┘ │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

## gRPC Services

### 1. Task Scheduler Service (`grpc-task-scheduler`)

**Purpose**: Handles intelligent task scheduling for fog nodes using RL algorithms.

**Key Operations**:

- `ScheduleTask`: Get RL-based task scheduling decisions
- `ReportNodeState`: Stream node state information for learning
- `ReportTaskOutcome`: Report task completion metrics

**Proto Definition**: `ifogsim_scheduler.proto`

### 2. Fog Allocation Service (`go-grpc-server`)

**Purpose**: Manages resource allocation and load balancing for cloud nodes.

**Key Operations**:

- `AllocateTask`: Get RL-based allocation decisions
- `ReportNodeState`: Stream node state information for learning
- `ReportTaskOutcome`: Report task completion metrics

**Proto Definition**: `ifogsim_allocation.proto`

## Client APIs

### GrpcClient (Base Client)

The foundation client providing core gRPC functionality.

#### Constructor

```java
public GrpcClient(String host, int port)
public GrpcClient(GrpcClientConfig config)
```

#### Key Methods

##### Connection Management

```java
public boolean connect()
public void disconnect()
public boolean isConnected()
```

**Description**: Manages gRPC channel connections with automatic retry and health checking.

**Parameters**:

- Returns `boolean`: Connection success status

**Example**:

```java
GrpcClient client = new GrpcClient("localhost", 50051);
if (client.connect()) {
    // Connection successful
}
```

##### Circuit Breaker

```java
public CircuitBreakerState getCircuitBreakerState()
public boolean isServiceAvailable()
```

**Description**: Provides circuit breaker functionality for fault tolerance.

**Returns**:

- `CircuitBreakerState`: Current state (CLOSED, OPEN, HALF_OPEN)
- `boolean`: Service availability status

##### Health Checking

```java
public boolean performHealthCheck()
```

**Description**: Performs health check on the gRPC service.

**Returns**: `boolean` - Health check result

### SchedulerClient

Specialized client for task scheduling operations.

#### Constructor

```java
public SchedulerClient(String host, int port)
public SchedulerClient(GrpcClient baseClient)
```

#### Key Methods

##### Task Scheduling

```java
public ScheduleTaskResponse scheduleTask(Task task, List<FogNode> availableNodes, SchedulingPolicy policy)
```

**Description**: Schedules a task using RL-based decision making.

**Parameters**:

- `Task task`: The task to be scheduled
- `List<FogNode> availableNodes`: Available fog nodes for scheduling
- `SchedulingPolicy policy`: Scheduling policy configuration

**Returns**: `ScheduleTaskResponse` - Scheduling decision with selected node

**Example**:

```java
SchedulerClient scheduler = new SchedulerClient("localhost", 50051);
Task task = createTask();
List<FogNode> nodes = getAvailableNodes();
SchedulingPolicy policy = new SchedulingPolicy(Algorithm.Q_LEARNING);

ScheduleTaskResponse response = scheduler.scheduleTask(task, nodes, policy);
if (response.getSuccess()) {
    int selectedNodeId = response.getSelectedNodeId();
    // Process with selected node
}
```

##### Batch Task Scheduling

```java
public BatchScheduleResponse scheduleBatchTasks(List<Task> tasks, List<FogNode> availableNodes, SchedulingPolicy policy)
```

**Description**: Schedules multiple tasks in a single request for efficiency.

**Parameters**:

- `List<Task> tasks`: List of tasks to schedule
- `List<FogNode> availableNodes`: Available fog nodes
- `SchedulingPolicy policy`: Scheduling policy

**Returns**: `BatchScheduleResponse` - Batch scheduling results

### AllocationClient

Specialized client for resource allocation operations.

#### Constructor

```java
public AllocationClient(String host, int port)
public AllocationClient(GrpcClient baseClient)
```

#### Key Methods

##### Task Allocation

```java
public AllocationResponse allocateTask(Task task, List<FogNode> availableNodes, AllocationPolicy policy)
```

**Description**: Allocates a task to the most suitable fog node using RL-based decisions.

**Parameters**:

- `Task task`: Task to allocate
- `List<FogNode> availableNodes`: Available fog nodes
- `AllocationPolicy policy`: Allocation policy configuration

**Returns**: `AllocationResponse` - Allocation decision

**Example**:

```java
AllocationClient allocator = new AllocationClient("localhost", 50052);
Task task = createTask();
List<FogNode> nodes = getAvailableNodes();
AllocationPolicy policy = new AllocationPolicy(Algorithm.SARSA);

AllocationResponse response = allocator.allocateTask(task, nodes, policy);
if (response.getSuccess()) {
    int targetNodeId = response.getTargetNodeId();
    // Allocate to target node
}
```

##### Node State Reporting

```java
public void reportNodeState(String nodeId, NodeState nodeState)
```

**Description**: Reports node state information for RL learning.

**Parameters**:

- `String nodeId`: Node identifier
- `NodeState nodeState`: Current node state

## Configuration

### Application Configuration (`application.yml`)

```yaml
grpc:
  scheduler:
    host: 'localhost'
    port: 50051
    timeout: 5000
    retry_attempts: 3
  allocation:
    host: 'localhost'
    port: 50052
    timeout: 5000
    retry_attempts: 3

logging:
  level: 'INFO'
  directory: 'logs'
  json:
    format: true
  correlation:
    enabled: true
  performance:
    enabled: true

rl:
  fog:
    enabled: true
    state_report_interval: 1000
  cloud:
    enabled: true
    state_report_interval: 2000
```

### Environment Variables

| Variable               | Description             | Default     |
| ---------------------- | ----------------------- | ----------- |
| `GRPC_SCHEDULER_HOST`  | Scheduler service host  | `localhost` |
| `GRPC_SCHEDULER_PORT`  | Scheduler service port  | `50051`     |
| `GRPC_ALLOCATION_HOST` | Allocation service host | `localhost` |
| `GRPC_ALLOCATION_PORT` | Allocation service port | `50052`     |
| `GRPC_TIMEOUT`         | Request timeout (ms)    | `5000`      |
| `GRPC_RETRY_ATTEMPTS`  | Retry attempts          | `3`         |

## Usage Examples

### Basic Task Scheduling

```java
// Initialize scheduler client
SchedulerClient scheduler = new SchedulerClient("localhost", 50051);

// Create task
Task task = Task.newBuilder()
    .setTaskId("task_001")
    .setCpuRequirement(1000)
    .setMemoryRequirement(512)
    .setDeadline(5000)
    .build();

// Get available nodes
List<FogNode> nodes = getAvailableFogNodes();

// Configure scheduling policy
SchedulingPolicy policy = SchedulingPolicy.newBuilder()
    .setAlgorithm(Algorithm.Q_LEARNING)
    .setExplorationRate(0.1)
    .build();

// Schedule task
ScheduleTaskResponse response = scheduler.scheduleTask(task, nodes, policy);

if (response.getSuccess()) {
    int selectedNodeId = response.getSelectedNodeId();
    System.out.println("Task scheduled to node: " + selectedNodeId);
} else {
    System.err.println("Scheduling failed: " + response.getMessage());
}
```

### Resource Allocation

```java
// Initialize allocation client
AllocationClient allocator = new AllocationClient("localhost", 50052);

// Create allocation request
Task task = createTask();
List<FogNode> nodes = getAvailableNodes();

AllocationPolicy policy = AllocationPolicy.newBuilder()
    .setAlgorithm(Algorithm.SARSA)
    .setLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN)
    .build();

// Allocate task
AllocationResponse response = allocator.allocateTask(task, nodes, policy);

if (response.getSuccess()) {
    int targetNodeId = response.getTargetNodeId();
    double confidence = response.getConfidence();
    System.out.println("Task allocated to node " + targetNodeId +
                     " with confidence " + confidence);
}
```

### Batch Operations

```java
// Batch task scheduling
List<Task> tasks = createTaskBatch();
BatchScheduleResponse batchResponse = scheduler.scheduleBatchTasks(tasks, nodes, policy);

for (int i = 0; i < batchResponse.getResponsesCount(); i++) {
    ScheduleTaskResponse response = batchResponse.getResponses(i);
    if (response.getSuccess()) {
        System.out.println("Task " + i + " scheduled to node " + response.getSelectedNodeId());
    }
}
```

## Error Handling

### Circuit Breaker States

1. **CLOSED**: Normal operation, requests allowed
2. **OPEN**: Service unavailable, requests blocked
3. **HALF_OPEN**: Testing service recovery, limited requests allowed

### Exception Handling

```java
try {
    ScheduleTaskResponse response = scheduler.scheduleTask(task, nodes, policy);
    // Process response
} catch (StatusRuntimeException e) {
    switch (e.getStatus().getCode()) {
        case UNAVAILABLE:
            // Service unavailable, use fallback
            handleServiceUnavailable();
            break;
        case DEADLINE_EXCEEDED:
            // Request timeout, retry if appropriate
            handleTimeout();
            break;
        case INTERNAL:
            // Internal server error
            handleInternalError();
            break;
        default:
            // Other errors
            handleGenericError(e);
    }
}
```

### Graceful Degradation

```java
// Check service availability before making requests
if (scheduler.isServiceAvailable()) {
    // Use RL-based scheduling
    ScheduleTaskResponse response = scheduler.scheduleTask(task, nodes, policy);
} else {
    // Fall back to traditional scheduling
    int selectedNode = fallbackScheduling(task, nodes);
}
```

## Performance Considerations

### Connection Pooling

```java
// Reuse client instances
SchedulerClient scheduler = new SchedulerClient("localhost", 50051);
// Use the same instance for multiple requests
```

### Batch Operations

```java
// Use batch operations for multiple tasks
List<Task> tasks = getTasksToSchedule();
BatchScheduleResponse response = scheduler.scheduleBatchTasks(tasks, nodes, policy);
```

### Caching

```java
// Enable caching for frequently accessed data
if (response.getIsCachedTask()) {
    // Use cached result
    handleCachedResponse(response);
} else {
    // Process normally
    processNormalResponse(response);
}
```

### Timeout Configuration

```java
// Configure appropriate timeouts
GrpcClientConfig config = GrpcClientConfig.builder()
    .setHost("localhost")
    .setPort(50051)
    .setTimeout(5000) // 5 seconds
    .setRetryAttempts(3)
    .build();
```

## Troubleshooting

### Common Issues

#### 1. Connection Failures

```
Error: UNAVAILABLE: io exception
```

**Solution**: Check if gRPC services are running and accessible.

#### 2. Timeout Errors

```
Error: DEADLINE_EXCEEDED: deadline exceeded
```

**Solution**: Increase timeout values or optimize request processing.

#### 3. Circuit Breaker Open

```
Warning: Circuit breaker is OPEN, requests blocked
```

**Solution**: Wait for service recovery or check service health.

### Debugging

#### Enable Debug Logging

```yaml
logging:
  level: 'DEBUG'
  correlation:
    enabled: true
```

#### Check Service Health

```java
boolean isHealthy = scheduler.performHealthCheck();
if (!isHealthy) {
    // Service is not responding
}
```

#### Monitor Circuit Breaker State

```java
CircuitBreakerState state = scheduler.getCircuitBreakerState();
System.out.println("Circuit breaker state: " + state);
```

### Log Analysis

#### Correlation Tracking

```java
// Enable correlation IDs for request tracking
StructuredLogger logger = new StructuredLogger();
logger.setCorrelationId("req_001");
```

#### Performance Monitoring

```java
// Monitor request performance
long startTime = System.currentTimeMillis();
ScheduleTaskResponse response = scheduler.scheduleTask(task, nodes, policy);
long duration = System.currentTimeMillis() - startTime;
logger.performance("schedule_task", duration, metrics);
```

## API Reference

### Data Types

#### Task

```java
Task task = Task.newBuilder()
    .setTaskId("unique_id")
    .setCpuRequirement(1000)        // CPU requirement in MIPS
    .setMemoryRequirement(512)      // Memory requirement in MB
    .setDeadline(5000)              // Deadline in milliseconds
    .setPriority(1)                 // Task priority (1-10)
    .build();
```

#### FogNode

```java
FogNode node = FogNode.newBuilder()
    .setNodeId("node_001")
    .setCpuCapacity(2000)           // CPU capacity in MIPS
    .setMemoryCapacity(1024)        // Memory capacity in MB
    .setCurrentUsage(NodeUsage.newBuilder()
        .setCpuUsage(50)            // Current CPU usage percentage
        .setMemoryUsage(30)         // Current memory usage percentage
        .build())
    .build();
```

#### SchedulingPolicy

```java
SchedulingPolicy policy = SchedulingPolicy.newBuilder()
    .setAlgorithm(Algorithm.Q_LEARNING)
    .setExplorationRate(0.1)        // Exploration rate for RL
    .setLearningRate(0.01)          // Learning rate for RL
    .setDiscountFactor(0.9)         // Discount factor for RL
    .build();
```

### Response Types

#### ScheduleTaskResponse

```java
ScheduleTaskResponse response = ScheduleTaskResponse.newBuilder()
    .setSuccess(true)
    .setSelectedNodeId(1)
    .setConfidence(0.95)
    .setIsCachedTask(false)
    .setCacheAction(CacheAction.CACHE_ACTION_NONE)
    .setMessage("Task scheduled successfully")
    .build();
```

#### AllocationResponse

```java
AllocationResponse response = AllocationResponse.newBuilder()
    .setSuccess(true)
    .setTargetNodeId(2)
    .setConfidence(0.88)
    .setLoadBalancingFactor(0.75)
    .setMessage("Task allocated successfully")
    .build();
```

---

## Version History

- **v1.0.0**: Initial gRPC integration with basic scheduling and allocation
- **v1.1.0**: Added circuit breaker patterns and fault tolerance
- **v1.2.0**: Enhanced logging and monitoring capabilities
- **v1.3.0**: Added batch operations and performance optimizations

## Support

For issues and questions:

- Check the troubleshooting section above
- Review log files in the configured log directory
- Enable debug logging for detailed information
- Contact the development team for advanced issues

---

_This documentation covers the complete gRPC integration API for iFogSim. For implementation details, refer to the source code and JavaDoc comments._
