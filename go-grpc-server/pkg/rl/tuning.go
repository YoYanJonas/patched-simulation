package rl

import (
	"fmt"
	"math"
	"math/rand"
	"sort"
	"sync"
	"time"
)

// ParamRange defines a range for a tunable parameter
type ParamRange struct {
	Min      float64
	Max      float64
	LogScale bool      // Whether to sample on logarithmic scale
	Type     string    // "float", "int", or "categorical"
	Options  []float64 // For categorical parameters
}

// ExperimentResults stores the outcome metrics of an experiment
type ExperimentResults struct {
	RewardMean     float64
	RewardStdDev   float64
	SuccessRate    float64
	CompletionTime float64
	EpisodeCount   int
	TotalSteps     int
	Score          float64 // Combined evaluation metric
}

// Experiment represents a single hyperparameter configuration to evaluate
type Experiment struct {
	ID            string
	AlgorithmName string
	Params        map[string]float64
	Results       ExperimentResults
	Status        string // "pending", "running", "completed", "failed"
	StartTime     time.Time
	EndTime       time.Time
	Error         string
}

// TuningStrategy defines methods for hyperparameter optimization
type TuningStrategy interface {
	Name() string
	GenerateExperiments(paramSpace map[string]ParamRange, budget int) []Experiment
	ProcessResults(completedExperiments []Experiment) []Experiment
	GetBestParameters() (map[string]float64, float64)
	IsDone() bool
}

// RandomSearchStrategy implements a simple random search for hyperparameters
type RandomSearchStrategy struct {
	name           string
	budget         int
	completed      int
	bestParams     map[string]float64
	bestScore      float64
	paramSpace     map[string]ParamRange
	mutex          sync.RWMutex
	experimentSeed int64
}

// NewRandomSearchStrategy creates a new random search strategy
func NewRandomSearchStrategy(budget int) *RandomSearchStrategy {
	return &RandomSearchStrategy{
		name:           "random_search",
		budget:         budget,
		bestScore:      -1e10, // Very low initial score
		bestParams:     make(map[string]float64),
		paramSpace:     make(map[string]ParamRange),
		experimentSeed: time.Now().UnixNano(),
	}
}

// Name returns the identifier for this strategy
func (r *RandomSearchStrategy) Name() string {
	return r.name
}

// GenerateExperiments creates random parameter combinations to explore
func (r *RandomSearchStrategy) GenerateExperiments(paramSpace map[string]ParamRange, budget int) []Experiment {
	r.mutex.Lock()
	defer r.mutex.Unlock()

	r.budget = budget
	r.paramSpace = paramSpace

	// Create a random number generator with a seed for reproducibility
	rng := rand.New(rand.NewSource(r.experimentSeed))

	experiments := make([]Experiment, budget)
	for i := 0; i < budget; i++ {
		params := make(map[string]float64)

		// Generate random values for each parameter
		for name, paramRange := range paramSpace {
			var value float64

			switch paramRange.Type {
			case "int":
				// Generate integer in range
				value = math.Floor(paramRange.Min + rng.Float64()*(paramRange.Max-paramRange.Min+1))

			case "categorical":
				// Select one of the options
				if len(paramRange.Options) > 0 {
					value = paramRange.Options[rng.Intn(len(paramRange.Options))]
				}

			case "float":
				fallthrough
			default:
				// Generate float in range
				if paramRange.LogScale {
					// Log scale sampling
					logMin := math.Log(paramRange.Min)
					logMax := math.Log(paramRange.Max)
					value = math.Exp(logMin + rng.Float64()*(logMax-logMin))
				} else {
					// Linear scale sampling
					value = paramRange.Min + rng.Float64()*(paramRange.Max-paramRange.Min)
				}
			}

			params[name] = value
		}

		experiments[i] = Experiment{
			ID:            fmt.Sprintf("exp_%d_%d", i, time.Now().UnixNano()),
			AlgorithmName: "", // To be filled by the tuning manager
			Params:        params,
			Status:        "pending",
		}
	}

	return experiments
}

// ProcessResults analyses completed experiments and updates best parameters
func (r *RandomSearchStrategy) ProcessResults(completedExperiments []Experiment) []Experiment {
	r.mutex.Lock()
	defer r.mutex.Unlock()

	// Update completion count
	r.completed += len(completedExperiments)

	// Find best result
	for _, exp := range completedExperiments {
		if exp.Status == "completed" && exp.Results.Score > r.bestScore {
			r.bestScore = exp.Results.Score
			r.bestParams = make(map[string]float64)

			// Copy parameters
			for k, v := range exp.Params {
				r.bestParams[k] = v
			}
		}
	}

	// Random search doesn't generate new experiments based on results
	return nil
}

