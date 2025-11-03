package org.patch.config;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * YAML configuration loader
 * Loads configuration from application.yml files
 */
public class YamlConfigLoader {
    private static final Logger logger = Logger.getLogger(YamlConfigLoader.class.getName());

    private static Map<String, Object> config = null;
    private static String configFilePath = null;

    /**
     * Load YAML configuration from file
     * 
     * @param configPath Path to application.yml file
     * @return true if loaded successfully, false otherwise
     */
    public static boolean loadConfig(String configPath) {
        if (config != null && configFilePath != null && configFilePath.equals(configPath)) {
            return true; // Already loaded
        }

        File configFile = new File(configPath);
        if (!configFile.exists() || !configFile.isFile()) {
            logger.warning("Config file not found: " + configPath);
            return false;
        }

        try {
            Yaml yaml = new Yaml();
            InputStream inputStream = new FileInputStream(configFile);
            Object loaded = yaml.load(inputStream);
            if (loaded instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> loadedMap = (Map<String, Object>) loaded;
                config = loadedMap;
            } else {
                logger.warning("YAML file does not contain a map at root level: " + configPath);
                return false;
            }
            configFilePath = configPath;
            logger.info("Loaded YAML configuration from: " + configPath);
            return true;
        } catch (FileNotFoundException e) {
            logger.warning("Config file not found: " + configPath);
            return false;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load YAML configuration from: " + configPath, e);
            return false;
        }
    }

    /**
     * Get value from YAML config using dot-notation path (e.g.,
     * "simulation.external-tasks.enabled")
     * Supports both dot notation and hyphen notation in YAML keys
     * 
     * @param path         Dot-notation path to the value (e.g.,
     *                     "simulation.external-tasks.enabled")
     * @param defaultValue Default value if not found
     * @return The value as String, or defaultValue if not found
     */
    @SuppressWarnings("unchecked")
    public static String getValue(String path, String defaultValue) {
        if (config == null) {
            return defaultValue;
        }

        String[] keys = path.split("\\.");
        Object current = config;

        for (String key : keys) {
            if (current instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) current;
                // Try exact key first
                current = map.get(key);

                // If not found, try variations (YAML keys use hyphens, we use dots in paths)
                if (current == null) {
                    // Try with hyphens (common YAML format: "external-tasks" vs "external.tasks")
                    String hyphenKey = key.replace("_", "-");
                    if (!hyphenKey.equals(key)) {
                        current = map.get(hyphenKey);
                    }
                }
                if (current == null) {
                    // Try with underscores
                    String underscoreKey = key.replace("-", "_");
                    if (!underscoreKey.equals(key)) {
                        current = map.get(underscoreKey);
                    }
                }
                // Last resort: try case-insensitive match
                if (current == null) {
                    for (Map.Entry<String, Object> entry : map.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(key)) {
                            current = entry.getValue();
                            break;
                        }
                    }
                }

                if (current == null) {
                    return defaultValue;
                }
            } else {
                return defaultValue;
            }
        }

        if (current != null) {
            String value = current.toString();
            // Parse environment variable syntax: ${VAR:default} or ${VAR}
            value = parseEnvironmentVariables(value);
            return value;
        }

        return defaultValue;
    }

    /**
     * Parse environment variable syntax from YAML values
     * Supports: ${VAR:default} and ${VAR}
     * If VAR is set, use it; otherwise use default (or empty string if no default)
     */
    private static String parseEnvironmentVariables(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        // Pattern: ${VAR:default} or ${VAR}
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?\\}");
        java.util.regex.Matcher matcher = pattern.matcher(value);
        
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultValue = matcher.group(2); // May be null
            
            String envValue = System.getenv(varName);
            String replacement;
            if (envValue != null && !envValue.isEmpty()) {
                replacement = envValue;
            } else if (defaultValue != null) {
                replacement = defaultValue;
            } else {
                replacement = ""; // No env var and no default
            }
            
            // Escape special characters in replacement string for appendReplacement
            // $ and \ need to be escaped in replacement strings
            replacement = java.util.regex.Matcher.quoteReplacement(replacement);
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        
        return result.toString();
    }

    /**
     * Get boolean value from YAML config
     */
    public static boolean getBoolean(String path, boolean defaultValue) {
        String value = getValue(path, null);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Get integer value from YAML config
     */
    public static int getInt(String path, int defaultValue) {
        String value = getValue(path, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + path + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Get long value from YAML config
     */
    public static long getLong(String path, long defaultValue) {
        String value = getValue(path, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid long value for " + path + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Get double value from YAML config
     */
    public static double getDouble(String path, double defaultValue) {
        String value = getValue(path, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid double value for " + path + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Get nested map from YAML config
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(String path) {
        if (config == null) {
            return new HashMap<>();
        }

        String[] keys = path.split("\\.");
        Object current = config;

        for (String key : keys) {
            if (current instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) current;
                // Try exact key first
                current = map.get(key);

                // If not found, try variations
                if (current == null) {
                    String hyphenKey = key.replace("_", "-");
                    if (!hyphenKey.equals(key)) {
                        current = map.get(hyphenKey);
                    }
                }
                if (current == null) {
                    String underscoreKey = key.replace("-", "_");
                    if (!underscoreKey.equals(key)) {
                        current = map.get(underscoreKey);
                    }
                }
                // Last resort: case-insensitive match
                if (current == null) {
                    for (Map.Entry<String, Object> entry : map.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(key)) {
                            current = entry.getValue();
                            break;
                        }
                    }
                }

                if (current == null) {
                    return new HashMap<>();
                }
            } else {
                return new HashMap<>();
            }
        }

        if (current instanceof Map) {
            return (Map<String, Object>) current;
        }

        return new HashMap<>();
    }

    /**
     * Check if config is loaded
     */
    public static boolean isLoaded() {
        return config != null;
    }

    /**
     * Clear loaded config (for testing)
     */
    public static void clear() {
        config = null;
        configFilePath = null;
    }

    /**
     * Get the config file path that was loaded
     */
    public static String getConfigFilePath() {
        return configFilePath;
    }
}
