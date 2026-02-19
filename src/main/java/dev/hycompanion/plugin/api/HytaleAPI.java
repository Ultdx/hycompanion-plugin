package dev.hycompanion.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.hycompanion.plugin.api.inventory.*;
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
     * Scan surroundings and return all entities with their details.
     * Excludes the scanning NPC itself.
     * 
     * @param npcId  NPC's instance ID (scan center and exclusion)
     * @param radius Scan radius in blocks
     * @return Map containing current_position, radius, entities array, totalEntities
     */
    CompletableFuture<Optional<Map<String, Object>>> scanEntities(
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

    /**
     * Broadcast a debug message to all OP (operator) players on the server.
     * This is used to show detailed error information only to server administrators.
     * 
     * @param message The debug message to display (will be shown in red)
     */
    void broadcastDebugMessageToOps(String message);

    /**
     * Check if a player is an operator (OP).
     * 
     * @param playerId The player's ID
     * @return true if the player is an operator
     */
    boolean isPlayerOp(String playerId);

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

    // ========== Inventory Operations ==========

    /**
     * Equip an item to the NPC (armor or weapon/tool)
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @param itemId        The item ID to equip
     * @param slot          Target slot (auto, head, chest, hands, legs, hotbar_0, hotbar_1, hotbar_2)
     * @return Result of the equip operation
     */
    EquipResult equipItem(UUID npcInstanceId, String itemId, String slot);

    /**
     * Break a block and return the drops
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @param targetBlock   The block location to break
     * @param toolItemId    Optional tool to use (null for held item)
     * @param maxAttempts   Maximum attempts before giving up
     * @return Result of the break operation
     */
    BreakResult breakBlock(UUID npcInstanceId, Location targetBlock, String toolItemId, int maxAttempts);

    /**
     * Pick up dropped items near the NPC
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @param radius        Pickup radius in blocks
     * @param itemId        Optional specific item ID to pick up (null for any)
     * @param maxItems      Maximum items to pick up
     * @return Result of the pickup operation
     */
    PickupResult pickupItems(UUID npcInstanceId, double radius, String itemId, int maxItems);

    /**
     * Use the currently held item multiple times
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @param target        Target location (block or entity)
     * @param useCount      Number of times to use the item
     * @param intervalMs    Interval between uses in milliseconds
     * @param targetType    Type of target (block or entity)
     * @return Result of the use operation
     */
    UseResult useHeldItem(UUID npcInstanceId, Location target, int useCount, long intervalMs, TargetType targetType);

    /**
     * Drop an item from inventory to the ground
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @param itemId        The item ID to drop
     * @param quantity      Quantity to drop
     * @param throwSpeed    Throw speed (0.5=gentle, 1.0=normal, 2.0=far)
     * @return Result of the drop operation
     */
    DropResult dropItem(UUID npcInstanceId, String itemId, int quantity, float throwSpeed);

    /**
     * Get the NPC's current inventory contents
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @param includeEmpty  Whether to include empty slots in the response
     * @return Snapshot of the inventory
     */
    InventorySnapshot getInventory(UUID npcInstanceId, boolean includeEmpty);

    /**
     * Unequip an item from a specific slot
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @param slot          Slot to unequip
     * @param destroy       If true, destroys the item instead of moving to storage
     * @return Result of the unequip operation
     */
    UnequipResult unequipItem(UUID npcInstanceId, String slot, boolean destroy);

    /**
     * Expand the NPC's inventory storage capacity
     * 
     * @param npcInstanceId The instance ID of the NPC
     * @param storageSlots  Number of storage slots to add
     * @return true if expansion was successful
     */
    boolean expandNpcInventory(UUID npcInstanceId, int storageSlots);

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

    /**
     * Target type for useHeldItem operation
     */
    enum TargetType {
        BLOCK,
        ENTITY
    }
}