// GetBestParameters returns the best parameters found so far
func (r *RandomSearchStrategy) GetBestParameters() (map[string]float64, float64) {
	r.mutex.RLock()
	defer r.mutex.RUnlock()

	// Copy parameters
	bestParams := make(map[string]float64)
	for k, v := range r.bestParams {
		bestParams[k] = v
	}

	return bestParams, r.bestScore
}

// IsDone returns whether the strategy has completed its budget
func (r *RandomSearchStrategy) IsDone() bool {
	r.mutex.RLock()
	defer r.mutex.RUnlock()

	return r.completed >= r.budget
}

// EvolutionarySearchStrategy implements genetic algorithm-based parameter tuning
type EvolutionarySearchStrategy struct {
	name           string
	populationSize int
	generations    int
	currentGen     int
	mutationRate   float64
	eliteCount     int
	bestParams     map[string]float64
	bestScore      float64
	paramSpace     map[string]ParamRange
	population     []Experiment
	mutex          sync.RWMutex
	experimentSeed int64
}

// NewEvolutionarySearchStrategy creates a new evolutionary search strategy
func NewEvolutionarySearchStrategy(popSize, generations int) *EvolutionarySearchStrategy {
	return &EvolutionarySearchStrategy{
		name:           "evolutionary_search",
		populationSize: popSize,
		generations:    generations,
		currentGen:     0,
		mutationRate:   0.1,
		eliteCount:     2,
		bestScore:      -1e10, // Very low initial score
		bestParams:     make(map[string]float64),
		paramSpace:     make(map[string]ParamRange),
		experimentSeed: time.Now().UnixNano(),
	}
}

// Name returns the identifier for this strategy
func (e *EvolutionarySearchStrategy) Name() string {
	return e.name
}

// GenerateExperiments creates the initial population of parameter combinations
func (e *EvolutionarySearchStrategy) GenerateExperiments(paramSpace map[string]ParamRange, budget int) []Experiment {
	e.mutex.Lock()
	defer e.mutex.Unlock()

	e.paramSpace = paramSpace
	e.populationSize = budget

	// Create a random number generator with a seed for reproducibility
	rng := rand.New(rand.NewSource(e.experimentSeed))

	experiments := make([]Experiment, budget)
	for i := 0; i < budget; i++ {
		params := make(map[string]float64)

		// Generate random values for each parameter
		for name, paramRange := range paramSpace {
			var value float64

			switch paramRange.Type {
			case "int":
				// Generate integer in range
				value = math.Floor(paramRange.Min + rng.Float64()*(paramRange.Max-paramRange.Min+1))

			case "categorical":
				// Select one of the options
				if len(paramRange.Options) > 0 {
					value = paramRange.Options[rng.Intn(len(paramRange.Options))]
				}

			case "float":
				fallthrough
			default:
				// Generate float in range
				if paramRange.LogScale {
					// Log scale sampling
					logMin := math.Log(paramRange.Min)
					logMax := math.Log(paramRange.Max)
					value = math.Exp(logMin + rng.Float64()*(logMax-logMin))
				} else {
					// Linear scale sampling
					value = paramRange.Min + rng.Float64()*(paramRange.Max-paramRange.Min)
				}
			}

			params[name] = value
		}

		experiments[i] = Experiment{
			ID:            fmt.Sprintf("gen%d_exp%d_%d", e.currentGen, i, time.Now().UnixNano()),
			AlgorithmName: "", // To be filled by the tuning manager
			Params:        params,
			Status:        "pending",
		}
	}

	e.population = experiments

	return experiments
}

