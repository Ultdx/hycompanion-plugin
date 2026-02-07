package dev.hycompanion.plugin.network;

import io.sentry.Sentry;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.ServerInfo;
import dev.hycompanion.plugin.config.NpcConfigManager;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.handlers.ActionExecutor;
import dev.hycompanion.plugin.handlers.ChatHandler;
import dev.hycompanion.plugin.role.RoleGenerator;
import dev.hycompanion.plugin.utils.PluginLogger;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import java.net.URI;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Socket.IO client manager for backend communication
 * 
 * Handles connection, reconnection, and event routing.
 * Uses Virtual Threads (Java 21+) for non-blocking event handling.
 */
public class SocketManager {

    private final String url;
    private String apiKey; // Not final to allow updates
    private final ServerInfo serverInfo;
    private final ActionExecutor actionExecutor;
    private final NpcManager npcManager;
    private final NpcConfigManager npcConfigManager;
    private final RoleGenerator roleGenerator;
    private final PluginLogger logger;
    private PluginConfig config; // Not final to allow updates
    private final HytaleAPI hytaleAPI;
    private final Gson gson;
    private ChatHandler chatHandler;

    private Socket socket;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean isReconnection = new AtomicBoolean(false);
    private ScheduledExecutorService reconnectScheduler;

    // Callback for post-reconnection entity discovery
    private Runnable onReconnectSyncComplete;

    // Flag to track intentional disconnects (to prevent auto-reconnect)
    private final AtomicBoolean intentionalDisconnect = new AtomicBoolean(false);

    // Cached blocks - discovered once and reused across reconnections
    private java.util.List<dev.hycompanion.plugin.core.world.BlockInfo> cachedBlocks = null;
    private final AtomicBoolean blocksSent = new AtomicBoolean(false);

    public SocketManager(
            String url,
            String apiKey,
            ServerInfo serverInfo,
            ActionExecutor actionExecutor,
            NpcManager npcManager,
            NpcConfigManager npcConfigManager,
            RoleGenerator roleGenerator,
            PluginLogger logger,
            PluginConfig config,
            HytaleAPI hytaleAPI) {
        this.url = url;
        this.apiKey = apiKey;
        this.serverInfo = serverInfo;
        this.actionExecutor = actionExecutor;
        this.npcManager = npcManager;
        this.npcConfigManager = npcConfigManager;
        this.roleGenerator = roleGenerator;
        this.logger = logger;
        this.config = config;
        this.hytaleAPI = hytaleAPI;
        this.gson = new Gson();
    }

    /**
     * Set callback for when reconnection sync completes.
     * Used to trigger entity discovery after NPCs are re-synced.
     * 
     * @param callback Runnable to execute after bulk sync on reconnect
     */
    public void setOnReconnectSyncComplete(Runnable callback) {
        this.onReconnectSyncComplete = callback;
    }

    /**
     * Update configuration
     */
    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    /**
     * Set ChatHandler for aborting operations on error
     */
    public void setChatHandler(ChatHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    /**
     * Update API key and reconnect
     */
    public void updateApiKey(String newApiKey) {
        if (newApiKey == null || newApiKey.equals(this.apiKey)) {
            return;
        }

        this.apiKey = newApiKey;
        logger.info("[Socket] Updating API Key and reconnecting...");

        // Force reconnect with new key
        disconnect();
        connect();
    }

    /**
     * Connect to backend
     */
    public void connect() {
        try {
            // Reset intentional disconnect flag
            intentionalDisconnect.set(false);

            // Configure Socket.IO options
            IO.Options options = IO.Options.builder()
                    .setAuth(Map.of("apiKey", apiKey))
                    .setReconnection(false) // We handle reconnection manually
                    .setTransports(new String[] { "websocket", "polling" })
                    .build();

            socket = IO.socket(URI.create(url), options);

            // Register event handlers
            registerEventHandlers();

            // Connect
            socket.connect();

        } catch (Exception e) {
            logger.error("Failed to create socket connection", e);
            Sentry.captureException(e);
            scheduleReconnect();
        }
    }

    /**
     * Disconnect from backend
     * Safe to call multiple times (idempotent).
     */
    public void disconnect() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();

        // Already disconnected
        if (socket == null && !connected.get()) {
            logger.debug("[Socket] Already disconnected (thread: " + threadName + ")");
            return;
        }

        logger.info("[Socket] Disconnecting from backend (thread: " + threadName + ")...");

        intentionalDisconnect.set(true);

        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
            logger.info("[Socket] Reconnect scheduler shut down");
        }

