package rl

import (
	"fmt"
	"math"
	"sort"
	"time"

	"scheduler-grpc-server/pkg/config"
)

// StateFeatures represents the current state of the scheduling system
type StateFeatures struct {
	// Queue characteristics
	QueueLength      int     `json:"queue_length"`
	AvgWaitingTime   float64 `json:"avg_waiting_time"`
	AvgExecutionTime float64 `json:"avg_execution_time"`
	AvgPriority      float64 `json:"avg_priority"`

	// Resource utilization
	CPUUtilization    float64 `json:"cpu_utilization"`
	MemoryUtilization float64 `json:"memory_utilization"`

	// Task distribution
	HighPriorityRatio float64 `json:"high_priority_ratio"`
	ShortTaskRatio    float64 `json:"short_task_ratio"`
	UrgentTaskRatio   float64 `json:"urgent_task_ratio"`

	// System load
	SystemLoad       float64 `json:"system_load"`
	ResourcePressure float64 `json:"resource_pressure"`

	// Time-based features
	TimeOfDay int `json:"time_of_day"` // 0-23 hours
	DayOfWeek int `json:"day_of_week"` // 0-6

	// Performance indicators
	RecentThroughput float64 `json:"recent_throughput"`
	RecentLatency    float64 `json:"recent_latency"`

	// Fuzzy categorized states (for better state representation)
	CPUCategory      string `json:"cpu_category"`
	MemoryCategory   string `json:"memory_category"`
	QueueCategory    string `json:"queue_category"`
	LoadCategory     string `json:"load_category"`
	PriorityCategory string `json:"priority_category"`

	// Cache-related features
	RepeatedTaskRatio float64 `json:"repeated_task_ratio"`
	CacheCategory     string  `json:"cache_category"`

	// Performance optimization: cached state key
	cachedStateKey string
	keyDirty       bool

	// Additional context
	Timestamp time.Time `json:"timestamp"`
}

// ExtractStateFeatures extracts state features from current tasks and node status
func ExtractStateFeatures(tasks []TaskEntry, nodeManager SingleNodeManager, cacheManager interface{}) *StateFeatures {
	state := &StateFeatures{
		Timestamp: time.Now(),
	}

	// Extract time-based features
	now := time.Now()
	state.TimeOfDay = now.Hour()
	state.DayOfWeek = int(now.Weekday())

	// Queue characteristics
	state.QueueLength = len(tasks)

	if len(tasks) > 0 {
		state.calculateTaskStatistics(tasks)
		state.calculateTaskDistribution(tasks)
	}

	// Resource utilization from node manager
	if nodeManager != nil {
		state.CPUUtilization = nodeManager.GetCPUUtilization()
		state.MemoryUtilization = nodeManager.GetMemoryUtilization()
		state.SystemLoad = (state.CPUUtilization + state.MemoryUtilization) / 2.0
		state.ResourcePressure = math.Max(state.CPUUtilization, state.MemoryUtilization)

		// Performance indicators (placeholder - would be calculated from historical data)
		state.RecentThroughput = float64(state.QueueLength) / 10.0 // Simplified
		state.RecentLatency = state.AvgWaitingTime + state.AvgExecutionTime
	}

	// Calculate cache-related features
	state.calculateCacheFeatures(cacheManager)

	// Apply fuzzy categorization if enabled
	state.applyFuzzyCategories()

	return state
}

// calculateTaskStatistics calculates average statistics for tasks
func (sf *StateFeatures) calculateTaskStatistics(tasks []TaskEntry) {
	totalWaitingTime := 0.0
	totalExecutionTime := 0.0
	totalPriority := 0.0

	for _, task := range tasks {
		// Calculate waiting time - assume 1 second per task as placeholder
		totalWaitingTime += 1.0 // Simplified - you'd calculate actual waiting time

		// Execution time
		totalExecutionTime += float64(task.GetExecutionTimeMs())

		// Priority
		totalPriority += float64(task.GetPriority())
	}

	count := float64(len(tasks))
	sf.AvgWaitingTime = totalWaitingTime / count
	sf.AvgExecutionTime = totalExecutionTime / count
	sf.AvgPriority = totalPriority / count
}

