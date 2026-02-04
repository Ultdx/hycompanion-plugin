package dev.hycompanion.plugin.shutdown;

import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized shutdown manager for coordinating graceful shutdown across all plugin components.
 * 
 * This class provides:
 * 1. Single source of truth for shutdown state
 * 2. Circuit breaker pattern for world thread operations
 * 3. Listener pattern for components to register cleanup callbacks
 * 4. Protection against operations during server shutdown
 * 
 * @author Hycompanion Team
 */
public class ShutdownManager {

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean worldOperationsBlocked = new AtomicBoolean(false);
    private final AtomicInteger pendingWorldOperations = new AtomicInteger(0);
    private final List<ShutdownListener> listeners = new CopyOnWriteArrayList<>();
    private final PluginLogger logger;

    public ShutdownManager(PluginLogger logger) {
        this.logger = logger;
    }

    /**
     * Check if the server is shutting down.
     * This is the single source of truth for shutdown state.
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * Block all world operations immediately.
     * Called from ShutdownEvent before player removal starts.
     */
    public void blockWorldOperations() {
        if (worldOperationsBlocked.compareAndSet(false, true)) {
            String threadName = Thread.currentThread().getName();
            logger.info("[ShutdownManager] World operations BLOCKED (thread: " + threadName + ")");
        }
    }

    /**
     * Check if world operations are blocked.
     */
    public boolean areWorldOperationsBlocked() {
        return worldOperationsBlocked.get();
    }

    /**
     * Set the shutting down flag WITHOUT blocking world operations.
     * This is called during early shutdown to signal that shutdown has started,
     * but allows Hytale's chunk saving to proceed normally.
     */
    public void setShuttingDown() {
        String threadName = Thread.currentThread().getName();
        if (shuttingDown.compareAndSet(false, true)) {
            logger.info("[ShutdownManager] Shutdown flag set (world operations NOT blocked) on thread: " + threadName);
        }
    }
    
    /**
     * Initiate shutdown sequence.
     * This method is idempotent - calling it multiple times has no additional effect.
     * 
     * @return true if this call initiated shutdown, false if already shutting down
     */
    public boolean initiateShutdown() {
        String threadName = Thread.currentThread().getName();
        long timestamp = System.currentTimeMillis();
        
        // Block world operations FIRST
        blockWorldOperations();
        
        if (shuttingDown.compareAndSet(false, true)) {
            logger.info("[ShutdownManager] ============================================");
            logger.info("[ShutdownManager] SHUTDOWN INITIATED (with world operations BLOCKED)");
            logger.info("[ShutdownManager] Thread: " + threadName);
            logger.info("[ShutdownManager] Timestamp: " + timestamp);
            logger.info("[ShutdownManager] Pending world operations: " + pendingWorldOperations.get());
            logger.info("[ShutdownManager] Notifying " + listeners.size() + " listeners...");
            
            long notifyStart = System.currentTimeMillis();
            notifyListeners();
            long notifyTime = System.currentTimeMillis() - notifyStart;
            
            logger.info("[ShutdownManager] Listeners notified in " + notifyTime + "ms");
            logger.info("[ShutdownManager] ============================================");
            return true;
        }
        logger.debug("[ShutdownManager] Shutdown already initiated, ignoring duplicate call from thread: " + threadName);
        return false;
    }

    /**
     * Register a component to be notified during shutdown.
     * If already shutting down, the listener is called immediately.
     */
    public void register(ShutdownListener listener) {
        if (shuttingDown.get()) {
            // Already shutting down, call immediately
            try {
                listener.onShutdown();
            } catch (Exception e) {
                logger.warn("[ShutdownManager] Listener threw exception: " + e.getMessage());
            }
        } else {
            listeners.add(listener);
        }
    }

    /**
     * Unregister a component. Safe to call even if not registered.
     */
    public void unregister(ShutdownListener listener) {
        listeners.remove(listener);
    }

    /**
     * Execute an operation only if not shutting down.
     * Returns true if executed, false if skipped due to shutdown.
     */
    public boolean executeIfNotShutdown(Runnable operation) {
        if (shuttingDown.get()) {
            return false;
        }
        try {
            operation.run();
            return true;
        } catch (Exception e) {
            logger.debug("[ShutdownManager] Operation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Circuit breaker for world thread operations.
     * Returns true if operation should proceed, false if should be rejected.
     * Use this to check before submitting to world.execute()
     */
    public boolean allowWorldOperation() {
        return !worldOperationsBlocked.get() && !Thread.currentThread().isInterrupted();
    }

    /**
     * Safe wrapper for world thread operations.
     * Returns true if submitted, false if rejected due to shutdown.
     * 
     * CRITICAL: This method MUST reject tasks during shutdown to prevent
     * "Invalid entity reference" errors. Once shutdown starts, no new
     * tasks should be submitted to the world thread.
     */
    public boolean safeWorldExecute(java.util.function.Consumer<Runnable> worldExecutor, Runnable task) {
        // AGGRESSIVE: Check shutdown flag BEFORE any operation
        if (worldOperationsBlocked.get()) {
            logger.debug("[ShutdownManager] World operation REJECTED - shutdown in progress (blocked)");
            return false;
        }
        
        // Track pending operation
        int pending = pendingWorldOperations.incrementAndGet();
        try {
            // Double-check after incrementing
            if (worldOperationsBlocked.get()) {
                logger.debug("[ShutdownManager] World operation REJECTED - shutdown started during check");
                return false;
            }
            
            worldExecutor.accept(() -> {
                try {
                    if (!worldOperationsBlocked.get()) {
                        task.run();
                    } else {
                        logger.debug("[ShutdownManager] World task SKIPPED - shutdown in progress");
                    }
                } catch (Exception e) {
                    logger.debug("[ShutdownManager] World task FAILED: " + e.getMessage());
                } finally {
                    int remaining = pendingWorldOperations.decrementAndGet();
                    if (remaining < 0) {
                        logger.warn("[ShutdownManager] Pending operations went negative: " + remaining);
                    }
                }
            });
            logger.debug("[ShutdownManager] World operation ACCEPTED (pending: " + pending + ")");
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("[ShutdownManager] World operation REJECTED - executor rejected (shutting down?)");
            pendingWorldOperations.decrementAndGet();
            return false;
        } catch (Exception e) {
            logger.debug("[ShutdownManager] World operation FAILED: " + e.getMessage());
            pendingWorldOperations.decrementAndGet();
            return false;
        }
    }

    /**
     * Get count of pending world operations.
     */
    public int getPendingWorldOperations() {
        return pendingWorldOperations.get();
    }

    private void notifyListeners() {
        for (ShutdownListener listener : listeners) {
            try {
                listener.onShutdown();
            } catch (Exception e) {
                logger.warn("[ShutdownManager] Listener threw exception: " + e.getMessage());
            }
        }
    }

    /**
     * Functional interface for shutdown listeners.
     */
    @FunctionalInterface
    public interface ShutdownListener {
        void onShutdown();
    }
}
