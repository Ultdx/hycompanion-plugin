package dev.hycompanion.plugin.handlers;

import io.sentry.Sentry;

import com.google.gson.JsonObject;
import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.core.context.ContextBuilder;
import dev.hycompanion.plugin.core.context.WorldContext;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.core.npc.NpcSearchResult;
import dev.hycompanion.plugin.network.SocketManager;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Handles chat messages from players to NPCs
 * 
 * Intercepts player chat, checks for nearby NPCs within range,
 * gathers context, and sends the message to the backend for processing.
 * 
 * TODO: [HYTALE-API] This should be connected to Hytale's chat event system
 */
public class ChatHandler {

    private final NpcManager npcManager;
    private final ContextBuilder contextBuilder;
    private final PluginLogger logger;
    private PluginConfig config;
    private SocketManager socketManager;
    private HytaleAPI hytaleAPI;

    // Track queues per NPC
    private final Map<UUID, Queue<ChatRequest>> npcQueues = new ConcurrentHashMap<>();
    // Track which NPCs are currently waiting for backend response
    private final Set<UUID> processingNpcs = ConcurrentHashMap.newKeySet();
    // Track pending timeout tasks per NPC
    private final Map<UUID, ScheduledFuture<?>> pendingTimeouts = new ConcurrentHashMap<>();

