package config

// RLConfig contains all reinforcement learning related configurations
type RLConfig struct {
	ModelSavePath         string `mapstructure:"model_save_path"`
	SaveInterval          int    `mapstructure:"save_interval"`          // in minutes
	DefaultDiscretization int    `mapstructure:"default_discretization"` // default levels
	DirectoryPermissions  int    `mapstructure:"directory_permissions"`  // file permissions (octal)
	DefaultAlgorithm      string `mapstructure:"default_algorithm"`
	PerformanceWindow     int    `mapstructure:"performance_window"` // window size for metrics

	// Algorithm specific configurations
	QLearning  RLAlgorithmConfig `mapstructure:"q_learning"`
	SARSA      RLAlgorithmConfig `mapstructure:"sarsa"`
	FunctionQL RLAlgorithmConfig `mapstructure:"function_q_learning"`
	Hybrid     HybridConfig      `mapstructure:"hybrid"`
}

// RLAlgorithmConfig defines parameters common to most RL algorithms
type RLAlgorithmConfig struct {
	Alpha             float64 `mapstructure:"alpha"`              // Learning rate
	Gamma             float64 `mapstructure:"gamma"`              // Discount factor
	Epsilon           float64 `mapstructure:"epsilon"`            // Exploration rate
	DecayRate         float64 `mapstructure:"decay_rate"`         // Rate of decay for epsilon
	MinimumEpsilon    float64 `mapstructure:"minimum_epsilon"`    // Minimum exploration rate
	EnableEligibility bool    `mapstructure:"enable_eligibility"` // Enable eligibility traces
	Lambda            float64 `mapstructure:"lambda"`             // Eligibility decay parameter
}

// HybridConfig includes special parameters for the hybrid algorithm
type HybridConfig struct {
	RLAlgorithmConfig               // Embed base algorithm parameters
	TransitionThreshold     float64 `mapstructure:"transition_threshold"`
	ConfidenceThreshold     float64 `mapstructure:"confidence_threshold"`
	HeuristicUsageThreshold float64 `mapstructure:"heuristic_usage_threshold"`
}

// NodeCapabilityConfig defines thresholds for node selection
type NodeCapabilityConfig struct {
	CPUThreshold       float64 `mapstructure:"cpu_threshold"`       // Maximum CPU utilization
	MemoryThreshold    float64 `mapstructure:"memory_threshold"`    // Maximum memory utilization
	BandwidthThreshold float64 `mapstructure:"bandwidth_threshold"` // Minimum required bandwidth
}

// FunctionApproximationConfig defines parameters for function approximation
type FunctionApproximationConfig struct {
	LearningRate             float64 `mapstructure:"learning_rate"`
	RegularizationL1         float64 `mapstructure:"regularization_l1"` // L1 regularization parameter
	RegularizationL2         float64 `mapstructure:"regularization_l2"` // L2 regularization parameter
	EnableTimeFeatures       bool    `mapstructure:"enable_time_features"`
	EnableTrendFeatures      bool    `mapstructure:"enable_trend_features"`
	EnableHistoricalFeatures bool    `mapstructure:"enable_historical_features"`
	FeatureRange             float64 `mapstructure:"feature_range"` // Feature normalization range
}
