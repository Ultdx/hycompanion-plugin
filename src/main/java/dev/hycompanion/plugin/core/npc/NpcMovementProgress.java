package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;

/** Arrival and stall detection from observed positions, sampled on the world thread. */
public final class NpcMovementProgress {
    public static final String STATE = "Move";
    public static final double STOP_DISTANCE = 1.0;
    public static final double ARRIVAL_DISTANCE = 1.5;
    private static final double MIN_PROGRESS = 0.2;
    private static final long STUCK_TIMEOUT_MS = 3000;
    private static final long PATH_START_TIMEOUT_MS = 10000;

    public enum Status { MOVING, ARRIVED, STUCK }

    private final Location destination;
    private Location lastPosition;
    private long lastProgressTime;
    private boolean hasMoved;

    public NpcMovementProgress(Location destination) {
        this.destination = destination;
    }

    /** Preserve movement for inherited or cached roles that only provide Follow. */
    public static String selectState(boolean hasMove, boolean hasFollow) {
        return hasMove ? STATE : hasFollow ? "Follow" : null;
    }

    public Status observe(Location position, long nowMillis) {
        return observe(position, nowMillis, false);
    }

    /** Give active path computation a bounded startup grace, never a moving NPC a longer stall timeout. */
    public Status observe(Location position, long nowMillis, boolean computingPath) {
        if (position.distanceTo(destination) <= ARRIVAL_DISTANCE) {
            return Status.ARRIVED;
        }
        if (lastPosition == null || position.distanceTo(lastPosition) > MIN_PROGRESS) {
            hasMoved |= lastPosition != null;
            lastPosition = position;
            lastProgressTime = nowMillis;
        } else if (nowMillis - lastProgressTime >=
                (computingPath && !hasMoved ? PATH_START_TIMEOUT_MS : STUCK_TIMEOUT_MS)) {
            return Status.STUCK;
        }
        return Status.MOVING;
    }
}
