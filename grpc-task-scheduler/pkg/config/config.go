package config

import (
	"fmt"
	"log"
	"os"
	"strings"
	"time"

	"github.com/fsnotify/fsnotify"
	"github.com/spf13/viper"
)

// Config holds the application's configuration
type Config struct {
	Server           ServerConfig           `mapstructure:"server"`
	GRPC             GRPCConfig             `mapstructure:"grpc"`
	Logging          LoggingConfig          `mapstructure:"logging"`
	ModelPersistence ModelPersistenceConfig `mapstructure:"model_persistence"`
	Metrics          MetricsConfig          `mapstructure:"metrics"`
	RLAgent          RLAgentConfig          `mapstructure:"rl_agent"` // Legacy
	RL               RLConfig               `mapstructure:"rl"`
	SingleNode       SingleNodeConfig       `mapstructure:"single_node"`
	Queue            QueueConfig            `mapstructure:"queue"`
	AlgorithmManager AlgorithmManagerConfig `mapstructure:"algorithm_manager"`
	Caching          CachingConfig          `mapstructure:"caching"`
}

// ServerConfig contains server-related settings
type ServerConfig struct {
	Host                 string          `mapstructure:"host"`
	Port                 int             `mapstructure:"port"`
	MaxConcurrentStreams int             `mapstructure:"max_concurrent_streams"`
	Keepalive            KeepaliveConfig `mapstructure:"keepalive"`
	ShutdownTimeout      time.Duration   `mapstructure:"shutdown_timeout"`
}

// KeepaliveConfig contains keepalive settings
type KeepaliveConfig struct {
	Time    time.Duration `mapstructure:"time"`
	Timeout time.Duration `mapstructure:"timeout"`
}

// GRPCConfig contains gRPC-specific settings
type GRPCConfig struct {
	ReflectionEnabled  bool `mapstructure:"reflection_enabled"`
	HealthCheckEnabled bool `mapstructure:"health_check_enabled"`
	MaxRecvMsgSize     int  `mapstructure:"max_recv_msg_size"`
	MaxSendMsgSize     int  `mapstructure:"max_send_msg_size"`
}

// LoggingConfig contains logging settings
type LoggingConfig struct {
	Level  string `mapstructure:"level"`
	Format string `mapstructure:"format"`
	Output string `mapstructure:"output"`
}

// ModelPersistenceConfig contains model persistence settings
type ModelPersistenceConfig struct {
	Enabled        bool          `mapstructure:"enabled"`
	ModelName      string        `mapstructure:"model_name"`
	SaveInterval   time.Duration `mapstructure:"save_interval"`
	SaveOnShutdown bool          `mapstructure:"save_on_shutdown"`
	BackupCount    int           `mapstructure:"backup_count"`
	ModelsPath     string        `mapstructure:"models_path"`
}

// MetricsConfig contains metrics collection settings
type MetricsConfig struct {
	Enabled      bool   `mapstructure:"enabled"`
	Port         int    `mapstructure:"port"`
	Path         string `mapstructure:"path"`
	InMemoryOnly bool   `mapstructure:"in_memory_only"`
}

// RLAgentConfig contains legacy RL agent settings (for compatibility)
type RLAgentConfig struct {
	Enabled      bool    `mapstructure:"enabled"`
	Algorithm    string  `mapstructure:"algorithm"`
	LearningRate float64 `mapstructure:"learning_rate"`
	BatchSize    int     `mapstructure:"batch_size"`
	MemorySize   int     `mapstructure:"memory_size"`
}

// LearningConfig contains learning behavior settings
type LearningConfig struct {
	ImmediateUpdates bool `mapstructure:"immediate_updates"`
	BatchLearning    bool `mapstructure:"batch_learning"`
}

// EpisodeConfig contains episode definition settings
type EpisodeConfig struct {
	Type                  string `mapstructure:"type"`
	TasksPerEpisode       int    `mapstructure:"tasks_per_episode"`
	TimePerEpisodeMinutes int    `mapstructure:"time_per_episode_minutes"`
	ResetOnEpisodeEnd     bool   `mapstructure:"reset_on_episode_end"`
}

// CategoryConfig contains fuzzy category configuration
type CategoryConfig struct {
	Categories []string  `mapstructure:"categories"`
	Boundaries []float64 `mapstructure:"boundaries"`
}

// ValidateCategoryConfig validates fuzzy category configuration
func (cc *CategoryConfig) ValidateCategoryConfig() error {
	if len(cc.Categories) == 0 {
		return fmt.Errorf("categories cannot be empty")
	}
	if len(cc.Boundaries) != len(cc.Categories)-1 {
		return fmt.Errorf("boundaries count must be categories count - 1")
	}

	// Check boundaries are in ascending order
	for i := 1; i < len(cc.Boundaries); i++ {
		if cc.Boundaries[i] <= cc.Boundaries[i-1] {
			return fmt.Errorf("boundaries must be in ascending order")
		}
	}
	return nil
}

// GetCategoryIndex returns the category index for a given value
func (cc *CategoryConfig) GetCategoryIndex(value float64) int {
	for i, boundary := range cc.Boundaries {
		if value <= boundary {
			return i
		}
	}
	return len(cc.Categories) - 1
}

// GetCategoryName returns the category name for a given value
func (cc *CategoryConfig) GetCategoryName(value float64) string {
	index := cc.GetCategoryIndex(value)
	if index < len(cc.Categories) {
		return cc.Categories[index]
	}
	return cc.Categories[len(cc.Categories)-1]
}

