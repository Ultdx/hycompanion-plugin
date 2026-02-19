package dev.hycompanion.plugin.api.inventory;

import java.util.Map;

/**
 * Result of an equip item operation
 */
public record EquipResult(
    boolean success,
    String itemId,
    String equippedSlot,
    Map<String, Object> previousItem,
    String error
) {
    public static EquipResult success(String itemId, String equippedSlot, Map<String, Object> previousItem) {
        return new EquipResult(true, itemId, equippedSlot, previousItem, null);
    }

    public static EquipResult failed(String error) {
        return new EquipResult(false, null, null, null, error);
    }
}
