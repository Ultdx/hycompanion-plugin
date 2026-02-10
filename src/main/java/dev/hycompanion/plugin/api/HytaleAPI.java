package dev.hycompanion.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.core.npc.NpcMoveResult;

/**
 * Abstraction layer for Hytale Server API
 * 
 * This interface defines all interactions with the Hytale game server.
 * Implementations should be created once the actual Hytale Server API is
 * available.
 * 
 * Current implementation:
 * {@link dev.hycompanion.plugin.adapter.MockHytaleAdapter}
 * 
 * @author Hycompanion Team
 */
public interface HytaleAPI {

    // ========== Player Operations ==========

    /**
     * Get a player by their unique ID
     * 
     * @param playerId Player's unique identifier
     * @return Player if online and found
     */
    Optional<GamePlayer> getPlayer(String playerId);

    /**
     * Get a player by their name
     * 
     * @param playerName Player's display name
     * @return Player if online and found
     */
    Optional<GamePlayer> getPlayerByName(String playerName);

    /**
     * Find nearest block by tag
     */
    CompletableFuture<Optional<Map<String, Object>>> findBlock(
            UUID npcId, String tag, int radius);

    /**
     * Find nearest entity by type
     */
    CompletableFuture<Optional<Map<String, Object>>> findEntity(
            UUID npcId,  String name, int radius);

    /**
     * Scan surroundings and return all unique block types with nearest coordinates.
     * Groups results by category, with each blockId having its nearest position.
     * 
     * @param npcId  NPC's instance ID (scan center)
     * @param radius Scan radius in blocks
     * @return Map containing current_position, radius, categories, blocks, totalUniqueBlocks
     */
    CompletableFuture<Optional<Map<String, Object>>> scanBlocks(
            UUID npcId, int radius);

    /**
     * Get list of online players
     */
    List<GamePlayer> getOnlinePlayers();

    /**
     * Get all NPC instances
     * 
     * @return List of NPC instances
     */
    Set<NpcInstanceData> getNpcInstances();

    /**
     * Get a specific NPC instance by its UUID
     * 
     * @param npcInstanceUuid NPC instance UUID
     * @return NPC instance if found
     */
    NpcInstanceData getNpcInstance(UUID npcInstanceUuid);

    /**
     * Send a message to a specific player
     * 
     * @param playerId Target player's ID
     * @param message  Message content
     */
    void sendMessage(String playerId, String message);

    /**
     * Send a formatted NPC message to a player
     * 
     * @param npcInstanceId NPC's instance ID
     * @param playerId      Target player's ID
     * @param message       Message content
     */
    void sendNpcMessage(UUID npcInstanceId, String playerId, String message);

    /**
     * Broadcast a formatted NPC message to all specified players
     * 
     * @param npcInstanceId NPC's instance ID
     * @param playerIds     List of target player IDs
     * @param message       Message content
     */
    void broadcastNpcMessage(UUID npcInstanceId, List<String> playerIds, String message);

    /**
     * Send an error message to a player (displayed in orange)
     * 
     * @param playerId Target player's ID
     * @param message  Error message content
     */
    void sendErrorMessage(String playerId, String message);

    // ========== NPC Operations ==========

    /**
     * Spawn an NPC entity in the world
     * 
     * @param npcId    NPC's external ID
     * @param name     Display name
     * @param location Spawn location
     * @return Created entity UUID
     */
    Optional<UUID> spawnNpc(String npcId, String name, Location location);

    /**
     * Remove an NPC entity from the world
     * 
     * @param npcInstanceId NPC's instance ID
     * @return true if removed
     */
    boolean removeNpc(UUID npcInstanceId);

    /**
     * Update NPC capabilities (invincibility, knockback, etc) for existing
     * instances
     * 
     * @param externalId NPC's external ID (Role ID)
     * @param npcData    Updated NPC data containing capability flags
     */
    void updateNpcCapabilities(String externalId, dev.hycompanion.plugin.core.npc.NpcData npcData);

