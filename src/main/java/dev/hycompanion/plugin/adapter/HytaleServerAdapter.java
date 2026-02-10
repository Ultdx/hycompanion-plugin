package dev.hycompanion.plugin.adapter;

import io.sentry.Sentry;

import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.TrigMathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import dev.hycompanion.plugin.HycompanionEntrypoint;
import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.core.npc.NpcMoveResult;
import dev.hycompanion.plugin.shutdown.ShutdownManager;
import dev.hycompanion.plugin.utils.PluginLogger;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Real implementation of HytaleAPI using the Hytale Server API
 * 
 * This adapter bridges Hycompanion with the actual Hytale game server,
 * utilizing the real Hytale Server API classes from com.hypixel.hytale.
 * 
 * @author Hycompanion Team
 */
public class HytaleServerAdapter implements HytaleAPI {

    private final PluginLogger logger;
    private final HycompanionEntrypoint plugin;
    private final ShutdownManager shutdownManager;

    // Track spawned NPCs instances by their UUID -> Entity reference
    private final Map<UUID, NpcInstanceData> npcInstanceEntities = new ConcurrentHashMap<>();

    // Track NPC locations cache REMOVED - Using real-time location via
    // getNpcInstanceLocation

    // Use private daemon scheduler instead of HytaleServer.SCHEDULED_EXECUTOR
    private final ScheduledExecutorService rotationScheduler = java.util.concurrent.Executors.newScheduledThreadPool(1,
            r -> {
                Thread t = new Thread(r, "Hycompanion-Rotation-Worker");
                t.setDaemon(true);
                return t;
            });
    private final Map<UUID, ScheduledFuture<?>> rotationTasks = new ConcurrentHashMap<>();

    // Track NPC movement monitoring tasks
    private final Map<UUID, ScheduledFuture<?>> movementTasks = new ConcurrentHashMap<>();

    // Track invisible target entities for move_to
    private final Map<UUID, Ref<EntityStore>> movementTargetEntities = new ConcurrentHashMap<>();

    // Thinking indicator animation tasks per NPC
    private final Map<UUID, ScheduledFuture<?>> thinkingAnimationTasks = new ConcurrentHashMap<>();

    // Track busy NPCs (following or attacking) - used to prevent emotes while
    // moving
    private final Set<UUID> busyNpcs = ConcurrentHashMap.newKeySet();

    // Thinking indicator references per NPC - stored separately for atomic operations
    // Key present with valid ref = indicator exists; Key absent = no indicator
    private final Map<UUID, Ref<EntityStore>> thinkingIndicatorRefs = new ConcurrentHashMap<>();

    // Track original spawn locations for NPC respawn: externalId -> Location
    private final Map<String, Location> npcSpawnLocations = new ConcurrentHashMap<>();

    // Simple pending respawn queue: externalId -> respawn time
    private final Map<String, Instant> pendingRespawns = new ConcurrentHashMap<>();
    private ScheduledFuture<?> respawnCheckerTask = null;

    private java.util.function.Consumer<UUID> removalListener;

    public HytaleServerAdapter(PluginLogger logger, HycompanionEntrypoint plugin, ShutdownManager shutdownManager) {
        this.logger = logger;
        this.plugin = plugin;
        this.shutdownManager = shutdownManager;

        // Register with shutdown manager for automatic cleanup
        shutdownManager.register(this::cleanup);

        logger.info("HytaleServerAdapter initialized - Using real Hytale Server API");
    }

    /**
     * Check if the server is shutting down.
     * Delegates to ShutdownManager for single source of truth.
     */
    private boolean isShuttingDown() {
        return shutdownManager.isShuttingDown();
    }

    /**
     * Safely execute a task on the world thread using ShutdownManager's circuit
     * breaker.
     * This prevents blocking operations during server shutdown.
     */
    private boolean safeWorldExecute(World world, Runnable task) {
        return shutdownManager.safeWorldExecute(world::execute, task);
    }

    // ========== Player Operations ==========

    @Override
    public Optional<GamePlayer> getPlayer(String playerId) {
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);

            if (playerRef != null) {
                return Optional.of(convertToGamePlayer(playerRef));
            }
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid UUID format for player ID: " + playerId);
            Sentry.captureException(e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<GamePlayer> getPlayerByName(String playerName) {
        PlayerRef playerRef = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);

        if (playerRef != null) {
            return Optional.of(convertToGamePlayer(playerRef));
        }
        return Optional.empty();
    }

