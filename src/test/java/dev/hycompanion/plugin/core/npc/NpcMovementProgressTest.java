package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.hycompanion.plugin.core.npc.NpcMovementProgress.Status.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NpcMovementProgressTest {
    // The NPC position returned by every move_to in the attached mushroom-gathering logs.
    private static final Location STATIONARY = Location.of(-706.1278907985318, 117, 47.572798887069894);

    @Test
    void prefersPreciseMovementAndPreservesLegacyFollowOnlyRoles() {
        assertEquals("Move", NpcMovementProgress.selectState(true, true));
        assertEquals("Move", NpcMovementProgress.selectState(true, false));
        assertEquals("Follow", NpcMovementProgress.selectState(false, true));
        org.junit.jupiter.api.Assertions.assertNull(NpcMovementProgress.selectState(false, false));
    }

    @ParameterizedTest
    @ValueSource(doubles = {50, 51, 52, 53})
    void stationaryNpcIsStuckEvenWithinFiveBlocks(double targetZ) {
        NpcMovementProgress progress = new NpcMovementProgress(Location.of(-707, 117, targetZ));
        assertEquals(MOVING, progress.observe(STATIONARY, 0));
        assertEquals(MOVING, progress.observe(STATIONARY, 2999));
        assertEquals(STUCK, progress.observe(STATIONARY, 3000));
    }

    @Test
    void realProgressResetsTheStallDeadlineAndArrivalRequiresTheActualPosition() {
        NpcMovementProgress progress = new NpcMovementProgress(Location.of(-707, 117, 53));
        assertEquals(MOVING, progress.observe(STATIONARY, 0));
        assertEquals(MOVING, progress.observe(Location.of(-707, 117, 49), 2900));
        assertEquals(MOVING, progress.observe(Location.of(-707, 117, 50), 5800));
        assertEquals(ARRIVED, progress.observe(Location.of(-707, 117, 52), 6000));
    }

    @Test
    void smallPositionJitterDoesNotHideAStall() {
        NpcMovementProgress progress = new NpcMovementProgress(Location.of(-707, 117, 53));
        assertEquals(MOVING, progress.observe(STATIONARY, 0));
        assertEquals(MOVING, progress.observe(STATIONARY.add(0.05, 0, 0), 1500));
        assertEquals(STUCK, progress.observe(STATIONARY.add(-0.05, 0, 0), 3000));
    }

    @Test
    void beingDirectlyBelowTheTargetIsNotArrival() {
        NpcMovementProgress progress = new NpcMovementProgress(Location.of(-707, 120, 53));
        Location below = Location.of(-707, 117, 53);
        assertEquals(MOVING, progress.observe(below, 0));
        assertEquals(STUCK, progress.observe(below, 3000));
    }

    @Test
    void pathComputationGetsABoundedStartupGrace() {
        NpcMovementProgress progress = new NpcMovementProgress(Location.of(-679, 116, 53));
        assertEquals(MOVING, progress.observe(STATIONARY, 0, true));
        assertEquals(MOVING, progress.observe(STATIONARY, 3000, true));
        assertEquals(MOVING, progress.observe(STATIONARY, 9999, true));
        assertEquals(STUCK, progress.observe(STATIONARY, 10000, true));
    }

    @Test
    void movementThatStartedStillTimesOutAfterThreeSecondsWithoutProgress() {
        NpcMovementProgress progress = new NpcMovementProgress(Location.of(-679, 116, 53));
        assertEquals(MOVING, progress.observe(STATIONARY, 0, true));
        Location moved = STATIONARY.add(1, 0, 0);
        assertEquals(MOVING, progress.observe(moved, 4000, true));
        assertEquals(STUCK, progress.observe(moved, 7000, true));
    }

    @Test
    void aBlockedPathDoesNotGetStartupGrace() {
        NpcMovementProgress progress = new NpcMovementProgress(Location.of(-679, 116, 53));
        assertEquals(MOVING, progress.observe(STATIONARY, 0, true));
        assertEquals(STUCK, progress.observe(STATIONARY, 3000, false));
    }
}
