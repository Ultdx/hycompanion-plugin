package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages active NPCs in the game world
 * 
 * Tracks NPC data, locations, and entity bindings.
 * Uses spatial indexing for efficient nearby NPC lookups.
 * Works with NpcConfigManager for persistence.
 */
public class NpcManager {

    private final PluginLogger logger;

    // Active NPCs by external ID
    private final Map<String, NpcData> activeNpcs = new ConcurrentHashMap<>();

    // Entity UUID → External ID mapping
    private final Map<UUID, String> entityToExternalId = new ConcurrentHashMap<>();

    private HytaleAPI hytaleAPI;

    public NpcManager(PluginLogger logger, HytaleAPI hytaleAPI) {
        this.logger = logger;
        this.hytaleAPI = hytaleAPI;
    }

    /**
     * Register or update an NPC
     */
    public void registerNpc(NpcData npc) {
        // Preserve runtime state and update properties if existing
        NpcData existing = activeNpcs.get(npc.externalId());

        if (existing != null) {
            existing.updateFrom(npc);
            logger.debug("NPC updated: " + npc.externalId() + " (" + npc.name() + ")");
        } else {
            activeNpcs.put(npc.externalId(), npc);
            logger.debug("NPC registered: " + npc.externalId() + " (" + npc.name() + ")");
        }

        // Get all npc instance id that are instance of this NPC
        Set<NpcInstanceData> npcInstances = hytaleAPI.getNpcInstances();
        for (NpcInstanceData npcInstance : npcInstances) {
            if (npcInstance.npcData().externalId().equals(npc.externalId())) {
                entityToExternalId.put(npcInstance.entityUuid(), npc.externalId());

            }
        }
    }

    /**
     * Unregister an NPC
     */
    public void unregisterNpc(String externalId) {
        NpcData npc = activeNpcs.remove(externalId);

        if (npc != null) {
            // Get all NPC instance id that are instance of this NPC
            Set<NpcInstanceData> npcInstances = hytaleAPI.getNpcInstances();

            for (NpcInstanceData npcInstance : npcInstances) {
                if (npcInstance.npcData().externalId().equals(npc.externalId())) {
                    entityToExternalId.remove(npcInstance.entityUuid());

                    // Unspawn the NPC instance
                    hytaleAPI.removeNpc(npcInstance.entityUuid());
                }
            }

        }

        logger.debug("NPC unregistered: " + externalId);
    }

    /**
     * Get NPC by external ID
     */
    public Optional<NpcData> getNpc(String externalId) {
        return Optional.ofNullable(activeNpcs.get(externalId));
    }

