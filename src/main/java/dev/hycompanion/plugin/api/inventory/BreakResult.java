package dev.hycompanion.plugin.api.inventory;

import java.util.List;
import java.util.Map;

/**
 * Result of a break block operation
 */
public record BreakResult(
    boolean success,
    boolean blockBroken,
    String blockId,
    int attemptsNeeded,
    List<Map<String, Object>> drops,
    Map<String, Object> dropsDetectedAt,
    Double toolDurabilityRemaining,
    String error
) {
    public static BreakResult success(String blockId, int attemptsNeeded, List<Map<String, Object>> drops, 
            Map<String, Object> dropsDetectedAt, Double toolDurabilityRemaining) {
        return new BreakResult(true, true, blockId, attemptsNeeded, drops, dropsDetectedAt, toolDurabilityRemaining, null);
    }

    public static BreakResult failed(String error) {
        return new BreakResult(false, false, null, 0, null, null, null, error);
    }

    public static BreakResult unbroken(String error) {
        return new BreakResult(true, false, null, 0, null, null, null, error);
    }
}
