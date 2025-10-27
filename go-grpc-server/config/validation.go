package config

import (
	"errors"
	"fmt"
	"strings"
)

// validateConfig checks if the loaded configuration is valid
func validateConfig(c *Config) error {
	var validationErrors []string

	// Validate server configuration
	if c.Server.Port < 0 || c.Server.Port > 65535 {
		validationErrors = append(validationErrors, "server.port must be between 0 and 65535")
	}
	if c.Server.ShutdownTimeout <= 0 {
		validationErrors = append(validationErrors, "server.shutdown_timeout must be positive")
	}

	// Validate RL configuration
	if c.RL.SaveInterval <= 0 {
		validationErrors = append(validationErrors, "rl.save_interval must be positive")
	}
	if c.RL.DefaultDiscretization <= 0 {
		validationErrors = append(validationErrors, "rl.default_discretization must be positive")
	}

	// Validate algorithm parameters
	if err := validateAlgorithmConfig("q_learning", &c.RL.QLearning); err != nil {
		validationErrors = append(validationErrors, err.Error())
	}
	if err := validateAlgorithmConfig("sarsa", &c.RL.SARSA); err != nil {
		validationErrors = append(validationErrors, err.Error())
	}
	if err := validateAlgorithmConfig("function_q_learning", &c.RL.FunctionQL); err != nil {
		validationErrors = append(validationErrors, err.Error())
	}

	// Validate hybrid algorithm specific parameters
	if c.RL.Hybrid.TransitionThreshold < 0 || c.RL.Hybrid.TransitionThreshold > 1 {
		validationErrors = append(validationErrors, "rl.hybrid.transition_threshold must be between 0 and 1")
	}
	if c.RL.Hybrid.ConfidenceThreshold < 0 || c.RL.Hybrid.ConfidenceThreshold > 1 {
		validationErrors = append(validationErrors, "rl.hybrid.confidence_threshold must be between 0 and 1")
	}

	// If we have any validation errors, return them
	if len(validationErrors) > 0 {
		return errors.New("Configuration validation failed: " + strings.Join(validationErrors, "; "))
	}

	return nil
}

// validateAlgorithmConfig checks if the algorithm configuration is valid
func validateAlgorithmConfig(name string, config *RLAlgorithmConfig) error {
	var validationErrors []string

	if config.Alpha <= 0 || config.Alpha > 1 {
		validationErrors = append(validationErrors, fmt.Sprintf("rl.%s.alpha must be between 0 and 1", name))
	}
	if config.Gamma < 0 || config.Gamma > 1 {
		validationErrors = append(validationErrors, fmt.Sprintf("rl.%s.gamma must be between 0 and 1", name))
	}
	if config.Epsilon < 0 || config.Epsilon > 1 {
		validationErrors = append(validationErrors, fmt.Sprintf("rl.%s.epsilon must be between 0 and 1", name))
	}
	if config.DecayRate <= 0 || config.DecayRate > 1 {
		validationErrors = append(validationErrors, fmt.Sprintf("rl.%s.decay_rate must be between 0 and 1", name))
	}
	if config.MinimumEpsilon < 0 || config.MinimumEpsilon > 1 {
		validationErrors = append(validationErrors, fmt.Sprintf("rl.%s.minimum_epsilon must be between 0 and 1", name))
	}

	// Only check lambda if eligibility traces are enabled
	if config.EnableEligibility && (config.Lambda < 0 || config.Lambda > 1) {
		validationErrors = append(validationErrors, fmt.Sprintf("rl.%s.lambda must be between 0 and 1", name))
	}

	// If we have any validation errors, return them
	if len(validationErrors) > 0 {
		return errors.New(strings.Join(validationErrors, "; "))
	}

	return nil
}