    /**
     * Get NPC by display name (case-insensitive)
     */
    public Optional<NpcData> getNpcByName(String name) {
        return activeNpcs.values().stream()
                .filter(npc -> npc.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Get NPC by any identifier: external ID, display name, or entity UUID.
     * Tries in order: externalId exact match, display name (case-insensitive),
     * entity UUID.
     * This provides flexible lookup for user commands.
     */
    public Optional<NpcData> getNpcByAnyIdentifier(String identifier) {
        // 1. Try external ID (exact match)
        NpcData byExternalId = activeNpcs.get(identifier);
        if (byExternalId != null) {
            return Optional.of(byExternalId);
        }

        // 2. Try display name (case-insensitive)
        Optional<NpcData> byName = activeNpcs.values().stream()
                .filter(npc -> npc.name().equalsIgnoreCase(identifier))
                .findFirst();
        if (byName.isPresent()) {
            return byName;
        }

        // 3. Try entity UUID
        try {
            UUID uuid = UUID.fromString(identifier);
            String externalId = entityToExternalId.get(uuid);
            if (externalId != null) {
                return getNpc(externalId);
            }
        } catch (IllegalArgumentException e) {
            // Not a valid UUID, ignore
        }

        return Optional.empty();
    }

    /**
     * Get NPC by entity UUID
     */
    public Optional<NpcData> getNpcByEntityUuid(UUID entityUuid) {
        String externalId = entityToExternalId.get(entityUuid);
        if (externalId != null) {
            return getNpc(externalId);
        }
        return Optional.empty();
    }

    /**
     * Get all active NPCs
     */
    public Collection<NpcData> getAllNpcs() {
        return Collections.unmodifiableCollection(activeNpcs.values());
    }

    /**
     * Get NPCs near a location using spatial index and per-NPC range.
     * 
     * Searches a wide area (MAX_SEARCH_RADIUS) and then filters by
     * individual NPC chat distances.
     * 
     * @param location     Center location to search around
     * @param defaultRange Default range to use if NPC has no configured
     *                     chatDistance
     * @return List of NPCs within their respective hearing ranges
     */
    public List<NpcSearchResult> getNpcsNear(Location location, double defaultRange) {
        if (location == null) {
            return Collections.emptyList();
        }

        List<NpcSearchResult> result = new ArrayList<>();

        // Iterate through all active NPC instances directly
        // Since we removed spatial indexing/caching, we check real-time location
        for (NpcInstanceData npcInstance : hytaleAPI.getNpcInstances()) {
            // Only check spawned NPCs
            if (!npcInstance.isSpawned()) {
                logger.debug("NPC instance not spawned: " + npcInstance.entityUuid());
                continue;
            }

            Optional<Location> npcLocOpt = hytaleAPI.getNpcInstanceLocation(npcInstance.entityUuid());

            if (npcLocOpt.isPresent()) {
                Location npcLoc = npcLocOpt.get();

                // Check if in same world
                if (!npcLoc.world().equals(location.world())) {
                    logger.debug("NPC instance not in same world: " + npcInstance.entityUuid());
                    continue;
                }

                double distance = npcLoc.distanceTo(location);
                NpcData npc = npcInstance.npcData();

                // Use NPC's specific range, or fallback to default
                double range = (npc != null && npc.chatDistance() != null)
                        ? npc.chatDistance().doubleValue()
                        : defaultRange;

                if (distance <= range) {
                    result.add(new NpcSearchResult(npcInstance, npcLoc, distance));
                    logger.debug("[NpcManager] Found nearby NPC: " + npcInstance.entityUuid() + " (" + distance + "m)");
                } else {
                    logger.debug("[NpcManager] NPC not in range: " + npcInstance.entityUuid() + " (" + distance + "m)");
                }
            } else {
                logger.debug("NPC instance loc not found: " + npcInstance.entityUuid());
            }
        }

        return result;
    }

    /**
     * Update NPC location and spatial index
     */

    /**
     * Bind entity UUID to NPC
     */
    public void bindEntity(String externalId, UUID entityUuid) {
        if (activeNpcs.containsKey(externalId)) {
            entityToExternalId.put(entityUuid, externalId);
            logger.debug("Entity bound: " + externalId + " → " + entityUuid);
        }
    }

    /**
     * Bind entity UUID to NPC and immediately update its location.
     * This ensures the spatial index is up-to-date right after binding.
     * 
     * @param externalId NPC external ID
     * @param entityUuid Entity UUID to bind
     * @param location   Current location of the entity
     */

    /**
     * Unbind entity from NPC
     */
    public void unbindEntity(UUID entityUuid) {
        entityToExternalId.remove(entityUuid);
    }

    /**
     * Check if an entity is a managed NPC
     */
    public boolean isNpcEntity(UUID entityUuid) {
        return entityToExternalId.containsKey(entityUuid);
    }

    /**
     * Get total NPC count
     */
    public int getNpcCount() {
        return activeNpcs.size();
    }

    /**
     * Get spawned NPC count
     */
    public int getSpawnedNpcCount() {
        return (int) hytaleAPI.getNpcInstances().stream()
                .filter(NpcInstanceData::isSpawned)
                .count();
    }

    /**
     * Find the nearest spawned NPC instance by external ID.
     * Searches through all spawned NPC instances and returns the one with the given
     * external ID that is closest to the specified location.
     *
     * @param externalId The external ID of the NPC to search for
     * @param location   The center location to search from
     * @return The nearest NpcInstanceData, or empty if none found
     */
    public Optional<NpcInstanceData> findNearestSpawnedNpcByExternalId(String externalId, Location location) {
        if (location == null) {
            return Optional.empty();
        }

        NpcInstanceData nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (NpcInstanceData npcInstance : hytaleAPI.getNpcInstances()) {
            // Only check spawned NPCs with matching external ID
            if (!npcInstance.isSpawned()) {
                continue;
            }

            if (!npcInstance.npcData().externalId().equals(externalId)) {
                continue;
            }

            Optional<Location> npcLocOpt = hytaleAPI.getNpcInstanceLocation(npcInstance.entityUuid());
            if (npcLocOpt.isEmpty()) {
                continue;
            }

            Location npcLoc = npcLocOpt.get();

            // Check if in same world
            if (!npcLoc.world().equals(location.world())) {
                continue;
            }

            double distance = npcLoc.distanceTo(location);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = npcInstance;
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Clear all NPCs and spatial index
     */

}
