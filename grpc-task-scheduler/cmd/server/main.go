package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
	"scheduler-grpc-server/pkg/server"
)

func main() {
	// Load configuration - Fix: Pass config file path
	err := config.LoadConfig(os.Getenv("CONFIG_FILE_PATH")) // or "" for auto-discovery
	if err != nil {
		panic("Failed to load configuration: " + err.Error())
	}

	// Get the loaded config
	cfg := config.GetConfig()

	// Validate enhanced RL configuration sections
	if cfg.RL.Enabled {
		if cfg.RL.StateDiscretization.Enabled {
			if err := validateFuzzyStateDiscretization(&cfg.RL.StateDiscretization); err != nil {
				panic("Fuzzy state discretization validation failed: " + err.Error())
			}
		}

		if cfg.RL.MemoryManagement.Enabled {
			if err := validateEnhancedMemoryManagement(&cfg.RL.MemoryManagement); err != nil {
				panic("Enhanced memory management validation failed: " + err.Error())
			}
		}
	}

	// Initialize logger
	logger.Initialize(&cfg.Logging)

	logger.GetLogger().Info("Starting Scheduler gRPC Server...")
	logger.GetLogger().Infof("Configuration loaded: %+v", cfg.Server)

	// Log enhanced RL configuration status
	if cfg.RL.Enabled {
		logger.GetLogger().Infof("RL Configuration: Algorithm=%s, LearningRate=%.3f, Episodes=%s",
			cfg.RL.Algorithm, cfg.RL.LearningRate, cfg.RL.EpisodeConfig.Type)

		if cfg.RL.StateDiscretization.Enabled {
			logger.GetLogger().Info("Fuzzy State Discretization: ENABLED")
			logger.GetLogger().Infof("  - CPU Categories: %v", cfg.RL.StateDiscretization.CPUUtilization.Categories)
			logger.GetLogger().Infof("  - Memory Categories: %v", cfg.RL.StateDiscretization.MemoryUtilization.Categories)
		}

		if cfg.RL.MemoryManagement.Enabled {
			logger.GetLogger().Infof("Enhanced Memory Management: ENABLED (Strategy=%s, MaxExp=%d)",
				cfg.RL.MemoryManagement.CleanupStrategy, cfg.RL.MemoryManagement.MaxExperiences)
		}

		if cfg.RL.MultiObjective.Enabled {
			logger.GetLogger().Infof("Multi-Objective Optimization: ENABLED (Profile=%s)",
				cfg.RL.MultiObjective.ActiveProfile)
		}
	}

	// Create server
	srv, err := server.NewServer(&cfg)
	if err != nil {
		logger.GetLogger().Fatalf("Failed to create server: %v", err)
	}

	// Setup graceful shutdown
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	// Start server in goroutine
	serverErr := make(chan error, 1)
	go func() {
		if err := srv.Start(); err != nil {
			serverErr <- err
		}
	}()

	// Wait for shutdown signal or server error
	select {
	case sig := <-sigCh:
		logger.GetLogger().Infof("Received signal: %v", sig)
	case err := <-serverErr:
		logger.GetLogger().Errorf("Server error: %v", err)
	}

	// Graceful shutdown with timeout
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.Server.ShutdownTimeout)
	defer shutdownCancel()

	if err := srv.Stop(shutdownCtx); err != nil {
		logger.GetLogger().Errorf("Failed to stop server gracefully: %v", err)
		os.Exit(1)
	}

	logger.GetLogger().Info("Scheduler gRPC Server stopped successfully")
}
func validateFuzzyStateDiscretization(config *config.StateDiscretizationConfig) error {
	if !config.Enabled {
		return nil
	}

	// Validate CPU Utilization
	if err := config.CPUUtilization.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("CPU utilization fuzzy categories invalid: %w", err)
	}

	// Validate Memory Utilization
	if err := config.MemoryUtilization.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("memory utilization fuzzy categories invalid: %w", err)
	}

	// Validate Queue Length
	if err := config.QueueLength.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("queue length fuzzy categories invalid: %w", err)
	}

	// Validate System Load
	if err := config.SystemLoad.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("system load fuzzy categories invalid: %w", err)
	}

	// Validate Task Priority
	if err := config.TaskPriority.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("task priority fuzzy categories invalid: %w", err)
	}

	return nil
}

func validateEnhancedMemoryManagement(config *config.MemoryManagementConfig) error {
	if !config.Enabled {
		return nil
	}

	if config.EmergencyCleanupThresholdMB <= 0 {
		return fmt.Errorf("emergency cleanup threshold must be positive, got %d MB", config.EmergencyCleanupThresholdMB)
	}

	if config.ExperienceTimeoutMinutes <= 0 {
		return fmt.Errorf("experience timeout must be positive, got %v", config.ExperienceTimeoutMinutes)
	}

	if config.MinHistorySize <= 0 {
		return fmt.Errorf("minimum history size must be positive, got %d", config.MinHistorySize)
	}

	return nil
}
