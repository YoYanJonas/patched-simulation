package org.patch.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.HashMap;
import java.util.Map;
import org.patch.config.EnhancedConfigurationLoader;

/**
 * Manages log files with date/time-based directories and fresh log files for
 * each simulation run
 */
public class LogFileManager {
    private static final Logger logger = Logger.getLogger(LogFileManager.class.getName());
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH-mm-ss");

    // Configuration keys for YAML
    private static final String LOG_DIR_KEY = "logging.directory";
    private static final String LOG_FILE_SIZE_KEY = "logging.file.size";
    private static final String LOG_FILE_COUNT_KEY = "logging.file.count";
    private static final String LOG_CLEANUP_KEY = "logging.file.cleanup.enabled";

    // Default values
    private static final String DEFAULT_LOG_DIR = "logs";
    private static final int DEFAULT_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int DEFAULT_FILE_COUNT = 5;
    private static final boolean DEFAULT_CLEANUP = true;

    private static volatile LogFileManager instance;
    private final String baseLogDir;
    private final int maxFileSize;
    private final int maxFileCount;
    private final boolean cleanupEnabled;
    private String currentSimulationId;
    private String currentLogDir;

    /**
     * Private constructor for singleton
     */
    private LogFileManager() {
        // Initialize configuration
        // Note: EnhancedConfigurationLoader doesn't need explicit initialization

        this.baseLogDir = EnhancedConfigurationLoader.getGrpcConfig(LOG_DIR_KEY, DEFAULT_LOG_DIR);
        this.maxFileSize = EnhancedConfigurationLoader.getGrpcConfigInt(LOG_FILE_SIZE_KEY, DEFAULT_FILE_SIZE);
        this.maxFileCount = EnhancedConfigurationLoader.getGrpcConfigInt(LOG_FILE_COUNT_KEY, DEFAULT_FILE_COUNT);
        this.cleanupEnabled = EnhancedConfigurationLoader.getGrpcConfigBoolean(LOG_CLEANUP_KEY, DEFAULT_CLEANUP);

        // Create base log directory if it doesn't exist
        createDirectoryIfNotExists(baseLogDir);

        logger.info("LogFileManager initialized: baseDir=" + baseLogDir +
                ", maxFileSize=" + maxFileSize + ", maxFileCount=" + maxFileCount);
    }

    /**
     * Get singleton instance
     */
    public static LogFileManager getInstance() {
        if (instance == null) {
            synchronized (LogFileManager.class) {
                if (instance == null) {
                    instance = new LogFileManager();
                }
            }
        }
        return instance;
    }

    /**
     * Start a new simulation run with fresh log files
     */
    public void startSimulationRun(String simulationName) {
        LocalDateTime now = LocalDateTime.now();
        this.currentSimulationId = simulationName + "_" + now.format(DATE_TIME_FORMATTER);
        this.currentLogDir = baseLogDir + File.separator + currentSimulationId;

        // Create simulation-specific directory
        createDirectoryIfNotExists(currentLogDir);

        // Clean up old logs if enabled
        if (cleanupEnabled) {
            cleanupOldLogs();
        }

        logger.info("Started new simulation run: " + currentSimulationId +
                " in directory: " + currentLogDir);
    }

