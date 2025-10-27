package metrics

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
)

// MetricsServer provides HTTP endpoint for metrics
type MetricsServer struct {
	server  *http.Server
	metrics *InMemoryMetrics
	config  *config.MetricsConfig
}

// NewMetricsServer creates a new metrics HTTP server
func NewMetricsServer(cfg *config.MetricsConfig, metrics *InMemoryMetrics) *MetricsServer {
	mux := http.NewServeMux()

	ms := &MetricsServer{
		metrics: metrics,
		config:  cfg,
		server: &http.Server{
			Addr:    fmt.Sprintf(":%d", cfg.Port),
			Handler: mux,
		},
	}

	// Register metrics endpoint
	mux.HandleFunc(cfg.Path, ms.handleMetrics)
	mux.HandleFunc("/health", ms.handleHealth)

	return ms
}

// Start starts the metrics HTTP server
func (ms *MetricsServer) Start() error {
	if !ms.config.Enabled {
		logger.GetLogger().Info("Metrics server disabled")
		return nil
	}

	logger.GetLogger().Infof("Starting metrics server on port %d", ms.config.Port)

	go func() {
		if err := ms.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.GetLogger().Errorf("Metrics server error: %v", err)
		}
	}()

	return nil
}

// Stop gracefully stops the metrics server
func (ms *MetricsServer) Stop(ctx context.Context) error {
	if !ms.config.Enabled {
		return nil
	}

	logger.GetLogger().Info("Stopping metrics server...")
	return ms.server.Shutdown(ctx)
}

// handleMetrics serves the metrics endpoint
func (ms *MetricsServer) handleMetrics(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	stats := ms.metrics.GetStats()

	if err := json.NewEncoder(w).Encode(stats); err != nil {
		logger.GetLogger().Errorf("Failed to encode metrics: %v", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}
}

// handleHealth serves a simple health check
func (ms *MetricsServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)

	health := map[string]interface{}{
		"status":    "healthy",
		"timestamp": time.Now().Format(time.RFC3339),
		"uptime":    time.Since(ms.metrics.startTime).String(),
	}

	json.NewEncoder(w).Encode(health)
}