// calculateTaskDistribution calculates task distribution ratios
func (sf *StateFeatures) calculateTaskDistribution(tasks []TaskEntry) {
	highPriorityCount := 0
	shortTaskCount := 0
	urgentTaskCount := 0

	// Calculate thresholds
	executionTimes := make([]float64, len(tasks))
	for i, task := range tasks {
		executionTimes[i] = float64(task.GetExecutionTimeMs())
	}
	sort.Float64s(executionTimes)

	shortTaskThreshold := 0.0
	if len(executionTimes) > 0 {
		shortTaskThreshold = executionTimes[len(executionTimes)/3]
	}

	for _, task := range tasks {
		// High priority (above average)
		if float64(task.GetPriority()) > sf.AvgPriority {
			highPriorityCount++
		}

		// Short tasks (bottom third of execution times)
		if float64(task.GetExecutionTimeMs()) <= shortTaskThreshold {
			shortTaskCount++
		}

		// Urgent tasks (deadline within next hour)
		if task.GetDeadline() > 0 {
			deadlineTime := time.Unix(task.GetDeadline(), 0)
			timeToDeadline := time.Until(deadlineTime).Hours()
			if timeToDeadline <= 1.0 && timeToDeadline > 0 {
				urgentTaskCount++
			}
		}
	}

	count := float64(len(tasks))
	if count > 0 {
		sf.HighPriorityRatio = float64(highPriorityCount) / count
		sf.ShortTaskRatio = float64(shortTaskCount) / count
		sf.UrgentTaskRatio = float64(urgentTaskCount) / count
	}
}

// calculateCacheFeatures calculates cache-related state features
func (sf *StateFeatures) calculateCacheFeatures(cacheManager interface{}) {
	// Default values
	sf.RepeatedTaskRatio = 0.0
	sf.CacheCategory = "none"

	// Check if cache manager is available and has the right interface
	if cacheManager != nil {
		// Use type assertion to get cache statistics
		if cm, ok := cacheManager.(interface {
			GetRepeatedTaskRatio() float64
		}); ok {
			sf.RepeatedTaskRatio = cm.GetRepeatedTaskRatio()
		}
	}
}

// applyFuzzyCategories applies fuzzy categorization to continuous features with optimization
func (sf *StateFeatures) applyFuzzyCategories() {
	cfg := config.GetConfig()

	// Only apply fuzzy categorization if enabled
	if !cfg.RL.StateDiscretization.Enabled {
		return
	}

	// Pre-calculate percentage conversions to avoid repeated calculations
	cpuPercent := sf.CPUUtilization * 100.0
	memPercent := sf.MemoryUtilization * 100.0
	loadPercent := sf.SystemLoad * 100.0

	// Batch categorization for better cache locality
	sf.CPUCategory = cfg.RL.StateDiscretization.CPUUtilization.GetCategoryName(cpuPercent)
	sf.MemoryCategory = cfg.RL.StateDiscretization.MemoryUtilization.GetCategoryName(memPercent)
	sf.QueueCategory = cfg.RL.StateDiscretization.QueueLength.GetCategoryName(float64(sf.QueueLength))
	sf.LoadCategory = cfg.RL.StateDiscretization.SystemLoad.GetCategoryName(loadPercent)
	sf.PriorityCategory = cfg.RL.StateDiscretization.TaskPriority.GetCategoryName(sf.AvgPriority)

	// Cache categorization
	sf.CacheCategory = sf.categorizeCacheRatio(sf.RepeatedTaskRatio)
	// Mark state key as dirty since categories changed
	sf.keyDirty = true
}