// StateDiscretizationConfig contains state discretization settings
type StateDiscretizationConfig struct {
	Enabled           bool           `mapstructure:"enabled"`
	CPUUtilization    CategoryConfig `mapstructure:"cpu_utilization"`
	MemoryUtilization CategoryConfig `mapstructure:"memory_utilization"`
	QueueLength       CategoryConfig `mapstructure:"queue_length"`
	SystemLoad        CategoryConfig `mapstructure:"system_load"`
	TaskPriority      CategoryConfig `mapstructure:"task_priority"`
	CacheRatio        CategoryConfig `mapstructure:"cache_ratio"`
}

// MemoryManagementConfig contains memory management settings
type MemoryManagementConfig struct {
	Enabled                 bool    `mapstructure:"enabled"`
	CleanupStrategy         string  `mapstructure:"cleanup_strategy"`
	StabilityThreshold      float64 `mapstructure:"stability_threshold"`
	StabilityWindow         int     `mapstructure:"stability_window"`
	MaxExperiences          int     `mapstructure:"max_experiences"`
	CleanupIntervalEpisodes int     `mapstructure:"cleanup_interval_episodes"`
	PreserveRecentEpisodes  int     `mapstructure:"preserve_recent_episodes"`

	// Enhanced Memory Management Configuration
	ExperienceTimeoutMinutes              time.Duration `mapstructure:"experience_timeout_minutes"`
	EstimatedBytesPerExperience           int           `mapstructure:"estimated_bytes_per_experience"`
	EstimatedBytesPerIncompleteExperience int           `mapstructure:"estimated_bytes_per_incomplete_experience"`
	EmergencyCleanupThresholdMB           int           `mapstructure:"emergency_cleanup_threshold_mb"`
	MinHistorySize                        int           `mapstructure:"min_history_size"`
	StateKeyOverhead                      int           `mapstructure:"state_key_overhead"`
	StabilityTrackerOverheadBytes         int           `mapstructure:"stability_tracker_overhead_bytes"`
	UnusedHistoryCleanupHours             int           `mapstructure:"unused_history_cleanup_hours"`
}

// RLConfig contains reinforcement learning settings
type RLConfig struct {
	Enabled             bool                      `mapstructure:"enabled"`
	Algorithm           string                    `mapstructure:"algorithm"`
	LearningRate        float64                   `mapstructure:"learning_rate"`
	DiscountFactor      float64                   `mapstructure:"discount_factor"`
	ExplorationRate     float64                   `mapstructure:"exploration_rate"`
	ExplorationDecay    float64                   `mapstructure:"exploration_decay"`
	MinExploration      float64                   `mapstructure:"min_exploration"`
	ExperienceSize      int                       `mapstructure:"experience_size"`
	BatchSize           int                       `mapstructure:"batch_size"`
	Learning            LearningConfig            `mapstructure:"learning"`
	EpisodeConfig       EpisodeConfig             `mapstructure:"episode_config"`
	StateDiscretization StateDiscretizationConfig `mapstructure:"state_discretization"`
	MemoryManagement    MemoryManagementConfig    `mapstructure:"memory_management"`
	MultiObjective      MultiObjectiveConfig      `mapstructure:"multi_objective"`
	RewardWeights       RewardWeights             `mapstructure:"reward_weights"`
}

// MultiObjectiveConfig contains multi-objective optimization settings
type MultiObjectiveConfig struct {
	Enabled             bool                        `mapstructure:"enabled"`
	ActiveProfile       string                      `mapstructure:"active_profile"`
	ScalarizationMethod string                      `mapstructure:"scalarization_method"`
	AdaptationEnabled   bool                        `mapstructure:"adaptation_enabled"`
	AdaptationWindow    int                         `mapstructure:"adaptation_window"`
	Profiles            map[string]ObjectiveProfile `mapstructure:"profiles"`
}

// ObjectiveProfile contains a named set of objective weights
type ObjectiveProfile struct {
	Description string        `mapstructure:"description"`
	Weights     RewardWeights `mapstructure:"weights"`
}

// RewardWeights contains weights for different reward components
type RewardWeights struct {
	Latency            float64 `mapstructure:"latency"`
	Throughput         float64 `mapstructure:"throughput"`
	ResourceEfficiency float64 `mapstructure:"resource_efficiency"`
	Fairness           float64 `mapstructure:"fairness"`
	DeadlineMiss       float64 `mapstructure:"deadline_miss"`
	EnergyEfficiency   float64 `mapstructure:"energy_efficiency"`
}

// Normalize ensures reward weights sum to 1.0
func (rw *RewardWeights) Normalize() {
	total := rw.Latency + rw.Throughput + rw.ResourceEfficiency +
		rw.Fairness + rw.DeadlineMiss + rw.EnergyEfficiency

	if total > 0 {
		rw.Latency /= total
		rw.Throughput /= total
		rw.ResourceEfficiency /= total
		rw.Fairness /= total
		rw.DeadlineMiss /= total
		rw.EnergyEfficiency /= total
	}
}

// SingleNodeConfig contains single node settings
type SingleNodeConfig struct {
	NodeID             string `mapstructure:"node_id"`
	NodeName           string `mapstructure:"node_name"`
	MaxConcurrentTasks int    `mapstructure:"max_concurrent_tasks"`
	DefaultAlgorithm   string `mapstructure:"default_algorithm"`
	DefaultObjective   string `mapstructure:"default_objective"`
}

// QueueConfig contains queue management settings
type QueueConfig struct {
	MaxQueueSize         int           `mapstructure:"max_queue_size"`
	TaskTimeout          time.Duration `mapstructure:"task_timeout"`
	CleanupInterval      time.Duration `mapstructure:"cleanup_interval"`
	RetryAttempts        int           `mapstructure:"retry_attempts"`
	ResortIntervalMs     int           `mapstructure:"resort_interval_ms"`
	EnablePeriodicResort bool          `mapstructure:"enable_periodic_resort"`
}

