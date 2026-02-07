package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * NPC data model - represents an NPC synced from the backend
 * 
 * @param id           Internal UUID (from backend database)
 * @param externalId   External ID used for identification (e.g.,
 *                     "merchant_bob")
 * @param entityUuid   In-game entity UUID (null if not spawned)
 * 
 * @param name         Display name shown in-game
 * @param personality  LLM personality prompt
 * @param greeting     Initial greeting message
 * @param alignment    D&D-style alignment (lawful_good, neutral, chaotic_evil,
 *                     etc.)
 * @param moralProfile Moral constraints for the NPC
 * @param location     Last known location (persisted locally)
 * @param entityUuid   In-game entity UUID (null if not spawned)
 * @param syncedAt     When this data was last synced
 */
public class NpcData {
    private final String id;
    private final String externalId;
    // Volatile for thread visibility (SocketManager updates, Game thread reads)
    private volatile String name;
    private volatile String personality;
    private volatile String greeting;
    private volatile Number chatDistance;
    private volatile boolean broadcastReplies;
    private volatile String alignment;
    private volatile MoralProfile moralProfile;
    private volatile boolean invincible;
    private volatile boolean preventKnockback;
    private volatile Instant syncedAt;

    public NpcData(String id, String externalId, String name, String personality, String greeting,
            Number chatDistance, boolean broadcastReplies, String alignment, MoralProfile moralProfile, boolean invincible,
            boolean preventKnockback, Instant syncedAt) {
        this.id = id;
        this.externalId = externalId;
        this.name = name;
        this.personality = personality;
        this.greeting = greeting;
        this.chatDistance = chatDistance;
        this.broadcastReplies = broadcastReplies;
        this.alignment = alignment;
        this.moralProfile = moralProfile;
        this.invincible = invincible;
        this.preventKnockback = preventKnockback;
        this.syncedAt = syncedAt;
    }

    // Synchronized to ensure atomic updates of all fields together
    // Synchronized to ensure atomic updates of all fields together
    public synchronized void updateFrom(NpcData other) {
        if (!this.externalId.equals(other.externalId))
            return;

        if (other.name != null)
            this.name = other.name;
        if (other.personality != null)
            this.personality = other.personality;
        if (other.greeting != null)
            this.greeting = other.greeting;
        if (other.chatDistance != null)
            this.chatDistance = other.chatDistance;
        this.broadcastReplies = other.broadcastReplies;
        if (other.alignment != null)
            this.alignment = other.alignment;
        if (other.moralProfile != null)
            this.moralProfile = other.moralProfile;
        this.invincible = other.invincible;
        this.preventKnockback = other.preventKnockback;
        if (other.syncedAt != null)
            this.syncedAt = other.syncedAt;
    }

    public String id() {
        return id;
    }

    public String externalId() {
        return externalId;
    }

    public String name() {
        return name;
    }

    public String personality() {
        return personality;
    }

    public String greeting() {
        return greeting;
    }

    public Number chatDistance() {
        return chatDistance;
    }

    public boolean broadcastReplies() {
        return broadcastReplies;
    }

    public String alignment() {
        return alignment;
    }

    public MoralProfile moralProfile() {
        return moralProfile;
    }

    public boolean isInvincible() {
        return invincible;
    }

    public boolean isKnockbackDisabled() {
        return preventKnockback;
    }

    public Instant syncedAt() {
        return syncedAt;
    }

    /**
     * Create NPC from sync payload
     */
    public static NpcData fromSync(
            String id,
            String externalId,
            String name,
            String personality,
            String greeting,
            Number chatDistance,
            boolean broadcastReplies,
            String alignment,
            MoralProfile moralProfile,
            boolean isInvincible,
            boolean preventKnockback) {
        return new NpcData(
                id,
                externalId,
                name,
                personality,
                greeting,
                chatDistance,
                broadcastReplies,
                alignment != null ? alignment : "neutral",
                moralProfile != null ? moralProfile : MoralProfile.DEFAULT,
                isInvincible,
                preventKnockback,
                Instant.now());
    }

    /**
     * Create updated copy with sync timestamp
     */
    public NpcData withSyncedAt(Instant newSyncedAt) {
        return new NpcData(
                id, externalId, name, personality, greeting,
                chatDistance, broadcastReplies, alignment, moralProfile, invincible, preventKnockback, newSyncedAt);
    }

    /**
     * Moral profile for the NPC
     * 
     * @param ideals               Core beliefs that the NPC will not violate
     * @param persuasionResistance How resistant to player manipulation (total,
     *                             strong, medium, weak)
     */
    public record MoralProfile(
            List<String> ideals,
            String persuasionResistance) {
        public static final MoralProfile DEFAULT = new MoralProfile(
                List.of("Be helpful within my role"),
                "strong");

        /**
         * Check if NPC is highly resistant to manipulation
         */
        public boolean isHighlyResistant() {
            return "total".equals(persuasionResistance) || "strong".equals(persuasionResistance);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NpcData npcData = (NpcData) o;
        return java.util.Objects.equals(externalId, npcData.externalId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(externalId);
    }

    @Override
    public String toString() {
        return "NpcData[" +
                "id=" + id + ", " +
                "externalId=" + externalId + ", " +
                "name=" + name + ']';
    }
}
