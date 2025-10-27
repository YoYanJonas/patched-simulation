package server

import (
	"context"
	"fmt"
	"net"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	"google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/reflection"

	pb "scheduler-grpc-server/api/proto"
	"scheduler-grpc-server/internal/monitoring"
	"scheduler-grpc-server/internal/rl"
	"scheduler-grpc-server/internal/scheduler"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
	"scheduler-grpc-server/pkg/metrics"
	"scheduler-grpc-server/pkg/storage"
)

// Server represents the gRPC server
type Server struct {
	config            *config.Config
	grpcServer        *grpc.Server
	metrics           *metrics.InMemoryMetrics
	metricsServer     *metrics.MetricsServer
	modelStorage      *storage.ModelStorage
	listener          net.Listener
	monitoringService *monitoring.MonitoringService
	schedulerService  *scheduler.SchedulerService // ADD: Store scheduler service reference
}

// NewServer creates a new gRPC server instance
func NewServer(cfg *config.Config) (*Server, error) {
	// Initialize metrics
	metricsCollector := metrics.NewInMemoryMetrics()

	// Initialize model storage
	modelStorage := storage.NewModelStorage(&cfg.ModelPersistence)
	if err := modelStorage.Initialize(); err != nil {
		return nil, fmt.Errorf("failed to initialize model storage: %w", err)
	}

	// Create gRPC server with interceptors
	grpcServer := grpc.NewServer(
		grpc.MaxConcurrentStreams(uint32(cfg.Server.MaxConcurrentStreams)), // Convert int to uint32
		grpc.KeepaliveParams(keepalive.ServerParameters{
			Time:    cfg.Server.Keepalive.Time,
			Timeout: cfg.Server.Keepalive.Timeout,
		}),
		grpc.MaxRecvMsgSize(cfg.GRPC.MaxRecvMsgSize),
		grpc.MaxSendMsgSize(cfg.GRPC.MaxSendMsgSize),
		// Use the interceptors defined in middleware.go (same package)
		grpc.ChainUnaryInterceptor(
			RecoveryInterceptor,
			LoggingInterceptor,
			MetricsInterceptor(metricsCollector),
			TimeoutInterceptor(30*time.Second),
		),
	)

	// Register services
	// Register scheduler service
	schedulerService := scheduler.NewSchedulerService(metricsCollector, cfg) // CHANGED: Added cfg parameter
	pb.RegisterTaskSchedulerServer(grpcServer, schedulerService)

	// Register monitoring service
	monitoringService := monitoring.NewMonitoringService(metricsCollector)
	pb.RegisterSystemMonitoringServer(grpcServer, monitoringService)

	// Register health service if enabled
	if cfg.GRPC.HealthCheckEnabled {
		healthServer := health.NewServer()
		healthServer.SetServingStatus("", grpc_health_v1.HealthCheckResponse_SERVING)
		grpc_health_v1.RegisterHealthServer(grpcServer, healthServer)
	}

	// Register reflection if enabled
	if cfg.GRPC.ReflectionEnabled {
		reflection.Register(grpcServer)
	}

	// Create metrics HTTP server
	metricsServer := metrics.NewMetricsServer(&cfg.Metrics, metricsCollector)

	return &Server{
		config:            cfg,
		grpcServer:        grpcServer,
		metrics:           metricsCollector,
		metricsServer:     metricsServer,
		modelStorage:      modelStorage,
		monitoringService: monitoringService,
		schedulerService:  schedulerService, // ADD: Store scheduler service reference
	}, nil
}

