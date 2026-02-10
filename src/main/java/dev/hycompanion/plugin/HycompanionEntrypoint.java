package dev.hycompanion.plugin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.npc.AllNPCsLoadedEvent;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hycompanion.plugin.adapter.HytaleServerAdapter;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.ServerInfo;
import dev.hycompanion.plugin.commands.HycompanionCommand;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.config.NpcConfigManager;
import dev.hycompanion.plugin.core.context.ContextBuilder;
import dev.hycompanion.plugin.core.npc.NpcGreetingService;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.handlers.ActionExecutor;
import dev.hycompanion.plugin.handlers.ChatHandler;
import dev.hycompanion.plugin.network.SocketManager;
import dev.hycompanion.plugin.role.RoleGenerator;
import dev.hycompanion.plugin.shutdown.ShutdownManager;
import dev.hycompanion.plugin.systems.NpcRespawnSystem;
import dev.hycompanion.plugin.utils.PluginLogger;
import io.sentry.Sentry;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Hycompanion Plugin Entry Point for Hytale Server
 * 
 * This class extends JavaPlugin and serves as the main entry point
 * when the plugin is loaded by a real Hytale server.
 * 
 * @author Hycompanion Team
 * @version 1.1.3
 */
public class HycompanionEntrypoint extends JavaPlugin {

    private static final HytaleLogger HYTALE_LOGGER = HytaleLogger.forEnclosingClass();
    public static final String VERSION = "1.1.3-SNAPSHOT";

    // Plugin components
    private PluginLogger logger;
    private PluginConfig config;
    private HytaleAPI hytaleAPI;
    private SocketManager socketManager;
    private NpcManager npcManager;
    private NpcConfigManager npcConfigManager;
    private ActionExecutor actionExecutor;
    private ChatHandler chatHandler;
    private ContextBuilder contextBuilder;
    private RoleGenerator roleGenerator;
    private NpcGreetingService greetingService;
    private Path dataFolder;

    // Dedicated scheduler for plugin background tasks (Daemon threads)
    private java.util.concurrent.ScheduledExecutorService pluginScheduler;

    // Centralized shutdown manager - single source of truth for shutdown state
    private ShutdownManager shutdownManager;

    // Flag to track if entity discovery has been performed
    private volatile boolean entityDiscoveryDone = false;

    /**
     * Constructor required by Hytale plugin loader
     * 
     * @param init Plugin initialization data provided by Hytale
     */
    public HycompanionEntrypoint(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        HYTALE_LOGGER.atInfo().log("Hycompanion plugin constructor called - version " + VERSION);
    }

    /**
     * Get the shutdown manager for other components to register.
     */
    public ShutdownManager getShutdownManager() {
        return shutdownManager;
    }

    /**
     * Check if the server is shutting down.
     * Used by SocketManager and other components to prevent new operations during shutdown.
     */
    public static boolean isShuttingDown() {
        return getInstance() != null && getInstance().shutdownManager != null 
            && getInstance().shutdownManager.isShuttingDown();
    }

    private static HycompanionEntrypoint instance;

    public static HycompanionEntrypoint getInstance() {
        return instance;
    }

    /**
     * Setup phase - runs BEFORE asset loading.
     * NOTE: Hytale blocks network access during setup() phase.
     * We can only load cached role files from previous runs here.
     * Role syncing happens during start() and roles are cached for next startup.
     */
    @Override
    protected void setup() {
        HYTALE_LOGGER.atInfo().log("Hycompanion setup() starting...");

        // Initialize logger first (needed for ShutdownManager)
        logger = new PluginLogger("Hycompanion");
        
        // Initialize centralized shutdown manager early
        shutdownManager = new ShutdownManager(logger);
        HYTALE_LOGGER.atInfo().log("ShutdownManager initialized");

        try {

            // Configure Sentry DSN via environment variable SENTRY_DSN
            // Set SENTRY_DSN environment variable to enable error tracking
            String sentryDsn = System.getenv("SENTRY_DSN");
            if (sentryDsn != null && !sentryDsn.isEmpty()) {
                Sentry.init(
                        options -> {
                            options.setDsn(sentryDsn);
                            options.setSendDefaultPii(true);

                            // All events get assigned to the release. See more at
                            // https://docs.sentry.io/workflow/releases/
                            // options.setRelease("io.sentry.samples.console@3.0.0+1");

                            // Configure the background worker which sends events to sentry:
                            // Wait up to 5 seconds before shutdown while there are events to send.
                            options.setShutdownTimeoutMillis(1000);

                            // Enable SDK logging with Debug level
                            // options.setDebug(true);
                            // To change the verbosity, use:
                            // By default it's DEBUG.
                            // options.setDiagnosticLevel(SentryLevel.ERROR);
                            // A good option to have SDK debug log in prod is to use only level ERROR here.

                            // Exclude frames from some packages from being "inApp" so are hidden by default
                            // in Sentry
                            // UI:
                            // options.addInAppExclude("org.jboss");

                            // Include frames from our package
                            // options.addInAppInclude("io.sentry.samples");

                            // Performance configuration options
                            // Set what percentage of traces should be collected
                            options.setTracesSampleRate(1.0); // set 0.5 to send 50% of traces

                            // Determine traces sample rate based on the sampling context
                            // options.setTracesSampler(
                            // context -> {
                            // // only 10% of transactions with "/product" prefix will be collected
                            // if (!context.getTransactionContext().getName().startsWith("/products"))
                            // {
                            // return 0.1;
                            // } else {
                            // return 0.5;
                            // }
                            // });
                        });
                logger.info("Sentry error tracking enabled");
            } else {
                logger.info("Sentry DSN not configured - error tracking disabled");
            }

            logger.info("Setup phase - initializing...");

            // Set global uncaught exception handler to capture all unhandled exceptions
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                logger.error("Uncaught exception in thread " + thread.getName() + ": " + throwable.getMessage());
                if (sentryDsn != null && !sentryDsn.isEmpty()) {
                    Sentry.captureException(throwable);
                }
            });
            if (sentryDsn != null && !sentryDsn.isEmpty()) {
                logger.info("Global uncaught exception handler registered with Sentry");
            } else {
                logger.info("Global uncaught exception handler registered (Sentry disabled)");
            }

