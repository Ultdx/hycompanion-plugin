package dev.hycompanion.plugin.adapter;

import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.api.inventory.*;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock implementation of HytaleAPI for testing without the actual game server
 * 
 * All methods log actions to console and simulate behavior.
 * Replace with actual HytaleServerAdapter when Hytale API is available.
 * 
 * TODO: [HYTALE-API] Replace this with actual server API implementation
 */
public class MockHytaleAdapter implements HytaleAPI {

    private final PluginLogger logger;

    // Mock data stores
    private final Map<String, GamePlayer> mockPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, NpcInstanceData> npcInstanceEntities = new ConcurrentHashMap<>();

    // Mock world state
    private String currentTimeOfDay = "noon";
    private String currentWeather = "clear";
    private String worldName = "world";

    public MockHytaleAdapter(PluginLogger logger) {
        this.logger = logger;
        logger.info("MockHytaleAdapter initialized - Hytale API calls will be simulated");
    }

    // ========== Player Operations ==========

    @Override
    public Optional<GamePlayer> getPlayer(String playerId) {
        return Optional.ofNullable(mockPlayers.get(playerId));
    }

    @Override
    public Optional<GamePlayer> getPlayerByName(String playerName) {
        return mockPlayers.values().stream()
                .filter(p -> p.name().equalsIgnoreCase(playerName))
                .findFirst();
    }

    @Override
    public List<GamePlayer> getOnlinePlayers() {
        return new ArrayList<>(mockPlayers.values());
    }

    @Override
    public Set<NpcInstanceData> getNpcInstances() {
        return new HashSet<>(npcInstanceEntities.values());
    }

    @Override
    public NpcInstanceData getNpcInstance(UUID npcInstanceUuid) {
        return npcInstanceEntities.get(npcInstanceUuid);
    }

    @Override
    public void sendMessage(String playerId, String message) {
        logger.info("[MOCK] Sending message to player [" + playerId + "]: " + message);
    }

