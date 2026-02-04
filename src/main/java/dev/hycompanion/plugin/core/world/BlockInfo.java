package dev.hycompanion.plugin.core.world;

import java.util.List;
import java.util.Objects;

/**
 * Enriched information about a block type available on the server.
 * Sent to backend on startup to enable LLM block discovery.
 * 
 * @param blockId        The unique block ID (e.g., "Wood_Cedar_Trunk", "hytale:stone")
 * @param displayName    Human-readable name (e.g., "Cedar Log")
 * @param materialTypes  Classified material types (e.g., ["wood"], ["stone", "ore"])
 * @param keywords       Extracted search keywords from ID and name
 * @param categories     Block categories from asset data (if available)
 * 
 * @author Hycompanion Team
 */
public record BlockInfo(
        String blockId,
        String displayName,
        List<String> materialTypes,
        List<String> keywords,
        List<String> categories) {

    /**
     * Checks if this block matches a material type.
     * Case-insensitive comparison.
     */
    public boolean hasMaterialType(String type) {
        return materialTypes.stream()
                .anyMatch(mt -> mt.equalsIgnoreCase(type));
    }

    /**
     * Checks if this block matches any of the given keywords.
     * Case-insensitive partial match.
     */
    public boolean matchesKeyword(String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        return keywords.stream()
                .anyMatch(k -> k.toLowerCase().contains(lowerKeyword));
    }

    @Override
    public String toString() {
        return String.format("BlockInfo[%s (%s) - materials: %s]", 
                blockId, displayName, String.join(", ", materialTypes));
    }
}
