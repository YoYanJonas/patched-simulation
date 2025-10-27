# iFogSim2 with gRPC Integration

[![Java](https://img.shields.io/badge/Java-8+-blue.svg)](https://www.oracle.com/java/)
[![gRPC](https://img.shields.io/badge/gRPC-1.50+-green.svg)](https://grpc.io/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Overview

This project extends **iFogSim2** with comprehensive gRPC integration capabilities, enabling real-time communication with external reinforcement learning services for intelligent fog computing resource management.

**Author**: Younes Shafiee  
**Repository**: https://github.com/YoYanJonas/ifogsim.git

### What is iFogSim2?

**iFogSim2** is a powerful toolkit for modeling and simulation of resource management techniques in Internet of Things, Edge and Fog Computing environments. It provides:

- **Mobility-support and Migration Management**
  - Supporting real mobility datasets
  - Implementing different random mobility models
- **Microservice Orchestration**
- **Dynamic Distributed Clustering**
- **Any Combinations of Above-mentioned Features**
- **Full Compatibility** with the Latest Version of CloudSim (i.e., [CloudSim 5](https://github.com/Cloudslab/cloudsim/releases)) and [Previous iFogSim Version](https://github.com/Cloudslab/iFogSim1)

### gRPC Integration Features

This enhanced version adds:

- **Real-time gRPC Communication** with external RL services
- **Intelligent Task Scheduling** using reinforcement learning
- **Dynamic Load Balancing** with adaptive algorithms
- **Comprehensive Logging** with structured correlation tracking
- **Robust Error Handling** with circuit breaker patterns
- **Performance Monitoring** with detailed metrics collection

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   iFogSim2      │    │  gRPC Services   │    │  RL Services    │
│   Simulation    │◄──►│                  │◄──►│                 │
│                 │    │  • Task Scheduler │    │  • Q-Learning   │
│  • Fog Nodes    │    │  • Load Balancer │    │  • SARSA       │
│  • Cloud Nodes  │    │  • Monitoring    │    │  • Experience  │
│  • Applications │    │                  │    │    Management  │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

## Key Components

### gRPC Clients

- **`GrpcClient`** - Base gRPC client with retry logic and circuit breaker
- **`SchedulerClient`** - Task scheduling with RL-based decisions
- **`AllocationClient`** - Load balancing with adaptive algorithms

### Enhanced Devices

- **`RLFogDevice`** - Fog devices with RL-based task scheduling
- **`RLCloudDevice`** - Cloud devices with intelligent load balancing

### Utilities

- **`StructuredLogger`** - Advanced logging with correlation tracking
- **`LogFileManager`** - Organized log file management
- **`ConfigurationLoader`** - YAML-based configuration management

## Quick Start

### Prerequisites

- Java 8 or higher
- Maven 3.6+
- Protocol Buffers 3.22.3
- Go 1.19+ (for external services)

### Installation

1. **Clone the repository:**

   ```bash
   git clone https://github.com/YoYanJonas/ifogsim.git
   cd ifogsim
   ```

2. **Build the project:**

   ```bash
   mvn clean compile
   ```

3. **Generate Protocol Buffers:**

   ```bash
   ./scripts/generate_proto.sh
   ```

4. **Configure services:**
   ```bash
   # Edit config/application.yml for your setup
   cp config/application.yml.example config/application.yml
   ```

### Running a Simulation

```java
import org.patch.utils.SimulationLogger;
import org.patch.devices.RLFogDevice;
import org.patch.client.SchedulerClient;

public class MySimulation {
    public static void main(String[] args) {
        // Start simulation with logging
        SimulationLogger.startSimulation("MyFogSimulation");

        // Create RL-enabled fog device
        RLFogDevice fogDevice = new RLFogDevice("fog-1", 1000, 4, 1000, 100, 100, 100, 100);

        // Create scheduler client
        SchedulerClient scheduler = new SchedulerClient("localhost", 50051);

        // Run simulation...

        // End simulation
        SimulationLogger.endSimulation();
    }
}
```

## Configuration

### YAML Configuration

The system uses `config/application.yml` for comprehensive configuration:

```yaml
# gRPC Configuration
grpc:
  global:
    connection:
      timeout: 5000
    retry:
      max-attempts: 3
    circuit-breaker:
      failure-threshold: 5

# Scheduler Services
schedulers:
  instances:
    scheduler-1:
      host: localhost
      port: 50051
      max-fog-nodes: 4

# Logging Configuration
logging:
  level:
    root: INFO
    org.patch: DEBUG
  directory: logs
  file:
    size: 10485760 # 10MB
    count: 5
```

### Environment Variables

Override configuration with environment variables:

```bash
export SCHEDULER_1_HOST="localhost"
export SCHEDULER_1_PORT="50051"
export LOGGING_DIRECTORY="/path/to/logs"
export LOGGING_LEVEL="DEBUG"
```

## Logging System

### Organized Log Files

Each simulation run creates timestamped directories:

```
logs/
├── MySimulation_2024-01-15_14-30-25/
│   ├── application_info_14-30-25.log
│   ├── grpc_info_14-30-25.log
│   ├── performance_info_14-30-25.log
│   └── errors_severe_14-30-25.log
└── MySimulation_2024-01-15_14-35-10/
    └── ...
```

### Usage

```java
// Start simulation with fresh logs
SimulationLogger.startSimulation("MyFogSimulation");

// Log milestones
SimulationLogger.logMilestone("INITIALIZATION", "Starting fog nodes");

// Log performance metrics
Map<String, Object> metrics = new HashMap<>();
metrics.put("fog_nodes", 4);
metrics.put("tasks_processed", 100);
SimulationLogger.logPerformance("SIMULATION_COMPLETE", 5000, metrics);

// End simulation
SimulationLogger.endSimulation();
```

## Examples

### Basic Fog Computing Simulation

```java
public class BasicFogSimulation {
    public static void main(String[] args) {
        SimulationLogger.startSimulation("BasicFogSimulation");

        try {
            // Create fog devices with RL capabilities
            RLFogDevice fog1 = new RLFogDevice("fog-1", 1000, 4, 1000, 100, 100, 100, 100);
            RLFogDevice fog2 = new RLFogDevice("fog-2", 1000, 4, 1000, 100, 100, 100, 100);

            // Create cloud device with load balancing
            RLCloudDevice cloud = new RLCloudDevice("cloud-1", 10000, 8, 10000, 1000, 1000, 1000, 1000);

            // Create scheduler clients
            SchedulerClient scheduler1 = new SchedulerClient("localhost", 50051);
            SchedulerClient scheduler2 = new SchedulerClient("localhost", 50052);

            // Run simulation...

        } catch (Exception e) {
            SimulationLogger.logError("Simulation failed", e);
        } finally {
            SimulationLogger.endSimulation();
        }
    }
}
```

### Advanced RL Integration

```java
public class RLFogSimulation {
    public static void main(String[] args) {
        SimulationLogger.startSimulation("RLFogSimulation");

        // Configure RL parameters
        Map<String, Double> rlParams = new HashMap<>();
        rlParams.put("learning_rate", 0.1);
        rlParams.put("exploration_rate", 0.1);
        rlParams.put("discount_factor", 0.9);

        // Create allocation client for RL configuration
        AllocationClient allocation = new AllocationClient("localhost", 50052);
        allocation.setRLParameters(rlParams);

        // Run simulation with RL...

        SimulationLogger.endSimulation();
    }
}
```

## API Documentation

### Core Classes

#### GrpcClient

Base gRPC client with comprehensive retry logic and circuit breaker pattern.

#### SchedulerClient

Specialized client for task scheduling with RL-based decision making.

#### AllocationClient

Specialized client for load balancing with adaptive algorithms.

#### RLFogDevice

Enhanced fog device with RL-based task scheduling capabilities.

#### RLCloudDevice

Enhanced cloud device with intelligent load balancing.

### Utility Classes

#### SimulationLogger

Easy-to-use logging utility for simulation runs.

#### StructuredLogger

Advanced logging with correlation tracking and performance metrics.

#### LogFileManager

Organized log file management with date/time directories.

## Performance Monitoring

### Metrics Collection

The system automatically collects:

- **Connection Performance** - gRPC call timing and success rates
- **Task Scheduling** - Decision time and accuracy
- **Load Balancing** - Resource utilization and efficiency
- **Error Rates** - Failure patterns and recovery times

### Dashboard Integration

Access real-time metrics through the monitoring dashboard:

```java
SchedulerClient scheduler = new SchedulerClient("localhost", 50051);
DashboardData dashboard = scheduler.getDashboard();
System.out.println("CPU Usage: " + dashboard.getCpuUsage());
System.out.println("Memory Usage: " + dashboard.getMemoryUsage());
```

## Troubleshooting

### Common Issues

1. **gRPC Connection Failed**

   - Check if external services are running
   - Verify host and port configuration
   - Check network connectivity

2. **Log Files Not Created**

   - Verify `logs/` directory exists and is writable
   - Check logging configuration in `application.yml`
   - Ensure simulation calls `SimulationLogger.startSimulation()`

3. **Performance Issues**
   - Reduce log level to WARNING or ERROR
   - Disable performance logging if not needed
   - Increase log file size to reduce rotation

### Debug Mode

Enable debug logging:

```yaml
logging:
  level:
    org.patch: DEBUG
    org.patch.client: DEBUG
```

## Contributing

We welcome contributions! Please see our [Contributing Guidelines](CONTRIBUTING.md) for details.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

### iFogSim2 Credits

This project is built upon the excellent work of the iFogSim2 team:

- **Redowan Mahmud, Samodha Pallewatta, Mohammad Goudarzi, and Rajkumar Buyya**, [iFogSim2: An Extended iFogSim Simulator for Mobility, Clustering, and Microservice Management in Edge and Fog Computing Environments](https://arxiv.org/abs/2109.05636), Journal of Systems and Software (JSS), Volume 190, Pages: 1-17, ISSN:0164-1212, Elsevier Press, Amsterdam, The Netherlands, August 2022.

- **Harshit Gupta, Amir Vahid Dastjerdi, Soumya K. Ghosh, and Rajkumar Buyya**, [iFogSim: A Toolkit for Modeling and Simulation of Resource Management Techniques in Internet of Things, Edge and Fog Computing Environments](http://www.buyya.com/papers/iFogSim.pdf), Software: Practice and Experience (SPE), Volume 47, Issue 9, Pages: 1275-1296, ISSN: 0038-0644, Wiley Press, New York, USA, September 2017.

- **Redowan Mahmud and Rajkumar Buyya**, [Modelling and Simulation of Fog and Edge Computing Environments using iFogSim Toolkit](http://www.buyya.com/papers/iFogSim-Tut.pdf), Fog and Edge Computing: Principles and Paradigms, R. Buyya and S. Srirama (eds), 433-466pp, ISBN: 978-111-95-2498-4, Wiley Press, New York, USA, January 2019.

### gRPC Integration Credits

The gRPC integration features were developed by **Younes Shafiee**, extending the original iFogSim2 capabilities with modern distributed computing technologies.

## References

- [iFogSim2 Original Repository](https://github.com/Cloudslab/iFogSim)
- [CloudSim 5](https://github.com/Cloudslab/cloudsim/releases)
- [gRPC Documentation](https://grpc.io/docs/)
- [Protocol Buffers](https://developers.google.com/protocol-buffers)

---

**Note**: This project extends iFogSim2 with gRPC integration capabilities. The original iFogSim2 functionality remains unchanged and fully compatible.
