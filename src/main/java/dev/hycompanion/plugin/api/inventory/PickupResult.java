package dev.hycompanion.plugin.api.inventory;

import java.util.List;
import java.util.Map;

/**
 * Result of a pickup item operation
 */
public record PickupResult(
    boolean success,
    int itemsPickedUp,
    List<Map<String, Object>> itemsByType,
    int itemsRemaining,
    String error
) {
    public static PickupResult success(int itemsPickedUp, List<Map<String, Object>> itemsByType, int itemsRemaining) {
        return new PickupResult(true, itemsPickedUp, itemsByType, itemsRemaining, null);
    }

    public static PickupResult failed(String error) {
        return new PickupResult(false, 0, null, 0, error);
    }
}