// AlgorithmManagerConfig contains algorithm manager settings
type AlgorithmManagerConfig struct {
	DefaultAlgorithm          string                        `mapstructure:"default_algorithm"`
	FallbackAlgorithm         string                        `mapstructure:"fallback_algorithm"`
	RLEnabled                 bool                          `mapstructure:"rl_enabled"`
	AutoSwitch                bool                          `mapstructure:"auto_switch"`
	PerformanceWindow         int                           `mapstructure:"performance_window"`
	SwitchThreshold           float64                       `mapstructure:"switch_threshold"`
	EvaluationInterval        time.Duration                 `mapstructure:"evaluation_interval"`
	ObjectiveAwareSelection   bool                          `mapstructure:"objective_aware_selection"`
	AlgorithmObjectiveFitness map[string]map[string]float64 `mapstructure:"algorithm_objective_fitness"`
	QLearningConfig           RLConfig                      `mapstructure:"qlearning_config"`
	RewardWeights             RewardWeights                 `mapstructure:"reward_weights"`
}

// CachingConfig contains task caching settings
type CachingConfig struct {
	Enabled                bool `mapstructure:"enabled"`
	RepeatThreshold        int  `mapstructure:"repeat_threshold"`
	CacheTTLHours          int  `mapstructure:"cache_ttl_hours"`
	MaxTrackedTasks        int  `mapstructure:"max_tracked_tasks"`
	CleanupIntervalMinutes int  `mapstructure:"cleanup_interval_minutes"`
}

var AppConfig Config

// LoadConfig loads configuration from file and environment variables
func LoadConfig(configPath string) error {
	// Set config file path
	if configPath != "" {
		viper.SetConfigFile(configPath)
	} else {
		// Default config locations
		viper.SetConfigName("config")
		viper.SetConfigType("yaml")
		viper.AddConfigPath(".")
		viper.AddConfigPath("./config")
		viper.AddConfigPath("/etc/scheduler/")
	}

	// Enable environment variable support
	viper.AutomaticEnv()
	viper.SetEnvPrefix("SCHEDULER")
	viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))

	// Set default values
	setDefaults()

	// Read configuration file
	if err := viper.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); ok {
			log.Printf("Config file not found, using defaults and environment variables")
		} else {
			return fmt.Errorf("failed to read config file: %w", err)
		}
	} else {
		log.Printf("Using config file: %s", viper.ConfigFileUsed())
	}

	// Unmarshal configuration
	if err := viper.Unmarshal(&AppConfig); err != nil {
		return fmt.Errorf("failed to unmarshal config: %w", err)
	}

	// Validate configuration
	if err := validateConfig(); err != nil {
		return fmt.Errorf("config validation failed: %w", err)
	}

	// Normalize reward weights
	AppConfig.RL.RewardWeights.Normalize()
	for profileName := range AppConfig.RL.MultiObjective.Profiles {
		profile := AppConfig.RL.MultiObjective.Profiles[profileName]
		profile.Weights.Normalize()
		AppConfig.RL.MultiObjective.Profiles[profileName] = profile
	}

	log.Println("Configuration loaded successfully")
	return nil
}

