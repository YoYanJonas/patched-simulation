# iFogSim Logging System

## Overview

The iFogSim logging system provides comprehensive log file management with date/time-based directories and fresh log files for each simulation run. This allows you to easily track and analyze simulation behavior across multiple runs.

## Features

- **Date/Time-based Directories**: Each simulation run gets its own directory with timestamp
- **Fresh Log Files**: New log files for each simulation run (no overwriting)
- **Organized Logging**: Separate log files for different components (gRPC, performance, errors, etc.)
- **Automatic Cleanup**: Old log directories are automatically cleaned up
- **Correlation Tracking**: Request correlation IDs across all log files
- **Performance Metrics**: Detailed performance logging with timing information

## Directory Structure

```
logs/
├── FogComputing_Test_1_2024-01-15_14-30-25/
│   ├── application_info_14-30-25.log
│   ├── grpc_info_14-30-25.log
│   ├── performance_info_14-30-25.log
│   ├── errors_severe_14-30-25.log
│   └── debug_fine_14-30-25.log
├── FogComputing_Test_2_2024-01-15_14-35-10/
│   ├── application_info_14-35-10.log
│   ├── grpc_info_14-35-10.log
│   └── ...
└── ...
```

## Usage

### 1. Start a Simulation Run

```java
import org.patch.utils.SimulationLogger;

// Start a new simulation with fresh log files
SimulationLogger.startSimulation("MyFogSimulation");
```

### 2. Log Simulation Milestones

```java
// Log important simulation events
SimulationLogger.logMilestone("INITIALIZATION", "Starting fog nodes");
SimulationLogger.logMilestone("TASK_SCHEDULING", "Scheduling 100 tasks");
SimulationLogger.logMilestone("COMPLETION", "Simulation completed successfully");
```

### 3. Log Performance Metrics

```java
import java.util.HashMap;
import java.util.Map;

Map<String, Object> metrics = new HashMap<>();
metrics.put("fog_nodes", 4);
metrics.put("tasks_processed", 100);
metrics.put("cache_hits", 25);
metrics.put("success_rate", 0.95);

SimulationLogger.logPerformance("SIMULATION_COMPLETE", 5000, metrics);
```

### 4. End Simulation

```java
// End the simulation and list generated log files
SimulationLogger.endSimulation();
```

## Configuration

### YAML Configuration

The logging system is configured through the main `config/application.yml` file:

```yaml
# Logging Configuration
logging:
  # Log levels
  level:
    root: INFO
    org.patch: DEBUG
    org.patch.client: DEBUG
    org.patch.config: DEBUG
    org.patch.devices: INFO
    org.patch.utils: INFO
    org.patch.core: INFO

  # File management
  directory: ${LOGGING_DIRECTORY:logs}
  json:
    format: ${LOGGING_JSON_FORMAT:false}
  correlation:
    enabled: ${LOGGING_CORRELATION_ENABLED:true}
  performance:
    enabled: ${LOGGING_PERFORMANCE_ENABLED:true}
    threshold: 1000

  # File rotation settings
  file:
    size: ${LOGGING_FILE_SIZE:10485760} # 10MB
    count: ${LOGGING_FILE_COUNT:5}
    cleanup:
      enabled: ${LOGGING_CLEANUP_ENABLED:true}

  # Component-specific logging
  components:
    application:
      level: INFO
      enabled: true
    grpc:
      level: INFO
      enabled: true
      requests: true
      responses: true
      errors: true
      performance: true
    performance:
      level: INFO
      enabled: true
      metrics: true
    errors:
      level: SEVERE
      enabled: true
      detailed: true
      stack-trace: true
    debug:
      level: FINE
      enabled: false

  # Simulation logging
  simulation:
    milestones: true
    performance: true
    errors: true
    correlation: true
```

### Environment Variables

You can override YAML values using environment variables:

