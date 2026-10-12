package dev.hycompanion.plugin.core.npc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/** Owns one movement request and releases its resources before acknowledging completion. */
public final class NpcMovementSession<T> {
    private final CompletableFuture<NpcMoveResult> result = new CompletableFuture<>();
    private final Consumer<T> disposeTarget;
    private ScheduledFuture<?> monitor;
    private T target;
    private boolean closed;

    public NpcMovementSession(Consumer<T> disposeTarget) {
        this.disposeTarget = disposeTarget;
    }

    public CompletableFuture<NpcMoveResult> result() {
        return result;
    }

    public synchronized T target() {
        return target;
    }

    public synchronized boolean setTarget(T target) {
        if (closed) {
            disposeTarget.accept(target);
            return false;
        }
        this.target = target;
        return true;
    }

    public synchronized void setMonitor(ScheduledFuture<?> monitor) {
        if (closed) {
            monitor.cancel(false);
        } else {
            this.monitor = monitor;
        }
    }

    public void finish(NpcMoveResult outcome) {
        T targetToDispose;
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (monitor != null) monitor.cancel(false);
            monitor = null;
            targetToDispose = target;
            target = null;
        }
        try {
            if (targetToDispose != null) disposeTarget.accept(targetToDispose);
        } finally {
            result.complete(outcome);
        }
    }
}
