package dev.hycompanion.plugin.core.context;

import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.List;

/**
 * Builds world context for NPC conversations
 * 
 * Gathers environmental data (time, weather, nearby players) to enrich
 * the context sent to the backend for more immersive NPC responses.
 */
public class ContextBuilder {

    private final HytaleAPI hytaleAPI;
    private final PluginLogger logger;

    // Default radius for nearby player detection
    private static final double NEARBY_PLAYER_RADIUS = 50.0;

    public ContextBuilder(HytaleAPI hytaleAPI, PluginLogger logger) {
        this.hytaleAPI = hytaleAPI;
        this.logger = logger;
    }

    /**
     * Build full world context from player location
     */
    public WorldContext buildContext(Location playerLocation) {
        if (playerLocation == null) {
            return WorldContext.defaultContext();
        }

        try {
            String locationStr = playerLocation.toCoordString();
            String timeOfDay = hytaleAPI.getTimeOfDay();
            String weather = hytaleAPI.getWeather();
            List<String> nearbyPlayers = hytaleAPI.getNearbyPlayerNames(
                    playerLocation,
                    NEARBY_PLAYER_RADIUS);

            return new WorldContext(locationStr, timeOfDay, weather, nearbyPlayers);

        } catch (Exception e) {
            logger.debug("Error building context, using defaults: " + e.getMessage());
            return WorldContext.minimal(playerLocation.toCoordString());
        }
    }

    /**
     * Build context from location string
     */
    public WorldContext buildContext(String locationStr) {
        try {
            Location location = Location.parse(locationStr);
            return buildContext(location);
        } catch (Exception e) {
            return WorldContext.defaultContext();
        }
    }

    /**
     * Build minimal context (just location)
     */
    public WorldContext buildMinimalContext(Location location) {
        String locationStr = location != null ? location.toCoordString() : "0,64,0";
        return WorldContext.minimal(locationStr);
    }
}
