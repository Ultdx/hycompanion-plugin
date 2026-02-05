package dev.hycompanion.plugin;

import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.ServerInfo;
import dev.hycompanion.plugin.adapter.MockHytaleAdapter;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.config.NpcConfigManager;
import dev.hycompanion.plugin.core.context.ContextBuilder;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.handlers.ActionExecutor;
import dev.hycompanion.plugin.handlers.ChatHandler;
import dev.hycompanion.plugin.network.SocketManager;
import dev.hycompanion.plugin.utils.PluginLogger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Hycompanion Plugin - Main Entry Point
 * 
 * AI-powered NPC companion system for Hytale servers.
 * Connects to the Hycompanion Cloud Backend to provide intelligent,
 * context-aware NPC interactions.
 * 
 * @author Hycompanion Team / NOLDO
 * @version 1.1.1
 * @see <a href="https://hycompanion.dev">Hycompanion Website</a>
 */
public class HycompanionPlugin {

    // Singleton instance for global access
    private static HycompanionPlugin instance;

    // Plugin version
    public static final String VERSION = "1.1.1-SNAPSHOT";

    // Plugin components
    private PluginConfig config;
    private PluginLogger logger;
    private SocketManager socketManager;
    private NpcManager npcManager;
    private NpcConfigManager npcConfigManager;
    private ActionExecutor actionExecutor;
    private ChatHandler chatHandler;
    private ContextBuilder contextBuilder;
    private HytaleAPI hytaleAPI;

    // Plugin state
    private boolean enabled = false;
    private Path dataFolder;

    /**
     * Main constructor - initializes the plugin
     */
    public HycompanionPlugin() {
        instance = this;
        // Default data folder for standalone testing
        this.dataFolder = Path.of("plugins", "Hycompanion");
    }

    /**
     * Plugin enable - called when plugin is loaded
     * 
     * TODO: [HYTALE-API] This should be called from Hytale's plugin lifecycle
     * e.g., extends HytalePlugin { @Override void onEnable() }
     */
    public void onEnable() {
        logger = new PluginLogger("Hycompanion");
        logger.info("======================================");
        logger.info("  Hycompanion Plugin v" + VERSION);
        logger.info("  https://hycompanion.dev");
        logger.info("  Sponsor: https://www.verygames.com (Hytale Server Hosting)");
        logger.info("======================================");

        try {
            // Create data folder if it doesn't exist
            Files.createDirectories(dataFolder);

            // Copy default config if not exists
            copyDefaultConfig();

            // Load configuration
            config = PluginConfig.load(dataFolder.resolve("config.yml"));
            logger.info("Configuration loaded successfully");

            // Update logger with config
            logger.setDebugMode(config.gameplay().debugMode());

            // Initialize Hytale API adapter
            // Detect if running on real Hytale server or standalone mode
            if (isHytaleServerEnvironment()) {
                // Running on real Hytale server - use real API
                // Note: Requires JavaPlugin instance from Hytale's plugin system
                logger.info("Detected Hytale Server environment");
                logger.info("Hytale API adapter requires JavaPlugin context - using Mock for now");
                // TODO: Pass JavaPlugin instance when integrating with Hytale plugin system
                // hytaleAPI = new HytaleServerAdapter(logger, javaPlugin);
                hytaleAPI = new MockHytaleAdapter(logger);
            } else {
                // Standalone testing mode
                hytaleAPI = new MockHytaleAdapter(logger);
                logger.info("Hytale API adapter initialized (Standalone/Mock Mode)");
            }

            // Initialize managers
            npcManager = new NpcManager(logger, hytaleAPI);
            npcConfigManager = new NpcConfigManager(dataFolder.resolve(config.npc().cacheDirectory()), logger);
            contextBuilder = new ContextBuilder(hytaleAPI, logger);

            // Initialize action executor
            actionExecutor = new ActionExecutor(hytaleAPI, npcManager, logger, config);

            // Initialize chat handler
            chatHandler = new ChatHandler(npcManager, contextBuilder, logger, config);

            // Initialize socket manager and connect
            initializeSocketConnection();

            // Register commands
            registerCommands();

            // Register event listeners
            registerEventListeners();

            enabled = true;
            logger.info("Hycompanion enabled successfully!");

        } catch (Exception e) {
            logger.error("Failed to enable Hycompanion", e);
            onDisable();
        }
    }

