package rl

import (
	"encoding/json"
	"errors"
	"io/ioutil"
	"math"
	"math/rand"
	"sync"
)

// FunctionApproximator approximates Q-values from state features
type FunctionApproximator interface {
	// Predict estimates the Q-value for a feature vector and action
	Predict(features map[string]float64, action string) float64

	// Update updates the approximator's weights based on TD error
	Update(features map[string]float64, action string, tdError float64)

	// Save persists the approximator's weights
	Save(path string) error

	// Load restores the approximator's weights
	Load(path string) error

	// Reset clears the approximator's weights
	Reset()

	// GetParams returns the approximator's parameters
	GetParams() map[string]interface{}

	// SetParams updates the approximator's parameters
	SetParams(params map[string]interface{}) error
}

// LinearFunctionApproximator implements linear function approximation
type LinearFunctionApproximator struct {
	weights          map[string]map[string]float64 // action -> feature -> weight
	featureExtractor FeatureExtractor
	learningRate     float64
	l2Regularization float64
	mutex            sync.RWMutex
	featureDimension int      // Number of features
	featureNames     []string // Feature names for consistent ordering
}

// NewLinearFunctionApproximator creates a new linear function approximator
func NewLinearFunctionApproximator(extractor FeatureExtractor) *LinearFunctionApproximator {
	return &LinearFunctionApproximator{
		weights:          make(map[string]map[string]float64),
		featureExtractor: extractor,
		learningRate:     0.01,
		l2Regularization: 0.001, // Light regularization to prevent overfitting
		featureDimension: extractor.GetFeatureDimension(),
	}
}

// Predict estimates the Q-value for a feature vector and action
func (l *LinearFunctionApproximator) Predict(features map[string]float64, action string) float64 {
	l.mutex.RLock()
	defer l.mutex.RUnlock()

	// Initialize action weights if they don't exist
	if _, exists := l.weights[action]; !exists {
		return 0 // Default value for unknown actions
	}

	// Calculate dot product of features and weights
	var prediction float64
	for feature, value := range features {
		if weight, exists := l.weights[action][feature]; exists {
			prediction += weight * value
		}
	}

	return prediction
}

// Update updates the weights based on TD error and gradient descent
func (l *LinearFunctionApproximator) Update(features map[string]float64, action string, tdError float64) {
	l.mutex.Lock()
	defer l.mutex.Unlock()

	// Initialize action weights if they don't exist
	if _, exists := l.weights[action]; !exists {
		l.weights[action] = make(map[string]float64)
	}

	// Update each weight: w += alpha * tdError * feature - alpha * lambda * w
	for feature, value := range features {
		currentWeight := l.weights[action][feature]

		// Gradient descent update with L2 regularization
		l.weights[action][feature] = currentWeight +
			l.learningRate*(tdError*value-l.l2Regularization*currentWeight)
	}
}

// Save persists the approximator's weights to a file
func (l *LinearFunctionApproximator) Save(path string) error {
	l.mutex.RLock()
	defer l.mutex.RUnlock()

	data, err := json.MarshalIndent(l.weights, "", "  ")
	if err != nil {
		return err
	}

	return ioutil.WriteFile(path, data, 0644)
}

// Load restores the approximator's weights from a file
func (l *LinearFunctionApproximator) Load(path string) error {
	l.mutex.Lock()
	defer l.mutex.Unlock()

	data, err := ioutil.ReadFile(path)
	if err != nil {
		return err
	}

	return json.Unmarshal(data, &l.weights)
}

// Reset clears the approximator's weights
func (l *LinearFunctionApproximator) Reset() {
	l.mutex.Lock()
	defer l.mutex.Unlock()

	l.weights = make(map[string]map[string]float64)
}

// GetParams returns the approximator's parameters
func (l *LinearFunctionApproximator) GetParams() map[string]interface{} {
	l.mutex.RLock()
	defer l.mutex.RUnlock()

	return map[string]interface{}{
		"learning_rate":     l.learningRate,
		"l2_regularization": l.l2Regularization,
		"feature_dimension": l.featureDimension,
		"feature_extractor": l.featureExtractor.Name(),
	}
}

// SetParams updates the approximator's parameters
func (l *LinearFunctionApproximator) SetParams(params map[string]interface{}) error {
	l.mutex.Lock()
	defer l.mutex.Unlock()

	if lr, ok := params["learning_rate"].(float64); ok {
		if lr <= 0 {
			return errors.New("learning rate must be positive")
		}
		l.learningRate = lr
	}

	if reg, ok := params["l2_regularization"].(float64); ok {
		if reg < 0 {
			return errors.New("regularization must be non-negative")
		}
		l.l2Regularization = reg
	}

	return nil
}

// FunctionQLAlgorithm implements Q-learning with function approximation
type FunctionQLAlgorithm struct {
	approximator     FunctionApproximator
	featureExtractor FeatureExtractor
	epsilon          float64 // Exploration rate
	gamma            float64 // Discount factor
	mutex            sync.RWMutex
}

