package config

import (
	"github.com/spf13/viper"
)

// setDefaults configures default values for all configuration parameters
func setDefaults(v *viper.Viper) {
	// Server defaults
	v.SetDefault("server.host", "0.0.0.0")
	v.SetDefault("server.port", 50051)
	v.SetDefault("server.shutdown_timeout", 6) // seconds

	// Logging defaults
	v.SetDefault("logging.level", "info")
	v.SetDefault("logging.format", "text")
	v.SetDefault("logging.output_path", "stdout")

	// Features defaults
	v.SetDefault("features.enable_hyperparameter_tuning", true)
	v.SetDefault("features.enable_function_approximation", true)
	v.SetDefault("features.enable_hybrid_algorithm", true)

	// RL defaults
	v.SetDefault("rl.model_save_path", "./models")
	v.SetDefault("rl.save_interval", 10) // minutes
	v.SetDefault("rl.default_discretization", 5)
	v.SetDefault("rl.directory_permissions", 0755)
	v.SetDefault("rl.default_algorithm", "q_learning")
	v.SetDefault("rl.performance_window", 100)

	// Node capability defaults
	v.SetDefault("rl.node_capability.cpu_threshold", 0.9)
	v.SetDefault("rl.node_capability.memory_threshold", 0.85)
	v.SetDefault("rl.node_capability.bandwidth_threshold", 10.0) // Mbps

	// Q-Learning defaults
	v.SetDefault("rl.q_learning.alpha", 0.1)
	v.SetDefault("rl.q_learning.gamma", 0.9)
	v.SetDefault("rl.q_learning.epsilon", 0.1)
	v.SetDefault("rl.q_learning.decay_rate", 0.99)
	v.SetDefault("rl.q_learning.minimum_epsilon", 0.01)
	v.SetDefault("rl.q_learning.enable_eligibility", false)
	v.SetDefault("rl.q_learning.lambda", 0.8)

	// SARSA defaults
	v.SetDefault("rl.sarsa.alpha", 0.1)
	v.SetDefault("rl.sarsa.gamma", 0.9)
	v.SetDefault("rl.sarsa.epsilon", 0.1)
	v.SetDefault("rl.sarsa.decay_rate", 0.99)
	v.SetDefault("rl.sarsa.minimum_epsilon", 0.01)
	v.SetDefault("rl.sarsa.enable_eligibility", false)
	v.SetDefault("rl.sarsa.lambda", 0.8)

	// Function Q-Learning defaults
	v.SetDefault("rl.function_q_learning.alpha", 0.05)
	v.SetDefault("rl.function_q_learning.gamma", 0.95)
	v.SetDefault("rl.function_q_learning.epsilon", 0.1)
	v.SetDefault("rl.function_q_learning.decay_rate", 0.99)
	v.SetDefault("rl.function_q_learning.minimum_epsilon", 0.01)
	v.SetDefault("rl.function_q_learning.enable_eligibility", false)
	v.SetDefault("rl.function_q_learning.lambda", 0.8)

	// Hybrid algorithm defaults
	v.SetDefault("rl.hybrid.alpha", 0.1)
	v.SetDefault("rl.hybrid.gamma", 0.9)
	v.SetDefault("rl.hybrid.epsilon", 0.1)
	v.SetDefault("rl.hybrid.decay_rate", 0.99)
	v.SetDefault("rl.hybrid.minimum_epsilon", 0.01)
	v.SetDefault("rl.hybrid.transition_threshold", 0.8)
	v.SetDefault("rl.hybrid.confidence_threshold", 0.7)
	v.SetDefault("rl.hybrid.heuristic_usage_threshold", 0.4)

	// Function approximation defaults
	v.SetDefault("rl.function_approximation.learning_rate", 0.01)
	v.SetDefault("rl.function_approximation.regularization_l1", 0.0)
	v.SetDefault("rl.function_approximation.regularization_l2", 0.0001)
	v.SetDefault("rl.function_approximation.enable_time_features", true)
	v.SetDefault("rl.function_approximation.enable_trend_features", true)
	v.SetDefault("rl.function_approximation.enable_historical_features", true)
	v.SetDefault("rl.function_approximation.feature_range", 1.0)
}
