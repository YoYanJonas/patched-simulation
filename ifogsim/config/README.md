# iFogSim gRPC Integration Configuration

This directory contains configuration files for the iFogSim gRPC integration system.

## 📁 Configuration Files

### 1. `application.yml` (Main Configuration)

- **Purpose**: Primary configuration file following Spring Boot best practices
- **Format**: YAML with environment-specific profiles
- **Features**:
  - Environment variable substitution
  - Profile-specific overrides (development, production)
  - Hierarchical configuration structure
  - Fog node to scheduler mapping

### 2. `docker-config.yaml` (Docker Configuration)

- **Purpose**: Docker-specific configuration settings
- **Format**: YAML
- **Usage**: Used by Docker Compose for container orchestration
- **Configuration**: Optimized for 3-4 fog nodes and 1 cloud device

## 🏗️ Architecture Overview

### Fog Node to Scheduler Mapping

```
Fog Nodes 1-4   → Scheduler 1 (localhost:50051)
Fog Nodes 5-8   → Scheduler 2 (localhost:50052)
Fog Nodes 9-12  → Scheduler 3 (localhost:50053)
```

### Cloud Node to Allocation Service

```
Cloud Node → Allocation Service (localhost:50052)
```

## 🔧 Configuration Best Practices

### 1. Environment Variables

Use environment variables for deployment-specific settings:

```bash
# Development
export SCHEDULER_1_HOST=localhost
export SCHEDULER_1_PORT=50051
export ALLOCATION_HOST=localhost
export ALLOCATION_PORT=50052

# Production
export SCHEDULER_1_HOST=scheduler-1.prod.com
export SCHEDULER_1_PORT=50051
export ALLOCATION_HOST=allocation.prod.com
export ALLOCATION_PORT=50052
```

### 2. Profile-Specific Configuration

The `application.yml` supports different profiles:

```yaml
# Development profile
spring:
  profiles: development

# Production profile
spring:
  profiles: production
```

### 3. Configuration Hierarchy

1. **Environment Variables** (highest priority)
2. **application.yml** (main configuration)
3. **Default values** (lowest priority)

## 🚀 Usage Examples

### Basic Usage

```java
// Get scheduler configuration for fog node 5
SchedulerInstance instance = EnhancedConfigurationLoader.getSchedulerForFogNode(5);
String host = instance.getHost(); // "localhost"
int port = instance.getPort();    // 50052
```

### Configuration Access

```java
// Get gRPC configuration
String timeout = EnhancedConfigurationLoader.getGrpcConfig("grpc.global.connection.timeout", "5000");
int maxRetries = EnhancedConfigurationLoader.getGrpcConfigInt("grpc.global.retry.max-attempts", 3);

// Get scheduler configuration
boolean healthCheckEnabled = EnhancedConfigurationLoader.getSchedulerConfigBoolean("scheduler.settings.health-check.enabled", true);

// Get allocation configuration
String allocationHost = EnhancedConfigurationLoader.getAllocationConfig("allocation.service.host", "localhost");
```

## 🔄 Configuration Reloading

The configuration system supports runtime reloading:

```java
// Reload all configuration
EnhancedConfigurationLoader.reload();

// Get updated configuration
SchedulerInstance instance = EnhancedConfigurationLoader.getSchedulerForFogNode(5);
```

## 🛠️ Development Setup

### 1. Local Development

```bash
# Set environment variables
export APP_ENV=development
export SCHEDULER_1_HOST=localhost
export SCHEDULER_1_PORT=50051
export SCHEDULER_2_HOST=localhost
export SCHEDULER_2_PORT=50052
export SCHEDULER_3_HOST=localhost
export SCHEDULER_3_PORT=50053
export ALLOCATION_HOST=localhost
export ALLOCATION_PORT=50052
```

### 2. Docker Development

```bash
# Use docker-compose with environment variables
docker-compose up -d
```

### 3. Production Deployment

```bash
# Set production environment variables
export APP_ENV=production
export SCHEDULER_1_HOST=scheduler-1.prod.com
export SCHEDULER_1_PORT=50051
export SCHEDULER_2_HOST=scheduler-2.prod.com
export SCHEDULER_2_PORT=50052
export SCHEDULER_3_HOST=scheduler-3.prod.com
export SCHEDULER_3_PORT=50053
export ALLOCATION_HOST=allocation.prod.com
export ALLOCATION_PORT=50052
```

## 📊 Configuration Validation

The system includes built-in validation:

- **Fog Node ID Range**: 1-12 (configurable)
- **Port Range**: 1-65535
- **Host Format**: Valid hostname or IP address
- **Timeout Values**: Positive integers
- **Retry Counts**: Non-negative integers

## 🔍 Troubleshooting

### Common Issues

1. **Configuration Not Found**

   ```
   Solution: Ensure configuration files are in the classpath
   ```

2. **Environment Variables Not Loaded**

   ```
   Solution: Check environment variable names and values
   ```

3. **Fog Node Mapping Errors**
   ```
   Solution: Verify fog node ID is within range (1-12)
   ```

### Debug Configuration

```java
// Enable debug logging
System.setProperty("java.util.logging.level.org.patch.config", "DEBUG");

// Check configuration loading
EnhancedConfigurationLoader.initialize();
Map<Integer, String> mapping = EnhancedConfigurationLoader.getFogNodeMapping();
System.out.println("Fog node mapping: " + mapping);
```

## 📈 Performance Considerations

- **Configuration Caching**: Values are cached after first load
- **Lazy Loading**: Configuration is loaded only when needed
- **Memory Usage**: Minimal memory footprint for configuration storage
- **Reload Performance**: Fast configuration reloading without restart

## 🔒 Security Considerations

- **Sensitive Data**: Use environment variables for secrets
- **File Permissions**: Restrict access to configuration files
- **Network Security**: Use secure connections for production
- **Validation**: Always validate configuration values

## 📝 Migration Guide

### From Old Configuration System

1. **Update imports**: Use `EnhancedConfigurationLoader` instead of `ConfigurationLoader`
2. **Update method calls**: Use new method signatures
3. **Test configuration**: Verify all settings work correctly
4. **Update deployment**: Use new environment variables

### Backward Compatibility

The system maintains backward compatibility with existing properties files while providing enhanced functionality through the new YAML configuration.
