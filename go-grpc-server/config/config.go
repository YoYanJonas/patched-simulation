package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/viper"
)

// Config is the root configuration struct containing all settings
type Config struct {
	Server   ServerConfig   `mapstructure:"server"`
	RL       RLConfig       `mapstructure:"rl"`
	Logging  LoggingConfig  `mapstructure:"logging"`
	Features FeaturesConfig `mapstructure:"features"`
}

// ServerConfig contains service-related configuration
type ServerConfig struct {
	Host            string `mapstructure:"host"`
	Port            int    `mapstructure:"port"`
	ShutdownTimeout int    `mapstructure:"shutdown_timeout"` // in seconds
}

// LoggingConfig contains logging-related configuration
type LoggingConfig struct {
	Level      string `mapstructure:"level"`       // debug, info, warn, error
	Format     string `mapstructure:"format"`      // json or text
	OutputPath string `mapstructure:"output_path"` // file path or stdout
}

// FeaturesConfig controls which features are enabled
type FeaturesConfig struct {
	EnableHyperparameterTuning  bool `mapstructure:"enable_hyperparameter_tuning"`
	EnableFunctionApproximation bool `mapstructure:"enable_function_approximation"`
	EnableHybridAlgorithm       bool `mapstructure:"enable_hybrid_algorithm"`
}

// LoadConfig loads configuration from files and environment variables
func LoadConfig(configPath string) (*Config, error) {
	v := viper.New()

	// Set default values
	setDefaults(v)

	// Set configuration file settings
	if configPath != "" {
		v.SetConfigFile(configPath)
	} else {
		// Look for config in the config directory
		v.AddConfigPath("./config")
		v.AddConfigPath("../config")
		v.AddConfigPath("../../config")
		v.SetConfigName("config") // config.yaml, config.json, etc.
	}

	// Read config file
	if err := v.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); !ok {
			return nil, fmt.Errorf("failed to read config file: %w", err)
		}
		// If file not found, we'll just use defaults and env vars
		fmt.Println("No configuration file found, using defaults and environment variables")
	} else {
		fmt.Println("Using config file:", v.ConfigFileUsed())
	}

	// Read environment variables
	v.AutomaticEnv()
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.SetEnvPrefix("APP") // APP_SERVER_PORT, etc.

	// Unmarshal config
	var config Config
	if err := v.Unmarshal(&config); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	// Validate config
	if err := validateConfig(&config); err != nil {
		return nil, err
	}

	return &config, nil
}

// GetRLAlgorithmConfig returns config for a specific algorithm
func (c *Config) GetRLAlgorithmConfig(algorithmName string) (*RLAlgorithmConfig, bool) {
	switch algorithmName {
	case "q_learning":
		return &c.RL.QLearning, true
	case "sarsa":
		return &c.RL.SARSA, true
	case "function_q_learning":
		return &c.RL.FunctionQL, true
	case "hybrid":
		// Since hybrid is a different type, we need to convert it
		return (*RLAlgorithmConfig)(&c.RL.Hybrid.RLAlgorithmConfig), true
	default:
		return nil, false
	}
}

// SaveConfig saves the current configuration to a file
func (c *Config) SaveConfig(filePath string) error {
	dir := filepath.Dir(filePath)
	if _, err := os.Stat(dir); os.IsNotExist(err) {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return fmt.Errorf("failed to create directory: %w", err)
		}
	}

	v := viper.New()
	v.SetConfigFile(filePath)

	// Note: Convert the config struct to map
	// Viper has better methods for this, but this is a simplified version
	if err := v.MergeConfigMap(structToMap(c)); err != nil {
		return fmt.Errorf("failed to merge config: %w", err)
	}

	// Write the config to the file
	if err := v.WriteConfig(); err != nil {
		return fmt.Errorf("failed to write config: %w", err)
	}

	return nil
}

// Helper to convert config struct to map
func structToMap(input interface{}) map[string]interface{} {
	// In a production implementation, you would use reflection or viper marshaling
	// This is a placeholder that should be replaced with proper implementation
	// For example, you could marshal to JSON and then unmarshal to map[string]interface{}
	return make(map[string]interface{})
}
