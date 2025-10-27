# iFogSim2 RL-Enhanced Fog Computing System

A comprehensive **Reinforcement Learning (RL) enhanced fog computing simulation system** that extends iFogSim2 with real-time gRPC communication capabilities for intelligent task allocation and scheduling in fog computing environments. This system demonstrates the integration of machine learning algorithms with distributed computing simulation frameworks to optimize resource utilization and task management in edge computing scenarios.

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           iFogSim2 Simulation Environment                    │
├─────────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐        │
│  │   Cloud Device  │    │  Fog Node 1      │    │  Fog Node 2      │        │
│  │  (RLCloudDevice)│    │  (RLFogDevice)   │    │  (RLFogDevice)   │        │
│  │                 │    │                  │    │                  │        │
│  │  • External     │    │  • Sensor Data   │    │  • Sensor Data   │        │
│  │    Task Gen     │    │  • Unscheduled   │    │  • Unscheduled   │        │
│  │  • RL           │    │    Queue         │    │    Queue         │        │
│  │    Allocation   │    │  • Scheduled     │    │  • Scheduled     │        │
│  │                 │    │    Queue         │    │    Queue         │        │
│  └─────────────────┘    └──────────────────┘    └─────────────────┘        │
│           │                       │                       │                │
│           │ gRPC                  │ gRPC                  │ gRPC            │
│           ▼                       ▼                       ▼                │
└─────────────────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────────────────┐
│                           External gRPC Services                            │
├─────────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────┐              ┌─────────────────────────────────┐   │
│  │  go-grpc-server     │              │  grpc-task-scheduler            │   │
│  │  (Allocation)       │              │  (Scheduling)                   │   │
│  │                     │              │                                 │   │
│  │  • Q-Learning       │              │  • Q-Learning                   │   │
│  │  • SARSA            │              │  • Multi-Objective              │   │
│  │  • Multi-Objective  │              │  • Experience Management        │   │
│  │  • Node Selection   │              │  • Queue Optimization          │   │
│  │  • Delayed Rewards  │              │  • Cache Management             │   │
│  │                     │              │  • Streaming Updates            │   │
│  └─────────────────────┘              └─────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Project Components

### 1. iFogSim2 Enhanced Simulation (`ifogsim/`)

**Purpose**: Core simulation environment with RL-aware extensions

**Key Features**:

- **Event-driven discrete simulation** utilizing CloudSim framework
- **RL-aware device classes** extending iFogSim's base classes
- **gRPC client integration** enabling real-time communication
- **Two-queue task management system** (Unscheduled → Scheduled)
- **Comprehensive metrics collection** and reporting capabilities

**Core Patched Classes**:

- `RLCloudDevice` - Cloud device with RL-based task allocation
- `RLFogDevice` - Fog nodes with RL-based task scheduling
- `RLController` - RL-aware simulation controller
- `RLFogBroker` - RL-aware resource broker
- `ExternalTaskGenerator` - Generates external tasks for testing
- `TaskExecutionEngine` - Processes tasks from scheduled queue
- `StreamingQueueObserver` - Handles real-time queue updates

### 2. go-grpc-server (Allocation Service)

**Purpose**: RL-based task allocation service responsible for determining optimal fog node assignment for external tasks

**Key Features**:

- **Multi-algorithm RL support** (Q-Learning, SARSA, Hybrid)
- **Multi-objective optimization** considering latency, energy, cost, and fairness
- **Delayed reward system** facilitating RL learning processes
- **Node state management** and comprehensive reporting
- **Model persistence** and loading capabilities

**Core Components**:

- `FogAllocationService` - Main gRPC service implementation
- `Agent` - RL agent with configurable algorithms
- `AlgorithmManager` - Manages different RL algorithms
- `ExperienceManager` - Handles experience replay and learning
- `TuningManager` - Hyperparameter optimization

**gRPC Services**:

- `AllocateTask` - Main allocation decision endpoint
- `ReportTaskOutcome` - Delayed reward reporting
- `GetSystemState` - System state monitoring
- `ControlAgent` - RL agent control (start/stop/reset)

### 3. grpc-task-scheduler (Scheduling Service)

**Purpose**: RL-based task scheduling service that optimizes task execution order within individual fog nodes

**Key Features**:

- **Per-fog-node scheduling** with 1-1 mapping to fog nodes
- **Streaming queue updates** enabling real-time optimization
- **Cache management** for intelligent task result reuse
- **Multiple scheduling algorithms** (FIFO, Priority, SJF, RL)
- **Performance monitoring** and comprehensive metrics collection

**Core Components**:

- `SchedulerService` - Main gRPC service implementation
- `SchedulerEngine` - Core scheduling logic
- `TaskCacheManager` - Cache decision management
- `Node` - Fog node state management
- `Queue` - Task queue implementations (FIFO, Priority, SJF)