// categorizeCacheRatio categorizes the repeated task ratio
func (sf *StateFeatures) categorizeCacheRatio(ratio float64) string {
	if ratio == 0.0 {
		return "none"
	} else if ratio <= 0.2 {
		return "low"
	} else if ratio <= 0.5 {
		return "medium"
	} else if ratio <= 0.8 {
		return "high"
	} else {
		return "very_high"
	}
}

// DiscretizeFeature discretizes a continuous feature using configurable categories
func (sf *StateFeatures) DiscretizeFeature(featureName string, value float64) (string, int, error) {
	cfg := config.GetConfig()

	if !cfg.RL.StateDiscretization.Enabled {
		// Fallback to legacy discretization if fuzzy categorization is disabled
		return sf.discretizeValueLegacy(value, featureName), -1, nil
	}

	var categoryConfig *config.CategoryConfig

	switch featureName {
	case "cpu_utilization":
		categoryConfig = &cfg.RL.StateDiscretization.CPUUtilization
		// Convert from [0,1] to [0,100] for percentage-based boundaries
		value *= 100.0
	case "memory_utilization":
		categoryConfig = &cfg.RL.StateDiscretization.MemoryUtilization
		// Convert from [0,1] to [0,100] for percentage-based boundaries
		value *= 100.0
	case "queue_length":
		categoryConfig = &cfg.RL.StateDiscretization.QueueLength
	case "system_load":
		categoryConfig = &cfg.RL.StateDiscretization.SystemLoad
		// Convert from [0,1] to [0,100] for percentage-based boundaries
		value *= 100.0
	case "task_priority":
		categoryConfig = &cfg.RL.StateDiscretization.TaskPriority
	default:
		return "", -1, fmt.Errorf("unknown feature: %s", featureName)
	}

	categoryName := categoryConfig.GetCategoryName(value)
	categoryIndex := categoryConfig.GetCategoryIndex(value)

	return categoryName, categoryIndex, nil
}

// GetStateKey generates a string key for the state (for Q-table indexing) with caching
func (sf *StateFeatures) GetStateKey() string {
	// Return cached key if available and not dirty
	if !sf.keyDirty && sf.cachedStateKey != "" {
		return sf.cachedStateKey
	}

	cfg := config.GetConfig()

	// Generate new key
	var newKey string
	if cfg.RL.StateDiscretization.Enabled {
		newKey = sf.getFuzzyStateKey()
	} else {
		newKey = sf.getLegacyStateKey()
	}

	// Cache the result
	sf.cachedStateKey = newKey
	sf.keyDirty = false

	return newKey
}

// InvalidateCache marks the state key cache as dirty
func (sf *StateFeatures) InvalidateCache() {
	sf.keyDirty = true
}

// getFuzzyStateKey generates state key using configurable fuzzy categories
func (sf *StateFeatures) getFuzzyStateKey() string {
	cfg := config.GetConfig()

	// Get category indices for each feature
	cpuIdx := cfg.RL.StateDiscretization.CPUUtilization.GetCategoryIndex(sf.CPUUtilization * 100.0)
	memIdx := cfg.RL.StateDiscretization.MemoryUtilization.GetCategoryIndex(sf.MemoryUtilization * 100.0)
	queueIdx := cfg.RL.StateDiscretization.QueueLength.GetCategoryIndex(float64(sf.QueueLength))
	loadIdx := cfg.RL.StateDiscretization.SystemLoad.GetCategoryIndex(sf.SystemLoad * 100.0)
	priorityIdx := cfg.RL.StateDiscretization.TaskPriority.GetCategoryIndex(sf.AvgPriority)

	// Create time bucket (6-hour periods: 0-5, 6-11, 12-17, 18-23)
	timeBucket := sf.TimeOfDay / 6

	// Get cache category index (simple mapping)
	cacheIdx := sf.getCacheCategoryIndex()

	return fmt.Sprintf("c%d_m%d_q%d_l%d_p%d_t%d_ch%d",
		cpuIdx, memIdx, queueIdx, loadIdx, priorityIdx, timeBucket, cacheIdx)
}