    /**
     * Trigger an animation on an NPC
     * 
     * The animation name should match a key from the model's AnimationSets
     * (e.g., "Sit", "Sleep", "Howl", "Greet", "Wave", "Idle", etc.)
     * Available animations vary per model and can be discovered via
     * getAvailableAnimations().
     * 
     * @param npcInstanceId NPC's instance ID
     * @param animationName Animation set name from the model definition
     */
    void triggerNpcEmote(UUID npcInstanceId, String animationName);

    /**
     * Move an NPC to a location
     * 
     * @param npcInstanceId NPC's instance ID
     * @param location      Target location
     * @return CompletableFuture with result containing success status and final
     *         location if applicable
     */
    CompletableFuture<NpcMoveResult> moveNpcTo(UUID npcInstanceId, Location location);

    /**
     * Get an NPC's current location
     * 
     * @param npcInstanceId NPC's instance ID
     * @return Current location or empty if not found
     */
    Optional<Location> getNpcInstanceLocation(UUID npcInstanceId);

    /**
     * Rotate an NPC to face a target location, if the NPC is idle/static.
     * Implementations should avoid rotating NPCs that are actively following.
     *
     * @param npcInstanceId  NPC's instance ID
     * @param targetLocation Location to face
     */
    void rotateNpcInstanceToward(UUID npcInstanceId, Location targetLocation);

    /**
     * Check if an NPC entity is still valid in the game world.
     * This returns false if the NPC was removed by Hytale commands (e.g., /npc
     * clean)
     * or if the entity reference is no longer valid.
     * 
     * @param npcInstanceId NPC's instance ID
     * @return true if the entity exists and is valid
     */
    boolean isNpcInstanceEntityValid(UUID npcInstanceId);

    /**
     * Discover and bind existing NPC entities in the world for a specific role.
     * This is used after sync to find NPCs that were persisted by Hytale
     * and bind them to the plugin's tracking system.
     * 
     * @param externalId NPC's external ID (Role ID)
     * @return List of discovered Entity UUIDs
     */
    List<UUID> discoverExistingNpcInstances(String externalId);

    /**
     * Register a listener to be notified when an NPC instance is removed or
     * invalidated.
     * This ensures the NpcManager can clean up its tracking maps.
     * 
     * @param listener Callback receiving the UUID of the removed entity
     */
    void registerNpcRemovalListener(java.util.function.Consumer<UUID> listener);

    // ========== Trade Operations ==========

    /**
     * Open trade interface between NPC and player
     * 
     * @param npcInstanceId NPC's instance ID
     * @param playerId      Target player's ID
     */
    void openTradeInterface(UUID npcInstanceId, String playerId);

    // ========== Quest Operations ==========

    /**
     * Offer a quest to a player from an NPC
     * 
     * @param npcInstanceId Source NPC's instance ID
     * @param playerId      Target player's ID
     * @param questId       Quest identifier
     * @param questName     Human-readable quest name (optional)
     */
    void offerQuest(UUID npcInstanceId, String playerId, String questId, String questName);

    // ========== World Context ==========

    /**
     * Get the current time of day
     * 
     * @return Time as string (dawn, morning, noon, afternoon, dusk, night)
     */
    String getTimeOfDay();

    /**
     * Get the current weather
     * 
     * @return Weather as string (clear, rain, storm, snow)
     */
    String getWeather();

    /**
     * Get players near a location
     * 
     * @param location Center location
     * @param radius   Search radius in blocks
     * @return List of nearby player names
     */
    List<String> getNearbyPlayerNames(Location location, double radius);

    /**
     * Get players near a location as GamePlayer objects
     * 
     * @param location Center location
     * @param radius   Search radius in blocks
     * @return List of nearby players
     */
    List<GamePlayer> getNearbyPlayers(Location location, double radius);

    /**
     * Get the world/dimension name
     * 
     * @return World name
     */
    String getWorldName();

    // ========== AI Action Operations ==========

    /**
     * Makes an NPC start following a player.
     * Uses the NPC's state machine to transition to a Following state.
     * 
     * @param npcInstanceId    The instance ID of the NPC
     * @param targetPlayerName The username of the player to follow
     * @return true if the NPC started following successfully
     */
    boolean startFollowingPlayer(UUID npcInstanceId, String targetPlayerName);

