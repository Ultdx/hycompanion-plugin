package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service to handle NPC greeting behavior when players approach for the first
 * time.
 * 
 * Tracks which players have been greeted by each NPC (since server start).
 * When a new player approaches an NPC that has a greeting configured:
 * 1. Rotates the NPC toward the player
 * 2. Sends the greeting message to the player
 * 
 * If an NPC has no greeting set (null or empty), it will not greet players.
 */
public class NpcGreetingService {

    private final HytaleAPI hytaleAPI;
    private final NpcManager npcManager;
    private final PluginConfig config;
    private final PluginLogger logger;

    // Tracks which players have been greeted by each NPC: "npcInstanceId:playerId"
    // -> true
    private final Set<String> greetedPlayers = ConcurrentHashMap.newKeySet();

    private ScheduledFuture<?> proximityCheckTask;

    // Shutdown flag to prevent operations during server shutdown
    private volatile boolean isShutdown = false;

    public NpcGreetingService(HytaleAPI hytaleAPI, NpcManager npcManager, PluginConfig config, PluginLogger logger) {
        this.hytaleAPI = hytaleAPI;
        this.npcManager = npcManager;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Start the periodic proximity check for greeting players.
     * 
     * @param scheduler  The scheduler to use for periodic checks
     * @param intervalMs The interval between checks in milliseconds
     */
    public void startProximityChecks(ScheduledExecutorService scheduler, long intervalMs) {

        proximityCheckTask = scheduler.scheduleAtFixedRate(
                this::checkPlayerProximity,
                intervalMs, // Initial delay
                intervalMs, // Period
                TimeUnit.MILLISECONDS);

        logger.info("NpcGreetingService started with interval " + intervalMs + "ms, range: "
                + config.gameplay().greetingRange());
    }

    /**
     * Stop the proximity check task.
     */
    public void stopProximityChecks() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        
        logger.info("[NpcGreetingService] Stopping on thread: " + threadName);
        
        isShutdown = true;
        if (proximityCheckTask != null) {
            boolean wasCancelled = proximityCheckTask.cancel(true); // Use interrupt to stop blocked operations
            boolean isDone = proximityCheckTask.isDone();
            proximityCheckTask = null;
            logger.info("[NpcGreetingService] Stopped (cancelled: " + wasCancelled + ", done: " + isDone + ") in " + 
                    (System.currentTimeMillis() - startTime) + "ms");
        } else {
            logger.info("[NpcGreetingService] No active task to stop");
        }
    }

    /**
     * Clear all greeted player records.
     * Call this on server restart/reload if needed.
     */
    public void clearGreetedPlayers() {
        greetedPlayers.clear();
        logger.debug("Cleared all greeted player records");
    }

    /**
     * Remove all greeted records for a specific NPC.
     */
    public void clearGreetedPlayersForNpc(UUID npcInstanceId) {
        greetedPlayers.removeIf(key -> key.startsWith(npcInstanceId.toString() + ":"));
    }

    /**
     * Check if a player has been greeted by an NPC.
     */
    public boolean hasBeenGreeted(UUID npcInstanceId, String playerId) {
        return greetedPlayers.contains(npcInstanceId.toString() + ":" + playerId);
    }

    /**
     * Mark a player as greeted by an NPC.
     */
    public void markAsGreeted(UUID npcInstanceId, String playerId) {
        greetedPlayers.add(npcInstanceId.toString() + ":" + playerId);
    }

    /**
     * Periodic check for players near NPCs.
     * Runs on a scheduled thread.
     */
    private void checkPlayerProximity() {


        // [Shutdown Fix] Check shutdown flag first
        if (isShutdown || Thread.currentThread().isInterrupted()) {
            logger.debug("Proximity check interrupted, skipping");
            return;
        }

        int greetingRange = config.gameplay().greetingRange();

        if (greetingRange <= 0) {
            logger.debug("Greeting range is 0, skipping proximity check");
            return; // Greeting disabled
        }

        // [Shutdown Fix] If no players are online, don't waste resources checking NPCs
        // This prevents blocking operations during server shutdown
        var onlinePlayers = hytaleAPI.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            //logger.debug("No online players, skipping proximity check");
            return;
        }

        // Check each spawned NPC
        for (NpcInstanceData npcInstanceData : hytaleAPI.getNpcInstances()) {

            if (Thread.currentThread().isInterrupted()) {
                logger.debug("Proximity check interrupted, skipping");
                return;
            }

            NpcData npc = npcInstanceData.npcData();
            // Skip NPCs without greeting
            if (!hasGreeting(npc)) {
                logger.debug("NPC [" + npcInstanceData.entityUuid() + "] has no greeting, skipping");
                continue;
            }

            // Skip NPCs not spawned or without location
            if (!npcInstanceData.isSpawned()) {
                logger.debug("NPC [" + npcInstanceData.entityUuid() + "] is not spawned, skipping");
                continue;
            }

            // Get live location
            var npcLoc = hytaleAPI.getNpcInstanceLocation(npcInstanceData.entityUuid());
            if (npcLoc.isEmpty()) {
                logger.debug("NPC [" + npcInstanceData.entityUuid() + "] has no location, skipping");
                continue;
            }

            // Get players near this NPC
            var nearbyPlayers = hytaleAPI.getNearbyPlayers(npcLoc.get(), greetingRange);

            for (GamePlayer player : nearbyPlayers) {
                // Check if this player has been greeted by this NPC
                if (!hasBeenGreeted(npcInstanceData.entityUuid(), player.id())) {
                    // First time encounter - greet the player!
                    greetPlayer(npcInstanceData.entityUuid(), player);
                }
            }
        }
    }

    /**
     * Check if an NPC has a greeting configured.
     */
    private boolean hasGreeting(NpcData npc) {
        return npc.greeting() != null && !npc.greeting().isEmpty();
    }

    /**
     * Perform the greeting action for a player.
     * 
     * 1. Rotate NPC toward player
     * 2. Send greeting message
     * 3. Mark as greeted
     */
    private void greetPlayer(UUID npcInstanceId, GamePlayer player) {
        logger.debug("Greeting player [" + player.name() + "] with NPC [" + npcInstanceId + "]");
        String playerId = player.id();
        NpcData npc = hytaleAPI.getNpcInstance(npcInstanceId).npcData();

        // Mark as greeted immediately to prevent duplicate greetings
        markAsGreeted(npcInstanceId, playerId);

        // Rotate toward the player
        if (player.location() != null) {
            hytaleAPI.rotateNpcInstanceToward(npcInstanceId, player.location());
        }

        // Format and send greeting message
        String greeting = npc.greeting();
        String prefix = config.gameplay().messagePrefix();
        // Remove legacy color codes
        prefix = prefix.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");
        String formattedMessage = prefix + npc.name() + ": " + greeting;

        hytaleAPI.sendNpcMessage(npcInstanceId, playerId, formattedMessage);

        logger.info("NPC [" + npcInstanceId + "] greeted player [" + player.name() + "]: " + greeting);
    }
}
