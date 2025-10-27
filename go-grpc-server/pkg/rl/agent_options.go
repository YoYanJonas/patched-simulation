package rl

// AgentOption configures the Agent
type AgentOption func(*Agent)

// WithEnhancedEncoder configures the agent to use the enhanced state encoder
func WithEnhancedEncoder(levels int, options ...EncoderOption) AgentOption {
	return func(a *Agent) {
		enhancedOptions := []EncoderOption{WithDiscretizationLevels(levels)}
		enhancedOptions = append(enhancedOptions, options...)
		a.stateEncoder = NewEnhancedStateEncoder(enhancedOptions...)
		a.usingEnhancedEncoder = true
	}
}

// WithHybridAlgorithm adds the hybrid algorithm and optionally sets it as active
func WithHybridAlgorithm(setActive bool, options ...HybridOption) AgentOption {
	return func(a *Agent) {
		hybrid := NewHybridAlgorithm(a.stateEncoder, options...)
		a.algorithmManager.RegisterAlgorithm(hybrid)

		if setActive {
			a.algorithmManager.SetActiveAlgorithm("hybrid")
		}
	}
}

// WithPerformanceTracking enables/disables performance tracking
func WithPerformanceTracking(enabled bool) AgentOption {
	return func(a *Agent) {
		a.performanceTracking = enabled
	}
}

// WithWindowSize sets the size of the performance metrics history window
func WithWindowSize(size int) AgentOption {
	return func(a *Agent) {
		a.performanceMetrics = NewPerformanceMetrics(size)
	}
}

// WithLearningState sets whether learning is enabled initially
func WithLearningState(enabled bool) AgentOption {
	return func(a *Agent) {
		a.learningEnabled = enabled
	}
}

// Factory functions for creating common agent configurations

// NewBasicAgent creates an agent with basic Q-learning
func NewBasicAgent(logger interface {
	Info(msg string)
	Error(msg string, err error)
}, savePath string) *Agent {
	return NewAgent(logger, savePath)
}

// NewEnhancedAgent creates an agent with enhanced encoder and hybrid algorithm
func NewEnhancedAgent(logger interface {
	Info(msg string)
	Error(msg string, err error)
}, savePath string) *Agent {
	return NewAgent(logger, savePath,
		WithEnhancedEncoder(10,
			WithTimeFeatures(true),
			WithHistoricalFeatures(true),
			WithTrendFeatures(true)),
		WithHybridAlgorithm(true,
			WithTransitionThreshold(500),
			WithConfidenceThreshold(0.6),
			WithObjectiveWeights(0.5, 0.3, 0.2)))
}

// WithHybridAlgorithmOptions configures specific parameters for the hybrid algorithm
func WithHybridAlgorithmOptions(
	transitionThreshold int,
	confidenceThreshold float64,
	performanceWeight, reliabilityWeight, loadBalanceWeight float64,
	safetyFallback bool) AgentOption {

	return func(a *Agent) {
		// Get the hybrid algorithm if it exists
		if alg, err := a.algorithmManager.GetAlgorithm("hybrid"); err == nil {
			if hybrid, ok := alg.(*HybridAlgorithm); ok {
				params := map[string]interface{}{
					"transition_threshold": float64(transitionThreshold),
					"confidence_threshold": confidenceThreshold,
					"performance_weight":   performanceWeight,
					"reliability_weight":   reliabilityWeight,
					"load_balance_weight":  loadBalanceWeight,
					"safety_fallback":      safetyFallback,
				}
				hybrid.SetParams(params)
			}
		}
	}
}

// WithFunctionApproximation configures the agent to use function approximation
func WithFunctionApproximation(options ...FeatureExtractorOption) AgentOption {
	return func(a *Agent) {
		// Create feature extractor with provided options
		extractor := NewAdvancedFeatureExtractor(options...)

		// Create function approximator using the extractor
		approximator := NewLinearFunctionApproximator(extractor)

		// Create and register the function approximation algorithm
		algorithm := NewFunctionQLAlgorithm(approximator, extractor)
		a.algorithmManager.RegisterAlgorithm(algorithm)

		// We'll still use the existing state encoder for other algorithms
		// but the function algorithm will use the feature extractor directly
	}
}