// getCacheCategoryIndex returns a simple index for cache category
func (sf *StateFeatures) getCacheCategoryIndex() int {
	switch sf.CacheCategory {
	case "none":
		return 0
	case "low":
		return 1
	case "medium":
		return 2
	case "high":
		return 3
	case "very_high":
		return 4
	default:
		return 0
	}
}

// getLegacyStateKey generates state key using legacy hardcoded discretization (for backward compatibility)
func (sf *StateFeatures) getLegacyStateKey() string {
	// Discretize continuous values using legacy method
	queueBucket := sf.discretizeValue(float64(sf.QueueLength), 0, 50, 5)
	cpuBucket := sf.discretizeValue(sf.CPUUtilization*100.0, 0, 100, 5)       // Convert to percentage
	memoryBucket := sf.discretizeValue(sf.MemoryUtilization*100.0, 0, 100, 5) // Convert to percentage
	priorityBucket := sf.discretizeValue(sf.AvgPriority, 1, 10, 3)
	loadBucket := sf.discretizeValue(sf.SystemLoad*100.0, 0, 100, 5) // Convert to percentage

	return fmt.Sprintf("q%d_c%d_m%d_p%d_l%d_t%d",
		queueBucket, cpuBucket, memoryBucket, priorityBucket, loadBucket, sf.TimeOfDay/6)
}

// discretizeValue converts a continuous value to discrete buckets (legacy method)
func (sf *StateFeatures) discretizeValue(value, min, max float64, buckets int) int {
	if value <= min {
		return 0
	}
	if value >= max {
		return buckets - 1
	}

	normalized := (value - min) / (max - min)
	bucket := int(normalized * float64(buckets))

	if bucket >= buckets {
		bucket = buckets - 1
	}

	return bucket
}

// discretizeValueLegacy provides legacy discretization for unknown features
func (sf *StateFeatures) discretizeValueLegacy(value float64, featureName string) string {
	// Provide reasonable defaults for unknown features
	switch featureName {
	case "cpu_utilization", "memory_utilization", "system_load":
		if value < 0.3 {
			return "low"
		} else if value < 0.7 {
			return "medium"
		} else if value < 0.9 {
			return "high"
		} else {
			return "critical"
		}
	case "queue_length":
		if value <= 2 {
			return "empty"
		} else if value <= 5 {
			return "light"
		} else if value <= 10 {
			return "moderate"
		} else {
			return "heavy"
		}
	case "task_priority":
		if value <= 3 {
			return "low"
		} else if value <= 6 {
			return "normal"
		} else if value <= 8 {
			return "high"
		} else {
			return "critical"
		}
	default:
		return "unknown"
	}
}

// GetStateKeyWithLabels returns a human-readable state key with category labels
func (sf *StateFeatures) GetStateKeyWithLabels() string {
	cfg := config.GetConfig()

	if cfg.RL.StateDiscretization.Enabled {
		return fmt.Sprintf("CPU:%s_MEM:%s_QUEUE:%s_LOAD:%s_PRIORITY:%s_TIME:%d",
			sf.CPUCategory, sf.MemoryCategory, sf.QueueCategory,
			sf.LoadCategory, sf.PriorityCategory, sf.TimeOfDay/6)
	}

	// Fallback to legacy labels
	return fmt.Sprintf("CPU:bucket_%s_MEM:bucket_%s_QUEUE:bucket_%s_LOAD:bucket_%s_PRIORITY:bucket_%s_TIME:%d",
		sf.discretizeValueLegacy(sf.CPUUtilization, "cpu_utilization"),
		sf.discretizeValueLegacy(sf.MemoryUtilization, "memory_utilization"),
		sf.discretizeValueLegacy(float64(sf.QueueLength), "queue_length"),
		sf.discretizeValueLegacy(sf.SystemLoad, "system_load"),
		sf.discretizeValueLegacy(sf.AvgPriority, "task_priority"),
		sf.TimeOfDay/6)
}