    /**
     * Makes an NPC stop following its current target and return to idle.
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @return true if the NPC stopped following successfully
     */
    boolean stopFollowing(UUID npcInstanceId);

    /**
     * Makes an NPC start attacking a target entity.
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @param targetName    The name of the target (player username or NPC name)
     * @param attackType    The type of attack ("melee" or "ranged")
     * @return true if the NPC started attacking successfully
     */
    boolean startAttacking(UUID npcInstanceId, String targetName, String attackType);

    /**
     * Makes an NPC stop attacking and return to peaceful state.
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @return true if the NPC stopped attacking successfully
     */
    boolean stopAttacking(UUID npcInstanceId);

    /**
     * Checks if an NPC is currently busy (following a target or in combat).
     * Used to determine if idle animations (emotes) should be played.
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @return true if the NPC is following or attacking, false if idle
     */
    boolean isNpcBusy(UUID npcInstanceId);

    // ========== Animation Discovery ==========

    /**
     * Gets all available animation IDs for an NPC based on its model.
     * This is used for dynamic emote tool generation.
     * 
     * @param npcId The external ID of the NPC
     * @return List of animation IDs available for this NPC's model
     */
    List<String> getAvailableAnimations(UUID npcInstanceId);

    // ========== Block Discovery ==========

    /**
     * Gets all available block types on the server with enriched metadata.
     * This is sent to the backend on startup to enable LLM block discovery.
     * 
     * The returned list includes block IDs, display names, material types
     * (wood, stone, ore, etc.), and keywords for semantic matching.
     * 
     * @return List of BlockInfo objects describing available blocks
     */
    List<dev.hycompanion.plugin.core.world.BlockInfo> getAvailableBlocks();

    // ========== Thinking Indicator ==========

    /**
     * Shows a floating "Thinking..." text above an NPC's head with animated dots.
     * The text cycles through "Thinking .", "Thinking ..", "Thinking ..." every
     * 500ms.
     * 
     * @param npcInstanceId The instance ID of the NPC
     */
    void showThinkingIndicator(UUID npcInstanceId);

    /**
     * Hides the thinking indicator above an NPC's head.
     * Should be called when the NPC receives a response.
     * 
     * @param npcInstanceId The instance ID of the NPC
     */
    void hideThinkingIndicator(UUID npcInstanceId);

    /**
     * Remove any "Zombie" thinking indicators that may have persisted from a crash.
     * These are entities with "Thinking..." nameplates that are not currently
     * tracked.
     * 
     * @return Number of indicators removed
     */
    int removeZombieThinkingIndicators();

    // ========== Teleport Operations ==========

    /**
     * Teleport an NPC instance to a specific location.
     * 
     * @param npcInstanceId The instance ID of the NPC to teleport
     * @param location      The target location
     * @return true if teleport was successful
     */
    boolean teleportNpcTo(UUID npcInstanceId, Location location);

    /**
     * Teleport a player to a specific location.
     * 
     * @param playerId The ID of the player to teleport
     * @param location The target location
     * @return true if teleport was successful
     */
    boolean teleportPlayerTo(String playerId, Location location);

    /**
     * Cleanup resources and cancel pending tasks.
     * Should be called when the plugin shuts down.
     */
    default void cleanup() {
    }

    // ========== NPC Respawn Operations ==========

    /**
     * Schedule an NPC to respawn after a specified delay.
     * The NPC will be spawned at its original spawn location with the same role and capabilities.
     * 
     * @param externalId    The external ID (role) of the NPC
     * @param delaySeconds  The delay in seconds before respawning
     */
    default void scheduleNpcRespawn(String externalId, long delaySeconds) {
    }

    /**
     * Cancel a pending NPC respawn task.
     * This can be used to prevent an NPC from respawning if needed.
     * 
     * @param externalId The external ID (role) of the NPC whose respawn should be cancelled
     */
    default void cancelNpcRespawn(String externalId) {
    }
}
