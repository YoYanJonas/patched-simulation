package org.patch.utils;

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
 * Completely decoupled - accepts any data and persists it
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
     * Generate reports from provided data
     * 
     * @param cloudDeviceData Cloud device statistics as Map
     * @param fogDevicesData List of fog device statistics (List<Map>)
     * @param simulationTime Simulation duration in seconds
     */
    public void generateReports(Map<String, Object> cloudDeviceData, 
                                  List<Map<String, Object>> fogDevicesData,
                                  double simulationTime) {
        try {
            ensureReportsDirectory();
            
            // Generate human-readable report
            generateHumanReadableReport(cloudDeviceData, fogDevicesData, simulationTime);
            
            // Generate JSON report for Python visualization
            generateJsonReport(cloudDeviceData, fogDevicesData, simulationTime);
            
            logger.info("Reports generated successfully in directory: " + reportsDirPath);
            
        } catch (IOException e) {
            logger.severe("Error generating reports: " + e.getMessage());
        }
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
     * Generate human-readable text report
     */
    private void generateHumanReadableReport(Map<String, Object> cloudDeviceData, 
                                               List<Map<String, Object>> fogDevicesData,
                                               double simulationTime)
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
            writer.write(String.format("\nDevice: %s (ID: %s)", 
                    cloudDeviceData.get("device_name"), cloudDeviceData.get("device_id")));
            writer.write(String.format("\nTotal Energy Consumed: %.2f J", 
                    getDouble(cloudDeviceData.get("energy_consumption"))));
            writer.write(String.format("\nTotal Cost: $%.4f", 
                    getDouble(cloudDeviceData.get("cost"))));

            // RL allocation statistics
            Object allocationStats = cloudDeviceData.get("allocation_stats");
            if (allocationStats instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stats = (Map<String, Object>) allocationStats;
                writer.write("\n\n[RL ALLOCATION STATISTICS]");
                writer.write(String.format("\nTotal Allocation Decisions: %s", stats.get("total_decisions")));
                writer.write(String.format("\nSuccessful Allocations: %s", stats.get("successful_allocations")));
                writer.write(String.format("\nAllocation Success Rate: %.2f%%", 
                        getDouble(stats.get("success_rate")) * 100));
                writer.write(String.format("\nTotal Allocation Energy: %.2f J", 
                        getDouble(stats.get("total_energy"))));
                writer.write(String.format("\nTotal Allocation Cost: $%.4f", 
                        getDouble(stats.get("total_cost"))));
                writer.write(String.format("\nAverage Allocation Latency: %.2f ms", 
                        getDouble(stats.get("average_latency_ms"))));
                writer.write(String.format("\nAllocation Throughput: %.2f decisions/sec", 
                        getDouble(stats.get("throughput_per_sec"))));
            }

            // Write fog node statistics
            writer.write("\n\n[FOG NODE STATISTICS]");
            for (int i = 0; i < fogDevicesData.size(); i++) {
                Map<String, Object> fogData = fogDevicesData.get(i);
                writer.write(String.format("\n\nFog Node %s: %s (ID: %s)", 
                        fogData.get("node_id"), fogData.get("device_name"), fogData.get("device_id")));
                writer.write(String.format("\n  Total Energy Consumed: %.2f J", 
                        getDouble(fogData.get("energy_consumption"))));
                writer.write(String.format("\n  Total Cost: $%.4f", 
                        getDouble(fogData.get("cost"))));
                writer.write(String.format("\n  Unscheduled Queue Size: %s", fogData.get("unscheduled_queue_size")));
                writer.write(String.format("\n  Scheduled Queue Size: %s", fogData.get("scheduled_queue_size")));

                // RL scheduling statistics
                Object schedulingStats = fogData.get("scheduling_stats");
                if (schedulingStats instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stats = (Map<String, Object>) schedulingStats;
                    writer.write("\n  [RL SCHEDULING STATISTICS]");
                    writer.write(String.format("\n  Total Scheduling Decisions: %s", stats.get("total_decisions")));
                    writer.write(String.format("\n  Scheduling Success Rate: %.2f%%", 
                            getDouble(stats.get("success_rate")) * 100));
                    writer.write(String.format("\n  Total Scheduling Energy: %.2f J", 
                            getDouble(stats.get("total_energy"))));
                    writer.write(String.format("\n  Total Scheduling Cost: $%.4f", 
                            getDouble(stats.get("total_cost"))));
                    writer.write(String.format("\n  Average Scheduling Latency: %.2f ms", 
                            getDouble(stats.get("average_latency_ms"))));
                    writer.write(String.format("\n  Scheduling Throughput: %.2f decisions/sec", 
                            getDouble(stats.get("throughput_per_sec"))));
                }

                // Cache statistics
                Object cacheStats = fogData.get("cache_stats");
                if (cacheStats instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stats = (Map<String, Object>) cacheStats;
                    writer.write("\n  [CACHE STATISTICS]");
                    writer.write(String.format("\n  Cache Size: %s / %s", stats.get("cache_size"), stats.get("max_cache_size")));
                    writer.write(String.format("\n  Cache Hits: %s", stats.get("hits")));
                    writer.write(String.format("\n  Cache Misses: %s", stats.get("misses")));
                    writer.write(String.format("\n  Cache Hit Rate: %.2f%%", 
                            getDouble(stats.get("hit_rate")) * 100));
                }
            }

            // Write summary
            writer.write("\n\n[OVERALL SUMMARY]");
            double totalEnergy = getDouble(cloudDeviceData.get("energy_consumption"));
            double totalCost = getDouble(cloudDeviceData.get("cost"));
            for (Map<String, Object> fogData : fogDevicesData) {
                totalEnergy += getDouble(fogData.get("energy_consumption"));
                totalCost += getDouble(fogData.get("cost"));
            }
            writer.write(String.format("\nTotal Energy Consumption: %.2f J", totalEnergy));
            writer.write(String.format("\nTotal Cost: $%.4f", totalCost));
            writer.write(String.format("\nSimulation Time: %.2f", simulationTime));

            writer.write("\n\n================================================================================");
        }

        logger.info("Human-readable report written to: " + filename);
    }

    /**
     * Helper method to safely extract double value
     */
    private double getDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }
    
    /**
     * Generate JSON report for Python visualization
     */
    private void generateJsonReport(Map<String, Object> cloudDeviceData, 
                                     List<Map<String, Object>> fogDevicesData,
                                     double simulationTime) throws IOException {
        String filename = reportsDirPath + "/report-" + timestamp + JSON_REPORT_EXT;
        File file = new File(filename);

        // Build complete report from provided data
        Map<String, Object> report = new HashMap<>();
        
        // Metadata
        report.put("timestamp", timestamp);
        report.put("simulation_time", simulationTime);
        
        // Cloud device (use provided data as-is)
        report.put("cloud_device", cloudDeviceData);
        
        // Fog devices (use provided data as-is)
        report.put("fog_devices", fogDevicesData);
        
        // Calculate summary from provided data
        Map<String, Object> summary = new HashMap<>();
        double totalEnergy = getDouble(cloudDeviceData.get("energy_consumption"));
        double totalCost = getDouble(cloudDeviceData.get("cost"));
        for (Map<String, Object> fogData : fogDevicesData) {
            totalEnergy += getDouble(fogData.get("energy_consumption"));
            totalCost += getDouble(fogData.get("cost"));
        }
        summary.put("total_energy", totalEnergy);
        summary.put("total_cost", totalCost);
        summary.put("average_energy_per_device", totalEnergy / (fogDevicesData.size() + 1));
        summary.put("average_cost_per_device", totalCost / (fogDevicesData.size() + 1));
        
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
                if (!first)
                    json.append(",\n");
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
                if (!first)
                    json.append(",\n");
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
