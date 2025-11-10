package rl

import (
	"fmt"
	"math"
	"sort"
	"time"

	pb "scheduler-grpc-server/api/proto"
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

	// Performance optimization: cached state key
	cachedStateKey string
	keyDirty       bool

	// Additional context
	Timestamp time.Time `json:"timestamp"`
}

// ExtractStateFeatures extracts state features from current tasks and node status
// Note: Cache-related features are excluded from scheduling state (cache has its own agent)
func ExtractStateFeatures(tasks []TaskEntry, nodeManager SingleNodeManager) *StateFeatures {
	// [DEBUG] Entry point for ExtractStateFeatures
	fmt.Printf("[DEBUG] [STATE-EXTRACT-ENTRY] ExtractStateFeatures called with %d tasks\n", len(tasks))
	
	state := &StateFeatures{
		Timestamp: time.Now(),
	}
	// [DEBUG] State struct created
	fmt.Printf("[DEBUG] [STATE-EXTRACT-CREATE] State struct created\n")

	// [DEBUG] Extract time-based features
	// Extract time-based features
	now := time.Now()
	state.TimeOfDay = now.Hour()
	state.DayOfWeek = int(now.Weekday())
	fmt.Printf("[DEBUG] [STATE-EXTRACT-TIME] Time features: TimeOfDay=%d, DayOfWeek=%d\n", state.TimeOfDay, state.DayOfWeek)

	// [DEBUG] Queue characteristics
	// Queue characteristics
	state.QueueLength = len(tasks)
	fmt.Printf("[DEBUG] [STATE-EXTRACT-QUEUE] Queue length: %d\n", state.QueueLength)

	// [DEBUG] Calculate task statistics
	if len(tasks) > 0 {
		fmt.Printf("[DEBUG] [STATE-EXTRACT-STATS-BEFORE] About to calculate task statistics\n")
		state.calculateTaskStatistics(tasks)
		// [DEBUG] Task statistics calculated
		fmt.Printf("[DEBUG] [STATE-EXTRACT-STATS-AFTER] Task statistics calculated: AvgWait=%.2f, AvgExec=%.2f, AvgPriority=%.2f\n",
			state.AvgWaitingTime, state.AvgExecutionTime, state.AvgPriority)
		
		fmt.Printf("[DEBUG] [STATE-EXTRACT-DIST-BEFORE] About to calculate task distribution\n")
		state.calculateTaskDistribution(tasks)
		// [DEBUG] Task distribution calculated
		fmt.Printf("[DEBUG] [STATE-EXTRACT-DIST-AFTER] Task distribution calculated: HighPriority=%.2f, ShortTask=%.2f, Urgent=%.2f\n",
			state.HighPriorityRatio, state.ShortTaskRatio, state.UrgentTaskRatio)
	} else {
		// [DEBUG] No tasks
		fmt.Printf("[DEBUG] [STATE-EXTRACT-NO-TASKS] No tasks, skipping statistics calculation\n")
	}

	// [DEBUG] Resource utilization
	// NOTE: During scheduling, node status is not available (tasks haven't executed yet)
	// CPU/Memory will be 0.0, but this is OK because:
	// 1. Queue length and task priorities are the main features for scheduling decisions
	// 2. Node status will be available in completion report for delayed reward calculation
	if nodeManager != nil {
		fmt.Printf("[DEBUG] [STATE-EXTRACT-NODE-BEFORE] About to get node manager metrics\n")
		// Keep for backward compatibility, but values will be 0.0
		// Real node status comes from completion report
		state.CPUUtilization = nodeManager.GetCPUUtilization() // Will be 0.0
		state.MemoryUtilization = nodeManager.GetMemoryUtilization() // Will be 0.0
		state.SystemLoad = (state.CPUUtilization + state.MemoryUtilization) / 2.0
		state.ResourcePressure = math.Max(state.CPUUtilization, state.MemoryUtilization)
		// [DEBUG] Node metrics retrieved
		fmt.Printf("[DEBUG] [STATE-EXTRACT-NODE-AFTER] Node metrics: CPU=%.2f, Memory=%.2f, Load=%.2f, Pressure=%.2f\n",
			state.CPUUtilization, state.MemoryUtilization, state.SystemLoad, state.ResourcePressure)
		fmt.Printf("[DEBUG] [STATE-EXTRACT-SCHEDULING] Node status not available during scheduling (CPU=%.2f%%, Mem=%.2f%%) - will use completion report for delayed reward\n",
			state.CPUUtilization*100, state.MemoryUtilization*100)

		// Performance indicators (placeholder - would be calculated from historical data)
		state.RecentThroughput = float64(state.QueueLength) / 10.0 // Simplified
		state.RecentLatency = state.AvgWaitingTime + state.AvgExecutionTime
		// [DEBUG] Performance indicators calculated
		fmt.Printf("[DEBUG] [STATE-EXTRACT-PERF] Performance indicators: Throughput=%.2f, Latency=%.2f\n",
			state.RecentThroughput, state.RecentLatency)
	} else {
		// [DEBUG] No node manager
		fmt.Printf("[DEBUG] [STATE-EXTRACT-NO-NODE] Node manager is nil\n")
	}

	// [DEBUG] Apply fuzzy categorization
	// Apply fuzzy categorization if enabled
	fmt.Printf("[DEBUG] [STATE-EXTRACT-FUZZY-BEFORE] About to apply fuzzy categories\n")
	state.applyFuzzyCategories()
	// [DEBUG] Fuzzy categories applied
	fmt.Printf("[DEBUG] [STATE-EXTRACT-FUZZY-AFTER] Fuzzy categories: CPU=%s, Memory=%s, Queue=%s, Load=%s, Priority=%s\n",
		state.CPUCategory, state.MemoryCategory, state.QueueCategory, state.LoadCategory, state.PriorityCategory)

	// [DEBUG] About to return
	fmt.Printf("[DEBUG] [STATE-EXTRACT-EXIT] ExtractStateFeatures returning state with QueueLength=%d\n", state.QueueLength)
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

	// Mark state key as dirty since categories changed
	sf.keyDirty = true
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

	return fmt.Sprintf("c%d_m%d_q%d_l%d_p%d_t%d",
		cpuIdx, memIdx, queueIdx, loadIdx, priorityIdx, timeBucket)
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
	// Count of features in GetNormalizedFeatures (cache features removed)
	return 15
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

// ExtractStateFeaturesFromNodeStatus creates state features from node status at completion time
// This is used for delayed reward calculation when we have node status from completion report
func ExtractStateFeaturesFromNodeStatus(
	tasks []TaskEntry,
	nodeStatus *pb.FogNode,
	queueLength int,
) *StateFeatures {
	state := &StateFeatures{
		QueueLength: queueLength,
		Timestamp:   time.Now(),
	}

	// Extract CPU/Memory from node status
	if nodeStatus != nil && nodeStatus.CurrentUsage != nil {
		// CPU utilization (already percentage 0-100)
		state.CPUUtilization = float64(nodeStatus.CurrentUsage.CpuUsage) / 100.0 // Convert to 0.0-1.0

		// Memory utilization (calculate percentage)
		// Safety check: ensure Capacity exists and is valid
		if nodeStatus.Capacity != nil && nodeStatus.Capacity.MemoryMb > 0 {
			// Calculate percentage: actual MB used / total MB capacity
			state.MemoryUtilization = float64(nodeStatus.CurrentUsage.MemoryUsageMb) / float64(nodeStatus.Capacity.MemoryMb)
			// Clamp to [0.0, 1.0] to handle edge cases
			if state.MemoryUtilization < 0.0 {
				state.MemoryUtilization = 0.0
			}
			if state.MemoryUtilization > 1.0 {
				state.MemoryUtilization = 1.0
			}
		} else {
			// Fallback: if capacity is missing or invalid, set to 0
			state.MemoryUtilization = 0.0
		}

		// System load = average of CPU and Memory
		state.SystemLoad = (state.CPUUtilization + state.MemoryUtilization) / 2.0
		state.ResourcePressure = math.Max(state.CPUUtilization, state.MemoryUtilization)
	} else {
		// If node status is missing, set all resource metrics to 0
		state.CPUUtilization = 0.0
		state.MemoryUtilization = 0.0
		state.SystemLoad = 0.0
		state.ResourcePressure = 0.0
	}

	// Calculate task statistics (if tasks provided)
	if len(tasks) > 0 {
		state.calculateTaskStatistics(tasks)
	}

	// Apply fuzzy categorization
	state.applyFuzzyCategories()

	return state
}