        if (socket != null) {
            try {
                // Remove listeners to prevent "disconnect" event triggering auto-reconnect
                socket.off();
                socket.disconnect();
                socket.close();
                logger.info("[Socket] Socket closed");
            } catch (NoClassDefFoundError e) {
                // During server shutdown, the plugin classloader may fail to load
                // inner classes (e.g., Socket$8). This is expected behavior during
                // plugin unload - just clean up our references.
                logger.debug("[Socket] NoClassDefFoundError during socket close (expected during shutdown): " + e.getMessage());
                Sentry.captureException(e);
            } catch (Exception e) {
                // Catch any other exceptions during close to ensure we always clean up
                logger.debug("[Socket] Exception during socket close: " + e.getMessage());
                Sentry.captureException(e);
            } finally {
                socket = null;
            }
        }

        connected.set(false);
        logger.info("[Socket] Disconnected from backend in " + (System.currentTimeMillis() - startTime) + "ms");
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Clear the cached blocks to force re-discovery on next connection.
     * Useful when block definitions change or for explicit reloads.
     */
    public void clearBlockCache() {
        cachedBlocks = null;
        blocksSent.set(false);
        logger.info("[Socket] Block cache cleared");
    }

    /**
     * Get the cached blocks if available.
     * 
     * @return Cached blocks or null if not yet discovered
     */
    public java.util.List<dev.hycompanion.plugin.core.world.BlockInfo> getCachedBlocks() {
        return cachedBlocks;
    }