// GetCategorizedFeatures returns a map of feature names to their categories
func (sf *StateFeatures) GetCategorizedFeatures() map[string]string {
	features := make(map[string]string)

	cfg := config.GetConfig()
	if cfg.RL.StateDiscretization.Enabled {
		features["cpu_utilization"] = sf.CPUCategory
		features["memory_utilization"] = sf.MemoryCategory
		features["queue_length"] = sf.QueueCategory
		features["system_load"] = sf.LoadCategory
		features["task_priority"] = sf.PriorityCategory
	} else {
		// Provide legacy categorization
		features["cpu_utilization"] = sf.discretizeValueLegacy(sf.CPUUtilization, "cpu_utilization")
		features["memory_utilization"] = sf.discretizeValueLegacy(sf.MemoryUtilization, "memory_utilization")
		features["queue_length"] = sf.discretizeValueLegacy(float64(sf.QueueLength), "queue_length")
		features["system_load"] = sf.discretizeValueLegacy(sf.SystemLoad, "system_load")
		features["task_priority"] = sf.discretizeValueLegacy(sf.AvgPriority, "task_priority")
	}

	return features
}

// GetNormalizedFeatures returns normalized feature vector for ML algorithms
func (sf *StateFeatures) GetNormalizedFeatures() []float64 {
	features := []float64{
		sf.normalizeQueueLength(float64(sf.QueueLength)),
		sf.CPUUtilization,    // Already normalized [0,1]
		sf.MemoryUtilization, // Already normalized [0,1]
		sf.normalizeTime(sf.AvgWaitingTime),
		sf.normalizeTime(sf.AvgExecutionTime),
		sf.normalizePriority(sf.AvgPriority),
		sf.HighPriorityRatio, // Already normalized [0,1]
		sf.ShortTaskRatio,    // Already normalized [0,1]
		sf.UrgentTaskRatio,   // Already normalized [0,1]
		sf.SystemLoad,        // Already normalized [0,1]
		sf.ResourcePressure,  // Already normalized [0,1]
		sf.normalizeHour(float64(sf.TimeOfDay)),
		sf.normalizeDay(float64(sf.DayOfWeek)),
		sf.normalizeThroughput(sf.RecentThroughput),
		sf.normalizeLatency(sf.RecentLatency),
		sf.RepeatedTaskRatio, // Already normalized [0,1]
	}

	return features
}

// Normalization helper functions
func (sf *StateFeatures) normalizeQueueLength(length float64) float64 {
	// Assume max queue length of 100
	return math.Min(length/100.0, 1.0)
}

func (sf *StateFeatures) normalizeTime(timeSeconds float64) float64 {
	// Normalize to [0,1] assuming max time of 1 hour (3600 seconds)
	return math.Min(timeSeconds/3600.0, 1.0)
}

func (sf *StateFeatures) normalizePriority(priority float64) float64 {
	// Assume priority range [1,10]
	return (priority - 1.0) / 9.0
}

func (sf *StateFeatures) normalizeHour(hour float64) float64 {
	return hour / 23.0
}

func (sf *StateFeatures) normalizeDay(day float64) float64 {
	return day / 6.0
}

func (sf *StateFeatures) normalizeThroughput(throughput float64) float64 {
	// Normalize assuming max throughput of 50 tasks/unit
	return math.Min(throughput/50.0, 1.0)
}

func (sf *StateFeatures) normalizeLatency(latency float64) float64 {
	// Normalize assuming max latency of 10 minutes (600 seconds)
	return math.Min(latency/600.0, 1.0)
}

// GetStateSize returns the number of features in the state vector
func GetStateSize() int {
	// Count of features in GetNormalizedFeatures
	return 16
}

