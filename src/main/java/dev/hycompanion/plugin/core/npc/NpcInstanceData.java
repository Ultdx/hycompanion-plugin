package dev.hycompanion.plugin.core.npc;

import java.util.UUID;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.utils.PluginLogger;

public record NpcInstanceData(
        UUID entityUuid,
        Ref<EntityStore> entityRef,
        NPCEntity npcEntity,
        NpcData npcData,
        Location spawnLocation) {

    private static final PluginLogger logger = new PluginLogger("NpcInstanceData");

    /**
     * All animation slots that need clearing to fully reset an NPC's visual state.
     */
    private static final AnimationSlot[] ALL_SLOTS = {
            AnimationSlot.Action,
            AnimationSlot.Emote,
            AnimationSlot.Face,
            AnimationSlot.Status,
            AnimationSlot.Movement
    };

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

    /**
     * Reset all manual animations and force the NPC posture back to standing.
     * <p>
     * This must be called on the world thread. It performs three steps:
     * <ol>
     * <li>Clears the {@link ActiveAnimationComponent} internal array so the
     * locomotion blend tree is no longer blocked by stale cached values.</li>
     * <li>Stops every animation slot (server cache + client packet).</li>
     * <li>Plays "Idle" then {@code null} on the Action slot to force the client
     * to transition the skeleton out of persistent poses (Sit, Sleep, …).</li>
     * </ol>
     *
     * @param store   the entity store (must be valid, obtained on the world thread)
     * @param context a short label for debug logging (e.g. "moveNpcTo")
     */
    public void resetAnimationsAndPosture(Store<EntityStore> store, String context) {
        // --- 1. Wipe ActiveAnimationComponent cache ---
        try {
            ActiveAnimationComponent activeAnimComp = store.getComponent(entityRef,
                    ActiveAnimationComponent.getComponentType());
            if (activeAnimComp != null) {
                String[] anims = activeAnimComp.getActiveAnimations();
                for (int i = 0; i < anims.length; i++) {
                    anims[i] = null;
                }
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] " + context
                    + ": could not clear ActiveAnimationComponent: " + e.getMessage());
        }

        // --- 2. Stop every animation slot (server + client) ---
        NPCEntity npc = this.npcEntity;
        if (npc == null) {
            try {
                npc = store.getComponent(entityRef, NPCEntity.getComponentType());
            } catch (Exception e) {
                logger.debug("[Hycompanion] " + context
                        + ": could not get NPCEntity for animation reset: " + e.getMessage());
            }
        }

        for (AnimationSlot slot : ALL_SLOTS) {
            try {
                if (npc != null) {
                    npc.playAnimation(entityRef, slot, null, store);
                }
                AnimationUtils.stopAnimation(entityRef, slot, true, store);
            } catch (Exception e) {
                logger.debug("[Hycompanion] " + context
                        + ": could not clear " + slot + " animation: " + e.getMessage());
            }
        }

        // --- 3. Force posture reset (Idle flash on Action slot) ---
        // Playing "Idle" forces the client to transition the skeleton out of any
        // persistent pose (e.g. Sit). Immediately nulling it lets the locomotion
        // blend tree take over for walk/run.
        try {
            if (npc != null) {
                npc.playAnimation(entityRef, AnimationSlot.Action, "Idle", store);
                npc.playAnimation(entityRef, AnimationSlot.Action, null, store);
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] " + context
                    + ": could not reset posture: " + e.getMessage());
        }
    }
}
