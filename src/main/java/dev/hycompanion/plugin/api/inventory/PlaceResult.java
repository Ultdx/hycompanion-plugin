package dev.hycompanion.plugin.api.inventory;

/**
 * Result of a place block operation
 */
public record PlaceResult(
    boolean success,
    String blockId,
    String itemId,
    int remainingQuantity,
    String error
) {
    public static PlaceResult success(String blockId, String itemId, int remainingQuantity) {
        return new PlaceResult(true, blockId, itemId, remainingQuantity, null);
    }

    public static PlaceResult failed(String error) {
        return new PlaceResult(false, null, null, 0, error);
    }
}
