package dev.hycompanion.plugin.api.inventory;

import java.util.Map;

/**
 * Result of an unequip item operation
 */
public record UnequipResult(
    boolean success,
    String slot,
    Map<String, Object> itemRemoved,
    boolean movedToStorage,
    boolean destroyed,
    String error
) {
    public static UnequipResult success(String slot, Map<String, Object> itemRemoved, boolean movedToStorage) {
        return new UnequipResult(true, slot, itemRemoved, movedToStorage, false, null);
    }

    public static UnequipResult destroyed(String slot, Map<String, Object> itemRemoved) {
        return new UnequipResult(true, slot, itemRemoved, false, true, null);
    }

    public static UnequipResult failed(String error) {
        return new UnequipResult(false, null, null, false, false, error);
    }
}
