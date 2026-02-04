package dev.hycompanion.plugin.api;

import java.util.UUID;

/**
 * Represents a player in the game world
 * 
 * @param id       Unique player identifier (UUID or platform ID)
 * @param name     Display name
 * @param uuid     Player's UUID
 * @param location Current location
 */
public record GamePlayer(
        String id,
        String name,
        UUID uuid,
        Location location) {
    /**
     * Create a GamePlayer with string ID
     */
    public static GamePlayer of(String id, String name, Location location) {
        return new GamePlayer(id, name, parseUUID(id), location);
    }

    /**
     * Create a GamePlayer with UUID
     */
    public static GamePlayer of(UUID uuid, String name, Location location) {
        return new GamePlayer(uuid.toString(), name, uuid, location);
    }

    /**
     * Parse UUID from string, or generate deterministic one from ID
     */
    private static UUID parseUUID(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            // Generate deterministic UUID from ID
            return UUID.nameUUIDFromBytes(id.getBytes());
        }
    }

    /**
     * Check if player is within range of a location
     */
    public boolean isWithinRange(Location other, double range) {
        return location != null && location.distanceTo(other) <= range;
    }
}
