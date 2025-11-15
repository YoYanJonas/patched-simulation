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
	periodicSaveCtx   context.Context              // Context for periodic save goroutine
	periodicSaveCancel context.CancelFunc          // Cancel function for periodic save
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

	// Start model persistence (with cache agent periodic save)
	// Use cancellable context so we can notify periodic save on shutdown
	s.periodicSaveCtx, s.periodicSaveCancel = context.WithCancel(context.Background())
	algorithmManagerGetter := func() *rl.AlgorithmManager {
		return s.getAlgorithmManagerFromSystem()
	}
	cacheAgentGetter := func() *rl.CacheAgent {
		return s.getCacheAgentFromSystem()
	}
	go s.modelStorage.StartPeriodicSave(s.periodicSaveCtx, algorithmManagerGetter, cacheAgentGetter)

	// ADD: Start scheduler service (use background context for service lifecycle)
	s.schedulerService.Start(context.Background())
	
	logger.GetLogger().Info("[SCHEDULER-SERVER-START] Scheduler gRPC server ready to accept connections")
	logger.GetLogger().Infof("[SCHEDULER-SERVER-LISTEN] Starting gRPC server on %s", addr)

	// [DEBUG] About to start gRPC server
	logger.GetLogger().Infof("[DEBUG] [SERVER-START-GRPC-BEFORE] About to start gRPC server (blocking call)")
	// Start gRPC server (blocking call)
	if err := s.grpcServer.Serve(listener); err != nil {
		// [DEBUG] gRPC server failed
		logger.GetLogger().Errorf("[DEBUG] [SERVER-START-GRPC-ERROR] gRPC server failed: %v", err)
		return fmt.Errorf("gRPC server failed: %w", err)
	}
	// [DEBUG] gRPC server stopped (should not reach here normally)
	logger.GetLogger().Infof("[DEBUG] [SERVER-START-GRPC-AFTER] gRPC server stopped")

	return nil
}

// Stop gracefully stops the server
func (s *Server) Stop(ctx context.Context) error {
	logger.GetLogger().Info("[SCHEDULER-SERVER-STOP] Shutting down scheduler gRPC server...")

	// Cancel periodic save context to trigger shutdown save in StartPeriodicSave goroutine
	// This ensures the periodic save goroutine's ctx.Done() case runs
	if s.periodicSaveCancel != nil {
		logger.GetLogger().Info("[SCHEDULER-SERVER-STOP] Cancelling periodic save context...")
		s.periodicSaveCancel()
		// Give periodic save goroutine a moment to complete its shutdown save
		time.Sleep(500 * time.Millisecond)
	}

	// ADD: Save model on shutdown (also saves via SaveModelOnShutdown for redundancy)
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

	logger.GetLogger().Info("[SCHEDULER-MODEL-LOAD] Loading persisted model on startup...")

	// Get the algorithm manager from the system
	algorithmManager := s.getAlgorithmManagerFromSystem()
	if algorithmManager == nil {
		logger.GetLogger().Warn("[SCHEDULER-MODEL-LOAD] Algorithm manager not available, skipping model loading")
		return nil
	}

	// Load the model with algorithm manager
	err := s.modelStorage.LoadModel(algorithmManager)
	if err != nil {
		logger.GetLogger().Warnf("[SCHEDULER-MODEL-LOAD] Failed to load persisted model: %v", err)
		logger.GetLogger().Info("[SCHEDULER-MODEL-LOAD] Starting with fresh model (no previous learning data)")
		return nil // Don't fail startup, just start fresh
	}

	// Verify model was actually loaded (not invalid)
	// Check if Q-table has data
	currentAlg := algorithmManager.GetCurrentAlgorithm()
	if qlAlg, ok := currentAlg.(*rl.QLearningScheduler); ok {
		qTable := qlAlg.GetQTable()
		if len(qTable) == 0 {
			logger.GetLogger().Warnf("[SCHEDULER-MODEL-LOAD] Loaded model has empty Q-table, starting fresh")
			// Model was loaded but is invalid - this shouldn't happen if validation works
			// But add as safety check
		} else {
			logger.GetLogger().Infof("[SCHEDULER-MODEL-LOAD] Model loaded successfully: Q-table size=%d", len(qTable))
		}
	}

	logger.GetLogger().Info("[SCHEDULER-MODEL-LOAD] Model loaded and applied successfully")

	// Wire up dirty flag callback for Q-learning scheduler
	// This ensures model is marked as dirty when Q-table updates (lightweight, no I/O)
	currentAlg = algorithmManager.GetCurrentAlgorithm()
	if qlAlg, ok := currentAlg.(*rl.QLearningScheduler); ok {
		qlAlg.SetDirtyCallback(func() {
			s.modelStorage.MarkDirty()
		})
		logger.GetLogger().Info("[SCHEDULER-MODEL-LOAD] Dirty flag callback wired up for Q-learning scheduler")
	}

	// Load cache agent state
	cacheAgent := s.getCacheAgentFromSystem()
	if cacheAgent != nil {
		logger.GetLogger().Info("[SCHEDULER-MODEL-LOAD] Loading cache agent state on startup...")
		if err := s.modelStorage.LoadCacheAgent(cacheAgent); err != nil {
			logger.GetLogger().Warnf("[SCHEDULER-MODEL-LOAD] Failed to load cache agent: %v", err)
			// Don't fail startup, just log warning
		} else {
			logger.GetLogger().Info("[SCHEDULER-MODEL-LOAD] Cache agent loaded and applied successfully")
		}
	} else {
		logger.GetLogger().Info("[SCHEDULER-MODEL-LOAD] Cache agent not available, skipping cache agent load")
	}

	return nil
}

