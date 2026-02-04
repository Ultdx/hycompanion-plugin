package dev.hycompanion.plugin.network.payload;

import java.util.Map;

/**
 * Action payload received from backend (MCP tool execution result)
 * 
 * @param npcId    NPC's external ID
 * @param playerId Target player's ID
 * @param action   Action type (say, emote, open_trade, give_quest, move_to)
 * @param params   Action parameters
 */
public record ActionPayload(
        String npcId,
        String playerId,
        String action,
        Map<String, Object> params) {
    /**
     * Get string parameter
     */
    public String getString(String key) {
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Get string parameter with default
     */
    public String getString(String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Get number parameter
     */
    public Double getNumber(String key) {
        Object value = params.get(key);
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        return null;
    }

    /**
     * Get number parameter with default
     */
    public double getNumber(String key, double defaultValue) {
        Double value = getNumber(key);
        return value != null ? value : defaultValue;
    }
}