**gRPC Services**:

- `AddTaskToQueue` - Add task to scheduling queue
- `SubscribeToQueueUpdates` - Streaming queue updates
- `ReportTaskCompletion` - Task completion reporting
- `GetSortedQueue` - Get current queue state
- `UpdateObjectiveWeights` - Runtime configuration

## System Integration and Workflow

### Task Flow Process

#### External Task Flow:

1. **ExternalTaskGenerator** creates external task
2. **RLCloudDevice** receives task via `TUPLE_ARRIVAL` event
3. **AllocationClient** calls `go-grpc-server` for allocation decision
4. **go-grpc-server** uses RL agent to select best fog node
5. **RLCloudDevice** forwards task to selected fog node
6. **RLFogDevice** receives task and adds to `UnscheduledQueue`
7. **SchedulerClient** sends task to `grpc-task-scheduler`
8. **grpc-task-scheduler** adds task to queue and returns position
9. **StreamingQueueObserver** receives periodic queue updates
10. **RLFogDevice** updates `ScheduledQueue` with new order
11. **TaskExecutionEngine** processes tasks from queue head

#### Internal Task Flow (Sensor Data):

1. **Sensor** generates data tuple every 5 seconds
2. **RLFogDevice** receives tuple via `TUPLE_ARRIVAL` event
3. Same process as external tasks from step 6 above

### RL Learning Process

#### Allocation Learning (go-grpc-server):

1. **Node state reporting** - Cloud device reports fog node states
2. **Task allocation** - RL agent selects fog node based on current state
3. **Delayed reward** - Task completion reported back to allocation service
4. **Model update** - RL agent learns from experience and updates Q-values
5. **Model persistence** - Learned models saved to JSON files

#### Scheduling Learning (grpc-task-scheduler):

1. **Task queuing** - Tasks added to scheduling queue
2. **RL optimization** - RL agent optimizes queue order based on objectives
3. **Streaming updates** - Optimized queue sent to fog device
4. **Task execution** - Tasks executed in optimized order
5. **Completion reporting** - Task completion reported for learning
6. **Experience replay** - Experiences stored and used for learning

## Key Technical Innovations

### 1. Two-Queue System

- **UnscheduledQueue**: Tasks waiting for scheduling decisions
- **ScheduledQueue**: Tasks ordered by RL optimization
- **Streaming Updates**: Real-time queue reordering via gRPC streaming

### 2. Cache Management

- **Task Fingerprinting**: Identical tasks identified for caching
- **Cache Actions**: Store, Use, Invalidate, None
- **Metadata Integration**: Cache decisions included in queue updates

### 3. Delayed Reward System

- **Task Completion Reporting**: Execution results sent back to RL agents
- **Multi-Objective Rewards**: Latency, energy, cost, fairness considerations
- **Experience Replay**: Past experiences stored for learning

### 4. Real-time Communication

- **gRPC Streaming**: Real-time queue updates from scheduler
- **Circuit Breaker Pattern**: Fault tolerance for gRPC calls
- **Retry Logic**: Exponential backoff for failed requests

### 5. Comprehensive Metrics

- **RLStatisticsManager**: Centralized metrics collection
- **TimeKeeper Integration**: iFogSim's built-in timing system
- **Performance Monitoring**: Detailed execution statistics

## Project Structure

```
bundle/
├── ifogsim/                          # Enhanced iFogSim2 simulation
│   ├── src/main/java/org/patch/      # RL-aware patch classes
│   │   ├── devices/                  # RLCloudDevice, RLFogDevice
│   │   ├── client/                   # gRPC clients
│   │   ├── processing/               # Task execution engine
│   │   ├── integration/              # Streaming observers
│   │   └── utils/                    # Configuration, statistics
│   └── src/main/java/scenarios/      # RL3FogSimulation.java
├── go-grpc-server/                   # Allocation service
│   ├── service/gateway/              # gRPC service implementation
│   ├── pkg/rl/                       # RL algorithms
│   └── models/                       # Learned models
├── grpc-task-scheduler/              # Scheduling service
│   ├── internal/scheduler/           # gRPC service implementation
│   ├── internal/models/              # Scheduler engine
│   └── internal/rl/                  # RL algorithms
├── scenarios/                        # Scenario-based configurations
│   ├── qlearning-baseline/
│   ├── fifo-traditional/
│   ├── priority-traditional/
│   ├── sarsa-rl/
│   ├── latency-focused/
│   └── throughput-focused/
├── config/                           # Configuration files
├── monitoring/                       # Monitoring configuration
├── run-scenario.sh                   # Scenario runner script
└── SCENARIO-BASED-CONFIGURATION.md   # Configuration guide
```

## Scenario-Based Configuration

The system supports multiple simulation scenarios for algorithm comparison:

