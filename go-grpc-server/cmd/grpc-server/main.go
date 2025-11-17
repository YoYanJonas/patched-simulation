package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"grpc-server/api/proto"
	grpcConfig "grpc-server/config"
	"grpc-server/pkg/logger"
	"grpc-server/service/gateway"

	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	"google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/reflection"
)

func main() {
	log := logger.NewLogger()

	// Load configuration - check for CONFIG_PATH env var
	configPath := os.Getenv("CONFIG_PATH")
	if configPath == "" {
		configPath = "" // Empty string triggers auto-discovery
	}
	cfg, err := grpcConfig.LoadConfig(configPath)
	if err != nil {
		log.Error("Failed to load configuration", err)
		return
	}

	// Override model save path if MODEL_PATH env var is set
	if modelPath := os.Getenv("MODEL_PATH"); modelPath != "" {
		cfg.RL.ModelSavePath = modelPath
		log.Info(fmt.Sprintf("Model save path overridden by MODEL_PATH env var: %s", modelPath))
	}

	// Create a TCP listener on configured host and port
	listenAddr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	lis, err := net.Listen("tcp", listenAddr)
	if err != nil {
		log.Error(fmt.Sprintf("Failed to listen on %s", listenAddr), err)
		return
	}

	// Create a new gRPC server with interceptors
	grpcServer := grpc.NewServer(
		grpc.UnaryInterceptor(loggingInterceptor(log)),
	)

	// Create and register the FogAllocationService with config
	fogService := gateway.NewFogAllocationServiceWithConfig(log, cfg)
	proto.RegisterFogAllocationServiceServer(grpcServer, fogService)

	// Start periodic model saving in the background
	go startModelSaving(fogService, log, cfg.RL.SaveInterval)

	// Register health check service
	healthServer := health.NewServer()
	healthServer.SetServingStatus("", grpc_health_v1.HealthCheckResponse_SERVING)
	grpc_health_v1.RegisterHealthServer(grpcServer, healthServer)

	// Register reflection service for debugging and client discovery
	reflection.Register(grpcServer)

	// Handle graceful shutdown
	go handleShutdown(grpcServer, fogService, log)

	// Start the server
	log.Info(fmt.Sprintf("Starting Fog Allocation gRPC server on %s...", listenAddr))
	if err := grpcServer.Serve(lis); err != nil {
		log.Error("Failed to serve gRPC server", err)
	}
}

// loggingInterceptor creates a gRPC interceptor for logging requests
func loggingInterceptor(log *logger.Logger) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
		start := time.Now()

		// Record the method being called
		method := info.FullMethod
		log.Info(fmt.Sprintf("Request started: %s", method))

		// Process the request
		resp, err := handler(ctx, req)

		// Log the completion
		duration := time.Since(start)
		if err != nil {
			log.Error(fmt.Sprintf("Request failed: %s (duration: %v)", method, duration), err)
		} else {
			log.Info(fmt.Sprintf("Request completed: %s (duration: %v)", method, duration))
		}

		return resp, err
	}
}

// startModelSaving starts a goroutine that periodically saves RL models
func startModelSaving(service *gateway.FogAllocationService, log *logger.Logger, intervalMinutes int) {
	interval := time.Duration(intervalMinutes) * time.Minute
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for range ticker.C {
		if err := service.SaveRLModels(); err != nil {
			log.Error("Failed to save RL models", err)
		} else {
			log.Info("Successfully saved RL models")
		}
	}
}

// handleShutdown sets up graceful shutdown for the server
func handleShutdown(server *grpc.Server, fogService *gateway.FogAllocationService, log *logger.Logger) {
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)

	sig := <-sigChan
	log.Info(fmt.Sprintf("Received signal %v, initiating graceful shutdown", sig))

	// Save models before shutting down
	log.Info("Saving RL models before shutdown")
	if err := fogService.SaveRLModels(); err != nil {
		log.Error("Failed to save RL models during shutdown", err)
	} else {
		log.Info("Successfully saved RL models")
	}

	// Load config for shutdown timeout
	cfg, _ := grpcConfig.LoadConfig("")
	timeoutDuration := 6 * time.Second
	if cfg != nil && cfg.Server.ShutdownTimeout > 0 {
		timeoutDuration = time.Duration(cfg.Server.ShutdownTimeout) * time.Second
	}

	// Give clients time to disconnect
	go func() {
		time.Sleep(timeoutDuration)
		log.Info("Forcing server shutdown after grace period")
		server.Stop()
	}()

	// Gracefully stop the server
	server.GracefulStop()
	log.Info("Server shutdown complete")
}
