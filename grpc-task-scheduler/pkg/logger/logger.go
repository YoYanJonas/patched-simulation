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
	switch cfg.Format {
	case "json":
		Log.SetFormatter(&logrus.JSONFormatter{
			TimestampFormat: "2006-01-02T15:04:05.000Z07:00",
		})
	case "text":
		Log.SetFormatter(&logrus.TextFormatter{
			FullTimestamp:   true,
			TimestampFormat: "2006-01-02 15:04:05",
		})
	default:
		Log.Warnf("Invalid log format '%s', using 'json'", cfg.Format)
		Log.SetFormatter(&logrus.JSONFormatter{})
	}

	// Set output
	switch cfg.Output {
	case "stdout":
		Log.SetOutput(os.Stdout)
	case "stderr":
		Log.SetOutput(os.Stderr)
	default:
		// Assume file path
		file, err := os.OpenFile(cfg.Output, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
		if err != nil {
			Log.Warnf("Failed to open log file '%s', using stdout", cfg.Output)
			Log.SetOutput(os.Stdout)
		} else {
			Log.SetOutput(file)
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