// setDefaults sets default configuration values
func setDefaults() {
	// Server defaults
	viper.SetDefault("server.host", "0.0.0.0")
	viper.SetDefault("server.port", 40040)
	viper.SetDefault("server.max_concurrent_streams", 1000)
	viper.SetDefault("server.keepalive.time", "30s")
	viper.SetDefault("server.keepalive.timeout", "5s")
	viper.SetDefault("server.shutdown_timeout", "30s")

	// gRPC defaults
	viper.SetDefault("grpc.reflection_enabled", true)
	viper.SetDefault("grpc.health_check_enabled", true)
	viper.SetDefault("grpc.max_recv_msg_size", 4194304)
	viper.SetDefault("grpc.max_send_msg_size", 4194304)

	// Logging defaults
	viper.SetDefault("logging.level", "info")
	viper.SetDefault("logging.format", "json")
	viper.SetDefault("logging.output", "stdout")

	// Model persistence defaults
	viper.SetDefault("model_persistence.enabled", true)
	viper.SetDefault("model_persistence.model_name", "task_scheduler_v1")
	viper.SetDefault("model_persistence.save_interval", "10m")
	viper.SetDefault("model_persistence.save_on_shutdown", true)
	viper.SetDefault("model_persistence.backup_count", 3)
	viper.SetDefault("model_persistence.models_path", "./models")

	// Metrics defaults
	viper.SetDefault("metrics.enabled", true)
	viper.SetDefault("metrics.port", 9090)
	viper.SetDefault("metrics.path", "/metrics")
	viper.SetDefault("metrics.in_memory_only", true)

	// Legacy RL Agent defaults
	viper.SetDefault("rl_agent.enabled", false)
	viper.SetDefault("rl_agent.algorithm", "dqn")
	viper.SetDefault("rl_agent.learning_rate", 0.001)
	viper.SetDefault("rl_agent.batch_size", 32)
	viper.SetDefault("rl_agent.memory_size", 10000)

	// RL defaults
	viper.SetDefault("rl.enabled", true)
	viper.SetDefault("rl.algorithm", "qlearning")
	viper.SetDefault("rl.learning_rate", 0.1)
	viper.SetDefault("rl.discount_factor", 0.95)
	viper.SetDefault("rl.exploration_rate", 0.3)
	viper.SetDefault("rl.exploration_decay", 0.995)
	viper.SetDefault("rl.min_exploration", 0.05)
	viper.SetDefault("rl.experience_size", 10000)
	viper.SetDefault("rl.batch_size", 32)

	// Learning behavior defaults
	viper.SetDefault("rl.learning.immediate_updates", true)
	viper.SetDefault("rl.learning.batch_learning", false)

	// Episode configuration defaults
	viper.SetDefault("rl.episode_config.type", "task_based")
	viper.SetDefault("rl.episode_config.tasks_per_episode", 20)
	viper.SetDefault("rl.episode_config.time_per_episode_minutes", 5)
	viper.SetDefault("rl.episode_config.reset_on_episode_end", false)

	// State discretization defaults
	viper.SetDefault("rl.state_discretization.enabled", true)
	viper.SetDefault("rl.state_discretization.cpu_utilization.categories", []string{"low", "medium", "high", "critical"})
	viper.SetDefault("rl.state_discretization.cpu_utilization.boundaries", []float64{0.3, 0.6, 0.85})
	viper.SetDefault("rl.state_discretization.memory_utilization.categories", []string{"low", "medium", "high", "critical"})
	viper.SetDefault("rl.state_discretization.memory_utilization.boundaries", []float64{0.4, 0.7, 0.9})
	viper.SetDefault("rl.state_discretization.queue_length.categories", []string{"empty", "light", "moderate", "heavy"})
	viper.SetDefault("rl.state_discretization.queue_length.boundaries", []float64{2, 5, 10})
	viper.SetDefault("rl.state_discretization.system_load.categories", []string{"idle", "normal", "busy", "overloaded"})
	viper.SetDefault("rl.state_discretization.system_load.boundaries", []float64{0.25, 0.65, 0.9})
	viper.SetDefault("rl.state_discretization.task_priority.categories", []string{"low", "normal", "high", "critical"})
	viper.SetDefault("rl.state_discretization.task_priority.boundaries", []float64{3, 6, 8})

	// Memory management defaults - ENHANCED with new configurable values
	viper.SetDefault("rl.memory_management.enabled", true)
	viper.SetDefault("rl.memory_management.cleanup_strategy", "stability_based")
	viper.SetDefault("rl.memory_management.stability_threshold", 0.01)
	viper.SetDefault("rl.memory_management.stability_window", 20)    // Doubled from 10
	viper.SetDefault("rl.memory_management.max_experiences", 100000) // Doubled from 50000
	viper.SetDefault("rl.memory_management.cleanup_interval_episodes", 5)
	viper.SetDefault("rl.memory_management.preserve_recent_episodes", 6) // Doubled from 3

	// New enhanced memory management defaults
	viper.SetDefault("rl.memory_management.experience_timeout_minutes", 10*time.Minute)      // 10 minutes timeout (doubled from 5)
	viper.SetDefault("rl.memory_management.estimated_bytes_per_experience", 2400)            // 2.4KB per experience (doubled from 1200)
	viper.SetDefault("rl.memory_management.estimated_bytes_per_incomplete_experience", 1200) // 1.2KB per incomplete (doubled from 600)
	viper.SetDefault("rl.memory_management.emergency_cleanup_threshold_mb", 100)             // 100MB emergency threshold (doubled from 50)
	viper.SetDefault("rl.memory_management.min_history_size", 6)                             // Minimum 6 Q-values (doubled from 3)
	viper.SetDefault("rl.memory_management.state_key_overhead", 16)                          // 16 bytes overhead (doubled from 8)
	viper.SetDefault("rl.memory_management.stability_tracker_overhead_bytes", 200)           // 200 bytes per tracker (doubled from 100)
	viper.SetDefault("rl.memory_management.unused_history_cleanup_hours", 2)                 // 2 hours cleanup (doubled from 1)

	// Multi-objective defaults
	viper.SetDefault("rl.multi_objective.enabled", true)
	viper.SetDefault("rl.multi_objective.active_profile", "balanced")
	viper.SetDefault("rl.multi_objective.scalarization_method", "weighted_sum")
	viper.SetDefault("rl.multi_objective.adaptation_enabled", true)
	viper.SetDefault("rl.multi_objective.adaptation_window", 50)

	// Reward weights defaults
	viper.SetDefault("rl.reward_weights.latency", 0.4)
	viper.SetDefault("rl.reward_weights.throughput", 0.3)
	viper.SetDefault("rl.reward_weights.resource_efficiency", 0.2)
	viper.SetDefault("rl.reward_weights.fairness", 0.1)
	viper.SetDefault("rl.reward_weights.deadline_miss", 0.0)
	viper.SetDefault("rl.reward_weights.energy_efficiency", 0.0)

	// Single node defaults
	viper.SetDefault("single_node.node_id", "fog_node_001")
	viper.SetDefault("single_node.node_name", "Primary Fog Node")
	viper.SetDefault("single_node.max_concurrent_tasks", 8)
	viper.SetDefault("single_node.default_algorithm", "fifo")
	viper.SetDefault("single_node.default_objective", "balanced")

	// Queue defaults
	viper.SetDefault("queue.max_queue_size", 500)
	viper.SetDefault("queue.task_timeout", "5m")
	viper.SetDefault("queue.cleanup_interval", "1h")
	viper.SetDefault("queue.retry_attempts", 3)
	viper.SetDefault("queue.resort_interval_ms", 100)
	viper.SetDefault("queue.enable_periodic_resort", true)

	// Algorithm manager defaults
	viper.SetDefault("algorithm_manager.default_algorithm", "qlearning")
	viper.SetDefault("algorithm_manager.fallback_algorithm", "priority")
	viper.SetDefault("algorithm_manager.rl_enabled", true)
	viper.SetDefault("algorithm_manager.auto_switch", false)
	viper.SetDefault("algorithm_manager.performance_window", 100)
	viper.SetDefault("algorithm_manager.switch_threshold", 0.05)
	viper.SetDefault("algorithm_manager.evaluation_interval", "5m")
	viper.SetDefault("algorithm_manager.objective_aware_selection", true)

	// Algorithm manager qlearning config defaults
	viper.SetDefault("algorithm_manager.qlearning_config.enabled", true)
	viper.SetDefault("algorithm_manager.qlearning_config.algorithm", "qlearning")
	viper.SetDefault("algorithm_manager.qlearning_config.learning_rate", 0.1)
	viper.SetDefault("algorithm_manager.qlearning_config.discount_factor", 0.95)
	viper.SetDefault("algorithm_manager.qlearning_config.exploration_rate", 0.3)
	viper.SetDefault("algorithm_manager.qlearning_config.exploration_decay", 0.995)
	viper.SetDefault("algorithm_manager.qlearning_config.min_exploration", 0.05)
	viper.SetDefault("algorithm_manager.qlearning_config.experience_size", 10000)
	viper.SetDefault("algorithm_manager.qlearning_config.batch_size", 32)

	// Algorithm manager reward weights defaults
	viper.SetDefault("algorithm_manager.reward_weights.latency", 0.4)
	viper.SetDefault("algorithm_manager.reward_weights.throughput", 0.3)
	viper.SetDefault("algorithm_manager.reward_weights.resource_efficiency", 0.2)
	viper.SetDefault("algorithm_manager.reward_weights.fairness", 0.1)
	viper.SetDefault("algorithm_manager.reward_weights.deadline_miss", 0.0)
	viper.SetDefault("algorithm_manager.reward_weights.energy_efficiency", 0.0)
}