// Start starts the gRPC server
func (s *Server) Start() error {
	// Create listener
	addr := fmt.Sprintf("%s:%d", s.config.Server.Host, s.config.Server.Port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", addr, err)
	}
	s.listener = listener

	// Start metrics server
	if err := s.metricsServer.Start(); err != nil {
		return fmt.Errorf("failed to start metrics server: %w", err)
	}

	// ADD: Load model on startup
	if err := s.LoadModelOnStartup(); err != nil {
		return fmt.Errorf("failed to load model on startup: %w", err)
	}

	// Start model persistence
	ctx := context.Background()
	go s.modelStorage.StartPeriodicSave(ctx)

	// ADD: Start scheduler service
	s.schedulerService.Start(ctx)

	logger.GetLogger().Infof("Starting gRPC server on %s", addr)

	// Start gRPC server (blocking call)
	if err := s.grpcServer.Serve(listener); err != nil {
		return fmt.Errorf("gRPC server failed: %w", err)
	}

	return nil
}

// Stop gracefully stops the server
func (s *Server) Stop(ctx context.Context) error {
	logger.GetLogger().Info("Shutting down server...")

	// ADD: Save model on shutdown
	if err := s.SaveModelOnShutdown(); err != nil {
		logger.GetLogger().Errorf("Failed to save model on shutdown: %v", err)
	}

	// ADD: Stop scheduler service first
	s.schedulerService.Stop()

	// Stop gRPC server gracefully
	done := make(chan struct{})
	go func() {
		s.grpcServer.GracefulStop()
		close(done)
	}()

	// Wait for graceful shutdown or timeout
	select {
	case <-done:
		logger.GetLogger().Info("gRPC server stopped gracefully")
	case <-ctx.Done():
		logger.GetLogger().Warn("Graceful shutdown timeout, forcing stop")
		s.grpcServer.Stop()
	}

	// Stop metrics server
	if err := s.metricsServer.Stop(ctx); err != nil {
		logger.GetLogger().Errorf("Failed to stop metrics server: %v", err)
	}

	logger.GetLogger().Info("Server shutdown completed")
	return nil
}

// GetMetrics returns current server metrics
func (s *Server) GetMetrics() map[string]interface{} {
	return s.metrics.GetStats()
}

// ============= MODEL PERSISTENCE INTEGRATION (CORRECTED) =============

// LoadModelOnStartup loads the persisted model during server startup
func (s *Server) LoadModelOnStartup() error {
	if !s.config.ModelPersistence.Enabled {
		logger.GetLogger().Info("Model persistence disabled, skipping model loading")
		return nil
	}

	logger.GetLogger().Info("Loading persisted model on startup...")

	// Get the algorithm manager from the system
	algorithmManager := s.getAlgorithmManagerFromSystem()
	if algorithmManager == nil {
		logger.GetLogger().Warn("Algorithm manager not available, skipping model loading")
		return nil
	}

	// Load the model with algorithm manager
	err := s.modelStorage.LoadModel(algorithmManager)
	if err != nil {
		logger.GetLogger().Warnf("Failed to load persisted model: %v", err)
		return nil // Don't fail startup, just log warning
	}

	logger.GetLogger().Info("Model loaded and applied successfully")
	return nil
}

// SaveModelOnShutdown saves the current model state during shutdown
func (s *Server) SaveModelOnShutdown() error {
	if !s.config.ModelPersistence.Enabled {
		return nil
	}

	logger.GetLogger().Info("Saving model state on shutdown...")

	// Get the algorithm manager from the system
	algorithmManager := s.getAlgorithmManagerFromSystem()
	if algorithmManager == nil {
		logger.GetLogger().Warn("Algorithm manager not available, skipping model save")
		return nil
	}

	// Save the model with algorithm manager
	if err := s.modelStorage.SaveModel(algorithmManager); err != nil {
		logger.GetLogger().Errorf("Failed to save model on shutdown: %v", err)
		return err
	}

	logger.GetLogger().Info("Model saved successfully on shutdown")
	return nil
}

// getAlgorithmManagerFromSystem retrieves the algorithm manager from the system
func (s *Server) getAlgorithmManagerFromSystem() *rl.AlgorithmManager {
	if s.schedulerService == nil {
		return nil
	}

	// Get agent from scheduler service
	agent := s.schedulerService.GetAgent()
	if agent == nil {
		return nil
	}

	// Get algorithm manager from agent - we need to add this method to Agent
	return agent.GetAlgorithmManager()
}
