package logger

import (
	"os"

	"scheduler-grpc-server/pkg/config"

	"github.com/sirupsen/logrus"
)

var Log *logrus.Logger

// Initialize sets up the logger based on configuration
func Initialize(cfg *config.LoggingConfig) {
	Log = logrus.New()

	// Set log level
	level, err := logrus.ParseLevel(cfg.Level)
	if err != nil {
		Log.Warnf("Invalid log level '%s', using 'info'", cfg.Level)
		level = logrus.InfoLevel
	}
	Log.SetLevel(level)

	// Set log format
	// Check if output is a terminal (TTY) - disable colors if writing to file
	// First check if REPORT_PATH is set (always writing to file in this case)
	reportPath := os.Getenv("REPORT_PATH")
	isTerminal := false
	
	if reportPath == "" {
		// Not writing to report directory - check if output is terminal
		if cfg.Output == "stdout" {
			fileInfo, err := os.Stdout.Stat()
			if err == nil {
				isTerminal = (fileInfo.Mode() & os.ModeCharDevice) != 0
			}
		} else if cfg.Output == "stderr" {
			fileInfo, err := os.Stderr.Stat()
			if err == nil {
				isTerminal = (fileInfo.Mode() & os.ModeCharDevice) != 0
			}
		}
		// If cfg.Output is a file path, isTerminal stays false
	}
	
	switch cfg.Format {
	case "json":
		Log.SetFormatter(&logrus.JSONFormatter{
			TimestampFormat: "2006-01-02T15:04:05.000Z07:00",
		})
	case "text":
		Log.SetFormatter(&logrus.TextFormatter{
			FullTimestamp:   true,
			TimestampFormat: "2006-01-02 15:04:05",
			ForceColors:     false, // Never force colors
			DisableColors:   !isTerminal, // Disable colors if not a terminal
		})
	default:
		Log.Warnf("Invalid log format '%s', using 'json'", cfg.Format)
		Log.SetFormatter(&logrus.JSONFormatter{})
	}

	// Set output
	// Check if REPORT_PATH env var is set - if so, write logs to report directory
	// (reportPath already declared above)
	var logFilePath string
	
	if reportPath != "" {
		// Write logs to report directory
		logFilePath = reportPath + "/logs/server.log"
		// Ensure directory exists
		if err := os.MkdirAll(reportPath+"/logs", 0755); err != nil {
			Log.Warnf("Failed to create log directory '%s', using default output: %v", reportPath+"/logs", err)
			logFilePath = ""
		}
	}
	
	switch cfg.Output {
	case "stdout":
		if logFilePath != "" {
			// Prefer report directory over stdout
			file, err := os.OpenFile(logFilePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
			if err != nil {
				Log.Warnf("Failed to open log file '%s', using stdout: %v", logFilePath, err)
				Log.SetOutput(os.Stdout)
			} else {
				Log.SetOutput(file)
				Log.Infof("Logging to report directory: %s", logFilePath)
			}
		} else {
			Log.SetOutput(os.Stdout)
		}
	case "stderr":
		if logFilePath != "" {
			// Prefer report directory over stderr
			file, err := os.OpenFile(logFilePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
			if err != nil {
				Log.Warnf("Failed to open log file '%s', using stderr: %v", logFilePath, err)
				Log.SetOutput(os.Stderr)
			} else {
				Log.SetOutput(file)
				Log.Infof("Logging to report directory: %s", logFilePath)
			}
		} else {
			Log.SetOutput(os.Stderr)
		}
	default:
		// If REPORT_PATH is set, override with report directory
		if logFilePath != "" {
			file, err := os.OpenFile(logFilePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
			if err != nil {
				Log.Warnf("Failed to open log file '%s', trying config path '%s': %v", logFilePath, cfg.Output, err)
				// Fallback to config path
				file, err = os.OpenFile(cfg.Output, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
				if err != nil {
					Log.Warnf("Failed to open log file '%s', using stdout: %v", cfg.Output, err)
					Log.SetOutput(os.Stdout)
				} else {
					Log.SetOutput(file)
				}
			} else {
				Log.SetOutput(file)
				Log.Infof("Logging to report directory: %s", logFilePath)
			}
		} else {
			// Use config path
			file, err := os.OpenFile(cfg.Output, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
			if err != nil {
				Log.Warnf("Failed to open log file '%s', using stdout: %v", cfg.Output, err)
				Log.SetOutput(os.Stdout)
			} else {
				Log.SetOutput(file)
			}
		}
	}

	Log.Info("Logger initialized successfully")
}

// GetLogger returns the global logger instance
func GetLogger() *logrus.Logger {
	if Log == nil {
		// Fallback to default logger
		Log = logrus.New()
		Log.SetLevel(logrus.InfoLevel)
		Log.SetFormatter(&logrus.JSONFormatter{})
	}
	return Log
}
