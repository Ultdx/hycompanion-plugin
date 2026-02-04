package dev.hycompanion.plugin.network;

/**
 * Socket.IO event names - must match backend events.ts
 * 
 * @see hycompanion-backend/src/modules/socket/events.ts
 */
public final class SocketEvents {

    // Prevent instantiation
    private SocketEvents() {
    }

    // ========== Plugin → Backend ==========

    /**
     * Plugin connection with API key authentication
     * Payload: { apiKey: string, serverInfo: { version, playerCount } }
     */
    public static final String PLUGIN_CONNECT = "plugin:connect";

    /**
     * Chat message from player to NPC
     * Payload: { npcId, playerId, playerName, message, context }
     */
    public static final String PLUGIN_CHAT = "plugin:chat";

    /**
     * Request NPC sync from backend
     * Payload: {}
     */
    public static final String PLUGIN_REQUEST_SYNC = "plugin:request_sync";

    /**
     * Report available animations for an NPC (discovered from model)
     * Sent after NPC entity is discovered/spawned
     * Payload: { npcId, animations: string[] }
     */
    public static final String PLUGIN_NPC_ANIMATIONS = "plugin:npc_animations";

    /**
     * Report available blocks on the server (discovered from BlockType registry)
     * Sent once on startup after connection
     * Payload: { blocks: BlockInfo[] }
     * where BlockInfo = { blockId, displayName, materialTypes, keywords, categories }
     */
    public static final String PLUGIN_BLOCKS_AVAILABLE = "plugin:blocks_available";

    // ========== Backend → Plugin ==========

    /**
     * Response to chat (legacy, now uses backend:action)
     * Payload: { npcId, playerId, message, actions }
     */
    public static final String BACKEND_RESPONSE = "backend:response";

    /**
     * MCP tool action to execute in-game
     * Payload: { npcId, playerId, action, params }
     */
    public static final String BACKEND_ACTION = "backend:action";

    /**
     * Error from backend
     * Payload: { code, message }
     */
    public static final String BACKEND_ERROR = "backend:error";

    /**
     * NPC sync (create/update/delete)
     * Payload: { action: 'create'|'update'|'delete', npc: { id, externalId, name,
     * ... } }
     */
    public static final String BACKEND_NPC_SYNC = "backend:npc_sync";

    /**
     * Chain-of-thought status update for NPC thinking indicators
     * Payload: { type, npcInstanceUuid, playerId, message, stepNumber, toolName, ... }
     */
    public static final String BACKEND_COT_UPDATE = "backend:cot_update";
}
