package dev.hycompanion.plugin.api.inventory;

import java.util.List;
import java.util.Map;

/**
 * Snapshot of NPC inventory contents
 */
public record InventorySnapshot(
    Map<String, Object> armor,
    List<Map<String, Object>> hotbar,
    List<Map<String, Object>> storage,
    Map<String, Object> heldItem,
    int totalItems,
    boolean success,
    String error
) {
    public static InventorySnapshot create(
            Map<String, Object> armor,
            List<Map<String, Object>> hotbar,
            List<Map<String, Object>> storage,
            Map<String, Object> heldItem,
            int totalItems) {
        return new InventorySnapshot(armor, hotbar, storage, heldItem, totalItems, true, null);
    }

    public static InventorySnapshot failed(String error) {
        return new InventorySnapshot(null, null, null, null, 0, false, error);
    }
}