    @Override
    public void sendNpcMessage(UUID npcInstanceId, String playerId, String message) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] says to player [" + playerId + "]: " + message);
    }

    @Override
    public void broadcastNpcMessage(UUID npcInstanceId, List<String> playerIds, String message) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] broadcasts to " + playerIds.size() + " players: " + message);
    }

    @Override
    public void sendErrorMessage(String playerId, String message) {
        logger.warn("[MOCK] [ERROR] Sending error message to player [" + playerId + "]: " + message);
    }

    @Override
    public void broadcastDebugMessageToOps(String message) {
        logger.warn("[MOCK] [DEBUG] Broadcasting to OPs: " + message);
    }

    @Override
    public boolean isPlayerOp(String playerId) {
        // In mock, consider all players as OP for testing purposes
        return mockPlayers.containsKey(playerId);
    }

    // ========== NPC Operations ==========

    @Override
    public Optional<UUID> spawnNpc(String externalId, String name, Location location) {
        logger.info("[MOCK] Spawning NPC [" + externalId + "] (" + name + ") at " + location);

        // Generate mock entity UUID
        UUID entityUuid = UUID.randomUUID();

        // Create mock NpcData
        NpcData npcData = NpcData.fromSync(
                externalId, // use external ID as ID for mock
                externalId,
                name,
                "Default Personality",
                "Hello!",
                10,
                false,
                "neutral",
                null, false, false);

        // Create NpcInstanceData (with null ref since we are mocking)
        NpcInstanceData instanceData = new NpcInstanceData(
                entityUuid,
                null, // No real entity ref in mock
                null,
                npcData,
                location); // Use spawn location as the spawnLocation

        npcInstanceEntities.put(entityUuid, instanceData);

        return Optional.of(entityUuid);
    }

    @Override
    public boolean removeNpc(UUID npcInstanceId) {
        logger.info("[MOCK] Removing NPC [" + npcInstanceId + "]");

        if (npcInstanceEntities.remove(npcInstanceId) != null) {
            return true;
        }
        return false;
    }

    @Override
    public void triggerNpcEmote(UUID npcInstanceId, String animationName) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] plays animation: " + animationName);
    }

    @Override
    public CompletableFuture<dev.hycompanion.plugin.core.npc.NpcMoveResult> moveNpcTo(UUID npcInstanceId,
            Location location) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] moving to " + location);

        NpcInstanceData data = npcInstanceEntities.get(npcInstanceId);
        if (data != null) {
            // npcInstanceEntities.put(npcInstanceId, data.(location));
        }
        return CompletableFuture.completedFuture(dev.hycompanion.plugin.core.npc.NpcMoveResult.success(location));
    }

    @Override
    public Optional<Location> getNpcInstanceLocation(UUID npcInstanceId) {
        NpcInstanceData data = npcInstanceEntities.get(npcInstanceId);
        return null;
        // return data != null ? Optional.ofNullable(data)) : Optional.empty();
    }

    @Override
    public void rotateNpcInstanceToward(UUID npcInstanceId, Location targetLocation) {
        if (targetLocation == null) {
            return;
        }
        logger.info("[MOCK] NPC [" + npcInstanceId + "] rotates to face " + targetLocation.toCoordString());
    }

    @Override
    public boolean isNpcInstanceEntityValid(UUID npcInstanceId) {
        return npcInstanceEntities.containsKey(npcInstanceId);
    }

    @Override
    public List<UUID> discoverExistingNpcInstances(String externalId) {
        // In mock, return any currently tracked NPCs that match the externalId
        return npcInstanceEntities.values().stream()
                .filter(inst -> inst.npcData().externalId().equals(externalId))
                .map(NpcInstanceData::entityUuid)
                .toList();
    }

    @Override
    public void registerNpcRemovalListener(java.util.function.Consumer<UUID> listener) {
        // No-op for mock currently
    }

    // ========== Trade Operations ==========

    @Override
    public void openTradeInterface(UUID npcInstanceId, String playerId) {
        logger.info("[MOCK] Opening trade interface between NPC [" + npcInstanceId + "] and player [" + playerId + "]");
    }

    // ========== Quest Operations ==========

    @Override
    public void offerQuest(UUID npcInstanceId, String playerId, String questId, String questName) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] offering quest '" + questName + "' (ID: " + questId
                + ") to player ["
                + playerId + "]");
    }

    // ========== World Context ==========

    @Override
    public String getTimeOfDay() {
        return currentTimeOfDay;
    }

    @Override
    public String getWeather() {
        return currentWeather;
    }

    @Override
    public List<String> getNearbyPlayerNames(Location location, double radius) {
        return mockPlayers.values().stream()
                .filter(p -> p.location() != null && p.location().distanceTo(location) <= radius)
                .map(GamePlayer::name)
                .toList();
    }

    @Override
    public List<GamePlayer> getNearbyPlayers(Location location, double radius) {
        return mockPlayers.values().stream()
                .filter(p -> p.location() != null && p.location().distanceTo(location) <= radius)
                .toList();
    }

    @Override
    public String getWorldName() {
        return worldName;
    }

    // ========== AI Action Operations ==========

    @Override
    public boolean startFollowingPlayer(UUID npcInstanceId, String targetPlayerName) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] starting to follow player: " + targetPlayerName);
        return true;
    }

    @Override
    public boolean stopFollowing(UUID npcInstanceId) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] stopped following");
        return true;
    }

    @Override
    public boolean startAttacking(UUID npcInstanceId, String targetName, String attackType) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] attacking " + targetName + " (type: " + attackType + ")");
        return true;
    }

    @Override
    public boolean stopAttacking(UUID npcInstanceId) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] stopped attacking");
        return true;
    }

    @Override
    public boolean isNpcBusy(UUID npcInstanceId) {
        return false;
    }

    // ========== Teleport Operations ==========

    @Override
    public boolean teleportNpcTo(UUID npcInstanceId, Location location) {
        logger.info("[MOCK] Teleporting NPC [" + npcInstanceId + "] to " + location);
        return true;
    }

    @Override
    public boolean teleportPlayerTo(String playerId, Location location) {
        logger.info("[MOCK] Teleporting player [" + playerId + "] to " + location);
        return true;
    }

    // ========== Animation Discovery ==========

    @Override
    public List<String> getAvailableAnimations(UUID npcInstanceId) {
        logger.info("[MOCK] Getting available animations for NPC: " + npcInstanceId);
        return List.of(
                "Idle", "Walk", "Run", "Jump", "Fall",
                "Sit", "SitGround", "Sleep", "Crouch",
                "Hurt", "Death", "IdlePassive",
                "Howl", "Greet", "Threaten", "Track", "Laydown", "Wake");
    }

    // ========== Block Discovery ==========

    @Override
    public List<dev.hycompanion.plugin.core.world.BlockInfo> getAvailableBlocks() {
        logger.info("[MOCK] Getting available blocks (sample data)");
        
        // Sample blocks based on hytaleitemids.com structure
        return List.of(
            // Wood blocks
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Wood_Cedar_Trunk", "Cedar Log"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Wood_Oak_Trunk", "Oak Log"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Wood_Birch_Trunk", "Birch Log"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Wood_Spruce_Trunk", "Spruce Log"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Wood_Cedar_Plank", "Cedar Plank"),
            
            // Stone blocks
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Stone_Cobble", "Cobblestone"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Stone_Granite_Raw", "Raw Granite"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Stone_Marble", "Marble"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Stone_Sandstone", "Sandstone"),
            
            // Ores
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Ore_Iron", "Iron Ore"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Ore_Gold", "Gold Ore"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Ore_Coal", "Coal Ore"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Ore_Diamond", "Diamond Ore"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Ore_Copper", "Copper Ore"),
            
            // Plants
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Plant_Crop_Mushroom_Block_Blue_Trunk", "Blue Mushroom Trunk"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Plant_Flower_Rose", "Rose"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Plant_Grass", "Grass"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Plant_Leaf_Oak", "Oak Leaves"),
            
            // Soils
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Dirt", "Dirt"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Sand", "Sand"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Gravel", "Gravel"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Clay", "Clay"),
            
            // Fluids
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Fluid_Water", "Water"),
            dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                "Fluid_Lava", "Lava")
        );
    }

    // ========== Thinking Indicator ==========

    @Override
    public void showThinkingIndicator(UUID npcInstanceId) {
        logger.info("[MOCK] Showing thinking indicator for NPC: " + npcInstanceId);
    }


    @Override
    public void hideThinkingIndicator(UUID npcInstanceId) {
        logger.info("[MOCK] Hiding thinking indicator for NPC: " + npcInstanceId);
    }

    @Override
    public int removeZombieThinkingIndicators() {
        logger.info("[MOCK] Removed 0 zombie thinking indicators (mock)");
        return 0;
    }

    // ========== Mock Data Management (for testing) ==========

    public void addMockPlayer(GamePlayer player) {
        mockPlayers.put(player.id(), player);
    }

    public void removeMockPlayer(String playerId) {
        mockPlayers.remove(playerId);
    }

    public void setMockTimeOfDay(String time) {
        this.currentTimeOfDay = time;
    }

    public void setMockWeather(String weather) {
        this.currentWeather = weather;
    }

    @Override
    public void updateNpcCapabilities(String externalId, NpcData npcData) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'updateNpcCapabilities'");
    }

    @Override
    public CompletableFuture<Optional<Map<String, Object>>> findBlock(UUID npcId, String tag, int radius) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findBlock'");
    }

    @Override
    public CompletableFuture<Optional<Map<String, Object>>> scanBlocks(UUID npcId, int radius) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'scanBlocks'");
    }

    @Override
    public CompletableFuture<Optional<Map<String, Object>>> scanEntities(UUID npcId, int radius) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'scanEntities'");
    }

    @Override
    public CompletableFuture<Optional<Map<String, Object>>> findEntity(UUID npcId, String name,
            int radius) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findEntity'");
    }

    // ========== Inventory Operations ==========

    @Override
    public EquipResult equipItem(UUID npcInstanceId, String itemId, String slot) {
        logger.info("[MOCK] Equipping item " + itemId + " to slot " + slot + " for NPC " + npcInstanceId);
        return EquipResult.success(itemId, slot.equals("auto") ? "hotbar_0" : slot, null);
    }

    @Override
    public BreakResult breakBlock(UUID npcInstanceId, Location targetBlock, String toolItemId, int maxAttempts) {
        logger.info("[MOCK] Breaking block at " + targetBlock + " for NPC " + npcInstanceId);
        // Simulate breaking a wood block
        List<Map<String, Object>> drops = List.of(
            Map.of("itemId", "Wood_Beech", "quantity", 2),
            Map.of("itemId", "Sapling_Beech", "quantity", 1)
        );
        Map<String, Object> dropLocation = Map.of("x", targetBlock.x(), "y", targetBlock.y(), "z", targetBlock.z());
        return BreakResult.success("Wood_Beech_Trunk", 5, drops, dropLocation, 45.0);
    }

    @Override
    public PickupResult pickupItems(UUID npcInstanceId, double radius, String itemId, int maxItems) {
        logger.info("[MOCK] Picking up items within " + radius + " blocks for NPC " + npcInstanceId);
        List<Map<String, Object>> items = List.of(
            Map.of("itemId", itemId != null ? itemId : "Wood_Beech", "quantity", 2)
        );
        return PickupResult.success(2, items, 0);
    }

    @Override
    public UseResult useHeldItem(UUID npcInstanceId, Location target, int useCount, long intervalMs, TargetType targetType) {
        logger.info("[MOCK] Using held item " + useCount + " times on " + targetType + " at " + target + " for NPC " + npcInstanceId);
        return UseResult.success(useCount, false, null, false);
    }

    @Override
    public DropResult dropItem(UUID npcInstanceId, String itemId, int quantity, float throwSpeed) {
        logger.info("[MOCK] Dropping " + quantity + "x " + itemId + " with speed " + throwSpeed + " for NPC " + npcInstanceId);
        return DropResult.success(itemId, quantity, 0);
    }

    @Override
    public InventorySnapshot getInventory(UUID npcInstanceId, boolean includeEmpty) {
        logger.info("[MOCK] Getting inventory for NPC " + npcInstanceId);
        Map<String, Object> armor = Map.of(
            "head", Map.of("itemId", "Helmet_Iron", "quantity", 1),
            "chest", Map.of("itemId", "Chestplate_Leather", "quantity", 1)
        );
        List<Map<String, Object>> hotbar = List.of(
            Map.of("slot", 0, "itemId", "Axe_Steel", "quantity", 1, "isActive", true),
            Map.of("slot", 1, "itemId", "Pickaxe_Stone", "quantity", 1, "isActive", false),
            Map.of("slot", 2, "itemId", null, "quantity", 0, "isActive", false)
        );
        List<Map<String, Object>> storage = List.of();
        Map<String, Object> heldItem = Map.of("itemId", "Axe_Steel", "quantity", 1);
        return InventorySnapshot.create(armor, hotbar, storage, heldItem, 3);
    }

    @Override
    public UnequipResult unequipItem(UUID npcInstanceId, String slot, boolean destroy) {
        logger.info("[MOCK] Unequipping item from slot " + slot + " (destroy=" + destroy + ") for NPC " + npcInstanceId);
        Map<String, Object> itemRemoved = Map.of("itemId", "Helmet_Iron", "quantity", 1);
        if (destroy) {
            return UnequipResult.destroyed(slot, itemRemoved);
        }
        return UnequipResult.success(slot, itemRemoved, true);
    }

    @Override
    public boolean expandNpcInventory(UUID npcInstanceId, int storageSlots) {
        logger.info("[MOCK] Expanding inventory by " + storageSlots + " slots for NPC " + npcInstanceId);
        return true;
    }
}
