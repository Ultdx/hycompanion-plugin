package dev.hycompanion.plugin.role;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Generates Hytale NPC Role JSON files from NPC data.
 * 
 * This class manages role file generation and caching:
 * - During setup(): Loads cached roles from previous runs
 * - During runtime: Generates/updates role files after NPC sync via socket
 * - Role files are cached for next startup
 */
public class RoleGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path rolesDirectory;
    private final Path cacheDirectory;
    private final Path modDirectory;
    private final PluginLogger logger;

    public RoleGenerator(Path modDirectory, Path dataFolder, PluginLogger logger) {
        this.modDirectory = modDirectory;
        this.rolesDirectory = modDirectory.resolve("Server").resolve("NPC").resolve("Roles");
        this.cacheDirectory = dataFolder.resolve("role_cache");
        this.logger = logger;
    }

    /**
     * Ensure the mod folder has a manifest.json so Hytale loads it as an asset
     * pack.
     * This is required for dynamic role files to be loaded.
     */
    public void ensureModManifest() {
        Path manifestPath = modDirectory.resolve("manifest.json");
        if (Files.exists(manifestPath)) {
            logger.debug("Mod manifest already exists at: " + manifestPath);
            return;
        }

        try {
            String manifest = """
                    {
                        "Group": "dev.hycompanion",
                        "Name": "HycompanionAssets",
                        "Version": "1.1.0",
                        "Description": "Hycompanion dynamic NPC role assets"
                    }
                    """;
            Files.writeString(manifestPath, manifest);
            logger.info("Created mod manifest at: " + manifestPath);
            logger.warn("RESTART REQUIRED: A manifest.json was created for the asset pack.");
            logger.warn("Please restart the server for Hytale to load the new NPC roles.");
        } catch (Exception e) {
            logger.error("Failed to create mod manifest: " + e.getMessage());
        }
    }

    /**
     * Load cached role files from previous run.
     * Called during setup() before asset loading.
     * 
     * NOTE: Hytale Server dynamically loads NPC roles from the asset pack on
     * startup.
     * This means role files added AFTER the manifest.json exists will be loaded
     * automatically on the next server restart - no special caching/restart logic
     * needed.
     * 
     * The only time a "restart required" message is shown is when manifest.json is
     * first created, as Hytale needs to discover the new asset pack.
     * 
     * @return Number of cached roles loaded
     */

    public int loadCachedRoles() {
        logger.info("Loading cached role files from previous run...");

        // Ensure the mod folder has a manifest for asset loading
        ensureModManifest();

        try {
            if (!Files.exists(cacheDirectory)) {
                logger.info("No cached roles found (first run)");
                return 0;
            }

            // Create roles directory if needed
            Files.createDirectories(rolesDirectory);

            // Copy cached roles to assets directory
            int copied = 0;
            try (var stream = Files.list(cacheDirectory)) {
                for (Path cachedFile : stream.toList()) {
                    if (cachedFile.toString().endsWith(".json")) {
                        Path targetFile = rolesDirectory.resolve(cachedFile.getFileName());
                        Files.copy(cachedFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        copied++;
                        logger.debug("Loaded cached role: " + cachedFile.getFileName());
                    }
                }
            }

            if (copied > 0) {
                logger.info("Loaded " + copied + " cached role files");
            } else {
                logger.info("No cached role files found");
            }
            return copied;

        } catch (Exception e) {
            logger.warn("Failed to load cached roles: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Fetch roles via Socket.IO during setup phase (synchronous with timeout).
     * This runs BEFORE asset loading to ensure roles are up-to-date.
     * 
     * @param backendUrl     The backend URL (e.g., http://192.168.1.169:3000)
     * @param apiKey         The server API key
     * @param timeoutSeconds Timeout for socket connection
     * @return Number of roles fetched and generated
     */
    public int fetchRolesViaSocket(String backendUrl, String apiKey, int timeoutSeconds) {
        logger.info("Fetching NPC roles via socket from backend...");

        var latch = new java.util.concurrent.CountDownLatch(1);
        var rolesGenerated = new java.util.concurrent.atomic.AtomicInteger(0);
        var connectionError = new java.util.concurrent.atomic.AtomicReference<String>(null);

        try {
            // Create socket connection
            io.socket.client.IO.Options options = io.socket.client.IO.Options.builder()
                    .setAuth(java.util.Map.of("apiKey", apiKey))
                    .setReconnection(false)
                    .setTransports(new String[] { "websocket", "polling" })
                    .build();

            io.socket.client.Socket socket = io.socket.client.IO.socket(java.net.URI.create(backendUrl), options);

            // Handle connection
            socket.on(io.socket.client.Socket.EVENT_CONNECT, args -> {
                logger.debug("Socket connected for role fetch");
                // Send connect event to trigger NPC sync
                var payload = new org.json.JSONObject();
                try {
                    payload.put("apiKey", apiKey);
                    var serverInfo = new org.json.JSONObject();
                    serverInfo.put("version", "1.1.0-SNAPSHOT");
                    serverInfo.put("playerCount", 0);
                    payload.put("serverInfo", serverInfo);
                } catch (Exception e) {
                    logger.error("Failed to build connect payload: " + e.getMessage());
                }
                socket.emit("PLUGIN_CONNECT", payload);
            });

            // Handle NPC sync
            socket.on("BACKEND_NPC_SYNC", args -> {
                try {
                    org.json.JSONObject json = (org.json.JSONObject) args[0];
                    String action = json.getString("action");

                    if ("create".equals(action) || "update".equals(action)) {
                        org.json.JSONObject npcJson = json.getJSONObject("npc");
                        NpcRoleData roleData = NpcRoleData.fromNpcJson(npcJson);
                        if (generateAndCacheRole(roleData)) {
                            rolesGenerated.incrementAndGet();
                            logger.debug("Role fetched for: " + roleData.externalId);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to process NPC sync during setup: " + e.getMessage());
                }
            });

            // Handle sync complete (or timeout will trigger)
            socket.on("BACKEND_SYNC_COMPLETE", args -> {
                logger.debug("Sync complete signal received");
                latch.countDown();
            });

            // Handle errors
            socket.on(io.socket.client.Socket.EVENT_CONNECT_ERROR, args -> {
                String error = args.length > 0 ? args[0].toString() : "Unknown error";
                connectionError.set(error);
                logger.warn("Socket connection error: " + error);
                latch.countDown();
            });

            // Connect
            socket.connect();

            // Wait for sync with timeout
            boolean completed = latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);

            // Disconnect
            socket.disconnect();
            socket.close();

            if (!completed) {
                logger.warn("Socket sync timed out after " + timeoutSeconds + "s - using cached roles");
                return 0;
            }

            if (connectionError.get() != null) {
                logger.warn("Socket connection failed: " + connectionError.get() + " - using cached roles");
                return 0;
            }

            int count = rolesGenerated.get();
            if (count > 0) {
                logger.info("Fetched and generated " + count + " NPC roles from backend");
            } else {
                logger.info("No NPCs configured on backend");
            }
            return count;

        } catch (Exception e) {
            logger.warn("Socket role fetch failed: " + e.getMessage() + " - using cached roles");
            return 0;
        }
    }

    /**
     * Generate a role file for a single NPC and cache it.
     * Called after NPC sync via socket.
     * 
     * @return true if role file was generated successfully
     */
    public boolean generateAndCacheRole(NpcRoleData npc) {
        try {
            // Create directories if needed
            Files.createDirectories(rolesDirectory);
            Files.createDirectories(cacheDirectory);

            // Generate role JSON
            JsonObject role = buildRoleJson(npc);
            String jsonContent = GSON.toJson(role);

            // Write to assets directory (for current run if asset reloading is supported)
            Path roleFile = rolesDirectory.resolve(npc.externalId + ".json");
            boolean updated = writeIfChanged(roleFile, jsonContent);

            // Also cache for next startup
            Path cacheFile = cacheDirectory.resolve(npc.externalId + ".json");
            writeIfChanged(cacheFile, jsonContent);

            if (updated) {
                logger.info("Generated and cached role for: " + npc.externalId + " with presets: " + 
                    (npc.behaviorPresets != null ? String.join(",", npc.behaviorPresets) : "default(idle)") +
                    " (roleType: " + npc.roleType + ", reference: " + npc.reference + ")");
                // Log the full JSON for debugging
                logger.info("[RoleDebug] Full JSON for " + npc.externalId + ":\n" + jsonContent);
            }
            return true;

        } catch (IOException e) {
            logger.error("Failed to generate role for " + npc.externalId + ": " + e.getMessage());
            return false;
        }
    }

    private boolean writeIfChanged(Path path, String content) throws IOException {
        if (Files.exists(path)) {
            String existing = Files.readString(path);
            if (existing.equals(content)) {
                logger.debug("Role file unchanged, skipping write: " + path);
                return false;
            }
            logger.info("Role file changed, updating: " + path);
        } else {
            logger.info("Role file new, creating: " + path);
        }
        Files.writeString(path, content);
        return true;
    }

    /**
     * Remove a role file and its cache.
     * Called when NPC is deleted.
     */
    public void removeRole(String externalId) {
        try {
            Path roleFile = rolesDirectory.resolve(externalId + ".json");
            Path cacheFile = cacheDirectory.resolve(externalId + ".json");

            Files.deleteIfExists(roleFile);
            Files.deleteIfExists(cacheFile);

            logger.debug("Removed role for: " + externalId);
        } catch (IOException e) {
            logger.warn("Failed to remove role for " + externalId + ": " + e.getMessage());
        }
    }

    /**
     * Build role JSON object for a given NPC.
     * Supports both new inheritance system (Variant/Abstract) and legacy flat
     * format.
     */
    private JsonObject buildRoleJson(NpcRoleData npc) {
        logger.info("Building Role JSON for " + npc.displayName + " | RoleType: " + npc.roleType +
                " | Reference: " + npc.reference + " | Appearance: " + npc.appearance);

        // ============================================================================
        // HYCOMPANION TEMPLATE - Use Generic type for full control (supports
        // DisplayNames)
        // ============================================================================
        if ("Variant".equals(npc.roleType) && "Template_Hycompanion_NPC".equals(npc.reference)) {
            return buildGenericRoleJson(npc);
        }

        // ============================================================================
        // VARIANT ROLE - Uses inheritance from a parent template
        // ============================================================================
        if ("Variant".equals(npc.roleType) && npc.reference != null && !npc.reference.isEmpty()) {
            return buildVariantRoleJson(npc);
        }

        // ============================================================================
        // ABSTRACT / LEGACY ROLE - Full role definition
        // ============================================================================
        return buildAbstractRoleJson(npc);
    }

    /**
     * Build a Generic role JSON based on Template_Hycompanion_NPC structure.
     * Generic roles are fully standalone and support DisplayNames as a root
     * property.
     * This is used instead of Variant when we need features that Variant doesn't
     * support.
     */
    private JsonObject buildGenericRoleJson(NpcRoleData npc) {
        JsonObject role = new JsonObject();

        // Type: Generic for standalone spawnable role
        role.addProperty("Type", "Generic");

        // State configuration
        role.addProperty("StartState", "Idle");
        role.addProperty("DefaultSubState", "Default");

        // Appearance - direct value (not computed since Generic doesn't use Parameters
        // the same way)
        String appearance = getParameterStringValue(npc.parameters, "Appearance", npc.appearance);
        role.addProperty("Appearance", appearance != null ? appearance : "Outlander");

        // MaxHealth
        Integer maxHealth = getParameterIntValue(npc.parameters, "MaxHealth", npc.maxHealth);
        role.addProperty("MaxHealth", maxHealth != null ? maxHealth : 100);

        // Attitudes
        String playerAttitude = getParameterStringValue(npc.parameters, "DefaultPlayerAttitude",
                npc.defaultPlayerAttitude);
        role.addProperty("DefaultPlayerAttitude",
                playerAttitude != null ? normalizeAttitude(playerAttitude) : "Neutral");

        String npcAttitude = getParameterStringValue(npc.parameters, "DefaultNPCAttitude", npc.defaultNPCAttitude);
        role.addProperty("DefaultNPCAttitude", npcAttitude != null ? normalizeAttitude(npcAttitude) : "Neutral");

        // NameTranslationKey
        String nameKey = getParameterStringValue(npc.parameters, "NameTranslationKey", npc.nameTranslationKey);
        if (nameKey == null || nameKey.isEmpty()) {
            nameKey = "server.npcRoles." + npc.externalId.toLowerCase() + ".name";
        }
        role.addProperty("NameTranslationKey", nameKey);

        // DropList
        String dropList = getParameterStringValue(npc.parameters, "DropList", npc.dropList);
        if (dropList != null && !dropList.isEmpty() && !"Empty".equals(dropList)) {
            role.addProperty("DropList", dropList);
        }

        // DisplayNames - ROOT LEVEL property (this is why we use Generic instead of
        // Variant!)
        com.google.gson.JsonArray displayNamesArray = new com.google.gson.JsonArray();
        if (npc.displayNames != null && npc.displayNames.length > 0) {
            for (String name : npc.displayNames) {
                displayNamesArray.add(name);
            }
        } else if (npc.displayName != null && !npc.displayName.isEmpty()) {
            displayNamesArray.add(npc.displayName);
        }
        if (displayNamesArray.size() > 0) {
            role.add("DisplayNames", displayNamesArray);
        }

        // Physics & behavior properties from template
        // Physics & behavior properties from template or NpcRoleData
        role.addProperty("Invulnerable", npc.invulnerable);
        role.addProperty("ApplyAvoidance", npc.applyAvoidance);
        role.addProperty("ApplySeparation", npc.applySeparation);
        role.addProperty("SeparationDistance", npc.separationDistance);
        role.addProperty("Inertia", npc.inertia);
        role.addProperty("KnockbackScale", npc.knockbackScale);
        role.addProperty("BreathesInAir", npc.breathesInAir);
        role.addProperty("BreathesInWater", npc.breathesInWater);

        role.addProperty("InventorySize", npc.inventorySize);
        role.addProperty("HotbarSize", npc.hotbarSize);
        role.addProperty("DeathAnimationTime", npc.deathAnimationTime);
        if (npc.despawnAnimationTime != null) {
            role.addProperty("DespawnAnimationTime", npc.despawnAnimationTime);
        } else {
            role.addProperty("DespawnAnimationTime", 0.8);
        }
        role.addProperty("SpawnLockTime", npc.spawnLockTime);
        if (npc.spawnViewDistance != null) {
            role.addProperty("SpawnViewDistance", npc.spawnViewDistance);
        } else {
            role.addProperty("SpawnViewDistance", 75);
        }
        if (npc.collisionDistance != null) {
            role.addProperty("CollisionDistance", npc.collisionDistance);
        } else {
            role.addProperty("CollisionDistance", 5);
        }
        if (npc.collisionRadius != null) {
            role.addProperty("CollisionRadius", npc.collisionRadius);
        } else {
            role.addProperty("CollisionRadius", -1);
        }

        // MotionControllerList
        com.google.gson.JsonArray motionControllers = new com.google.gson.JsonArray();

        // Use custom motion controllers if defined, otherwise default
        if (npc.motionControllerList != null && npc.motionControllerList.length() > 0) {
            // Need to convert org.json.JSONArray to Gson JsonArray
            for (int i = 0; i < npc.motionControllerList.length(); i++) {
                org.json.JSONObject mc = npc.motionControllerList.getJSONObject(i);
                JsonObject gsonMc = new JsonObject();
                for (String key : mc.keySet()) {
                    addValueToJsonObject(gsonMc, key, mc.get(key));
                }
                motionControllers.add(gsonMc);
            }
        } else if (npc.motionControllers != null && !npc.motionControllers.isEmpty()) {
            // Legacy format support
            Iterator<String> keys = npc.motionControllers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                org.json.JSONObject mc = npc.motionControllers.getJSONObject(key);
                JsonObject gsonMc = new JsonObject();
                // Add defaults if missing
                if (!mc.has("Type"))
                    gsonMc.addProperty("Type", "Walk");

                for (String subKey : mc.keySet()) {
                    addValueToJsonObject(gsonMc, subKey, mc.get(subKey));
                }
                motionControllers.add(gsonMc);
            }
        } else {
            // Default Walk Controller
            JsonObject walkController = new JsonObject();
            walkController.addProperty("Type", "Walk");

            // Get MaxSpeed from parameters or use default
            Double maxSpeed = getParameterDoubleValue(npc.parameters, "MaxSpeed", 5.0);
            walkController.addProperty("MaxWalkSpeed", maxSpeed);
            walkController.addProperty("Acceleration", 25);
            walkController.addProperty("MaxRotationSpeed", 180);
            walkController.addProperty("MinJumpHeight", 1.2);
            walkController.addProperty("Gravity", 10);
            walkController.addProperty("MaxFallSpeed", 15);
            motionControllers.add(walkController);
        }
        role.add("MotionControllerList", motionControllers);

        // Get FollowDistance from parameters or use default
        Double followDistance = getParameterDoubleValue(npc.parameters, "FollowDistance", 2.0);

        // Check if "follow" preset is present
        boolean hasFollowPreset = npc.behaviorPresets != null &&
                java.util.Arrays.stream(npc.behaviorPresets).anyMatch(p -> "follow".equalsIgnoreCase(p));

        // Instructions - Generate based on behavior presets
        // Get FollowDistance from parameters (used if "follow" preset is present)
        com.google.gson.JsonArray instructions = generateBehaviorInstructions(npc.behaviorPresets, followDistance);
        role.add("Instructions", instructions);

        // InteractionInstruction - Only add Follow state setter if "follow" preset is
        // present
        if (hasFollowPreset) {
            JsonObject interactionInstruction = new JsonObject();
            com.google.gson.JsonArray interactionInstructions = new com.google.gson.JsonArray();

            JsonObject followTrigger = new JsonObject();

            JsonObject sensor = new JsonObject();
            sensor.addProperty("Type", "And");

            com.google.gson.JsonArray sensors = new com.google.gson.JsonArray();

            JsonObject idleState = new JsonObject();
            idleState.addProperty("Type", "State");
            idleState.addProperty("State", "Idle");
            sensors.add(idleState);

            JsonObject targetSensor = new JsonObject();
            targetSensor.addProperty("Type", "Target");
            sensors.add(targetSensor);

            sensor.add("Sensors", sensors);
            followTrigger.add("Sensor", sensor);

            com.google.gson.JsonArray actions = new com.google.gson.JsonArray();
            JsonObject stateAction = new JsonObject();
            stateAction.addProperty("Type", "State");
            stateAction.addProperty("State", "Follow");
            actions.add(stateAction);

            followTrigger.add("Actions", actions);
            interactionInstructions.add(followTrigger);

            interactionInstruction.add("Instructions", interactionInstructions);
            role.add("InteractionInstruction", interactionInstruction);
        }

        return role;
    }

    /**
     * Build a Variant role JSON that inherits from a parent template.
     * Uses the simpler Type/Reference/Modify format.
     * 
     * IMPORTANT: Different templates have different parameters available.
     * We can only modify parameters that exist in the parent template.
     */
    private JsonObject buildVariantRoleJson(NpcRoleData npc) {
        JsonObject role = new JsonObject();

        // Core inheritance fields
        role.addProperty("Type", "Variant");
        role.addProperty("Reference", npc.reference);

        // Build Modify block
        // Values set here override the parent template's parameter values
        // IMPORTANT: We can only modify parameters that exist in the parent template!
        JsonObject modify = new JsonObject();

        // Determine which parameters exist in each template
        boolean isIntelligentTemplate = "Template_Intelligent".equals(npc.reference);
        boolean isPredatorTemplate = "Template_Predator".equals(npc.reference);
        boolean isHycompanionTemplate = "Template_Hycompanion_NPC".equals(npc.reference);

        // These templates have Appearance as a computed parameter
        boolean hasAppearanceParam = isIntelligentTemplate || isPredatorTemplate || isHycompanionTemplate;
        // These templates have attitude parameters
        boolean hasAttitudeParam = isIntelligentTemplate || isPredatorTemplate || isHycompanionTemplate;
        // Template_Intelligent and Template_Predator have weapons parameters
        // (Hycompanion doesn't)
        boolean hasWeaponsParam = isIntelligentTemplate || isPredatorTemplate;
        // These templates have DropList as a parameter
        boolean hasDropListParam = isIntelligentTemplate || isPredatorTemplate || isHycompanionTemplate;
        // Most templates have MaxHealth as computed parameter
        boolean hasMaxHealthParam = true;
        // All templates have NameTranslationKey
        boolean hasNameTranslationKeyParam = true;

        // Collect values
        String appearance = getParameterStringValue(npc.parameters, "Appearance", npc.appearance);
        Integer maxHealth = getParameterIntValue(npc.parameters, "MaxHealth", npc.maxHealth);
        String dropList = getParameterStringValue(npc.parameters, "DropList", npc.dropList);

        String nameKey = getParameterStringValue(npc.parameters, "NameTranslationKey", npc.nameTranslationKey);
        if (nameKey == null || nameKey.isEmpty()) {
            nameKey = "server.npcRoles." + npc.externalId.toLowerCase() + ".name";
        }

        // Set Appearance:
        // - For templates with Appearance as a parameter: put direct value in Modify
        // - For templates without (BlankTemplate): we CANNOT override Appearance
        // Variant roles can only have Type/Reference/Modify/Parameters at root
        // The Appearance must be inherited from the parent template
        if (appearance != null && !appearance.isEmpty() && hasAppearanceParam) {
            // Template has Appearance as computed parameter - we can modify it
            modify.addProperty("Appearance", appearance);
        }
        // NOTE: For templates without Appearance parameter (BlankTemplate, etc.),
        // the Appearance is inherited from the parent and cannot be customized.
        // Users should use Template_Intelligent instead, or set roleType to "Generic"

        // Set MaxHealth in Modify (most templates have this as a parameter)
        if (hasMaxHealthParam && maxHealth != null) {
            modify.addProperty("MaxHealth", maxHealth);
        }

        // Set DropList in Modify (only for templates that have it)
        if (hasDropListParam && dropList != null && !dropList.isEmpty()) {
            modify.addProperty("DropList", dropList);
        }

        // Set NameTranslationKey via Compute (referencing our parameter)
        if (hasNameTranslationKeyParam) {
            JsonObject nameCompute = new JsonObject();
            nameCompute.addProperty("Compute", "NameTranslationKey");
            modify.add("NameTranslationKey", nameCompute);
        }

        // Template_Intelligent / Template_Predator specific parameters
        if (hasAttitudeParam) {
            String attitude = getParameterStringValue(npc.parameters, "DefaultPlayerAttitude",
                    npc.defaultPlayerAttitude);
            if (attitude != null) {
                String normalizedAttitude = normalizeAttitude(attitude);
                modify.addProperty("DefaultPlayerAttitude", normalizedAttitude);
            }
        }

        if (hasWeaponsParam) {
            // Weapons array - set directly in Modify
            if (npc.hotbarItems != null && npc.hotbarItems.length > 0) {
                com.google.gson.JsonArray weapons = new com.google.gson.JsonArray();
                for (String item : npc.hotbarItems) {
                    weapons.add(item);
                }
                modify.add("Weapons", weapons);
            }

            // OffHand array
            if (npc.offHandItems != null && npc.offHandItems.length > 0) {
                com.google.gson.JsonArray offHand = new com.google.gson.JsonArray();
                for (String item : npc.offHandItems) {
                    offHand.add(item);
                }
                modify.add("OffHand", offHand);
            }
        }

        // Add user-specified parameters to Modify (these should already be validated in
        // UI)
        if (npc.parameters != null) {
            for (String key : npc.parameters.keySet()) {
                // Skip parameters we've already handled
                if (key.equals("Appearance") || key.equals("MaxHealth") || key.equals("DropList") ||
                        key.equals("NameTranslationKey") || key.equals("DefaultPlayerAttitude") ||
                        key.equals("Weapons") || key.equals("OffHand")) {
                    continue;
                }

                org.json.JSONObject param = npc.parameters.getJSONObject(key);
                if (param.has("Value")) {
                    Object value = param.get("Value");
                    addValueToJsonObject(modify, key, value);
                }
            }
        }

        // Add explicit modify values from backend (highest priority)
        if (npc.modify != null) {
            for (String key : npc.modify.keySet()) {
                Object value = npc.modify.get(key);
                addValueToJsonObject(modify, key, value);
            }
        }

        if (isHycompanionTemplate) {
            addValueToJsonObject(modify, "Appearance", appearance);
            // Display names
            com.google.gson.JsonArray displayNames = new com.google.gson.JsonArray();
            if (npc.displayNames != null && npc.displayNames.length > 0) {
                for (String name : npc.displayNames) {
                    displayNames.add(name);
                }
            } else if (npc.displayName != null && !npc.displayName.isEmpty()) {
                displayNames.add(npc.displayName);
            }
            if (displayNames.size() > 0) {
                addValueToJsonObject(modify, "DisplayNames", displayNames);
            }

        }

        // Add Modify block
        role.add("Modify", modify);

        // Parameters block - define our own parameters for referencing
        // These parameters can be modified by child variants or referenced via {
        // "Compute": "ParamName" }
        JsonObject params = new JsonObject();

        // Appearance parameter - required for model
        JsonObject appearanceParam = new JsonObject();
        appearanceParam.addProperty("Value", appearance != null ? appearance : "Outlander");
        appearanceParam.addProperty("Description", "Model/appearance to use");
        params.add("Appearance", appearanceParam);

        // MaxHealth parameter
        JsonObject healthParam = new JsonObject();
        healthParam.addProperty("Value", maxHealth != null ? maxHealth : 100);
        healthParam.addProperty("Description", "Maximum health for the NPC");
        params.add("MaxHealth", healthParam);

        // NameTranslationKey parameter
        JsonObject nameParam = new JsonObject();
        nameParam.addProperty("Value", nameKey);
        nameParam.addProperty("Description", "Translation key for NPC name");
        params.add("NameTranslationKey", nameParam);

        // DropList parameter
        if (dropList != null && !dropList.isEmpty()) {
            JsonObject dropListParam = new JsonObject();
            dropListParam.addProperty("Value", dropList);
            dropListParam.addProperty("Description", "Drop list reference");
            params.add("DropList", dropListParam);
        }

        // DefaultPlayerAttitude parameter (only for templates that support it)
        if (hasAttitudeParam) {
            String attitude = getParameterStringValue(npc.parameters, "DefaultPlayerAttitude",
                    npc.defaultPlayerAttitude);
            if (attitude != null) {
                JsonObject attitudeParam = new JsonObject();
                attitudeParam.addProperty("Value", normalizeAttitude(attitude));
                attitudeParam.addProperty("Description", "Default attitude towards players");
                params.add("DefaultPlayerAttitude", attitudeParam);
            }
        }

        role.add("Parameters", params);

        // ============================================================================
        // IMPORTANT: Variant roles can ONLY have: Type, Reference, Modify, Parameters
        // ============================================================================
        // The following are NOT valid for Variant roles:
        // - Appearance (even as Compute) - must be in parent template's Parameters
        // - MotionControllerList - inherited from parent template
        // - Instructions - inherited from parent template
        // - InteractionInstruction - inherited from parent template
        // - DisplayNames - not valid for Variant
        //
        // For NPCs that need follow/custom behavior:
        // - Use Template_Intelligent as parent (has Follow built-in)
        // - OR use Generic/Abstract role type with full definition

        return role;
    }

    /**
     * Build an Abstract role JSON with full role definition.
     * This is the legacy format that defines everything from scratch.
     */
    private JsonObject buildAbstractRoleJson(NpcRoleData npc) {
        JsonObject role = new JsonObject();

        // Type for Abstract roles
        role.addProperty("Type", "Abstract");

        // If this is actually a legacy role without roleType, use Generic
        if (npc.roleType == null || npc.roleType.isEmpty()) {
            role.addProperty("Type", "Generic");
        }

        role.addProperty("MaxHealth", npc.maxHealth);

        // Model
        // Use "Appearance" for the model name/path
        String modelPath = npc.modelPath != null && !npc.modelPath.isEmpty()
                ? npc.modelPath
                : (npc.appearance != null && !npc.appearance.isEmpty() ? npc.appearance : "Outlander");
        role.addProperty("Appearance", modelPath);

        if (npc.modelScale != null && npc.modelScale != 1.0) {
            role.addProperty("ModelScale", npc.modelScale);
        }

        // Display names
        com.google.gson.JsonArray displayNames = new com.google.gson.JsonArray();
        if (npc.displayNames != null && npc.displayNames.length > 0) {
            for (String name : npc.displayNames) {
                displayNames.add(name);
            }
        } else if (npc.displayName != null && !npc.displayName.isEmpty()) {
            displayNames.add(npc.displayName);
        }
        if (displayNames.size() > 0) {
            role.add("DisplayNames", displayNames);
        }

        // NameTranslationKey (Required)
        String nameTranslationKey = (npc.nameTranslationKey != null && !npc.nameTranslationKey.isEmpty())
                ? npc.nameTranslationKey
                : "server.npcRoles." + npc.externalId.toLowerCase() + ".name";
        role.addProperty("NameTranslationKey", nameTranslationKey);

        // NPC Groups
        if (npc.npcGroups != null && npc.npcGroups.length > 0) {
            com.google.gson.JsonArray groups = new com.google.gson.JsonArray();
            for (String group : npc.npcGroups) {
                groups.add(group);
            }
            role.add("NPCGroups", groups);
        }

        // State Configuration
        String startState = (npc.startState != null && !npc.startState.isEmpty()) ? npc.startState : "Idle";
        role.addProperty("StartState", startState);

        String startSubState = (npc.startSubState != null && !npc.startSubState.isEmpty())
                ? npc.startSubState
                : "Default";
        role.addProperty("DefaultSubState", startSubState);

        // Physics & Attributes
        role.addProperty("Invulnerable", npc.invulnerable);
        role.addProperty("DefaultPlayerAttitude", npc.defaultPlayerAttitude);
        role.addProperty("DefaultNPCAttitude", npc.defaultNPCAttitude);
        role.addProperty("Inertia", npc.inertia);
        role.addProperty("KnockbackScale", npc.knockbackScale);

        // Crowd Control
        role.addProperty("ApplyAvoidance", npc.applyAvoidance);
        role.addProperty("ApplySeparation", npc.applySeparation);
        role.addProperty("SeparationDistance", npc.separationDistance);

        // Collision
        if (npc.collisionDistance != null)
            role.addProperty("CollisionDistance", npc.collisionDistance);
        if (npc.collisionRadius != null)
            role.addProperty("CollisionRadius", npc.collisionRadius);

        // Environment
        role.addProperty("BreathesInAir", npc.breathesInAir);
        role.addProperty("BreathesInWater", npc.breathesInWater);
        if (npc.stayInEnvironment != null)
            role.addProperty("StayInEnvironment", npc.stayInEnvironment);

        // Inventory defaults
        role.addProperty("InventorySize", npc.inventorySize);
        role.addProperty("HotbarSize", npc.hotbarSize);

        if (npc.offHandSlots != null) {
            role.addProperty("OffHandSlots", npc.offHandSlots);
        }
        if (npc.dropList != null && !npc.dropList.isEmpty()) {
            role.addProperty("DropList", npc.dropList);
        }
        if (npc.pickupDropOnDeath != null) {
            role.addProperty("PickupDropOnDeath", npc.pickupDropOnDeath);
        }

        // Hotbar Items
        if (npc.hotbarItems != null && npc.hotbarItems.length > 0) {
            com.google.gson.JsonArray hotbarItemsArray = new com.google.gson.JsonArray();
            for (String item : npc.hotbarItems) {
                hotbarItemsArray.add(item);
            }
            role.add("HotbarItems", hotbarItemsArray);
        }

        // OffHand Items
        if (npc.offHandItems != null && npc.offHandItems.length > 0) {
            com.google.gson.JsonArray offHandItemsArray = new com.google.gson.JsonArray();
            for (String item : npc.offHandItems) {
                offHandItemsArray.add(item);
            }
            role.add("OffHandItems", offHandItemsArray);
        }

        // Lifecycle
        role.addProperty("DeathAnimationTime", npc.deathAnimationTime);
        if (npc.despawnAnimationTime != null)
            role.addProperty("DespawnAnimationTime", npc.despawnAnimationTime);
        role.addProperty("SpawnLockTime", npc.spawnLockTime);

        // Spawn Effects
        if (npc.spawnParticles != null && !npc.spawnParticles.isEmpty())
            role.addProperty("SpawnParticles", npc.spawnParticles);
        if (npc.spawnViewDistance != null)
            role.addProperty("SpawnViewDistance", npc.spawnViewDistance);

        // Motion Controller List
        com.google.gson.JsonArray motionControllers = new com.google.gson.JsonArray();

        if (npc.motionControllers != null && !npc.motionControllers.isEmpty()) {
            Iterator<String> keys = npc.motionControllers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                org.json.JSONObject controllerData = npc.motionControllers.getJSONObject(key);
                JsonObject controller = new JsonObject();

                // Determine Type
                String type = "Walk"; // Default
                if (controllerData.has("type")) {
                    type = controllerData.getString("type");
                } else if (key.equalsIgnoreCase("walk") || key.equalsIgnoreCase("run")
                        || key.equalsIgnoreCase("sprint")) {
                    type = "Walk";
                } else if (key.equalsIgnoreCase("fly")) {
                    type = "Fly";
                } else if (key.equalsIgnoreCase("swim") || key.equalsIgnoreCase("dive")) {
                    type = "Dive"; // "Dive" is the Hytale type for swimming
                }

                controller.addProperty("Type", type);

                // Common Properties
                if (controllerData.has("acceleration"))
                    controller.addProperty("Acceleration", controllerData.getDouble("acceleration"));

                if (controllerData.has("maxRotationSpeed"))
                    controller.addProperty("MaxRotationSpeed", controllerData.getDouble("maxRotationSpeed"));
                else if (controllerData.has("turnSpeed"))
                    controller.addProperty("MaxRotationSpeed", controllerData.getDouble("turnSpeed"));

                // Type Specific Properties
                switch (type) {
                    case "Walk":
                        if (controllerData.has("maxSpeed"))
                            controller.addProperty("MaxWalkSpeed", controllerData.getDouble("maxSpeed"));
                        else if (controllerData.has("maxWalkSpeed"))
                            controller.addProperty("MaxWalkSpeed", controllerData.getDouble("maxWalkSpeed"));

                        // Let's use MinJumpHeight if JumpHeight is provided
                        if (controllerData.has("jumpHeight"))
                            controller.addProperty("MinJumpHeight", controllerData.getDouble("jumpHeight"));

                        // Gravity
                        controller.addProperty("Gravity", 10.0);
                        controller.addProperty("MaxFallSpeed", 15.0);
                        break;

                    case "Fly":
                        // "MaxHorizontalSpeed": 20, "MaxClimbSpeed": 10
                        if (controllerData.has("maxSpeed")) {
                            controller.addProperty("MaxHorizontalSpeed", controllerData.getDouble("maxSpeed"));
                            controller.addProperty("MaxClimbSpeed", controllerData.getDouble("maxSpeed") / 2.0); // Rough
                                                                                                                 // estimate
                        }
                        // Default Fallbacks
                        if (!controller.has("MaxHorizontalSpeed"))
                            controller.addProperty("MaxHorizontalSpeed", 10.0);
                        if (!controller.has("MaxClimbSpeed"))
                            controller.addProperty("MaxClimbSpeed", 5.0);
                        controller.addProperty("MaxSinkSpeed", 3.0);
                        controller.addProperty("MinAirSpeed", 0.0); // Allow hovering by default
                        break;

                    case "Dive": // Swimming
                        // "MaxSwimSpeed": 10, "MaxDiveSpeed": 8
                        if (controllerData.has("maxSpeed")) {
                            controller.addProperty("MaxSwimSpeed", controllerData.getDouble("maxSpeed"));
                            controller.addProperty("MaxDiveSpeed", controllerData.getDouble("maxSpeed") * 0.8);
                        }
                        if (!controller.has("MaxSwimSpeed"))
                            controller.addProperty("MaxSwimSpeed", 5.0);
                        controller.addProperty("Gravity", 10.0);
                        break;

                    default:
                        if (controllerData.has("maxSpeed"))
                            controller.addProperty("MaxSpeed", controllerData.getDouble("maxSpeed"));
                        break;
                }

                motionControllers.add(controller);
            }
        } else {
            // Default fallback
            JsonObject walkController = new JsonObject();
            walkController.addProperty("Type", "Walk");
            walkController.addProperty("MaxWalkSpeed", 5.0);
            walkController.addProperty("Acceleration", 25.0);
            walkController.addProperty("MaxRotationSpeed", 180.0);
            walkController.addProperty("MinJumpHeight", 1.2);
            motionControllers.add(walkController);
        }

        role.add("MotionControllerList", motionControllers);

        // Instructions (Behaviors)
        // Generate based on behavior presets array
        String[] presets = npc.behaviorPresets != null ? npc.behaviorPresets : new String[] { "idle" };
        com.google.gson.JsonArray instructions = generateBehaviorInstructions(presets, 2.0);
        role.add("Instructions", instructions);

        // Interaction Instruction - Only register Follow state if "follow" preset is
        // present
        boolean hasFollowPreset = java.util.Arrays.stream(presets)
                .anyMatch(p -> "follow".equalsIgnoreCase(p));
        if (hasFollowPreset) {
            JsonObject interactionInstruction = new JsonObject();
            JsonObject interactSensor = new JsonObject();
            interactSensor.addProperty("Type", "Any"); // "Any" type takes no parameters
            interactionInstruction.add("Sensor", interactSensor);

            com.google.gson.JsonArray interactActions = new com.google.gson.JsonArray();
            JsonObject setStateAction = new JsonObject();
            setStateAction.addProperty("Type", "State");
            setStateAction.addProperty("State", "Follow"); // Registers "Follow" as a valid state
            interactActions.add(setStateAction);

            interactionInstruction.add("Actions", interactActions);
            role.add("InteractionInstruction", interactionInstruction);
        }

        return role;
    }

    /**
     * Generate instruction sets (States implementation) based on behavior presets.
     * Loops through each preset and generates appropriate instructions.
     * 
     * Available presets:
     * - "idle" (mandatory): Static behavior with Observe head motion
     * - "follow": Follow state with Seek body motion towards target
     * - "wander": Wander behavior with random movement
     * 
     * @param presets        Array of behavior preset names
     * @param followDistance Stop distance for follow behavior (default 2.0)
     * @return JsonArray of instructions
     */
    private com.google.gson.JsonArray generateBehaviorInstructions(String[] presets, double followDistance) {
        com.google.gson.JsonArray instructions = new com.google.gson.JsonArray();

        // Ensure we have at least idle preset
        if (presets == null || presets.length == 0) {
            presets = new String[] { "idle" };
        }

        // Check which presets are present
        boolean hasIdlePreset = false;
        boolean hasFollowPreset = false;
        boolean hasWanderPreset = false;

        for (String preset : presets) {
            if (preset == null)
                continue;
            switch (preset.toLowerCase()) {
                case "idle":
                    hasIdlePreset = true;
                    break;
                case "follow":
                    hasFollowPreset = true;
                    break;
                case "wander":
                    hasWanderPreset = true;
                    break;
            }
        }

        // Ensure idle is always present
        if (!hasIdlePreset) {
            hasIdlePreset = true;
        }

        // Generate instructions for each preset (order matters - higher priority first)

        // 1. Follow Behavior (Higher Priority)
        // Only add if "follow" preset is present
        if (hasFollowPreset) {
            JsonObject followInstruction = new JsonObject();
            followInstruction.addProperty("Name", "FollowBehavior");

            JsonObject followSensor = new JsonObject();
            // Use "And" sensor to combine State check with Target provision
            followSensor.addProperty("Type", "And");
            com.google.gson.JsonArray sensors = new com.google.gson.JsonArray();

            // Target Sensor: Provides the required "LiveEntity" feature for "Seek" motion
            JsonObject targetSensor = new JsonObject();
            targetSensor.addProperty("Type", "Target");
            sensors.add(targetSensor);

            // State Sensor: Checks we are in "Follow" state
            JsonObject stateSensor = new JsonObject();
            stateSensor.addProperty("Type", "State");
            stateSensor.addProperty("State", "Follow");
            sensors.add(stateSensor);

            followSensor.add("Sensors", sensors);
            followInstruction.add("Sensor", followSensor);

            JsonObject followBody = new JsonObject();
            followBody.addProperty("Type", "Seek");
            followBody.addProperty("StopDistance", followDistance);
            followBody.addProperty("SlowDownDistance", 4.0);
            followBody.addProperty("AbortDistance", 80.0);
            followInstruction.add("BodyMotion", followBody);

            JsonObject followHead = new JsonObject();
            followHead.addProperty("Type", "Observe");
            com.google.gson.JsonArray followAngle = new com.google.gson.JsonArray();
            followAngle.add(-45.0);
            followAngle.add(45.0);
            followHead.add("AngleRange", followAngle);
            followInstruction.add("HeadMotion", followHead);

            instructions.add(followInstruction);
        }

        // 2. Wander Behavior (Fallback)
        if (hasWanderPreset) {
            JsonObject wanderInstruction = new JsonObject();
            wanderInstruction.addProperty("Name", "WanderBehavior");

            // IMPORTANT: fallback sensor
            JsonObject wanderSensor = new JsonObject();
            wanderSensor.addProperty("Type", "Any");
            wanderInstruction.add("Sensor", wanderSensor);

            // BodyMotion
            JsonObject outerSequence = new JsonObject();
            outerSequence.addProperty("Type", "Sequence");

            com.google.gson.JsonArray outerMotions = new com.google.gson.JsonArray();

            JsonObject innerSequence = new JsonObject();
            innerSequence.addProperty("Type", "Sequence");
            innerSequence.addProperty("Looped", true);

            com.google.gson.JsonArray innerMotions = new com.google.gson.JsonArray();

            // Wander timer
            JsonObject wanderTimer = new JsonObject();
            wanderTimer.addProperty("Type", "Timer");
            com.google.gson.JsonArray wanderTime = new com.google.gson.JsonArray();
            wanderTime.add(2);
            wanderTime.add(4);
            wanderTimer.add("Time", wanderTime);

            JsonObject wanderMotion = new JsonObject();
            wanderMotion.addProperty("Type", "WanderInCircle");
            wanderMotion.addProperty("Radius", 10);
            wanderMotion.addProperty("MaxHeadingChange", 60);
            wanderMotion.addProperty("RelativeSpeed", 0.3);
            wanderMotion.addProperty("MinWalkTime", 1);
            wanderMotion.addProperty("RelaxedMoveConstraints", false);

            wanderTimer.add("Motion", wanderMotion);
            innerMotions.add(wanderTimer);

            // Idle timer
            JsonObject idleTimer = new JsonObject();
            idleTimer.addProperty("Type", "Timer");
            com.google.gson.JsonArray idleTime = new com.google.gson.JsonArray();
            idleTime.add(3);
            idleTime.add(5);
            idleTimer.add("Time", idleTime);

            JsonObject nothingMotion = new JsonObject();
            nothingMotion.addProperty("Type", "Nothing");
            idleTimer.add("Motion", nothingMotion);
            innerMotions.add(idleTimer);

            innerSequence.add("Motions", innerMotions);
            outerMotions.add(innerSequence);
            outerSequence.add("Motions", outerMotions);

            wanderInstruction.add("BodyMotion", outerSequence);

            instructions.add(wanderInstruction);
        }

        // 3. Idle Behavior (Lowest Priority - fallback when no wander)
        // Only add if wander is not present (wander already handles Idle state)
        if (hasIdlePreset && !hasWanderPreset) {
            JsonObject idleInstruction = new JsonObject();
            idleInstruction.addProperty("Name", "IdleBehavior");

            JsonObject idleSensor = new JsonObject();
            idleSensor.addProperty("Type", "State");
            idleSensor.addProperty("State", "Idle");
            idleInstruction.add("Sensor", idleSensor);

            JsonObject idleBody = new JsonObject();
            idleBody.addProperty("Type", "Nothing");
            idleInstruction.add("BodyMotion", idleBody);

            JsonObject idleHead = new JsonObject();
            idleHead.addProperty("Type", "Observe");
            com.google.gson.JsonArray idleAngle = new com.google.gson.JsonArray();
            idleAngle.add(-45.0);
            idleAngle.add(45.0);
            idleHead.add("AngleRange", idleAngle);
            idleInstruction.add("HeadMotion", idleHead);

            instructions.add(idleInstruction);
        }

        return instructions;
    }

    /**
     * Data class for NPC role configuration.
     */
    public static class NpcRoleData {
        public String externalId;
        public String displayName;
        public String[] displayNames;
        public String nameTranslationKey;
        public String startState;
        public String startSubState;

        // ============================================================================
        // INHERITANCE SYSTEM (New Role System)
        // ============================================================================

        /**
         * Role type: "Abstract" or "Variant"
         * - Abstract: Standalone role with all behavior defined
         * - Variant: Inherits from a reference template, only overrides specific values
         */
        public String roleType = "Variant";

        /**
         * Parent template reference (for Variant roles)
         * Example: "Template_Intelligent", "Template_Predator"
         */
        public String reference;

        /**
         * Override values for the parent template (for Variant roles)
         * Contains parameter names mapped to their override values
         */
        public org.json.JSONObject modify;

        /**
         * Parameters - configurable values with descriptions
         * Each parameter has: Value, Description, TypeHint (optional)
         */
        public org.json.JSONObject parameters;

        // ============================================================================
        // LEGACY / DIRECT FIELDS (for backwards compatibility and Abstract roles)
        // ============================================================================

        // Hytale Role fields
        public int maxHealth = 100;
        public String appearance = "Outlander";

        // Model
        public String modelPath;
        public Double modelScale;

        public boolean invulnerable = false;
        public String defaultPlayerAttitude = "NEUTRAL";
        public String defaultNPCAttitude = "NEUTRAL";
        public double inertia = 1.0;
        public double knockbackScale = 1.0;
        public boolean applyAvoidance = false;
        public boolean applySeparation = false;
        public double separationDistance = 3.0;

        // Collision
        public Double collisionDistance;
        public Double collisionRadius;

        // Environment
        public boolean breathesInAir = true;
        public boolean breathesInWater = false;
        public Boolean stayInEnvironment;

        // Inventory
        public int inventorySize = 0;
        public int hotbarSize = 3;
        public String[] hotbarItems;
        public Integer offHandSlots;
        public String[] offHandItems;
        public String dropList;
        public Boolean pickupDropOnDeath;

        // Lifecycle
        public double deathAnimationTime = 5.0;
        public Double despawnAnimationTime;
        public double spawnLockTime = 1.5;
        public String spawnParticles;
        public Integer spawnViewDistance;

        // Behavior
        /**
         * Behavior presets array. Available: "idle" (mandatory), "follow", "wander"
         * Default is ["idle"].
         */
        public String[] behaviorPresets = new String[] { "idle" };
        public String[] npcGroups;
        public org.json.JSONObject motionControllers;

        // New format motion controller list (array format)
        public org.json.JSONArray motionControllerList;

        /**
         * Create from full NPC JSON object (including root properties like
         * preventKnockback)
         */
        public static NpcRoleData fromNpcJson(org.json.JSONObject npcJson) {
            String externalId = npcJson.getString("externalId");
            String name = npcJson.getString("name");
            org.json.JSONObject hytaleRole = npcJson.optJSONObject("hytaleRole");

            // Delegate to partial parser
            NpcRoleData data = fromNpcData(externalId, name, hytaleRole);

            // Apply overrides from root NPC properties
            if (npcJson.optBoolean("preventKnockback", false)) {
                data.knockbackScale = 0.0;
            }

            return data;
        }

        /**
         * Create from partial data (Legacy/Direct)
         */
        public static NpcRoleData fromNpcData(String externalId, String name, org.json.JSONObject hytaleRole) {
            NpcRoleData data = new NpcRoleData();
            data.externalId = externalId;
            data.displayName = name;

            if (hytaleRole != null) {
                // ============================================================================
                // INHERITANCE SYSTEM - Parse first for proper precedence
                // ============================================================================

                if (hytaleRole.has("roleType") && !hytaleRole.isNull("roleType")) {
                    data.roleType = hytaleRole.getString("roleType");
                }
                if (hytaleRole.has("reference") && !hytaleRole.isNull("reference")) {
                    data.reference = hytaleRole.getString("reference");
                }
                if (hytaleRole.has("modify") && !hytaleRole.isNull("modify")) {
                    data.modify = hytaleRole.getJSONObject("modify");
                }
                if (hytaleRole.has("parameters") && !hytaleRole.isNull("parameters")) {
                    data.parameters = hytaleRole.getJSONObject("parameters");
                }

                // New format motion controller list (array)
                if (hytaleRole.has("motionControllerList") && !hytaleRole.isNull("motionControllerList")) {
                    data.motionControllerList = hytaleRole.getJSONArray("motionControllerList");
                }

                // ============================================================================
                // LEGACY / DIRECT FIELDS - for backwards compatibility
                // ============================================================================

                if (hytaleRole.has("maxHealth")) {
                    // Handle both number and Compute object
                    Object maxHealthVal = hytaleRole.get("maxHealth");
                    if (maxHealthVal instanceof Number) {
                        data.maxHealth = ((Number) maxHealthVal).intValue();
                    }
                    // If it's a Compute object, leave default - the value will come from parameters
                }
                if (hytaleRole.has("appearance")) {
                    // Handle both string and Compute object
                    Object appearanceVal = hytaleRole.get("appearance");
                    if (appearanceVal instanceof String) {
                        data.appearance = (String) appearanceVal;
                    }
                    // If it's a Compute object, leave default - the value will come from parameters
                }

                // Parse nested "model" object
                if (hytaleRole.has("model") && !hytaleRole.isNull("model")) {
                    org.json.JSONObject model = hytaleRole.getJSONObject("model");
                    if (model.has("path"))
                        data.modelPath = model.getString("path");
                    if (model.has("scale"))
                        data.modelScale = model.getDouble("scale");
                }
                // Fallback for legacy "modelPath" at root
                else if (hytaleRole.has("modelPath") && !hytaleRole.isNull("modelPath")) {
                    data.modelPath = hytaleRole.getString("modelPath");
                }

                // Parse behaviorPresets as an array
                if (hytaleRole.has("behaviorPresets") && !hytaleRole.isNull("behaviorPresets")) {
                    var arr = hytaleRole.getJSONArray("behaviorPresets");
                    data.behaviorPresets = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.behaviorPresets[i] = arr.getString(i);
                    }
                }
                // Legacy: support old behaviorPreset (single string) field
                else if (hytaleRole.has("behaviorPreset") && !hytaleRole.isNull("behaviorPreset")) {
                    String preset = hytaleRole.getString("behaviorPreset");
                    // Convert "static" to "idle" for backwards compatibility
                    if ("static".equalsIgnoreCase(preset)) {
                        data.behaviorPresets = new String[] { "idle" };
                    } else {
                        // Include both idle and the specified preset
                        data.behaviorPresets = new String[] { "idle", preset };
                    }
                }

                if (hytaleRole.has("invulnerable"))
                    data.invulnerable = hytaleRole.getBoolean("invulnerable");
                if (hytaleRole.has("defaultPlayerAttitude"))
                    data.defaultPlayerAttitude = hytaleRole.getString("defaultPlayerAttitude");
                if (hytaleRole.has("defaultNPCAttitude"))
                    data.defaultNPCAttitude = hytaleRole.getString("defaultNPCAttitude");
                if (hytaleRole.has("inertia"))
                    data.inertia = hytaleRole.getDouble("inertia");
                if (hytaleRole.has("knockbackScale"))
                    data.knockbackScale = hytaleRole.getDouble("knockbackScale");
                if (hytaleRole.has("applyAvoidance"))
                    data.applyAvoidance = hytaleRole.getBoolean("applyAvoidance");
                if (hytaleRole.has("applySeparation"))
                    data.applySeparation = hytaleRole.getBoolean("applySeparation");
                if (hytaleRole.has("separationDistance"))
                    data.separationDistance = hytaleRole.getDouble("separationDistance");

                if (hytaleRole.has("collisionDistance"))
                    data.collisionDistance = hytaleRole.getDouble("collisionDistance");
                if (hytaleRole.has("collisionRadius"))
                    data.collisionRadius = hytaleRole.getDouble("collisionRadius");

                if (hytaleRole.has("breathesInAir"))
                    data.breathesInAir = hytaleRole.getBoolean("breathesInAir");
                if (hytaleRole.has("breathesInWater"))
                    data.breathesInWater = hytaleRole.getBoolean("breathesInWater");
                if (hytaleRole.has("stayInEnvironment"))
                    data.stayInEnvironment = hytaleRole.getBoolean("stayInEnvironment");

                if (hytaleRole.has("inventorySize"))
                    data.inventorySize = hytaleRole.getInt("inventorySize");
                if (hytaleRole.has("hotbarSize"))
                    data.hotbarSize = hytaleRole.getInt("hotbarSize");
                if (hytaleRole.has("deathAnimationTime"))
                    data.deathAnimationTime = hytaleRole.getDouble("deathAnimationTime");
                if (hytaleRole.has("despawnAnimationTime"))
                    data.despawnAnimationTime = hytaleRole.getDouble("despawnAnimationTime");
                if (hytaleRole.has("spawnLockTime"))
                    data.spawnLockTime = hytaleRole.getDouble("spawnLockTime");
                if (hytaleRole.has("spawnParticles") && !hytaleRole.isNull("spawnParticles"))
                    data.spawnParticles = hytaleRole.getString("spawnParticles");
                if (hytaleRole.has("spawnViewDistance"))
                    data.spawnViewDistance = hytaleRole.getInt("spawnViewDistance");

                // New fields
                if (hytaleRole.has("nameTranslationKey") && !hytaleRole.isNull("nameTranslationKey"))
                    data.nameTranslationKey = hytaleRole.getString("nameTranslationKey");
                if (hytaleRole.has("startState") && !hytaleRole.isNull("startState"))
                    data.startState = hytaleRole.getString("startState");
                if (hytaleRole.has("startSubState") && !hytaleRole.isNull("startSubState"))
                    data.startSubState = hytaleRole.getString("startSubState");
                if (hytaleRole.has("dropList") && !hytaleRole.isNull("dropList"))
                    data.dropList = hytaleRole.getString("dropList");
                if (hytaleRole.has("pickupDropOnDeath"))
                    data.pickupDropOnDeath = hytaleRole.getBoolean("pickupDropOnDeath");

                if (hytaleRole.has("offHandSlots") && !hytaleRole.isNull("offHandSlots"))
                    data.offHandSlots = hytaleRole.getInt("offHandSlots");

                // Arrays
                if (hytaleRole.has("displayNames") && !hytaleRole.isNull("displayNames")) {
                    var arr = hytaleRole.getJSONArray("displayNames");
                    data.displayNames = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.displayNames[i] = arr.getString(i);
                    }
                }
                if (hytaleRole.has("hotbarItems") && !hytaleRole.isNull("hotbarItems")) {
                    var arr = hytaleRole.getJSONArray("hotbarItems");
                    data.hotbarItems = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.hotbarItems[i] = arr.getString(i);
                    }
                }
                if (hytaleRole.has("offHandItems") && !hytaleRole.isNull("offHandItems")) {
                    var arr = hytaleRole.getJSONArray("offHandItems");
                    data.offHandItems = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.offHandItems[i] = arr.getString(i);
                    }
                }

                if (hytaleRole.has("npcGroups") && !hytaleRole.isNull("npcGroups")) {
                    var arr = hytaleRole.getJSONArray("npcGroups");
                    data.npcGroups = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.npcGroups[i] = arr.getString(i);
                    }
                }

                // Motion Controllers
                if (hytaleRole.has("motionControllers") && !hytaleRole.isNull("motionControllers")) {
                    data.motionControllers = hytaleRole.getJSONObject("motionControllers");
                }
            }

            return data;
        }
    }

    // ============================================================================
    // HELPER METHODS
    // ============================================================================

    /**
     * Add a value of any type to a JsonObject.
     * Handles numbers, strings, booleans, and JSON arrays.
     */
    private void addValueToJsonObject(JsonObject obj, String key, Object value) {
        if (value == null) {
            return;
        }

        if (value instanceof Number) {
            if (value instanceof Integer || value instanceof Long) {
                obj.addProperty(key, ((Number) value).longValue());
            } else {
                obj.addProperty(key, ((Number) value).doubleValue());
            }
        } else if (value instanceof Boolean) {
            obj.addProperty(key, (Boolean) value);
        } else if (value instanceof String) {
            obj.addProperty(key, (String) value);
        } else if (value instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) value;
            com.google.gson.JsonArray gsonArr = new com.google.gson.JsonArray();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                if (item instanceof String) {
                    gsonArr.add((String) item);
                } else if (item instanceof Number) {
                    gsonArr.add(((Number) item).doubleValue());
                } else if (item instanceof Boolean) {
                    gsonArr.add((Boolean) item);
                }
            }
            obj.add(key, gsonArr);
        }
    }

    /**
     * Extract a string value from a parameters object, with fallback.
     */
    private String getParameterStringValue(org.json.JSONObject parameters, String key, String fallback) {
        if (parameters != null && parameters.has(key)) {
            org.json.JSONObject param = parameters.optJSONObject(key);
            if (param != null && param.has("Value")) {
                Object val = param.get("Value");
                if (val instanceof String) {
                    return (String) val;
                }
            }
        }
        return fallback;
    }

    /**
     * Extract an integer value from a parameters object, with fallback.
     */
    private Integer getParameterIntValue(org.json.JSONObject parameters, String key, int fallback) {
        if (parameters != null && parameters.has(key)) {
            org.json.JSONObject param = parameters.optJSONObject(key);
            if (param != null && param.has("Value")) {
                Object val = param.get("Value");
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        }
        return fallback;
    }

    /**
     * Extract a double value from a parameters object, with fallback.
     */
    private Double getParameterDoubleValue(org.json.JSONObject parameters, String key, double fallback) {
        if (parameters != null && parameters.has(key)) {
            org.json.JSONObject param = parameters.optJSONObject(key);
            if (param != null && param.has("Value")) {
                Object val = param.get("Value");
                if (val instanceof Number) {
                    return ((Number) val).doubleValue();
                }
            }
        }
        return fallback;
    }

    /**
     * Normalize attitude string from backend format to Hytale format.
     * Backend uses: HOSTILE, NEUTRAL, FRIENDLY
     * Hytale uses: Hostile, Neutral, Ignore (FRIENDLY maps to Ignore)
     */
    private String normalizeAttitude(String attitude) {
        if (attitude == null) {
            return "Neutral";
        }

        String upper = attitude.toUpperCase();
        switch (upper) {
            case "HOSTILE":
                return "Hostile";
            case "FRIENDLY":
            case "IGNORE":
                return "Ignore";
            case "NEUTRAL":
            default:
                return "Neutral";
        }
    }
}