// SaveModelOnShutdown saves the current model state during shutdown
func (s *Server) SaveModelOnShutdown() error {
	if !s.config.ModelPersistence.Enabled {
		return nil
	}

	logger.GetLogger().Info("[SCHEDULER-MODEL-SAVE] Saving model state on shutdown...")

	// Get the algorithm manager from the system
	algorithmManager := s.getAlgorithmManagerFromSystem()
	if algorithmManager == nil {
		logger.GetLogger().Warn("[SCHEDULER-MODEL-SAVE] Algorithm manager not available, skipping model save")
		return nil
	}

	// Get current algorithm and log Q-table status
	currentAlg := algorithmManager.GetCurrentAlgorithm()
	logger.GetLogger().Warnf("[SCHEDULER-MODEL-SAVE] SaveModelOnShutdown: Current algorithm type = %T", currentAlg)
	
	if currentAlg == nil {
		logger.GetLogger().Errorf("[SCHEDULER-MODEL-SAVE] SaveModelOnShutdown: ERROR - currentAlg is NIL!")
		return fmt.Errorf("current algorithm is nil")
	}
	
	if qlAlg, ok := currentAlg.(*rl.QLearningScheduler); ok {
		// CRITICAL DIAGNOSTIC: Get Q-table and log it
		qTable := qlAlg.GetQTable()
		qTableSize := len(qTable)
		logger.GetLogger().Warnf("[SCHEDULER-MODEL-SAVE] SaveModelOnShutdown: Q-table size = %d", qTableSize)
		
		if qTableSize > 0 {
			logger.GetLogger().Warnf("[SCHEDULER-MODEL-SAVE] SaveModelOnShutdown: Listing ALL Q-table states:")
			stateIndex := 0
			for stateKey, actions := range qTable {
				stateIndex++
				logger.GetLogger().Warnf("[SCHEDULER-MODEL-SAVE] SaveModelOnShutdown:   State[%d]: Key='%s', Actions=%d", stateIndex, stateKey, len(actions))
			}
			logger.GetLogger().Infof("[SCHEDULER-MODEL-SAVE] Q-table has data (size=%d), proceeding with save", qTableSize)
		} else {
			logger.GetLogger().Errorf("[SCHEDULER-MODEL-SAVE] SaveModelOnShutdown: ERROR - Q-table is EMPTY (0 states)!")
			logger.GetLogger().Warnf("[SCHEDULER-MODEL-SAVE] Q-table is empty, but saving model anyway (for next run initialization)")
		}
	} else {
		logger.GetLogger().Errorf("[SCHEDULER-MODEL-SAVE] SaveModelOnShutdown: Current algorithm is NOT QLearningScheduler (type: %T)", currentAlg)
		logger.GetLogger().Errorf("[SCHEDULER-MODEL-SAVE] SaveModelOnShutdown: This means Q-table will NOT be saved!")
	}
	// Always save, even if Q-table is empty

	// Get scheduling Q-table size for comparison
	schedulingQTableSize := 0
	if algorithmManager != nil {
		currentAlg := algorithmManager.GetCurrentAlgorithm()
		if qlAlg, ok := currentAlg.(*rl.QLearningScheduler); ok {
			schedulingQTableSize = len(qlAlg.GetQTable())
		}
	}

	// Get cache Q-table size for comparison
	cacheAgent := s.getCacheAgentFromSystem()
	cacheQTableSize := 0
	if cacheAgent != nil {
		cacheQLScheduler := cacheAgent.GetQLearningScheduler()
		if cacheQLScheduler != nil {
			cacheQTableSize = len(cacheQLScheduler.GetQTable())
		}
	}

	logger.GetLogger().Infof("[SCHEDULER-MODEL-SAVE] Q-table comparison: Scheduling=%d states, Cache=%d states",
		schedulingQTableSize, cacheQTableSize)

	if schedulingQTableSize == 0 && cacheQTableSize > 0 {
		logger.GetLogger().Warnf("[SCHEDULER-MODEL-SAVE] DIAGNOSTIC: Scheduling Q-table is empty but cache Q-table has data!")
		logger.GetLogger().Warnf("[SCHEDULER-MODEL-SAVE] This suggests scheduling RL agent is not learning/updating Q-table")
	}

	// Save the model with algorithm manager (always save, even if Q-table is empty)
	if err := s.modelStorage.SaveModel(algorithmManager); err != nil {
		logger.GetLogger().Errorf("Failed to save model on shutdown: %v", err)
		return err
	}

	// Save cache agent state (cacheAgent already retrieved above)
	if cacheAgent != nil {
		logger.GetLogger().Info("[SCHEDULER-MODEL-SAVE] Saving cache agent state on shutdown...")
		if err := s.modelStorage.SaveCacheAgent(cacheAgent); err != nil {
			logger.GetLogger().Errorf("[SCHEDULER-MODEL-SAVE] Failed to save cache agent on shutdown: %v", err)
			// Don't fail shutdown, just log error
		} else {
			logger.GetLogger().Info("[SCHEDULER-MODEL-SAVE] Cache agent saved successfully on shutdown")
		}
	} else {
		logger.GetLogger().Info("[SCHEDULER-MODEL-SAVE] Cache agent not available, skipping cache agent save")
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

// getCacheAgentFromSystem retrieves the cache agent from the system
func (s *Server) getCacheAgentFromSystem() *rl.CacheAgent {
	if s.schedulerService == nil {
		return nil
	}

	// Get cache agent from scheduler service
	return s.schedulerService.GetCacheAgent()
}