// NewFunctionQLAlgorithm creates a new function approximation Q-learning algorithm
func NewFunctionQLAlgorithm(approximator FunctionApproximator, extractor FeatureExtractor) *FunctionQLAlgorithm {
	return &FunctionQLAlgorithm{
		approximator:     approximator,
		featureExtractor: extractor,
		epsilon:          0.1, // 10% exploration
		gamma:            0.9, // Standard discount factor
	}
}

// Name returns the identifier for this algorithm
func (f *FunctionQLAlgorithm) Name() string {
	return "function_q_learning"
}

// SelectAction chooses an action using epsilon-greedy exploration
func (f *FunctionQLAlgorithm) SelectAction(state SystemState, availableActions []string) (string, error) {
	if len(availableActions) == 0 {
		return "", errors.New("no available actions")
	}

	// With probability epsilon, choose a random action
	if rand.Float64() < f.epsilon {
		return availableActions[rand.Intn(len(availableActions))], nil
	}

	// Extract features from state
	features := f.featureExtractor.ExtractFeatures(state)

	// Find action with maximum Q-value
	var bestAction string
	var bestValue float64 = math.Inf(-1)

	f.mutex.RLock()
	for _, action := range availableActions {
		qValue := f.approximator.Predict(features, action)
		if qValue > bestValue {
			bestValue = qValue
			bestAction = action
		}
	}
	f.mutex.RUnlock()

	// If all values are the same, use random action
	if bestAction == "" {
		return availableActions[rand.Intn(len(availableActions))], nil
	}

	return bestAction, nil
}

// Learn updates the approximator based on observed reward
func (f *FunctionQLAlgorithm) Learn(prevState SystemState, action string, newState SystemState, reward float64) {
	// Extract features from states
	prevFeatures := f.featureExtractor.ExtractFeatures(prevState)
	newFeatures := f.featureExtractor.ExtractFeatures(newState)

	f.mutex.Lock()
	defer f.mutex.Unlock()

	// Calculate current Q-value
	currentQ := f.approximator.Predict(prevFeatures, action)

	// Find maximum Q-value for next state across all possible actions
	// This assumes we know all possible actions, which might not be true
	// In practice, we'd use the availableActions from the next state
	var maxNextQ float64 = 0

	// As a simplification, we'll use the nodes in the new state as available actions
	availableActions := make([]string, 0, len(newState.FogNodes))
	for nodeID := range newState.FogNodes {
		availableActions = append(availableActions, nodeID)
	}

	// If we have available actions, find max Q
	if len(availableActions) > 0 {
		maxNextQ = math.Inf(-1)
		for _, nextAction := range availableActions {
			qValue := f.approximator.Predict(newFeatures, nextAction)
			if qValue > maxNextQ {
				maxNextQ = qValue
			}
		}

		// Handle first prediction case
		if math.IsInf(maxNextQ, -1) {
			maxNextQ = 0
		}
	}

	// Calculate TD target and error
	target := reward + f.gamma*maxNextQ
	tdError := target - currentQ

	// Update approximator
	f.approximator.Update(prevFeatures, action, tdError)
}

// SaveModel persists the approximator to a file
func (f *FunctionQLAlgorithm) SaveModel(path string) error {
	return f.approximator.Save(path)
}

// LoadModel restores the approximator from a file
func (f *FunctionQLAlgorithm) LoadModel(path string) error {
	return f.approximator.Load(path)
}

// Reset clears the algorithm's learned knowledge
func (f *FunctionQLAlgorithm) Reset() {
	f.mutex.Lock()
	defer f.mutex.Unlock()

	f.approximator.Reset()
}

// GetParams returns the algorithm's current parameters
func (f *FunctionQLAlgorithm) GetParams() map[string]interface{} {
	f.mutex.RLock()
	defer f.mutex.RUnlock()

	params := map[string]interface{}{
		"epsilon": f.epsilon,
		"gamma":   f.gamma,
	}

	// Add approximator params
	approxParams := f.approximator.GetParams()
	for k, v := range approxParams {
		params["approx_"+k] = v
	}

	return params
}

// SetParams updates the algorithm's parameters
func (f *FunctionQLAlgorithm) SetParams(params map[string]interface{}) error {
	f.mutex.Lock()
	defer f.mutex.Unlock()

	if epsilon, ok := params["epsilon"].(float64); ok {
		if epsilon < 0 || epsilon > 1 {
			return errors.New("epsilon must be between 0 and 1")
		}
		f.epsilon = epsilon
	}

	if gamma, ok := params["gamma"].(float64); ok {
		if gamma < 0 || gamma > 1 {
			return errors.New("gamma must be between 0 and 1")
		}
		f.gamma = gamma
	}

	// Extract and forward approximator parameters
	approxParams := make(map[string]interface{})
	for k, v := range params {
		if len(k) > 7 && k[:7] == "approx_" {
			approxParams[k[7:]] = v
		}
	}

	if len(approxParams) > 0 {
		return f.approximator.SetParams(approxParams)
	}

	return nil
}
