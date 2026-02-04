package dev.hycompanion.plugin.systems;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hycompanion.plugin.HycompanionEntrypoint;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Hytale System that detects NPC death and triggers respawn.
 * 
 * This system extends DeathSystems.OnDeathSystem and is registered with Hytale's
 * entity store registry. It detects when an NPC entity gets a DeathComponent
 * and schedules a respawn via the HytaleServerAdapter.
 */
public class NpcRespawnSystem extends DeathSystems.OnDeathSystem {

    @Nonnull
    private final Query<EntityStore> query = NPCEntity.getComponentType();

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component, 
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Get the NPC component to extract UUID and role info
        NPCEntity npcEntity = store.getComponent(ref, NPCEntity.getComponentType());
        if (npcEntity == null) {
            return;
        }

        UUID entityUuid = npcEntity.getUuid();
        String roleName = npcEntity.getRoleName();
        
        if (entityUuid == null || roleName == null) {
            return;
        }

        // Schedule respawn via the adapter
        HycompanionEntrypoint entrypoint = HycompanionEntrypoint.getInstance();
        if (entrypoint != null && entrypoint.getHytaleAPI() != null) {
            var adapter = (dev.hycompanion.plugin.adapter.HytaleServerAdapter) entrypoint.getHytaleAPI();
            adapter.handleNpcDeath(entityUuid, roleName);
        }
    }
}