// StateComparator compares two states for similarity
func (sf *StateFeatures) IsSimilar(other *StateFeatures, threshold float64) bool {
	features1 := sf.GetNormalizedFeatures()
	features2 := other.GetNormalizedFeatures()

	if len(features1) != len(features2) {
		return false
	}

	// Calculate Euclidean distance
	sumSquaredDiff := 0.0
	for i := 0; i < len(features1); i++ {
		diff := features1[i] - features2[i]
		sumSquaredDiff += diff * diff
	}

	distance := math.Sqrt(sumSquaredDiff)
	return distance <= threshold
}

// Clone creates a deep copy of the state features
func (sf *StateFeatures) Clone() *StateFeatures {
	return &StateFeatures{
		QueueLength:       sf.QueueLength,
		AvgWaitingTime:    sf.AvgWaitingTime,
		AvgExecutionTime:  sf.AvgExecutionTime,
		AvgPriority:       sf.AvgPriority,
		CPUUtilization:    sf.CPUUtilization,
		MemoryUtilization: sf.MemoryUtilization,
		HighPriorityRatio: sf.HighPriorityRatio,
		ShortTaskRatio:    sf.ShortTaskRatio,
		UrgentTaskRatio:   sf.UrgentTaskRatio,
		SystemLoad:        sf.SystemLoad,
		ResourcePressure:  sf.ResourcePressure,
		TimeOfDay:         sf.TimeOfDay,
		DayOfWeek:         sf.DayOfWeek,
		RecentThroughput:  sf.RecentThroughput,
		RecentLatency:     sf.RecentLatency,
		CPUCategory:       sf.CPUCategory,
		MemoryCategory:    sf.MemoryCategory,
		QueueCategory:     sf.QueueCategory,
		LoadCategory:      sf.LoadCategory,
		PriorityCategory:  sf.PriorityCategory,
		RepeatedTaskRatio: sf.RepeatedTaskRatio,
		CacheCategory:     sf.CacheCategory,
		Timestamp:         sf.Timestamp,
	}
}

// String returns a string representation of the state
func (sf *StateFeatures) String() string {
	cfg := config.GetConfig()

	if cfg.RL.StateDiscretization.Enabled {
		return fmt.Sprintf("State{Queue:%d(%s), CPU:%.2f(%s), Mem:%.2f(%s), Load:%.2f(%s), Priority:%.2f(%s)}",
			sf.QueueLength, sf.QueueCategory,
			sf.CPUUtilization, sf.CPUCategory,
			sf.MemoryUtilization, sf.MemoryCategory,
			sf.SystemLoad, sf.LoadCategory,
			sf.AvgPriority, sf.PriorityCategory)
	}

	return fmt.Sprintf("State{Queue:%d, CPU:%.2f, Mem:%.2f, Load:%.2f, Priority:%.2f}",
		sf.QueueLength, sf.CPUUtilization, sf.MemoryUtilization, sf.SystemLoad, sf.AvgPriority)
}

// ValidateStateDiscretization validates the state discretization configuration
func ValidateStateDiscretization() error {
	cfg := config.GetConfig()

	if !cfg.RL.StateDiscretization.Enabled {
		return nil // No validation needed if disabled
	}

	// Validate each category configuration
	if err := cfg.RL.StateDiscretization.CPUUtilization.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("cpu utilization categories invalid: %w", err)
	}

	if err := cfg.RL.StateDiscretization.MemoryUtilization.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("memory utilization categories invalid: %w", err)
	}

	if err := cfg.RL.StateDiscretization.QueueLength.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("queue length categories invalid: %w", err)
	}

	if err := cfg.RL.StateDiscretization.SystemLoad.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("system load categories invalid: %w", err)
	}

	if err := cfg.RL.StateDiscretization.TaskPriority.ValidateCategoryConfig(); err != nil {
		return fmt.Errorf("task priority categories invalid: %w", err)
	}

	return nil
}
