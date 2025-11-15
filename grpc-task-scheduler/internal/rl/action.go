package rl

import (
	"fmt"
	"scheduler-grpc-server/pkg/logger"
)

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
		{Type: ActionDeadlineAware, Description: "FIFO scheduling (deadline disabled)", Priority: 0.5},
		{Type: ActionResourceOptimized, Description: "Optimize for resource utilization", Priority: 0.8},
	}
}

// GetActionSize returns the number of possible actions
func GetActionSize() int {
	return len(GetAllActions())
}

// ApplyAction applies the chosen action to reorder the task queue
func ApplyAction(action Action, tasks []TaskEntry) []TaskEntry {
	// [DEBUG] Entry point for ApplyAction
	fmt.Printf("[DEBUG] [ACTION-APPLY-ENTRY] ApplyAction called: ActionType=%d, Tasks=%d\n", action.Type, len(tasks))
	
	if len(tasks) <= 1 {
		// [DEBUG] Not enough tasks
		fmt.Printf("[DEBUG] [ACTION-APPLY-SKIP] Skipping (tasks <= 1: %d)\n", len(tasks))
		return tasks
	}

	// [DEBUG] Creating reordered slice
	fmt.Printf("[DEBUG] [ACTION-APPLY-COPY] Creating reordered slice\n")
	reordered := make([]TaskEntry, len(tasks))
	copy(reordered, tasks)
	// [DEBUG] Copy complete
	fmt.Printf("[DEBUG] [ACTION-APPLY-COPY-DONE] Copy complete: %d tasks\n", len(reordered))

	// [DEBUG] Applying action based on type
	fmt.Printf("[DEBUG] [ACTION-APPLY-SWITCH] Applying action: Type=%d, Description=%s\n", action.Type, action.Description)
	logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-SWITCH] 🔄 APPLYING ACTION: Type=%d, Description=%s, Priority=%.2f, Tasks=%d",
		action.Type, action.Description, action.Priority, len(reordered))
	switch action.Type {
	case ActionNone:
		// [DEBUG] No action
		fmt.Printf("[DEBUG] [ACTION-APPLY-NONE] ActionNone: returning original order\n")
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-NONE] ⚠️ Applying ActionNone: returning original order (no sorting)")
		return reordered

	case ActionScheduleNext:
		// [DEBUG] Schedule next
		fmt.Printf("[DEBUG] [ACTION-APPLY-SCHEDULE-NEXT] ActionScheduleNext: sorting by priority\n")
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-SCHEDULE-NEXT] ✅ Applying ActionScheduleNext: sorting by priority (high first)")
		result := sortByPriority(reordered)
		fmt.Printf("[DEBUG] [ACTION-APPLY-SCHEDULE-NEXT-DONE] Sorted by priority: %d tasks\n", len(result))
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-SCHEDULE-NEXT-DONE] ✅ ActionScheduleNext complete: %d tasks sorted by priority", len(result))
		return result

	case ActionReorder:
		// [DEBUG] Reorder
		fmt.Printf("[DEBUG] [ACTION-APPLY-REORDER] ActionReorder: Priority=%.2f\n", action.Priority)
		if action.Priority > 0.7 {
			fmt.Printf("[DEBUG] [ACTION-APPLY-REORDER-BALANCED] Sorting by balanced\n")
			logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-REORDER-BALANCED] ✅ Applying ActionReorder (Priority>0.7): sorting by balanced score")
			result := sortByBalanced(reordered)
			fmt.Printf("[DEBUG] [ACTION-APPLY-REORDER-BALANCED-DONE] Sorted by balanced: %d tasks\n", len(result))
			logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-REORDER-BALANCED-DONE] ✅ ActionReorder (balanced) complete: %d tasks", len(result))
			return result
		}
		fmt.Printf("[DEBUG] [ACTION-APPLY-REORDER-PRIORITY] Sorting by priority\n")
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-REORDER-PRIORITY] ✅ Applying ActionReorder (Priority<=0.7): sorting by priority")
		result := sortByPriority(reordered)
		fmt.Printf("[DEBUG] [ACTION-APPLY-REORDER-PRIORITY-DONE] Sorted by priority: %d tasks\n", len(result))
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-REORDER-PRIORITY-DONE] ✅ ActionReorder (priority) complete: %d tasks", len(result))
		return result

	case ActionDelay:
		// [DEBUG] Delay
		fmt.Printf("[DEBUG] [ACTION-APPLY-DELAY] ActionDelay: sorting by priority (high priority first = delay low priority)\n")
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-DELAY] ✅ Applying ActionDelay: sorting by priority (high first = delay low)")
		result := sortByPriority(reordered)
		fmt.Printf("[DEBUG] [ACTION-APPLY-DELAY-DONE] Sorted by priority: %d tasks\n", len(result))
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-DELAY-DONE] ✅ ActionDelay complete: %d tasks sorted by priority", len(result))
		return result

	case ActionPriorityBoost:
		// [DEBUG] Priority boost
		fmt.Printf("[DEBUG] [ACTION-APPLY-PRIORITY-BOOST] ActionPriorityBoost: sorting by urgency\n")
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-PRIORITY-BOOST] ✅ Applying ActionPriorityBoost: sorting by urgency (priority*0.7 + deadline*0.3)")
		result := sortByUrgency(reordered)
		fmt.Printf("[DEBUG] [ACTION-APPLY-PRIORITY-BOOST-DONE] Sorted by urgency: %d tasks\n", len(result))
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-PRIORITY-BOOST-DONE] ✅ ActionPriorityBoost complete: %d tasks sorted by urgency", len(result))
		return result

	case ActionPromoteHighPriority:
		// [DEBUG] Promote high priority
		fmt.Printf("[DEBUG] [ACTION-APPLY-PROMOTE-HIGH] ActionPromoteHighPriority: sorting by priority\n")
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-PROMOTE-HIGH] ✅ Applying ActionPromoteHighPriority: sorting by priority (high first)")
		result := sortByPriority(reordered)
		fmt.Printf("[DEBUG] [ACTION-APPLY-PROMOTE-HIGH-DONE] Sorted by priority: %d tasks\n", len(result))
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-PROMOTE-HIGH-DONE] ✅ ActionPromoteHighPriority complete: %d tasks sorted by priority", len(result))
		return result

	case ActionPromoteShortJobs:
		// [DEBUG] Promote short jobs
		fmt.Printf("[DEBUG] [ACTION-APPLY-PROMOTE-SHORT] ActionPromoteShortJobs: sorting by shortest job\n")
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-PROMOTE-SHORT] ✅ Applying ActionPromoteShortJobs: sorting by execution time (shortest first)")
		result := sortByShortestJob(reordered)
		fmt.Printf("[DEBUG] [ACTION-APPLY-PROMOTE-SHORT-DONE] Sorted by shortest job: %d tasks\n", len(result))
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-PROMOTE-SHORT-DONE] ✅ ActionPromoteShortJobs complete: %d tasks sorted by shortest job", len(result))
		return result

	case ActionBalancedScheduling:
		// [DEBUG] Balanced scheduling
		fmt.Printf("[DEBUG] [ACTION-APPLY-BALANCED] ActionBalancedScheduling: sorting by balanced\n")
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-BALANCED] ✅ Applying ActionBalancedScheduling: sorting by balanced score (priority*0.6 + throughput*0.4)")
		result := sortByBalanced(reordered)
		fmt.Printf("[DEBUG] [ACTION-APPLY-BALANCED-DONE] Sorted by balanced: %d tasks\n", len(result))
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-BALANCED-DONE] ✅ ActionBalancedScheduling complete: %d tasks sorted by balanced score", len(result))
		return result

	case ActionDeadlineAware:
		// Later Feature: deadline-aware disabled - using FIFO
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-DEADLINE] ⚠️ Applying ActionDeadlineAware: deadline disabled, returning original order (FIFO)")
		logger.GetLogger().Infof("[ACTION] FIFO scheduling (deadline disabled)")
		return reordered // Return unchanged (deadline-aware disabled)

	case ActionResourceOptimized:
		// [DEBUG] Resource optimized
		fmt.Printf("[DEBUG] [ACTION-APPLY-RESOURCE] ActionResourceOptimized: sorting by resource\n")
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-RESOURCE] ✅ Applying ActionResourceOptimized: sorting by resource requirements (CPU+Memory)")
		result := sortByResource(reordered)
		fmt.Printf("[DEBUG] [ACTION-APPLY-RESOURCE-DONE] Sorted by resource: %d tasks\n", len(result))
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-RESOURCE-DONE] ✅ ActionResourceOptimized complete: %d tasks sorted by resource", len(result))
		return result

	default:
		// [DEBUG] Default case
		fmt.Printf("[DEBUG] [ACTION-APPLY-DEFAULT] Unknown action type: %d, returning original order\n", action.Type)
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-DEFAULT] ⚠️ Unknown action type: %d, returning original order (no sorting)", action.Type)
		// [DEBUG] About to return
		fmt.Printf("[DEBUG] [ACTION-APPLY-EXIT] ApplyAction returning %d tasks\n", len(reordered))
		logger.GetLogger().Warnf("[RL-VERIFY] [ACTION-APPLY-EXIT] ✅ ApplyAction complete: %d tasks returned", len(reordered))
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

// Later Feature: deadline-aware sorting disabled
func sortByDeadline(tasks []TaskEntry) []TaskEntry {
	// Deadline-aware disabled: return unchanged (FIFO)
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
