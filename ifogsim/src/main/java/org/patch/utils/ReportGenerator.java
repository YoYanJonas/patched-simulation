package org.patch.utils;

import org.cloudbus.cloudsim.core.CloudSim;
import org.patch.devices.RLCloudDevice;
import org.patch.devices.RLFogDevice;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Report generator for simulation results
 * Creates both human-readable and machine-readable reports
 */
public class ReportGenerator {
    private static final Logger logger = Logger.getLogger(ReportGenerator.class.getName());
    
    private static final String REPORTS_DIR = "reports";
    private static final String HUMAN_REPORT_EXT = ".txt";
    private static final String JSON_REPORT_EXT = ".json";
    
    private final String timestamp;
    private final String reportsDirPath;
    
    public ReportGenerator() {
        // Create timestamp for file naming
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
        this.timestamp = now.format(formatter);
        
        // Determine reports directory path (in project root/out directory)
        this.reportsDirPath = "reports";
    }
    
    /**
     * Create reports directory if it doesn't exist
     */
    private void ensureReportsDirectory() {
        File reportsDir = new File(reportsDirPath);
        if (!reportsDir.exists()) {
            reportsDir.mkdirs();
            logger.info("Created reports directory: " + reportsDirPath);
        }
    }
    
    /**
     * Generate and write all reports
     */
    public void generateReports(RLCloudDevice cloud, List<RLFogDevice> fogDevices) {
        try {
            ensureReportsDirectory();
            
            // Generate human-readable report
            generateHumanReadableReport(cloud, fogDevices);
            
            // Generate JSON report for Python visualization
            generateJsonReport(cloud, fogDevices);
            
            logger.info("Reports generated successfully in directory: " + reportsDirPath);
            
        } catch (IOException e) {
            logger.severe("Error generating reports: " + e.getMessage());
        }
    }
    
