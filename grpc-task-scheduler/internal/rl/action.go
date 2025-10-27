package rl

// ActionType represents different types of scheduling actions
type ActionType int

const (
	ActionNone ActionType = iota
	ActionScheduleNext
	ActionReorder
	ActionDelay
	ActionPriorityBoost
	ActionPromoteHighPriority
	ActionPromoteShortJobs
	ActionBalancedScheduling
	ActionDeadlineAware
	ActionResourceOptimized
)

// Action represents a scheduling decision
type Action struct {
	Type        ActionType             `json:"type"`
	Description string                 `json:"description"`
	Priority    float64                `json:"priority"`
	Parameters  map[string]interface{} `json:"parameters,omitempty"`
}

// GetAllActions returns all possible actions
func GetAllActions() []Action {
	return []Action{
		{Type: ActionNone, Description: "No action - use default algorithm", Priority: 0.0},
		{Type: ActionScheduleNext, Description: "Schedule next task immediately", Priority: 0.5},
		{Type: ActionReorder, Description: "Reorder task queue for optimization", Priority: 0.7},
		{Type: ActionDelay, Description: "Delay task execution", Priority: 0.3},
		{Type: ActionPriorityBoost, Description: "Boost priority of selected tasks", Priority: 0.8},
		{Type: ActionPromoteHighPriority, Description: "Promote high priority tasks to front", Priority: 0.6},
		{Type: ActionPromoteShortJobs, Description: "Promote short execution time tasks", Priority: 0.6},
		{Type: ActionBalancedScheduling, Description: "Balance priority and execution time", Priority: 0.7},
		{Type: ActionDeadlineAware, Description: "Prioritize tasks close to deadline", Priority: 0.9},
		{Type: ActionResourceOptimized, Description: "Optimize for resource utilization", Priority: 0.8},
	}
}

// GetActionSize returns the number of possible actions
func GetActionSize() int {
	return len(GetAllActions())
}

// ApplyAction applies the chosen action to reorder the task queue
func ApplyAction(action Action, tasks []TaskEntry) []TaskEntry {
	if len(tasks) <= 1 {
		return tasks
	}

	reordered := make([]TaskEntry, len(tasks))
	copy(reordered, tasks)

	switch action.Type {
	case ActionNone:
		return reordered

	case ActionScheduleNext:
		return sortByPriority(reordered)

	case ActionReorder:
		if action.Priority > 0.7 {
			return sortByBalanced(reordered)
		}
		return sortByPriority(reordered)

	case ActionDelay:
		return sortByPriority(reordered) // High priority first = delay low priority

	case ActionPriorityBoost:
		return sortByUrgency(reordered)

	case ActionPromoteHighPriority:
		return sortByPriority(reordered)

	case ActionPromoteShortJobs:
		return sortByShortestJob(reordered)

	case ActionBalancedScheduling:
		return sortByBalanced(reordered)

	case ActionDeadlineAware:
		return sortByDeadline(reordered)

	case ActionResourceOptimized:
		return sortByResource(reordered)

	default:
		return reordered
	}
}

// Helper sorting functions with optimized algorithms
func sortByPriority(tasks []TaskEntry) []TaskEntry {
	if len(tasks) <= 1 {
		return tasks
	}
	
	// Use optimized quicksort for better performance
	return quickSortByPriority(tasks, 0, len(tasks)-1)
}

func sortByShortestJob(tasks []TaskEntry) []TaskEntry {
	if len(tasks) <= 1 {
		return tasks
	}
	
	// Use optimized quicksort for better performance
	return quickSortByExecutionTime(tasks, 0, len(tasks)-1)
}

// quickSortByPriority implements optimized quicksort for priority-based sorting
func quickSortByPriority(tasks []TaskEntry, low, high int) []TaskEntry {
	if low < high {
		pi := partitionByPriority(tasks, low, high)
		quickSortByPriority(tasks, low, pi-1)
		quickSortByPriority(tasks, pi+1, high)
	}
	return tasks
}