// validateConfig validates the loaded configuration
func validateConfig() error {
	// Validate server config
	if AppConfig.Server.Port <= 0 || AppConfig.Server.Port > 65535 {
		return fmt.Errorf("invalid server port: %d", AppConfig.Server.Port)
	}

	// Validate RL parameters
	if AppConfig.RL.Enabled {
		if AppConfig.RL.LearningRate <= 0 || AppConfig.RL.LearningRate > 1 {
			return fmt.Errorf("invalid learning rate: %f", AppConfig.RL.LearningRate)
		}
		if AppConfig.RL.DiscountFactor <= 0 || AppConfig.RL.DiscountFactor > 1 {
			return fmt.Errorf("invalid discount factor: %f", AppConfig.RL.DiscountFactor)
		}
		if AppConfig.RL.ExplorationRate < 0 || AppConfig.RL.ExplorationRate > 1 {
			return fmt.Errorf("invalid exploration rate: %f", AppConfig.RL.ExplorationRate)
		}

		// Validate state discretization categories
		if AppConfig.RL.StateDiscretization.Enabled {
			if err := AppConfig.RL.StateDiscretization.CPUUtilization.ValidateCategoryConfig(); err != nil {
				return fmt.Errorf("invalid CPU utilization categories: %w", err)
			}
			if err := AppConfig.RL.StateDiscretization.MemoryUtilization.ValidateCategoryConfig(); err != nil {
				return fmt.Errorf("invalid memory utilization categories: %w", err)
			}
			if err := AppConfig.RL.StateDiscretization.QueueLength.ValidateCategoryConfig(); err != nil {
				return fmt.Errorf("invalid queue length categories: %w", err)
			}
			if err := AppConfig.RL.StateDiscretization.SystemLoad.ValidateCategoryConfig(); err != nil {
				return fmt.Errorf("invalid system load categories: %w", err)
			}
			if err := AppConfig.RL.StateDiscretization.TaskPriority.ValidateCategoryConfig(); err != nil {
				return fmt.Errorf("invalid task priority categories: %w", err)
			}
			if err := AppConfig.RL.StateDiscretization.CacheRatio.ValidateCategoryConfig(); err != nil {
				return fmt.Errorf("invalid cache ratio categories: %w", err)
			}
		}

		// Validate episode configuration
		if AppConfig.RL.EpisodeConfig.Type != "task_based" && AppConfig.RL.EpisodeConfig.Type != "time_based" {
			return fmt.Errorf("invalid episode type: %s (must be 'task_based' or 'time_based')", AppConfig.RL.EpisodeConfig.Type)
		}
		if AppConfig.RL.EpisodeConfig.Type == "task_based" && AppConfig.RL.EpisodeConfig.TasksPerEpisode <= 0 {
			return fmt.Errorf("invalid tasks per episode: %d", AppConfig.RL.EpisodeConfig.TasksPerEpisode)
		}
		if AppConfig.RL.EpisodeConfig.Type == "time_based" && AppConfig.RL.EpisodeConfig.TimePerEpisodeMinutes <= 0 {
			return fmt.Errorf("invalid time per episode: %d", AppConfig.RL.EpisodeConfig.TimePerEpisodeMinutes)
		}

		// Validate memory management configuration - ENHANCED validation
		if AppConfig.RL.MemoryManagement.Enabled {
			validStrategies := []string{"stability_based", "age_based", "size_based"}
			valid := false
			for _, strategy := range validStrategies {
				if AppConfig.RL.MemoryManagement.CleanupStrategy == strategy {
					valid = true
					break
				}
			}
			if !valid {
				return fmt.Errorf("invalid cleanup strategy: %s", AppConfig.RL.MemoryManagement.CleanupStrategy)
			}

			// Validate new memory management parameters
			if AppConfig.RL.MemoryManagement.EmergencyCleanupThresholdMB <= 0 {
				return fmt.Errorf("invalid emergency cleanup threshold: %d MB", AppConfig.RL.MemoryManagement.EmergencyCleanupThresholdMB)
			}
			if AppConfig.RL.MemoryManagement.MinHistorySize <= 0 {
				return fmt.Errorf("invalid min history size: %d", AppConfig.RL.MemoryManagement.MinHistorySize)
			}
			if AppConfig.RL.MemoryManagement.ExperienceTimeoutMinutes <= 0 {
				return fmt.Errorf("invalid experience timeout: %v minutes", AppConfig.RL.MemoryManagement.ExperienceTimeoutMinutes)
			}
		}
	}

	// Validate node resources
	if AppConfig.SingleNode.MaxConcurrentTasks <= 0 {
		return fmt.Errorf("invalid max concurrent tasks: %d", AppConfig.SingleNode.MaxConcurrentTasks)
	}

	// Validate queue config
	if AppConfig.Queue.MaxQueueSize <= 0 {
		return fmt.Errorf("invalid queue max size: %d", AppConfig.Queue.MaxQueueSize)
	}

	// Validate multi-objective config
	if AppConfig.RL.MultiObjective.Enabled {
		if AppConfig.RL.MultiObjective.ActiveProfile == "" {
			return fmt.Errorf("active profile must be specified when multi-objective is enabled")
		}
		if _, exists := AppConfig.RL.MultiObjective.Profiles[AppConfig.RL.MultiObjective.ActiveProfile]; !exists {
			return fmt.Errorf("active profile '%s' not found in profiles", AppConfig.RL.MultiObjective.ActiveProfile)
		}
	}

	if err := ValidateEpisodeConfiguration(&AppConfig.RL.EpisodeConfig); err != nil {
		return fmt.Errorf("episode configuration validation failed: %w", err)
	}

	if err := ValidateMemoryManagementLimits(&AppConfig.RL.MemoryManagement); err != nil {
		return fmt.Errorf("memory management limits validation failed: %w", err)
	}

	if err := ValidateFuzzyCategoryBoundaries(&AppConfig.RL.StateDiscretization); err != nil {
		return fmt.Errorf("fuzzy category boundaries validation failed: %w", err)
	}

	if err := ValidateCachingConfiguration(&AppConfig.Caching); err != nil {
		return fmt.Errorf("caching configuration validation failed: %w", err)
	}

	if err := ValidateConfigurationConsistency(&AppConfig); err != nil {
		return fmt.Errorf("configuration consistency validation failed: %w", err)
	}

	return nil
}