    /**
     * Generate human-readable text report
     */
    private void generateHumanReadableReport(RLCloudDevice cloud, List<RLFogDevice> fogDevices) 
            throws IOException {
        String filename = reportsDirPath + "/report-" + timestamp + HUMAN_REPORT_EXT;
        File file = new File(filename);
        
        try (FileWriter writer = new FileWriter(file)) {
            // Write header
            writer.write("================================================================================");
            writer.write("\nSIMULATION RESULTS");
            writer.write("\nGenerated: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            writer.write("\n================================================================================");
            
            // Write cloud statistics
            writer.write("\n\n[CLOUD DEVICE STATISTICS]");
            writer.write(String.format("\nDevice: %s (ID: %d)", cloud.getName(), cloud.getId()));
            writer.write(String.format("\nTotal Energy Consumed: %.2f J", cloud.getEnergyConsumption()));
            writer.write(String.format("\nTotal Cost: $%.4f", cloud.getTotalCost()));
            
            if (cloud.getAllocationClient() != null && cloud.isRlEnabled()) {
                writer.write("\n\n[RL ALLOCATION STATISTICS]");
                writer.write(String.format("\nTotal Allocation Decisions: %d", cloud.getTotalAllocationDecisions()));
                writer.write(String.format("\nSuccessful Allocations: %d", cloud.getSuccessfulAllocations()));
                writer.write(String.format("\nAllocation Success Rate: %.2f%%", cloud.getAllocationSuccessRate() * 100));
                writer.write(String.format("\nTotal Allocation Energy: %.2f J", cloud.getTotalAllocationEnergy()));
                writer.write(String.format("\nTotal Allocation Cost: $%.4f", cloud.getTotalAllocationCost()));
                writer.write(String.format("\nAverage Allocation Latency: %.2f ms", cloud.getAverageAllocationLatency()));
                writer.write(String.format("\nAllocation Throughput: %.2f decisions/sec", cloud.getAllocationThroughput()));
            }
            
            // Write fog node statistics
            writer.write("\n\n[FOG NODE STATISTICS]");
            for (int i = 0; i < fogDevices.size(); i++) {
                RLFogDevice fogDevice = fogDevices.get(i);
                writer.write(String.format("\n\nFog Node %d: %s (ID: %d)", i, fogDevice.getName(), fogDevice.getId()));
                writer.write(String.format("\n  Total Energy Consumed: %.2f J", fogDevice.getEnergyConsumption()));
                writer.write(String.format("\n  Total Cost: $%.4f", fogDevice.getTotalCost()));
                writer.write(String.format("\n  Unscheduled Queue Size: %d", fogDevice.getUnscheduledQueue().size()));
                writer.write(String.format("\n  Scheduled Queue Size: %d", fogDevice.getScheduledQueue().size()));
                
                if (fogDevice.getSchedulerClient() != null && fogDevice.isRlEnabled()) {
                    writer.write("\n  [RL SCHEDULING STATISTICS]");
                    writer.write(String.format("\n  Total Scheduling Decisions: %d", fogDevice.getTotalSchedulingDecisions()));
                    writer.write(String.format("\n  Scheduling Success Rate: %.2f%%", fogDevice.getSchedulingSuccessRate() * 100));
                    writer.write(String.format("\n  Total Scheduling Energy: %.2f J", fogDevice.getTotalSchedulingEnergy()));
                    writer.write(String.format("\n  Total Scheduling Cost: $%.4f", fogDevice.getTotalSchedulingCost()));
                    writer.write(String.format("\n  Average Scheduling Latency: %.2f ms", fogDevice.getAverageSchedulingLatency()));
                    writer.write(String.format("\n  Scheduling Throughput: %.2f decisions/sec", fogDevice.getSchedulingThroughput()));
                    writer.write(String.format("\n  Streaming Observer Status: %s", 
                            fogDevice.getStreamingObserver() != null ? "ACTIVE" : "INACTIVE"));
                }
                
                // Cache statistics
                Map<String, Object> cacheStats = fogDevice.getCacheStatistics();
                writer.write("\n  [CACHE STATISTICS]");
                writer.write(String.format("\n  Cache Size: %s / %s", cacheStats.get("cacheSize"), cacheStats.get("maxCacheSize")));
                writer.write(String.format("\n  Cache Hits: %s", cacheStats.get("cacheHitCount")));
                writer.write(String.format("\n  Cache Misses: %s", cacheStats.get("cacheMissCount")));
                writer.write(String.format("\n  Cache Hit Rate: %.2f%%", 
                        ((Double) cacheStats.get("cacheHitRate")) * 100));
            }
            
            // Write summary
            writer.write("\n\n[OVERALL SUMMARY]");
            double totalEnergy = cloud.getEnergyConsumption();
            double totalCost = cloud.getTotalCost();
            for (RLFogDevice fogDevice : fogDevices) {
                totalEnergy += fogDevice.getEnergyConsumption();
                totalCost += fogDevice.getTotalCost();
            }
            writer.write(String.format("\nTotal Energy Consumption: %.2f J", totalEnergy));
            writer.write(String.format("\nTotal Cost: $%.4f", totalCost));
            writer.write(String.format("\nSimulation Time: %.2f", CloudSim.clock()));
            
            writer.write("\n\n================================================================================");
        }
        
        logger.info("Human-readable report written to: " + filename);
    }
    
    /**
     * Generate JSON report for Python visualization
     */
    private void generateJsonReport(RLCloudDevice cloud, List<RLFogDevice> fogDevices) throws IOException {
        String filename = reportsDirPath + "/report-" + timestamp + JSON_REPORT_EXT;
        File file = new File(filename);
        
        Map<String, Object> report = new HashMap<>();
        
        // Metadata
        report.put("timestamp", timestamp);
        report.put("simulation_time", CloudSim.clock());
        
        // Cloud device data
        Map<String, Object> cloudData = new HashMap<>();
        cloudData.put("device_id", cloud.getId());
        cloudData.put("device_name", cloud.getName());
        cloudData.put("energy_consumption", cloud.getEnergyConsumption());
        cloudData.put("cost", cloud.getTotalCost());
        
        if (cloud.getAllocationClient() != null && cloud.isRlEnabled()) {
            Map<String, Object> allocationStats = new HashMap<>();
            allocationStats.put("total_decisions", cloud.getTotalAllocationDecisions());
            allocationStats.put("successful_allocations", cloud.getSuccessfulAllocations());
            allocationStats.put("success_rate", cloud.getAllocationSuccessRate());
            allocationStats.put("total_energy", cloud.getTotalAllocationEnergy());
            allocationStats.put("total_cost", cloud.getTotalAllocationCost());
            allocationStats.put("average_latency_ms", cloud.getAverageAllocationLatency());
            allocationStats.put("throughput_per_sec", cloud.getAllocationThroughput());
            cloudData.put("allocation_stats", allocationStats);
        }
        
        report.put("cloud_device", cloudData);
        
        // Fog devices data
        List<Map<String, Object>> fogDevicesData = new java.util.ArrayList<>();
        for (int i = 0; i < fogDevices.size(); i++) {
            RLFogDevice fogDevice = fogDevices.get(i);
            Map<String, Object> fogData = new HashMap<>();
            fogData.put("node_id", i);
            fogData.put("device_id", fogDevice.getId());
            fogData.put("device_name", fogDevice.getName());
            fogData.put("energy_consumption", fogDevice.getEnergyConsumption());
            fogData.put("cost", fogDevice.getTotalCost());
            fogData.put("unscheduled_queue_size", fogDevice.getUnscheduledQueue().size());
            fogData.put("scheduled_queue_size", fogDevice.getScheduledQueue().size());
            
            // Scheduling stats
            if (fogDevice.getSchedulerClient() != null && fogDevice.isRlEnabled()) {
                Map<String, Object> schedulingStats = new HashMap<>();
                schedulingStats.put("total_decisions", fogDevice.getTotalSchedulingDecisions());
                schedulingStats.put("success_rate", fogDevice.getSchedulingSuccessRate());
                schedulingStats.put("total_energy", fogDevice.getTotalSchedulingEnergy());
                schedulingStats.put("total_cost", fogDevice.getTotalSchedulingCost());
                schedulingStats.put("average_latency_ms", fogDevice.getAverageSchedulingLatency());
                schedulingStats.put("throughput_per_sec", fogDevice.getSchedulingThroughput());
                fogData.put("scheduling_stats", schedulingStats);
            }
            
            // Cache stats
            Map<String, Object> cacheStats = new HashMap<>();
            Map<String, Object> deviceCacheStats = fogDevice.getCacheStatistics();
            cacheStats.put("cache_size", deviceCacheStats.get("cacheSize"));
            cacheStats.put("max_cache_size", deviceCacheStats.get("maxCacheSize"));
            cacheStats.put("hits", deviceCacheStats.get("cacheHitCount"));
            cacheStats.put("misses", deviceCacheStats.get("cacheMissCount"));
            cacheStats.put("hit_rate", deviceCacheStats.get("cacheHitRate"));
            fogData.put("cache_stats", cacheStats);
            
            fogDevicesData.add(fogData);
        }
        
        report.put("fog_devices", fogDevicesData);
        
        // Overall summary
        Map<String, Object> summary = new HashMap<>();
        double totalEnergy = cloud.getEnergyConsumption();
        double totalCost = cloud.getTotalCost();
        for (RLFogDevice fogDevice : fogDevices) {
            totalEnergy += fogDevice.getEnergyConsumption();
            totalCost += fogDevice.getTotalCost();
        }
        summary.put("total_energy", totalEnergy);
        summary.put("total_cost", totalCost);
        summary.put("average_energy_per_device", totalEnergy / (fogDevices.size() + 1));
        summary.put("average_cost_per_device", totalCost / (fogDevices.size() + 1));
        
        report.put("summary", summary);
        
        // Write JSON file (pretty-printed for readability)
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(formatJson(report));
        }
        
        logger.info("JSON report written to: " + filename);
    }
    
