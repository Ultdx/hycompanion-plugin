package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NpcMovementSessionTest {
    @Test
    void interruptedMovementCompletesItsResultAndReleasesItsOwnResourcesOnce() {
        var released = new ArrayList<String>();
        var session = new NpcMovementSession<String>(released::add);
        var monitor = new TestMonitor();
        assertTrue(session.setTarget("old-marker"));
        session.setMonitor(monitor);
        assertFalse(session.result().isDone());

        session.finish(NpcMoveResult.failed("interrupted"));
        session.finish(NpcMoveResult.failed("shutdown"));

        assertEquals("interrupted", session.result().join().status());
        assertFalse(session.result().join().success());
        assertTrue(monitor.isCancelled());
        assertEquals(java.util.List.of("old-marker"), released);
        assertNull(session.target());
    }

    @Test
    void cancellingBeforeQueuedSetupReleasesLateResources() {
        var released = new ArrayList<String>();
        var session = new NpcMovementSession<String>(released::add);
        session.finish(NpcMoveResult.failed("interrupted"));
        var monitor = new TestMonitor();

        assertFalse(session.setTarget("late-marker"));
        session.setMonitor(monitor);

        assertEquals(java.util.List.of("late-marker"), released);
        assertTrue(monitor.isCancelled());
        assertNull(session.target());
    }

    @Test
    void completionCanStartANewMovementWithoutOldCleanupRemovingItsResources() {
        var released = new ArrayList<String>();
        var old = new NpcMovementSession<String>(released::add);
        old.setTarget("old-marker");
        var next = new AtomicReference<NpcMovementSession<String>>();
        old.result().thenAccept(result -> {
            assertEquals(java.util.List.of("old-marker"), released);
            var replacement = new NpcMovementSession<String>(released::add);
            replacement.setTarget("new-marker");
            next.set(replacement);
        });

        old.finish(NpcMoveResult.success(Location.of(1, 2, 3)));
        old.finish(NpcMoveResult.failed("interrupted"));

        assertEquals("new-marker", next.get().target());
        assertFalse(next.get().result().isDone());
        assertEquals(java.util.List.of("old-marker"), released);
    }

    @Test
    void shutdownCompletesPendingMovementRequests() {
        var session = new NpcMovementSession<String>(ignored -> {});
        session.finish(NpcMoveResult.failed("shutdown"));
        assertTrue(session.result().isDone());
        assertEquals("shutdown", session.result().join().status());
    }

    @Test
    void failedResourceDisposalDoesNotLeaveTheMovementResultPending() {
        var session = new NpcMovementSession<String>(ignored -> { throw new IllegalStateException("removed"); });
        session.setTarget("marker");
        assertThrows(IllegalStateException.class, () -> session.finish(NpcMoveResult.failed("interrupted")));
        assertEquals("interrupted", session.result().join().status());
    }

    private static class TestMonitor extends FutureTask<Void> implements ScheduledFuture<Void> {
        TestMonitor() { super(() -> null); }
        public long getDelay(TimeUnit unit) { return 0; }
        public int compareTo(Delayed other) { return 0; }
    }
}
