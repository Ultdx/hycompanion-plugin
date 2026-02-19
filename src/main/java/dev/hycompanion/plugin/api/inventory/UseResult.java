package dev.hycompanion.plugin.api.inventory;

/**
 * Result of a use held item operation
 */
public record UseResult(
    boolean success,
    int usesPerformed,
    Boolean targetDestroyed,
    Double targetHealthRemaining,
    boolean toolBroke,
    String error
) {
    public static UseResult success(int usesPerformed, Boolean targetDestroyed, Double targetHealthRemaining, boolean toolBroke) {
        return new UseResult(true, usesPerformed, targetDestroyed, targetHealthRemaining, toolBroke, null);
    }

    public static UseResult failed(String error) {
        return new UseResult(false, 0, null, null, false, error);
    }
}