    /**
     * Send chat message to backend
     * 
     * @param npcId           External NPC ID (template identifier)
     * @param npcInstanceUuid Entity UUID of the spawned NPC instance (null if not
     *                        spawned)
     * @param playerId        Player ID
     * @param playerName      Player display name
     * @param message         Chat message content
     * @param context         World context (location, time, nearby players)
     */
    public void sendChat(String npcId, String npcInstanceUuid, String playerId, String playerName, String message,
            JsonObject context) {
        if (!isConnected()) {
            logger.warn("Cannot send chat - not connected");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("npcId", npcId);
        if (npcInstanceUuid != null) {
            payload.addProperty("npcInstanceUuid", npcInstanceUuid);
        }
        payload.addProperty("playerId", playerId);
        payload.addProperty("playerName", playerName);
        payload.addProperty("message", message);
        payload.add("context", context != null ? context : new JsonObject());

        if (config.logging().logChat()) {
            logger.debug("Sending chat: " + playerId + " → " + npcId +
                    (npcInstanceUuid != null ? " (instance: " + npcInstanceUuid + ")" : "") +
                    ": " + message);
        }

        socket.emit(SocketEvents.PLUGIN_CHAT, new org.json.JSONObject(payload.toString()));
    }

    /**
     * Request NPC sync from backend
     */
    public void requestSync() {
        if (!isConnected()) {
            logger.warn("Cannot request sync - not connected");
            return;
        }

        socket.emit(SocketEvents.PLUGIN_REQUEST_SYNC, new org.json.JSONObject());
        logger.debug("NPC sync requested");
    }

    /**
     * Report available animations for an NPC to the backend.
     * This is sent after an NPC entity is discovered/spawned so the backend
     * can build dynamic MCP tools for animations.
     * 
     * @param npcId      External NPC ID
     * @param animations List of animation names from the model's AnimationSets
     */
    public void sendNpcAnimations(String npcId, java.util.List<String> animations) {
        if (!isConnected()) {
            logger.warn("Cannot send NPC animations - not connected");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("npcId", npcId);

        // Convert List to JSONArray
        com.google.gson.JsonArray animArray = new com.google.gson.JsonArray();
        for (String anim : animations) {
            animArray.add(anim);
        }
        payload.add("animations", animArray);

        socket.emit(SocketEvents.PLUGIN_NPC_ANIMATIONS, new org.json.JSONObject(payload.toString()));
        logger.info("[Socket] Sent " + animations.size() + " animations for NPC: " + npcId);
    }

    /**
     * Report available blocks on the server to the backend.
     * This is sent once on startup so the backend can build a searchable
     * block catalog for LLM queries like "find wood" or "find stone".
     * 
     * @param blocks List of BlockInfo objects with enriched block metadata
     */
    public void sendAvailableBlocks(java.util.List<dev.hycompanion.plugin.core.world.BlockInfo> blocks) {
        if (!isConnected()) {
            logger.warn("Cannot send available blocks - not connected");
            return;
        }

        if (blocks == null || blocks.isEmpty()) {
            logger.warn("No blocks to send to backend");
            return;
        }

        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();

        // Convert BlockInfo list to JSON array
        com.google.gson.JsonArray blocksArray = new com.google.gson.JsonArray();
        for (dev.hycompanion.plugin.core.world.BlockInfo block : blocks) {
            com.google.gson.JsonObject blockObj = new com.google.gson.JsonObject();
            blockObj.addProperty("blockId", block.blockId());
            blockObj.addProperty("displayName", block.displayName());

            // Material types array
            com.google.gson.JsonArray materialsArray = new com.google.gson.JsonArray();
            for (String material : block.materialTypes()) {
                materialsArray.add(material);
            }
            blockObj.add("materialTypes", materialsArray);

            // Keywords array
            com.google.gson.JsonArray keywordsArray = new com.google.gson.JsonArray();
            for (String keyword : block.keywords()) {
                keywordsArray.add(keyword);
            }
            
            blockObj.add("keywords", keywordsArray);

            // Categories array (may be empty)
            com.google.gson.JsonArray categoriesArray = new com.google.gson.JsonArray();
            for (String category : block.categories()) {
                categoriesArray.add(category);
            }
            blockObj.add("categories", categoriesArray);

            blocksArray.add(blockObj);
        }
        payload.add("blocks", blocksArray);

        // Add summary stats
        payload.addProperty("totalCount", blocks.size());

        // Count by material type
        com.google.gson.JsonObject materialStats = new com.google.gson.JsonObject();
        java.util.Map<String, Integer> materialCounts = new java.util.HashMap<>();
        for (dev.hycompanion.plugin.core.world.BlockInfo block : blocks) {
            for (String material : block.materialTypes()) {
                materialCounts.merge(material, 1, Integer::sum);
            }
        }
        for (java.util.Map.Entry<String, Integer> entry : materialCounts.entrySet()) {
            materialStats.addProperty(entry.getKey(), entry.getValue());
        }
        payload.add("materialStats", materialStats);

        socket.emit(SocketEvents.PLUGIN_BLOCKS_AVAILABLE, new org.json.JSONObject(payload.toString()));
        logger.info("[Socket] Sent " + blocks.size() + " available blocks to backend");
    }

    /**
     * Register all socket event handlers
     */
    private void registerEventHandlers() {
        // Connection established
        socket.on(Socket.EVENT_CONNECT, args -> {
            // Use Virtual Thread for non-blocking handling
            Thread.ofVirtual().start(() -> onConnect());
        });

        // Connection error
        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            Thread.ofVirtual().start(() -> onConnectError(args));
        });

        // Disconnection
        socket.on(Socket.EVENT_DISCONNECT, args -> {
            Thread.ofVirtual().start(() -> onDisconnect(args));
        });

        // Backend action (MCP tool result)
        socket.on(SocketEvents.BACKEND_ACTION, args -> {
            Thread.ofVirtual().start(() -> onAction(args));
        });

        // NPC sync
        socket.on(SocketEvents.BACKEND_NPC_SYNC, args -> {
            Thread.ofVirtual().start(() -> onNpcSync(args));
        });

        // Error from backend
        socket.on(SocketEvents.BACKEND_ERROR, args -> {
            Thread.ofVirtual().start(() -> onError(args));
        });

        // Chain-of-thought status update
        socket.on(SocketEvents.BACKEND_COT_UPDATE, args -> {
            Thread.ofVirtual().start(() -> onCotUpdate(args));
        });
    }

