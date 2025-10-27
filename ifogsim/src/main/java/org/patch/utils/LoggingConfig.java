package org.patch.utils;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.Handler;
import org.patch.config.EnhancedConfigurationLoader;

/**
 * Centralized logging configuration for the patch module
 */
public class LoggingConfig {
    private static final Logger logger = Logger.getLogger(LoggingConfig.class.getName());

    // Configuration keys for YAML
    private static final String LOG_LEVEL_KEY = "logging.level.root";
    private static final String JSON_FORMAT_KEY = "logging.json.format";
    private static final String CORRELATION_ENABLED_KEY = "logging.correlation.enabled";
    private static final String PERFORMANCE_LOGGING_KEY = "logging.performance.enabled";

    // Default values
    private static final String DEFAULT_LOG_LEVEL = "INFO";
    private static final boolean DEFAULT_JSON_FORMAT = false;
    private static final boolean DEFAULT_CORRELATION_ENABLED = true;
    private static final boolean DEFAULT_PERFORMANCE_LOGGING = true;

    private static volatile LoggingConfig instance;
    private final Level logLevel;
    private final boolean jsonFormat;
    private final boolean correlationEnabled;
    private final boolean performanceLogging;
    private final LogFileManager logFileManager;

    /**
     * Private constructor for singleton
     */
    private LoggingConfig() {
        this.logLevel = parseLogLevel(EnhancedConfigurationLoader.getGrpcConfig(LOG_LEVEL_KEY, DEFAULT_LOG_LEVEL));
        this.jsonFormat = EnhancedConfigurationLoader.getGrpcConfigBoolean(JSON_FORMAT_KEY, DEFAULT_JSON_FORMAT);
        this.correlationEnabled = EnhancedConfigurationLoader.getGrpcConfigBoolean(CORRELATION_ENABLED_KEY,
                DEFAULT_CORRELATION_ENABLED);
        this.performanceLogging = EnhancedConfigurationLoader.getGrpcConfigBoolean(PERFORMANCE_LOGGING_KEY,
                DEFAULT_PERFORMANCE_LOGGING);
        this.logFileManager = LogFileManager.getInstance();

        logger.info("Logging configuration initialized: level=" + logLevel +
                ", json=" + jsonFormat + ", correlation=" + correlationEnabled +
                ", performance=" + performanceLogging);
    }

    /**
     * Get singleton instance
     */
    public static LoggingConfig getInstance() {
        if (instance == null) {
            synchronized (LoggingConfig.class) {
                if (instance == null) {
                    instance = new LoggingConfig();
                }
            }
        }
        return instance;
    }

    /**
     * Parse log level from string
     */
    private Level parseLogLevel(String levelStr) {
        try {
            return Level.parse(levelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid log level: " + levelStr + ", using INFO");
            return Level.INFO;
        }
    }

    /**
     * Create structured logger for a class
     */
    public StructuredLogger createLogger(Class<?> clazz) {
        return createLogger(clazz, null);
    }

    /**
     * Create structured logger for a class with correlation ID
     */
    public StructuredLogger createLogger(Class<?> clazz, String correlationId) {
        Logger classLogger = Logger.getLogger(clazz.getName());
        String actualCorrelationId = correlationEnabled ? correlationId : null;
        return new StructuredLogger(classLogger, actualCorrelationId, jsonFormat, logLevel);
    }

    /**
     * Create structured logger for a class with auto-generated correlation ID
     */
    public StructuredLogger createLoggerWithCorrelation(Class<?> clazz) {
        return createLogger(clazz, StructuredLogger.generateCorrelationId());
    }

    /**
     * Check if performance logging is enabled
     */
    public boolean isPerformanceLoggingEnabled() {
        return performanceLogging;
    }

    /**
     * Check if correlation IDs are enabled
     */
    public boolean isCorrelationEnabled() {
        return correlationEnabled;
    }

    /**
     * Check if JSON format is enabled
     */
    public boolean isJsonFormatEnabled() {
        return jsonFormat;
    }

    /**
     * Get current log level
     */
    public Level getLogLevel() {
        return logLevel;
    }

    /**
     * Start a new simulation run with fresh log files
     */
    public void startSimulationRun(String simulationName) {
        logFileManager.startSimulationRun(simulationName);
        setupLogHandlers();
    }

    /**
     * Setup log handlers for the current simulation run
     */
    private void setupLogHandlers() {
        // Setup handlers for different loggers
        setupLoggerHandlers("org.patch.client", logFileManager.createGrpcLogHandler());
        setupLoggerHandlers("org.patch.devices", logFileManager.createApplicationLogHandler());
        setupLoggerHandlers("org.patch.utils", logFileManager.createDebugLogHandler());
        setupLoggerHandlers("org.patch.core", logFileManager.createPerformanceLogHandler());
    }

    /**
     * Setup handlers for a specific logger
     */
    private void setupLoggerHandlers(String loggerName, Handler handler) {
        if (handler != null) {
            Logger logger = Logger.getLogger(loggerName);
            logger.addHandler(handler);
            logger.setUseParentHandlers(false); // Disable console output
        }
    }

    /**
     * Get current simulation ID
     */
    public String getCurrentSimulationId() {
        return logFileManager.getCurrentSimulationId();
    }

    /**
     * Get current log directory
     */
    public String getCurrentLogDir() {
        return logFileManager.getCurrentLogDir();
    }

    /**
     * List all log files for current simulation
     */
    public java.util.Map<String, String> listLogFiles() {
        return logFileManager.listLogFiles();
    }

    /**
     * Reload configuration (useful for runtime configuration changes)
     */
    public void reload() {
        synchronized (LoggingConfig.class) {
            instance = new LoggingConfig();
        }
    }
}