// ProcessResults evolves the population based on completed experiments
func (e *EvolutionarySearchStrategy) ProcessResults(completedExperiments []Experiment) []Experiment {
	e.mutex.Lock()
	defer e.mutex.Unlock()

	// Update best result
	for _, exp := range completedExperiments {
		if exp.Status == "completed" && exp.Results.Score > e.bestScore {
			e.bestScore = exp.Results.Score
			e.bestParams = make(map[string]float64)

			// Copy parameters
			for k, v := range exp.Params {
				e.bestParams[k] = v
			}
		}
	}

	// Store completed experiments as the current population
	e.population = completedExperiments
	e.currentGen++

	// Check if we've reached the max generations
	if e.currentGen >= e.generations {
		return nil // Done
	}

	// Sort population by score, descending
	sort.Slice(e.population, func(i, j int) bool {
		return e.population[i].Results.Score > e.population[j].Results.Score
	})

	// Create a random number generator with a seed for reproducibility
	rng := rand.New(rand.NewSource(e.experimentSeed + int64(e.currentGen)))

	// Create next generation
	nextGen := make([]Experiment, e.populationSize)

	// Elite selection: Copy the best individuals unchanged
	for i := 0; i < e.eliteCount && i < len(e.population); i++ {
		elite := e.population[i]
		nextGen[i] = Experiment{
			ID:            fmt.Sprintf("gen%d_elite%d_%d", e.currentGen, i, time.Now().UnixNano()),
			AlgorithmName: elite.AlgorithmName,
			Params:        copyParams(elite.Params),
			Status:        "pending",
		}
	}

	// Fill the rest with offspring
	for i := e.eliteCount; i < e.populationSize; i++ {
		// Tournament selection for parents
		parent1 := e.tournamentSelect(rng, 3)
		parent2 := e.tournamentSelect(rng, 3)

		// Crossover and mutation
		childParams := e.crossover(parent1.Params, parent2.Params, rng)
		childParams = e.mutate(childParams, rng)

		nextGen[i] = Experiment{
			ID:            fmt.Sprintf("gen%d_child%d_%d", e.currentGen, i-e.eliteCount, time.Now().UnixNano()),
			AlgorithmName: parent1.AlgorithmName, // Inherit from first parent
			Params:        childParams,
			Status:        "pending",
		}
	}

	return nextGen
}

// tournamentSelect selects an individual using tournament selection
func (e *EvolutionarySearchStrategy) tournamentSelect(rng *rand.Rand, tournamentSize int) Experiment {
	best := e.population[rng.Intn(len(e.population))]

	for i := 1; i < tournamentSize; i++ {
		candidate := e.population[rng.Intn(len(e.population))]
		if candidate.Results.Score > best.Results.Score {
			best = candidate
		}
	}

	return best
}

// crossover creates a new parameter set by combining two parents
func (e *EvolutionarySearchStrategy) crossover(params1, params2 map[string]float64, rng *rand.Rand) map[string]float64 {
	child := make(map[string]float64)

	for name := range params1 {
		// Each parameter has 50% chance to come from either parent
		if rng.Float64() < 0.5 {
			child[name] = params1[name]
		} else {
			child[name] = params2[name]
		}
	}

	return child
}

// mutate applies random mutations to parameters
func (e *EvolutionarySearchStrategy) mutate(params map[string]float64, rng *rand.Rand) map[string]float64 {
	for name, value := range params {
		// Apply mutation with probability mutationRate
		if rng.Float64() < e.mutationRate {
			paramRange, exists := e.paramSpace[name]
			if !exists {
				continue
			}

			switch paramRange.Type {
			case "int":
				// Shift by -1, 0, or 1, keeping within range
				shift := float64(rng.Intn(3) - 1)
				newValue := value + shift
				if newValue < paramRange.Min {
					newValue = paramRange.Min
				}
				if newValue > paramRange.Max {
					newValue = paramRange.Max
				}
				params[name] = newValue

			case "categorical":
				// Select a different option
				if len(paramRange.Options) > 1 {
					newIndex := rng.Intn(len(paramRange.Options))
					if paramRange.Options[newIndex] == value && newIndex < len(paramRange.Options)-1 {
						newIndex++
					}
					params[name] = paramRange.Options[newIndex]
				}

			case "float":
				fallthrough
			default:
				// Perturb by up to ±10%
				perturbation := (rng.Float64()*0.2 - 0.1) * value
				newValue := value + perturbation

				// Keep within bounds
				if newValue < paramRange.Min {
					newValue = paramRange.Min
				}
				if newValue > paramRange.Max {
					newValue = paramRange.Max
				}
				params[name] = newValue
			}
		}
	}

	return params
}

// GetBestParameters returns the best parameters found so far
func (e *EvolutionarySearchStrategy) GetBestParameters() (map[string]float64, float64) {
	e.mutex.RLock()
	defer e.mutex.RUnlock()

	// Copy parameters
	bestParams := make(map[string]float64)
	for k, v := range e.bestParams {
		bestParams[k] = v
	}

	return bestParams, e.bestScore
}

// IsDone returns whether the strategy has completed all generations
func (e *EvolutionarySearchStrategy) IsDone() bool {
	e.mutex.RLock()
	defer e.mutex.RUnlock()

	return e.currentGen >= e.generations
}

// Helper function to copy parameter maps
func copyParams(src map[string]float64) map[string]float64 {
	dst := make(map[string]float64)
	for k, v := range src {
		dst[k] = v
	}
	return dst
}