    /**
     * Handle successful connection
     */
    private void onConnect() {
        boolean wasReconnect = reconnectAttempts.get() > 0;
        connected.set(true);
        reconnectAttempts.set(0);
        isReconnection.set(wasReconnect); // Track reconnection state for sync callback

        if (wasReconnect) {
            logger.info("[Socket] Reconnected to backend! NPCs will be re-synced.");
        } else {
            logger.info("[Socket] Connected to backend!");
        }

        // Send connection payload
        JsonObject payload = new JsonObject();
        payload.addProperty("apiKey", apiKey);

        JsonObject serverInfoJson = new JsonObject();
        serverInfoJson.addProperty("version", serverInfo.version());
        serverInfoJson.addProperty("playerCount", serverInfo.playerCount());
        payload.add("serverInfo", serverInfoJson);

        logger.info("[Socket] Sending PLUGIN_CONNECT with apiKey and serverInfo");
        socket.emit(SocketEvents.PLUGIN_CONNECT, new org.json.JSONObject(payload.toString()));

        // Send available blocks to backend after a short delay to ensure connection is
        // ready
        // This enables LLM to understand what blocks exist on this server
        // Blocks are discovered once and cached, then sent on each connection
        // (including reconnections)
        logger.info("[Socket] Will report available blocks in 2 seconds...");
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(2000); // Wait for connection to be fully established
                if (!isConnected()) {
                    logger.debug("[Socket] No longer connected, skipping block report");
                    return;
                }

                // Discover blocks once and cache them
                if (cachedBlocks == null) {
                    logger.info("[Socket] Discovering blocks for the first time...");
                    cachedBlocks = hytaleAPI.getAvailableBlocks();
                    if (cachedBlocks == null || cachedBlocks.isEmpty()) {
                        logger.warn("[Socket] No blocks discovered");
                        return;
                    }
                    logger.info("[Socket] Discovered and cached " + cachedBlocks.size() + " blocks");
                }

                // Send cached blocks (on every connection, including reconnections)
                if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
                    sendAvailableBlocks(cachedBlocks);
                    blocksSent.set(true);
                }
                // } catch (InterruptedException e) {
                // Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("[Socket] Error reporting available blocks: " + e.getMessage());
                Sentry.captureException(e);
            }
        });
    }

    /**
     * Handle connection error
     */
    private void onConnectError(Object[] args) {
        connected.set(false);

        String errorMsg = args.length > 0 ? args[0].toString() : "Unknown error";
        logger.error("Connection error: " + errorMsg);

        scheduleReconnect();
    }

    /**
     * Handle disconnection
     */
    private void onDisconnect(Object[] args) {
        connected.set(false);

        String reason = args.length > 0 ? args[0].toString() : "Unknown reason";

        // If this was an intentional disconnect (e.g. shutdown), do not reconnect
        if (intentionalDisconnect.get()) {
            // logger.info("Disconnected from backend (intentional): " + reason);
            return;
        }

        logger.warn("Disconnected from backend: " + reason);

        scheduleReconnect();
    }

    /**
     * Check if server is shutting down - if so, skip processing
     */
    private boolean isShuttingDown() {
        // Check the static shutdown flag from HycompanionEntrypoint
        try {
            return dev.hycompanion.plugin.HycompanionEntrypoint.isShuttingDown();
        } catch (Exception e) {
            Sentry.captureException(e);
            return false;
        }
    }

    /**
     * Handle action from backend (MCP tool execution)
     */
    private void onAction(Object[] args) {
        if (args.length == 0)
            return;

        // CRITICAL: Skip processing actions during shutdown to prevent
        // interfering with Hytale's world thread shutdown sequence
        if (isShuttingDown()) {
            logger.debug("[Socket] Skipping action processing - server shutting down");
            // Call ack with error if present to prevent client hanging
            if (args.length > 1 && args[args.length - 1] instanceof io.socket.client.Ack) {
                io.socket.client.Ack ack = (io.socket.client.Ack) args[args.length - 1];
                ack.call("{\"error\": \"Server shutting down\"}");
            }
            return;
        }

        try {
            org.json.JSONObject json = (org.json.JSONObject) args[0];

            // Check for acknowledgement callback
            io.socket.client.Ack ack = null;
            if (args.length > 1 && args[args.length - 1] instanceof io.socket.client.Ack) {
                ack = (io.socket.client.Ack) args[args.length - 1];
            }

            String npcInstanceUuidStr = json.optString("npcInstanceUuid");

            // Get npcId from npcInstanceUuidStr

            java.util.UUID npcInstanceId;
            if (npcInstanceUuidStr != null && !npcInstanceUuidStr.isEmpty()) {
                npcInstanceId = java.util.UUID.fromString(npcInstanceUuidStr);
            } else {
                logger.error("No npc instance found for UUID: " + npcInstanceUuidStr);
                if (ack != null)
                    ack.call("{\"error\": \"Missing/Invalid npcInstanceUuid\"}");
                return;
            }

            Optional<NpcData> npcData = this.npcManager.getNpcByEntityUuid(npcInstanceId);

            if (npcData.isEmpty()) {
                logger.error("No npc instance found for UUID: " + npcInstanceId);
                // We still pass it to executor because executor handles 'unknown NPC' logic
                // with optional Ack now
                // via execute(...) call below.
            }

            String playerId = json.getString("playerId");
            String action = json.getString("action");
            org.json.JSONObject params = json.optJSONObject("params");

            if (config.logging().logActions()) {
                logger.debug("Action received: " + action + " for NPC " + npcInstanceId + " (template: "
                        + (npcData.isPresent() ? npcData.get().externalId() : "unknown") + ")");
            }

            actionExecutor.execute(npcInstanceId, playerId, action, params, ack);

        } catch (Exception e) {
            logger.error("Failed to process action", e);
            Sentry.captureException(e);
        }
    }

    /**
     * Handle NPC sync from backend
     */
    private void onNpcSync(Object[] args) {
        if (args.length == 0)
            return;

        try {
            org.json.JSONObject json = (org.json.JSONObject) args[0];

            String syncAction = json.getString("action");

            switch (syncAction) {
                case "bulk_create" -> {
                    // Handle batch NPC sync for lower latency
                    org.json.JSONArray npcsArray = json.getJSONArray("npcs");
                    for (int i = 0; i < npcsArray.length(); i++) {
                        handleNpcCreateOrUpdate(npcsArray.getJSONObject(i));
                    }
                    logger.info("Bulk synced " + npcsArray.length() + " NPCs");

                    // Trigger entity discovery after reconnection bulk sync
                    if (isReconnection.compareAndSet(true, false) && onReconnectSyncComplete != null) {
                        logger.info("[Socket] Triggering entity discovery after reconnection sync...");
                        Thread.ofVirtual().start(onReconnectSyncComplete);
                    }
                }
                case "create", "update" -> handleNpcCreateOrUpdate(json.getJSONObject("npc"));
                case "delete" -> handleNpcDelete(json.getJSONObject("npc"));
                default -> logger.warn("Unknown sync action: " + syncAction);
            }

        } catch (Exception e) {
            logger.error("Failed to process NPC sync", e);
            Sentry.captureException(e);
        }
    }

    /**
     * Handle NPC create/update sync
     */
    private void handleNpcCreateOrUpdate(org.json.JSONObject json) {
        try {
            logger.info("Received NPC raw data: " + json.toString());
            String id = json.getString("id");
            String externalId = json.getString("externalId");
            String name = json.getString("name");
            Number chatDistance = json.getNumber("chatDistance");
            String personality = json.getString("personality");
            // Greeting is optional - null means NPC won't greet players
            String greeting = json.isNull("greeting") ? null : json.optString("greeting", null);
            // Convert empty string to null for consistency
            if (greeting != null && greeting.isEmpty()) {
                greeting = null;
            }
            String alignment = json.optString("alignment", "neutral");

            // Parse moral profile
            NpcData.MoralProfile moralProfile = NpcData.MoralProfile.DEFAULT;
            if (json.has("moralProfile")) {
                org.json.JSONObject mpJson = json.getJSONObject("moralProfile");
                var ideals = new java.util.ArrayList<String>();
                if (mpJson.has("ideals")) {
                    org.json.JSONArray idealsArray = mpJson.getJSONArray("ideals");
                    for (int i = 0; i < idealsArray.length(); i++) {
                        ideals.add(idealsArray.getString(i));
                    }
                }
                String resistance = mpJson.optString("persuasionResistance", "strong");
                moralProfile = new NpcData.MoralProfile(ideals, resistance);
            }

            boolean isInvincible = json.optBoolean("isInvincible", false);
            boolean preventKnockback = json.optBoolean("preventKnockback", false);
            boolean broadcastReplies = json.optBoolean("broadcastReplies", false);

            // Create NPC data
            NpcData npc = NpcData.fromSync(id, externalId, name, personality, greeting, chatDistance, broadcastReplies, alignment,
                    moralProfile, isInvincible, preventKnockback);

            // Register in managers
            npcManager.registerNpc(npc);
            npcConfigManager.upsertNpc(npc);

            // Update capabilities of any existing instances
            if (hytaleAPI != null) {
                hytaleAPI.updateNpcCapabilities(externalId, npc);
            }

            // Generate and cache role file for next startup
            if (roleGenerator != null) {
                // Pass the full JSON to handle root properties like preventKnockback
                RoleGenerator.NpcRoleData roleData = RoleGenerator.NpcRoleData.fromNpcJson(json);
                if (roleGenerator.generateAndCacheRole(roleData)) {
                    logger.debug("Role file cached for: " + externalId);
                }
            }

            logger.info("NPC synced: " + externalId + " (" + name + ")");

            // Entity discovery for persisted NPCs now happens via AllNPCsLoadedEvent
            // in HycompanionEntrypoint. This ensures we discover entities AFTER Hytale
            // has fully loaded all NPC entities, rather than using arbitrary delays.

        } catch (Exception e) {
            logger.error("Failed to create/update NPC", e);
            Sentry.captureException(e);
        }
    }

    /**
     * Handle NPC delete sync
     */
    private void handleNpcDelete(org.json.JSONObject json) {
        try {
            String externalId = json.getString("externalId");

            npcManager.unregisterNpc(externalId);
            npcConfigManager.removeNpc(externalId);

            // Remove cached role file
            if (roleGenerator != null) {
                roleGenerator.removeRole(externalId);
            }

            logger.info("NPC removed: " + externalId);

        } catch (Exception e) {
            logger.error("Failed to delete NPC", e);
            Sentry.captureException(e);
        }
    }

    /**
     * Handle error from backend
     */
    private void onError(Object[] args) {
        if (args.length == 0)
            return;

        try {
            org.json.JSONObject json = (org.json.JSONObject) args[0];

            String code = json.optString("code", "UNKNOWN");
            String message = json.optString("message", "Unknown error");
            String npcInstanceUuidStr = json.optString("npcInstanceUuid", json.optString("npcInstanceId", null));

            java.util.UUID npcInstanceId = null;
            if (npcInstanceUuidStr != null && !npcInstanceUuidStr.isEmpty()) {
                npcInstanceId = java.util.UUID.fromString(npcInstanceUuidStr);
            }

            String playerId = json.optString("playerId", null);

            logger.error("Backend error [" + code + "]: " + message);

            // Handle specific errors
            if ("INVALID_API_KEY".equals(code)) {
                logger.error("Invalid API key! Please check your config.yml");
                disconnect();
                return;
            }

            // LLM errors - display red error message in player chat and abort operation
            if ("LLM_ERROR".equals(code) && playerId != null && npcInstanceId != null) {
                // Abort the current world operation for this NPC
                if (chatHandler != null) {
                    chatHandler.abortOperation(npcInstanceId);
                }

                // Get NPC name for display
                String npcName = npcInstanceId.toString();
                var npcOpt = npcManager.getNpcByEntityUuid(npcInstanceId);
                if (npcOpt.isPresent()) {
                    npcName = npcOpt.get().name();
                }

                // Send red error message to player with the actual error details
                // The error message from backend contains the actual error (e.g., "Error: 400 Provider returned error")
                String errorMessage = "[" + npcName + "] Error: " + message;
                actionExecutor.execute(npcInstanceId, playerId, "error_message",
                        new org.json.JSONObject().put("message", errorMessage));
            }

        } catch (Exception e) {
            logger.error("Failed to process error", e);
            Sentry.captureException(e);
        }
    }

    /**
     * Handle chain-of-thought status update from backend
     * Shows dynamic thinking indicators above NPC heads
     */
    private void onCotUpdate(Object[] args) {
        if (args.length == 0)
            return;

        try {
            org.json.JSONObject json = (org.json.JSONObject) args[0];

            String npcInstanceUuidStr = json.optString("npcInstanceUuid");
            String type = json.optString("type", "thinking");
            String message = json.optString("message", "Thinking");
            String toolName = json.optString("toolName", "");

            if (npcInstanceUuidStr == null || npcInstanceUuidStr.isEmpty()) {
                logger.debug("CoT update missing npcInstanceUuid");
                return;
            }

            UUID npcInstanceId;
            try {
                npcInstanceId = UUID.fromString(npcInstanceUuidStr);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid npcInstanceUuid in CoT update: " + npcInstanceUuidStr);
                Sentry.captureException(e);
                return;
            }

            // Build display text based on type
            String displayText;
            switch (type) {
                case "thinking":
                    displayText = message != null && !message.isEmpty() ? message : "Thinking";
                    break;
                case "tool_executing":
                    displayText = (message != null && !message.isEmpty()) ? message : 
                                  (toolName != null && !toolName.isEmpty() ? "Executing: " + toolName : "Working...");
                    break;
                case "tool_completed":
                    displayText = message != null && !message.isEmpty() ? message : "Done";
                    break;
                case "tool_failed":
                    displayText = message != null && !message.isEmpty() ? message : "Failed";
                    break;
                case "completed":
                    displayText = message != null && !message.isEmpty() ? message : "Done";
                    break;
                default:
                    displayText = message != null && !message.isEmpty() ? message : "Thinking";
            }

            // Show/hide thinking indicator based on type
            if ("completed".equals(type) || "tool_completed".equals(type) || "tool_failed".equals(type)) {
                // Hide after a brief delay to show completion
                hytaleAPI.hideThinkingIndicator(npcInstanceId);
            } else if("tool_executing".equals(type)) {
                // Display thinking indicator
                hytaleAPI.showThinkingIndicator(npcInstanceId);
            }

            if (config.logging().logActions()) {
                logger.debug("CoT update [" + type + "] for NPC " + npcInstanceId + ": " + displayText);
            }

        } catch (Exception e) {
            logger.error("Failed to process CoT update", e);
            Sentry.captureException(e);
        }
    }

    /**
     * Schedule reconnection attempt
     */
    private void scheduleReconnect() {
        if (!config.connection().reconnectEnabled()) {
            logger.info("Reconnection disabled");
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        // Removed max attempts check - try to reconnect indefinitely

        int delay = config.connection().reconnectDelayMs();
        logger.info("Reconnecting in " + delay + "ms (attempt " + attempts + ")");

        if (reconnectScheduler == null || reconnectScheduler.isShutdown()) {
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Hycompanion-Socket-Reconnect");
                t.setDaemon(true); // daemon thread
                return t;
            });
        }

        reconnectScheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }
}