// partitionByPriority partitions tasks by priority for quicksort
func partitionByPriority(tasks []TaskEntry, low, high int) int {
	pivot := tasks[high].GetPriority()
	i := low - 1
	
	for j := low; j < high; j++ {
		if tasks[j].GetPriority() >= pivot {
			i++
			tasks[i], tasks[j] = tasks[j], tasks[i]
		}
	}
	tasks[i+1], tasks[high] = tasks[high], tasks[i+1]
	return i + 1
}

// quickSortByExecutionTime implements optimized quicksort for execution time sorting
func quickSortByExecutionTime(tasks []TaskEntry, low, high int) []TaskEntry {
	if low < high {
		pi := partitionByExecutionTime(tasks, low, high)
		quickSortByExecutionTime(tasks, low, pi-1)
		quickSortByExecutionTime(tasks, pi+1, high)
	}
	return tasks
}

// partitionByExecutionTime partitions tasks by execution time for quicksort
func partitionByExecutionTime(tasks []TaskEntry, low, high int) int {
	pivot := tasks[high].GetExecutionTimeMs()
	i := low - 1
	
	for j := low; j < high; j++ {
		if tasks[j].GetExecutionTimeMs() <= pivot {
			i++
			tasks[i], tasks[j] = tasks[j], tasks[i]
		}
	}
	tasks[i+1], tasks[high] = tasks[high], tasks[i+1]
	return i + 1
}

func sortByBalanced(tasks []TaskEntry) []TaskEntry {
	n := len(tasks)
	for i := 0; i < n-1; i++ {
		for j := 0; j < n-i-1; j++ {
			scoreA := float64(tasks[j].GetPriority())*0.6 + (1000.0/float64(tasks[j].GetExecutionTimeMs()))*0.4
			scoreB := float64(tasks[j+1].GetPriority())*0.6 + (1000.0/float64(tasks[j+1].GetExecutionTimeMs()))*0.4
			if scoreA < scoreB {
				tasks[j], tasks[j+1] = tasks[j+1], tasks[j]
			}
		}
	}
	return tasks
}

func sortByDeadline(tasks []TaskEntry) []TaskEntry {
	n := len(tasks)
	for i := 0; i < n-1; i++ {
		for j := 0; j < n-i-1; j++ {
			if tasks[j].GetDeadline() > tasks[j+1].GetDeadline() {
				tasks[j], tasks[j+1] = tasks[j+1], tasks[j]
			}
		}
	}
	return tasks
}

func sortByResource(tasks []TaskEntry) []TaskEntry {
	n := len(tasks)
	for i := 0; i < n-1; i++ {
		for j := 0; j < n-i-1; j++ {
			resourceA := tasks[j].GetCPURequirement() + float64(tasks[j].GetMemoryRequirement())/100.0
			resourceB := tasks[j+1].GetCPURequirement() + float64(tasks[j+1].GetMemoryRequirement())/100.0
			if resourceA > resourceB {
				tasks[j], tasks[j+1] = tasks[j+1], tasks[j]
			}
		}
	}
	return tasks
}

func sortByUrgency(tasks []TaskEntry) []TaskEntry {
	n := len(tasks)
	for i := 0; i < n-1; i++ {
		for j := 0; j < n-i-1; j++ {
			urgencyA := float64(tasks[j].GetPriority()) * 0.7
			urgencyB := float64(tasks[j+1].GetPriority()) * 0.7

			if tasks[j].GetDeadline() > 0 && tasks[j+1].GetDeadline() > 0 {
				urgencyA += (1.0 / float64(tasks[j].GetDeadline())) * 0.3
				urgencyB += (1.0 / float64(tasks[j+1].GetDeadline())) * 0.3
			}

			if urgencyA < urgencyB {
				tasks[j], tasks[j+1] = tasks[j+1], tasks[j]
			}
		}
	}
	return tasks
}

// CreateAction creates a new action with specified type and priority
func CreateAction(actionType ActionType, priority float64) Action {
	actions := GetAllActions()
	for _, action := range actions {
		if action.Type == actionType {
			action.Priority = priority
			return action
		}
	}
	return actions[0] // Return ActionNone as default
}

// GetActionByType returns an action by its type
func GetActionByType(actionType ActionType) Action {
	actions := GetAllActions()
	for _, action := range actions {
		if action.Type == actionType {
			return action
		}
	}
	return actions[0] // Return ActionNone as default
}
