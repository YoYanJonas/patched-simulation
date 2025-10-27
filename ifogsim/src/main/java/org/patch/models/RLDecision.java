package org.patch.models;

import java.util.*;

/**
 * Represents decisions received from RL agents
 */
public class RLDecision {
    // Decision type
    private String decisionType;

    // Task scheduling decisions
    private Map<Integer, Integer> taskPriorities = new HashMap<>();
    private Integer selectedTaskId;
    private Integer selectedTaskIndex;

    // Task placement decisions
    private Map<String, Integer> modulePlacements = new HashMap<>();

    // Resource allocation decisions
    private Map<String, Double> resourceAllocations = new HashMap<>();

    /**
     * Default constructor
     */
    public RLDecision() {
    }

    /**
     * Constructor with decision type
     * 
     * @param decisionType Type of decision ("scheduling", "placement", "resource")
     */
    public RLDecision(String decisionType) {
        this.decisionType = decisionType;
    }

    /**
     * Create a scheduling decision with task index
     * 
     * @param taskIndex Index of task in queue to schedule
     * @return RLDecision object
     */
    public static RLDecision createSchedulingDecisionWithIndex(int taskIndex) {
        RLDecision decision = new RLDecision("scheduling");
        decision.setSelectedTaskIndex(taskIndex);
        return decision;
    }

    /**
     * Create a scheduling decision with task ID
     * 
     * @param taskId ID of task to schedule
     * @return RLDecision object
     */
    public static RLDecision createSchedulingDecisionWithId(int taskId) {
        RLDecision decision = new RLDecision("scheduling");
        decision.setSelectedTaskId(taskId);
        return decision;
    }

    /**
     * Create a scheduling decision with priorities
     * 
     * @param taskPriorities Map of task IDs to priority values
     * @return RLDecision object
     */
    public static RLDecision createPriorityDecision(Map<Integer, Integer> taskPriorities) {
        RLDecision decision = new RLDecision("priority");
        decision.setTaskPriorities(taskPriorities);
        return decision;
    }

    /**
     * Create a placement decision
     * 
     * @param modulePlacements Map of module names to device IDs
     * @return RLDecision object
     */
    public static RLDecision createPlacementDecision(Map<String, Integer> modulePlacements) {
        RLDecision decision = new RLDecision("placement");
        decision.setModulePlacements(modulePlacements);
        return decision;
    }

    /**
     * Create a resource allocation decision
     * 
     * @param resourceAllocations Map of resource names to allocation values
     * @return RLDecision object
     */
    public static RLDecision createResourceDecision(Map<String, Double> resourceAllocations) {
        RLDecision decision = new RLDecision("resource");
        decision.setResourceAllocations(resourceAllocations);
        return decision;
    }

    /**
     * Parse a decision from a map (e.g., from JSON)
     * 
     * @param map Map representation of the decision
     * @return RLDecision object
     */
    @SuppressWarnings("unchecked")
    public static RLDecision fromMap(Map<String, Object> map) {
        String decisionType = (String) map.get("decisionType");
        RLDecision decision = new RLDecision(decisionType);

        if (map.containsKey("selectedTaskId")) {
            decision.setSelectedTaskId(((Number) map.get("selectedTaskId")).intValue());
        }

        if (map.containsKey("selectedTaskIndex")) {
            decision.setSelectedTaskIndex(((Number) map.get("selectedTaskIndex")).intValue());
        }

        if (map.containsKey("taskPriorities")) {
            Map<String, Number> priorities = (Map<String, Number>) map.get("taskPriorities");
            Map<Integer, Integer> taskPriorities = new HashMap<>();

            for (Map.Entry<String, Number> entry : priorities.entrySet()) {
                taskPriorities.put(Integer.parseInt(entry.getKey()), entry.getValue().intValue());
            }

            decision.setTaskPriorities(taskPriorities);
        }

        if (map.containsKey("modulePlacements")) {
            Map<String, Number> placements = (Map<String, Number>) map.get("modulePlacements");
            Map<String, Integer> modulePlacements = new HashMap<>();

            for (Map.Entry<String, Number> entry : placements.entrySet()) {
                modulePlacements.put(entry.getKey(), entry.getValue().intValue());
            }

            decision.setModulePlacements(modulePlacements);
        }

        if (map.containsKey("resourceAllocations")) {
            Map<String, Number> allocations = (Map<String, Number>) map.get("resourceAllocations");
            Map<String, Double> resourceAllocations = new HashMap<>();

            for (Map.Entry<String, Number> entry : allocations.entrySet()) {
                resourceAllocations.put(entry.getKey(), entry.getValue().doubleValue());
            }

            decision.setResourceAllocations(resourceAllocations);
        }

        return decision;
    }

