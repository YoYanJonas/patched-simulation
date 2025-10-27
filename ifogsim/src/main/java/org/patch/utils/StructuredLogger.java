package org.patch.utils;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enhanced structured logging utility with correlation IDs and JSON formatting
 */
public class StructuredLogger {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private final Logger logger;
    private final String correlationId;
    private final boolean jsonFormat;
    private final Level logLevel;

    /**
     * Constructor for structured logger
     */
    public StructuredLogger(Logger logger, String correlationId, boolean jsonFormat, Level logLevel) {
        this.logger = logger;
        this.correlationId = correlationId != null ? correlationId : generateCorrelationId();
        this.jsonFormat = jsonFormat;
        this.logLevel = logLevel;
    }

    /**
     * Generate a new correlation ID
     */
    public static String generateCorrelationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Log an info message with structured data
     */
    public void info(String message, Map<String, Object> fields) {
        if (shouldLog(Level.INFO)) {
            log(Level.INFO, message, fields, null);
        }
    }

    /**
     * Log an info message
     */
    public void info(String message) {
        if (shouldLog(Level.INFO)) {
            log(Level.INFO, message, null, null);
        }
    }

    /**
     * Log a warning message with structured data
     */
    public void warning(String message, Map<String, Object> fields) {
        if (shouldLog(Level.WARNING)) {
            log(Level.WARNING, message, fields, null);
        }
    }

    /**
     * Log a warning message
     */
    public void warning(String message) {
        if (shouldLog(Level.WARNING)) {
            log(Level.WARNING, message, null, null);
        }
    }

    /**
     * Log an error message with structured data
     */
    public void error(String message, Map<String, Object> fields, Throwable throwable) {
        if (shouldLog(Level.SEVERE)) {
            log(Level.SEVERE, message, fields, throwable);
        }
    }

    /**
     * Log an error message
     */
    public void error(String message, Throwable throwable) {
        if (shouldLog(Level.SEVERE)) {
            log(Level.SEVERE, message, null, throwable);
        }
    }

    /**
     * Log performance metrics
     */
    public void performance(String operation, long durationMs, Map<String, Object> metrics) {
        if (shouldLog(Level.INFO)) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("operation", operation);
            fields.put("duration_ms", durationMs);
            fields.put("timestamp", System.currentTimeMillis());
            if (metrics != null) {
                fields.putAll(metrics);
            }
            log(Level.INFO, "Performance metrics", fields, null);
        }
    }

    /**
     * Log gRPC request start
     */
    public void grpcRequestStart(String service, String method, Map<String, Object> requestData) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("service", service);
        fields.put("method", method);
        fields.put("request_data", requestData);
        fields.put("event_type", "grpc_request_start");
        log(Level.INFO, "gRPC request started", fields, null);
    }

    /**
     * Log gRPC request completion
     */
    public void grpcRequestComplete(String service, String method, long durationMs, boolean success) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("service", service);
        fields.put("method", method);
        fields.put("duration_ms", durationMs);
        fields.put("success", success);
        fields.put("event_type", "grpc_request_complete");
        log(Level.INFO, "gRPC request completed", fields, null);
    }

    /**
     * Log circuit breaker state change
     */
    public void circuitBreakerStateChange(String fromState, String toState, String reason) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("from_state", fromState);
        fields.put("to_state", toState);
        fields.put("reason", reason);
        fields.put("event_type", "circuit_breaker_state_change");
        log(Level.WARNING, "Circuit breaker state changed", fields, null);
    }

    /**
     * Check if we should log at this level
     */
    private boolean shouldLog(Level messageLevel) {
        return messageLevel.intValue() >= logLevel.intValue();
    }

    /**
     * Core logging method
     */
    private void log(Level level, String message, Map<String, Object> fields, Throwable throwable) {
        if (jsonFormat) {
            logJson(level, message, fields, throwable);
        } else {
            logText(level, message, fields, throwable);
        }
    }

    /**
     * Log in JSON format
     */
    private void logJson(Level level, String message, Map<String, Object> fields, Throwable throwable) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("timestamp", ISO_FORMATTER.format(Instant.now()));
        logEntry.put("level", level.getName());
        logEntry.put("correlation_id", correlationId);
        logEntry.put("message", message);

        if (fields != null) {
            logEntry.putAll(fields);
        }

        if (throwable != null) {
            logEntry.put("exception", throwable.getClass().getSimpleName());
            logEntry.put("exception_message", throwable.getMessage());
        }

        logger.log(level, formatJson(logEntry));
    }

    /**
     * Log in text format
     */
    private void logText(Level level, String message, Map<String, Object> fields, Throwable throwable) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("[").append(correlationId).append("] ");
        logMessage.append(message);

        if (fields != null && !fields.isEmpty()) {
            logMessage.append(" | ");
            fields.forEach((key, value) -> logMessage.append(key).append("=").append(value).append(" "));
        }

        if (throwable != null) {
            logger.log(level, logMessage.toString(), throwable);
        } else {
            logger.log(level, logMessage.toString());
        }
    }

    /**
     * Format map as JSON string
     */
    private String formatJson(Map<String, Object> data) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) {
                json.append(",");
            }
            json.append("\"").append(entry.getKey()).append("\":");

            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(value).append("\"");
            } else {
                json.append(value);
            }
            first = false;
        }

        json.append("}");
        return json.toString();
    }

    /**
     * Get correlation ID
     */
    public String getCorrelationId() {
        return correlationId;
    }

    /**
     * Create a child logger with same correlation ID
     */
    public StructuredLogger child(Logger childLogger) {
        return new StructuredLogger(childLogger, correlationId, jsonFormat, logLevel);
    }
}
