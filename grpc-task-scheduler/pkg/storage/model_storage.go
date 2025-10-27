package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sync"
	"time"

	"scheduler-grpc-server/internal/rl"
	"scheduler-grpc-server/pkg/config"
	"scheduler-grpc-server/pkg/logger"
)

// ModelStorage handles RL model persistence
type ModelStorage struct {
	config    *config.ModelPersistenceConfig
	modelData interface{}
	mutex     sync.RWMutex
	dirty     bool
	stopCh    chan struct{}

	currentModel *ModelData
	storagePath  string
}

// ModelData represents the complete RL model structure
type ModelData struct {
	Algorithm    string                 `json:"algorithm"`
	LearningRate float64                `json:"learning_rate"`
	Weights      map[string]float64     `json:"weights"`
	Hyperparams  map[string]interface{} `json:"hyperparams"`
	Metadata     ModelMetadata          `json:"metadata"`
	Config       *config.Config         `json:"config"`
	ConfigHash   string                 `json:"config_hash"`

	// RL-SPECIFIC DATA STRUCTURES
	QLearningData        *QLearningModelData   `json:"qlearning_data,omitempty"`
	AlgorithmManagerData *AlgorithmManagerData `json:"algorithm_manager_data,omitempty"`
	ExperienceBufferData *ExperienceBufferData `json:"experience_buffer_data,omitempty"`
	MultiObjectiveData   *MultiObjectiveData   `json:"multi_objective_data,omitempty"`
}

// QLearningModelData stores Q-learning agent state
type QLearningModelData struct {
	QTable           map[string]map[string]float64 `json:"q_table"` // StateKey -> ActionType -> QValue
	CurrentEpisode   int                           `json:"current_episode"`
	EpisodeTaskCount int                           `json:"episode_task_count"`
	EpisodeStartTime time.Time                     `json:"episode_start_time"`
	LastEpisodeReset time.Time                     `json:"last_episode_reset"`
	ExplorationRate  float64                       `json:"exploration_rate"`
	IsLearning       bool                          `json:"is_learning"`
	RewardWeights    config.RewardWeights          `json:"reward_weights"`

	// Episode Configuration
	EpisodeConfig config.EpisodeConfig `json:"episode_config"`

	// Performance Optimization Cache (don't persist - rebuild on load)
	// FrequentStates map[string]time.Time `json:"-"` // Skip serialization

	// Learning Progress Tracking
	TotalQUpdates       int64     `json:"total_q_updates"`
	AverageQValue       float64   `json:"average_q_value"`
	QTableSize          int       `json:"q_table_size"`
	LastUpdateTimestamp time.Time `json:"last_update_timestamp"`
}

// AlgorithmManagerData stores algorithm manager state
type AlgorithmManagerData struct {
	CurrentAlgorithmType string `json:"current_algorithm_type"`
	DefaultAlgorithm     string `json:"default_algorithm"`
	FallbackAlgorithm    string `json:"fallback_algorithm"`
	RLEnabled            bool   `json:"rl_enabled"`
	LearningModeEnabled  bool   `json:"learning_mode_enabled"`

	// Performance History (last 100 records)
	PerformanceHistory []AlgorithmPerformanceRecord `json:"performance_history"`

	// Algorithm Selection History
	AlgorithmSwitchHistory []AlgorithmSwitchRecord `json:"algorithm_switch_history"`

	// Statistics
	TotalTasksProcessed   int64     `json:"total_tasks_processed"`
	LastPerformanceUpdate time.Time `json:"last_performance_update"`
}

// AlgorithmPerformanceRecord tracks algorithm performance over episodes
type AlgorithmPerformanceRecord struct {
	AlgorithmType  string             `json:"algorithm_type"`
	Episode        int                `json:"episode"`
	Timestamp      time.Time          `json:"timestamp"`
	Metrics        map[string]float64 `json:"metrics"`
	TaskCount      int                `json:"task_count"`
	AverageLatency float64            `json:"average_latency"`
	Throughput     float64            `json:"throughput"`
}

// AlgorithmSwitchRecord tracks algorithm switching decisions
type AlgorithmSwitchRecord struct {
	Timestamp        time.Time `json:"timestamp"`
	FromAlgorithm    string    `json:"from_algorithm"`
	ToAlgorithm      string    `json:"to_algorithm"`
	Reason           string    `json:"reason"`
	PerformanceDelta float64   `json:"performance_delta"`
}

// ExperienceBufferData stores experience manager state
type ExperienceBufferData struct {
	// Incomplete experiences that need completion after restart
	IncompleteExperiences []IncompleteExperienceData `json:"incomplete_experiences"`

	// Q-Value stability tracking history (last 50 episodes)
	QValueStabilityHistory map[string][]QValueHistoryEntry `json:"q_value_stability_history"`

	// Memory management statistics
	MemoryStats ExperienceMemoryStats `json:"memory_stats"`

	// Episode tracking
	LastCompletedEpisode   int         `json:"last_completed_episode"`
	EpisodeExperienceCount map[int]int `json:"episode_experience_count"`

	// Cleanup statistics
	LastCleanupTimestamp time.Time        `json:"last_cleanup_timestamp"`
	TotalCleanupCount    int64            `json:"total_cleanup_count"`
	CleanupStats         CleanupStatsData `json:"cleanup_stats"`
}

// IncompleteExperienceData represents incomplete experiences for persistence
type IncompleteExperienceData struct {
	TaskID        string           `json:"task_id"`
	StateFeatures StateFeatureData `json:"state_features"`
	Action        ActionData       `json:"action"`
	StoredAt      time.Time        `json:"stored_at"`
	Episode       int              `json:"episode"`
	TimeoutAt     time.Time        `json:"timeout_at"`
}

// StateFeatureData serializable state features
type StateFeatureData struct {
	CPUUtilization    float64 `json:"cpu_utilization"`
	MemoryUtilization float64 `json:"memory_utilization"`
	QueueLength       float64 `json:"queue_length"`
	SystemLoad        float64 `json:"system_load"`
	AvgTaskPriority   float64 `json:"avg_task_priority"`
	StateKey          string  `json:"state_key"`
}

// ActionData serializable action data
type ActionData struct {
	Type       string                 `json:"type"`
	Parameters map[string]interface{} `json:"parameters"`
}

// QValueHistoryEntry tracks Q-value changes over time
type QValueHistoryEntry struct {
	Episode   int       `json:"episode"`
	QValue    float64   `json:"q_value"`
	Timestamp time.Time `json:"timestamp"`
}

// ExperienceMemoryStats tracks memory usage
type ExperienceMemoryStats struct {
	TotalExperiences       int       `json:"total_experiences"`
	IncompleteExperiences  int       `json:"incomplete_experiences"`
	EstimatedMemoryUsageMB float64   `json:"estimated_memory_usage_mb"`
	MaxMemoryLimitMB       float64   `json:"max_memory_limit_mb"`
	MemoryUtilizationPct   float64   `json:"memory_utilization_pct"`
	LastMemoryCheckTime    time.Time `json:"last_memory_check_time"`
}

// CleanupStatsData tracks cleanup operation statistics
type CleanupStatsData struct {
	ExperiencesRemoved    int64   `json:"experiences_removed"`
	HistoryEntriesRemoved int64   `json:"history_entries_removed"`
	MemoryFreedMB         float64 `json:"memory_freed_mb"`
	CleanupDurationMs     int64   `json:"cleanup_duration_ms"`
	CleanupTrigger        string  `json:"cleanup_trigger"`
}

// MultiObjectiveData stores ESSENTIAL multi-objective calculator state (Step 6.4)
type MultiObjectiveData struct {
	// ESSENTIAL STATE ONLY (for policy reuse)
	CurrentWeights      config.RewardWeights `json:"current_weights"`      // CRITICAL: Final adapted weights
	ActiveProfile       string               `json:"active_profile"`       // Current profile name
	ScalarizationMethod string               `json:"scalarization_method"` // Algorithm behavior
	AdaptationEnabled   bool                 `json:"adaptation_enabled"`   // Adaptation config
	AdaptationWindow    int                  `json:"adaptation_window"`    // Window size config

	// skipped Performance history, Pareto front, adaptation history (not needed for policy reuse)
}

// ObjectivePerformanceData serializable objective performance
type ObjectivePerformanceData struct {
	Episode          int                  `json:"episode"`
	Objectives       ObjectiveVectorData  `json:"objectives"`
	ScalarizedReward float64              `json:"scalarized_reward"`
	Weights          config.RewardWeights `json:"weights"`
	ProfileUsed      string               `json:"profile_used"`
	Timestamp        time.Time            `json:"timestamp"`
}