    /**
     * Plugin disable - called when plugin is unloaded
     */
    public void onDisable() {
        logger.info("Disabling Hycompanion...");

        if (socketManager != null) {
            socketManager.disconnect();
        }

        if (npcConfigManager != null) {
            npcConfigManager.saveAll();
        }

        enabled = false;
        logger.info("Hycompanion disabled.");
    }

    /**
     * Update API key in config.yml and reload
     */
    public void updateApiKey(String newApiKey) {
        try {
            Path configPath = dataFolder.resolve("config.yml");
            // Use shared helper to update file
            PluginConfig.updateApiKeyInFile(configPath, newApiKey);
            logger.info("API Key updated in config.yml");

            reload();

        } catch (IOException e) {
            logger.error("Failed to update API key", e);
        }
    }

    /**
     * Reload configuration and reconnect
     */
    public void reload() {
        logger.info("Reloading Hycompanion...");

        try {
            // Reload config
            config = PluginConfig.load(dataFolder.resolve("config.yml"));
            logger.setDebugMode(config.gameplay().debugMode());

            // Update components with new config
            actionExecutor.setConfig(config);
            chatHandler.setConfig(config);

            // Update SocketManager
            if (socketManager != null) {
                socketManager.setConfig(config);
                socketManager.updateApiKey(config.connection().apiKey());

                if (!socketManager.isConnected() && config.connection().reconnectEnabled()) {
                    socketManager.connect();
                }
            } else {
                initializeSocketConnection();
            }

            logger.info("Reload complete!");
        } catch (Exception e) {
            logger.error("Failed to reload configuration", e);
        }
    }

    /**
     * Force sync NPCs from backend
     */
    public void forceSync() {
        if (socketManager != null && socketManager.isConnected()) {
            socketManager.requestSync();
            logger.info("NPC sync requested");
        } else {
            logger.warn("Cannot sync - not connected to backend");
        }
    }

    /**
     * Get connection status
     */
    public boolean isConnected() {
        return socketManager != null && socketManager.isConnected();
    }

    /**
     * Initialize socket connection to backend
     */
    private void initializeSocketConnection() {
        // Create server info for handshake
        ServerInfo serverInfo = new ServerInfo(
                VERSION,
                getOnlinePlayerCount());

        // Initialize socket manager (no roleGenerator in standalone mode)
        socketManager = new SocketManager(
                config.connection().url(),
                config.connection().apiKey(),
                serverInfo,
                actionExecutor,
                npcManager,
                npcConfigManager,
                null, // No RoleGenerator in standalone mode
                logger,
                config,
                hytaleAPI);

        // Inject socket manager into chat handler
        chatHandler.setSocketManager(socketManager);

        // Connect
        logger.info("Connecting to backend: " + config.connection().url());
        socketManager.connect();
    }

