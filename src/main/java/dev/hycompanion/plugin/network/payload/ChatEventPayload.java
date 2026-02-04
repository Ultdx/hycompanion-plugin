package dev.hycompanion.plugin.network.payload;

import dev.hycompanion.plugin.core.context.WorldContext;

/**
 * Chat event payload sent to backend
 * 
 * @param npcId      NPC's external ID
 * @param playerId   Player's unique ID
 * @param playerName Player's display name
 * @param message    Chat message content
 * @param context    World context (location, time, weather, nearby players)
 */
public record ChatEventPayload(
        String npcId,
        String playerId,
        String playerName,
        String message,
        WorldContext context) {
    /**
     * Create chat event payload
     */
    public static ChatEventPayload of(
            String npcId,
            String playerId,
            String playerName,
            String message,
            WorldContext context) {
        return new ChatEventPayload(npcId, playerId, playerName, message, context);
    }
}