    // Timeout for backend responses (60 seconds)
    private static final long BACKEND_TIMEOUT_SECONDS = 60;
    // Scheduler for timeout tasks
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "Hycompanion-ChatTimeout");
        t.setDaemon(true);
        return t;
    });

    private record ChatRequest(GamePlayer player, String message) {
    }

    public ChatHandler(NpcManager npcManager, ContextBuilder contextBuilder, PluginLogger logger, PluginConfig config) {
        this.npcManager = npcManager;
        this.contextBuilder = contextBuilder;
        this.logger = logger;
        this.config = config;
    }

    /**
     * Set Hytale API (injected after construction for emote support)
     */
    public void setHytaleAPI(HytaleAPI hytaleAPI) {
        this.hytaleAPI = hytaleAPI;
    }

    /**
     * Set socket manager (injected after construction)
     */
    public void setSocketManager(SocketManager socketManager) {
        this.socketManager = socketManager;
    }

    /**
     * Set updated config (for reload)
     */
    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    /**
     * Handle a chat message from a player
     * 
     * This method should be called from Hytale's chat event listener.
     * 
     * @param player  The player sending the message
     * @param message The chat message content
     * @return True if the message was handled (sent to NPC), false otherwise
     */
    public boolean handleChat(GamePlayer player, String message) {
        logger.info("[ChatHandler] handleChat called for player: " + player.name() + ", message: " + message);

        if (socketManager == null || !socketManager.isConnected()) {
            logger.warn("[ChatHandler] Socket not connected, ignoring chat (socketManager=" +
                    (socketManager == null ? "null" : "not connected") + ")");
            return false;
        }

        if (message == null || message.isBlank()) {
            return false;
        }

        // Find NPCs near the player
        Location playerLocation = player.location();

        // Always fetch fresh player location from API if possible
        // The GamePlayer record passed in might be slightly stale (snapshot)
        if (hytaleAPI != null) {
            Optional<GamePlayer> freshPlayer = hytaleAPI.getPlayer(player.id());
            if (freshPlayer.isPresent()) {
                player = freshPlayer.get();
                playerLocation = player.location();
            }
        }

        if (playerLocation == null) {
            logger.warn("[ChatHandler] Player location unknown, cannot find nearby NPCs");
            return false;
        }

        logger.info("[ChatHandler] Player location: " + playerLocation.toCoordString() +
                ", checking " + npcManager.getNpcCount() + " registered NPCs");

        // Get nearby NPCs - first try with tracked locations, then fallback to
        // real-time location query
        List<NpcSearchResult> nearbyNpcs = findNpcsNearPlayer(playerLocation);

        if (nearbyNpcs.isEmpty()) {
            logger.info("[ChatHandler] No NPCs near player: " + player.name());
            return false;
        }

        // Find closest NPC
        NpcInstanceData closestNpc = findClosestNpc(nearbyNpcs, playerLocation);
        if (closestNpc == null) {
            return false;
        }

        // Enqueue the request instead of sending immediately
        UUID npcId = closestNpc.entityUuid();
        if (npcId != null) {
            npcQueues.computeIfAbsent(npcId, k -> new ConcurrentLinkedQueue<>())
                    .add(new ChatRequest(player, message));

            logger.info("[ChatHandler] Enqueued chat from " + player.name() + " for NPC " + npcId +
                    ". Queue size: " + npcQueues.get(npcId).size());

            processQueue(npcId, closestNpc);
        } else {
            logger.warn("[ChatHandler] Cannot enqueue chat - NPC has no entity UUID");
        }

        return true;
    }

    /**
     * Process the next item in the NPC's chat queue
     */
    private void processQueue(UUID npcId, NpcInstanceData npcInstance) {
        if (npcId == null)
            return;

        // If already processing a request, wait for it to finish (ActionExecutor will
        // call onNpcAction)
        if (processingNpcs.contains(npcId)) {
            logger.debug("[ChatHandler] NPC " + npcId + " is busy processing a request, waiting...");
            return;
        }

        Queue<ChatRequest> queue = npcQueues.get(npcId);
        if (queue == null || queue.isEmpty()) {
            return;
        }

        // Mark as processing
        processingNpcs.add(npcId);
        ChatRequest request = queue.poll();

        if (request == null) {
            processingNpcs.remove(npcId);
            return;
        }

        // Schedule timeout for this request
        scheduleTimeout(npcId);

        // Rotate idle NPCs to face the player when processing
        if (hytaleAPI != null) {
            hytaleAPI.rotateNpcInstanceToward(npcId, request.player.location());
        }

        // Ensure thinking indicator is ON while processing
        // This stays on as long as the queue is being processed
        if (hytaleAPI != null) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    hytaleAPI.showThinkingIndicator(npcId);

                    // Also trigger thinking emote if enabled (optional)
                    if (config.gameplay().emotesEnabled()) {
                        hytaleAPI.triggerNpcEmote(npcId, "thinking");
                    }
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });
        }

        // Build context (lightweight, keep sync)
        WorldContext context = contextBuilder.buildContext(request.player.location());
        JsonObject contextJson = context.toJson();

        // Log chat message being sent (async to not block)
        if (config.logging().logChat()) {
            final String logMsg = "[" + request.player.name() + "] → [" + npcInstance.npcData().name() + "]: "
                    + request.message;
            java.util.concurrent.CompletableFuture.runAsync(() -> logger.info(logMsg));
        }

        // Get NPC instance UUID (entity UUID)
        String npcInstanceUuid = npcId.toString();

        // Send to backend via socket
        logger.info("[Socket] Sending PLUGIN_CHAT to backend: npcId=" + npcInstance.npcData().externalId() +
                ", instanceUuid=" + npcInstanceUuid +
                ", playerId=" + request.player.id() + ", playerName=" + request.player.name() +
                ", messageLength=" + request.message.length());

        socketManager.sendChat(
                npcInstance.npcData().externalId(),
                npcInstanceUuid,
                request.player.id(),
                request.player.name(),
                request.message,
                contextJson);
    }

    /**
     * Callback when an NPC performs an action (message received from backend).
     * This signals that the current request is "done" (or at least the NPC has
     * responded),
     * so we can process the next item in the queue.
     */
    public void onNpcAction(UUID npcId) {
        if (npcId == null)
            return;

        logger.debug("[ChatHandler] Action received for NPC " + npcId + ", advancing queue");

        // Cancel any pending timeout
        cancelTimeout(npcId);

        // Mark current request as done
        processingNpcs.remove(npcId);

        Queue<ChatRequest> queue = npcQueues.get(npcId);

        // If more items in queue, process next one
        if (queue != null && !queue.isEmpty()) {
            // Need NpcInstanceData again - fetch it
            NpcInstanceData npcInstance = hytaleAPI.getNpcInstance(npcId);
            if (npcInstance != null) {
                processQueue(npcId, npcInstance);
            } else {
                // Should not happen if NPC is still valid
                processingNpcs.remove(npcId); // Just to be safe
            }
        } else {
            // Queue empty - NPC is done thinking
            if (hytaleAPI != null) {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        hytaleAPI.hideThinkingIndicator(npcId);
                    } catch (Exception e) {
                        Sentry.captureException(e);
                    }
                });
            }
        }
    }

    /**
     * Abort the current operation for an NPC and clear its queue.
     * Called when a backend error occurs (e.g., LLM_ERROR).
     * 
     * @param npcId The NPC instance UUID
     */
    public void abortOperation(UUID npcId) {
        if (npcId == null)
            return;

        logger.info("[ChatHandler] Aborting operation for NPC " + npcId);

        // Cancel any pending timeout
        cancelTimeout(npcId);

        // Remove from processing set
        processingNpcs.remove(npcId);

        // Clear the queue for this NPC
        Queue<ChatRequest> queue = npcQueues.get(npcId);
        if (queue != null) {
            int cleared = queue.size();
            queue.clear();
            logger.info("[ChatHandler] Cleared " + cleared + " pending request(s) for NPC " + npcId);
        }

        // Hide thinking indicator
        if (hytaleAPI != null) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    hytaleAPI.hideThinkingIndicator(npcId);
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });
        }
    }

    /**
     * Schedule a timeout for a pending backend request.
     * If no response arrives within BACKEND_TIMEOUT_SECONDS, the operation is aborted.
     */
    private void scheduleTimeout(UUID npcId) {
        // Cancel any existing timeout first
        cancelTimeout(npcId);

        ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
            logger.warn("[ChatHandler] Backend response timeout for NPC " + npcId +
                    " after " + BACKEND_TIMEOUT_SECONDS + " seconds");

            // Remove from pending timeouts (task is complete)
            pendingTimeouts.remove(npcId);

            // Abort the operation to clear state and queue
            abortOperation(npcId);

            // Optionally notify the player
            Queue<ChatRequest> queue = npcQueues.get(npcId);
            if (queue != null && !queue.isEmpty()) {
                ChatRequest request = queue.peek();
                if (request != null && hytaleAPI != null) {
                    hytaleAPI.sendErrorMessage(request.player.id(),
                            "[NPC] Request timed out. Please try again.");
                }
            }
        }, BACKEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        pendingTimeouts.put(npcId, timeoutTask);
        logger.debug("[ChatHandler] Scheduled timeout for NPC " + npcId +
                " (" + BACKEND_TIMEOUT_SECONDS + "s)");
    }

    /**
     * Cancel any pending timeout for an NPC.
     */
    private void cancelTimeout(UUID npcId) {
        ScheduledFuture<?> existingTask = pendingTimeouts.remove(npcId);
        if (existingTask != null) {
            existingTask.cancel(false);
            logger.debug("[ChatHandler] Cancelled timeout for NPC " + npcId);
        }
    }

    /**
     * Shutdown the timeout scheduler. Should be called on plugin disable.
     */
    public void shutdown() {
        logger.info("[ChatHandler] Shutting down timeout scheduler");
        timeoutScheduler.shutdownNow();
        pendingTimeouts.clear();
    }

    /**
     * Find NPC instances near a player where the player is within the NPC's chat
     * range.
     * 
     * Uses NpcManager's spatial index with per-NPC range checking.
     * 
     * @param playerLocation The player's current location
     * @return List of NPC instances within chat range
     */
    private List<NpcSearchResult> findNpcsNearPlayer(Location playerLocation) {
        // Use global config as default if NPC has no specific range

        logger.info("[ChatHandler] Searching for NPCs near "
                + playerLocation.toCoordString());

        // Use NpcManager's spatial index with per-NPC range check
        List<NpcSearchResult> nearbyNpcs = npcManager.getNpcsNear(playerLocation, 20);

        logger.info("[ChatHandler] Found " + nearbyNpcs.size() + " NPCs within range");
        return nearbyNpcs;
    }

    // /**
    // * Handle a chat message directed at a specific NPC
    // *
    // * @param player The player sending the message
    // * @param npcInstanceUuid Target NPC's instance UUID
    // * @param message The chat message content
    // * @return True if the message was sent, false otherwise
    // */
    // public boolean handleDirectChat(GamePlayer player, UUID npcInstanceUuid,
    // String message) {
    // if (socketManager == null || !socketManager.isConnected()) {
    // logger.debug("Socket not connected, ignoring chat");
    // return false;
    // }

    // if (message == null || message.isBlank()) {
    // return false;
    // }

    // // Verify NPC exists
    // Optional<NpcData> npcOpt = npcManager.getNpc(npcExternalId);
    // if (npcOpt.isEmpty()) {
    // logger.debug("NPC not found: " + npcExternalId);
    // return false;
    // }

    // NpcData npc = npcOpt.get();

    // // Rotate idle NPCs to face the player on direct chat
    // if (hytaleAPI != null && player.location() != null) {
    // hytaleAPI.rotateNpcInstanceToward(npc.externalId(), player.location());
    // }

    // // Build context
    // WorldContext context = contextBuilder.buildContext(player.location());
    // JsonObject contextJson = context.toJson();

    // // Get NPC instance UUID (entity UUID) for per-instance memory tracking
    // String npcInstanceUuid = npc.entityUuid() != null
    // ? npc.entityUuid().toString()
    // : null;

    // // Send to backend
    // if (config.logging().logChat()) {
    // logger.info("[" + player.name() + "] → [" + npc.name() + "]: " + message);
    // }

    // socketManager.sendChat(
    // npc.externalId(),
    // npcInstanceUuid,
    // player.id(),
    // player.name(),
    // message,
    // contextJson);

    // return true;
    // }

    /**
     * Find the closest NPC to a location
     */
    private NpcInstanceData findClosestNpc(List<NpcSearchResult> npcResults, Location location) {
        NpcInstanceData closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (NpcSearchResult result : npcResults) {
            // We already have the precise location and distance from NpcManager search
            // No need to query API again (which risks timeout)

            if (result.distance() < closestDistance) {
                closestDistance = result.distance();
                closest = result.instance();
            }
        }

        if (closest != null) {
            logger.info(
                    "[ChatHandler] findClosestNpc Selected: " + closest.entityUuid() + " Distance: " + closestDistance);
        }

        // Fallback if list was not empty but somehow no closest found (should not
        // happen)
        return closest != null ? closest : (!npcResults.isEmpty() ? npcResults.get(0).instance() : null);
    }
}