    @Override
    public List<GamePlayer> getOnlinePlayers() {
        // [Shutdown Fix] Check if we're shutting down to avoid accessing invalid player
        // refs
        if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return Collections.emptyList();
        }
        try {
            return Universe.get().getPlayers().stream()
                    .map(this::convertToGamePlayer)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // During shutdown, player references may become invalid
            logger.debug("Could not get online players (shutdown in progress?): " + e.getMessage());
            Sentry.captureException(e);
            return Collections.emptyList();
        }
    }

    @Override
    public Set<NpcInstanceData> getNpcInstances() {
        return npcInstanceEntities.values().stream()
                .collect(Collectors.toSet());
    }

    @Override
    public NpcInstanceData getNpcInstance(UUID npcInstanceUuid) {
        return npcInstanceEntities.get(npcInstanceUuid);
    }

    @Override
    public void sendMessage(String playerId, String message) {
        // Try to get the player's world first
        World world = null;
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef != null) {
                UUID worldUuid = playerRef.getWorldUuid();
                if (worldUuid != null) {
                    world = Universe.get().getWorld(worldUuid);
                }
            }
        } catch (Exception e) {
            // Ignore, will fall back to default
        }
        
        // Fall back to default world
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }
        
        if (world == null)
            return;

        final World targetWorld = world;
        targetWorld.execute(() -> {
            try {
                UUID uuid = UUID.fromString(playerId);
                PlayerRef playerRef = Universe.get().getPlayer(uuid);

                if (playerRef != null) {
                    Message hytaleMessage = Message.raw(message);
                    playerRef.sendMessage(hytaleMessage);
                    logger.debug("Sent message to player [" + playerId + "]: " + message);
                } else {
                    logger.warn("Player not found for ID: " + playerId);
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid UUID format for player ID: " + playerId);
                Sentry.captureException(e);
            }
        });
    }

    @Override
    public void sendNpcMessage(UUID npcInstanceId, String playerId, String message) {
        // Try to get the player's world first
        World world = null;
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef != null) {
                UUID worldUuid = playerRef.getWorldUuid();
                if (worldUuid != null) {
                    world = Universe.get().getWorld(worldUuid);
                }
            }
        } catch (Exception e) {
            // Ignore, will fall back to default
        }
        
        // Fall back to default world
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }
        
        if (world == null)
            return;

        final World targetWorld = world;
        targetWorld.execute(() -> {
            try {
                UUID uuid = UUID.fromString(playerId);
                PlayerRef playerRef = Universe.get().getPlayer(uuid);

                if (playerRef != null) {
                    // Message is already formatted by ActionExecutor.formatNpcMessage
                    // Apply green color for NPC messages (matching spawn text color)
                    Message hytaleMessage = Message.raw(message).color("#22C55E");
                    playerRef.sendMessage(hytaleMessage);
                    logger.debug("NPC [" + npcInstanceId + "] says to player [" + playerId + "]: " + message);
                } else {
                    logger.warn("Player not found for NPC message: " + playerId);
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid UUID format for player ID: " + playerId);
                Sentry.captureException(e);
            }
        });
    }

    @Override
    public void broadcastNpcMessage(UUID npcInstanceId, List<String> playerIds, String message) {
        if (playerIds == null || playerIds.isEmpty()) {
            logger.debug("No players to broadcast NPC message to");
            return;
        }

        // For broadcasting, players could be in different worlds.
        // We use the default world's executor for simplicity since PlayerRef.sendMessage()
        // works regardless of which world thread we're on.
        World world = Universe.get().getDefaultWorld();
        if (world == null)
            return;

        world.execute(() -> {
            // Pre-create the message once for efficiency
            // Apply green color for NPC messages (matching spawn text color)
            Message hytaleMessage = Message.raw(message).color("#22C55E");
            int sent = 0;

            for (String playerId : playerIds) {
                try {
                    UUID uuid = UUID.fromString(playerId);
                    PlayerRef playerRef = Universe.get().getPlayer(uuid);

                    if (playerRef != null) {
                        playerRef.sendMessage(hytaleMessage);
                        sent++;
                    }
                } catch (IllegalArgumentException e) {
                    // Skip invalid UUIDs
                }
            }
            logger.debug("NPC [" + npcInstanceId + "] broadcast message to " + sent + " players: " + message);
        });
    }

    @Override
    public void sendErrorMessage(String playerId, String message) {
        // Try to get the player's world first
        World world = null;
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef != null) {
                UUID worldUuid = playerRef.getWorldUuid();
                if (worldUuid != null) {
                    world = Universe.get().getWorld(worldUuid);
                }
            }
        } catch (Exception e) {
            // Ignore, will fall back to default
        }
        
        // Fall back to default world
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }
        
        if (world == null)
            return;

        final World targetWorld = world;
        targetWorld.execute(() -> {
            try {
                UUID uuid = UUID.fromString(playerId);
                PlayerRef playerRef = Universe.get().getPlayer(uuid);

                if (playerRef != null) {
                    // Use RED color #FF0000 for error messages
                    Message hytaleMessage = Message.raw(message).color("#FF0000");
                    playerRef.sendMessage(hytaleMessage);
                    logger.debug("Error message sent to player [" + playerId + "]: " + message);
                } else {
                    logger.warn("Player not found for error message: " + playerId);
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid UUID format for player ID: " + playerId);
                Sentry.captureException(e);
            }
        });
    }

    // ========== NPC Operations ==========

    @Override
    public Optional<UUID> spawnNpc(String externalId, String name, Location location) {
        logger.info("[Hycompanion] Spawning NPC [" + externalId + "] (" + name + ") at " + location);

        try {
            World world = getWorldByName(location.world());
            if (world == null) {
                world = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] spawnNpc: Could not find world '" + location.world() + "', falling back to default world");
            }

            if (world == null) {
                logger.warn("No world available to spawn NPC");
                return Optional.empty();
            }

            // Check if role exists in the NPC system
            NPCPlugin npcPlugin = NPCPlugin.get();
            int roleIndex = npcPlugin.getIndex(externalId);

            if (roleIndex < 0) {
                // Role doesn't exist - create a placeholder UUID
                // The NPC role must be defined in Hytale's NPC Role assets
                logger.warn("NPC role '" + externalId + "' not found in NPC definitions. " +
                        "Ensure the role file '" + externalId + ".json' exists in mods/<mod>/Server/NPC/Roles/ " +
                        "and restart the server to load new roles.");
                UUID placeholderUuid = UUID.nameUUIDFromBytes(externalId.getBytes());
                return Optional.of(placeholderUuid);
            }

            // Spawn NPC using the Hytale NPC API
            Store<EntityStore> store = world.getEntityStore().getStore();
            Vector3d position = new Vector3d(location.x(), location.y(), location.z());
            Vector3f rotation = new Vector3f(0, 0, 0);

            it.unimi.dsi.fastutil.Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(
                    store,
                    roleIndex,
                    position,
                    rotation,
                    null, // spawnModel - use default from role
                    null // postSpawn callback
            );

            if (npcPair != null) {
                Ref<EntityStore> entityRef = npcPair.first();
                NPCEntity npcEntity = npcPair.second();

                // CRITICAL: Initialize leash point for WanderInCircle motion to work
                // WanderInCircle uses getLeashPoint() as the center reference for wandering
                // This mirrors what NPCSystems.AddedFromExternalSystem does for WorldGen/Prefab
                // NPCs
                npcEntity.setLeashPoint(position);
                npcEntity.setLeashHeading(rotation.getYaw());
                npcEntity.setLeashPitch(rotation.getPitch());

                // DEBUG: Log leash point and role state
                Vector3d leashPoint = npcEntity.getLeashPoint();
                logger.info("[Spawn Debug] NPC: " + externalId);
                logger.info("[Spawn Debug]   Spawn position: " + position.getX() + ", " + position.getY() + ", "
                        + position.getZ());
                logger.info("[Spawn Debug]   Leash point: " + leashPoint.getX() + ", " + leashPoint.getY() + ", "
                        + leashPoint.getZ());

                var role = npcEntity.getRole();
                if (role != null) {
                    logger.info("[Spawn Debug]   Role: " + role.getClass().getSimpleName());
                    logger.info("[Spawn Debug]   Role Name: " + npcEntity.getRoleName());
                    logger.info("[Spawn Debug]   RequiresLeashPosition: " + role.requiresLeashPosition());

                    // Log all methods available on the role for debugging
                    logger.info("[Spawn Debug]   Available methods on Role:");
                    java.lang.reflect.Method[] methods = role.getClass().getMethods();
                    for (java.lang.reflect.Method m : methods) {
                        if (m.getName().toLowerCase().contains("instruction") ||
                                m.getName().toLowerCase().contains("state") ||
                                m.getName().toLowerCase().contains("motion")) {
                            logger.info("[Spawn Debug]     - " + m.getName() + "()");
                        }
                    }
                    var stateSupport = role.getStateSupport();
                    if (stateSupport != null) {
                        logger.info("[Spawn Debug]   Current state: " + stateSupport.getStateName());

                        // Check available states
                        try {
                            java.lang.reflect.Method getAvailableStatesMethod = stateSupport.getClass()
                                    .getMethod("getAvailableStates");
                            Object states = getAvailableStatesMethod.invoke(stateSupport);
                            if (states != null) {
                                logger.info("[Spawn Debug]   Available states: " + states.toString());
                            }
                        } catch (Exception e) {
                            // Method might not exist, ignore
                        }
                    }
                    var activeController = role.getActiveMotionController();
                    if (activeController != null) {
                        logger.info(
                                "[Spawn Debug]   Motion controller: " + activeController.getClass().getSimpleName());
                    } else {
                        logger.warn("[Spawn Debug]   No active motion controller!");
                    }
                } else {
                    logger.warn("[Spawn Debug]   Role is NULL!");
                }

                // Get UUID from the entity
                UUID entityUuid = npcEntity.getUuid();
                if (entityUuid == null) {
                    entityUuid = UUID.nameUUIDFromBytes(externalId.getBytes());
                }

                NpcData npcData = plugin.getNpcManager().getNpc(externalId).orElse(null);
                if (npcData == null) {
                    logger.warn("NpcData not found for spawned NPC: " + externalId);
                    // Try to proceed if possible or handle error?
                    // For now, we need NpcData to create the instance record.
                    // If it's missing, we can't track it properly.
                    return Optional.ofNullable(entityUuid);
                }
                // Store the spawn location for respawn purposes (linked to externalId)
                Location spawnLocation = Location.of(location.x(), location.y(), location.z(),
                        world.getName() != null ? world.getName() : "world");
                npcSpawnLocations.put(externalId, spawnLocation);

                NpcInstanceData npcInstance = new NpcInstanceData(entityUuid, entityRef, npcEntity, npcData,
                        spawnLocation);

                // Apply capabilities using helper method
                applyNpcCapabilities(store, entityRef, npcEntity, npcData, "Spawn");

                npcInstanceEntities.put(entityUuid, npcInstance);

                // Bind entity immediately so NpcManager knows about it
                plugin.getNpcManager().bindEntity(externalId, entityUuid);

                logger.info("NPC spawned successfully with UUID: " + entityUuid);
                return Optional.of(entityUuid);
            } else {
                logger.warn("Failed to spawn NPC entity");
                return Optional.empty();
            }

        } catch (Exception e) {
            logger.error("Failed to spawn NPC: " + e.getMessage());
            Sentry.captureException(e);
            return Optional.empty();
        }
    }

    @Override
    public boolean removeNpc(UUID npcInstanceId) {
        logger.info("[Hycompanion] Removing NPC [" + npcInstanceId + "]");

        // Get instance data first (before removing)
        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        String externalId = (npcInstanceData != null) ? npcInstanceData.npcData().externalId() : null;

        // Destroy thinking indicator first (before removing NPC)
        destroyThinkingIndicator(npcInstanceId);

        // [Shutdown Fix] During shutdown, don't try to remove entities from the world.
        // Hytale's shutdown process handles entity cleanup. Just remove from our
        // tracking.
        if (isShuttingDown()) {
            npcInstanceEntities.remove(npcInstanceId);
            if (externalId != null) {
                pendingRespawns.remove(externalId);
            }
            if (npcInstanceData != null && removalListener != null) {
                removalListener.accept(npcInstanceId);
            }
            logger.debug("[Hycompanion] Skipping NPC removal during shutdown: " + npcInstanceId);
            return npcInstanceData != null;
        }

        // Remove from our tracking maps immediately to prevent further usage
        npcInstanceEntities.remove(npcInstanceId);
        Ref<EntityStore> entityRef = (npcInstanceData != null) ? npcInstanceData.entityRef() : null;

        // Cancel any pending respawn for this NPC type
        if (externalId != null) {
            pendingRespawns.remove(externalId);
        }

        // Notify listener
        if (removalListener != null) {
            removalListener.accept(npcInstanceId);
        }

        if (npcInstanceData == null) {
            return false;
        }

        if (entityRef == null) {
            logger.warn("[Hycompanion] NPC entity reference not found for: " + npcInstanceId +
                    " (NPC may not have been spawned via plugin or entity tracking was lost)");
            return false;
        }

        // Schedule removal on the world thread to respect Hytale's threading rules
        try {
            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] removeNpc: Could not find world '" + worldName + "' for NPC, falling back to default world");
            }
            if (resolvedWorld != null) {
                final World world = resolvedWorld;
                world.execute(() -> {
                    try {
                        // Check validity inside the thread
                        if (!entityRef.isValid()) {
                            logger.warn("[Hycompanion] NPC entity reference is invalid for: " + npcInstanceId +
                                    " (entity may have already been removed)");
                            return;
                        }

                        Store<EntityStore> store = entityRef.getStore();
                        if (store == null) {
                            logger.error("[Hycompanion] Could not get entity store for NPC: " + npcInstanceId);
                            return;
                        }

                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            npcEntity.remove();
                            logger.info("[Hycompanion] Successfully removed NPC entity: " + npcInstanceId);
                        } else {
                            logger.warn("[Hycompanion] NPC component not found on entity: " + npcInstanceId);
                        }
                    } catch (Exception e) {
                        logger.error(
                                "[Hycompanion] Failed to remove NPC entity '" + npcInstanceId + "': " + e.getMessage());
                        Sentry.captureException(e);
                        e.printStackTrace();
                    }
                });

                // Return true to indicate the removal request was successfully scheduled
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to schedule NPC removal: " + e.getMessage());
            Sentry.captureException(e);
        }

        return false;
    }

    /**
     * Handle NPC death - schedules respawn for this NPC type.
     * Simple approach: link respawn to externalId, not instance UUID.
     */
    public boolean handleNpcDeath(UUID npcInstanceId, String externalId) {
        logger.info("[Hycompanion] NPC died [" + npcInstanceId + "] (" + externalId + "), scheduling respawn");

        // Remove from tracking
        npcInstanceEntities.remove(npcInstanceId);
        
        // Notify removal
        if (removalListener != null) {
            removalListener.accept(npcInstanceId);
        }

        // Schedule respawn using game time (30 seconds)
        scheduleNpcRespawn(externalId, 30);
        return true;
    }

    /**
     * Schedule a respawn for an NPC type at its registered spawn location.
     */
    @Override
    public void scheduleNpcRespawn(String externalId, long delaySeconds) {
        // Check if spawn location exists
        Location spawnLocation = npcSpawnLocations.get(externalId);
        if (spawnLocation == null) {
            logger.warn("[Hycompanion] No spawn location for '" + externalId + "', cannot respawn");
            return;
        }

        // Use real time for scheduling (simpler and more reliable)
        Instant respawnAt = Instant.now().plusSeconds(delaySeconds);
        pendingRespawns.put(externalId, respawnAt);
        logger.info("[Hycompanion] Respawn scheduled for '" + externalId + "' in " + delaySeconds + " seconds");

        // Ensure checker is running
        startRespawnChecker();
    }

    @Override
    public void cancelNpcRespawn(String externalId) {
        if (pendingRespawns.remove(externalId) != null) {
            logger.debug("[Hycompanion] Cancelled respawn for '" + externalId + "'");
        }
    }

    /**
     * Start the periodic respawn checker if not already running.
     */
    private void startRespawnChecker() {
        if (respawnCheckerTask != null && !respawnCheckerTask.isDone()) {
            return; // Already running
        }
        
        respawnCheckerTask = rotationScheduler.scheduleAtFixedRate(() -> {
            if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
                return;
            }
            checkPendingRespawns();
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Check all pending respawns and execute any that are due.
     */
    private void checkPendingRespawns() {
        if (pendingRespawns.isEmpty()) {
            return;
        }

        Instant now = Instant.now(); // Realtime check is fine for scheduling
        List<String> toRespawn = new ArrayList<>();
        
        // Find due respawns
        for (Map.Entry<String, Instant> entry : pendingRespawns.entrySet()) {
            if (entry.getValue().isBefore(now)) {
                toRespawn.add(entry.getKey());
            }
        }

        // Execute each due respawn on appropriate world thread
        for (String externalId : toRespawn) {
            pendingRespawns.remove(externalId);
            executeRespawn(externalId);
        }
    }

    /**
     * Execute the actual respawn on the world thread.
     */
    private void executeRespawn(String externalId) {
        Location spawnLocation = npcSpawnLocations.get(externalId);
        if (spawnLocation == null) {
            logger.warn("[Hycompanion] Spawn location lost for '" + externalId + "'");
            return;
        }

        World resolvedWorld = getWorldByName(spawnLocation.world());
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] executeRespawn: Could not find world '" + spawnLocation.world() + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            logger.error("[Hycompanion] World not available for respawn of '" + externalId + "'");
            return;
        }
        final World world = resolvedWorld;

        NpcData npcData = plugin.getNpcManager().getNpc(externalId).orElse(null);
        if (npcData == null) {
            logger.warn("[Hycompanion] NPC data not found for '" + externalId + "'");
            return;
        }

        world.execute(() -> {
            if (isShuttingDown()) {
                return;
            }

            try {
                NPCPlugin npcPlugin = NPCPlugin.get();
                int roleIndex = npcPlugin.getIndex(externalId);
                if (roleIndex < 0) {
                    logger.warn("[Hycompanion] Role '" + externalId + "' not found");
                    return;
                }

                Store<EntityStore> store = world.getEntityStore().getStore();
                Vector3d position = new Vector3d(spawnLocation.x(), spawnLocation.y(), spawnLocation.z());
                Vector3f rotation = new Vector3f(0, 0, 0);

                var npcPair = npcPlugin.spawnEntity(store, roleIndex, position, rotation, null, null);
                if (npcPair == null) {
                    logger.error("[Hycompanion] Failed to respawn '" + externalId + "'");
                    return;
                }

                Ref<EntityStore> entityRef = npcPair.first();
                NPCEntity npcEntity = npcPair.second();
                npcEntity.setLeashPoint(position);
                npcEntity.setLeashHeading(rotation.getYaw());
                npcEntity.setLeashPitch(rotation.getPitch());

                UUID entityUuid = npcEntity.getUuid();
                NpcInstanceData instance = new NpcInstanceData(entityUuid, entityRef, npcEntity, npcData, spawnLocation);
                
                applyNpcCapabilities(store, entityRef, npcEntity, npcData, "Respawn");
                npcInstanceEntities.put(entityUuid, instance);
                plugin.getNpcManager().bindEntity(externalId, entityUuid);

                logger.info("[Hycompanion] NPC '" + externalId + "' respawned successfully as " + entityUuid);
            } catch (Exception e) {
                logger.error("[Hycompanion] Error respawning '" + externalId + "': " + e.getMessage());
                Sentry.captureException(e);
            }
        });
    }

    @Override
    public void updateNpcCapabilities(String externalId, NpcData npcData) {
        logger.info("[Hycompanion] Updating capabilities for NPC role: " + externalId);

        // Find all active instances for this externalId
        List<NpcInstanceData> instancesToUpdate = new ArrayList<>();
        for (NpcInstanceData instance : npcInstanceEntities.values()) {
            if (instance.npcData().externalId().equals(externalId)) {
                instancesToUpdate.add(instance);
            }
        }

        if (instancesToUpdate.isEmpty()) {
            logger.debug("No active instances found for update: " + externalId);
            return;
        }

        // Group instances by world to execute on the correct world thread for each
        Map<String, List<NpcInstanceData>> instancesByWorld = new HashMap<>();
        for (NpcInstanceData instance : instancesToUpdate) {
            String worldName = instance.spawnLocation() != null ? instance.spawnLocation().world() : "default";
            instancesByWorld.computeIfAbsent(worldName, k -> new ArrayList<>()).add(instance);
        }

        // Execute on each world's thread
        for (Map.Entry<String, List<NpcInstanceData>> entry : instancesByWorld.entrySet()) {
            String worldName = entry.getKey();
            List<NpcInstanceData> worldInstances = entry.getValue();
            
            World world = Universe.get().getWorld(worldName);
            if (world == null) {
                world = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] updateNpcCapabilities: Could not find world '" + worldName + "', falling back to default world");
            }
            if (world == null) {
                continue;
            }
            
            world.execute(() -> {
                for (NpcInstanceData instance : worldInstances) {
                    try {
                        Ref<EntityStore> entityRef = instance.entityRef();
                        if (entityRef != null && entityRef.isValid()) {
                            Store<EntityStore> store = entityRef.getStore();
                            NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());

                            if (npcEntity != null) {
                                applyNpcCapabilities(store, entityRef, npcEntity, npcData, "Update");

                                // Update stored data record to keep it fresh
                                // NpcInstanceData is a record, so we create a new one
                                // Preserve the spawn location from the existing instance
                                NpcInstanceData newInstance = new NpcInstanceData(
                                        instance.entityUuid(),
                                        instance.entityRef(),
                                        instance.npcEntity(),
                                        npcData, // Use updated data
                                        instance.spawnLocation() // Preserve spawn location
                                );

                                npcInstanceEntities.put(instance.entityUuid(), newInstance);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Failed to update capabilities for instance " + instance.entityUuid() + ": "
                                + e.getMessage());
                        Sentry.captureException(e);
                    }
                }
            });
        }
    }

    /**
     * Helper to apply capability flags (Invincibility, Knockback) to an NPC entity
     */
    private void applyNpcCapabilities(Store<EntityStore> store, Ref<EntityStore> entityRef, NPCEntity npcEntity,
            NpcData npcData, String context) {
        String externalId = npcData.externalId();

        // 1. Invincibility
        try {
            EffectControllerComponent effect = (EffectControllerComponent) store.getComponent(entityRef,
                    EffectControllerComponent.getComponentType());
            if (effect != null) {
                // Apply value from data
                effect.setInvulnerable(npcData.isInvincible());
                if (npcData.isInvincible()) {
                    logger.debug("[" + context + "] Set invincible: " + externalId);
                }
            } else {
                logger.warn("[" + context + "] EffectControllerComponent missing for: " + externalId);
            }
        } catch (Exception e) {
            logger.warn("[" + context + "] Failed to apply invulnerability: " + e.getMessage());
            Sentry.captureException(e);
        }

        // 2. Knockback Prevention
        try {
            var role = npcEntity.getRole();
            if (role != null) {
                // Determine target scale
                boolean disabled = npcData.isKnockbackDisabled();
                double targetScale = disabled ? 0.0 : 1.0;

                if (disabled || context.equals("Update")) {
                    logger.debug("[" + context + "] Applying knockback scale: " + targetScale + " (Disabled: "
                            + disabled + ") for " + externalId);
                }

                // 1. Update active controller immediately
                MotionController activeController = role.getActiveMotionController();
                if (activeController != null) {
                    activeController.setKnockbackScale(targetScale);
                }

                // 2. Update ALL controllers via reflection
                try {
                    Field controllersField = com.hypixel.hytale.server.npc.role.Role.class
                            .getDeclaredField("motionControllers");
                    controllersField.setAccessible(true);

                    @SuppressWarnings("unchecked")
                    Map<String, MotionController> controllers = (Map<String, MotionController>) controllersField
                            .get(role);

                    if (controllers != null && !controllers.isEmpty()) {
                        for (MotionController mc : controllers.values()) {
                            mc.setKnockbackScale(targetScale);
                        }
                        if (disabled) {
                            logger.debug("Updated " + controllers.size() + " motion controllers");
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to update motionControllers map: " + e.getMessage());
                    Sentry.captureException(e);
                }

                // 3. CRITICAL: Update Role master field "knockbackScale"
                // This is required because Role.updateMotionControllers() resets all
                // controllers
                // to this value periodically (e.g. on model updates).
                try {
                    Field scaleField = com.hypixel.hytale.server.npc.role.Role.class.getDeclaredField("knockbackScale");
                    scaleField.setAccessible(true);
                    scaleField.setDouble(role, targetScale);
                    // logger.debug("Updated Role master knockbackScale field");
                } catch (Exception e) {
                    logger.warn("Failed to update Role master knockbackScale: " + e.getMessage());
                    Sentry.captureException(e);
                }
            }
        } catch (Exception e) {
            logger.warn("[" + context + "] Failed to update knockback: " + e.getMessage());
            Sentry.captureException(e);
        }
    }

    @Override
    public void triggerNpcEmote(UUID npcInstanceId, String animationName) {
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] plays animation: " + animationName);

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.error("[Hycompanion] Cannot play animation - entity not tracked for NPC: " + npcInstanceId);
            return;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef != null && entityRef.isValid()) {
            try {
                // Get the world from the entity store and execute on world thread
                Store<EntityStore> store = entityRef.getStore();
                
                // Get the correct world for this NPC, not the default world
                String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
                World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
                if (resolvedWorld == null) {
                    resolvedWorld = Universe.get().getDefaultWorld();
                    logger.warn("[Hycompanion] triggerNpcEmote: Could not find world '" + worldName + "' for NPC, falling back to default world");
                }

                if (resolvedWorld != null) {
                    final World world = resolvedWorld;
                    // Animation must be played on the world thread
                    world.execute(() -> {
                        try {
                            NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                            if (npcEntity != null) {
                                // Play the animation directly by name from the model's AnimationSets
                                // The animation name should match a key from the model's AnimationSets
                                // (e.g., "Sit", "Sleep", "Howl", "Greet", "Wave", etc.)
                                npcEntity.playAnimation(entityRef, com.hypixel.hytale.protocol.AnimationSlot.Action,
                                        animationName, store);
                                logger.debug("[Hycompanion] Animation '" + animationName + "' triggered for NPC "
                                        + npcInstanceId);
                            }
                        } catch (Exception e) {
                            logger.debug("Could not play animation on world thread: " + e.getMessage());
                            Sentry.captureException(e);
                        }
                    });
                }
            } catch (Exception e) {
                logger.debug("Could not play animation: " + e.getMessage());
                Sentry.captureException(e);
            }
        } else {
            logger.debug("Cannot play animation - entity not tracked for NPC: " + npcInstanceId);
        }
    }

    @Override
    public void registerNpcRemovalListener(java.util.function.Consumer<UUID> listener) {
        this.removalListener = listener;
    }

    @Override
    public CompletableFuture<NpcMoveResult> moveNpcTo(UUID npcInstanceId,
            Location location) {
        CompletableFuture<NpcMoveResult> result = new CompletableFuture<>();
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] moving to " + location);

        // Cancel previous tasks
        cleanupMovement(npcInstanceId);

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.error("[Hycompanion] Cannot move NPC - entity not tracked for NPC: " + npcInstanceId);
            result.complete(NpcMoveResult.failed("entity_not_tracked"));
            return result;
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef != null && entityRef.isValid()) {
            try {
                Store<EntityStore> store = entityRef.getStore();
                
                // Get the correct world for this NPC, not the default world
                String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
                World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
                if (resolvedWorld == null) {
                    resolvedWorld = Universe.get().getDefaultWorld();
                    logger.warn("[Hycompanion] moveNpcTo: Could not find world '" + worldName + "' for NPC, falling back to default world");
                }

                if (resolvedWorld != null) {
                    final World world = resolvedWorld;
                    world.execute(() -> {
                        try {
                            // 1. Spawn invisible target entity (Projectile with NetworkId + BoundingBox)
                            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();

                            ProjectileComponent projectile = new ProjectileComponent("Projectile");
                            holder.putComponent(ProjectileComponent.getComponentType(), projectile);

                            Vector3d pos = new Vector3d(location.x(), location.y(), location.z());
                            holder.putComponent(TransformComponent.getComponentType(),
                                    new TransformComponent(pos, new Vector3f(0, 0, 0)));

                            holder.ensureComponent(UUIDComponent.getComponentType());

                            // Keep it intangible so NPC doesn't collide
                            holder.ensureComponent(Intangible.getComponentType());

                            // Add NetworkId so it registers in spatial systems and client
                            // (Invisible but valid for AI)
                            holder.addComponent(NetworkId.getComponentType(),
                                    new NetworkId(
                                            world.getEntityStore().getStore().getExternalData().takeNextNetworkId()));

                            // Basic initialization
                            projectile.initialize();

                            Ref<EntityStore> targetRef = world.getEntityStore().getStore().addEntity(holder,
                                    AddReason.SPAWN);

                            if (targetRef != null && targetRef.isValid()) {
                                movementTargetEntities.put(npcInstanceId, targetRef);

                                NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                                if (npcEntity != null) {
                                    var role = npcEntity.getRole();
                                    if (role != null) {
                                        // 2. Reset Animation/State
                                        try {
                                            AnimationUtils.stopAnimation(entityRef, AnimationSlot.Action, true, store);
                                            npcEntity.playAnimation(entityRef, AnimationSlot.Action, "Idle", store);
                                        } catch (Exception e) {
                                        }
                                        try {
                                            role.getStateSupport().setState(entityRef, "Idle", "Default", store);
                                        } catch (Exception e) {
                                        }

                                        // 3. Set LockedTarget to our invisible entity
                                        role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", targetRef);

                                        // 4. Set state to Follow
                                        role.getStateSupport().setState(entityRef, "Follow", "Default", store);
                                        busyNpcs.add(npcInstanceId);
                                        logger.info("[Hycompanion] NPC started following target marker to " + location);
                                    }
                                }
                            } else {
                                logger.error("Failed to spawn movement target entity");
                                result.complete(NpcMoveResult.failed("spawn_failed"));
                            }
                        } catch (Exception e) {
                            logger.error("Error setting up move: " + e.getMessage());
                            Sentry.captureException(e);
                            result.complete(NpcMoveResult.failed("error: " + e.getMessage()));
                        }
                    });

                    // Start monitoring
                    AtomicInteger ticks = new AtomicInteger(0);
                    // Track position for stuck detection: [x, y, z, timestamp_ms]
                    AtomicReference<double[]> lastPosition = new AtomicReference<>(new double[] { 0, 0, 0, 0 });
                    // Stuck detection: 3 seconds without significant movement
                    final long STUCK_TIMEOUT_MS = 3000;
                    final double STUCK_DISTANCE_THRESHOLD = 0.2; // 0.2 blocks = 20cm
                    // Absolute maximum timeout: 5 minutes (for very long distances)
                    final int MAX_TICKS = 5 * 60 * 4; // 5 min * 60 sec * 4 ticks/sec (250ms interval)

                    ScheduledFuture<?> task = rotationScheduler.scheduleAtFixedRate(() -> {
                        // [Shutdown Fix] Check shutdown flag first
                        if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
                            result.complete(NpcMoveResult.failed("shutdown"));
                            cleanupMovement(npcInstanceId);
                            return;
                        }

                        // Check if already done (completed by another execution)
                        if (result.isDone()) {
                            cleanupMovement(npcInstanceId);
                            return;
                        }

                        // Increment tick counter
                        int currentTick = ticks.incrementAndGet();

                        // Absolute maximum timeout (5 minutes) as safety net
                        if (currentTick > MAX_TICKS) {
                            logger.info(
                                    "[Hycompanion] Move absolute timeout after " + currentTick + " checks (~5 min)");

                            safeWorldExecute(world, () -> {
                                if (result.isDone())
                                    return;
                                try {
                                    Location finalLoc = null;
                                    if (entityRef.isValid()) {
                                        TransformComponent t = store.getComponent(entityRef,
                                                TransformComponent.getComponentType());
                                        if (t != null) {
                                            Vector3d p = t.getPosition();
                                            finalLoc = Location.of(p.getX(), p.getY(), p.getZ(), "world");
                                        }
                                    }
                                    result.complete(NpcMoveResult.timeout(finalLoc));
                                    cleanupMovementAndResetState(npcInstanceId, entityRef, store);
                                } catch (Exception e) {
                                    Sentry.captureException(e);
                                    result.complete(NpcMoveResult.timeout(null));
                                    cleanupMovement(npcInstanceId);
                                }
                            });
                            return;
                        }

                        // Check arrival and stuck detection on world thread
                        safeWorldExecute(world, () -> {
                            // Double-check completion status inside world thread
                            if (result.isDone()) {
                                return;
                            }
                            try {
                                if (!entityRef.isValid()) {
                                    result.complete(NpcMoveResult.failed("entity_lost"));
                                    cleanupMovement(npcInstanceId);
                                    return;
                                }
                                TransformComponent t = store.getComponent(entityRef,
                                        TransformComponent.getComponentType());
                                if (t == null) {
                                    return;
                                }

                                Vector3d currentPos = t.getPosition();
                                double currentX = currentPos.getX();
                                double currentY = currentPos.getY();
                                double currentZ = currentPos.getZ();

                                // Calculate distance to target
                                double dx = currentX - location.x();
                                double dy = currentY - location.y();
                                double dz = currentZ - location.z();
                                double distSq = dx * dx + dy * dy + dz * dz;

                                // Check arrival - Threshold: 9 blocks squared = 3 blocks actual distance
                                if (distSq < 9.0) {
                                    double distance = Math.sqrt(distSq);
                                    logger.info("[Hycompanion] NPC arrived at destination, distance=" +
                                            String.format("%.2f", distance) + " blocks");
                                    Location finalLoc = Location.of(currentX, currentY, currentZ, "world");
                                    result.complete(NpcMoveResult.success(finalLoc));
                                    cleanupMovementAndResetState(npcInstanceId, entityRef, store);
                                    return;
                                }

                                // Stuck detection: check if NPC hasn't moved significantly
                                double[] lastPos = lastPosition.get();
                                long now = System.currentTimeMillis();

                                if (lastPos[3] == 0) {
                                    // First position recording
                                    lastPosition.set(new double[] { currentX, currentY, currentZ, (double) now });
                                } else {
                                    double moveDx = currentX - lastPos[0];
                                    double moveDy = currentY - lastPos[1];
                                    double moveDz = currentZ - lastPos[2];
                                    double movedDist = Math.sqrt(moveDx * moveDx + moveDy * moveDy + moveDz * moveDz);

                                    if (movedDist > STUCK_DISTANCE_THRESHOLD) {
                                        // NPC moved significantly, update last position
                                        lastPosition.set(new double[] { currentX, currentY, currentZ, (double) now });
                                    } else {
                                        // NPC hasn't moved much, check if stuck for 3+ seconds
                                        long timeSinceLastMove = now - (long) lastPos[3];
                                        if (timeSinceLastMove > STUCK_TIMEOUT_MS) {
                                            double distance = Math.sqrt(distSq);
                                            logger.info(
                                                    "[Hycompanion] NPC stuck detected! No significant movement for " +
                                                            (timeSinceLastMove / 1000) + "s, distance=" +
                                                            String.format("%.2f", distance) + " blocks from target");

                                            Location finalLoc = Location.of(currentX, currentY, currentZ, "world");
                                            result.complete(NpcMoveResult.timeout(finalLoc));
                                            cleanupMovementAndResetState(npcInstanceId, entityRef, store);
                                            return;
                                        }
                                    }
                                }

                                // Progress logging every 5 seconds (20 ticks * 250ms)
                                if (currentTick % 10 == 0) {
                                    double distance = Math.sqrt(distSq);
                                    logger.debug("[Hycompanion] NPC moving... distance=" +
                                            String.format("%.2f", distance) + " blocks to target");
                                }
                            } catch (Exception e) {
                                logger.debug("[Hycompanion] Error checking NPC position: " + e.getMessage());
                                Sentry.captureException(e);
                            }
                        });

                    }, 250, 250, TimeUnit.MILLISECONDS);
                    movementTasks.put(npcInstanceId, task);

                } else {
                    result.complete(NpcMoveResult.failed("no_world"));
                }
            } catch (Exception e) {
                logger.error("Could not move NPC: " + e.getMessage());
                Sentry.captureException(e);
                result.complete(NpcMoveResult.failed("error: " + e.getMessage()));
            }
        } else {
            result.complete(NpcMoveResult.failed("entity_ref_invalid"));
        }

        return result;
    }

    private void removeEntity(Ref<EntityStore> ref) {
        // [Shutdown Fix] Don't try to remove entities during shutdown
        if (isShuttingDown()) {
            return;
        }
        try {
            // Try to get the world from the entity's store
            World world = null;
            if (ref.isValid()) {
                try {
                    Store<EntityStore> store = ref.getStore();
                    if (store != null) {
                        world = store.getExternalData().getWorld();
                    }
                } catch (Exception e) {
                    // Ignore, will fall back to default
                }
            }
            // Fall back to default world
            if (world == null) {
                world = Universe.get().getDefaultWorld();
            }
            if (world != null) {
                world.execute(() -> {
                    if (ref.isValid()) {
                        ref.getStore().removeEntity(ref, com.hypixel.hytale.component.RemoveReason.REMOVE);
                    }
                });
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // World executor rejected task - server shutting down, this is expected
            logger.debug("[Hycompanion] Could not remove entity - world shutting down");
            Sentry.captureException(e);
        } catch (Exception e) {
            // Ignore other exceptions
            Sentry.captureException(e);
        }
    }

    private void cleanupMovement(UUID npcInstanceId) {
        ScheduledFuture<?> t = movementTasks.remove(npcInstanceId);
        if (t != null)
            t.cancel(false);

        Ref<EntityStore> target = movementTargetEntities.remove(npcInstanceId);
        if (target != null)
            removeEntity(target);
    }

    /**
     * Cleanup movement and reset NPC state to Idle.
     * This should be called from within a world thread execution.
     */
    private void cleanupMovementAndResetState(UUID npcInstanceId, Ref<EntityStore> entityRef,
            Store<EntityStore> store) {
        // Reset NPC state to Idle
        try {
            NPCEntity npc = store.getComponent(entityRef, NPCEntity.getComponentType());
            if (npc != null && npc.getRole() != null) {
                npc.getRole().getStateSupport().setState(entityRef, "Idle", "Default", store);
                npc.getRole().getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
                busyNpcs.remove(npcInstanceId);
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] Error resetting NPC state: " + e.getMessage());
            Sentry.captureException(e);
        }

        // Cleanup movement tracking
        cleanupMovement(npcInstanceId);
    }

    // Circuit breaker for timeouts
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger(0);

    @Override
    public CompletableFuture<Optional<Map<String, Object>>> findBlock(UUID npcInstanceId, String tag, int radius) {
        CompletableFuture<Optional<Map<String, Object>>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.empty());
            return future;
        }

        // Get the correct world for this NPC, not the default world
        String worldName = npcData.spawnLocation() != null ? npcData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] findBlock: Could not find world '" + worldName + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            future.complete(Optional.empty());
            return future;
        }
        final World world = resolvedWorld;

        world.execute(() -> {
            try {
                // Get NPC current position
                Ref<EntityStore> ref = npcData.entityRef();
                if (!ref.isValid()) {
                    future.complete(Optional.empty());
                    return;
                }
                Store<EntityStore> store = ref.getStore();
                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform == null) {
                    future.complete(Optional.empty());
                    return;
                }
                Vector3d pos = transform.getPosition();
                int startX = (int) Math.floor(pos.getX());
                int startY = (int) Math.floor(pos.getY());
                int startZ = (int) Math.floor(pos.getZ());

                var blockTypeAssetMap = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType
                        .getAssetMap();
                if (blockTypeAssetMap == null) {
                    logger.debug("BlockType asset map is null");
                    future.complete(Optional.empty());
                    return;
                }

                // Build a set of block IDs to search for
                it.unimi.dsi.fastutil.ints.IntSet targetBlockIds = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
                String searchDescription;

                // Strategy 1: Try exact match as block ID first
                int directBlockId = blockTypeAssetMap.getIndex(tag);
                if (directBlockId != Integer.MIN_VALUE && blockTypeAssetMap.getAsset(directBlockId) != null) {
                    targetBlockIds.add(directBlockId);
                    searchDescription = "block ID: " + tag;
                    logger.debug("Found exact block ID match: " + tag + " -> " + directBlockId);
                } else {
                    // Strategy 2: Try as a tag
                    int tagIndex = com.hypixel.hytale.assetstore.AssetRegistry.getTagIndex(tag);
                    if (tagIndex != com.hypixel.hytale.assetstore.AssetRegistry.TAG_NOT_FOUND) {
                        it.unimi.dsi.fastutil.ints.IntSet taggedBlocks = blockTypeAssetMap.getIndexesForTag(tagIndex);
                        if (!taggedBlocks.isEmpty()) {
                            targetBlockIds.addAll(taggedBlocks);
                            searchDescription = "tag: " + tag;
                            logger.debug("Found tag match: " + tag + " with " + taggedBlocks.size() + " blocks");
                        }
                    }

                    // Strategy 3: Case-insensitive block ID search (partial match)
                    if (targetBlockIds.isEmpty()) {
                        String searchLower = tag.toLowerCase();
                        int matchCount = 0;
                        for (String blockKey : blockTypeAssetMap.getAssetMap().keySet()) {
                            if (blockKey.toLowerCase().contains(searchLower)) {
                                int blockId = blockTypeAssetMap.getIndex(blockKey);
                                if (blockId != Integer.MIN_VALUE) {
                                    targetBlockIds.add(blockId);
                                    matchCount++;
                                }
                            }
                        }
                        if (matchCount > 0) {
                            searchDescription = "partial match for: " + tag + " (" + matchCount + " blocks)";
                            logger.debug("Found partial block ID matches for: " + tag + " (" + matchCount + " blocks)");
                        } else {
                            searchDescription = null;
                        }
                    } else {
                        searchDescription = "tag: " + tag;
                    }
                }

                if (targetBlockIds.isEmpty()) {
                    logger.debug("No blocks found for search: " + tag);
                    future.complete(Optional.empty());
                    return;
                }

                logger.debug("Searching for blocks with " + searchDescription + " in radius " + radius);

                // Spiral Search
                com.hypixel.hytale.math.iterator.SpiralIterator iterator = new com.hypixel.hytale.math.iterator.SpiralIterator(
                        startX, startZ, radius);

                while (iterator.hasNext()) {
                    long packedPos = iterator.next();
                    int x = com.hypixel.hytale.math.util.MathUtil.unpackLeft(packedPos);
                    int z = com.hypixel.hytale.math.util.MathUtil.unpackRight(packedPos);

                    // Optimization: Check if chunk is loaded first
                    com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk = world
                            .getChunkIfLoaded(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x, z));
                    if (chunk == null)
                        continue;

                    // Scan Y range (NPC height +/- 10 blocks)
                    for (int y = Math.max(0, startY - 10); y <= Math.min(255, startY + 10); y++) {
                        int blockId = chunk.getBlock(x, y, z);
                        if (targetBlockIds.contains(blockId)) {
                            // Get the actual block type for the result
                            var blockType = blockTypeAssetMap.getAsset(blockId);
                            String foundTypeName = blockType != null ? blockType.getId() : tag;

                            // Ignore blocks with "Leaves_" in their ID
                            if (foundTypeName != null && foundTypeName.contains("Leaves_")) {
                                continue;
                            }

                            // Found it!
                            Map<String, Object> result = new HashMap<>();
                            result.put("found", true);
                            result.put("type", foundTypeName);
                            result.put("coordinates", Map.of("x", x, "y", y, "z", z));
                            result.put("distance", Math
                                    .sqrt(Math.pow(x - startX, 2) + Math.pow(y - startY, 2) + Math.pow(z - startZ, 2)));

                            logger.debug("Found block " + foundTypeName + " at (" + x + ", " + y + ", " + z + ")");
                            future.complete(Optional.of(result));
                            return;
                        }
                    }
                }

                logger.debug("No blocks found within search radius " + radius + " for: " + tag);
                future.complete(Optional.empty());

            } catch (Exception e) {
                logger.error("Error in findBlock: " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Optional.empty());
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<Optional<Map<String, Object>>> scanBlocks(UUID npcInstanceId, int radius) {
        CompletableFuture<Optional<Map<String, Object>>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.empty());
            return future;
        }

        // Get the correct world for this NPC
        String worldName = npcData.spawnLocation() != null ? npcData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] scanBlocks: Could not find world '" + worldName + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            future.complete(Optional.empty());
            return future;
        }
        final World world = resolvedWorld;

        world.execute(() -> {
            try {
                // Get NPC current position
                Ref<EntityStore> ref = npcData.entityRef();
                if (!ref.isValid()) {
                    future.complete(Optional.empty());
                    return;
                }
                Store<EntityStore> store = ref.getStore();
                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform == null) {
                    future.complete(Optional.empty());
                    return;
                }
                Vector3d pos = transform.getPosition();
                int startX = (int) Math.floor(pos.getX());
                int startY = (int) Math.floor(pos.getY());
                int startZ = (int) Math.floor(pos.getZ());

                var blockTypeAssetMap = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType
                        .getAssetMap();
                if (blockTypeAssetMap == null) {
                    logger.debug("BlockType asset map is null");
                    future.complete(Optional.empty());
                    return;
                }

                // Data structures to track found blocks
                // Map<blockId, BlockScanInfo>
                Map<String, BlockScanInfo> foundBlocks = new HashMap<>();
                Map<String, Set<String>> categoryToBlockIds = new HashMap<>();

                // Spiral Search
                com.hypixel.hytale.math.iterator.SpiralIterator iterator = new com.hypixel.hytale.math.iterator.SpiralIterator(
                        startX, startZ, radius);

                while (iterator.hasNext()) {
                    long packedPos = iterator.next();
                    int x = com.hypixel.hytale.math.util.MathUtil.unpackLeft(packedPos);
                    int z = com.hypixel.hytale.math.util.MathUtil.unpackRight(packedPos);

                    // Optimization: Check if chunk is loaded first
                    com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk = world
                            .getChunkIfLoaded(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x, z));
                    if (chunk == null)
                        continue;

                    // Scan Y range (NPC height +/- 10 blocks)
                    for (int y = Math.max(0, startY - 10); y <= Math.min(255, startY + 10); y++) {
                        int blockIdInt = chunk.getBlock(x, y, z);
                        var blockType = blockTypeAssetMap.getAsset(blockIdInt);
                        if (blockType == null) continue;
                        
                        String blockId = blockType.getId();
                        if (blockId == null || blockId.isEmpty()) continue;

                        // Calculate distance
                        double distance = Math.sqrt(
                            Math.pow(x - startX, 2) + 
                            Math.pow(y - startY, 2) + 
                            Math.pow(z - startZ, 2)
                        );

                        // Update or create block scan info
                        BlockScanInfo info = foundBlocks.computeIfAbsent(blockId, id -> {
                            String displayName = formatBlockIdAsDisplayName(id);
                            List<String> materialTypes = dev.hycompanion.plugin.core.world.BlockClassifier
                                    .getMaterialTypes(id, displayName);
                            return new BlockScanInfo(id, displayName, materialTypes);
                        });

                        // Update count
                        info.count++;

                        // Update nearest if this is closer
                        if (distance < info.nearestDistance) {
                            info.nearestDistance = distance;
                            info.nearestX = x;
                            info.nearestY = y;
                            info.nearestZ = z;
                        }

                        // Add to category mapping (normalize "misc" to "other")
                        for (String materialType : info.materialTypes) {
                            String normalizedCategory = "misc".equals(materialType) ? "other" : materialType;
                            categoryToBlockIds.computeIfAbsent(normalizedCategory, k -> new HashSet<>()).add(blockId);
                        }
                    }
                }

                // Build response
                Map<String, Object> result = new HashMap<>();
                result.put("current_position", Map.of("x", startX, "y", startY, "z", startZ));
                result.put("radius", radius);
                
                // Build categories map
                Map<String, Map<String, Object>> categories = new HashMap<>();
                for (Map.Entry<String, Set<String>> entry : categoryToBlockIds.entrySet()) {
                    String category = entry.getKey();
                    Set<String> blockIds = entry.getValue();
                    
                    Map<String, Object> categoryInfo = new HashMap<>();
                    categoryInfo.put("blockIds", new ArrayList<>(blockIds));
                    categoryInfo.put("count", blockIds.size());
                    categories.put(category, categoryInfo);
                }
                result.put("categories", categories);

                // Build blocks map (grouped by blockId with nearest coordinates)
                Map<String, Map<String, Object>> blocks = new HashMap<>();
                for (BlockScanInfo info : foundBlocks.values()) {
                    Map<String, Object> blockInfo = new HashMap<>();
                    blockInfo.put("displayName", info.displayName);
                    // Normalize "misc" to "other" for better LLM comprehension
                    String primaryCategory = info.materialTypes.isEmpty() ? "other" : info.materialTypes.get(0);
                    if ("misc".equals(primaryCategory)) {
                        primaryCategory = "other";
                    }
                    blockInfo.put("category", primaryCategory);
                    // Also normalize "misc" to "other" in the categories list
                    List<String> normalizedCategories = info.materialTypes.stream()
                        .map(cat -> "misc".equals(cat) ? "other" : cat)
                        .toList();
                    blockInfo.put("categories", normalizedCategories);
                    blockInfo.put("count", info.count);
                    blockInfo.put("nearest", Map.of(
                        "coordinates", Map.of("x", info.nearestX, "y", info.nearestY, "z", info.nearestZ),
                        "distance", Math.round(info.nearestDistance * 100.0) / 100.0
                    ));
                    blocks.put(info.blockId, blockInfo);
                }
                result.put("blocks", blocks);
                result.put("totalUniqueBlocks", foundBlocks.size());

                logger.debug("Scan complete: found " + foundBlocks.size() + " unique block types in radius " + radius);
                future.complete(Optional.of(result));

            } catch (Exception e) {
                logger.error("Error in scanBlocks: " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Optional.empty());
            }
        });

        return future;
    }

    /**
     * Helper class to track scan information for a block type
     */
    private static class BlockScanInfo {
        final String blockId;
        final String displayName;
        final List<String> materialTypes;
        int count = 0;
        double nearestDistance = Double.MAX_VALUE;
        int nearestX, nearestY, nearestZ;

        BlockScanInfo(String blockId, String displayName, List<String> materialTypes) {
            this.blockId = blockId;
            this.displayName = displayName;
            this.materialTypes = materialTypes;
        }
    }

    @Override
    public CompletableFuture<Optional<Map<String, Object>>> findEntity(UUID npcInstanceId, String name,
            int radius) {
        CompletableFuture<Optional<Map<String, Object>>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.empty());
            return future;
        }

        // Get the correct world for this NPC, not the default world
        String worldName = npcData.spawnLocation() != null ? npcData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] findEntity: Could not find world '" + worldName + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            future.complete(Optional.empty());
            return future;
        }
        final World world = resolvedWorld;

        world.execute(() -> {
            try {
                Ref<EntityStore> myselfRef = npcData.entityRef();
                Store<EntityStore> store = myselfRef.getStore();
                TransformComponent myTransform = store.getComponent(myselfRef, TransformComponent.getComponentType());

                if (myTransform == null) {
                    future.complete(Optional.empty());
                    return;
                }

                Vector3d myPos = myTransform.getPosition();
                EntityStore entityStore = store.getExternalData();

                // Use reflection to access entitiesByUuid map
                Field entitiesMapField = EntityStore.class.getDeclaredField("entitiesByUuid");
                entitiesMapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UUID, Ref<EntityStore>> allEntities = (Map<UUID, Ref<EntityStore>>) entitiesMapField
                        .get(entityStore);

                double minDistance = Double.MAX_VALUE;
                Ref<EntityStore> nearestRef = null;
                String bestName = "";

                // Iterate all entities
                for (Ref<EntityStore> ref : allEntities.values()) {
                    if (ref.equals(myselfRef) || !ref.isValid())
                        continue;

                    // if(npcInstanceId.equals(ref.getStore().getExternalData().getRefFromUUID()))
                    // continue;

                    TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                    if (t == null)
                        continue;

                    double dist = t.getPosition().distanceTo(myPos);
                    if (dist > radius)
                        continue;

                    String entityName = null;
                    boolean typeMatch = false;

                    // Check for player
                    com.hypixel.hytale.server.core.universe.PlayerRef pRef = store.getComponent(ref,
                            com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (pRef != null) {
                        typeMatch = true;
                        entityName = pRef.getUsername();
                    } else {
                        // Check for npc
                        NPCEntity npcComp = store.getComponent(ref, NPCEntity.getComponentType());
                        if (npcComp != null) {
                            typeMatch = true;
                            Nameplate np = store.getComponent(ref, Nameplate.getComponentType());
                            if (np != null)
                                entityName = np.getText();
                            else {
                                // Use UUIDComponent if available for ID
                                com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = store.getComponent(ref,
                                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                                if (uuidComp != null)
                                    entityName = npcComp.getRole() != null
                                            ? npcComp.getRole().getClass().getSimpleName()
                                            : "NPC-" + uuidComp.getUuid().toString().substring(0, 5);
                                else
                                    entityName = "NPC";
                            }
                        } else {
                            // Check for item
                            com.hypixel.hytale.server.core.modules.entity.item.ItemComponent ic = store.getComponent(
                                    ref,
                                    com.hypixel.hytale.server.core.modules.entity.item.ItemComponent
                                            .getComponentType());
                            if (ic != null) {
                                typeMatch = true;
                                entityName = ic.getItemStack().getItem().getId(); // Use ID as name (e.g. "stone_sword")
                            }
                        }
                    }

                    if (!typeMatch)
                        continue;

                    if (name != null && !name.isEmpty()) {
                        if (entityName == null || !entityName.toLowerCase().contains(name.toLowerCase())) {
                            continue;
                        }
                    }

                    if (dist < minDistance) {
                        minDistance = dist;
                        nearestRef = ref;
                        bestName = entityName != null ? entityName : "Unknown";
                    }
                }

                if (nearestRef != null) {
                    TransformComponent t = store.getComponent(nearestRef, TransformComponent.getComponentType());
                    Vector3d pos = t.getPosition();

                    Map<String, Object> result = new HashMap<>();
                    result.put("found", true);
                    result.put("name", bestName);
                    result.put("coordinates", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
                    result.put("distance", minDistance);

                    future.complete(Optional.of(result));
                } else {
                    future.complete(Optional.empty());
                }

            } catch (Exception e) {
                logger.error("Error in findEntity: " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Optional.empty());
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<Optional<Map<String, Object>>> scanEntities(UUID npcInstanceId, int radius) {
        CompletableFuture<Optional<Map<String, Object>>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.empty());
            return future;
        }

        // Get the correct world for this NPC
        String worldName = npcData.spawnLocation() != null ? npcData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] scanEntities: Could not find world '" + worldName + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            future.complete(Optional.empty());
            return future;
        }
        final World world = resolvedWorld;

        world.execute(() -> {
            try {
                Ref<EntityStore> myselfRef = npcData.entityRef();
                Store<EntityStore> store = myselfRef.getStore();
                TransformComponent myTransform = store.getComponent(myselfRef, TransformComponent.getComponentType());

                if (myTransform == null) {
                    future.complete(Optional.empty());
                    return;
                }

                Vector3d myPos = myTransform.getPosition();
                int startX = (int) Math.floor(myPos.getX());
                int startY = (int) Math.floor(myPos.getY());
                int startZ = (int) Math.floor(myPos.getZ());
                
                EntityStore entityStore = store.getExternalData();

                // Use reflection to access entitiesByUuid map
                Field entitiesMapField = EntityStore.class.getDeclaredField("entitiesByUuid");
                entitiesMapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UUID, Ref<EntityStore>> allEntities = (Map<UUID, Ref<EntityStore>>) entitiesMapField
                        .get(entityStore);

                List<Map<String, Object>> entities = new ArrayList<>();

                // Iterate all entities
                for (Ref<EntityStore> ref : allEntities.values()) {
                    if (ref.equals(myselfRef) || !ref.isValid())
                        continue;

                    TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                    if (t == null)
                        continue;

                    double dist = t.getPosition().distanceTo(myPos);
                    if (dist > radius)
                        continue;

                    // Skip projectiles (holograms, thinking indicators, etc.)
                    ProjectileComponent projectileComp = store.getComponent(ref, ProjectileComponent.getComponentType());
                    if (projectileComp != null)
                        continue;

                    // Extract entity information
                    String entityName = null;
                    String entityType = "unknown";
                    String appearance = null;
                    UUID entityUuid = null;
                    Double health = null;
                    Double maxHealth = null;
                    
                    // Check for player
                    com.hypixel.hytale.server.core.universe.PlayerRef pRef = store.getComponent(ref,
                            com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (pRef != null) {
                        entityType = "player";
                        entityName = pRef.getUsername();
                        entityUuid = pRef.getUuid();
                        appearance = "Player";
                    } else {
                        // Check for NPC
                        NPCEntity npcComp = store.getComponent(ref, NPCEntity.getComponentType());
                        if (npcComp != null) {
                            entityType = "npc";
                            entityUuid = npcComp.getUuid();
                            
                            Nameplate np = store.getComponent(ref, Nameplate.getComponentType());
                            if (np != null && np.getText() != null && !np.getText().isEmpty()) {
                                entityName = np.getText();
                            } else if (npcComp.getRole() != null && npcComp.getRoleName() != null) {
                                // Use role name if available
                                entityName = npcComp.getRoleName();
                            } else {
                                entityName = "NPC";
                            }
                            
                            appearance = npcComp.getRole() != null && npcComp.getRoleName() != null
                                    ? npcComp.getRoleName()
                                    : "NPC";
                        } else {
                            // Check for dropped item
                            com.hypixel.hytale.server.core.modules.entity.item.ItemComponent ic = store.getComponent(
                                    ref,
                                    com.hypixel.hytale.server.core.modules.entity.item.ItemComponent
                                            .getComponentType());
                            if (ic != null) {
                                entityType = "item";
                                entityName = ic.getItemStack().getItem().getId();
                                appearance = "Dropped Item: " + entityName;
                                
                                com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = 
                                    store.getComponent(ref, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                                if (uuidComp != null) {
                                    entityUuid = uuidComp.getUuid();
                                }
                            }
                            // Note: We're skipping creatures/mobs for now since they don't have a reliable
                            // way to be distinguished from projectiles without AIComponent
                        }
                    }

                    // Skip if we couldn't identify the entity
                    if (entityName == null)
                        continue;

                    // Get health stats for players and NPCs
                    if ("player".equals(entityType) || "npc".equals(entityType)) {
                        try {
                            com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap statMap = 
                                store.getComponent(ref, com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap.getComponentType());
                            if (statMap != null) {
                                int healthIndex = com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth();
                                if (healthIndex != Integer.MIN_VALUE) {
                                    com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue healthValue = statMap.get(healthIndex);
                                    if (healthValue != null) {
                                        health = (double) healthValue.get();
                                        maxHealth = (double) healthValue.getMax();
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Health stats not available, skip
                        }
                    }

                    // Build entity info
                    Map<String, Object> entityInfo = new HashMap<>();
                    entityInfo.put("name", entityName);
                    entityInfo.put("type", entityType);
                    if (appearance != null) {
                        entityInfo.put("appearance", appearance);
                    }
                    if (entityUuid != null) {
                        entityInfo.put("uuid", entityUuid.toString());
                    }
                    
                    Vector3d pos = t.getPosition();
                    entityInfo.put("coordinates", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
                    entityInfo.put("distance", Math.round(dist * 100.0) / 100.0);
                    
                    if (health != null) {
                        entityInfo.put("health", health);
                    }
                    if (maxHealth != null) {
                        entityInfo.put("maxHealth", maxHealth);
                    }

                    entities.add(entityInfo);
                }

                // Build response
                Map<String, Object> result = new HashMap<>();
                result.put("current_position", Map.of("x", startX, "y", startY, "z", startZ));
                result.put("radius", radius);
                result.put("entities", entities);
                result.put("totalEntities", entities.size());

                logger.debug("Scan entities complete: found " + entities.size() + " entities in radius " + radius);
                future.complete(Optional.of(result));

            } catch (Exception e) {
                logger.error("Error in scanEntities: " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Optional.empty());
            }
        });

        return future;
    }

    @Override
    public Optional<Location> getNpcInstanceLocation(UUID npcInstanceId) {
        // Fail fast if thread interrupted
        if (Thread.currentThread().isInterrupted()) {
            return Optional.empty();
        }

        // Circuit breaker: If we had > 10 consecutive timeouts, skip real-time checks
        // This prevents blocking during server shutdown/unresponsive states
        if (consecutiveTimeouts.get() > 10) {
            // Only log once per 'trip'
            if (consecutiveTimeouts.get() == 11) {
                logger.debug("Circuit breaker tripped: Too many consecutive timeouts accessing World.");
                consecutiveTimeouts.incrementAndGet();
            }
            return Optional.empty();
        }

        // First try to get real-time location from entity
        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);

        if (npcInstanceData == null) {
            return Optional.empty();
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();

        // Location extraction logic - MUST run on WorldThread due to Hytale ECS
        // thread-safety
        Supplier<Optional<Location>> locationExtractor = () -> {
            if (entityRef != null && entityRef.isValid()) {
                try {
                    Store<EntityStore> store = entityRef.getStore();
                    TransformComponent transform = store.getComponent(entityRef,
                            TransformComponent.getComponentType());
                    if (transform != null) {
                        Vector3d pos = transform.getPosition();
                        World entityWorld = store.getExternalData().getWorld();
                        String worldName = entityWorld != null ? entityWorld.getName() : "world";
                        return Optional.of(new Location(pos.getX(), pos.getY(), pos.getZ(), worldName));
                    }
                } catch (Exception e) {
                    logger.debug("Could not get NPC transform: " + e.getMessage());
                }
            }
            return Optional.empty();
        };

        // Check if we are already on the correct WorldThread for this NPC
        String expectedWorldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
        String currentThreadName = Thread.currentThread().getName();
        if (currentThreadName.contains("WorldThread") && expectedWorldName != null && 
            currentThreadName.contains(expectedWorldName)) {
            // We're already on the correct world thread, can execute directly
            Optional<Location> result = locationExtractor.get();
            if (result.isPresent()) {
                consecutiveTimeouts.set(0);
            }
            return result;
        }

        // Schedule on WorldThread and wait for result
        try {
            // Get the world from the NPC's spawn location, not the default world
            // The NPC might be in a different world (e.g., "Arcana" vs "Lobby")
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World world = worldName != null ? Universe.get().getWorld(worldName) : null;
            
            // Fallback to default world if specific world not found
            if (world == null) {
                world = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] getNpcInstanceLocation: Could not find world '" + worldName + "' for NPC, falling back to default world");
            }
            
            if (world == null) {
                return Optional.empty();
            }

            CompletableFuture<Optional<Location>> future = new CompletableFuture<>();
            try {
                world.execute(() -> future.complete(locationExtractor.get()));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // World executor is shut down or full
                logger.debug("World executor rejected location check task (Server shutting down?)");
                return Optional.empty();
            }

            // Wait up to 100ms for the result (was 25ms - too short for busy World threads)
            Optional<Location> result = future.get(100, TimeUnit.MILLISECONDS);
            if (result.isPresent()) {
                // Reset circuit breaker on success
                consecutiveTimeouts.set(0);
            }
            return result;
        } catch (InterruptedException e) {
            // Preserve interrupt status
            Thread.currentThread().interrupt();
            Sentry.captureException(e);
            return Optional.empty();
        } catch (java.util.concurrent.TimeoutException e) {
            // Handle timeout specifically for circuit breaker
            int timeouts = consecutiveTimeouts.incrementAndGet();
            if (timeouts <= 10) { // Reduce log spam
                logger.debug("Timeout getting NPC location (" + timeouts + ")");
            }
            Sentry.captureException(e);
        } catch (Exception e) {
            // Other error
            logger.debug("Could not get real-time location on world thread (error): " + e.getMessage());
            Sentry.captureException(e);
        }

        return Optional.empty();
    }

    @Override
    public void rotateNpcInstanceToward(UUID npcInstanceId, Location targetLocation) {
        if (targetLocation == null) {
            return;
        }

        // [Shutdown Fix] Check shutdown early to avoid unnecessary work
        if (isShuttingDown()) {
            return;
        }

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.debug("[Hycompanion] Cannot rotate NPC - entity not tracked: " + npcInstanceId);
            return;
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.debug("[Hycompanion] Cannot rotate NPC - entity not tracked: " + npcInstanceId);
            return;
        }
        if (!entityRef.isValid()) {
            logger.debug("[Hycompanion] Cannot rotate NPC - entity reference stale: " + npcInstanceId);
            npcInstanceEntities.remove(npcInstanceId);
            return;
        }

        // Cancel any existing rotation task
        ScheduledFuture<?> existing = rotationTasks.remove(npcInstanceId);
        if (existing != null) {
            existing.cancel(false);
        }

        // Get the correct world for this NPC, not the default world
        String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] rotateNpcInstanceToward: Could not find world '" + worldName + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            return;
        }
        final World world = resolvedWorld;

        // Calculate target yaw once at start
        final float[] targetYaw = new float[1];

        try {
            CompletableFuture<Boolean> calcFuture = new CompletableFuture<>();
            world.execute(() -> {
                try {
                    if (!entityRef.isValid()) {
                        calcFuture.complete(false);
                        return;
                    }

                    Store<EntityStore> store = entityRef.getStore();
                    TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
                    if (transform == null) {
                        calcFuture.complete(false);
                        return;
                    }

                    Vector3d npcPos = transform.getPosition();
                    double dx = targetLocation.x() - npcPos.getX();
                    double dz = targetLocation.z() - npcPos.getZ();

                    if ((dx * dx) + (dz * dz) < 0.0001d) {
                        calcFuture.complete(false);
                        return;
                    }

                    targetYaw[0] = TrigMathUtil.atan2(-dx, -dz);
                    calcFuture.complete(true);
                } catch (Exception e) {
                    Sentry.captureException(e);
                    calcFuture.complete(false);
                }
            });

            if (!calcFuture.get(100, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] Failed to calculate target rotation: " + e.getMessage());
            Sentry.captureException(e);
            return;
        }

        // Schedule smooth body rotation
        AtomicInteger ticksRemaining = new AtomicInteger(15);

        ScheduledFuture<?> rotationTask = rotationScheduler.scheduleAtFixedRate(() -> {
            if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
                rotationTasks.remove(npcInstanceId);
                return;
            }

            if (ticksRemaining.decrementAndGet() < 0) {
                rotationTasks.remove(npcInstanceId);
                return;
            }

            try {
                world.execute(() -> {
                    try {
                        if (!entityRef.isValid()) {
                            rotationTasks.remove(npcInstanceId);
                            return;
                        }

                        Store<EntityStore> store = entityRef.getStore();
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity == null) {
                            rotationTasks.remove(npcInstanceId);
                            return;
                        }

                        // Check busy states
                        var role = npcEntity.getRole();
                        if (role != null) {
                            var stateSupport = role.getStateSupport();
                            if (stateSupport != null && stateSupport.isInBusyState()) {
                                rotationTasks.remove(npcInstanceId);
                                return;
                            }

                            var markedSupport = role.getMarkedEntitySupport();
                            if (markedSupport != null) {
                                var lockedTarget = markedSupport.getMarkedEntityRef("LockedTarget");
                                if (lockedTarget != null && lockedTarget.isValid()) {
                                    rotationTasks.remove(npcInstanceId);
                                    return;
                                }
                            }
                        }

                        TransformComponent transform = store.getComponent(entityRef,
                                TransformComponent.getComponentType());
                        if (transform == null) {
                            rotationTasks.remove(npcInstanceId);
                            return;
                        }

                        // Get current body rotation
                        Vector3f bodyRotation = transform.getRotation();
                        float currentYaw = bodyRotation.getYaw();

                        // Calculate shortest angle difference
                        float diff = targetYaw[0] - currentYaw;
                        while (diff > Math.PI)
                            diff -= 2 * Math.PI;
                        while (diff < -Math.PI)
                            diff += 2 * Math.PI;

                        // Lerp toward target (15% per tick for smoother motion)
                        float newYaw = currentYaw + diff * 0.15f;

                        bodyRotation.setYaw(newYaw);
                        bodyRotation.setPitch(0.0f);
                        bodyRotation.setRoll(0.0f);
                        transform.setRotation(bodyRotation);

                        // Update leash heading so AI knows where we're facing
                        npcEntity.setLeashHeading(newYaw);
                        npcEntity.setLeashPitch(0.0f);

                        // Stop if aligned (within ~9 degrees)
                        if (Math.abs(diff) < 0.20f) {
                            rotationTasks.remove(npcInstanceId);
                        }
                    } catch (Exception e) {
                        logger.debug("[Hycompanion] Rotation tick error: " + e.getMessage());
                        Sentry.captureException(e);
                        rotationTasks.remove(npcInstanceId);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                Sentry.captureException(e);
                rotationTasks.remove(npcInstanceId);
            } catch (Exception e) {
                Sentry.captureException(e);
                rotationTasks.remove(npcInstanceId);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        rotationTasks.put(npcInstanceId, rotationTask);
    }

    @Override
    public boolean isNpcInstanceEntityValid(UUID npcInstanceId) {

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();

        // No entity reference tracked
        if (entityRef == null) {
            logger.info("[isNpcInstanceEntityValid] NPC " + npcInstanceId
                    + ": No entity reference tracked (NPC may not be spawned or was never registered)");
            return false;
        }

        // Check if reference is still valid (entity not removed by /npc clean etc.)
        if (!entityRef.isValid()) {
            // Clean up stale reference
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            logger.info("[isNpcInstanceEntityValid] NPC " + npcInstanceId
                    + ": Entity reference became invalid (entity was likely removed by /npc clean or despawned)");
            return false;
        }

        // Verify NPC component still exists
        // We need to check on the correct world thread to avoid thread assertion errors
        String expectedWorldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
        String currentThreadName = Thread.currentThread().getName();
        boolean onCorrectWorldThread = currentThreadName.contains("WorldThread") && expectedWorldName != null && 
            currentThreadName.contains(expectedWorldName);
        
        if (onCorrectWorldThread) {
            // Already on correct world thread, can check directly
            try {
                Store<EntityStore> store = entityRef.getStore();
                if (store == null) {
                    logger.info("[isNpcInstanceEntityValid] NPC " + npcInstanceId + ": Could not get entity store");
                    return false;
                }
                NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                if (npcEntity == null) {
                    logger.info("[isNpcInstanceEntityValid] NPC " + npcInstanceId + ": NPC component not found on entity");
                    return false;
                }
                return true;
            } catch (Exception e) {
                logger.info("[isNpcInstanceEntityValid] NPC " + npcInstanceId
                        + ": Exception checking entity - " + e.getMessage());
                Sentry.captureException(e);
                return false;
            }
        } else {
            // Not on correct world thread, just assume valid if entityRef is valid
            // The actual validation will happen when the NPC is accessed on the correct thread
            logger.debug("[isNpcInstanceEntityValid] NPC " + npcInstanceId + ": Not on correct world thread (" + expectedWorldName + "), skipping component check");
            return true;
        }
    }

    @Override
    public List<UUID> discoverExistingNpcInstances(String externalId) {
        logger.info("[discoverExistingNpcInstances] Searching for existing NPC entities for role: " + externalId);

        NpcData npcData = plugin.getNpcManager().getNpc(externalId).orElse(null);
        if (npcData == null) {
            logger.info("[discoverExistingNpcInstances] NPC Data not found for: " + externalId);
            return Collections.emptyList();
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(externalId);

        if (roleIndex < 0) {
            logger.info("[discoverExistingNpcInstances] Role not found in NPC definitions: " + externalId);
            return Collections.emptyList();
        }

        logger.info("[discoverExistingNpcInstances] Role index for " + externalId + ": " + roleIndex);

        final int targetRoleIndex = roleIndex;
        final List<UUID> discoveredUuids = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger pendingWorlds = new AtomicInteger(Universe.get().getWorlds().size());

        for (World world : Universe.get().getWorlds().values()) {
            try {
                world.execute(() -> {
                    try {
                        Store<EntityStore> store = world.getEntityStore().getStore();
                        store.forEachChunk(NPCEntity.getComponentType(),
                                (BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>>) (chunk,
                                        commandBuffer) -> {
                                    for (int i = 0; i < chunk.size(); i++) {
                                        try {
                                            NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                                            if (npc != null && npc.getRoleIndex() == targetRoleIndex) {
                                                Ref<EntityStore> entityRef = chunk.getReferenceTo(i);
                                                UUID entityUuid = npc.getUuid();

                                                if (entityUuid == null) {
                                                    continue;
                                                }

                                                // Always update tracking to ensure fresh Entity References
                                                // (References become invalid after entity reload/respawn)
                                                boolean isUpdate = npcInstanceEntities.containsKey(entityUuid);

                                                // Get or create spawn location - use externalId as key
                                                // IMPORTANT: Only set spawn location if not already set, to preserve
                                                // the original spawn point. The leash point may change as the NPC moves
                                                // or could be 0,0,0 if the NPC is dead/dying.
                                                Vector3d leashPoint = npc.getLeashPoint();
                                                Location spawnLocation = npcSpawnLocations.get(externalId);
                                                if (spawnLocation == null) {
                                                    // Only use leash point if it's valid (not at origin)
                                                    if (leashPoint.getX() == 0 && leashPoint.getY() == 0 
                                                            && leashPoint.getZ() == 0) {
                                                        // Use current position as fallback
                                                        TransformComponent currentTransform = store.getComponent(entityRef,
                                                                TransformComponent.getComponentType());
                                                        if (currentTransform != null) {
                                                            Vector3d pos = currentTransform.getPosition();
                                                            spawnLocation = Location.of(pos.getX(), pos.getY(), pos.getZ(),
                                                                    world.getName() != null ? world.getName() : "world");
                                                        } else {
                                                            spawnLocation = Location.of(0, 0, 0,
                                                                    world.getName() != null ? world.getName() : "world");
                                                        }
                                                    } else {
                                                        spawnLocation = Location.of(
                                                                leashPoint.getX(), leashPoint.getY(), leashPoint.getZ(),
                                                                world.getName() != null ? world.getName() : "world");
                                                    }
                                                    npcSpawnLocations.put(externalId, spawnLocation);
                                                }

                                                // Create Instance
                                                NpcInstanceData instance = new NpcInstanceData(entityUuid,
                                                        entityRef,
                                                        npc, npcData, spawnLocation);

                                                // Apply capabilities (invulnerability, knockback) to existing NPCs
                                                // This is critical for server restart - existing NPCs need their capabilities restored
                                                applyNpcCapabilities(store, entityRef, npc, npcData, "Discovery");

                                                // Get Location
                                                TransformComponent transform = store.getComponent(entityRef,
                                                        TransformComponent.getComponentType());
                                                if (transform != null) {
                                                    Vector3d pos = transform.getPosition();

                                                    // Ensure leash point is set (use position if at origin)
                                                    if (leashPoint.getX() == 0 && leashPoint.getY() == 0
                                                            && leashPoint.getZ() == 0) {
                                                        npc.setLeashPoint(pos);
                                                        Vector3f rot = transform.getRotation();
                                                        npc.setLeashHeading(rot.getYaw());
                                                        npc.setLeashPitch(rot.getPitch());
                                                    }
                                                }

                                                npcInstanceEntities.put(entityUuid, instance);

                                                if (isUpdate) {
                                                    logger.info(
                                                            "[discoverExistingNpcInstances] Updated binding (Refreshed Ref): "
                                                                    + externalId + " -> " + entityUuid);
                                                } else {
                                                    logger.info("[discoverExistingNpcInstances] Discovered & Bound: "
                                                            + externalId + " -> " + entityUuid);
                                                }

                                                // Always add to list
                                                discoveredUuids.add(entityUuid);
                                            }
                                        } catch (Exception e) {
                                            logger.debug("Error inspecting NPC entity: " + e.getMessage());
                                            Sentry.captureException(e);
                                        }
                                    }
                                });
                    } catch (Exception e) {
                        logger.error("[discoverExistingNpcInstances] Error in world execute: " + e.getMessage());
                        Sentry.captureException(e);
                    } finally {
                        pendingWorlds.decrementAndGet();
                    }
                });
            } catch (Exception e) {
                pendingWorlds.decrementAndGet();
                logger.error("[discoverExistingNpcInstances] Failed to schedule world execution: " + e.getMessage());
                Sentry.captureException(e);
            }
        }

        // Wait for completion (max 2 seconds)
        long start = System.currentTimeMillis();
        while (pendingWorlds.get() > 0 && System.currentTimeMillis() - start < 2000) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Sentry.captureException(e);
                break;
            }
        }

        logger.info(
                "[discoverExistingNpcInstances] Discovery complete. Found " + discoveredUuids.size() + " instances.");

        return new ArrayList<>(discoveredUuids);
    }

    // ========== Trade Operations ==========

    @Override
    public void openTradeInterface(UUID npcInstanceId, String playerId) {
        // Trade interface functionality is NOT currently exposed in Hytale's public
        // plugin API.
        // Trading appears to be handled through NPC interaction hints and internal game
        // systems.
        // Example hint: "interactionHints.trade" (seen in BuilderActionSetInteractable)

        logger.warn("[Hycompanion] openTradeInterface is NOT IMPLEMENTED - " +
                "Trade UI is not exposed in Hytale's plugin API. " +
                "Trade interactions are handled through NPC role configuration.");

        // Placeholder: Send a message to the player instead
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef != null) {
                Message msg = Message.raw("[Trade] The merchant opens their wares...")
                        .color("#FFD700");
                playerRef.sendMessage(msg);
            }
        } catch (Exception e) {
            Sentry.captureException(e);
        }

        throw new UnsupportedOperationException(
                "Trade interface not available in Hytale plugin API. " +
                        "Configure trades in NPC Role definitions instead.");
    }

    // ========== Quest Operations ==========

    @Override
    public void offerQuest(UUID npcInstanceId, String playerId, String questId, String questName) {
        // Quest system functionality is NOT currently exposed in Hytale's public plugin
        // API.
        // Hytale appears to have an adventure/objectives system (builtin.adventure
        // package)
        // but it's not designed for direct plugin manipulation.

        logger.warn("[Hycompanion] offerQuest is NOT IMPLEMENTED - " +
                "Quest system is not exposed in Hytale's plugin API. " +
                "Quests are defined through Adventure Mode assets.");

        // Placeholder: Send a quest notification message instead
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);

            if (playerRef != null) {
                Message questMessage = Message.raw("[Quest Available] " + questName)
                        .color("#FFD700");
                playerRef.sendMessage(questMessage);

                Message questDesc = Message.raw("Talk to the NPC to learn more about this quest.")
                        .color("#AAAAAA");
                playerRef.sendMessage(questDesc);
            }
        } catch (Exception e) {
            Sentry.captureException(e);
        }

        throw new UnsupportedOperationException(
                "Quest system not available in Hytale plugin API. " +
                        "Define quests in Adventure Mode assets instead.");
    }

    // ========== World Context ==========

    @Override
    public String getTimeOfDay() {
        // NOTE: This method returns the time of day for the default world.
        // For multi-world support, the API would need to be extended to accept a world parameter.
        // Different worlds may have different times (e.g., Lobby vs Arcana).
        World world = Universe.get().getDefaultWorld();
        if (world != null) {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                WorldTimeResource timeResource = store.getResource(WorldTimeResource.getResourceType());

                if (timeResource != null) {
                    int currentHour = timeResource.getCurrentHour();

                    // Map to time of day based on hour and sunlight
                    // Hytale uses 24-hour format with ~60% daytime, ~40% nighttime
                    if (currentHour >= 5 && currentHour < 7) {
                        return "dawn";
                    } else if (currentHour >= 7 && currentHour < 12) {
                        return "morning";
                    } else if (currentHour >= 12 && currentHour < 14) {
                        return "noon";
                    } else if (currentHour >= 14 && currentHour < 18) {
                        return "afternoon";
                    } else if (currentHour >= 18 && currentHour < 20) {
                        return "dusk";
                    } else {
                        return "night";
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not get world time: " + e.getMessage());
                Sentry.captureException(e);
            }
        }
        return "noon"; // Default
    }

    @Override
    public String getWeather() {
        // NOTE: This method returns the weather for the default world.
        // For multi-world support, the API would need to be extended to accept a world parameter.
        // Different worlds may have different weather (e.g., Lobby vs Arcana).
        World world = Universe.get().getDefaultWorld();
        if (world != null) {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                WeatherResource weatherResource = store.getResource(WeatherResource.getResourceType());

                if (weatherResource != null) {
                    int weatherIndex = weatherResource.getForcedWeatherIndex();

                    if (weatherIndex > 0) {
                        // Get weather name from asset map
                        Weather weather = Weather.getAssetMap().getAsset(weatherIndex);
                        if (weather != null) {
                            String weatherId = weather.getId();
                            // Return simplified weather name
                            if (weatherId != null) {
                                return weatherId.toLowerCase()
                                        .replace("weather_", "")
                                        .replace("_", " ");
                            }
                        }
                    }

                    // Default weather
                    return "clear";
                }
            } catch (Exception e) {
                logger.debug("Could not get weather: " + e.getMessage());
                Sentry.captureException(e);
            }
        }
        return "clear"; // Default
    }

    @Override
    public List<String> getNearbyPlayerNames(Location location, double radius) {
        // [Shutdown Fix] Check if we're shutting down to avoid accessing invalid player
        // refs
        if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return Collections.emptyList();
        }

        List<String> nearbyPlayers = new ArrayList<>();

        try {
            for (PlayerRef playerRef : Universe.get().getPlayers()) {
                // [Shutdown Fix] Check shutdown in the loop
                if (isShuttingDown()) {
                    break;
                }

                try {
                    Transform transform = playerRef.getTransform();
                    Vector3d playerPos = transform.getPosition();

                    double distance = Math.sqrt(
                            Math.pow(playerPos.getX() - location.x(), 2) +
                                    Math.pow(playerPos.getY() - location.y(), 2) +
                                    Math.pow(playerPos.getZ() - location.z(), 2));

                    if (distance <= radius) {
                        nearbyPlayers.add(playerRef.getUsername());
                    }
                } catch (Exception e) {
                    // During shutdown, player references may become invalid
                    logger.debug("Could not get player position: " + e.getMessage());
                    Sentry.captureException(e);
                }
            }
        } catch (Exception e) {
            // During shutdown, the player list may be inaccessible
            logger.debug("Could not iterate online players (shutdown in progress?): " + e.getMessage());
            Sentry.captureException(e);
        }

        return nearbyPlayers;
    }

    @Override
    public List<GamePlayer> getNearbyPlayers(Location location, double radius) {
        // [Shutdown Fix] Check if we're shutting down to avoid accessing invalid player
        // refs
        if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return Collections.emptyList();
        }

        List<GamePlayer> nearbyPlayers = new ArrayList<>();

        try {
            for (PlayerRef playerRef : Universe.get().getPlayers()) {
                // [Shutdown Fix] Check shutdown in the loop
                if (isShuttingDown()) {
                    break;
                }

                try {
                    Transform transform = playerRef.getTransform();
                    Vector3d playerPos = transform.getPosition();

                    double distance = Math.sqrt(
                            Math.pow(playerPos.getX() - location.x(), 2) +
                                    Math.pow(playerPos.getY() - location.y(), 2) +
                                    Math.pow(playerPos.getZ() - location.z(), 2));

                    if (distance <= radius) {
                        nearbyPlayers.add(convertToGamePlayer(playerRef));
                    }
                } catch (Exception e) {
                    // During shutdown, player references may become invalid
                    logger.debug("Could not get player position: " + e.getMessage());
                    Sentry.captureException(e);
                }
            }
        } catch (Exception e) {
            // During shutdown, the player list may be inaccessible
            logger.debug("Could not iterate online players (shutdown in progress?): " + e.getMessage());
            Sentry.captureException(e);
        }

        return nearbyPlayers;
    }

    @Override
    public String getWorldName() {
        // NOTE: This returns the default world's name as a global fallback.
        // For specific world names, use Location.world() or getWorldByName().
        World world = Universe.get().getDefaultWorld();
        return world != null ? world.getName() : "world";
    }

    // ========== Utility Methods ==========

    /**
     * Convert Hytale PlayerRef to GamePlayer
     */
    private GamePlayer convertToGamePlayer(PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        String name = playerRef.getUsername();

        // Get player location from transform
        Location location = null;
        try {
            Transform transform = playerRef.getTransform();
            Vector3d pos = transform.getPosition();
            UUID worldUuid = playerRef.getWorldUuid();
            String worldName = "world";

            if (worldUuid != null) {
                World world = Universe.get().getWorld(worldUuid);
                if (world != null) {
                    worldName = world.getName();
                }
            }

            location = new Location(pos.getX(), pos.getY(), pos.getZ(), worldName);
        } catch (Exception e) {
            logger.debug("Could not get player location: " + e.getMessage());
            Sentry.captureException(e);
            location = new Location(0, 0, 0, "world");
        }

        return new GamePlayer(uuid.toString(), name, uuid, location);
    }

    /**
     * Get Hytale World by name
     */
    private World getWorldByName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return Universe.get().getDefaultWorld();
        }
        return Universe.get().getWorld(worldName);
    }

    // ========== AI Action Operations ==========

    @Override
    public boolean startFollowingPlayer(UUID npcInstanceId, String targetPlayerName) {
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] starting to follow player: " + targetPlayerName);

        // Debug: Log all tracked NPCs
        logger.debug("[Hycompanion] startFollowingPlayer - Tracked NPCs: " + npcInstanceEntities.keySet());

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot follow - NPC entity not tracked: " + npcInstanceId +
                    " (NPC needs to be spawned or discovered first. Tracked NPCs: " + npcInstanceEntities.keySet()
                    + ")");
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot follow - NPC entity not tracked: " + npcInstanceId +
                    " (NPC needs to be spawned or discovered first. Tracked NPCs: " + npcInstanceEntities.keySet()
                    + ")");
            return false;
        }
        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot follow - NPC entity reference is stale: " + npcInstanceId +
                    " (entity may have been removed by /npc clean or role reload). Cleaning up reference.");
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            return false;
        }

        // Find target player
        PlayerRef targetPlayer = Universe.get().getPlayerByUsername(targetPlayerName, NameMatching.EXACT_IGNORE_CASE);
        if (targetPlayer == null) {
            logger.warn("[Hycompanion] Cannot follow - Player not found: " + targetPlayerName);
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            
            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] startFollowingPlayer: Could not find world '" + worldName + "' for NPC, falling back to default world");
            }

            if (resolvedWorld != null) {
                final World world = resolvedWorld;
                world.execute(() -> {
                    try {
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            var role = npcEntity.getRole();
                            if (role != null) {
                                // Get the player's entity reference
                                Ref<EntityStore> playerEntityRef = targetPlayer.getReference();
                                if (playerEntityRef != null && playerEntityRef.isValid()) {
                                    // Cancel any playing Action animation (Sit, Sleep, etc.) by playing Idle
                                    // We use playAnimation with "Idle" instead of stopAnimation because some
                                    // looping animations need to be explicitly replaced
                                    try {
                                        // First try to stop the action slot
                                        AnimationUtils.stopAnimation(entityRef, AnimationSlot.Action, true, store);
                                        // Then play Idle to ensure visual reset
                                        npcEntity.playAnimation(entityRef, AnimationSlot.Action, "Idle", store);
                                        logger.info("[Hycompanion] Reset animation to Idle before following");
                                    } catch (Exception e) {
                                        logger.debug("[Hycompanion] Could not reset animation: " + e.getMessage());
                                    }

                                    // Also reset state machine to Idle
                                    try {
                                        role.getStateSupport().setState(entityRef, "Idle", "Default", store);
                                    } catch (Exception e) {
                                        logger.debug("[Hycompanion] Could not reset state to Idle: " + e.getMessage());
                                        Sentry.captureException(e);
                                    }

                                    // Set the player as the follow target using MarkedEntitySupport
                                    // Use "LockedTarget" as the default target slot since that's the standard
                                    role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", playerEntityRef);

                                    // Try to transition to a Follow state if it exists
                                    // Most NPCs have Idle state, Follow might need to be added via role config
                                    try {
                                        role.getStateSupport().setState(entityRef, "Follow", null, store);
                                        busyNpcs.add(npcInstanceId);

                                        // Debug: Verify the state was actually set
                                        String currentState = role.getStateSupport().getStateName();
                                        logger.info("[Hycompanion] NPC transitioned to Follow state. Current state: "
                                                + currentState);

                                        // Debug: Verify LockedTarget is set
                                        var markedRef = role.getMarkedEntitySupport()
                                                .getMarkedEntityRef("LockedTarget");
                                        logger.info("[Hycompanion] LockedTarget set: "
                                                + (markedRef != null && markedRef.isValid()));

                                        // Get display name by formatting the role name
                                        // Note: DisplayNameComponent.getDisplayName().getAnsiMessage() returns
                                        // the raw localization key (e.g., "npc.name.villager") which cannot be
                                        // resolved server-side. We format the role name directly instead.
                                        String npcName = formatRoleAsDisplayName(npcEntity.getRoleName());

                                        Message questMessage = Message
                                                .raw(npcName + " starts to follow you.")
                                                .color("#FFD700");
                                        targetPlayer.sendMessage(questMessage);

                                    } catch (Exception e) {
                                        // Follow state might not exist - that's OK, the marked entity will still work
                                        // for NPCs with Find motion configured
                                        logger.warn("[Hycompanion] Could not set Follow state: "
                                                + e.getMessage() + " (Role may not have Follow state defined)");
                                        Sentry.captureException(e);
                                    }
                                } else {
                                    logger.warn("[Hycompanion] Player entity reference not available");
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("[Hycompanion] Error in startFollowingPlayer: " + e.getMessage());
                        Sentry.captureException(e);
                    }
                });
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to start following player: " + e.getMessage());
            Sentry.captureException(e);
        }
        return false;
    }

    @Override
    public boolean stopFollowing(UUID npcInstanceId) {
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] stopping follow");

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot stop following - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot stop following - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot stop following - NPC entity reference is stale: " + npcInstanceId +
                    ". Cleaning up reference.");
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            
            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] stopFollowing: Could not find world '" + worldName + "' for NPC, falling back to default world");
            }

            if (resolvedWorld != null) {
                final World world = resolvedWorld;
                world.execute(() -> {
                    try {
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            var role = npcEntity.getRole();
                            if (role != null) {
                                // Get the current follow target before clearing (to notify them)
                                Ref<EntityStore> targetRef = role.getMarkedEntitySupport()
                                        .getMarkedEntityRef("LockedTarget");

                                // Clear the follow target
                                role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);

                                // Return to Idle state
                                try {
                                    role.getStateSupport().setState(entityRef, "Idle", "Default", store);
                                    busyNpcs.remove(npcInstanceId);
                                    logger.info("[Hycompanion] NPC returned to Idle state");

                                    // If the target was a player, notify them
                                    if (targetRef != null && targetRef.isValid()) {
                                        PlayerRef targetPlayerRef = store.getComponent(targetRef,
                                                PlayerRef.getComponentType());
                                        if (targetPlayerRef != null) {
                                            // Get NPC display name by formatting the role name
                                            String npcName = formatRoleAsDisplayName(npcEntity.getRoleName());

                                            Message stopMessage = Message
                                                    .raw(npcName + " stopped following you.")
                                                    .color("#FFD700");
                                            targetPlayerRef.sendMessage(stopMessage);
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.debug("[Hycompanion] Could not set Idle state: " + e.getMessage());
                                    Sentry.captureException(e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("[Hycompanion] Error in stopFollowing: " + e.getMessage());
                        Sentry.captureException(e);
                    }
                });
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to stop following: " + e.getMessage());
            Sentry.captureException(e);
        }
        return false;
    }

    /**
     * Clear all NPC follow targets that point to a specific player.
     * This is called when a player disconnects to prevent "Invalid entity
     * reference"
     * errors during server shutdown.
     */
    public void clearFollowTargetsForPlayer(String playerName) {
        if (playerName == null || npcInstanceEntities.isEmpty()) {
            return;
        }

        // Find the player entity reference
        PlayerRef targetPlayer = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);
        if (targetPlayer == null) {
            return;
        }

        Ref<EntityStore> playerEntityRef = targetPlayer.getReference();
        if (playerEntityRef == null) {
            return;
        }

        // Check each NPC to see if it's following this player
        for (var entry : npcInstanceEntities.entrySet()) {
            UUID npcInstanceId = entry.getKey();
            NpcInstanceData npcInstance = entry.getValue();
            try {
                Ref<EntityStore> entityRef = npcInstance.entityRef();
                if (entityRef == null || !entityRef.isValid()) {
                    continue;
                }

                Store<EntityStore> store = entityRef.getStore();
                NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                if (npcEntity == null) {
                    continue;
                }

                var role = npcEntity.getRole();
                if (role == null) {
                    continue;
                }

                // Check if this NPC has the player as its LockedTarget
                var markedSupport = role.getMarkedEntitySupport();
                if (markedSupport == null) {
                    continue;
                }

                Ref<EntityStore> currentTarget = markedSupport.getMarkedEntityRef("LockedTarget");
                if (currentTarget != null && currentTarget.equals(playerEntityRef)) {
                    // This NPC is following the disconnecting player - clear the target
                    logger.info("[Hycompanion] Clearing follow target for NPC " + npcInstanceId +
                            " (was following disconnecting player: " + playerName + ")");

                    // Use world execute to clear the target on the world thread
                    // Get the correct world for this NPC, not the default world
                    String worldName = npcInstance.spawnLocation() != null ? npcInstance.spawnLocation().world() : null;
                    World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
                    if (resolvedWorld == null) {
                        resolvedWorld = Universe.get().getDefaultWorld();
                        logger.warn("[Hycompanion] clearFollowTargetsForPlayer: Could not find world '" + worldName + "' for NPC, falling back to default world");
                    }
                    if (resolvedWorld != null) {
                        final World world = resolvedWorld;
                        safeWorldExecute(world, () -> {
                            try {
                                // Re-check validity inside world thread
                                if (!entityRef.isValid()) {
                                    return;
                                }

                                // Clear the follow target
                                markedSupport.setMarkedEntity("LockedTarget", null);

                                // Return to Idle state
                                var stateSupport = role.getStateSupport();
                                if (stateSupport != null) {
                                    stateSupport.setState(entityRef, "Idle", "Default", store);
                                }

                                busyNpcs.remove(npcInstanceId);
                                logger.debug("[Hycompanion] NPC " + npcInstanceId + " returned to Idle state");
                            } catch (Exception e) {
                                logger.debug("[Hycompanion] Error clearing follow target: " + e.getMessage());
                                Sentry.captureException(e);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                logger.debug("[Hycompanion] Error checking NPC follow target: " + e.getMessage());
                Sentry.captureException(e);
            }
        }
    }

    /**
     * SAFELY clear NPC follow targets for a player during shutdown.
     * This method does NOT access Hytale entity APIs (which require WorldThread).
     * It only clears our internal tracking maps.
     */
    public void clearFollowTargetsSafe(String playerName) {
        if (playerName == null || npcInstanceEntities.isEmpty()) {
            return;
        }

        // Note: During shutdown, we cannot access Hytale entity APIs from
        // ShutdownThread.
        // We can only clear our internal tracking. Hytale's entity cleanup will handle
        // the actual NPC state during world shutdown.
        int clearedCount = 0;
        for (UUID npcInstanceId : npcInstanceEntities.keySet()) {
            if (busyNpcs.remove(npcInstanceId)) {
                clearedCount++;
            }
        }

        if (clearedCount > 0) {
            logger.debug("[Hycompanion] Safe cleared " + clearedCount + " busy NPCs for player: " + playerName);
        }
    }

    /**
     * Clear all entity references from tracking maps.
     * Called during early shutdown to prevent "Invalid entity reference" errors.
     * This must be called BEFORE Hytale starts removing players.
     */
    public void clearAllEntityReferences() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();

        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] CLEAR_ALL_ENTITY_REFERENCES STARTED");
        logger.info("[Hycompanion] Thread: " + threadName);
        logger.info("[Hycompanion] Timestamp: " + startTime);
        logger.info("[Hycompanion] ============================================");

        // STEP 1: Cancel all scheduled tasks first to stop new world.execute() calls
        logger.info("[Hycompanion] Step 1/5: Cancelling scheduled tasks...");
        long stepStart = System.currentTimeMillis();
        int cancelledTasks = 0;
        int rotationCount = rotationTasks.size();
        int movementCount = movementTasks.size();
        int thinkingCount = thinkingAnimationTasks.size();

        for (ScheduledFuture<?> task : rotationTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelledTasks++;
            }
        }
        rotationTasks.clear();

        for (ScheduledFuture<?> task : movementTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelledTasks++;
            }
        }
        movementTasks.clear();

        for (ScheduledFuture<?> task : thinkingAnimationTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelledTasks++;
            }
        }
        thinkingAnimationTasks.clear();

        logger.info("[Hycompanion] Step 1/5: Cancelled " + cancelledTasks + " tasks " +
                "(rotation: " + rotationCount +
                ", movement: " + movementCount + ", thinking: " + thinkingCount + ") " +
                "in " + (System.currentTimeMillis() - stepStart) + "ms");

        // STEP 2: Shutdown the rotation scheduler
        logger.info("[Hycompanion] Step 2/5: Shutting down rotation scheduler...");
        stepStart = System.currentTimeMillis();
        if (rotationScheduler != null && !rotationScheduler.isShutdown()) {
            rotationScheduler.shutdownNow();
            try {
                boolean terminated = rotationScheduler.awaitTermination(200, TimeUnit.MILLISECONDS);
                logger.info("[Hycompanion] Step 2/5: Rotation scheduler " +
                        (terminated ? "terminated" : "did not terminate") + " in " +
                        (System.currentTimeMillis() - stepStart) + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("[Hycompanion] Step 2/5: Interrupted while waiting for scheduler");
                Sentry.captureException(e);
            }
        } else {
            logger.info("[Hycompanion] Step 2/5: Scheduler already shut down or null");
        }

        // STEP 3: Complete any pending movement futures
        logger.info("[Hycompanion] Step 3/5: Checking for pending futures...");
        stepStart = System.currentTimeMillis();
        int pendingFutures = 0;
        for (var entry : movementTasks.entrySet()) {
            if (!entry.getValue().isDone()) {
                pendingFutures++;
            }
            entry.getValue().cancel(true);
        }
        logger.info("[Hycompanion] Step 3/5: Found and cancelled " + pendingFutures + " pending futures in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // STEP 4: Clear entity references
        logger.info("[Hycompanion] Step 4/5: Clearing entity references...");
        stepStart = System.currentTimeMillis();
        int npcCount = npcInstanceEntities.size();
        int targetCount = movementTargetEntities.size();
        int busyCount = busyNpcs.size();

        npcInstanceEntities.clear();
        movementTargetEntities.clear();
        busyNpcs.clear();
        npcSpawnLocations.clear();
        pendingRespawns.clear();

        // Cancel respawn checker
        if (respawnCheckerTask != null && !respawnCheckerTask.isDone()) {
            respawnCheckerTask.cancel(true);
            respawnCheckerTask = null;
        }

        logger.info("[Hycompanion] Step 4/5: Cleared " + npcCount + " NPCs, " + targetCount + " targets, " +
                busyCount + " busy flags in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // STEP 5: Brief pause for in-flight tasks
        logger.info("[Hycompanion] Step 5/5: Waiting for in-flight tasks (50ms)...");
        stepStart = System.currentTimeMillis();
        try {
            Thread.sleep(50);
            logger.info("[Hycompanion] Step 5/5: Wait complete (actual: " +
                    (System.currentTimeMillis() - stepStart) + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[Hycompanion] Step 5/5: Interrupted during wait");
        }

        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] CLEAR_ALL_ENTITY_REFERENCES COMPLETE");
        logger.info("[Hycompanion] Total time: " + totalTime + "ms");
        logger.info("[Hycompanion] ============================================");
    }

    @Override
    public boolean startAttacking(UUID npcInstanceId, String targetName, String attackType) {
        logger.info(
                "[Hycompanion] NPC [" + npcInstanceId + "] starting attack on: " + targetName + " (type: " + attackType
                        + ")");

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot attack - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot attack - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot attack - NPC entity reference is stale: " + npcInstanceId +
                    ". Cleaning up reference.");
            // Clean up reference
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            return false;
        }

        // Try to find target (could be player or another NPC)
        Ref<EntityStore> targetRef = null;

        // First try to find as player
        PlayerRef targetPlayer = Universe.get().getPlayerByUsername(targetName, NameMatching.EXACT_IGNORE_CASE);
        if (targetPlayer != null) {
            targetRef = targetPlayer.getReference();
        }

        // If not a player, could be another NPC - check our tracked NPCs
        if (targetRef == null) {
            NpcInstanceData targetNpcInstanceData = npcInstanceEntities.get(java.util.UUID.fromString(targetName));
            if (targetNpcInstanceData != null) {
                targetRef = targetNpcInstanceData.entityRef();
            }
        }

        if (targetRef == null || !targetRef.isValid()) {
            logger.warn("[Hycompanion] Cannot attack - Target not found: " + targetName);
            return false;
        }

        final Ref<EntityStore> finalTargetRef = targetRef;

        try {
            Store<EntityStore> store = entityRef.getStore();
            
            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] startAttacking: Could not find world '" + worldName + "' for NPC, falling back to default world");
            }

            if (resolvedWorld != null) {
                final World world = resolvedWorld;
                world.execute(() -> {
                    try {
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            // Ensure weapon is equipped
                            try {
                                Inventory inventory = npcEntity.getInventory();
                                if (inventory != null) {
                                    ItemContainer hotbar = inventory.getHotbar();
                                    int currentSlot = inventory.getActiveHotbarSlot();
                                    boolean currentSlotHasItem = false;

                                    if (currentSlot >= 0) {
                                        ItemStack currentItem = hotbar.getItemStack((short) currentSlot);
                                        if (currentItem != null && !currentItem.isEmpty()) {
                                            currentSlotHasItem = true;
                                        }
                                    }

                                    if (!currentSlotHasItem) {
                                        // Try to find a slot with an item
                                        boolean foundItem = false;
                                        for (short i = 0; i < hotbar.getCapacity(); i++) {
                                            ItemStack item = hotbar.getItemStack(i);
                                            if (item != null && !item.isEmpty()) {
                                                inventory.setActiveHotbarSlot((byte) i);
                                                logger.info("[Hycompanion] Auto-equipped hotbar slot " + i + " for NPC "
                                                        + npcInstanceId);
                                                foundItem = true;
                                                break;
                                            }
                                        }

                                        if (!foundItem) {
                                            logger.info("[Hycompanion] NPC " + npcInstanceId
                                                    + " has no items in hotbar. Proceeding with unarmed attack.");
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                logger.info("[Hycompanion] Inventory check failed: " + e.getMessage());
                                Sentry.captureException(e);
                            }

                            var role = npcEntity.getRole();
                            if (role != null) {
                                // Cancel any playing Action animation (Sit, Sleep, etc.) by playing Idle
                                // We use playAnimation with "Idle" instead of just stopAnimation because some
                                // looping animations need to be explicitly replaced
                                try {
                                    // First try to stop the action slot
                                    AnimationUtils.stopAnimation(entityRef, AnimationSlot.Action, true, store);
                                    // Then play Idle to ensure visual reset
                                    npcEntity.playAnimation(entityRef, AnimationSlot.Action, "Idle", store);
                                    logger.info("[Hycompanion] Reset animation to Idle before attacking");
                                } catch (Exception e) {
                                    logger.debug("[Hycompanion] Could not reset animation: " + e.getMessage());
                                    Sentry.captureException(e);
                                }

                                // Also reset state machine to Idle
                                try {
                                    role.getStateSupport().setState(entityRef, "Idle", "Default", store);
                                } catch (Exception e) {
                                    logger.debug("[Hycompanion] Could not reset state to Idle: " + e.getMessage());
                                    Sentry.captureException(e);
                                }

                                // Set the target using MarkedEntitySupport
                                role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", finalTargetRef);

                                // Configure attack type using CombatSupport
                                var combatSupport = role.getCombatSupport();
                                if (combatSupport != null) {
                                    combatSupport.clearAttackOverrides();
                                    if ("ranged".equalsIgnoreCase(attackType)) {
                                        combatSupport.addAttackOverride("Attack=Ranged");
                                    } else {
                                        combatSupport.addAttackOverride("Attack=Melee");
                                    }
                                }

                                // Try to transition to Combat/Attack state
                                // We check which states are available in the role definition
                                try {
                                    StateMappingHelper stateHelper = role.getStateSupport().getStateHelper();
                                    boolean stateSet = false;

                                    // 1. Try "Combat" state (Standard)
                                    if (stateHelper.getStateIndex("Combat") >= 0) {
                                        role.getStateSupport().setState(entityRef, "Combat", "Attack", store);
                                        stateSet = true;
                                        logger.info("[Hycompanion] NPC transitioned to Combat.Attack state");
                                    }
                                    // 2. Try "Chase" state (Trorks, Creatures)
                                    else if (stateHelper.getStateIndex("Chase") >= 0) {
                                        // For Chase, we prefer the "Attack" substate if it exists
                                        if (stateHelper.getSubStateIndex(stateHelper.getStateIndex("Chase"),
                                                "Attack") >= 0) {
                                            role.getStateSupport().setState(entityRef, "Chase", "Attack", store);
                                            logger.info("[Hycompanion] NPC transitioned to Chase.Attack state");
                                        } else {
                                            role.getStateSupport().setState(entityRef, "Chase", "Default", store);
                                            logger.info("[Hycompanion] NPC transitioned to Chase.Default state");
                                        }
                                        stateSet = true;
                                    }
                                    // 3. Try "Attack" state (Simple)
                                    else if (stateHelper.getStateIndex("Attack") >= 0) {
                                        role.getStateSupport().setState(entityRef, "Attack", "Default", store);
                                        stateSet = true;
                                        logger.info("[Hycompanion] NPC transitioned to Attack state");
                                    }

                                    if (stateSet) {
                                        busyNpcs.add(npcInstanceId);
                                    } else {
                                        logger.warn(
                                                "[Hycompanion] effective startAttacking failed: No suitable combat state found (checked: Combat, Chase, Attack). Role: "
                                                        + npcEntity.getRoleName());
                                    }
                                } catch (Exception e) {
                                    logger.error("[Hycompanion] Exception setting combat state: " + e.getMessage());
                                    Sentry.captureException(e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("[Hycompanion] Error in startAttacking: " + e.getMessage());
                        Sentry.captureException(e);
                    }
                });
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to start attacking: " + e.getMessage());
            Sentry.captureException(e);
        }
        return false;
    }

    @Override
    public boolean stopAttacking(UUID npcInstanceId) {
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] stopping attack");

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot stop attacking - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot stop attacking - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot stop attacking - NPC entity reference is stale: " + npcInstanceId +
                    ". Cleaning up reference.");
            // Cleaning up reference
            npcInstanceEntities.remove(npcInstanceId);
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            
            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] stopAttacking: Could not find world '" + worldName + "' for NPC, falling back to default world");
            }

            if (resolvedWorld != null) {
                final World world = resolvedWorld;
                world.execute(() -> {
                    try {
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            var role = npcEntity.getRole();
                            if (role != null) {
                                // Clear combat overrides
                                var combatSupport = role.getCombatSupport();
                                if (combatSupport != null) {
                                    combatSupport.clearAttackOverrides();
                                }

                                // Clear the target
                                role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);

                                // Return to Idle state
                                try {
                                    role.getStateSupport().setState(entityRef, "Idle", "Default", store);
                                    busyNpcs.remove(npcInstanceId);
                                    logger.info("[Hycompanion] NPC returned to Idle state");
                                } catch (Exception e) {
                                    logger.debug("[Hycompanion] Could not set Idle state: " + e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("[Hycompanion] Error in stopAttacking: " + e.getMessage());
                        Sentry.captureException(e);
                    }
                });
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to stop attacking: " + e.getMessage());
            Sentry.captureException(e);
        }
        return false;
    }

    @Override
    public boolean isNpcBusy(UUID npcInstanceId) {
        return busyNpcs.contains(npcInstanceId);
    }

    // ========== Teleport Operations ==========

    @Override
    public boolean teleportNpcTo(UUID npcInstanceId, Location location) {
        logger.info("[Hycompanion] Teleporting NPC [" + npcInstanceId + "] to " + location);

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot teleport - NPC entity not tracked: " + npcInstanceId);
            return false;
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot teleport - NPC entity not tracked: " + npcInstanceId);
            return false;
        }

        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot teleport - NPC entity reference is stale: " + npcInstanceId +
                    ". Cleaning up reference.");
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            World world = getWorldByName(location.world());

            if (world == null) {
                logger.warn("[Hycompanion] Cannot teleport - World not found: " + location.world());
                return false;
            }

            // Execute teleport on world thread
            world.execute(() -> {
                try {
                    TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
                    if (transform != null) {
                        Vector3d newPos = new Vector3d(location.x(), location.y(), location.z());
                        transform.setPosition(newPos);

                        // Update leash point so NPC wanders around new location
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            npcEntity.setLeashPoint(newPos);
                        }

                        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] teleported to " + location);
                    } else {
                        logger.warn("[Hycompanion] Cannot teleport - Transform component not found for NPC: "
                                + npcInstanceId);
                    }
                } catch (Exception e) {
                    logger.error("[Hycompanion] Error teleporting NPC: " + e.getMessage());
                    Sentry.captureException(e);
                }
            });
            return true;
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to teleport NPC: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean teleportPlayerTo(String playerId, Location location) {
        logger.info("[Hycompanion] Teleporting player [" + playerId + "] to " + location);

        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);

            if (playerRef == null) {
                logger.warn("[Hycompanion] Cannot teleport - Player not found: " + playerId);
                return false;
            }

            World world = getWorldByName(location.world());
            if (world == null) {
                logger.warn("[Hycompanion] Cannot teleport - World not found: " + location.world());
                return false;
            }

            // Get player entity reference
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) {
                logger.warn("[Hycompanion] Cannot teleport - Player entity reference not available: " + playerId);
                return false;
            }

            // Execute teleport on world thread using Teleport component
            world.execute(() -> {
                try {
                    Store<EntityStore> store = entityRef.getStore();

                    // Get current transform to preserve rotation
                    TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
                    HeadRotation headRotation = store.getComponent(entityRef, HeadRotation.getComponentType());

                    Vector3f currentBodyRotation = (transform != null) ? transform.getRotation()
                            : new Vector3f(0, 0, 0);
                    Vector3f currentHeadRotation = (headRotation != null) ? headRotation.getRotation()
                            : new Vector3f(0, 0, 0);

                    // Create teleport destination
                    Vector3d newPos = new Vector3d(location.x(), location.y(), location.z());

                    // Create Teleport component - this is how Hytale handles teleportation
                    Teleport teleport = new Teleport(newPos, currentBodyRotation);
                    teleport.setHeadRotation(currentHeadRotation);

                    // Add the Teleport component to the entity store
                    store.addComponent(entityRef, Teleport.getComponentType(), teleport);

                    logger.info("[Hycompanion] Player [" + playerId + "] teleported to " + location);
                } catch (Exception e) {
                    logger.error("[Hycompanion] Error teleporting player: " + e.getMessage());
                    Sentry.captureException(e);
                    e.printStackTrace();
                }
            });
            return true;
        } catch (IllegalArgumentException e) {
            logger.warn("[Hycompanion] Invalid UUID format for player ID: " + playerId);
            Sentry.captureException(e);
            return false;
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to teleport player: " + e.getMessage());
            Sentry.captureException(e);
            return false;
        }
    }

    // ========== Animation Discovery ==========

    @Override
    public List<String> getAvailableAnimations(UUID npcInstanceId) {
        logger.info("[Hycompanion] Getting available animations for NPC: " + npcInstanceId);

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.info("[Hycompanion] Cannot get animations - NPC entity not tracked: " + npcInstanceId);
            return Collections.emptyList();
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            logger.info("[Hycompanion] Cannot get animations - NPC entity not found: " + npcInstanceId);
            return Collections.emptyList();
        }

        // Entity component access must happen on the WorldThread
        // Get the correct world for this NPC, not the default world
        String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] getAvailableAnimations: Could not find world '" + worldName + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            logger.info("[Hycompanion] Cannot get animations - world not available");
            return Collections.emptyList();
        }
        final World world = resolvedWorld;

        java.util.concurrent.CompletableFuture<List<String>> future = new java.util.concurrent.CompletableFuture<>();

        world.execute(() -> {
            try {
                Store<EntityStore> store = entityRef.getStore();

                // Get the model component from the entity
                ModelComponent modelComponent = store.getComponent(entityRef, ModelComponent.getComponentType());
                if (modelComponent != null) {
                    Model model = modelComponent.getModel();
                    if (model != null && model.getAnimationSetMap() != null) {
                        // Return ALL animation set keys - let the LLM decide which ones to use
                        // AnimationSets include: Idle, Walk, Run, Sit, Sleep, Howl, Greet, etc.
                        // Each model has different animations based on its JSON definition
                        List<String> animations = new ArrayList<>(model.getAnimationSetMap().keySet());

                        logger.info("[Hycompanion] Found " + animations.size() + " animations for NPC "
                                + npcInstanceId + ": " + animations);
                        future.complete(animations);
                        return;
                    }
                }
                future.complete(Collections.emptyList());
            } catch (Exception e) {
                logger.error("[Hycompanion] Error getting animations for NPC " + npcInstanceId + ": " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Collections.emptyList());
            }
        });

        try {
            // Wait for result from world thread (with timeout to prevent deadlock)
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("[Hycompanion] Timeout or error waiting for animations for NPC " + npcInstanceId + ": "
                    + e.getMessage());
            Sentry.captureException(e);
            return Collections.emptyList();
        }
    }

    /**
     * Format a role name into a human-readable display name.
     * 
     * The DisplayNameComponent often contains a localization key (e.g.,
     * "npc.name.villager")
     * which cannot be resolved server-side. This method converts the role name
     * directly
     * into a readable format.
     * 
     * Examples:
     * - "villager" → "Villager"
     * - "guard_captain" → "Guard Captain"
     * - "hytale:villager" → "Villager"
     * 
     * @param roleName The role name from NPCEntity.getRoleName()
     * @return A human-readable display name
     */
    private String formatRoleAsDisplayName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return "NPC";
        }

        // Strip namespace prefix if present (e.g., "hytale:villager" → "villager")
        String name = roleName;
        int colonIndex = name.indexOf(':');
        if (colonIndex >= 0) {
            name = name.substring(colonIndex + 1);
        }

        // Replace underscores with spaces and capitalize each word
        String[] words = name.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            String word = words[i];
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }
            }
        }

        return result.length() > 0 ? result.toString() : "NPC";
    }

    // ========== Thinking Indicator Operations ==========

    /** Vertical offset for the thinking hologram above the NPC's head (in blocks) */
    private static final double THINKING_HOLOGRAM_Y_OFFSET = 2.0; // was 2.5
    
    /** Attachment offset for MountedComponent (Vector3f for the mount system) */
    private static final Vector3f THINKING_INDICATOR_OFFSET = new Vector3f(0.0f, 2.0f, 0.0f);

    /** Animation interval in milliseconds for cycling dots */
    private static final long THINKING_ANIMATION_INTERVAL_MS = 250;

    /** Thinking text states for animation cycle */
    private static final String[] THINKING_STATES = {
            "Thinking .",
            "Thinking ..",
            "Thinking ..."
    };

    @Override
    public void showThinkingIndicator(UUID npcInstanceId) {
        logger.debug("[Hycompanion] Showing thinking indicator for NPC: " + npcInstanceId);

        // [Shutdown Fix] Don't show thinking indicators during shutdown
        if (isShuttingDown()) {
            logger.debug("[Hycompanion] Skipping thinking indicator during shutdown");
            return;
        }

        // Get NPC instance data
        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot show thinking indicator - NPC entity not tracked: " + npcInstanceId);
            return;
        }

        // Get the correct world for this NPC
        String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
        }
        if (resolvedWorld == null) {
            logger.warn("[Hycompanion] Cannot show thinking indicator - No world available");
            return;
        }
        final World world = resolvedWorld;

        Ref<EntityStore> npcEntityRef = npcInstanceData.entityRef();
        if (npcEntityRef == null || !npcEntityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot show thinking indicator - NPC entity not valid: " + npcInstanceId);
            if (npcEntityRef != null && !npcEntityRef.isValid()) {
                npcInstanceEntities.remove(npcInstanceId);
            }
            return;
        }

        // Execute all logic on world thread for atomicity
        try {
            world.execute(() -> {
                if (isShuttingDown()) {
                    return;
                }

                // Check if indicator already exists (atomic on world thread)
                Ref<EntityStore> existingRef = thinkingIndicatorRefs.get(npcInstanceId);
                if (existingRef != null) {
                    if (existingRef.isValid()) {
                        // Reuse existing indicator
                        logger.debug("[Hycompanion] Reusing existing thinking indicator for NPC: " + npcInstanceId);
                        restartThinkingAnimation(npcInstanceId, existingRef);
                    } else {
                        // Invalid ref, remove it
                        thinkingIndicatorRefs.remove(npcInstanceId);
                        // Try again (will spawn new one)
                        spawnThinkingIndicator(npcInstanceId, npcEntityRef, world);
                    }
                    return;
                }

                // No indicator exists, spawn new one
                spawnThinkingIndicator(npcInstanceId, npcEntityRef, world);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("[Hycompanion] Could not show thinking indicator - world shutting down");
        }
    }

    /**
     * Spawn a new thinking indicator for an NPC.
     * Must be called on the world thread.
     */
    private void spawnThinkingIndicator(UUID npcInstanceId, Ref<EntityStore> npcEntityRef, World world) {
        try {
            Store<EntityStore> store = npcEntityRef.getStore();
            if (store == null) {
                return;
            }

            TransformComponent npcTransform = store.getComponent(npcEntityRef,
                    TransformComponent.getComponentType());
            if (npcTransform == null) {
                return;
            }

            Vector3d npcPos = npcTransform.getPosition();
            Vector3d hologramPos = new Vector3d(npcPos.getX(), npcPos.getY() + THINKING_HOLOGRAM_Y_OFFSET,
                    npcPos.getZ());
            Vector3f hologramRotation = new Vector3f(0, 0, 0);

            // Create hologram entity
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            ProjectileComponent projectileComponent = new ProjectileComponent("Projectile");
            holder.putComponent(ProjectileComponent.getComponentType(), projectileComponent);
            holder.putComponent(TransformComponent.getComponentType(),
                    new TransformComponent(hologramPos, hologramRotation));
            holder.ensureComponent(UUIDComponent.getComponentType());
            holder.ensureComponent(Intangible.getComponentType());
            if (projectileComponent.getProjectile() == null) {
                projectileComponent.initialize();
            }
            holder.addComponent(NetworkId.getComponentType(),
                    new NetworkId(world.getEntityStore().getStore().getExternalData().takeNextNetworkId()));
            Nameplate nameplate = new Nameplate(THINKING_STATES[0]);
            holder.addComponent(Nameplate.getComponentType(), nameplate);

            Ref<EntityStore> hologramRef = world.getEntityStore().getStore().addEntity(holder, AddReason.SPAWN);

            if (hologramRef != null && hologramRef.isValid()) {
                // Store the hologram reference
                thinkingIndicatorRefs.put(npcInstanceId, hologramRef);

                logger.info("[Hycompanion] Thinking indicator spawned for NPC: " + npcInstanceId +
                        " at " + hologramPos.getX() + ", " + hologramPos.getY() + ", " + hologramPos.getZ());

                startThinkingAnimation(npcInstanceId, npcEntityRef, hologramRef, world);
            } else {
                logger.warn("[Hycompanion] Failed to get hologram reference after spawn");
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Error spawning thinking indicator: " + e.getMessage());
        }
    }

    /**
     * Restart the thinking animation for an existing indicator.
     * Must be called on the world thread.
     */
    private void restartThinkingAnimation(UUID npcInstanceId, Ref<EntityStore> hologramRef) {
        // Get the NPC entity ref for position tracking
        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null || npcInstanceData.entityRef() == null) {
            return;
        }
        
        World world = getWorldForHologram(hologramRef, npcInstanceId);
        if (world == null) {
            return;
        }

        // Reset the text to initial state (already on world thread)
        try {
            if (hologramRef.isValid()) {
                Store<EntityStore> store = hologramRef.getStore();
                if (store != null) {
                    Nameplate nameplate = store.getComponent(hologramRef, Nameplate.getComponentType());
                    if (nameplate != null) {
                        nameplate.setText(THINKING_STATES[0]);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] Error resetting thinking text: " + e.getMessage());
        }

        // Start/restart the animation
        startThinkingAnimation(npcInstanceId, npcInstanceData.entityRef(), hologramRef, world);
    }

    /**
     * Start the animation task that cycles through "Thinking .", "Thinking ..", "Thinking ..." text.
     * The position is automatically handled by the MountedComponent - no manual updates needed.
     * 
     * @param npcInstanceId The NPC's instance ID
     * @param hologramRef   Reference to the hologram entity (to update)
     * @param world         The world for thread-safe execution
     */
    private void startThinkingAnimation(UUID npcInstanceId, Ref<EntityStore> npcEntityRef,
            Ref<EntityStore> hologramRef, World world) {
        AtomicInteger stateIndex = new AtomicInteger(0);

        ScheduledFuture<?> animationTask = rotationScheduler.scheduleAtFixedRate(() -> {
            try {
                // [Shutdown Fix] Check shutdown flag first
                if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
                    cancelThinkingAnimation(npcInstanceId);
                    return;
                }

                // Check if this task has been cancelled (removed from map)
                ScheduledFuture<?> currentTask = thinkingAnimationTasks.get(npcInstanceId);
                if (currentTask == null) {
                    // Task was cancelled, stop execution
                    Thread.currentThread().interrupt();
                    return;
                }

                // Check if hologram is still valid
                if (!hologramRef.isValid()) {
                    cancelThinkingAnimation(npcInstanceId);
                    return;
                }

                // [Shutdown Fix] Stop if no players online (Server shutting down?)
                // Use Universe directly to avoid our wrapper's checks during shutdown
                try {
                    if (Universe.get().getPlayers().isEmpty()) {
                        cancelThinkingAnimation(npcInstanceId);
                        return;
                    }
                } catch (Exception e) {
                    // During shutdown, player list may be inaccessible
                    cancelThinkingAnimation(npcInstanceId);
                    return;
                }

                // Check if NPC entity is still valid
                if (!npcEntityRef.isValid()) {
                    logger.debug(
                            "[Hycompanion] NPC entity became invalid, hiding thinking indicator: " + npcInstanceId);
                    hideThinkingIndicator(npcInstanceId);
                    return;
                }

                // Cycle to next state
                int nextIndex = (stateIndex.incrementAndGet()) % THINKING_STATES.length;
                String newText = THINKING_STATES[nextIndex];

                // Update nameplate text AND position on world thread
                // Use safeWorldExecute to handle shutdown gracefully
                safeWorldExecute(world, () -> {
                    try {
                        // Double-check shutdown inside the task
                        if (isShuttingDown()) {
                            return;
                        }

                        Store<EntityStore> hologramStore = hologramRef.getStore();
                        Store<EntityStore> npcStore = npcEntityRef.getStore();

                        if (hologramStore == null || npcStore == null) {
                            return;
                        }

                        // Update nameplate text
                        Nameplate nameplate = hologramStore.getComponent(hologramRef, Nameplate.getComponentType());
                        if (nameplate != null) {
                            nameplate.setText(newText);
                        }

                        // Update hologram position to follow NPC
                        TransformComponent npcTransform = npcStore.getComponent(npcEntityRef,
                                TransformComponent.getComponentType());
                        TransformComponent hologramTransform = hologramStore.getComponent(hologramRef,
                                TransformComponent.getComponentType());

                        if (npcTransform != null && hologramTransform != null) {
                            Vector3d npcPos = npcTransform.getPosition();
                            Vector3d newHologramPos = new Vector3d(
                                    npcPos.getX(),
                                    npcPos.getY() + THINKING_HOLOGRAM_Y_OFFSET,
                                    npcPos.getZ());
                            hologramTransform.setPosition(newHologramPos);
                        }
                    } catch (Exception e) {
                        logger.debug("[Hycompanion] Error updating thinking animation: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.debug("[Hycompanion] Error in thinking animation task: " + e.getMessage());
                cancelThinkingAnimation(npcInstanceId);
            }
        }, THINKING_ANIMATION_INTERVAL_MS, THINKING_ANIMATION_INTERVAL_MS, TimeUnit.MILLISECONDS);

        thinkingAnimationTasks.put(npcInstanceId, animationTask);
    }

    /**
     * Cancel the thinking animation task for an NPC
     */
    private void cancelThinkingAnimation(UUID npcInstanceId) {
        ScheduledFuture<?> task = thinkingAnimationTasks.remove(npcInstanceId);
        if (task != null) {
            // Cancel with interrupt to stop ongoing execution
            boolean cancelled = task.cancel(true);
            logger.debug("[Hycompanion] Cancelled thinking animation for NPC: " + npcInstanceId + " (success: "
                    + cancelled + ")");
        }
    }

    @Override
    public int removeZombieThinkingIndicators() {
        // [Shutdown Fix] Don't scan/remove entities during shutdown
        if (isShuttingDown()) {
            logger.debug("[Hycompanion] Skipping zombie indicator cleanup during shutdown");
            return 0;
        }

        logger.info("[Hycompanion] Scanning for zombie thinking indicators...");

        // Collect UUIDs of currently tracked holograms to avoid removing active ones
        // (This allows safe execution even if called while plugin is active)
        Set<UUID> validHologramUuids = new HashSet<>();
        for (Ref<EntityStore> ref : thinkingIndicatorRefs.values()) {
            if (ref != null && ref.isValid()) {
                try {
                    Store<EntityStore> store = ref.getStore();
                    if (store != null) {
                        UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
                        if (uuidComp != null) {
                            validHologramUuids.add(uuidComp.getUuid());
                        }
                    }
                } catch (Exception e) {
                    // Ignore errors checking individual refs
                }
            }
        }

        AtomicInteger removedCount = new AtomicInteger(0);
        AtomicInteger pendingWorlds = new AtomicInteger(Universe.get().getWorlds().size());

        for (World world : Universe.get().getWorlds().values()) {
            // [Shutdown Fix] Check shutdown before submitting to world
            if (isShuttingDown()) {
                pendingWorlds.decrementAndGet();
                continue;
            }

            try {
                world.execute(() -> {
                    // [Shutdown Fix] Check shutdown inside the task
                    if (isShuttingDown()) {
                        pendingWorlds.decrementAndGet();
                        return;
                    }

                    try {
                        Store<EntityStore> store = world.getEntityStore().getStore();
                        List<Ref<EntityStore>> toRemove = new ArrayList<>();

                        store.forEachChunk(Nameplate.getComponentType(),
                                (BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>>) (chunk,
                                        commandBuffer) -> {
                                    // [Shutdown Fix] Check shutdown while iterating
                                    if (isShuttingDown()) {
                                        return;
                                    }

                                    for (int i = 0; i < chunk.size(); i++) {
                                        try {
                                            Nameplate nameplate = chunk.getComponent(i, Nameplate.getComponentType());
                                            if (nameplate != null && nameplate.getText() != null
                                                    && nameplate.getText().startsWith("Thinking")) {

                                                // Verify it is a projectile (hologram) to be safe
                                                if (chunk.getComponent(i,
                                                        ProjectileComponent.getComponentType()) == null) {
                                                    continue;
                                                }

                                                // Check if it's a valid tracked hologram
                                                UUIDComponent uuidComp = chunk.getComponent(i,
                                                        UUIDComponent.getComponentType());
                                                if (uuidComp != null
                                                        && validHologramUuids.contains(uuidComp.getUuid())) {
                                                    continue;
                                                }

                                                // It's a zombie! Add to removal list.
                                                toRemove.add(chunk.getReferenceTo(i));

                                                if (uuidComp != null) {
                                                    logger.info("[Cleanup] Found zombie indicator: "
                                                            + uuidComp.getUuid());
                                                } else {
                                                    logger.info("[Cleanup] Found zombie indicator (no UUID)");
                                                }
                                            }
                                        } catch (Exception e) {
                                            // Continue scanning
                                        }
                                    }
                                });

                        // Remove collected entities (unless shutting down)
                        if (!isShuttingDown()) {
                            for (Ref<EntityStore> ref : toRemove) {
                                try {
                                    if (ref.isValid()) {
                                        store.removeEntity(ref, com.hypixel.hytale.component.RemoveReason.REMOVE);
                                        removedCount.incrementAndGet();
                                    }
                                } catch (Exception e) {
                                    logger.error("[Cleanup] Failed to remove entity: " + e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("[Cleanup] Error cleaning world: " + e.getMessage());
                    } finally {
                        pendingWorlds.decrementAndGet();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // World executor rejected task - server shutting down
                pendingWorlds.decrementAndGet();
                logger.debug("[Cleanup] World executor rejected task - shutting down");
            } catch (Exception e) {
                pendingWorlds.decrementAndGet();
                logger.error("[Cleanup] Failed to schedule world scan: " + e.getMessage());
            }
        }

        // Wait for scan to complete (max 1s)
        long start = System.currentTimeMillis();
        while (pendingWorlds.get() > 0 && System.currentTimeMillis() - start < 1000) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }

        int count = removedCount.get();
        if (count > 0) {
            logger.info("[Hycompanion] Removed " + count + " zombie thinking indicators.");
        } else {
            logger.info("[Hycompanion] No zombie thinking indicators found.");
        }

        return count;
    }

    @Override
    public void hideThinkingIndicator(UUID npcInstanceId) {
        logger.debug("[Hycompanion] Hiding thinking indicator for NPC: " + npcInstanceId);

        // Cancel animation task first
        cancelThinkingAnimation(npcInstanceId);

        // Get the hologram reference
        final Ref<EntityStore> hologramRef = thinkingIndicatorRefs.get(npcInstanceId);
        if (hologramRef == null || !hologramRef.isValid()) {
            return;
        }

        // [Shutdown Fix] During shutdown, don't try to modify entities
        if (isShuttingDown()) {
            logger.debug("[Hycompanion] Skipping thinking indicator hide during shutdown: " + npcInstanceId);
            return;
        }

        // Instead of removing the entity, just clear the text
        // This allows reusing the indicator for subsequent thinking states
        World world = getWorldForHologram(hologramRef, npcInstanceId);
        if (world != null) {
            try {
                world.execute(() -> {
                    try {
                        if (hologramRef.isValid()) {
                            Store<EntityStore> store = hologramRef.getStore();
                            if (store != null) {
                                Nameplate nameplate = store.getComponent(hologramRef, Nameplate.getComponentType());
                                if (nameplate != null) {
                                    nameplate.setText("");
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("[Hycompanion] Error hiding thinking hologram: " + e.getMessage());
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                logger.debug("[Hycompanion] Could not hide thinking indicator - world shutting down");
            }
        }
    }

    /**
     * Get the world for a hologram entity, falling back to NPC's world and default world.
     */
    private World getWorldForHologram(Ref<EntityStore> hologramRef, UUID npcInstanceId) {
        World world = null;
        if (hologramRef.isValid()) {
            try {
                Store<EntityStore> store = hologramRef.getStore();
                if (store != null) {
                    world = store.getExternalData().getWorld();
                }
            } catch (Exception e) {
                // Ignore, will fall back
            }
        }
        // Fall back to NPC's world
        if (world == null) {
            NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
            String worldName = npcData != null && npcData.spawnLocation() != null ? npcData.spawnLocation().world() : null;
            world = worldName != null ? Universe.get().getWorld(worldName) : null;
        }
        // Final fallback to default world
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }
        return world;
    }

    /**
     * Destroy the thinking indicator entity for an NPC.
     * Used for zombie cleanup and when NPC is removed.
     */
    public void destroyThinkingIndicator(UUID npcInstanceId) {
        logger.debug("[Hycompanion] Destroying thinking indicator for NPC: " + npcInstanceId);

        // Cancel animation task
        cancelThinkingAnimation(npcInstanceId);

        // Get and remove the hologram reference
        final Ref<EntityStore> hologramRef = thinkingIndicatorRefs.remove(npcInstanceId);

        // [Shutdown Fix] During shutdown, don't try to remove entities
        if (isShuttingDown()) {
            logger.debug("[Hycompanion] Skipping thinking indicator destruction during shutdown: " + npcInstanceId);
            return;
        }

        if (hologramRef == null || !hologramRef.isValid()) {
            return;
        }

        World world = getWorldForHologram(hologramRef, npcInstanceId);
        if (world != null) {
            try {
                world.execute(() -> {
                    try {
                        if (hologramRef.isValid()) {
                            Store<EntityStore> store = hologramRef.getStore();
                            if (store != null) {
                                store.removeEntity(hologramRef, com.hypixel.hytale.component.RemoveReason.REMOVE);
                                logger.info("[Hycompanion] Thinking indicator destroyed for NPC: " + npcInstanceId);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("[Hycompanion] Error destroying thinking hologram: " + e.getMessage());
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                logger.debug("[Hycompanion] Could not destroy thinking indicator - world shutting down");
            }
        }
    }

    @Override
    public void cleanup() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();

        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] CLEANUP (via ShutdownManager) STARTED");
        logger.info("[Hycompanion] Thread: " + threadName);
        logger.info("[Hycompanion] Timestamp: " + startTime);
        logger.info("[Hycompanion] ============================================");

        // Cancel all rotation tasks
        long stepStart = System.currentTimeMillis();
        int cancelled = 0;
        for (ScheduledFuture<?> task : rotationTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelled++;
            }
        }
        rotationTasks.clear();
        logger.info("[Hycompanion] Cancelled " + cancelled + " rotation tasks in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        logger.info("[Hycompanion] Cancelled " + cancelled + " following tasks in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // Cancel all movement tasks
        stepStart = System.currentTimeMillis();
        cancelled = 0;
        for (ScheduledFuture<?> task : movementTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelled++;
            }
        }
        movementTasks.clear();
        logger.info("[Hycompanion] Cancelled " + cancelled + " movement tasks in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // Cancel all thinking animation tasks
        stepStart = System.currentTimeMillis();
        cancelled = 0;
        for (ScheduledFuture<?> task : thinkingAnimationTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelled++;
            }
        }
        thinkingAnimationTasks.clear();
        logger.info("[Hycompanion] Cancelled " + cancelled + " thinking animation tasks in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // Shutdown local scheduler
        stepStart = System.currentTimeMillis();
        if (rotationScheduler != null && !rotationScheduler.isShutdown()) {
            rotationScheduler.shutdownNow();
            try {
                boolean terminated = rotationScheduler.awaitTermination(100, TimeUnit.MILLISECONDS);
                logger.info("[Hycompanion] Scheduler " + (terminated ? "terminated" : "timeout") +
                        " in " + (System.currentTimeMillis() - stepStart) + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("[Hycompanion] Interrupted waiting for scheduler");
            }
        } else {
            logger.info("[Hycompanion] Scheduler already shut down or null");
        }

        // Clear tracking maps
        stepStart = System.currentTimeMillis();
        int npcCount = npcInstanceEntities.size();
        int targetCount = movementTargetEntities.size();
        int busyCount = busyNpcs.size();
        int spawnLocCount = npcSpawnLocations.size();
        int pendingRespawnCount = pendingRespawns.size();

        npcInstanceEntities.clear();
        movementTargetEntities.clear();
        busyNpcs.clear();
        npcSpawnLocations.clear();
        pendingRespawns.clear();

        // Cancel respawn checker
        if (respawnCheckerTask != null && !respawnCheckerTask.isDone()) {
            respawnCheckerTask.cancel(true);
            respawnCheckerTask = null;
        }

        logger.info("[Hycompanion] Cleared " + npcCount + " NPCs, " + targetCount + " targets, " +
                busyCount + " busy flags, " +
                spawnLocCount + " spawn locations, " + pendingRespawnCount + " pending respawns in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] CLEANUP COMPLETE");
        logger.info("[Hycompanion] Total time: " + totalTime + "ms");
        logger.info("[Hycompanion] ============================================");
    }

    // ========== Block Discovery ==========

    @Override
    public List<dev.hycompanion.plugin.core.world.BlockInfo> getAvailableBlocks() {
        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] DISCOVERING BLOCKS FROM REGISTRY");
        logger.info("[Hycompanion] ============================================");

        List<dev.hycompanion.plugin.core.world.BlockInfo> blocks = new ArrayList<>();

        try {
            // Get the BlockType asset map
            var blockTypeMap = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap();
            if (blockTypeMap == null) {
                logger.warn("[Hycompanion] BlockType asset map is null");
                return blocks;
            }

            // Iterate over all registered block types
            var assetMap = blockTypeMap.getAssetMap();
            if (assetMap == null) {
                logger.warn("[Hycompanion] BlockType asset map internal map is null");
                return blocks;
            }

            int totalBlocks = assetMap.size();
            logger.info("[Hycompanion] Found " + totalBlocks + " block types in registry");

            // Log first 10 raw block IDs for debugging
            logger.info("[Hycompanion] --- Sample block IDs from registry ---");
            int sampleCount = 0;
            for (var entry : assetMap.entrySet()) {
                if (sampleCount >= 10)
                    break;
                String blockId = entry.getKey();
                var blockType = entry.getValue();

                // Try to get tags from block type data
                String tags = "N/A";
                try {
                    if (blockType != null && blockType.getData() != null) {
                        var rawTags = blockType.getData().getRawTags();
                        if (rawTags != null && !rawTags.isEmpty()) {
                            tags = rawTags.toString();
                        }
                    }
                } catch (Exception e) {
                    tags = "Error: " + e.getMessage();
                }

                logger.info(String.format("[Hycompanion] Block %d: ID=%s, Tags=%s",
                        sampleCount + 1, blockId, tags.substring(0, Math.min(tags.length(), 100))));
                sampleCount++;
            }
            logger.info("[Hycompanion] --- End sample ---");

            // Now classify all blocks
            int processed = 0;
            int errors = 0;

            logger.warn("[Hycompanion] Sending only Wood_Beech_Trunk block for now");

            for (var entry : assetMap.entrySet()) {
                try {
                    String blockId = entry.getKey();
                    var blockType = entry.getValue();

                    if (blockType == null)
                        continue;

                    // Get display name from the block type
                    String displayName = blockId;
                    try {
                        if (blockType.getId() != null) {
                            displayName = formatBlockIdAsDisplayName(blockType.getId());
                        }
                    } catch (Exception e) {
                        displayName = blockId;
                    }

                    if (!displayName.contains("Wood_Beech_Trunk")) {
                        continue;
                    }

                    // Extract tags from Hytale block data
                    Map<String, String[]> tags = null;
                    List<String> categories = null;
                    try {
                        if (blockType.getData() != null) {
                            tags = blockType.getData().getRawTags();
                        }
                    } catch (Exception e) {
                        // Tags not available
                    }

                    // Classify the block with Hytale tags
                    var blockInfo = dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                            blockId, displayName, tags, categories);

                    blocks.add(blockInfo);
                    processed++;

                } catch (Exception e) {
                    errors++;
                    if (errors <= 5) {
                        logger.debug("[Hycompanion] Error processing block type: " + e.getMessage());
                    }
                }
            }

            logger.info("[Hycompanion] Processed " + processed + " blocks (" + errors + " errors)");

            // Log classified samples
            logger.info("[Hycompanion] --- Sample classified blocks ---");
            for (int i = 0; i < Math.min(10, blocks.size()); i++) {
                var block = blocks.get(i);
                logger.info(String.format("[Hycompanion] %s -> materials=%s, keywords=%s",
                        block.blockId(),
                        block.materialTypes(),
                        block.keywords().subList(0, Math.min(5, block.keywords().size()))));
            }
            logger.info("[Hycompanion] --- End sample ---");

            // Log material type distribution
            var materialCounts = new java.util.HashMap<String, Integer>();
            for (var block : blocks) {
                for (var material : block.materialTypes()) {
                    materialCounts.merge(material, 1, Integer::sum);
                }
            }
            logger.info("[Hycompanion] Material type distribution: " + materialCounts);

        } catch (Exception e) {
            logger.error("[Hycompanion] Error discovering blocks: " + e.getMessage());
            e.printStackTrace();
        }

        logger.info("[Hycompanion] Returning " + blocks.size() + " classified blocks");
        logger.info("[Hycompanion] ============================================");
        return blocks;
    }

    /**
     * Format a block ID into a human-readable display name.
     * Similar to formatRoleAsDisplayName but for blocks.
     */
    private String formatBlockIdAsDisplayName(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "Block";
        }

        // Strip namespace prefix if present (e.g., "hytale:stone" → "stone")
        String name = blockId;
        int colonIndex = name.indexOf(':');
        if (colonIndex >= 0) {
            name = name.substring(colonIndex + 1);
        }

        // Replace underscores with spaces and capitalize each word
        String[] words = name.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            String word = words[i];
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }
            }
        }

        return result.length() > 0 ? result.toString() : "Block";
    }

}

