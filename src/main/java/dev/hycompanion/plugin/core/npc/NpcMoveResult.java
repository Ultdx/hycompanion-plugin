package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;

public record NpcMoveResult(boolean success, String status, Location finalLocation) {
    public static NpcMoveResult success(Location location) {
        return new NpcMoveResult(true, "success", location);
    }

    public static NpcMoveResult timeout(Location location) {
        return new NpcMoveResult(false, "timeout", location);
    }

    public static NpcMoveResult failed(String reason) {
        return new NpcMoveResult(false, reason, null);
    }
}