    /**
     * Format map to pretty-printed JSON string
     */
    private String formatJson(Map<String, Object> data) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        formatJsonValue(json, data, 1);
        json.append("}\n");
        return json.toString();
    }
    
    @SuppressWarnings("unchecked")
    private void formatJsonValue(StringBuilder json, Object value, int indent) {
        String indentStr = repeatString("  ", indent);
        
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            json.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) json.append(",\n");
                first = false;
                json.append(indentStr).append("\"").append(entry.getKey()).append("\": ");
                formatJsonValue(json, entry.getValue(), indent + 1);
            }
            json.append("\n").append(repeatString("  ", indent - 1)).append("}");
        } else if (value instanceof List) {
            json.append("[\n");
            List<?> list = (List<?>) value;
            boolean first = true;
            for (Object item : list) {
                if (!first) json.append(",\n");
                first = false;
                json.append(indentStr);
                formatJsonValue(json, item, indent + 1);
            }
            json.append("\n").append(repeatString("  ", indent - 1)).append("]");
        } else if (value instanceof String) {
            json.append("\"").append(value).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else {
            json.append("\"").append(String.valueOf(value)).append("\"");
        }
    }
    
    /**
     * Repeat a string n times (Java 8 compatible)
     */
    private String repeatString(String str, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            result.append(str);
        }
        return result.toString();
    }
    
    /**
     * Get the timestamp used for file naming
     */
    public String getTimestamp() {
        return timestamp;
    }
    
    /**
     * Get the reports directory path
     */
    public String getReportsDirPath() {
        return reportsDirPath;
    }
}