            // Get data folder and load config early
            dataFolder = getDataDirectory();
            HYTALE_LOGGER.atInfo().log("Data directory: " + dataFolder.toString());

            Files.createDirectories(dataFolder);
            copyDefaultConfig();
            config = PluginConfig.load(dataFolder.resolve("config.yml"));
            logger.setDebugMode(config.gameplay().debugMode());

            logger.info("Configuration loaded from " + dataFolder.resolve("config.yml"));

            // Check if API key is configured
            String apiKey = config.connection().apiKey();
            if (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_SERVER_API_KEY")) {
                logger.warn("========================================================");
                logger.warn("  API KEY NOT CONFIGURED!");
                logger.warn("  Your Hycompanion API key is not set in config.yml");
                logger.warn("  ");
                logger.warn("  Get your API key from: https://app.hycompanion.dev");
                logger.warn("  Then use: /hycompanion register <your-api-key>");
                logger.warn("  Or edit config.yml directly");
                logger.warn("========================================================");
            }

            // Initialize role generator
            // modDirectory should be mods/<plugin-id>/ e.g.
            // mods/dev.hycompanion_Hycompanion/
            // Note: getIdentifier() uses ":" but folder uses "_" (Windows compatibility)
            Path modsFolder = dataFolder.getParent();
            String modFolderName = getIdentifier().toString().replace(":", "_");
            Path modDirectory = modsFolder.resolve(modFolderName);
            HYTALE_LOGGER.atInfo().log("Mod directory for role files: " + modDirectory.toString());
            roleGenerator = new RoleGenerator(modDirectory, dataFolder, logger);

            // Load cached role files from previous runs
            // (Network is not available during setup phase - roles are synced during
            // start())
            int cachedRoles = roleGenerator.loadCachedRoles();
            if (cachedRoles > 0) {
                logger.info("Loaded " + cachedRoles + " cached NPC role files from previous session");
            } else {
                logger.info("No cached roles (first run) - NPCs will sync after server starts");
                logger.info("========================================================");
                logger.info("  FIRST RUN NOTICE");
                logger.info("  Custom NPC roles will be synced and cached during this session.");
                logger.info("  To apply custom NPC appearances/stats, RESTART the server");
                logger.info("  after this session. Cached roles will then be loaded.");
                logger.info("========================================================");
            }

            // Register for AllNPCsLoadedEvent to trigger entity discovery
            // This event fires when Hytale finishes loading/reloading NPC entities
            getEventRegistry().register(AllNPCsLoadedEvent.class, event -> onAllNpcsLoaded(event));

