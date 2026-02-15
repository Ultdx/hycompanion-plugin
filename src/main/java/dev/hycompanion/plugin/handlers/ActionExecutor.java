package dev.hycompanion.plugin.handlers;

import io.sentry.Sentry;

import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.utils.PluginLogger;
import org.json.JSONObject;

import java.util.Optional;
import java.util.UUID;

/**
 * Executes MCP tool actions received from the backend
 * 
 * All NPC output (text messages, emotes, trade, quest offers) comes through
 * here
 * as results of MCP tool calls made by the LLM.
 * 
 * @see dev.hycompanion.plugin.network.SocketManager
 */
public class ActionExecutor {

    private final HytaleAPI hytaleAPI;
    private final NpcManager npcManager;
    private final PluginLogger logger;
    private PluginConfig config;
    private ChatHandler chatHandler;

    public ActionExecutor(HytaleAPI hytaleAPI, NpcManager npcManager, PluginLogger logger, PluginConfig config) {
        this.hytaleAPI = hytaleAPI;
        this.npcManager = npcManager;
        this.logger = logger;
        this.config = config;
    }

    /**
     * Set updated config (for reload)
     */
    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    /**
     * Set ChatHandler to notify on action completion
     */
    public void setChatHandler(ChatHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    /**
     * Execute an action received from backend
     * 
     * @param npcInstanceId NPC instance UUID
     * @param playerId      Target player ID
     * @param action        Action type (say, emote, open_trade, give_quest,
     *                      move_to)
     * @param params        Action parameters
     */
    /**
     * Execute an action received from backend with optional acknowledgement
     * 
     * @param npcInstanceId NPC instance UUID
     * @param playerId      Target player ID
     * @param action        Action type
     * @param params        Action parameters
     * @param ack           Optional acknowledgment callback
     */
    public void execute(UUID npcInstanceId, String playerId, String action, JSONObject params,
            io.socket.client.Ack ack) {
        // We do NOT hide thinking indicator here anymore.
        // It is managed by ChatHandler based on the queue state.

        // Handle error_message action first - it doesn't require NPC instance
        // This ensures error messages are always sent to the player even if NPC is not
        // found
        if ("error_message".equals(action)) {
            try {
                executeErrorMessage(npcInstanceId, playerId, params);
                if (ack != null)
                    ack.call("{\"status\": \"success\"}");
            } catch (Exception e) {
                logger.error("Failed to execute error_message action", e);
                Sentry.captureException(e);
                if (ack != null)
                    ack.call("{\"error\": \"Exception: " + e.getMessage() + "\"}");
            }
            // Don't notify chatHandler for error messages - they don't advance the queue
            return;
        }

        // Resolve NPC Instance
        NpcInstanceData npcInstanceData = hytaleAPI.getNpcInstance(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("Action for unknown NPC: npcInstanceId=" + npcInstanceId + ", action=" + action +
                    ", playerId=" + playerId + " (NPC not in registry - possible external_id vs id mismatch)");
            // Still try to execute basic actions even without NPC registration
            if (ack != null) {
                ack.call("{\"error\": \"NPC instance not found\"}");
            }
            return; // Don't proceed if NPC is missing for these actions
        }

        // Dispatch action - entity-manipulating actions will handle failures gracefully
        try {
            switch (action) {
                case "say" -> {
                    executeSay(npcInstanceData, playerId, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "emote" -> {
                    executeEmote(npcInstanceData, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "open_trade" -> {
                    executeOpenTrade(npcInstanceData, playerId);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "give_quest" -> {
                    executeGiveQuest(npcInstanceData, playerId, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "move_to" -> {
                    executeMoveTo(npcInstanceData, params, ack);
                }
                case "follow_target" -> {
                    executeFollowTarget(npcInstanceData, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "stop_following" -> {
                    executeStopFollowing(npcInstanceData);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "start_attacking" -> {
                    executeStartAttacking(npcInstanceData, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "stop_attacking" -> {
                    executeStopAttacking(npcInstanceData);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "find_block" -> executeFindBlock(npcInstanceData, params, ack);
                case "scan_blocks" -> executeScanBlocks(npcInstanceData, params, ack);
                case "scan_entities" -> executeScanEntities(npcInstanceData, params, ack);
                // case "find_entity" -> executeFindEntity(npcInstanceData, params, ack); // Deprecated
                case "get_current_position" -> executeGetCurrentPosition(npcInstanceData, ack);
                case "wait" -> executeWait(npcInstanceData, params, ack);
                case "teleport_player" -> executeTeleportPlayer(npcInstanceData, playerId, params, ack);
                default -> {
                    logger.warn("Unknown action received: " + action);
                    if (ack != null)
                        ack.call("{\"error\": \"Unknown action: " + action + "\"}");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to execute action '" + action + "'", e);
            Sentry.captureException(e);
            if (ack != null) {
                ack.call("{\"error\": \"Exception: " + e.getMessage() + "\"}");
            }
        }

        // Notify ChatHandler that an action occurred, so it can advance the queue
        if (chatHandler != null) {
            chatHandler.onNpcAction(npcInstanceId);
        }
    }

    public void execute(UUID npcInstanceId, String playerId, String action, JSONObject params) {
        execute(npcInstanceId, playerId, action, params, null);
    }

    /**
     * FIND_BLOCK - Search for a block by tag near the NPC
     */
    private void executeFindBlock(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (params == null || ack == null)
            return;

        String tag = params.optString("tag", "");
        int radius = params.optInt("radius", 10);

        if (tag.isEmpty()) {
            ack.call("{\"error\": \"Missing block tag\"}");
            return;
        }

        hytaleAPI.findBlock(npcInstanceData.entityUuid(), tag, radius).thenAccept(result -> {
            if (result.isPresent()) {
                ack.call(new JSONObject(result.get()).toString());
            } else {
                ack.call("{\"found\": false}");
            }
        }).exceptionally(e -> {
            logger.error("Error finding block: " + e.getMessage());
            Sentry.captureException(e);
            ack.call("{\"error\": \"Internal error searching for block\"}");
            return null;
        });
    }

    /**
     * SCAN_BLOCKS - Scan surroundings and return all unique block types with nearest coordinates
     */
    private void executeScanBlocks(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (params == null || ack == null)
            return;

        int radius = params.optInt("radius", 16);

        hytaleAPI.scanBlocks(npcInstanceData.entityUuid(), radius).thenAccept(result -> {
            if (result.isPresent()) {
                ack.call(new JSONObject(result.get()).toString());
            } else {
                ack.call("{\"blocks\": {}, \"radius\": " + radius + ", \"totalUniqueBlocks\": 0}");
            }
        }).exceptionally(e -> {
            logger.error("Error scanning blocks: " + e.getMessage());
            Sentry.captureException(e);
            ack.call("{\"error\": \"Internal error scanning blocks\"}");
            return null;
        });
    }

    /**
     * SCAN_ENTITIES - Scan surroundings and return all entities with their details
     */
    private void executeScanEntities(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (params == null || ack == null)
            return;

        int radius = params.optInt("radius", 32);

        hytaleAPI.scanEntities(npcInstanceData.entityUuid(), radius).thenAccept(result -> {
            if (result.isPresent()) {
                ack.call(new JSONObject(result.get()).toString());
            } else {
                ack.call("{\"entities\": [], \"radius\": " + radius + ", \"totalEntities\": 0}");
            }
        }).exceptionally(e -> {
            logger.error("Error scanning entities: " + e.getMessage());
            Sentry.captureException(e);
            ack.call("{\"error\": \"Internal error scanning entities\"}");
            return null;
        });
    }

    /**
     * FIND_ENTITY - Search for an entity by type/name near the NPC (Deprecated)
     */
    private void executeFindEntity(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (params == null || ack == null)
            return;

        String name = params.optString("name", "");
        int radius = params.optInt("radius", 32);

        hytaleAPI.findEntity(npcInstanceData.entityUuid(), name, radius).thenAccept(result -> {
            if (result.isPresent()) {
                ack.call(new JSONObject(result.get()).toString());
            } else {
                ack.call("{\"found\": false}");
            }
        }).exceptionally(e -> {
            logger.error("Error finding entity: " + e.getMessage());
            Sentry.captureException(e);
            ack.call("{\"error\": \"Internal error searching for entity\"}");
            return null;
        });
    }

    /**
     * SAY - Send text message to player(s)
     * This is the primary way NPCs communicate
     * 
     * If playerId is null or empty, broadcasts to all nearby players.
     * If params.broadcast is true, broadcasts to all nearby players within chat distance.
     * 
     * NOTE: Chat bubbles above NPC heads are NOT currently supported in Hytale's
     * plugin API. This feature would require custom UI implementation which is
     * out of scope for the current API. Messages are sent to the player's chat.
     */
    private void executeSay(NpcInstanceData npcInstanceData, String playerId, JSONObject params) {

        if (npcInstanceData == null) {
            logger.warn("Say action for unknown NPC: playerId=" + playerId + ", params=" + params);
            return;
        }

        String message = params != null ? params.optString("message", "") : "";

        if (message.isEmpty()) {
            logger.warn("Say action with empty message for NPC: " + npcInstanceData.entityUuid());
            return;
        }

        // Check if broadcasting is enabled (from params or NPC config)
        boolean broadcastFromParams = params != null && params.optBoolean("broadcast", false);
        NpcData npc = npcInstanceData.npcData();
        boolean broadcastFromConfig = npc != null && npc.broadcastReplies();
        boolean shouldBroadcast = broadcastFromParams || broadcastFromConfig;

        // Check if playerId is provided
        boolean hasPlayerId = playerId != null && !playerId.isEmpty();

        // Resolve target player name for message formatting
        String targetPlayerName = "Player";
        if (hasPlayerId) {
            Optional<GamePlayer> playerOpt = hytaleAPI.getPlayer(playerId);
            if (playerOpt.isPresent()) {
                targetPlayerName = playerOpt.get().name();
            }
        }

        // Format message with NPC name and prefix
        String npcName = npc != null ? npc.name() : npcInstanceData.entityUuid().toString();
        // New format: [NPC] Name to PlayerName: Message
        String formattedMessage = formatNpcMessage(npcName, message, targetPlayerName);

        if (shouldBroadcast) {
            // Broadcast to all nearby players using NPC's chat distance
            Optional<Location> npcLoc = hytaleAPI.getNpcInstanceLocation(npcInstanceData.entityUuid());
            if (npcLoc.isEmpty()) {
                logger.warn("Cannot broadcast message - NPC location unknown: " + npcInstanceData.entityUuid());
                return;
            }

            // Use NPC's chat distance for broadcast range, fallback to greeting range from config
            Number chatDistance = npc != null ? npc.chatDistance() : null;
            double broadcastRange = chatDistance != null ? chatDistance.doubleValue() : config.gameplay().greetingRange();
            
            java.util.List<dev.hycompanion.plugin.api.GamePlayer> nearbyPlayers = hytaleAPI
                    .getNearbyPlayers(npcLoc.get(), broadcastRange);

            if (nearbyPlayers.isEmpty()) {
                logger.debug("No nearby players to receive broadcast from NPC: " + npcInstanceData.entityUuid());
                return;
            }

            // Get player IDs for broadcasting
            java.util.List<String> playerIds = nearbyPlayers.stream()
                    .map(dev.hycompanion.plugin.api.GamePlayer::id)
                    .toList();

            // Broadcast to all nearby players
            hytaleAPI.broadcastNpcMessage(npcInstanceData.entityUuid(), playerIds, formattedMessage);

            if (config.logging().logActions()) {
                logger.info("NPC [" + npcInstanceData.entityUuid() + "] broadcasts to " +
                        nearbyPlayers.size() + " players (range: " + broadcastRange + "): " + message);
            }
        } else if (hasPlayerId) {
            // Rotate NPC toward the specific player when responding
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    hytaleAPI.getPlayer(playerId).ifPresent(player -> {
                        if (player.location() != null) {
                            hytaleAPI.rotateNpcInstanceToward(npcInstanceData.entityUuid(), player.location());
                        }
                    });
                } catch (Exception e) {
                    Sentry.captureException(e);
                    // Ignore - rotation is optional
                }
            });

            // Send to specific player only
            hytaleAPI.sendNpcMessage(npcInstanceData.entityUuid(), playerId, formattedMessage);

            if (config.logging().logActions()) {
                logger.info(
                        "NPC [" + npcInstanceData.entityUuid() + "] (UUID: " + npcInstanceData.entityUuid().toString()
                                + ") says to [" + playerId + "]: " + message);
            }
        } else {
            // No specific player and no broadcast - just log a warning
            logger.warn("Say action with no playerId and broadcast disabled for NPC: " + npcInstanceData.entityUuid());
        }
    }

    /**
     * EMOTE - Play animation on NPC
     * 
     * The backend sends the actual animation name (e.g., "Sit", "Howl", "Greet")
     * based on what's available for this NPC's model.
     * 
     * Emotes are only played when the NPC is idle (not following or attacking).
     */
    private void executeEmote(NpcInstanceData npcInstanceData, JSONObject params) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();

        if (!config.gameplay().emotesEnabled()) {
            logger.debug("Animations disabled, skipping for NPC: " + npcInstanceId);
            return;
        }

        // Don't play emotes while the NPC is busy (following or attacking)
        if (hytaleAPI.isNpcBusy(npcInstanceId)) {
            logger.debug("Skipping emote for NPC " + npcInstanceId + " - NPC is busy (following/attacking)");
            return;
        }

        // The 'animation' parameter contains the actual animation name from the model
        // Falls back to 'emotion' for backwards compatibility
        String animationName = params != null
                ? params.optString("animation", params.optString("emotion", "Idle"))
                : "Idle";

        // Pass the animation name directly - no mapping needed
        // The animation name should match a key from the model's AnimationSets
        hytaleAPI.triggerNpcEmote(npcInstanceId, animationName);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] plays animation: " + animationName);
        }
    }

    /**
     * OPEN_TRADE - Open trade interface between NPC and player
     */
    private void executeOpenTrade(NpcInstanceData npcInstanceData, String playerId) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        hytaleAPI.openTradeInterface(npcInstanceId, playerId);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] opens trade with [" + playerId + "]");
        }
    }

    /**
     * GIVE_QUEST - Offer a quest to the player
     */
    private void executeGiveQuest(NpcInstanceData npcInstanceData, String playerId, JSONObject params) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        String questId = params != null ? params.optString("questId", "") : "";
        String questName = params != null ? params.optString("questName", questId) : questId;

        if (questId.isEmpty()) {
            logger.warn("Give quest action with empty questId for NPC: " + npcInstanceId);
            return;
        }

        hytaleAPI.offerQuest(npcInstanceId, playerId, questId, questName);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] offers quest '" + questName + "' to [" + playerId + "]");
        }
    }

    /**
     * MOVE_TO - Move NPC to a location
     */
    private void executeMoveTo(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();
        if (params == null) {
            logger.warn("Move_to action without params for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"error\": \"Missing parameters\"}");
            return;
        }

        double x = params.optDouble("x", 0);
        double y = params.optDouble("y", 0);
        double z = params.optDouble("z", 0);

        Location destination = Location.of(x, y, z);

        hytaleAPI.moveNpcTo(npcInstanceId, destination).thenAccept(result -> {
            if (ack != null) {
                org.json.JSONObject response = new org.json.JSONObject();

                // Map result status to response format expected by backend
                String status = result.status();
                if ("success".equals(status)) {
                    response.put("success", true);
                } else if ("timeout".equals(status)) {
                    response.put("stuck", true);
                    response.put("reason", "Path blocked or movement timed out");
                } else if ("shutdown".equals(status)) {
                    response.put("interrupted", true);
                } else {
                    // Any other failure status
                    response.put("stuck", true);
                    response.put("reason", status);
                }

                if (result.finalLocation() != null) {
                    response.put("x", result.finalLocation().x());
                    response.put("y", result.finalLocation().y());
                    response.put("z", result.finalLocation().z());
                }

                ack.call(response);
            }
        });

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] moving to " + destination.toCoordString());
        }
    }

    /**
     * ERROR_MESSAGE - Display an error message in red to the player
     * Used when backend encounters errors (like LLM failures)
     */
    private void executeErrorMessage(UUID npcInstanceId, String playerId, JSONObject params) {
        String message = params != null ? params.optString("message", "An error occurred.") : "An error occurred.";

        // Send red message to player (bypass NPC entity check since this is just error
        // display)
        // The HytaleAPI implementation will color this with #FF0000 (red)
        hytaleAPI.sendErrorMessage(playerId, message);

        logger.warn("[Error] Sent to player [" + playerId + "] (NPC: " + npcInstanceId + "): " + message);
    }

    /**
     * Format NPC message with prefix and name.
     * Note: Hytale doesn't use Minecraft-style color codes (§ or &).
     * Colors are applied via the Hytale Message API in sendNpcMessage.
     */
    private String formatNpcMessage(String npcName, String message, String targetPlayerName) {
        // Strip any legacy color codes from the prefix
        String prefix = config.gameplay().messagePrefix();
        // Remove § and & color codes (e.g., §6, &r, etc.)
        prefix = prefix.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");

        // E.g. [NPC] Warrior to Steve: Hello there!
        return prefix + npcName + " to " + targetPlayerName + ": " + message;
    }

    // ========== AI Action Methods ==========

    /**
     * FOLLOW_TARGET - Make NPC start following a player
     */
    private void executeFollowTarget(NpcInstanceData npcInstanceData, JSONObject params) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        String targetPlayerName = params != null ? params.optString("targetPlayerName", "") : "";

        if (targetPlayerName.isEmpty()) {
            logger.warn("Follow target action without targetPlayerName for NPC: " + npcInstanceId);
            return;
        }

        boolean success = hytaleAPI.startFollowingPlayer(npcInstanceId, targetPlayerName);

        if (config.logging().logActions()) {
            logger.info(
                    "[NPC:" + npcInstanceId + "] follow " + targetPlayerName + ": " + (success ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * STOP_FOLLOWING - Make NPC stop following current target
     */
    private void executeStopFollowing(NpcInstanceData npcInstanceData) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        boolean success = hytaleAPI.stopFollowing(npcInstanceId);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] stop following: " + (success ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * START_ATTACKING - Make NPC start attacking a target
     */
    private void executeStartAttacking(NpcInstanceData npcInstanceData, JSONObject params) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        String targetName = params != null ? params.optString("targetName", "") : "";
        String attackType = params != null ? params.optString("attackType", "melee") : "melee";

        if (targetName.isEmpty()) {
            logger.warn("Start attacking action without targetName for NPC: " + npcInstanceId);
            return;
        }

        boolean success = hytaleAPI.startAttacking(npcInstanceId, targetName, attackType);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] attack " + targetName + " (" + attackType + "): "
                    + (success ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * STOP_ATTACKING - Make NPC stop attacking
     */
    private void executeStopAttacking(NpcInstanceData npcInstanceData) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        boolean success = hytaleAPI.stopAttacking(npcInstanceId);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] stop attacking: " + (success ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * GET_CURRENT_POSITION - Get the NPC's current location
     */
    private void executeGetCurrentPosition(NpcInstanceData npcInstanceData, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        Optional<Location> location = hytaleAPI.getNpcInstanceLocation(npcInstanceId);

        if (location.isPresent()) {
            org.json.JSONObject response = new org.json.JSONObject();
            response.put("success", true);
            response.put("x", location.get().x());
            response.put("y", location.get().y());
            response.put("z", location.get().z());

            if (ack != null) {
                ack.call(response);
            }

            if (config.logging().logActions()) {
                logger.info("[NPC:" + npcInstanceId + "] current position: (" +
                        location.get().x() + ", " + location.get().y() + ", " + location.get().z() + ")");
            }
        } else {
            if (ack != null) {
                ack.call("{\"success\": false, \"error\": \"Could not get NPC location\"}");
            }
            logger.warn("Could not get current position for NPC: " + npcInstanceId);
        }
    }

    /**
     * WAIT - Pause for a specified duration (handled server-side)
     * Note: The actual wait is performed in the backend, this just acknowledges
     */
    private void executeWait(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        int duration = params != null ? params.optInt("duration", 1000) : 1000;

        // Acknowledge immediately - the actual wait happens in the backend
        if (ack != null) {
            org.json.JSONObject response = new org.json.JSONObject();
            response.put("success", true);
            response.put("waitedMs", duration);
            ack.call(response);
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] wait: " + duration + "ms");
        }
    }

    /**
     * TELEPORT_PLAYER - Teleport a player to specific coordinates
     */
    private void executeTeleportPlayer(NpcInstanceData npcInstanceData, String playerId, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        if (playerId == null || playerId.isEmpty()) {
            logger.warn("Teleport player action without playerId for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"error\": \"No player specified\"}");
            return;
        }

        if (params == null) {
            logger.warn("Teleport player action without params for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"error\": \"Missing parameters\"}");
            return;
        }

        String locationName = params.optString("locationName", "Unknown");
        String worldName = params.optString("worldName", "default");
        double x = params.optDouble("x", 0);
        double y = params.optDouble("y", 0);
        double z = params.optDouble("z", 0);

        logger.info("Trying to teleport player action: " + locationName + " in world " + worldName + " at " + x + ", " + y + ", " + z);

        Location destination = Location.of(x, y, z, worldName);

        // Teleport the player
        boolean success = hytaleAPI.teleportPlayerTo(playerId, destination);

        // Send chat message to player confirming teleport
        if (success) {
            String message = "You have been teleported to " + locationName + ".";
            hytaleAPI.sendMessage(playerId, message);
        }

        if (ack != null) {
            org.json.JSONObject response = new org.json.JSONObject();
            response.put("success", success);
            if (!success) {
                response.put("error", "Failed to teleport player");
            }
            ack.call(response);
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] teleport player [" + playerId + "] to " + locationName + 
                    " (" + x + ", " + y + ", " + z + "): " + (success ? "SUCCESS" : "FAILED"));
        }
    }
}