```bash
# Log directory (default: logs)
export LOGGING_DIRECTORY="/path/to/logs"

# Log level (default: INFO)
export LOGGING_LEVEL="INFO"

# JSON format (default: false)
export LOGGING_JSON_FORMAT="false"

# Performance logging (default: true)
export LOGGING_PERFORMANCE_ENABLED="true"

# File size limit (default: 10MB)
export LOGGING_FILE_SIZE="10485760"

# Number of log files to keep (default: 5)
export LOGGING_FILE_COUNT="5"
```

## Log File Types

### 1. Application Logs (`application_*.log`)

- General simulation events
- Fog node operations
- Task processing
- System state changes

### 2. gRPC Logs (`grpc_*.log`)

- gRPC client connections
- Request/response logging
- Circuit breaker state changes
- Service health checks

### 3. Performance Logs (`performance_*.log`)

- Operation timing
- Throughput metrics
- Resource utilization
- Cache hit/miss ratios

### 4. Error Logs (`errors_*.log`)

- Exception details
- Error stack traces
- Service failures
- Recovery attempts

### 5. Debug Logs (`debug_*.log`)

- Detailed debugging information
- Internal state changes
- Verbose operation logs

## Example Simulation

```java
public class MySimulation {
    public static void main(String[] args) {
        try {
            // Start simulation
            SimulationLogger.startSimulation("FogComputing_Test");

            // Initialize fog nodes
            SimulationLogger.logMilestone("INITIALIZATION", "Setting up 4 fog nodes");
            setupFogNodes();

            // Process tasks
            SimulationLogger.logMilestone("TASK_PROCESSING", "Processing 100 tasks");
            processTasks();

            // Log performance
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("tasks_completed", 100);
            metrics.put("average_latency", 150.5);
            metrics.put("success_rate", 0.98);

            SimulationLogger.logPerformance("SIMULATION_COMPLETE", 5000, metrics);

            // End simulation
            SimulationLogger.endSimulation();

        } catch (Exception e) {
            SimulationLogger.logError("Simulation failed", e);
        }
    }
}
```

## Reading Log Files

### 1. View All Log Files for a Simulation

```bash
# List all log files for current simulation
ls -la logs/FogComputing_Test_2024-01-15_14-30-25/
```

### 2. Monitor Real-time Logs

```bash
# Monitor application logs in real-time
tail -f logs/FogComputing_Test_2024-01-15_14-30-25/application_info_14-30-25.log

# Monitor gRPC logs in real-time
tail -f logs/FogComputing_Test_2024-01-15_14-30-25/grpc_info_14-30-25.log
```

### 3. Search for Specific Events

```bash
# Search for errors
grep "ERROR" logs/FogComputing_Test_2024-01-15_14-30-25/*.log

# Search for performance metrics
grep "PERFORMANCE" logs/FogComputing_Test_2024-01-15_14-30-25/performance_*.log

# Search for gRPC requests
grep "gRPC request" logs/FogComputing_Test_2024-01-15_14-30-25/grpc_*.log
```

### 4. Analyze Correlation IDs

```bash
# Track a specific request across all log files
grep "correlation_id: abc12345" logs/FogComputing_Test_2024-01-15_14-30-25/*.log
```

## Benefits

1. **Easy Analysis**: Each simulation run has its own directory with organized log files
2. **No Overwriting**: Fresh log files for each run prevent data loss
3. **Correlation Tracking**: Follow requests across different log files using correlation IDs
4. **Performance Monitoring**: Detailed performance metrics for optimization
5. **Error Debugging**: Comprehensive error logging with stack traces
6. **Automatic Cleanup**: Old log directories are automatically cleaned up

## Troubleshooting

### Log Files Not Created

- Check if `logs/` directory exists and is writable
- Verify logging configuration in `config/logging.properties`
- Check environment variables

### Missing Log Entries

- Verify log level configuration
- Check if specific loggers are enabled
- Ensure simulation is calling `SimulationLogger.startSimulation()`

### Performance Issues

- Reduce log level to WARNING or ERROR
- Disable performance logging if not needed
- Increase log file size to reduce rotation frequency