- **qlearning-baseline**: Q-Learning RL with balanced objectives
- **fifo-traditional**: FIFO traditional algorithm
- **priority-traditional**: Priority traditional algorithm
- **sarsa-rl**: SARSA RL algorithm (allocation service only)
- **latency-focused**: Q-Learning RL with latency focus
- **throughput-focused**: Q-Learning RL with throughput focus

Each scenario has its own dedicated `docker-compose.yml` file with specific algorithm and objective configurations.

### Algorithm Support by Service

- **go-grpc-server (Allocation)**: Q-Learning, SARSA, Hybrid
- **grpc-task-scheduler (Scheduling)**: Q-Learning only (traditional algorithms: FIFO, Priority, SJF, EDF)

## Configuration

### iFogSim Configuration (`ifogsim/config/application.yml`):

```yaml
grpc:
  allocation:
    host: localhost
    port: 50051
  scheduler:
    host: localhost
    port: 50052

simulation:
  external-tasks:
    enabled: true
    generation-rate: 2.0
    initial-delay: 1000

rl:
  fog-enabled: true
  cloud-enabled: true
  state-report-interval: 5000
  placement-update-interval: 10000
```

### go-grpc-server Configuration (`go-grpc-server/config/config.yaml`):

```yaml
server:
  port: 50051
  host: '0.0.0.0'

rl:
  enabled: true
  algorithm: 'hybrid'
  learning_rate: 0.1
  discount_factor: 0.9
  epsilon: 0.1

multi_objective:
  enabled: true
  active_profile: 'balanced'
  scalarization_method: 'weighted_sum'
```

### grpc-task-scheduler Configuration (`grpc-task-scheduler/config/config.yaml`):

```yaml
server:
  port: 50052
  host: '0.0.0.0'

single_node:
  node_id: 'fog-node-0'
  max_concurrent_tasks: 10
  default_algorithm: 'qlearning'

rl:
  enabled: true
  algorithm: 'qlearning' # Only Q-Learning supported
  learning_rate: 0.05
  discount_factor: 0.95

cache:
  enabled: true
  max_size: 1000
  ttl_seconds: 3600
```

## Project Goals Achieved

1. **RL-Enhanced Fog Computing**: Successfully integrated RL algorithms into iFogSim
2. **Real-time Communication**: gRPC services provide real-time decision making
3. **Scalable Architecture**: 1-1 mapping between fog nodes and scheduler services
4. **Comprehensive Metrics**: Full simulation statistics and performance monitoring
5. **Fault Tolerance**: Circuit breaker patterns and retry logic
6. **Cache Management**: Intelligent task result caching
7. **Multi-Objective Optimization**: Multiple performance objectives considered
8. **Delayed Reward Learning**: RL agents learn from task completion feedback

## Event-Driven Architecture

The system employs iFogSim's discrete event simulation (DES) paradigm, which provides:

- **Chronological event processing** where events are scheduled and processed in temporal order
- **Discrete time advancement** where simulation time progresses only during event processing
- **Event-based communication** where entities interact through `SimEvent` objects
- **Controlled simulation execution** that continues until the event queue is exhausted or predefined time limits are reached

**Key Event Types**:

- `TUPLE_ARRIVAL` - Task/tuple arrival at device
- `SEND_PERIODIC_TUPLE` - Sensor data generation
- `GENERATE_EXTERNAL_TASK` - External task generation
- `ALLOC_REQUEST_SENT` - Allocation request sent
- `ALLOC_RESPONSE_RECEIVED` - Allocation response received
- `TASK_COMPLETE` - Task completion notification

## Validation and Testing

### Simulation Validation:

- **RL3FogSimulation.java**: Complete 3-fog-node simulation
- **Event Flow Verification**: All events processed correctly
- **gRPC Integration**: All services communicate properly
- **Metrics Collection**: Comprehensive statistics gathered

### Code Validation Points:

1. **RLCloudDevice.java**: Allocation decision logic
2. **RLFogDevice.java**: Two-queue system implementation
3. **go-grpc-server/service/gateway/gateway.go**: Allocation service implementation
4. **grpc-task-scheduler/internal/scheduler/service.go**: Streaming updates
5. **grpc-task-scheduler/internal/models/scheduler.go**: Cache management

## Results and Metrics

Each simulation run generates:

- **Overall system metrics** (energy, cost, latency, throughput)
- **Per-fog-node metrics** (individual performance)
- **RL learning curves** (if RL enabled)
- **Comparison data** for analysis

Results are stored in:

- `ifogsim/results/` - Simulation results
- `ifogsim/logs/` - Detailed logs
- Grafana dashboard - Real-time monitoring
- Prometheus metrics - Performance data

This system represents a comprehensive, production-ready RL-enhanced fog computing simulation framework that extends iFogSim2 with real-time intelligent decision making capabilities, demonstrating the practical application of reinforcement learning techniques in distributed computing environments.