    /**
     * Convert to a map for JSON serialization
     * 
     * @return Map representation of the decision
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("decisionType", decisionType);

        if (selectedTaskId != null) {
            map.put("selectedTaskId", selectedTaskId);
        }

        if (selectedTaskIndex != null) {
            map.put("selectedTaskIndex", selectedTaskIndex);
        }

        if (!taskPriorities.isEmpty()) {
            Map<String, Integer> priorities = new HashMap<>();
            for (Map.Entry<Integer, Integer> entry : taskPriorities.entrySet()) {
                priorities.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            map.put("taskPriorities", priorities);
        }

        if (!modulePlacements.isEmpty()) {
            map.put("modulePlacements", modulePlacements);
        }

        if (!resourceAllocations.isEmpty()) {
            map.put("resourceAllocations", resourceAllocations);
        }

        return map;
    }

    // Getters and setters

    public String getDecisionType() {
        return decisionType;
    }

    public void setDecisionType(String decisionType) {
        this.decisionType = decisionType;
    }

    public Map<Integer, Integer> getTaskPriorities() {
        return taskPriorities;
    }

    public void setTaskPriorities(Map<Integer, Integer> taskPriorities) {
        this.taskPriorities = taskPriorities;
    }

    public void addTaskPriority(int taskId, int priority) {
        this.taskPriorities.put(taskId, priority);
    }

    public Integer getSelectedTaskId() {
        return selectedTaskId;
    }

    public void setSelectedTaskId(Integer selectedTaskId) {
        this.selectedTaskId = selectedTaskId;
    }

    public Integer getSelectedTaskIndex() {
        return selectedTaskIndex;
    }

    public void setSelectedTaskIndex(Integer selectedTaskIndex) {
        this.selectedTaskIndex = selectedTaskIndex;
    }

    public Map<String, Integer> getModulePlacements() {
        return modulePlacements;
    }

    public void setModulePlacements(Map<String, Integer> modulePlacements) {
        this.modulePlacements = modulePlacements;
    }

    public void addModulePlacement(String moduleName, int deviceId) {
        this.modulePlacements.put(moduleName, deviceId);
    }

    public Map<String, Double> getResourceAllocations() {
        return resourceAllocations;
    }

    public void setResourceAllocations(Map<String, Double> resourceAllocations) {
        this.resourceAllocations = resourceAllocations;
    }

    public void addResourceAllocation(String resource, double value) {
        this.resourceAllocations.put(resource, value);
    }

    /**
     * Check if this is a scheduling decision
     */
    public boolean isSchedulingDecision() {
        return "scheduling".equals(decisionType);
    }

    /**
     * Check if this is a priority decision
     */
    public boolean isPriorityDecision() {
        return "priority".equals(decisionType);
    }

    /**
     * Check if this is a placement decision
     */
    public boolean isPlacementDecision() {
        return "placement".equals(decisionType);
    }

    /**
     * Check if this is a resource decision
     */
    public boolean isResourceDecision() {
        return "resource".equals(decisionType);
    }
}