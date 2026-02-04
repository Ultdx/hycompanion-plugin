package dev.hycompanion.plugin.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main plugin configuration
 * Loaded from config.yml in the plugin data folder
 * 
 * @param connection Connection settings
 * @param gameplay   Gameplay settings
 * @param npc        NPC settings
 * @param logging    Logging settings
 */
public record PluginConfig(
        ConnectionConfig connection,
        GameplayConfig gameplay,
        NpcConfig npc,
        LoggingConfig logging) {

    /**
     * Load configuration from YAML file
     * Uses simple line-based parsing (no external YAML library needed)
     */
    public static PluginConfig load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);

        // Parse connection section
        String url = getValue(lines, "url", "https://api.hycompanion.dev");
        String apiKey = getValue(lines, "api_key", "YOUR_SERVER_API_KEY");
        boolean reconnectEnabled = getBooleanValue(lines, "reconnect_enabled", true);
        int reconnectDelay = getIntValue(lines, "reconnect_delay_ms", 5000);

        // Parse gameplay section
        boolean debugMode = getBooleanValue(lines, "debug_mode", false);
        boolean emotesEnabled = getBooleanValue(lines, "emotes_enabled", true);
        String messagePrefix = getValue(lines, "message_prefix", "[NPC] ");
        int greetingRange = getIntValue(lines, "greeting_range", 10);

        // Parse npc section
        String cacheDirectory = getValue(lines, "cache_directory", "data/npcs");
        boolean syncOnStartup = getBooleanValue(lines, "sync_on_startup", true);

        // Parse logging section
        String logLevel = getValue(lines, "level", "INFO");
        boolean logChat = getBooleanValue(lines, "log_chat", false);
        boolean logActions = getBooleanValue(lines, "log_actions", true);

        return new PluginConfig(
                new ConnectionConfig(url, apiKey, reconnectEnabled, reconnectDelay),
                new GameplayConfig(debugMode, emotesEnabled, messagePrefix, greetingRange),
                new NpcConfig(cacheDirectory, syncOnStartup),
                new LoggingConfig(logLevel, logChat, logActions));
    }

    /**
     * Create default configuration
     */
    public static PluginConfig defaults() {
        return new PluginConfig(
                new ConnectionConfig(
                        "https://api.hycompanion.dev",
                        "YOUR_SERVER_API_KEY",
                        true,
                        5000),
                new GameplayConfig(false, true, "[NPC] ", 10),
                new NpcConfig("data/npcs", true),
                new LoggingConfig("INFO", false, true));
    }

    // ========== YAML Parsing Helpers ==========

    private static String getValue(List<String> lines, String key, String defaultValue) {
        Pattern pattern = Pattern.compile("^\\s*" + key + ":\\s*[\"']?([^\"'#]+)[\"']?", Pattern.CASE_INSENSITIVE);

        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return defaultValue;
    }

    private static boolean getBooleanValue(List<String> lines, String key, boolean defaultValue) {
        String value = getValue(lines, key, String.valueOf(defaultValue));
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private static int getIntValue(List<String> lines, String key, int defaultValue) {
        String value = getValue(lines, key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Update API key in config file without losing comments/formatting
     */
    public static void updateApiKeyInFile(Path configPath, String newApiKey) throws IOException {
        List<String> lines = Files.readAllLines(configPath);
        List<String> newLines = new java.util.ArrayList<>();

        boolean updated = false;
        for (String line : lines) {
            if (line.trim().startsWith("api_key:")) {
                // Start of line, preservation of indentation
                int indentIndex = line.indexOf("api_key:");
                String indent = line.substring(0, indentIndex);
                newLines.add(indent + "api_key: \"" + newApiKey + "\"");
                updated = true;
            } else {
                newLines.add(line);
            }
        }

        if (updated) {
            Files.write(configPath, newLines);
        } else {
            throw new IOException("Could not find api_key field in config.yml");
        }
    }

    // ========== Nested Config Records ==========

    /**
     * Connection configuration
     */
    public record ConnectionConfig(
            String url,
            String apiKey,
            boolean reconnectEnabled,
            int reconnectDelayMs) {
    }

    /**
     * Gameplay configuration
     */
    public record GameplayConfig(
            boolean debugMode,
            boolean emotesEnabled,
            String messagePrefix,
            int greetingRange) {
    }

    /**
     * NPC configuration
     */
    public record NpcConfig(
            String cacheDirectory,
            boolean syncOnStartup) {
    }

    /**
     * Logging configuration
     */
    public record LoggingConfig(
            String level,
            boolean logChat,
            boolean logActions) {
    }
}
