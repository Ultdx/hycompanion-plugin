package dev.hycompanion.plugin.core.npc;

import java.util.UUID;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hycompanion.plugin.api.Location;

public record NpcInstanceData(
        UUID entityUuid,
        Ref<EntityStore> entityRef,
        NPCEntity npcEntity,
        NpcData npcData,
        Location spawnLocation) {

    public boolean isSpawned() {
        return entityUuid != null;
    }

    /**
     * Create updated copy with entity UUID
     */
    public NpcInstanceData withEntityUuid(UUID newEntityUuid) {
        return new NpcInstanceData(
                newEntityUuid, entityRef, npcEntity, npcData, spawnLocation);
    }

    /**
     * Create updated copy with spawn location
     */
    public NpcInstanceData withSpawnLocation(Location newSpawnLocation) {
        return new NpcInstanceData(
                entityUuid, entityRef, npcEntity, npcData, newSpawnLocation);
    }
}