// ValidateEpisodeConfiguration validates episode configuration for edge cases
func ValidateEpisodeConfiguration(config *EpisodeConfig) error {
	if config.Type != "task_based" && config.Type != "time_based" {
		return fmt.Errorf("invalid episode type: %s (must be 'task_based' or 'time_based')", config.Type)
	}

	// Edge case validation for task-based episodes
	if config.Type == "task_based" {
		if config.TasksPerEpisode <= 0 {
			return fmt.Errorf("tasks per episode must be positive, got %d", config.TasksPerEpisode)
		}
		if config.TasksPerEpisode > 10000 {
			return fmt.Errorf("tasks per episode too high (max 10000), got %d", config.TasksPerEpisode)
		}
		if config.TasksPerEpisode < 5 {
			return fmt.Errorf("tasks per episode too low (min 5), got %d", config.TasksPerEpisode)
		}
	}

	// Edge case validation for time-based episodes
	if config.Type == "time_based" {
		if config.TimePerEpisodeMinutes <= 0 {
			return fmt.Errorf("time per episode must be positive, got %d minutes", config.TimePerEpisodeMinutes)
		}
		if config.TimePerEpisodeMinutes > 1440 { // 24 hours max
			return fmt.Errorf("time per episode too high (max 1440 minutes), got %d", config.TimePerEpisodeMinutes)
		}
		if config.TimePerEpisodeMinutes < 1 {
			return fmt.Errorf("time per episode too low (min 1 minute), got %d", config.TimePerEpisodeMinutes)
		}
	}

	return nil
}

// ValidateMemoryManagementLimits validates memory management for stress test scenarios
func ValidateMemoryManagementLimits(config *MemoryManagementConfig) error {
	if !config.Enabled {
		return nil
	}

	// Validate cleanup strategy
	validStrategies := []string{"stability_based", "age_based", "size_based"}
	valid := false
	for _, strategy := range validStrategies {
		if config.CleanupStrategy == strategy {
			valid = true
			break
		}
	}
	if !valid {
		return fmt.Errorf("invalid cleanup strategy: %s (must be one of %v)", config.CleanupStrategy, validStrategies)
	}

	// Stress test validation - memory limits
	if config.MaxExperiences <= 0 {
		return fmt.Errorf("max experiences must be positive, got %d", config.MaxExperiences)
	}
	if config.MaxExperiences > 1000000 { // 1M max for stress testing
		return fmt.Errorf("max experiences too high for stress testing (max 1M), got %d", config.MaxExperiences)
	}

	// Validate stability parameters
	if config.StabilityThreshold <= 0 || config.StabilityThreshold > 1.0 {
		return fmt.Errorf("stability threshold must be between 0 and 1, got %f", config.StabilityThreshold)
	}
	if config.StabilityWindow <= 0 || config.StabilityWindow > 1000 {
		return fmt.Errorf("stability window must be between 1 and 1000, got %d", config.StabilityWindow)
	}

	// Validate cleanup intervals
	if config.CleanupIntervalEpisodes <= 0 {
		return fmt.Errorf("cleanup interval episodes must be positive, got %d", config.CleanupIntervalEpisodes)
	}
	if config.PreserveRecentEpisodes < 0 {
		return fmt.Errorf("preserve recent episodes cannot be negative, got %d", config.PreserveRecentEpisodes)
	}

	// Enhanced validation for new fields
	if config.EmergencyCleanupThresholdMB <= 0 || config.EmergencyCleanupThresholdMB > 10240 { // Max 10GB
		return fmt.Errorf("emergency cleanup threshold must be between 1MB and 10GB, got %d MB", config.EmergencyCleanupThresholdMB)
	}

	if config.EstimatedBytesPerExperience <= 0 || config.EstimatedBytesPerExperience > 100000 { // Max 100KB per experience
		return fmt.Errorf("estimated bytes per experience must be between 1 and 100000, got %d", config.EstimatedBytesPerExperience)
	}

	if config.MinHistorySize <= 0 || config.MinHistorySize > 100 {
		return fmt.Errorf("min history size must be between 1 and 100, got %d", config.MinHistorySize)
	}

	// Cross-validation: preserve recent episodes shouldn't exceed cleanup interval
	if config.PreserveRecentEpisodes > config.CleanupIntervalEpisodes*2 {
		return fmt.Errorf("preserve recent episodes (%d) should not exceed 2x cleanup interval (%d)",
			config.PreserveRecentEpisodes, config.CleanupIntervalEpisodes)
	}

	return nil
}

