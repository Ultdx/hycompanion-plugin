package dev.hycompanion.plugin.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Plugin logger utility
 * 
 * Provides consistent logging format for the plugin.
 * Uses SLF4J internally but can also output to console for standalone testing.
 */
public class PluginLogger {

    private final String prefix;
    private boolean debugMode = false;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ANSI color codes for console output
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String GRAY = "\u001B[90m";

    public PluginLogger(String prefix) {
        this.prefix = "[" + prefix + "]";
    }

    /**
     * Set debug mode
     */
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    /**
     * Log info message
     */
    public void info(String message) {
        log(Level.INFO, message);
    }

    /**
     * Log warning message
     */
    public void warn(String message) {
        log(Level.WARN, message);
    }

    /**
     * Log error message
     */
    public void error(String message) {
        log(Level.ERROR, message);
    }

    /**
     * Log error with exception
     */
    public void error(String message, Throwable throwable) {
        log(Level.ERROR, message);
        if (throwable != null) {
            System.err.println(throwable.getMessage());
            if (debugMode) {
                throwable.printStackTrace();
            }
        }
    }

    /**
     * Log debug message (only if debug mode enabled)
     */
    public void debug(String message) {
        if (debugMode) {
            log(Level.DEBUG, message);
        }
    }

    /**
     * Internal log method
     */
    private void log(Level level, String message) {
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        String colorCode = getColorCode(level);
        String levelStr = level.name();

        // TODO: [HYTALE-API] Use Hytale's logger instead of System.out
        // Example: HytaleServer.getLogger().log(level, message);

        String formattedMessage = String.format(
                "%s%s %s%s %s%s%s",
                GRAY, timestamp,
                colorCode, levelStr,
                prefix,
                RESET, " " + message);

        if (level == Level.ERROR) {
            System.err.println(formattedMessage);
        } else {
            System.out.println(formattedMessage);
        }
    }

    /**
     * Get ANSI color code for log level
     */
    private String getColorCode(Level level) {
        return switch (level) {
            case DEBUG -> GRAY;
            case INFO -> GREEN;
            case WARN -> YELLOW;
            case ERROR -> RED;
        };
    }

    /**
     * Log levels
     */
    public enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}
