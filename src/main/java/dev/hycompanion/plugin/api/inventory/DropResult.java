package dev.hycompanion.plugin.api.inventory;

/**
 * Result of a drop item operation
 */
public record DropResult(
    boolean success,
    String itemId,
    int quantityDropped,
    int remainingQuantity,
    String error
) {
    public static DropResult success(String itemId, int quantityDropped, int remainingQuantity) {
        return new DropResult(true, itemId, quantityDropped, remainingQuantity, null);
    }

    public static DropResult failed(String error) {
        return new DropResult(false, null, 0, 0, error);
    }
}
