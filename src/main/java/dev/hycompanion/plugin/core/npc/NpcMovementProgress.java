package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;

/** Arrival and stall detection from observed positions, sampled on the world thread. */
public final class NpcMovementProgress {
    public static final String STATE = "Move";
    public static final double STOP_DISTANCE = 1.0;
    public static final double ARRIVAL_DISTANCE = 1.5;
    private static final double MIN_PROGRESS = 0.2;
    private static final long STUCK_TIMEOUT_MS = 3000;

    public enum Status { MOVING, ARRIVED, STUCK }

    private final Location destination;
    private Location lastPosition;
    private long lastProgressTime;

    public NpcMovementProgress(Location destination) {
        this.destination = destination;
    }

    public Status observe(Location position, long nowMillis) {
        if (position.distanceTo(destination) <= ARRIVAL_DISTANCE) {
            return Status.ARRIVED;
        }
        if (lastPosition == null || position.distanceTo(lastPosition) > MIN_PROGRESS) {
            lastPosition = position;
            lastProgressTime = nowMillis;
        } else if (nowMillis - lastProgressTime >= STUCK_TIMEOUT_MS) {
            return Status.STUCK;
        }
        return Status.MOVING;
    }
}