    /**
     * Copy default config.yml from resources if it doesn't exist
     */
    private void copyDefaultConfig() throws IOException {
        Path configPath = dataFolder.resolve("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Default config.yml created");
                }
            }
        }
    }

    /**
     * Register plugin commands
     * TODO: [HYTALE-API] Integrate with Hytale command system
     */
    private void registerCommands() {
        // TODO: [HYTALE-API] Register /hycompanion command
        // Example for Spigot-like API:
        // getCommand("hycompanion").setExecutor(new HycompanionCommand(this));
        logger.debug("Commands registration (pending Hytale API)");
    }

    /**
     * Register event listeners
     * TODO: [HYTALE-API] Integrate with Hytale event system
     */
    private void registerEventListeners() {
        // TODO: [HYTALE-API] Register chat listener
        // Example for Spigot-like API:
        // Bukkit.getPluginManager().registerEvents(new ChatListener(chatHandler),
        // this);
        logger.debug("Event listeners registration (pending Hytale API)");
    }

    /**
     * Get current online player count
     * TODO: [HYTALE-API] Get from actual server
     */
    private int getOnlinePlayerCount() {
        // TODO: [HYTALE-API] Return actual player count
        // e.g., return HytaleServer.getOnlinePlayers().size();
        return 0;
    }

    /**
     * Detect if running on real Hytale Server environment
     * Checks for presence of Hytale Server API classes
     */
    private boolean isHytaleServerEnvironment() {
        try {
            // Check if Hytale Server classes are available
            Class.forName("com.hypixel.hytale.server.core.HytaleServer");
            Class.forName("com.hypixel.hytale.server.core.universe.Universe");
            return true;
        } catch (ClassNotFoundException e) {
            // Running standalone or on a non-Hytale platform
            return false;
        }
    }

    // ========== Getters ==========

    public static HycompanionPlugin getInstance() {
        return instance;
    }

    public PluginConfig getConfig() {
        return config;
    }

    public PluginLogger getLogger() {
        return logger;
    }

    public SocketManager getSocketManager() {
        return socketManager;
    }

    public NpcManager getNpcManager() {
        return npcManager;
    }

    public HytaleAPI getHytaleAPI() {
        return hytaleAPI;
    }

    public ChatHandler getChatHandler() {
        return chatHandler;
    }

    public Path getDataFolder() {
        return dataFolder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ========== Main (for testing) ==========

    /**
     * Main method for standalone testing with interactive console
     */
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║         HYCOMPANION - Standalone Test Mode                 ║");
        System.out.println("║         Interactive Console for Testing                    ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();

        HycompanionPlugin plugin = new HycompanionPlugin();
        plugin.onEnable();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(plugin::onDisable));

        // Wait for connection
        System.out.println("\nWaiting for backend connection...");
        int attempts = 0;
        while (!plugin.isConnected() && attempts < 30) {
            try {
                Thread.sleep(500);
                attempts++;
            } catch (InterruptedException e) {
                break;
            }
        }

        if (!plugin.isConnected()) {
            System.out.println("WARNING: Not connected to backend after 15 seconds.");
            System.out.println("Check your config.yml settings (url, api_key)");
        }

        // Start interactive console
        runInteractiveConsole(plugin);
    }

    /**
     * Interactive console for testing chat with NPCs
     */
    private static void runInteractiveConsole(HycompanionPlugin plugin) {
        java.util.Scanner scanner = new java.util.Scanner(System.in);

        System.out.println("\n┌─────────────────────────────────────────────────────────────┐");
        System.out.println("│              INTERACTIVE CONSOLE MODE                       │");
        System.out.println("├─────────────────────────────────────────────────────────────┤");
        System.out.println("│  Commands:                                                  │");
        System.out.println("│    /chat <npcId> <message>  - Send chat to NPC              │");
        System.out.println("│    /list                    - List loaded NPCs              │");
        System.out.println("│    /sync                    - Force NPC sync                │");
        System.out.println("│    /status                  - Show connection status        │");
        System.out.println("│    /reload                  - Reload config                 │");
        System.out.println("│    /help                    - Show this help                │");
        System.out.println("│    /quit                    - Exit test mode                │");
        System.out.println("│                                                             │");
        System.out.println("│  Quick chat (after setting default NPC):                    │");
        System.out.println("│    /npc <npcId>             - Set default NPC for chat      │");
        System.out.println("│    <message>                - Send to default NPC           │");
        System.out.println("└─────────────────────────────────────────────────────────────┘");
        System.out.println();

        String defaultNpcId = null;
        String testPlayerId = "test-player-001";
        String testPlayerName = "TestPlayer";

        System.out.print("> ");
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                System.out.print("> ");
                continue;
            }

            if (input.startsWith("/")) {
                // Command mode
                String[] parts = input.substring(1).split("\\s+", 3);
                String cmd = parts[0].toLowerCase();

                switch (cmd) {
                    case "quit", "exit", "q" -> {
                        System.out.println("Goodbye!");
                        plugin.onDisable();
                        System.exit(0);
                    }
                    case "help", "?" -> {
                        System.out.println("Commands:");
                        System.out.println("  /chat <npcId> <message> - Send chat to NPC");
                        System.out.println("  /npc <npcId>           - Set default NPC");
                        System.out.println("  /list                  - List NPCs");
                        System.out.println("  /sync                  - Force sync");
                        System.out.println("  /status                - Connection status");
                        System.out.println("  /reload                - Reload config");
                        System.out.println("  /quit                  - Exit");
                    }
                    case "status" -> {
                        boolean connected = plugin.isConnected();
                        int npcCount = plugin.getNpcManager().getNpcCount();
                        System.out.println("Connection: " + (connected ? "✓ Connected" : "✗ Disconnected"));
                        System.out.println("NPCs loaded: " + npcCount);
                        System.out.println("Default NPC: " + (defaultNpcId != null ? defaultNpcId : "(not set)"));
                        System.out.println("Test player: " + testPlayerName + " (" + testPlayerId + ")");
                    }
                    case "sync" -> {
                        plugin.forceSync();
                        System.out.println("Sync requested...");
                    }
                    case "reload" -> {
                        plugin.reload();
                        System.out.println("Config reloaded.");
                    }
                    case "list" -> {
                        var npcs = plugin.getNpcManager().getAllNpcs();
                        if (npcs.isEmpty()) {
                            System.out.println("No NPCs loaded. Try /sync first.");
                        } else {
                            System.out.println("Loaded NPCs (" + npcs.size() + "):");
                            for (var npc : npcs) {
                                System.out.println("  - " + npc.externalId() + " (" + npc.name() + ")");
                            }
                        }
                    }
                    case "npc" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: /npc <npcId>");
                            System.out.println("Current: " + (defaultNpcId != null ? defaultNpcId : "(not set)"));
                        } else {
                            defaultNpcId = parts[1];
                            System.out.println("Default NPC set to: " + defaultNpcId);
                        }
                    }
                    case "player" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: /player <name>");
                            System.out.println("Current: " + testPlayerName);
                        } else {
                            testPlayerName = parts[1];
                            System.out.println("Test player name set to: " + testPlayerName);
                        }
                    }
                    case "chat" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: /chat <npcId> <message>");
                        } else {
                            String npcId = parts[1];
                            String message = parts[2];
                            sendTestChat(plugin, npcId, testPlayerId, testPlayerName, message);
                        }
                    }
                    default -> System.out.println("Unknown command: " + cmd + ". Type /help for help.");
                }
            } else {
                // Quick chat to default NPC
                if (defaultNpcId == null) {
                    System.out.println("No default NPC set. Use /npc <npcId> or /chat <npcId> <message>");
                } else {
                    sendTestChat(plugin, defaultNpcId, testPlayerId, testPlayerName, input);
                }
            }

            System.out.print("> ");
        }
    }

    /**
     * Send test chat message to backend
     */
    private static void sendTestChat(HycompanionPlugin plugin, String npcId, String playerId, String playerName,
            String message) {
        if (!plugin.isConnected()) {
            System.out.println("ERROR: Not connected to backend!");
            return;
        }

        System.out.println("[" + playerName + " → " + npcId + "]: " + message);

        // Build context
        var context = new com.google.gson.JsonObject();
        context.addProperty("location", "0,64,0");
        context.addProperty("timeOfDay", "noon");
        context.addProperty("weather", "clear");

        // Send via socket manager
        plugin.getSocketManager().sendChat(npcId, null, playerId, playerName, message, context);

        System.out.println("(Waiting for response...)");
    }
}
