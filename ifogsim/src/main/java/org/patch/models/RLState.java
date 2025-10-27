package org.patch.models;

import org.fog.entities.Tuple;
import java.util.*;

/**
 * Represents the state information sent to RL agents
 */
public class RLState {
    // Device information
    private int deviceId;
    private String deviceName;
    private String deviceType; // "fog" or "cloud"
    private double simulationTime;

    // Resource utilization
    private double cpuUtilization;
    private double ramUtilization;
    private double bwUtilization;
    private double energyConsumption;

    // Moving averages
    private double cpuUtilizationAvg;
    private double ramUtilizationAvg;
    private double bwUtilizationAvg;

    // Task queue information
    private int queueSize;
    private List<TaskInfo> queuedTasks = new ArrayList<>();

    // Network information
    private Map<Integer, DeviceNetworkInfo> connectedDevices = new HashMap<>();

    // Child devices (for cloud or parent fog devices)
    private List<DeviceInfo> childDevices = new ArrayList<>();

    /**
     * Default constructor
     */
    public RLState() {
    }

    /**
     * Constructor with basic device info
     * 
     * @param deviceId       Device ID
     * @param deviceName     Device name
     * @param deviceType     Device type ("fog" or "cloud")
     * @param simulationTime Current simulation time
     */
    public RLState(int deviceId, String deviceName, String deviceType, double simulationTime) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.simulationTime = simulationTime;
    }

    /**
     * Convert to a map for JSON serialization
     * 
     * @return Map representation of the state
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        // Device information
        map.put("deviceId", deviceId);
        map.put("deviceName", deviceName);
        map.put("deviceType", deviceType);
        map.put("simulationTime", simulationTime);

        // Resource utilization
        map.put("cpuUtilization", cpuUtilization);
        map.put("ramUtilization", ramUtilization);
        map.put("bwUtilization", bwUtilization);
        map.put("energyConsumption", energyConsumption);

        // Moving averages
        map.put("cpuUtilizationAvg", cpuUtilizationAvg);
        map.put("ramUtilizationAvg", ramUtilizationAvg);
        map.put("bwUtilizationAvg", bwUtilizationAvg);

        // Task queue information
        map.put("queueSize", queueSize);

        // Convert queued tasks to list of maps
        List<Map<String, Object>> queuedTasksMaps = new ArrayList<>();
        for (TaskInfo task : queuedTasks) {
            queuedTasksMaps.add(task.toMap());
        }
        map.put("queuedTasks", queuedTasksMaps);

        // Convert connected devices to map of maps
        Map<String, Map<String, Object>> connectedDevicesMaps = new HashMap<>();
        for (Map.Entry<Integer, DeviceNetworkInfo> entry : connectedDevices.entrySet()) {
            connectedDevicesMaps.put(String.valueOf(entry.getKey()), entry.getValue().toMap());
        }
        map.put("connectedDevices", connectedDevicesMaps);

        // Convert child devices to list of maps
        List<Map<String, Object>> childDevicesMaps = new ArrayList<>();
        for (DeviceInfo device : childDevices) {
            childDevicesMaps.add(device.toMap());
        }
        map.put("childDevices", childDevicesMaps);

        return map;
    }

    /**
     * Create an RLState from StateCollector and TaskQueue
     * 
     * @param collector  StateCollector with device metrics
     * @param taskQueue  TaskQueue with task information
     * @param deviceType Device type ("fog" or "cloud")
     * @return RLState object
     */
    public static RLState fromCollector(StateCollector collector, TaskQueue taskQueue, String deviceType) {
        Map<String, Object> collectedState = collector.collectState();

        RLState state = new RLState(
                (int) collectedState.get("deviceId"),
                (String) collectedState.get("deviceName"),
                deviceType,
                (double) collectedState.get("simulationTime"));

        // Set resource utilization
        state.setCpuUtilization((double) collectedState.get("cpuUtilization"));
        state.setRamUtilization((double) collectedState.get("ramUtilization"));
        state.setBwUtilization((double) collectedState.get("bwUtilization"));
        state.setEnergyConsumption((double) collectedState.get("energyConsumption"));

        // Set moving averages
        state.setCpuUtilizationAvg((double) collectedState.get("cpuUtilizationAvg"));
        state.setRamUtilizationAvg((double) collectedState.get("ramUtilizationAvg"));
        state.setBwUtilizationAvg((double) collectedState.get("bwUtilizationAvg"));

        // Add task queue information
        state.setQueueSize(taskQueue.size());

        // Add tasks to queue
        List<TaskQueue.TaskInfo> allTasks = taskQueue.getAllTasks();
        for (TaskQueue.TaskInfo taskInfo : allTasks) {
            Tuple tuple = taskInfo.getTuple();
            TaskInfo rlTaskInfo = new TaskInfo(
                    tuple.getCloudletId(),
                    tuple.getAppId(),
                    tuple.getTupleType(),
                    tuple.getDestModuleName(),
                    tuple.getCloudletLength(),
                    tuple.getCloudletFileSize(),
                    tuple.getCloudletOutputSize());
            state.addQueuedTask(rlTaskInfo);
        }

        return state;
    }

    // Getters and setters

    public int getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public double getSimulationTime() {
        return simulationTime;
    }

    public void setSimulationTime(double simulationTime) {
        this.simulationTime = simulationTime;
    }

    public double getCpuUtilization() {
        return cpuUtilization;
    }

    public void setCpuUtilization(double cpuUtilization) {
        this.cpuUtilization = cpuUtilization;
    }

    public double getRamUtilization() {
        return ramUtilization;
    }

    public void setRamUtilization(double ramUtilization) {
        this.ramUtilization = ramUtilization;
    }

    public double getBwUtilization() {
        return bwUtilization;
    }

    public void setBwUtilization(double bwUtilization) {
        this.bwUtilization = bwUtilization;
    }

    public double getEnergyConsumption() {
        return energyConsumption;
    }

    public void setEnergyConsumption(double energyConsumption) {
        this.energyConsumption = energyConsumption;
    }

    public double getCpuUtilizationAvg() {
        return cpuUtilizationAvg;
    }

    public void setCpuUtilizationAvg(double cpuUtilizationAvg) {
        this.cpuUtilizationAvg = cpuUtilizationAvg;
    }

    public double getRamUtilizationAvg() {
        return ramUtilizationAvg;
    }

    public void setRamUtilizationAvg(double ramUtilizationAvg) {
        this.ramUtilizationAvg = ramUtilizationAvg;
    }

    public double getBwUtilizationAvg() {
        return bwUtilizationAvg;
    }

    public void setBwUtilizationAvg(double bwUtilizationAvg) {
        this.bwUtilizationAvg = bwUtilizationAvg;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public List<TaskInfo> getQueuedTasks() {
        return queuedTasks;
    }

    public void setQueuedTasks(List<TaskInfo> queuedTasks) {
        this.queuedTasks = queuedTasks;
    }

    public void addQueuedTask(TaskInfo task) {
        this.queuedTasks.add(task);
    }

    public Map<Integer, DeviceNetworkInfo> getConnectedDevices() {
        return connectedDevices;
    }

    public void setConnectedDevices(Map<Integer, DeviceNetworkInfo> connectedDevices) {
        this.connectedDevices = connectedDevices;
    }

    public void addConnectedDevice(int deviceId, DeviceNetworkInfo info) {
        this.connectedDevices.put(deviceId, info);
    }

    public List<DeviceInfo> getChildDevices() {
        return childDevices;
    }

    public void setChildDevices(List<DeviceInfo> childDevices) {
        this.childDevices = childDevices;
    }

    public void addChildDevice(DeviceInfo device) {
        this.childDevices.add(device);
    }

    /**
     * Inner class to represent task information
     */
    public static class TaskInfo {
        private int taskId;
        private String appId;
        private String tupleType;
        private String moduleName;
        private long length;
        private long inputSize;
        private long outputSize;

        public TaskInfo() {
        }

        public TaskInfo(int taskId, String appId, String tupleType, String moduleName,
                long length, long inputSize, long outputSize) {
            this.taskId = taskId;
            this.appId = appId;
            this.tupleType = tupleType;
            this.moduleName = moduleName;
            this.length = length;
            this.inputSize = inputSize;
            this.outputSize = outputSize;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("taskId", taskId);
            map.put("appId", appId);
            map.put("tupleType", tupleType);
            map.put("moduleName", moduleName);
            map.put("length", length);
            map.put("inputSize", inputSize);
            map.put("outputSize", outputSize);
            return map;
        }

        // Getters and setters

        public int getTaskId() {
            return taskId;
        }

        public void setTaskId(int taskId) {
            this.taskId = taskId;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getTupleType() {
            return tupleType;
        }

        public void setTupleType(String tupleType) {
            this.tupleType = tupleType;
        }

        public String getModuleName() {
            return moduleName;
        }

        public void setModuleName(String moduleName) {
            this.moduleName = moduleName;
        }

        public long getLength() {
            return length;
        }

        public void setLength(long length) {
            this.length = length;
        }

        public long getInputSize() {
            return inputSize;
        }

        public void setInputSize(long inputSize) {
            this.inputSize = inputSize;
        }

        public long getOutputSize() {
            return outputSize;
        }

        public void setOutputSize(long outputSize) {
            this.outputSize = outputSize;
        }
    }

    /**
     * Inner class to represent device network information
     */
    public static class DeviceNetworkInfo {
        private int deviceId;
        private String deviceName;
        private double uplinkLatency;
        private double downlinkLatency;
        private long uplinkBandwidth;
        private long downlinkBandwidth;

        public DeviceNetworkInfo() {
        }

        public DeviceNetworkInfo(int deviceId, String deviceName, double uplinkLatency,
                double downlinkLatency, long uplinkBandwidth, long downlinkBandwidth) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.uplinkLatency = uplinkLatency;
            this.downlinkLatency = downlinkLatency;
            this.uplinkBandwidth = uplinkBandwidth;
            this.downlinkBandwidth = downlinkBandwidth;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("deviceId", deviceId);
            map.put("deviceName", deviceName);
            map.put("uplinkLatency", uplinkLatency);
            map.put("downlinkLatency", downlinkLatency);
            map.put("uplinkBandwidth", uplinkBandwidth);
            map.put("downlinkBandwidth", downlinkBandwidth);
            return map;
        }

        // Getters and setters

        public int getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(int deviceId) {
            this.deviceId = deviceId;
        }

        public String getDeviceName() {
            return deviceName;
        }

        public void setDeviceName(String deviceName) {
            this.deviceName = deviceName;
        }

        public double getUplinkLatency() {
            return uplinkLatency;
        }

        public void setUplinkLatency(double uplinkLatency) {
            this.uplinkLatency = uplinkLatency;
        }

        public double getDownlinkLatency() {
            return downlinkLatency;
        }

        public void setDownlinkLatency(double downlinkLatency) {
            this.downlinkLatency = downlinkLatency;
        }

        public long getUplinkBandwidth() {
            return uplinkBandwidth;
        }

        public void setUplinkBandwidth(long uplinkBandwidth) {
            this.uplinkBandwidth = uplinkBandwidth;
        }

        public long getDownlinkBandwidth() {
            return downlinkBandwidth;
        }

        public void setDownlinkBandwidth(long downlinkBandwidth) {
            this.downlinkBandwidth = downlinkBandwidth;
        }
    }

    /**
     * Inner class to represent child device information
     */
    public static class DeviceInfo {
        private int deviceId;
        private String deviceName;
        private double cpuUtilization;
        private double ramUtilization;
        private double bwUtilization;
        private int queueSize;
        private List<String> hostedModules = new ArrayList<>();

        public DeviceInfo() {
        }

        public DeviceInfo(int deviceId, String deviceName, double cpuUtilization,
                double ramUtilization, double bwUtilization, int queueSize) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.cpuUtilization = cpuUtilization;
            this.ramUtilization = ramUtilization;
            this.bwUtilization = bwUtilization;
            this.queueSize = queueSize;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("deviceId", deviceId);
            map.put("deviceName", deviceName);
            map.put("cpuUtilization", cpuUtilization);
            map.put("ramUtilization", ramUtilization);
            map.put("bwUtilization", bwUtilization);
            map.put("queueSize", queueSize);
            map.put("hostedModules", hostedModules);
            return map;
        }

        // Getters and setters

        public int getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(int deviceId) {
            this.deviceId = deviceId;
        }

        public String getDeviceName() {
            return deviceName;
        }

        public void setDeviceName(String deviceName) {
            this.deviceName = deviceName;
        }

        public double getCpuUtilization() {
            return cpuUtilization;
        }

        public void setCpuUtilization(double cpuUtilization) {
            this.cpuUtilization = cpuUtilization;
        }

        public double getRamUtilization() {
            return ramUtilization;
        }

        public void setRamUtilization(double ramUtilization) {
            this.ramUtilization = ramUtilization;
        }

        public double getBwUtilization() {
            return bwUtilization;
        }

        public void setBwUtilization(double bwUtilization) {
            this.bwUtilization = bwUtilization;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public void setQueueSize(int queueSize) {
            this.queueSize = queueSize;
        }

        public List<String> getHostedModules() {
            return hostedModules;
        }

        public void setHostedModules(List<String> hostedModules) {
            this.hostedModules = hostedModules;
        }

        public void addHostedModule(String module) {
            this.hostedModules.add(module);
        }
    }
}