// ValidateFuzzyCategoryBoundaries validates fuzzy category boundaries with edge cases
func ValidateFuzzyCategoryBoundaries(config *StateDiscretizationConfig) error {
	if !config.Enabled {
		return nil
	}

	// Validate each category configuration with specific boundary checks
	categories := map[string]*CategoryConfig{
		"cpu_utilization":    &config.CPUUtilization,
		"memory_utilization": &config.MemoryUtilization,
		"queue_length":       &config.QueueLength,
		"system_load":        &config.SystemLoad,
		"task_priority":      &config.TaskPriority,
		"cache_ratio":        &config.CacheRatio,
	}

	for name, categoryConfig := range categories {
		if err := categoryConfig.ValidateCategoryConfig(); err != nil {
			return fmt.Errorf("%s categories invalid: %w", name, err)
		}

		// Edge case validation: check for reasonable boundary values
		if err := validateCategoryBoundaryRanges(name, categoryConfig); err != nil {
			return fmt.Errorf("%s boundary validation failed: %w", name, err)
		}

		// Edge case: minimum 2 categories required
		if len(categoryConfig.Categories) < 2 {
			return fmt.Errorf("%s must have at least 2 categories, got %d", name, len(categoryConfig.Categories))
		}

		// Edge case: maximum reasonable categories
		if len(categoryConfig.Categories) > 10 {
			return fmt.Errorf("%s has too many categories (max 10), got %d", name, len(categoryConfig.Categories))
		}
	}

	return nil
}

// validateCategoryBoundaryRanges validates boundary ranges are reasonable for each feature type
func validateCategoryBoundaryRanges(featureName string, config *CategoryConfig) error {
	switch featureName {
	case "cpu_utilization", "memory_utilization", "system_load":
		// These should be percentages (0-100)
		for i, boundary := range config.Boundaries {
			if boundary < 0 || boundary > 100 {
				return fmt.Errorf("boundary %d value %f is outside valid range [0,100] for %s", i, boundary, featureName)
			}
		}
	case "queue_length":
		// Queue length should be reasonable positive integers
		for i, boundary := range config.Boundaries {
			if boundary < 0 || boundary > 1000 {
				return fmt.Errorf("boundary %d value %f is outside valid range [0,1000] for queue length", i, boundary)
			}
		}
	case "task_priority":
		// Task priority typically ranges 1-10
		for i, boundary := range config.Boundaries {
			if boundary < 1 || boundary > 10 {
				return fmt.Errorf("boundary %d value %f is outside valid range [1,10] for task priority", i, boundary)
			}
		}
	}

	// Check for minimum gap between boundaries to avoid overlap issues
	for i := 1; i < len(config.Boundaries); i++ {
		gap := config.Boundaries[i] - config.Boundaries[i-1]
		minGap := 0.01 // Minimum 1% gap for percentages, 0.01 for others
		if featureName == "queue_length" || featureName == "task_priority" {
			minGap = 1.0 // Minimum gap of 1 for discrete values
		}

		if gap < minGap {
			return fmt.Errorf("boundaries %d and %d are too close (gap=%f, min=%f) for %s",
				i-1, i, gap, minGap, featureName)
		}
	}

	return nil
}

// ValidateConfigurationConsistency validates cross-dependencies between config sections
func ValidateConfigurationConsistency(config *Config) error {
	// Validate RL and Algorithm Manager consistency
	if config.RL.Enabled && config.AlgorithmManager.RLEnabled {
		// Ensure algorithm manager RL config is compatible with main RL config
		if config.AlgorithmManager.DefaultAlgorithm == "qlearning" {
			if config.AlgorithmManager.QLearningConfig.LearningRate <= 0 {
				return fmt.Errorf("algorithm manager qlearning config has invalid learning rate")
			}
		}
	}

	// Validate multi-objective consistency
	if config.RL.MultiObjective.Enabled {
		if config.RL.MultiObjective.ActiveProfile == "" {
			return fmt.Errorf("active profile must be specified when multi-objective is enabled")
		}

		// Check if active profile exists
		if _, exists := config.RL.MultiObjective.Profiles[config.RL.MultiObjective.ActiveProfile]; !exists {
			return fmt.Errorf("active profile '%s' not found in profiles", config.RL.MultiObjective.ActiveProfile)
		}

		// Validate adaptation window is reasonable
		if config.RL.MultiObjective.AdaptationWindow <= 0 || config.RL.MultiObjective.AdaptationWindow > 1000 {
			return fmt.Errorf("adaptation window must be between 1 and 1000, got %d", config.RL.MultiObjective.AdaptationWindow)
		}
	}

	// Validate memory management vs episode configuration consistency
	if config.RL.MemoryManagement.Enabled && config.RL.EpisodeConfig.Type == "task_based" {
		// Ensure cleanup interval makes sense with episode size
		episodeSize := config.RL.EpisodeConfig.TasksPerEpisode
		cleanupInterval := config.RL.MemoryManagement.CleanupIntervalEpisodes

		if cleanupInterval*episodeSize > config.RL.MemoryManagement.MaxExperiences/2 {
			return fmt.Errorf("cleanup interval too large: would accumulate %d experiences before cleanup, but max is %d",
				cleanupInterval*episodeSize, config.RL.MemoryManagement.MaxExperiences)
		}
	}

	// Validate server resource limits
	if config.SingleNode.MaxConcurrentTasks > 1000 {
		return fmt.Errorf("max concurrent tasks too high for single node (max 1000), got %d", config.SingleNode.MaxConcurrentTasks)
	}

	if config.Queue.MaxQueueSize < config.SingleNode.MaxConcurrentTasks {
		return fmt.Errorf("queue max size (%d) should be >= max concurrent tasks (%d)",
			config.Queue.MaxQueueSize, config.SingleNode.MaxConcurrentTasks)
	}

	return nil
}

