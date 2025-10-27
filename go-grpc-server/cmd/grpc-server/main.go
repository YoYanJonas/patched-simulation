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
	"grpc-server/pkg/logger"
	"grpc-server/service/gateway"

	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	"google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/reflection"
)

func main() {
	log := logger.NewLogger()

	// Create a TCP listener on port 50051 // TODO constant server info
	lis, err := net.Listen("tcp", ":50051")
	if err != nil {
		log.Error("Failed to listen on port 50051", err)
		return
	}

	// Create a new gRPC server with interceptors
	grpcServer := grpc.NewServer(
		grpc.UnaryInterceptor(loggingInterceptor(log)),
	)

	// Create and register the FogAllocationService
	fogService := gateway.NewFogAllocationService(log)
	proto.RegisterFogAllocationServiceServer(grpcServer, fogService)

	// Start periodic model saving in the background
	go startModelSaving(fogService, log)

	// Register health check service
	healthServer := health.NewServer()
	healthServer.SetServingStatus("", grpc_health_v1.HealthCheckResponse_SERVING)
	grpc_health_v1.RegisterHealthServer(grpcServer, healthServer)

	// Register reflection service for debugging and client discovery
	reflection.Register(grpcServer)

	// Handle graceful shutdown
	go handleShutdown(grpcServer, fogService, log)

	// Start the server
	log.Info("Starting Fog Allocation gRPC server on port 50051...")
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
func startModelSaving(service *gateway.FogAllocationService, log *logger.Logger) {
	ticker := time.NewTicker(10 * time.Minute) // TODO constant
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

	// Give clients 6 seconds to disconnect
	go func() {
		time.Sleep(6 * time.Second)
		log.Info("Forcing server shutdown after grace period")
		server.Stop()
	}()

	// Gracefully stop the server
	server.GracefulStop()
	log.Info("Server shutdown complete")
}
