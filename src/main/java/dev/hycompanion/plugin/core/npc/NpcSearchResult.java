package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;

public record NpcSearchResult(
        NpcInstanceData instance,
        Location location,
        double distance) {
}