// GetConfig returns the current configuration
func GetConfig() Config {
	return AppConfig
}

// ReloadConfig reloads configuration from file
func ReloadConfig() error {
	configFile := viper.ConfigFileUsed()
	if configFile == "" {
		return fmt.Errorf("no config file to reload")
	}
	return LoadConfig(configFile)
}

// SaveConfig saves current configuration to file
func SaveConfig(filename string) error {
	if filename == "" {
		filename = viper.ConfigFileUsed()
		if filename == "" {
			filename = "config.yaml"
		}
	}

	// Set all config sections to viper before writing
	viper.Set("server", AppConfig.Server)
	viper.Set("grpc", AppConfig.GRPC)
	viper.Set("logging", AppConfig.Logging)
	viper.Set("model_persistence", AppConfig.ModelPersistence)
	viper.Set("metrics", AppConfig.Metrics)
	viper.Set("rl_agent", AppConfig.RLAgent)
	viper.Set("rl", AppConfig.RL)
	viper.Set("single_node", AppConfig.SingleNode)
	viper.Set("queue", AppConfig.Queue)
	viper.Set("algorithm_manager", AppConfig.AlgorithmManager)

	return viper.WriteConfigAs(filename)
}

// SetConfigValue sets a configuration value at runtime
func SetConfigValue(key string, value interface{}) error {
	viper.Set(key, value)

	// Re-unmarshal to update AppConfig
	if err := viper.Unmarshal(&AppConfig); err != nil {
		return fmt.Errorf("failed to update config: %w", err)
	}

	return validateConfig()
}

// GetConfigValue gets a configuration value
func GetConfigValue(key string) interface{} {
	return viper.Get(key)
}

// WatchConfig watches for configuration file changes
func WatchConfig(callback func()) {
	viper.WatchConfig()
	viper.OnConfigChange(func(e fsnotify.Event) {
		log.Printf("Config file changed: %s", e.Name)
		if err := viper.Unmarshal(&AppConfig); err != nil {
			log.Printf("Failed to reload config: %v", err)
			return
		}

		if err := validateConfig(); err != nil {
			log.Printf("Config validation failed after reload: %v", err)
			return
		}

		// Normalize weights after reload
		AppConfig.RL.RewardWeights.Normalize()
		for profileName := range AppConfig.RL.MultiObjective.Profiles {
			profile := AppConfig.RL.MultiObjective.Profiles[profileName]
			profile.Weights.Normalize()
			AppConfig.RL.MultiObjective.Profiles[profileName] = profile
		}

		log.Println("Configuration reloaded successfully")
		if callback != nil {
			callback()
		}
	})
}

// CreateDirectories creates necessary directories based on config
func CreateDirectories() error {
	dirs := []string{}

	// Add model persistence directories
	if AppConfig.ModelPersistence.ModelsPath != "" {
		dirs = append(dirs, AppConfig.ModelPersistence.ModelsPath)
	}

	for _, dir := range dirs {
		if dir != "" && dir != "." {
			if err := os.MkdirAll(dir, 0755); err != nil {
				return fmt.Errorf("failed to create directory %s: %w", dir, err)
			}
			log.Printf("Created directory: %s", dir)
		}
	}

	return nil
}

// ValidateCachingConfiguration validates cache configuration
func ValidateCachingConfiguration(config *CachingConfig) error {
	if !config.Enabled {
		return nil // No validation needed if caching is disabled
	}

	// Validate repeat threshold
	if config.RepeatThreshold < 1 {
		return fmt.Errorf("repeat_threshold must be at least 1, got: %d", config.RepeatThreshold)
	}

	if config.RepeatThreshold > 100 {
		return fmt.Errorf("repeat_threshold too high (max 100), got: %d", config.RepeatThreshold)
	}

	// Validate cache TTL
	if config.CacheTTLHours < 1 {
		return fmt.Errorf("cache_ttl_hours must be at least 1, got: %d", config.CacheTTLHours)
	}

	if config.CacheTTLHours > 168 { // 1 week max
		return fmt.Errorf("cache_ttl_hours too high (max 168 hours), got: %d", config.CacheTTLHours)
	}

	// Validate max tracked tasks
	if config.MaxTrackedTasks < 100 {
		return fmt.Errorf("max_tracked_tasks too low (min 100), got: %d", config.MaxTrackedTasks)
	}

	if config.MaxTrackedTasks > 100000 {
		return fmt.Errorf("max_tracked_tasks too high (max 100000), got: %d", config.MaxTrackedTasks)
	}

	// Validate cleanup interval
	if config.CleanupIntervalMinutes < 1 {
		return fmt.Errorf("cleanup_interval_minutes must be at least 1, got: %d", config.CleanupIntervalMinutes)
	}

	if config.CleanupIntervalMinutes > 1440 { // 24 hours max
		return fmt.Errorf("cleanup_interval_minutes too high (max 1440), got: %d", config.CleanupIntervalMinutes)
	}

	return nil
}