            // Register for first player join to trigger entity discovery
            // Chunks aren't loaded until a player connects, so entities can't be found
            // earlier
            getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::onPlayerAddedToWorld);
            
            // Register for player disconnect to clear NPC follow targets
            // This prevents "Invalid entity reference" errors during shutdown
            getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
            
            // Register for early shutdown event to clear NPC references before world shutdown
            // Priority -48 = DISCONNECT_PLAYERS phase - we clear NPCs before players are removed
            getEventRegistry().register((short)-48, ShutdownEvent.class, this::onServerShutdown);
            
            // Register death detection system for NPC respawn
            com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY.registerSystem(new NpcRespawnSystem());
            logger.info("Registered NPC respawn death detection system");
            
            logger.info("Registered event listeners for entity discovery, player disconnect, and shutdown");

            logger.info("Setup phase complete");
            HYTALE_LOGGER.atInfo().log("Hycompanion setup() complete");

        } catch (Exception e) {
            HYTALE_LOGGER.atSevere().log("Failed during setup phase: " + e.getMessage());
            Sentry.captureException(e);
            if (logger != null) {
                logger.error("Failed during setup phase: " + e.getMessage());
            }
            e.printStackTrace();
        }
    }

    @Override
    protected void start() {
        HYTALE_LOGGER.atInfo().log("Hycompanion start() beginning...");

        // Logger and config already initialized in setup()
        // If setup() wasn't called for some reason, initialize them now
        if (logger == null) {
            logger = new PluginLogger("Hycompanion");
        }

        logger.info("======================================");
        logger.info("  Hycompanion Plugin v" + VERSION);
        logger.info("  https://hycompanion.dev");
        logger.info("  Running on Hytale Server");
        logger.info("  Sponsor: https://verygames.com - Host your Hytale server with us!");
        logger.info("======================================");

        try {
            // Ensure data folder exists (should already be created in setup)
            if (dataFolder == null) {
                dataFolder = getDataDirectory();
                Files.createDirectories(dataFolder);
            }

            // Reload config if needed (should already be loaded in setup)
            if (config == null) {
                copyDefaultConfig();
                config = PluginConfig.load(dataFolder.resolve("config.yml"));
                logger.setDebugMode(config.gameplay().debugMode());
            }

            // Initialize role generator if not done in setup
            if (roleGenerator == null) {
                Path modsFolder = dataFolder.getParent();
                String modFolderName = getIdentifier().toString().replace(":", "_");
                Path modDirectory = modsFolder.resolve(modFolderName);
                roleGenerator = new RoleGenerator(modDirectory, dataFolder, logger);
            }

            logger.info("Configuration loaded successfully");

            // Initialize ShutdownManager early if not done in setup()
            if (shutdownManager == null) {
                shutdownManager = new ShutdownManager(logger);
                logger.info("ShutdownManager initialized");
            }
            
            // Register JVM shutdown hook for early detection
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("JVM shutdown hook triggered - initiating early cleanup");
                if (shutdownManager != null) {
                    shutdownManager.initiateShutdown();
                }
            }, "Hycompanion-Shutdown-Hook"));
            
            // Initialize Hytale API adapter with real server API
            hytaleAPI = new HytaleServerAdapter(logger, this, shutdownManager);
            logger.info("Hytale API adapter initialized (Real Server Mode)");

            // Initialize managers
            npcManager = new NpcManager(logger, hytaleAPI);
            npcConfigManager = new NpcConfigManager(dataFolder.resolve(config.npc().cacheDirectory()), logger);
            contextBuilder = new ContextBuilder(hytaleAPI, logger);

            // Create daemon scheduler for plugin tasks
            pluginScheduler = java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "Hycompanion-Background-Worker");
                t.setDaemon(true);
                return t;
            });

            // Initialize action executor
            actionExecutor = new ActionExecutor(hytaleAPI, npcManager, logger, config);

            // Initialize chat handler
            chatHandler = new ChatHandler(npcManager, contextBuilder, logger, config);
            chatHandler.setHytaleAPI(hytaleAPI);

            // Connect ActionExecutor to ChatHandler (circular dependency handled via
            // setter)
            actionExecutor.setChatHandler(chatHandler);

            // Initialize socket manager and connect (pass roleGenerator for caching)
            initializeSocketConnection();

            // Register event listeners
            registerEventListeners();

            // Register commands
            registerCommands();

            // Initialize and start NPC greeting service
            greetingService = new NpcGreetingService(hytaleAPI, npcManager, config, logger);
            greetingService.startProximityChecks(
                    pluginScheduler,
                    1000 // Check every 1 second
            );

            logger.info("Hycompanion enabled successfully on Hytale Server!");
            HYTALE_LOGGER.atInfo().log("Hycompanion start() complete - plugin fully loaded");

        } catch (Exception e) {
            logger.error("Failed to enable Hycompanion", e);
            HYTALE_LOGGER.atSevere().log("Failed to enable Hycompanion: " + e.getMessage());
            Sentry.captureException(e);
            e.printStackTrace();
            // Don't throw exception - let server continue even if we fail
        }
    }

    @Override
    protected void shutdown() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        
        HYTALE_LOGGER.atInfo().log("Hycompanion shutdown() starting on thread: " + threadName);

        if (logger != null) {
            logger.debug("[Shutdown] ============================================");
            logger.debug("[Shutdown] FINAL SHUTDOWN PHASE STARTED");
            logger.debug("[Shutdown] Thread: " + threadName);
            logger.debug("[Shutdown] Timestamp: " + startTime);
            logger.debug("[Shutdown] ============================================");
        }

        // NOTE: Early cleanup was already done in onServerShutdown() which runs
        // at ShutdownEvent priority -48 (before player removal).
        // This includes: disconnecting socket, stopping periodic tasks, clearing entity refs.
        // See onServerShutdown() for early shutdown handling.

        // STEP 1: Clear entity references (deferred from early shutdown)
        // This is now safe to do because Hytale's chunk saving is complete
        logger.debug("[Shutdown] Step 1/5: Clearing entity references...");
        long stepStart = System.currentTimeMillis();
        if (hytaleAPI != null) {
            ((HytaleServerAdapter) hytaleAPI).clearAllEntityReferences();
            logger.debug("[Shutdown] Step 1/5: Entity references cleared in " + (System.currentTimeMillis() - stepStart) + "ms");
        } else {
            logger.warn("[Shutdown] Step 1/5: HytaleAPI is null");
        }

        // STEP 2: Block world operations
        logger.debug("[Shutdown] Step 2/5: Blocking world operations...");
        stepStart = System.currentTimeMillis();
        if (shutdownManager != null) {
            shutdownManager.blockWorldOperations();
            logger.debug("[Shutdown] Step 2/5: World operations blocked in " + (System.currentTimeMillis() - stepStart) + "ms");
        } else {
            logger.warn("[Shutdown] Step 2/5: ShutdownManager is null");
        }

        // STEP 3: Shutdown plugin scheduler and chat handler
        logger.debug("[Shutdown] Step 3/5: Shutting down plugin scheduler and chat handler...");
        stepStart = System.currentTimeMillis();
        if (pluginScheduler != null) {
            try {
                pluginScheduler.shutdownNow();
                logger.debug("[Shutdown] Plugin scheduler shutdownNow() called");
                if (!pluginScheduler.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (logger != null)
                        logger.warn("[Shutdown] Plugin scheduler did not terminate within 2s timeout");
                } else {
                    logger.debug("[Shutdown] Plugin scheduler terminated successfully");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("[Shutdown] Interrupted while waiting for plugin scheduler");
                Sentry.captureException(e);
            } catch (Exception e) {
                logger.error("[Shutdown] Error shutting down plugin scheduler: " + e.getMessage());
                Sentry.captureException(e);
            } finally {
                pluginScheduler = null;
            }
        } else {
            logger.debug("[Shutdown] Plugin scheduler is null, skipping");
        }
        
        // Shutdown chat handler timeout scheduler
        if (chatHandler != null) {
            try {
                chatHandler.shutdown();
                logger.debug("[Shutdown] ChatHandler shutdown complete");
            } catch (Exception e) {
                logger.error("[Shutdown] Error shutting down ChatHandler: " + e.getMessage());
                Sentry.captureException(e);
            }
        } else {
            logger.debug("[Shutdown] ChatHandler is null, skipping");
        }
        logger.debug("[Shutdown] Step 3/5: Plugin scheduler and chat handler handled in " + (System.currentTimeMillis() - stepStart) + "ms");

        // STEP 4: Save NPC configurations
        logger.debug("[Shutdown] Step 4/5: Saving NPC configurations...");
        stepStart = System.currentTimeMillis();
        if (npcConfigManager != null) {
            try {
                npcConfigManager.saveAll();
                logger.debug("[Shutdown] Step 4/5: NPC configurations saved in " + (System.currentTimeMillis() - stepStart) + "ms");
            } catch (Exception e) {
                logger.error("[Shutdown] Step 4/5: Error saving NPC configs: " + e.getMessage());
                Sentry.captureException(e);
            }
        } else {
            logger.debug("[Shutdown] Step 4/5: NpcConfigManager is null, skipping");
        }
        
        // STEP 5: Close Sentry
        logger.debug("[Shutdown] Step 5/5: Closing Sentry...");
        stepStart = System.currentTimeMillis();
        try {
            io.sentry.Sentry.close();
            logger.debug("[Shutdown] Step 5/5: Sentry closed in " + (System.currentTimeMillis() - stepStart) + "ms");
        } catch (Exception e) {
            logger.debug("[Shutdown] Step 5/5: Error closing Sentry (ignored): " + e.getMessage());
        }

        long totalTime = System.currentTimeMillis() - startTime;
        if (logger != null) {
            logger.debug("[Shutdown] ============================================");
            logger.debug("[Shutdown] FINAL SHUTDOWN COMPLETE");
            logger.debug("[Shutdown] Total time: " + totalTime + "ms");
            logger.debug("[Shutdown] ============================================");
        }

        HYTALE_LOGGER.atInfo().log("Hycompanion shutdown() complete in " + totalTime + "ms");
        instance = null;
    }

    /**
     * Initialize socket connection to backend
     */
    private void initializeSocketConnection() {
        ServerInfo serverInfo = new ServerInfo(
                VERSION,
                getOnlinePlayerCount());

        socketManager = new SocketManager(
                config.connection().url(),
                config.connection().apiKey(),
                serverInfo,
                actionExecutor,
                npcManager,
                npcConfigManager,
                roleGenerator,
                logger,
                config,
                hytaleAPI);

        // Register callback for post-reconnection entity discovery
        socketManager.setOnReconnectSyncComplete(this::runEntityDiscoveryAfterReconnect);

        // Wire up ChatHandler and SocketManager (bidirectional)
        chatHandler.setSocketManager(socketManager);
        socketManager.setChatHandler(chatHandler);

        logger.info("Connecting to backend: " + config.connection().url());
        socketManager.connect();
    }

    /**
     * Copy default config.yml from resources if it doesn't exist
     */
    private void copyDefaultConfig() {
        Path configPath = dataFolder.resolve("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
                    HYTALE_LOGGER.atInfo().log("Default config.yml created at " + configPath);
                } else {
                    HYTALE_LOGGER.atWarning().log("config.yml not found in resources");
                }
            } catch (IOException e) {
                HYTALE_LOGGER.atSevere().log("Failed to copy default config: " + e.getMessage());
            }
        }
    }

    /**
     * Register event listeners with Hytale event system
     */
    private void registerEventListeners() {
        EventRegistry eventRegistry = getEventRegistry();
        logger.info("Registering event listeners...");

        // Register player chat event listener for NPC interactions
        eventRegistry.registerGlobal(PlayerChatEvent.class, event -> {
            try {
                // Get player information
                String playerUuid = event.getSender().getUuid().toString();
                String playerName = event.getSender().getUsername();
                String message = event.getContent();

                // Skip if message is empty or starts with command prefix
                if (message == null || message.isEmpty() || message.startsWith("/")) {
                    return;
                }

                logger.debug("Player chat: " + playerName + " -> " + message);

                // Get player location from their transform
                com.hypixel.hytale.math.vector.Transform playerTransform = event.getSender().getTransform();
                com.hypixel.hytale.math.vector.Vector3d playerPos = playerTransform.getPosition();

                dev.hycompanion.plugin.api.Location playerLocation = new dev.hycompanion.plugin.api.Location(
                        playerPos.getX(),
                        playerPos.getY(),
                        playerPos.getZ(),
                        hytaleAPI.getWorldName());

                // Create player object for chat handler
                dev.hycompanion.plugin.api.GamePlayer gamePlayer = new dev.hycompanion.plugin.api.GamePlayer(
                        playerUuid,
                        playerName,
                        event.getSender().getUuid(),
                        playerLocation);

                // Use ChatHandler which already handles NPC proximity detection
                // It uses config.gameplay().chatRange() to find nearby NPCs
                boolean handled = chatHandler.handleChat(gamePlayer, message);

                if (handled) {
                    logger.debug("Chat message routed to NPC for player: " + playerName);
                } else {
                    logger.debug("No nearby NPCs for player: " + playerName);
                }
            } catch (Exception e) {
                logger.error("Error processing player chat event: " + e.getMessage());
                Sentry.captureException(e);
            }
        });

        logger.info("Event listeners registered with Hytale server");
    }

    /**
     * Register plugin commands with Hytale command system
     */
    private void registerCommands() {
        logger.info("Registering commands...");
        getCommandRegistry().registerCommand(new HycompanionCommand(this));
        logger.info("Commands registered: /hycompanion (aliases: /hyc, /hc)");
    }

    /**
     * Get current online player count from Hytale server
     */
    private int getOnlinePlayerCount() {
        try {
            return com.hypixel.hytale.server.core.universe.Universe.get().getPlayers().size();
        } catch (Exception e) {
            Sentry.captureException(e);
            return 0;
        }
    }

    // ========== Getters ==========

    public PluginConfig getPluginConfig() {
        return config;
    }

    public PluginLogger getPluginLogger() {
        return logger;
    }

    public HytaleAPI getHytaleAPI() {
        return hytaleAPI;
    }

    public SocketManager getSocketManager() {
        return socketManager;
    }

    public NpcManager getNpcManager() {
        return npcManager;
    }

    public ChatHandler getChatHandler() {
        return chatHandler;
    }

    public RoleGenerator getRoleGenerator() {
        return roleGenerator;
    }

    public Path getPluginDataFolder() {
        return dataFolder;
    }

    // ========== Event Handlers ==========

    /**
     * Called when Hytale finishes loading/reloading all NPC entities.
     * This is the perfect time to discover and bind existing NPC entities
     * that were persisted from previous sessions.
     * 
     * IMPORTANT: Always forces rediscovery because entity references may have
     * been invalidated by the reload. This handles both:
     * - Initial startup (first discovery)
     * - Reconnection (role file changes trigger entity reload, invalidating
     * references)
     */
    private void onAllNpcsLoaded(AllNPCsLoadedEvent event) {
        logger.info("AllNPCsLoadedEvent received - scheduling entity discovery...");
        logger.info("  Total NPCs in server: " + event.getAllNPCs().size());
        logger.info("  NPCs loaded this cycle: " + event.getLoadedNPCs().size());

        // Always force rediscovery when this event fires, as entity references
        // may have been invalidated by the reload (e.g., after role file changes
        // during reconnection). This ensures we always have fresh entity references.
        scheduleEntityDiscovery("NPCsLoaded", 2000, true);
    }

    /**
     * Called when a player is added to a world.
     * Fallback discovery trigger if AllNPCsLoadedEvent didn't find entities.
     */
    private void onPlayerAddedToWorld(AddPlayerToWorldEvent event) {

        // Get Player entity component to check permissions
        Player player = event.getHolder().getComponent(Player.getComponentType());
        boolean isAdmin = player != null && (player.hasPermission("*") || player.hasPermission("hycompanion.admin"));

        // Check if API key is not set (null, empty, or default value)
        String apiKey = config.connection().apiKey();
        boolean isKeySet = apiKey != null && !apiKey.trim().isEmpty() && !"YOUR_SERVER_API_KEY".equals(apiKey);

        if (!isKeySet && isAdmin) {
            // Check if player is admin/op (has wildcard permission or specific register
            // permission)
            // Note: Hytale players implement PermissionHolder, but isOp() is not available
            // directly
            player.sendMessage(
                    Message.raw("Hycompanion API key not set. Please use /hycompanion register [key] to set it.")
                            .color("#FF5555"));
            player.sendMessage(Message.raw("You can get a key on https://app.hycompanion.dev")
                    .color("#AAAAAA"));
        }

        // Check if manifest was created this session (restart required for NPC roles)
        if (roleGenerator != null && roleGenerator.isManifestCreatedThisSession() && isAdmin) {
            player.sendMessage(Message.raw("").color("#FF5555"));
            player.sendMessage(
                    Message.raw("[Hycompanion] SERVER RESTART REQUIRED!")
                            .color("#FF0000"));
            player.sendMessage(
                    Message.raw("The asset pack manifest was just created.")
                            .color("#FF5555"));
            player.sendMessage(
                    Message.raw("Please restart the server to enable NPC roles.")
                            .color("#FF5555"));
            player.sendMessage(Message.raw("").color("#FF5555"));
        }

        // Validate existing NPCs and trigger rediscovery if needed
        // This handles cases where entity references became stale after player disconnects
        if (entityDiscoveryDone) {
            boolean hasValidNpcs = validateAndRediscoverIfNeeded();
            if (hasValidNpcs) {
                logger.debug("Entity discovery already done and valid NPCs exist, skipping rediscovery");
                return;
            }
            // validateAndRediscoverIfNeeded already triggered rediscovery, just log
            logger.info("Entity discovery was done but no valid NPCs found - rediscovery triggered");
            return;
        }

        logger.info("First player joining world - triggering entity discovery...");
        logger.info("  World: " + event.getWorld().getName());

        scheduleEntityDiscovery("PlayerJoin", 3000, false);
    }

    /**
     * Handle player disconnect to clear NPC follow targets.
     * This prevents "Invalid entity reference" errors during server shutdown
     * when NPCs have references to player entities that are being removed.
     * 
     * Note: During server shutdown, this event fires on ShutdownThread, not WorldThread.
     * Hytale entity APIs require WorldThread, so we use safe clear that doesn't access
     * entity APIs during shutdown.
     */
    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        try {
            PlayerRef playerRef = event.getPlayerRef();
            if (playerRef == null) {
                return;
            }
            
            String playerName = playerRef.getUsername();
            String threadName = Thread.currentThread().getName();
            boolean isWorldThread = threadName.contains("WorldThread");
            
            if (isWorldThread) {
                // Normal disconnect - can use full cleanup with entity APIs
                logger.debug("Player disconnecting: " + playerName + " - clearing NPC follow targets");
                if (hytaleAPI != null) {
                    ((HytaleServerAdapter) hytaleAPI).clearFollowTargetsForPlayer(playerName);
                }
            } else {
                // Shutdown disconnect - can only clear from our maps, not access Hytale APIs
                logger.debug("Player disconnecting on " + threadName + " - safe clearing only");
                if (hytaleAPI != null) {
                    // Use safe clear that doesn't access Hytale entity APIs
                    ((HytaleServerAdapter) hytaleAPI).clearFollowTargetsSafe(playerName);
                }
            }
        } catch (Exception e) {
            logger.debug("Error handling player disconnect: " + e.getMessage());
        }
    }

    // Guard to prevent duplicate early cleanup
    private volatile boolean earlyShutdownDone = false;

    /**
     * Handle server shutdown event - triggered early in shutdown sequence.
     * Priority -48 = DISCONNECT_PLAYERS phase - runs BEFORE players are removed.
     * 
     * CRITICAL: We do as little as possible here to avoid interfering with Hytale's
     * shutdown sequence. Specifically, we do NOT clear entity references because
     * doing so can cause "Invalid entity reference" errors that break Hytale's
     * player removal and chunk saving.
     * 
     * The only things we do here:
     * 1. Disconnect from backend (stop incoming events)
     * 2. Stop our periodic background tasks
     * 3. Set the shutdown flag (without blocking world operations)
     */
    private void onServerShutdown(ShutdownEvent event) {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        
        // Prevent duplicate execution
        if (earlyShutdownDone) {
            logger.debug("[Shutdown] Early cleanup already done, skipping (thread: " + threadName + ")");
            return;
        }
        earlyShutdownDone = true;
        
        logger.debug("[Shutdown] ============================================");
        logger.debug("[Shutdown] SHUTDOWN EVENT RECEIVED");
        logger.debug("[Shutdown] Phase: DISCONNECT_PLAYERS (priority -48)");
        logger.debug("[Shutdown] Thread: " + threadName);
        logger.debug("[Shutdown] Timestamp: " + startTime);
        logger.debug("[Shutdown] ============================================");
        
        try {
            // STEP 1: Disconnect from backend to stop incoming events
            logger.debug("[Shutdown] Step 1/3: Disconnecting from backend...");
            long stepStart = System.currentTimeMillis();
            if (socketManager != null) {
                socketManager.disconnect();
                logger.debug("[Shutdown] Step 1/3: Backend disconnected in " + (System.currentTimeMillis() - stepStart) + "ms");
            } else {
                logger.debug("[Shutdown] Step 1/3: SocketManager is null, skipping");
            }
            
            // STEP 2: Stop all periodic background tasks
            logger.debug("[Shutdown] Step 2/3: Stopping NPC greeting service...");
            stepStart = System.currentTimeMillis();
            if (greetingService != null) {
                greetingService.stopProximityChecks();
                logger.debug("[Shutdown] Step 2/3: Greeting service stopped in " + (System.currentTimeMillis() - stepStart) + "ms");
            } else {
                logger.debug("[Shutdown] Step 2/3: GreetingService is null, skipping");
            }
            
            // STEP 3: Set shutdown flag (but DON'T clear entity references here!)
            // Clearing entity references during early shutdown can cause
            // "Invalid entity reference" errors that break Hytale's player removal
            // and chunk saving. We defer entity cleanup to the final shutdown phase.
            logger.debug("[Shutdown] Step 3/3: Setting shutdown flag...");
            stepStart = System.currentTimeMillis();
            if (shutdownManager != null) {
                shutdownManager.setShuttingDown();
                logger.debug("[Shutdown] Step 3/3: Shutdown flag set in " + (System.currentTimeMillis() - stepStart) + "ms");
            } else {
                logger.debug("[Shutdown] Step 3/3: ShutdownManager is null, skipping");
            }
            
            long totalTime = System.currentTimeMillis() - startTime;
            logger.debug("[Shutdown] ============================================");
            logger.debug("[Shutdown] EARLY CLEANUP COMPLETE");
            logger.debug("[Shutdown] Total time: " + totalTime + "ms");
            logger.debug("[Shutdown] NOTE: Entity references NOT cleared - deferring to final shutdown");
            logger.debug("[Shutdown] ============================================");
        } catch (Exception e) {
            logger.error("[Shutdown] ERROR during early shutdown: " + e.getMessage(), e);
            Sentry.captureException(e);
        }
    }

    /**
     * Run entity discovery after reconnection.
     * Called by SocketManager after bulk sync completes on reconnect.
     */
    private void runEntityDiscoveryAfterReconnect() {
        scheduleEntityDiscovery("Reconnect", 2000, true);
    }

    // ========== Unified Entity Discovery ==========

    /**
     * Schedule entity discovery with configurable delay and behavior.
     * 
     * This unified method handles all discovery triggers:
     * - Startup: After AllNPCsLoadedEvent, waits for entity reload
     * - PlayerJoin: When first player joins, waits for chunks to load
     * - Reconnect: After backend reconnection, always runs (bypasses
     * entityDiscoveryDone)
     * 
     * @param trigger  Human-readable label for logging
     * @param delayMs  Milliseconds to wait before discovery (allows Hytale to
     *                 finish loading)
     * @param forceRun If true, runs even if entityDiscoveryDone is true (for
     *                 reconnect)
     */
    private void scheduleEntityDiscovery(String trigger, long delayMs, boolean forceRun) {
        if (npcManager == null || hytaleAPI == null) {
            logger.debug("[" + trigger + "] Cannot run discovery - NPC manager or API not ready");
            return;
        }

        if (npcManager.getNpcCount() == 0) {
            logger.debug("[" + trigger + "] No NPCs synced yet, skipping discovery");
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                if (delayMs > 0) {
                    logger.info("[" + trigger + "] Waiting " + delayMs + "ms before entity discovery...");
                    Thread.sleep(delayMs);
                }

                // Skip if already done (unless forceRun is true, e.g. for reconnect)
                if (!forceRun && entityDiscoveryDone) {
                    logger.debug("[" + trigger + "] Discovery already completed, skipping");
                    return;
                }

                runEntityDiscoveryNow(trigger);

            } catch (InterruptedException e) {
                logger.debug("[" + trigger + "] Discovery interrupted: " + e.getMessage());
            }
        });
    }

    /**
     * Execute entity discovery synchronously (call from virtual thread).
     * Scans all synced NPCs and binds their entity references.
     * 
     * @param trigger Label for logging context
     * @return Discovery results [discovered, alreadyValid, failed]
     */
    private int[] runEntityDiscoveryNow(String trigger) {
        // CLEANUP: Remove "Zombie" thinking indicators from previous session/crash
        // This must be done BEFORE discovery to ensure we don't accidentally remove
        // active ones
        // (though the method has safeguards for that too)
        if (hytaleAPI != null) {
            hytaleAPI.removeZombieThinkingIndicators();
        }

        logger.info("[" + trigger + "] Running entity discovery for " + npcManager.getNpcCount() + " synced NPCs...");

        int discovered = 0;
        int alreadyValid = 0;
        int failed = 0;

        Set<String> npcIdAnimationsSent = new HashSet<>();
        // Loop over defined NPCs to find their instances in the world
        for (dev.hycompanion.plugin.core.npc.NpcData npcData : npcManager.getAllNpcs()) {
            try {
                // Discover existing instances for this role
                java.util.List<java.util.UUID> entityUuids = hytaleAPI
                        .discoverExistingNpcInstances(npcData.externalId());

                for (java.util.UUID entityUuid : entityUuids) {
                    if (entityUuid != null) {
                        // Get the NPC's current location immediately (optional check)
                        var location = hytaleAPI.getNpcInstanceLocation(entityUuid);
                        if (location.isPresent()) {
                            npcManager.bindEntity(npcData.externalId(), entityUuid);
                            logger.info(
                                    "[" + trigger + "] Bound: " + npcData.externalId() + " -> " + entityUuid +
                                            " at " + location.get().toCoordString());
                        } else {
                            // Even if location unavailable, bind it
                            npcManager.bindEntity(npcData.externalId(), entityUuid);
                            logger.warn(
                                    "[" + trigger + "] Bound: " + npcData.externalId() + " -> " + entityUuid +
                                            " (location not available)");
                        }

                        // We count individual instances associated
                        discovered++;
                        entityDiscoveryDone = true;

                        if (!npcIdAnimationsSent.contains(npcData.id())) {
                            // Report available animations to backend for dynamic MCP tool generation
                            var npcInstance = hytaleAPI.getNpcInstance(entityUuid);
                            if (npcInstance != null) {
                                reportNpcAnimations(npcInstance);
                                npcIdAnimationsSent.add(npcData.id());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("[" + trigger + "] Failed for " + npcData.id() + ": " + e.getMessage());
                failed++;
            }
        }

        logger.info("[" + trigger + "] Discovery complete: " + discovered + " bound, " +
                alreadyValid + " already valid" + (failed > 0 ? ", " + failed + " failed" : ""));

        return new int[] { discovered, alreadyValid, failed };
    }

    /**
     * Validate that tracked NPC entities are still valid.
     * If no valid NPCs are found, trigger a rediscovery.
     * This is called periodically and on player join to ensure NPCs are always trackable.
     * 
     * Note: This handles partial staleness - even if some NPCs are valid, we still
     * trigger rediscovery to ensure ALL NPCs are properly tracked (some might be
     * in unloaded chunks or have stale references while others don't).
     * 
     * @return true if valid NPCs exist (rediscovery may still have been triggered for missing ones)
     */
    public boolean validateAndRediscoverIfNeeded() {
        if (hytaleAPI == null || npcManager == null) {
            logger.debug("[Validation] Cannot validate - API or manager not ready");
            return false;
        }

        // Check validity of all tracked NPC instances
        var npcInstances = hytaleAPI.getNpcInstances();
        int validCount = 0;
        int invalidCount = 0;
        int totalExpected = npcManager.getNpcCount(); // Number of NPC types synced from backend
        
        for (var npc : npcInstances) {
            if (npc.entityRef() != null && hytaleAPI.isNpcInstanceEntityValid(npc.entityUuid())) {
                validCount++;
            } else {
                invalidCount++;
            }
        }

        logger.debug("[Validation] NPC validation: " + validCount + " valid, " + invalidCount + " invalid/stale");

        // Trigger rediscovery if:
        // 1. No valid NPCs at all, OR
        // 2. Some NPCs are invalid (partial staleness - could be due to chunk unloading, respawn, etc.)
        // 3. We have fewer valid instances than expected NPC types (some NPCs never got discovered)
        boolean needsRediscovery = validCount == 0 || invalidCount > 0 || validCount < totalExpected;

        if (needsRediscovery) {
            logger.warn("[Validation] NPC staleness detected (valid=" + validCount + ", invalid=" + invalidCount + 
                       ", expected=" + totalExpected + "). Triggering rediscovery...");
            
            // Reset entityDiscoveryDone to allow rediscovery
            // scheduleEntityDiscovery will handle the delay for chunk loading
            entityDiscoveryDone = false;
            scheduleEntityDiscovery("Validation", 3000, true);
        }
        
        return validCount > 0;
    }

    /**
     * Report available animations for an NPC to the backend.
     * This enables dynamic MCP tool generation based on the NPC's model animations.
     * 
     * @param npcInstanceData The NPC instance data
     */
    private void reportNpcAnimations(dev.hycompanion.plugin.core.npc.NpcInstanceData npcInstanceData) {
        if (socketManager == null || !socketManager.isConnected()) {
            logger.info("[Animation] Cannot report animations - not connected");
            return;
        }

        try {
            var animations = hytaleAPI.getAvailableAnimations(npcInstanceData.entityUuid());
            if (animations != null && !animations.isEmpty()) {
                socketManager.sendNpcAnimations(npcInstanceData.npcData().id(), animations);
            }
        } catch (Exception e) {
            logger.info("[Animation] Failed to report animations for " + npcInstanceData.npcData().id() + ": "
                    + e.getMessage());
        }
    }

    public void updateApiKey(String newApiKey) {
        try {
            Path configPath = dataFolder.resolve("config.yml");
            // Use shared helper to update file
            PluginConfig.updateApiKeyInFile(configPath, newApiKey);
            logger.info("API Key updated in config.yml");

            // Reload just the config and update socket auth without full re-init
            reload();

        } catch (IOException e) {
            logger.error("Failed to update API key", e);
        }
    }

    public void reload() {
        if (logger != null)
            logger.info("Reloading Hycompanion...");

        try {
            // Reload config
            config = PluginConfig.load(dataFolder.resolve("config.yml"));
            if (logger != null)
                logger.setDebugMode(config.gameplay().debugMode());

            // Update components with new config
            if (actionExecutor != null)
                actionExecutor.setConfig(config);
            if (chatHandler != null)
                chatHandler.setConfig(config);

            // Update SocketManager
            if (socketManager != null) {
                socketManager.setConfig(config);
                socketManager.updateApiKey(config.connection().apiKey());
            } else {
                initializeSocketConnection();
            }

            // Auto after reconnect
            // scheduleEntityDiscovery("Reload", 2000, true);

            if (logger != null)
                logger.info("Reload complete!");
        } catch (Exception e) {
            if (logger != null)
                logger.error("Failed to reload configuration", e);
            Sentry.captureException(e);
        }
    }

    /**
     * Manually trigger entity rediscovery. Called by /hycompanion rediscover
     * command.
     * Runs synchronously on a virtual thread and returns results.
     * 
     * @param resultCallback Callback with results [discovered, alreadyValid,
     *                       failed], or null on error
     */
    public void triggerManualRediscovery(java.util.function.Consumer<int[]> resultCallback) {
        if (npcManager == null || hytaleAPI == null) {
            logger.warn("[Command] Cannot rediscover - NPC manager or API not ready");
            resultCallback.accept(null);
            return;
        }

        if (npcManager.getNpcCount() == 0) {
            logger.warn("[Command] No NPCs synced, nothing to rediscover");
            resultCallback.accept(new int[] { 0, 0, 0 });
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                int[] results = runEntityDiscoveryNow("Command");
                resultCallback.accept(results);
            } catch (Exception e) {
                logger.error("[Command] Rediscovery failed: " + e.getMessage());
                Sentry.captureException(e);
                resultCallback.accept(null);
            }
        });
    }
}