// ObjectiveVectorData serializable objective vector
type ObjectiveVectorData struct {
	Latency            float64   `json:"latency"`
	Throughput         float64   `json:"throughput"`
	ResourceEfficiency float64   `json:"resource_efficiency"`
	Fairness           float64   `json:"fairness"`
	DeadlineMiss       float64   `json:"deadline_miss"`
	EnergyEfficiency   float64   `json:"energy_efficiency"`
	Timestamp          time.Time `json:"timestamp"`
	Episode            int       `json:"episode"`
}

// WeightAdaptationData serializable weight adaptation record
type WeightAdaptationData struct {
	Timestamp   time.Time            `json:"timestamp"`
	Episode     int                  `json:"episode"`
	OldWeights  config.RewardWeights `json:"old_weights"`
	NewWeights  config.RewardWeights `json:"new_weights"`
	Reason      string               `json:"reason"`
	Performance float64              `json:"performance_change"`
}

// ModelMetadata (ENHANCED with RL-specific metadata)
type ModelMetadata struct {
	Version     string    `json:"version"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
	TrainingEps int64     `json:"training_episodes"`
	TotalReward float64   `json:"total_reward"`

	// RL-SPECIFIC METADATA (NEW)
	RLEnabled                  bool `json:"rl_enabled"`
	MultiObjectiveEnabled      bool `json:"multi_objective_enabled"`
	ExperienceManagerEnabled   bool `json:"experience_manager_enabled"`
	StateDiscretizationEnabled bool `json:"state_discretization_enabled"`

	// Model Health Indicators
	QTableConverged      bool      `json:"q_table_converged"`
	LastConvergenceCheck time.Time `json:"last_convergence_check"`
	ModelHealthScore     float64   `json:"model_health_score"`

	// Configuration Hashes (to detect config changes)
	RLConfigHash             string `json:"rl_config_hash"`
	MultiObjectiveConfigHash string `json:"multi_objective_config_hash"`

	// Performance Indicators
	AverageEpisodeReward   float64 `json:"average_episode_reward"`
	BestEpisodeReward      float64 `json:"best_episode_reward"`
	RecentPerformanceTrend string  `json:"recent_performance_trend"` // "improving", "stable", "declining"
}

// NewModelStorage creates a new model storage instance
func NewModelStorage(cfg *config.ModelPersistenceConfig) *ModelStorage {
	return &ModelStorage{
		config: cfg,
		stopCh: make(chan struct{}),
	}
}

// Initialize loads existing model or creates default (called ONCE at startup)
func (ms *ModelStorage) Initialize() error {
	if !ms.config.Enabled {
		logger.GetLogger().Info("Model persistence disabled")
		return nil
	}

	ms.mutex.Lock()
	defer ms.mutex.Unlock()

	modelPath := ms.getCurrentModelPath()

	// Check if model file exists
	if _, err := os.Stat(modelPath); os.IsNotExist(err) {
		logger.GetLogger().Info("No existing model found, creating default model")
		ms.modelData = ms.createDefaultModel()
		ms.dirty = true
		return nil
	}

	// Load existing model
	logger.GetLogger().Infof("Loading existing model from: %s", modelPath)
	data, err := os.ReadFile(modelPath)
	if err != nil {
		return fmt.Errorf("failed to read model file: %w", err)
	}

	var modelData ModelData
	if err := json.Unmarshal(data, &modelData); err != nil {
		logger.GetLogger().Warnf("Failed to parse existing model, using default: %v", err)
		ms.modelData = ms.createDefaultModel()
		ms.dirty = true
		return nil
	}

	ms.modelData = &modelData
	logger.GetLogger().Infof("Model loaded successfully. Version: %s, Episodes: %d, RL Components: Q-Learning=%v, MultiObj=%v",
		modelData.Metadata.Version,
		modelData.Metadata.TrainingEps,
		modelData.QLearningData != nil,
		modelData.MultiObjectiveData != nil)

	return nil
}

// GetModel returns current model (FAST - memory access only)
func (ms *ModelStorage) GetModel() interface{} {
	ms.mutex.RLock()
	defer ms.mutex.RUnlock()
	return ms.modelData
}

// UpdateModel updates the model in memory (FAST - no I/O)
func (ms *ModelStorage) UpdateModel(newModel interface{}) {
	ms.mutex.Lock()
	defer ms.mutex.Unlock()

	ms.modelData = newModel
	ms.dirty = true

	// Update metadata if it's our enhanced ModelData type
	if modelData, ok := newModel.(*ModelData); ok {
		modelData.Metadata.UpdatedAt = time.Now()
		modelData.Metadata.TrainingEps++

		// Update RL-specific health indicators
		ms.updateModelHealthIndicators(modelData)
	}

	logger.GetLogger().Debug("Model updated in memory with RL components")
}

// updateModelHealthIndicators updates RL-specific health metrics
func (ms *ModelStorage) updateModelHealthIndicators(modelData *ModelData) {
	// Update Q-table convergence status
	if modelData.QLearningData != nil {
		// Simple convergence check: if exploration rate is very low, consider converged
		if modelData.QLearningData.ExplorationRate <= 0.1 {
			modelData.Metadata.QTableConverged = true
		}
		modelData.Metadata.LastConvergenceCheck = time.Now()
	}

	// Update performance trends from multi-objective data (simplified for essential state)
	if modelData.MultiObjectiveData != nil {
		// Use a simple health indicator based on current weights validity
		weights := modelData.MultiObjectiveData.CurrentWeights
		if err := ms.validateRewardWeights(weights); err == nil {
			modelData.Metadata.RecentPerformanceTrend = "stable"
			modelData.Metadata.AverageEpisodeReward = 0.5 // Default stable value
		} else {
			modelData.Metadata.RecentPerformanceTrend = "declining"
			modelData.Metadata.AverageEpisodeReward = 0.0
		}
	}

	// Calculate overall model health score (0.0 to 1.0)
	healthScore := 0.0
	factors := 0

	// Factor 1: Q-table size (larger = more learned)
	if modelData.QLearningData != nil {
		if modelData.QLearningData.QTableSize > 0 {
			healthScore += math.Min(1.0, float64(modelData.QLearningData.QTableSize)/1000.0)
			factors++
		}
	}

	// Factor 2: Performance trend
	switch modelData.Metadata.RecentPerformanceTrend {
	case "improving":
		healthScore += 1.0
	case "stable":
		healthScore += 0.7
	case "declining":
		healthScore += 0.3
	}
	factors++

	// Factor 3: Multi-objective state validity (simplified)
	if modelData.MultiObjectiveData != nil {
		weights := modelData.MultiObjectiveData.CurrentWeights
		if err := ms.validateRewardWeights(weights); err == nil {
			healthScore += 0.8 // Good weight configuration
			factors++
		}
	}

	if factors > 0 {
		modelData.Metadata.ModelHealthScore = healthScore / float64(factors)
	}
}

// StartPeriodicSave starts background saving routine
func (ms *ModelStorage) StartPeriodicSave(ctx context.Context) {
	if !ms.config.Enabled || ms.config.SaveInterval <= 0 {
		logger.GetLogger().Info("Periodic model saving disabled")
		return
	}

	logger.GetLogger().Infof("Starting periodic RL model saving every %v", ms.config.SaveInterval)

	ticker := time.NewTicker(ms.config.SaveInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			if ms.dirty {
				if err := ms.saveModel(); err != nil {
					logger.GetLogger().Errorf("Failed to save RL model: %v", err)
				} else {
					logger.GetLogger().Debug("RL model saved successfully (periodic)")
					ms.dirty = false
				}
			}
		case <-ctx.Done():
			// Final save on shutdown
			if ms.config.SaveOnShutdown && ms.dirty {
				logger.GetLogger().Info("Performing final RL model save on shutdown...")
				if err := ms.saveModel(); err != nil {
					logger.GetLogger().Errorf("Failed to save RL model on shutdown: %v", err)
				} else {
					logger.GetLogger().Info("RL model saved successfully on shutdown")
				}
			}
			return
		}
	}
}

// saveModel performs the actual file I/O (background operation)
func (ms *ModelStorage) saveModel() error {
	ms.mutex.RLock()
	modelCopy := ms.modelData
	ms.mutex.RUnlock()

	// Create directory structure
	modelDir := filepath.Join(ms.config.ModelsPath, ms.config.ModelName, "current")
	if err := os.MkdirAll(modelDir, 0755); err != nil {
		return fmt.Errorf("failed to create model directory: %w", err)
	}

	// Create backup if current model exists
	currentPath := ms.getCurrentModelPath()
	if _, err := os.Stat(currentPath); err == nil {
		if err := ms.createBackup(currentPath); err != nil {
			logger.GetLogger().Warnf("Failed to create backup: %v", err)
		}
	}

	// Marshal model data with pretty formatting for readability
	data, err := json.MarshalIndent(modelCopy, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal RL model: %w", err)
	}

	// Write to temporary file first, then rename (atomic operation)
	tempPath := currentPath + ".tmp"
	if err := os.WriteFile(tempPath, data, 0644); err != nil {
		return fmt.Errorf("failed to write temp RL model file: %w", err)
	}

	if err := os.Rename(tempPath, currentPath); err != nil {
		os.Remove(tempPath) // Cleanup temp file
		return fmt.Errorf("failed to rename temp RL model file: %w", err)
	}

	// Log RL-specific save details
	if modelData, ok := modelCopy.(*ModelData); ok {
		logger.GetLogger().Infof("RL model saved: Episodes=%d, Q-table=%v, MultiObj=%v, Experience=%v",
			modelData.Metadata.TrainingEps,
			modelData.QLearningData != nil,
			modelData.MultiObjectiveData != nil,
			modelData.ExperienceBufferData != nil)
	}

	return nil
}

func (ms *ModelStorage) createBackup(currentPath string) error {
	backupDir := filepath.Join(ms.config.ModelsPath, ms.config.ModelName, "backups")
	if err := os.MkdirAll(backupDir, 0755); err != nil {
		return err
	}

	timestamp := time.Now().Format("20060102_150405")
	backupPath := filepath.Join(backupDir, fmt.Sprintf("model_%s.json", timestamp))

	data, err := os.ReadFile(currentPath)
	if err != nil {
		return err
	}

	return os.WriteFile(backupPath, data, 0644)
}

func (ms *ModelStorage) getCurrentModelPath() string {
	return filepath.Join(ms.config.ModelsPath, ms.config.ModelName, "current", "model.json")
}

// createDefaultModel creates enhanced default model with RL components
func (ms *ModelStorage) createDefaultModel() *ModelData {
	now := time.Now()
	cfg := config.GetConfig()

	// Use config-driven algorithm selection instead of hardcoded
	defaultAlgorithm := "qlearning" // fallback
	if cfg.AlgorithmManager.DefaultAlgorithm != "" {
		defaultAlgorithm = cfg.AlgorithmManager.DefaultAlgorithm
	}

	return &ModelData{
		Algorithm:    defaultAlgorithm, // Now config-driven
		LearningRate: cfg.RL.LearningRate,
		Weights:      make(map[string]float64), // Empty initially - will be populated during learning
		Hyperparams: map[string]interface{}{
			"batch_size":      cfg.RL.BatchSize,
			"experience_size": cfg.RL.ExperienceSize,
			"epsilon_start":   cfg.RL.ExplorationRate,
			"epsilon_end":     cfg.RL.MinExploration,
			"epsilon_decay":   cfg.RL.ExplorationDecay,
			"discount_factor": cfg.RL.DiscountFactor,
		},
		Metadata: ModelMetadata{
			Version:                    "1.0.0", // Now config-driven
			CreatedAt:                  now,
			UpdatedAt:                  now,
			TrainingEps:                0,   // Counter - starts at 0
			TotalReward:                0.0, // Accumulator - starts at 0
			RLEnabled:                  cfg.RL.Enabled,
			MultiObjectiveEnabled:      cfg.RL.MultiObjective.Enabled,
			ExperienceManagerEnabled:   cfg.RL.MemoryManagement.Enabled,
			StateDiscretizationEnabled: cfg.RL.StateDiscretization.Enabled,
			QTableConverged:            false, // Status flag - starts false
			LastConvergenceCheck:       now,
			ModelHealthScore:           0.0,      // Metric - starts at 0
			RecentPerformanceTrend:     "stable", // Initial state
			AverageEpisodeReward:       0.0,      // Metric - starts at 0
			BestEpisodeReward:          0.0,      // Metric - starts at 0
		},

		// Initialize RL components based on config
		QLearningData: func() *QLearningModelData {
			if cfg.RL.Enabled && (defaultAlgorithm == "qlearning" || cfg.AlgorithmManager.RLEnabled) {
				return &QLearningModelData{
					QTable:              make(map[string]map[string]float64), // Empty Q-table initially
					CurrentEpisode:      1,                                   // Starts at episode 1
					EpisodeTaskCount:    0,                                   // Counter - starts at 0
					EpisodeStartTime:    now,
					LastEpisodeReset:    now,
					ExplorationRate:     cfg.RL.ExplorationRate, // Config-driven
					IsLearning:          cfg.RL.Enabled,         // Config-driven
					RewardWeights:       cfg.RL.RewardWeights,   // Config-driven
					EpisodeConfig:       cfg.RL.EpisodeConfig,   // Config-driven
					TotalQUpdates:       0,                      // Counter - starts at 0
					AverageQValue:       0.0,                    // Metric - starts at 0
					QTableSize:          0,                      // Counter - starts at 0
					LastUpdateTimestamp: now,
				}
			}
			return nil // Don't create if RL is disabled
		}(),

		AlgorithmManagerData: &AlgorithmManagerData{
			CurrentAlgorithmType:   cfg.AlgorithmManager.DefaultAlgorithm,  // Config-driven
			DefaultAlgorithm:       cfg.AlgorithmManager.DefaultAlgorithm,  // Config-driven
			FallbackAlgorithm:      cfg.AlgorithmManager.FallbackAlgorithm, // Config-driven
			RLEnabled:              cfg.AlgorithmManager.RLEnabled,         // Config-driven
			LearningModeEnabled:    cfg.RL.Enabled,                         // Config-driven
			PerformanceHistory:     make([]AlgorithmPerformanceRecord, 0),  // Empty initially
			AlgorithmSwitchHistory: make([]AlgorithmSwitchRecord, 0),       // Empty initially
			TotalTasksProcessed:    0,                                      // Counter - starts at 0
			LastPerformanceUpdate:  now,
		},

		ExperienceBufferData: func() *ExperienceBufferData {
			if cfg.RL.MemoryManagement.Enabled {
				return &ExperienceBufferData{
					IncompleteExperiences:  make([]IncompleteExperienceData, 0),   // Empty initially
					QValueStabilityHistory: make(map[string][]QValueHistoryEntry), // Empty initially
					MemoryStats: ExperienceMemoryStats{
						TotalExperiences:       0,                                                            // Counter - starts at 0
						IncompleteExperiences:  0,                                                            // Counter - starts at 0
						EstimatedMemoryUsageMB: 0.0,                                                          // Metric - starts at 0
						MaxMemoryLimitMB:       float64(cfg.RL.MemoryManagement.EmergencyCleanupThresholdMB), // Config-driven
						MemoryUtilizationPct:   0.0,                                                          // Metric - starts at 0
						LastMemoryCheckTime:    now,
					},
					LastCompletedEpisode:   0,                 // Counter - starts at 0
					EpisodeExperienceCount: make(map[int]int), // Empty initially
					LastCleanupTimestamp:   now,
					TotalCleanupCount:      0, // Counter - starts at 0
					CleanupStats: CleanupStatsData{
						ExperiencesRemoved:    0,      // Counter - starts at 0
						HistoryEntriesRemoved: 0,      // Counter - starts at 0
						MemoryFreedMB:         0.0,    // Metric - starts at 0
						CleanupDurationMs:     0,      // Metric - starts at 0
						CleanupTrigger:        "none", // Initial state
					},
				}
			}
			return nil // Don't create if memory management is disabled
		}(),

		MultiObjectiveData: func() *MultiObjectiveData {
			if cfg.RL.MultiObjective.Enabled {
				return &MultiObjectiveData{
					CurrentWeights:      cfg.RL.RewardWeights,                      // CRITICAL: Initial weights from config
					ActiveProfile:       cfg.RL.MultiObjective.ActiveProfile,       // Config-driven
					ScalarizationMethod: cfg.RL.MultiObjective.ScalarizationMethod, // Config-driven
					AdaptationEnabled:   cfg.RL.MultiObjective.AdaptationEnabled,   // Config-driven
					AdaptationWindow:    cfg.RL.MultiObjective.AdaptationWindow,    // Config-driven
				}
			}
			return nil // Don't create if multi-objective is disabled
		}(),
	}
}

// SaveQLearningAgent saves Q-learning agent state to persistent storage
func (ms *ModelStorage) SaveQLearningAgent(agent *rl.QLearningScheduler) error {
	ms.mutex.Lock()
	defer ms.mutex.Unlock()

	if ms.currentModel == nil {
		ms.currentModel = ms.createDefaultModel()
	}

	// Extract Q-learning agent state using getter methods
	qData := &QLearningModelData{
		QTable:              ms.convertQTableToStringFormat(agent.GetQTable()),
		CurrentEpisode:      agent.GetCurrentEpisode(),
		EpisodeTaskCount:    agent.GetEpisodeTaskCount(),
		EpisodeStartTime:    agent.GetEpisodeStartTime(),
		LastEpisodeReset:    agent.GetLastEpisodeReset(),
		ExplorationRate:     agent.GetConfig().ExplorationRate,
		IsLearning:          agent.IsLearning(),
		RewardWeights:       agent.GetRewardWeights(),
		EpisodeConfig:       agent.GetConfig().EpisodeConfig,
		TotalQUpdates:       ms.calculateTotalQUpdates(agent.GetQTable()),
		AverageQValue:       ms.calculateAverageQValue(agent.GetQTable()),
		QTableSize:          len(agent.GetQTable()),
		LastUpdateTimestamp: time.Now(),
	}

	// Update model data
	ms.currentModel.QLearningData = qData
	ms.currentModel.Algorithm = "qlearning"
	ms.currentModel.LearningRate = agent.GetConfig().LearningRate
	ms.currentModel.Metadata.UpdatedAt = time.Now()
	ms.currentModel.Metadata.TrainingEps = int64(agent.GetCurrentEpisode())
	ms.currentModel.Metadata.RLEnabled = true

	// Update hyperparameters
	ms.currentModel.Hyperparams["current_episode"] = agent.GetCurrentEpisode()
	ms.currentModel.Hyperparams["exploration_rate"] = agent.GetConfig().ExplorationRate
	ms.currentModel.Hyperparams["episode_progress"] = agent.GetEpisodeProgress()
	ms.currentModel.Hyperparams["q_table_size"] = len(agent.GetQTable())
	ms.currentModel.Hyperparams["total_q_updates"] = qData.TotalQUpdates
	ms.currentModel.Hyperparams["average_q_value"] = qData.AverageQValue

	// Save to file
	if err := ms.saveToFile(); err != nil {
		return fmt.Errorf("failed to save Q-learning agent: %w", err)
	}

	logger.GetLogger().Info("Q-learning agent state saved successfully",
		"episode", agent.GetCurrentEpisode(),
		"q_table_size", len(agent.GetQTable()),
		"exploration_rate", agent.GetConfig().ExplorationRate)

	return nil
}

// LoadQLearningAgent loads Q-learning agent state from persistent storage
func (ms *ModelStorage) LoadQLearningAgent() (*rl.QLearningScheduler, error) {
	ms.mutex.RLock()
	defer ms.mutex.RUnlock()

	if err := ms.loadFromFile(); err != nil {
		return nil, fmt.Errorf("failed to load model data: %w", err)
	}

	if ms.currentModel == nil || ms.currentModel.QLearningData == nil {
		return nil, fmt.Errorf("no Q-learning data found in saved model")
	}

	qData := ms.currentModel.QLearningData
	cfg := config.GetConfig()

	// Create new Q-learning scheduler with saved configuration
	agent := rl.NewQLearningScheduler(cfg.RL, qData.RewardWeights)

	// Restore state using setter methods
	agent.SetCurrentEpisode(qData.CurrentEpisode)
	agent.SetEpisodeTaskCount(qData.EpisodeTaskCount)
	agent.SetEpisodeStartTime(qData.EpisodeStartTime)
	agent.SetLastEpisodeReset(qData.LastEpisodeReset)
	agent.SetExplorationRate(qData.ExplorationRate)
	agent.SetLearning(qData.IsLearning)
	agent.SetRewardWeights(qData.RewardWeights)
	agent.SetQTable(ms.convertQTableFromStringFormat(qData.QTable))

	// Validate loaded state
	if err := ms.validateQLearningState(agent, qData); err != nil {
		return nil, fmt.Errorf("loaded Q-learning state validation failed: %w", err)
	}

	logger.GetLogger().Info("Q-learning agent state loaded successfully",
		"episode", qData.CurrentEpisode,
		"q_table_states", len(qData.QTable),
		"exploration_rate", qData.ExplorationRate,
		"is_learning", qData.IsLearning)

	return agent, nil
}

// Helper method to calculate total Q-updates from Q-table
func (ms *ModelStorage) calculateTotalQUpdates(qTable map[string]map[rl.ActionType]float64) int64 {
	var totalUpdates int64 = 0
	for _, actions := range qTable {
		for _, qValue := range actions {
			if qValue != 0.0 {
				totalUpdates++
			}
		}
	}
	return totalUpdates
}

// Helper method to calculate average Q-value from Q-table
func (ms *ModelStorage) calculateAverageQValue(qTable map[string]map[rl.ActionType]float64) float64 {
	if len(qTable) == 0 {
		return 0.0
	}

	totalValue := 0.0
	count := 0

	for _, actions := range qTable {
		for _, qValue := range actions {
			totalValue += qValue
			count++
		}
	}

	if count == 0 {
		return 0.0
	}

	return totalValue / float64(count)
}

// Helper method to convert Q-table format for saving (ActionType to string)
func (ms *ModelStorage) convertQTableToStringFormat(qTable map[string]map[rl.ActionType]float64) map[string]map[string]float64 {
	stringQTable := make(map[string]map[string]float64)

	for stateKey, actions := range qTable {
		stringQTable[stateKey] = make(map[string]float64)

		for actionType, qValue := range actions {
			actionStr := ms.actionTypeToString(actionType)
			stringQTable[stateKey][actionStr] = qValue
		}
	}

	return stringQTable
}

// Helper method to convert Q-table format for loading (string to ActionType)
func (ms *ModelStorage) convertQTableFromStringFormat(savedQTable map[string]map[string]float64) map[string]map[rl.ActionType]float64 {
	qTable := make(map[string]map[rl.ActionType]float64)

	for stateKey, actions := range savedQTable {
		qTable[stateKey] = make(map[rl.ActionType]float64)

		for actionStr, qValue := range actions {
			actionType := ms.stringToActionType(actionStr)
			qTable[stateKey][actionType] = qValue
		}
	}

	return qTable
}

// Helper method to convert ActionType to string
func (ms *ModelStorage) actionTypeToString(actionType rl.ActionType) string {
	switch actionType {
	case rl.ActionNone:
		return "none"
	case rl.ActionScheduleNext:
		return "schedule_next"
	case rl.ActionReorder:
		return "reorder"
	case rl.ActionDelay:
		return "delay"
	case rl.ActionPriorityBoost:
		return "priority_boost"
	case rl.ActionPromoteHighPriority:
		return "promote_high_priority"
	case rl.ActionPromoteShortJobs:
		return "promote_short_jobs"
	case rl.ActionBalancedScheduling:
		return "balanced_scheduling"
	case rl.ActionDeadlineAware:
		return "deadline_aware"
	case rl.ActionResourceOptimized:
		return "resource_optimized"
	default:
		return "none"
	}
}

// Helper method to convert string to ActionType
func (ms *ModelStorage) stringToActionType(actionStr string) rl.ActionType {
	switch actionStr {
	case "none":
		return rl.ActionNone
	case "schedule_next":
		return rl.ActionScheduleNext
	case "reorder":
		return rl.ActionReorder
	case "delay":
		return rl.ActionDelay
	case "priority_boost":
		return rl.ActionPriorityBoost
	case "promote_high_priority":
		return rl.ActionPromoteHighPriority
	case "promote_short_jobs":
		return rl.ActionPromoteShortJobs
	case "balanced_scheduling":
		return rl.ActionBalancedScheduling
	case "deadline_aware":
		return rl.ActionDeadlineAware
	case "resource_optimized":
		return rl.ActionResourceOptimized
	default:
		return rl.ActionNone
	}
}

// Helper method to validate loaded Q-learning state
func (ms *ModelStorage) validateQLearningState(agent *rl.QLearningScheduler, qData *QLearningModelData) error {
	if qData.CurrentEpisode < 1 {
		return fmt.Errorf("invalid current episode: %d", qData.CurrentEpisode)
	}

	if qData.EpisodeTaskCount < 0 {
		return fmt.Errorf("invalid episode task count: %d", qData.EpisodeTaskCount)
	}

	if qData.ExplorationRate < 0 || qData.ExplorationRate > 1 {
		return fmt.Errorf("invalid exploration rate: %f", qData.ExplorationRate)
	}

	if len(agent.GetQTable()) != qData.QTableSize {
		logger.GetLogger().Warn("Q-table size mismatch after loading",
			"expected", qData.QTableSize,
			"actual", len(agent.GetQTable()))
	}

	if qData.EpisodeStartTime.After(time.Now()) {
		return fmt.Errorf("invalid episode start time: %v", qData.EpisodeStartTime)
	}

	if qData.LastEpisodeReset.After(time.Now()) {
		return fmt.Errorf("invalid last episode reset time: %v", qData.LastEpisodeReset)
	}

	if err := ms.validateRewardWeights(qData.RewardWeights); err != nil {
		return fmt.Errorf("invalid reward weights: %w", err)
	}

	return nil
}

// Helper method to validate reward weights
func (ms *ModelStorage) validateRewardWeights(weights config.RewardWeights) error {
	total := weights.Latency + weights.Throughput + weights.ResourceEfficiency +
		weights.Fairness + weights.DeadlineMiss + weights.EnergyEfficiency

	if math.Abs(total-1.0) > 0.01 {
		return fmt.Errorf("reward weights don't sum to 1.0: %f", total)
	}

	if weights.Latency < 0 || weights.Throughput < 0 || weights.ResourceEfficiency < 0 ||
		weights.Fairness < 0 || weights.DeadlineMiss < 0 || weights.EnergyEfficiency < 0 {
		return fmt.Errorf("reward weights cannot be negative")
	}

	return nil
}

// GetQLearningAgentStats returns statistics about the saved Q-learning agent
func (ms *ModelStorage) GetQLearningAgentStats() (map[string]interface{}, error) {
	ms.mutex.RLock()
	defer ms.mutex.RUnlock()

	if ms.currentModel == nil || ms.currentModel.QLearningData == nil {
		return nil, fmt.Errorf("no Q-learning data available")
	}

	qData := ms.currentModel.QLearningData
	stats := make(map[string]interface{})

	stats["current_episode"] = qData.CurrentEpisode
	stats["episode_task_count"] = qData.EpisodeTaskCount
	stats["exploration_rate"] = qData.ExplorationRate
	stats["is_learning"] = qData.IsLearning
	stats["q_table_size"] = qData.QTableSize
	stats["total_q_updates"] = qData.TotalQUpdates
	stats["average_q_value"] = qData.AverageQValue
	stats["last_update"] = qData.LastUpdateTimestamp
	stats["episode_start_time"] = qData.EpisodeStartTime
	stats["last_episode_reset"] = qData.LastEpisodeReset

	if qData.EpisodeConfig.Type == "task_based" && qData.EpisodeConfig.TasksPerEpisode > 0 {
		progress := float64(qData.EpisodeTaskCount) / float64(qData.EpisodeConfig.TasksPerEpisode)
		stats["episode_progress"] = math.Min(progress, 1.0)
	} else if qData.EpisodeConfig.Type == "time_based" && qData.EpisodeConfig.TimePerEpisodeMinutes > 0 {
		elapsed := time.Since(qData.EpisodeStartTime).Minutes()
		progress := elapsed / float64(qData.EpisodeConfig.TimePerEpisodeMinutes)
		stats["episode_progress"] = math.Min(progress, 1.0)
	}

	return stats, nil
}

// IsQLearningAgentSaved checks if Q-learning agent data exists in storage
func (ms *ModelStorage) IsQLearningAgentSaved() bool {
	ms.mutex.RLock()
	defer ms.mutex.RUnlock()

	return ms.currentModel != nil && ms.currentModel.QLearningData != nil
}

// saveToFile saves the current model to file
func (ms *ModelStorage) saveToFile() error {
	if ms.currentModel == nil {
		return fmt.Errorf("no model data to save")
	}

	// Create directory if it doesn't exist
	if err := os.MkdirAll(filepath.Dir(ms.storagePath), 0755); err != nil {
		return fmt.Errorf("failed to create storage directory: %w", err)
	}

	// Marshal model data to JSON
	data, err := json.MarshalIndent(ms.currentModel, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal model data: %w", err)
	}

	// Write to file
	if err := os.WriteFile(ms.storagePath, data, 0644); err != nil {
		return fmt.Errorf("failed to write model file: %w", err)
	}

	return nil
}

// loadFromFile loads model data from file
func (ms *ModelStorage) loadFromFile() error {
	// Check if file exists
	if _, err := os.Stat(ms.storagePath); os.IsNotExist(err) {
		return fmt.Errorf("model file does not exist: %s", ms.storagePath)
	}

	// Read file
	data, err := os.ReadFile(ms.storagePath)
	if err != nil {
		return fmt.Errorf("failed to read model file: %w", err)
	}

	// Unmarshal JSON
	var modelData ModelData
	if err := json.Unmarshal(data, &modelData); err != nil {
		return fmt.Errorf("failed to unmarshal model data: %w", err)
	}

	ms.currentModel = &modelData
	return nil
}

// ============= ALGORITHM MANAGER ESSENTIAL STATE PERSISTENCE =============

// SaveAlgorithmManagerState saves essential algorithm manager state to persistent storage
func (ms *ModelStorage) SaveAlgorithmManagerState(manager *rl.AlgorithmManager) error {
	ms.mutex.Lock()
	defer ms.mutex.Unlock()

	if ms.currentModel == nil {
		ms.currentModel = ms.createDefaultModel()
	}

	// Extract essential algorithm manager state (compatibility-critical only)
	amData := &AlgorithmManagerData{
		// ESSENTIAL: Core algorithm behavior (for compatibility)
		CurrentAlgorithmType: manager.GetCurrentAlgorithmType(),
		DefaultAlgorithm:     manager.GetDefaultAlgorithm(),
		FallbackAlgorithm:    manager.GetFallbackAlgorithm(),
		RLEnabled:            manager.IsRLEnabled(),
		LearningModeEnabled:  manager.IsLearningModeEnabled(),

		// SKIP: Performance history, switch history (not needed for policy reuse)
		PerformanceHistory:     make([]AlgorithmPerformanceRecord, 0),
		AlgorithmSwitchHistory: make([]AlgorithmSwitchRecord, 0),

		// Basic stats only
		TotalTasksProcessed:   manager.GetTotalTasksProcessed(),
		LastPerformanceUpdate: time.Now(),
	}

	// Update model data
	ms.currentModel.AlgorithmManagerData = amData
	ms.currentModel.Metadata.UpdatedAt = time.Now()

	// Update hyperparameters for compatibility validation
	ms.currentModel.Hyperparams["current_algorithm_type"] = amData.CurrentAlgorithmType
	ms.currentModel.Hyperparams["rl_enabled"] = amData.RLEnabled
	ms.currentModel.Hyperparams["learning_mode_enabled"] = amData.LearningModeEnabled

	// Save to file
	if err := ms.saveToFile(); err != nil {
		return fmt.Errorf("failed to save algorithm manager state: %w", err)
	}

	logger.GetLogger().Info("Algorithm manager essential state saved successfully",
		"current_algorithm", amData.CurrentAlgorithmType,
		"rl_enabled", amData.RLEnabled,
		"learning_mode", amData.LearningModeEnabled)

	return nil
}

// LoadAlgorithmManagerState loads algorithm manager state from persistent storage
func (ms *ModelStorage) LoadAlgorithmManagerState() (*rl.AlgorithmManager, error) {
	ms.mutex.RLock()
	defer ms.mutex.RUnlock()

	if err := ms.loadFromFile(); err != nil {
		return nil, fmt.Errorf("failed to load model data: %w", err)
	}

	if ms.currentModel == nil || ms.currentModel.AlgorithmManagerData == nil {
		return nil, fmt.Errorf("no algorithm manager data found in saved model")
	}

	amData := ms.currentModel.AlgorithmManagerData
	cfg := config.GetConfig()

	// Validate compatibility before creating manager
	if err := ms.validateAlgorithmManagerCompatibility(amData, &cfg); err != nil {
		return nil, fmt.Errorf("algorithm manager compatibility validation failed: %w", err)
	}

	// Create new algorithm manager with loaded essential state
	manager := rl.NewAlgorithmManager(cfg.AlgorithmManager)

	// Restore essential state using setter methods
	manager.SetCurrentAlgorithmType(amData.CurrentAlgorithmType)
	manager.SetRLEnabled(amData.RLEnabled)
	manager.SetLearningModeEnabled(amData.LearningModeEnabled)
	manager.SetTotalTasksProcessed(amData.TotalTasksProcessed)

	logger.GetLogger().Info("Algorithm manager essential state loaded successfully",
		"current_algorithm", amData.CurrentAlgorithmType,
		"rl_enabled", amData.RLEnabled,
		"learning_mode", amData.LearningModeEnabled,
		"total_tasks_processed", amData.TotalTasksProcessed)

	return manager, nil
}

// ValidateAlgorithmManagerCompatibility validates that saved state is compatible with current config
func (ms *ModelStorage) validateAlgorithmManagerCompatibility(savedData *AlgorithmManagerData, currentConfig *config.Config) error {
	// CRITICAL: Algorithm type must be compatible
	if savedData.CurrentAlgorithmType == "qlearning" || savedData.CurrentAlgorithmType == "rl" {
		if !currentConfig.RL.Enabled {
			return fmt.Errorf("saved model uses RL algorithm '%s' but RL is disabled in current config", savedData.CurrentAlgorithmType)
		}
	}

	// CRITICAL: RL mode consistency
	if savedData.RLEnabled != currentConfig.AlgorithmManager.RLEnabled {
		logger.GetLogger().Warn("RL enabled setting differs between saved model and config",
			"saved_rl_enabled", savedData.RLEnabled,
			"config_rl_enabled", currentConfig.AlgorithmManager.RLEnabled)
		// This is a warning, not an error - we'll use the saved value
	}

	// CRITICAL: Check if saved algorithm is available
	availableAlgorithms := []string{"fifo", "priority", "sjf", "resource_aware", "hybrid"}
	if currentConfig.RL.Enabled {
		availableAlgorithms = append(availableAlgorithms, "qlearning", "rl")
	}

	algorithmFound := false
	for _, algo := range availableAlgorithms {
		if algo == savedData.CurrentAlgorithmType {
			algorithmFound = true
			break
		}
	}

	if !algorithmFound {
		return fmt.Errorf("saved algorithm '%s' is not available in current configuration", savedData.CurrentAlgorithmType)
	}

	// CRITICAL: Validate default and fallback algorithms
	if savedData.DefaultAlgorithm != currentConfig.AlgorithmManager.DefaultAlgorithm {
		logger.GetLogger().Warn("Default algorithm differs between saved model and config",
			"saved_default", savedData.DefaultAlgorithm,
			"config_default", currentConfig.AlgorithmManager.DefaultAlgorithm)
	}

	if savedData.FallbackAlgorithm != currentConfig.AlgorithmManager.FallbackAlgorithm {
		logger.GetLogger().Warn("Fallback algorithm differs between saved model and config",
			"saved_fallback", savedData.FallbackAlgorithm,
			"config_fallback", currentConfig.AlgorithmManager.FallbackAlgorithm)
	}

	return nil
}

// GetAlgorithmManagerStats returns statistics about the saved algorithm manager
func (ms *ModelStorage) GetAlgorithmManagerStats() (map[string]interface{}, error) {
	ms.mutex.RLock()
	defer ms.mutex.RUnlock()

	if ms.currentModel == nil || ms.currentModel.AlgorithmManagerData == nil {
		return nil, fmt.Errorf("no algorithm manager data available")
	}

	amData := ms.currentModel.AlgorithmManagerData
	stats := make(map[string]interface{})

	stats["current_algorithm_type"] = amData.CurrentAlgorithmType
	stats["default_algorithm"] = amData.DefaultAlgorithm
	stats["fallback_algorithm"] = amData.FallbackAlgorithm
	stats["rl_enabled"] = amData.RLEnabled
	stats["learning_mode_enabled"] = amData.LearningModeEnabled
	stats["total_tasks_processed"] = amData.TotalTasksProcessed
	stats["last_performance_update"] = amData.LastPerformanceUpdate

	return stats, nil
}

// IsAlgorithmManagerStateSaved checks if algorithm manager data exists in storage
func (ms *ModelStorage) IsAlgorithmManagerStateSaved() bool {
	ms.mutex.RLock()
	defer ms.mutex.RUnlock()

	return ms.currentModel != nil && ms.currentModel.AlgorithmManagerData != nil
}

// SaveMultiObjectiveState extracts and saves essential multi-objective state
func (ms *ModelStorage) SaveMultiObjectiveState(algorithmManager *rl.AlgorithmManager) error {
	if algorithmManager == nil {
		return nil // No multi-objective state to save
	}

	// Get multi-objective stats to check if enabled
	moStats := algorithmManager.GetMultiObjectiveStats()
	if len(moStats) == 0 {
		return nil // Multi-objective not enabled
	}

	// Extract multi-objective calculator for Q-Learning (primary RL algorithm)
	qlearningStats, exists := moStats["qlearning"]
	if !exists {
		return fmt.Errorf("no multi-objective data found for qlearning algorithm")
	}

	_, ok := qlearningStats.(map[string]interface{})
	if !ok {
		return fmt.Errorf("invalid multi-objective stats format")
	}

	// Get the actual multi-objective calculator
	currentAlg := algorithmManager.GetCurrentAlgorithm()
	if currentAlg == nil {
		return fmt.Errorf("no current algorithm available")
	}

	// Check if it's a Q-Learning algorithm with multi-objective support
	if qlAlg, ok := currentAlg.(*rl.QLearningScheduler); ok {
		multiObjCalc := qlAlg.GetMultiObjectiveCalculator()
		if multiObjCalc == nil {
			return nil // Multi-objective not enabled for this algorithm
		}

		// Extract ESSENTIAL STATE ONLY
		moData := &MultiObjectiveData{
			CurrentWeights:      multiObjCalc.GetRewardWeights(),               // CRITICAL: Final adapted weights
			ActiveProfile:       multiObjCalc.GetActiveProfile(),               // Current profile
			ScalarizationMethod: string(multiObjCalc.GetScalarizationMethod()), // Algorithm behavior
			AdaptationEnabled:   true,                                          // Get from config if available
			AdaptationWindow:    50,                                            // Get from config if available
		}

		// Store in model data
		if ms.currentModel == nil {
			ms.currentModel = ms.createDefaultModel()
		}
		ms.currentModel.MultiObjectiveData = moData

		logger.GetLogger().Info("Multi-objective essential state saved",
			"active_profile", moData.ActiveProfile,
			"scalarization_method", moData.ScalarizationMethod,
			"current_weights", fmt.Sprintf("%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
				moData.CurrentWeights.Latency,
				moData.CurrentWeights.Throughput,
				moData.CurrentWeights.ResourceEfficiency,
				moData.CurrentWeights.Fairness,
				moData.CurrentWeights.DeadlineMiss,
				moData.CurrentWeights.EnergyEfficiency))

		return nil
	}

	return fmt.Errorf("current algorithm does not support multi-objective optimization")
}

// LoadMultiObjectiveState restores essential multi-objective state
func (ms *ModelStorage) LoadMultiObjectiveState(algorithmManager *rl.AlgorithmManager) error {
	if ms.currentModel == nil || ms.currentModel.MultiObjectiveData == nil {
		logger.GetLogger().Info("No multi-objective state to restore")
		return nil
	}

	moData := ms.currentModel.MultiObjectiveData

	// Get current algorithm and check if it supports multi-objective
	currentAlg := algorithmManager.GetCurrentAlgorithm()
	if currentAlg == nil {
		return fmt.Errorf("no current algorithm available for multi-objective restoration")
	}

	// Restore multi-objective state if Q-Learning algorithm
	if qlAlg, ok := currentAlg.(*rl.QLearningScheduler); ok {
		multiObjCalc := qlAlg.GetMultiObjectiveCalculator()
		if multiObjCalc == nil {
			logger.GetLogger().Warn("Multi-objective calculator not available, skipping restoration")
			return nil
		}

		// RESTORE ESSENTIAL STATE

		// 1. Restore final adapted weights (CRITICAL)
		multiObjCalc.SetRewardWeights(moData.CurrentWeights)

		// 2. Restore active profile
		err := multiObjCalc.SetActiveProfile(moData.ActiveProfile)
		if err != nil {
			logger.GetLogger().Warn("Failed to restore active profile, using default",
				"profile", moData.ActiveProfile,
				"error", err)
		}

		// 3. Restore scalarization method
		multiObjCalc.SetScalarizationMethod(rl.ScalarizationMethod(moData.ScalarizationMethod))

		logger.GetLogger().Info("Multi-objective essential state restored",
			"active_profile", moData.ActiveProfile,
			"scalarization_method", moData.ScalarizationMethod,
			"restored_weights", fmt.Sprintf("%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
				moData.CurrentWeights.Latency,
				moData.CurrentWeights.Throughput,
				moData.CurrentWeights.ResourceEfficiency,
				moData.CurrentWeights.Fairness,
				moData.CurrentWeights.DeadlineMiss,
				moData.CurrentWeights.EnergyEfficiency))

		return nil
	}

	logger.GetLogger().Info("Current algorithm does not support multi-objective optimization")
	return nil
}

// SaveModel saves the complete model state
func (ms *ModelStorage) SaveModel(algorithmManager *rl.AlgorithmManager) error {
	ms.mutex.Lock()
	defer ms.mutex.Unlock()

	// Initialize model if needed
	if ms.currentModel == nil {
		ms.currentModel = ms.createDefaultModel()
	}

	// Update metadata
	ms.currentModel.Metadata.UpdatedAt = time.Now()

	currentConfig := config.GetConfig()
	ms.currentModel.Config = &currentConfig
	ms.currentModel.ConfigHash = ms.generateConfigurationHash(&currentConfig)

	// Save Q-Learning state
	currentAlg := algorithmManager.GetCurrentAlgorithm()
	if qlAlg, ok := currentAlg.(*rl.QLearningScheduler); ok {
		if err := ms.SaveQLearningAgent(qlAlg); err != nil {
			return fmt.Errorf("failed to save Q-Learning state: %w", err)
		}
	}

	// Save Algorithm Manager state
	if err := ms.SaveAlgorithmManagerState(algorithmManager); err != nil {
		return fmt.Errorf("failed to save algorithm manager state: %w", err)
	}

	// Save Multi-Objective state
	if err := ms.SaveMultiObjectiveState(algorithmManager); err != nil {
		return fmt.Errorf("failed to save multi-objective state: %w", err)
	}

	// Save to file
	return ms.saveToFile()
}

// LoadModel loads the complete model state (Step 6.4)
func (ms *ModelStorage) LoadModel(algorithmManager *rl.AlgorithmManager) error {
	ms.mutex.Lock()
	defer ms.mutex.Unlock()

	// Load from file
	if err := ms.loadFromFile(); err != nil {
		return fmt.Errorf("failed to load model from file: %w", err)
	}

	if err := ms.validateConfigurationCompatibility(ms.currentModel); err != nil {
		return fmt.Errorf("configuration compatibility validation failed: %w", err)
	}

	logger.GetLogger().Info("Configuration compatibility validation passed")

	// Load Q-Learning state
	currentAlg := algorithmManager.GetCurrentAlgorithm()
	if qlAlg, ok := currentAlg.(*rl.QLearningScheduler); ok {
		if ms.currentModel != nil && ms.currentModel.QLearningData != nil {
			// Restore Q-Learning state directly to the algorithm
			qData := ms.currentModel.QLearningData

			qlAlg.SetCurrentEpisode(qData.CurrentEpisode)
			qlAlg.SetEpisodeTaskCount(qData.EpisodeTaskCount)
			qlAlg.SetEpisodeStartTime(qData.EpisodeStartTime)
			qlAlg.SetLastEpisodeReset(qData.LastEpisodeReset)
			qlAlg.SetExplorationRate(qData.ExplorationRate)
			qlAlg.SetLearning(qData.IsLearning)
			qlAlg.SetRewardWeights(qData.RewardWeights)
			qlAlg.SetQTable(ms.convertQTableFromStringFormat(qData.QTable))

			logger.GetLogger().Info("Q-learning state restored to current algorithm")
		}
	}

	// Load Algorithm Manager state (restore essential settings)
	if ms.currentModel != nil && ms.currentModel.AlgorithmManagerData != nil {
		amData := ms.currentModel.AlgorithmManagerData

		algorithmManager.SetCurrentAlgorithmType(amData.CurrentAlgorithmType)
		algorithmManager.SetRLEnabled(amData.RLEnabled)
		algorithmManager.SetLearningModeEnabled(amData.LearningModeEnabled)
		algorithmManager.SetTotalTasksProcessed(amData.TotalTasksProcessed)

		logger.GetLogger().Info("Algorithm manager state restored")
	}

	// Load Multi-Objective state
	if err := ms.LoadMultiObjectiveState(algorithmManager); err != nil {
		return fmt.Errorf("failed to load multi-objective state: %w", err)
	}

	return nil
}

// ============= STEP 6.5: CONFIGURATION COMPATIBILITY VALIDATION =============

// validateConfigurationCompatibility validates that saved config is compatible with current config
func (ms *ModelStorage) validateConfigurationCompatibility(savedModel *ModelData) error {
	if savedModel.Config == nil {
		return fmt.Errorf("saved model configuration is missing")
	}

	currentConfig := config.GetConfig()
	savedConfig := savedModel.Config

	// Validate algorithm type compatibility
	if err := ms.validateAlgorithmTypeCompatibility(savedConfig, &currentConfig); err != nil {
		return fmt.Errorf("algorithm type compatibility failed: %w", err)
	}

	// Validate action space compatibility
	if err := ms.validateActionSpaceCompatibility(); err != nil {
		return fmt.Errorf("action space compatibility failed: %w", err)
	}

	// Validate state feature structure compatibility
	if err := ms.validateStateFeatureCompatibility(savedConfig, &currentConfig); err != nil {
		return fmt.Errorf("state feature compatibility failed: %w", err)
	}

	// Validate RL vs non-RL mode compatibility
	if err := ms.validateRLModeCompatibility(savedConfig, &currentConfig); err != nil {
		return fmt.Errorf("RL mode compatibility failed: %w", err)
	}

	// Validate critical configuration parameters
	if err := ms.validateCriticalParameters(savedConfig, &currentConfig); err != nil {
		return fmt.Errorf("critical parameters compatibility failed: %w", err)
	}

	return nil
}

// validateAlgorithmTypeCompatibility checks algorithm type matches
func (ms *ModelStorage) validateAlgorithmTypeCompatibility(savedConfig, currentConfig *config.Config) error {
	savedAlgorithm := savedConfig.RL.Algorithm
	currentAlgorithm := currentConfig.RL.Algorithm

	if savedAlgorithm != currentAlgorithm {
		return fmt.Errorf("algorithm type mismatch: saved='%s', current='%s'", savedAlgorithm, currentAlgorithm)
	}

	// Validate algorithm manager default algorithm
	savedManagerAlgo := savedConfig.AlgorithmManager.DefaultAlgorithm
	currentManagerAlgo := currentConfig.AlgorithmManager.DefaultAlgorithm

	if savedManagerAlgo != currentManagerAlgo {
		return fmt.Errorf("algorithm manager default algorithm mismatch: saved='%s', current='%s'",
			savedManagerAlgo, currentManagerAlgo)
	}

	// Additional validation for Q-learning specific parameters
	if savedAlgorithm == "qlearning" {
		if err := ms.validateQLearningCompatibility(savedConfig, currentConfig); err != nil {
			return fmt.Errorf("q-learning compatibility failed: %w", err)
		}
	}

	return nil
}

// validateQLearningCompatibility validates Q-learning specific compatibility
func (ms *ModelStorage) validateQLearningCompatibility(savedConfig, currentConfig *config.Config) error {
	// Validate learning parameters are in reasonable range
	savedLR := savedConfig.RL.LearningRate
	currentLR := currentConfig.RL.LearningRate

	if math.Abs(savedLR-currentLR) > 0.5 {
		logger.GetLogger().Warn("Learning rate significantly different",
			"saved", savedLR, "current", currentLR)
	}

	// Validate discount factor
	savedDF := savedConfig.RL.DiscountFactor
	currentDF := currentConfig.RL.DiscountFactor

	if math.Abs(savedDF-currentDF) > 0.2 {
		logger.GetLogger().Warn("Discount factor significantly different",
			"saved", savedDF, "current", currentDF)
	}

	return nil
}

// validateActionSpaceCompatibility validates action space is compatible
func (ms *ModelStorage) validateActionSpaceCompatibility() error {
	// Get all possible actions from current system
	actions := rl.GetAllActions()
	actionCount := len(actions)

	// Validate we have expected action types
	expectedActions := []rl.ActionType{
		rl.ActionNone,
		rl.ActionScheduleNext,
		rl.ActionReorder,
		rl.ActionDelay,
		rl.ActionPriorityBoost,
		rl.ActionPromoteHighPriority,
		rl.ActionPromoteShortJobs,
		rl.ActionBalancedScheduling,
		rl.ActionDeadlineAware,
		rl.ActionResourceOptimized,
	}

	if actionCount != len(expectedActions) {
		return fmt.Errorf("action space size mismatch: expected %d, got %d", len(expectedActions), actionCount)
	}

	// Validate each expected action exists
	actionMap := make(map[rl.ActionType]bool)
	for _, action := range actions {
		actionMap[action.Type] = true
	}

	for _, expectedAction := range expectedActions {
		if !actionMap[expectedAction] {
			return fmt.Errorf("missing expected action type: %d", expectedAction)
		}
	}

	return nil
}

// validateStateFeatureCompatibility validates state feature structure
func (ms *ModelStorage) validateStateFeatureCompatibility(savedConfig, currentConfig *config.Config) error {
	// Validate state discretization configuration
	if savedConfig.RL.StateDiscretization.Enabled != currentConfig.RL.StateDiscretization.Enabled {
		return fmt.Errorf("state discretization enabled mismatch: saved=%t, current=%t",
			savedConfig.RL.StateDiscretization.Enabled, currentConfig.RL.StateDiscretization.Enabled)
	}

	if savedConfig.RL.StateDiscretization.Enabled {
		// Validate category configurations match
		if err := ms.validateCategoryCompatibility("cpu_utilization",
			&savedConfig.RL.StateDiscretization.CPUUtilization,
			&currentConfig.RL.StateDiscretization.CPUUtilization); err != nil {
			return err
		}

		if err := ms.validateCategoryCompatibility("memory_utilization",
			&savedConfig.RL.StateDiscretization.MemoryUtilization,
			&currentConfig.RL.StateDiscretization.MemoryUtilization); err != nil {
			return err
		}

		if err := ms.validateCategoryCompatibility("queue_length",
			&savedConfig.RL.StateDiscretization.QueueLength,
			&currentConfig.RL.StateDiscretization.QueueLength); err != nil {
			return err
		}

		if err := ms.validateCategoryCompatibility("system_load",
			&savedConfig.RL.StateDiscretization.SystemLoad,
			&currentConfig.RL.StateDiscretization.SystemLoad); err != nil {
			return err
		}

		if err := ms.validateCategoryCompatibility("task_priority",
			&savedConfig.RL.StateDiscretization.TaskPriority,
			&currentConfig.RL.StateDiscretization.TaskPriority); err != nil {
			return err
		}
	}

	return nil
}

// validateCategoryCompatibility validates category configuration compatibility
func (ms *ModelStorage) validateCategoryCompatibility(featureName string, savedCategory, currentCategory *config.CategoryConfig) error {
	// Check category count matches
	if len(savedCategory.Categories) != len(currentCategory.Categories) {
		return fmt.Errorf("%s category count mismatch: saved=%d, current=%d",
			featureName, len(savedCategory.Categories), len(currentCategory.Categories))
	}

	// Check boundary count matches
	if len(savedCategory.Boundaries) != len(currentCategory.Boundaries) {
		return fmt.Errorf("%s boundary count mismatch: saved=%d, current=%d",
			featureName, len(savedCategory.Boundaries), len(currentCategory.Boundaries))
	}

	// Check category names match (order matters for state key generation)
	for i, savedCat := range savedCategory.Categories {
		if i >= len(currentCategory.Categories) || savedCat != currentCategory.Categories[i] {
			return fmt.Errorf("%s category name mismatch at index %d: saved='%s', current='%s'",
				featureName, i, savedCat,
				func() string {
					if i < len(currentCategory.Categories) {
						return currentCategory.Categories[i]
					}
					return "missing"
				}())
		}
	}

	// Check boundaries match (with small tolerance for floating point differences)
	const tolerance = 1e-6
	for i, savedBoundary := range savedCategory.Boundaries {
		if i >= len(currentCategory.Boundaries) {
			return fmt.Errorf("%s boundary missing at index %d", featureName, i)
		}
		currentBoundary := currentCategory.Boundaries[i]
		if math.Abs(savedBoundary-currentBoundary) > tolerance {
			return fmt.Errorf("%s boundary value mismatch at index %d: saved=%f, current=%f",
				featureName, i, savedBoundary, currentBoundary)
		}
	}

	return nil
}

// validateRLModeCompatibility validates RL vs non-RL mode matches
func (ms *ModelStorage) validateRLModeCompatibility(savedConfig, currentConfig *config.Config) error {
	// Check main RL enabled flag
	if savedConfig.RL.Enabled != currentConfig.RL.Enabled {
		return fmt.Errorf("RL enabled mismatch: saved=%t, current=%t",
			savedConfig.RL.Enabled, currentConfig.RL.Enabled)
	}

	// Check algorithm manager RL enabled flag
	if savedConfig.AlgorithmManager.RLEnabled != currentConfig.AlgorithmManager.RLEnabled {
		return fmt.Errorf("algorithm manager RL enabled mismatch: saved=%t, current=%t",
			savedConfig.AlgorithmManager.RLEnabled, currentConfig.AlgorithmManager.RLEnabled)
	}

	// If RL is enabled, validate multi-objective configuration
	if savedConfig.RL.Enabled {
		if savedConfig.RL.MultiObjective.Enabled != currentConfig.RL.MultiObjective.Enabled {
			return fmt.Errorf("multi-objective enabled mismatch: saved=%t, current=%t",
				savedConfig.RL.MultiObjective.Enabled, currentConfig.RL.MultiObjective.Enabled)
		}

		if savedConfig.RL.MultiObjective.Enabled {
			// Validate scalarization method compatibility
			if savedConfig.RL.MultiObjective.ScalarizationMethod != currentConfig.RL.MultiObjective.ScalarizationMethod {
				logger.GetLogger().Warn("Scalarization method different",
					"saved", savedConfig.RL.MultiObjective.ScalarizationMethod,
					"current", currentConfig.RL.MultiObjective.ScalarizationMethod)
			}
		}
	}

	return nil
}

// validateCriticalParameters validates critical configuration parameters
func (ms *ModelStorage) validateCriticalParameters(savedConfig, currentConfig *config.Config) error {
	// Validate episode configuration compatibility
	if savedConfig.RL.EpisodeConfig.Type != currentConfig.RL.EpisodeConfig.Type {
		return fmt.Errorf("episode type mismatch: saved='%s', current='%s'",
			savedConfig.RL.EpisodeConfig.Type, currentConfig.RL.EpisodeConfig.Type)
	}

	// Validate memory management strategy
	if savedConfig.RL.MemoryManagement.CleanupStrategy != currentConfig.RL.MemoryManagement.CleanupStrategy {
		logger.GetLogger().Warn("Memory management cleanup strategy different",
			"saved", savedConfig.RL.MemoryManagement.CleanupStrategy,
			"current", currentConfig.RL.MemoryManagement.CleanupStrategy)
	}

	// Validate node configuration
	if savedConfig.SingleNode.NodeID != currentConfig.SingleNode.NodeID {
		logger.GetLogger().Warn("Node ID different",
			"saved", savedConfig.SingleNode.NodeID,
			"current", currentConfig.SingleNode.NodeID)
	}

	return nil
}

// generateConfigurationHash generates a hash of critical configuration parameters
func (ms *ModelStorage) generateConfigurationHash(cfg *config.Config) string {
	// Create hash of critical parameters that affect model compatibility
	criticalParams := fmt.Sprintf("%s_%s_%t_%t_%t_%s_%d_%d",
		cfg.RL.Algorithm,
		cfg.AlgorithmManager.DefaultAlgorithm,
		cfg.RL.Enabled,
		cfg.AlgorithmManager.RLEnabled,
		cfg.RL.StateDiscretization.Enabled,
		cfg.RL.EpisodeConfig.Type,
		len(cfg.RL.StateDiscretization.CPUUtilization.Categories),
		len(cfg.RL.StateDiscretization.CPUUtilization.Boundaries),
	)

	// Add state discretization details to hash
	if cfg.RL.StateDiscretization.Enabled {
		for _, cat := range cfg.RL.StateDiscretization.CPUUtilization.Categories {
			criticalParams += "_" + cat
		}
		for _, boundary := range cfg.RL.StateDiscretization.CPUUtilization.Boundaries {
			criticalParams += fmt.Sprintf("_%.3f", boundary)
		}
	}

	// Generate simple hash (in production, use proper hash function)
	hash := 0
	for _, char := range criticalParams {
		hash = hash*31 + int(char)
	}

	return fmt.Sprintf("%x", hash&0xFFFFFFFF)
}