    /**
     * Get file handler for a specific logger with rotation
     */
    public FileHandler createFileHandler(String loggerName, Level level) {
        try {
            String fileName = getLogFileName(loggerName, level);
            FileHandler handler = new FileHandler(fileName, maxFileSize, maxFileCount, true);
            handler.setFormatter(new StructuredLogFormatter());
            handler.setLevel(level);
            return handler;
        } catch (IOException e) {
            logger.severe("Failed to create file handler for " + loggerName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Get file handler for general application logs
     */
    public FileHandler createApplicationLogHandler() {
        return createFileHandler("application", Level.INFO);
    }

    /**
     * Get file handler for gRPC client logs
     */
    public FileHandler createGrpcLogHandler() {
        return createFileHandler("grpc", Level.INFO);
    }

    /**
     * Get file handler for performance logs
     */
    public FileHandler createPerformanceLogHandler() {
        return createFileHandler("performance", Level.INFO);
    }

    /**
     * Get file handler for error logs
     */
    public FileHandler createErrorLogHandler() {
        return createFileHandler("errors", Level.SEVERE);
    }

    /**
     * Get file handler for debug logs
     */
    public FileHandler createDebugLogHandler() {
        return createFileHandler("debug", Level.FINE);
    }

    /**
     * Generate log file name with timestamp
     */
    private String getLogFileName(String loggerName, Level level) {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(TIME_FORMATTER);
        String levelName = level.getName().toLowerCase();
        return currentLogDir + File.separator + loggerName + "_" + levelName + "_" + timestamp + ".log";
    }

    /**
     * Create directory if it doesn't exist
     */
    private void createDirectoryIfNotExists(String dirPath) {
        try {
            Path path = Paths.get(dirPath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                logger.info("Created log directory: " + dirPath);
            }
        } catch (IOException e) {
            logger.severe("Failed to create directory " + dirPath + ": " + e.getMessage());
        }
    }

    /**
     * Clean up old log directories (keep only recent ones)
     */
    private void cleanupOldLogs() {
        try {
            File baseDir = new File(baseLogDir);
            if (!baseDir.exists()) {
                return;
            }

            // Get all subdirectories in the base log directory
            File[] dirs = baseDir.listFiles(File::isDirectory);
            if (dirs == null || dirs.length <= maxFileCount) {
                return; // No cleanup needed if within limit
            }

            // Sort directories by modification time (oldest first) for LRU cleanup
            // This ensures we remove the oldest log directories first
            java.util.Arrays.sort(dirs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

            // Calculate how many directories to remove to stay within limit
            int dirsToRemove = dirs.length - maxFileCount;
            for (int i = 0; i < dirsToRemove; i++) {
                // Recursively delete directory and all its contents
                deleteDirectory(dirs[i]);
                logger.info("Cleaned up old log directory: " + dirs[i].getName());
            }
        } catch (Exception e) {
            logger.warning("Failed to cleanup old logs: " + e.getMessage());
        }
    }

    /**
     * Recursively delete directory
     */
    private void deleteDirectory(File dir) {
        // Recursively delete directory contents first, then the directory itself
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                // Recursively delete all child files and subdirectories
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        // Delete the directory/file itself after contents are removed
        dir.delete();
    }

    /**
     * Get current simulation ID
     */
    public String getCurrentSimulationId() {
        return currentSimulationId;
    }

    /**
     * Get current log directory
     */
    public String getCurrentLogDir() {
        return currentLogDir;
    }

    /**
     * Get log file path for a specific logger
     */
    public String getLogFilePath(String loggerName, Level level) {
        return getLogFileName(loggerName, level);
    }

    /**
     * List all log files in current simulation
     */
    public Map<String, String> listLogFiles() {
        Map<String, String> logFiles = new HashMap<>();
        if (currentLogDir == null) {
            return logFiles;
        }

        try {
            File dir = new File(currentLogDir);
            File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
            if (files != null) {
                for (File file : files) {
                    logFiles.put(file.getName(), file.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to list log files: " + e.getMessage());
        }

        return logFiles;
    }

    /**
     * Custom formatter for structured logs
     */
    private static class StructuredLogFormatter extends Formatter {
        private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        @Override
        public String format(LogRecord record) {
            StringBuilder sb = new StringBuilder();
            sb.append(LocalDateTime.now().format(formatter));
            sb.append(" [").append(record.getLevel()).append("]");
            sb.append(" [").append(record.getLoggerName()).append("]");
            sb.append(" ").append(record.getMessage());

            if (record.getThrown() != null) {
                sb.append("\n").append(record.getThrown().toString());
            }

            sb.append("\n");
            return sb.toString();
        }
    }
}
