package com.agv.fleet.ui.simulation;

import java.util.concurrent.*;

/**
 * Controller for the simulation physics loop.
 * <p>
 * Enforces:
 * <ul>
 *   <li>Only discrete speed values (0.25x, 0.5x, 1.0x, 2.0x, 4.0x)</li>
 *   <li>Base period of 250ms (4 Hz) at 1.0x speed, scaled inversely by speed multiplier</li>
 *   <li>Proper cancellation of existing {@link ScheduledFuture} prior to scheduling a new loop</li>
 *   <li>Guarantees exactly one active ticker even under rapid concurrent restart calls</li>
 * </ul>
 */
public class PhysicsLoopController {

    public static final double[] DISCRETE_SPEEDS = {0.25, 0.5, 1.0, 2.0, 4.0};
    public static final long BASE_PERIOD_MS = 250; // 4 Hz at 1.0x speed (250 ms / cell)

    private final ScheduledExecutorService executor;
    private final boolean managesExecutor;
    private final Runnable tickTask;

    private ScheduledFuture<?> currentFuture;
    private volatile double currentDiscreteSpeed = 1.0;
    private volatile boolean paused = false;

    public PhysicsLoopController(Runnable tickTask) {
        this(tickTask, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fms-physics-sim");
            t.setDaemon(true);
            return t;
        }), true);
    }

    public PhysicsLoopController(Runnable tickTask, ScheduledExecutorService executor, boolean managesExecutor) {
        this.tickTask = tickTask;
        this.executor = executor;
        this.managesExecutor = managesExecutor;
    }

    /**
     * Snaps an arbitrary speed multiplier to the closest discrete speed in {0.25, 0.5, 1.0, 2.0, 4.0}.
     */
    public static double snapToDiscreteSpeed(double rawSpeed) {
        double closest = DISCRETE_SPEEDS[0];
        double minDiff = Math.abs(rawSpeed - closest);
        for (double s : DISCRETE_SPEEDS) {
            double diff = Math.abs(rawSpeed - s);
            if (diff < minDiff) {
                minDiff = diff;
                closest = s;
            }
        }
        return closest;
    }

    /**
     * Updates the simulation speed. Restarts the physics loop only if the snapped discrete speed
     * has actually changed.
     *
     * @param rawSpeed requested speed multiplier
     * @return {@code true} if the physics loop was restarted, {@code false} if speed was unchanged
     */
    public synchronized boolean updateSpeed(double rawSpeed) {
        double snapped = snapToDiscreteSpeed(rawSpeed);
        if (Double.compare(snapped, currentDiscreteSpeed) == 0 && currentFuture != null && !currentFuture.isDone()) {
            return false;
        }
        currentDiscreteSpeed = snapped;
        restartPhysicsLoop();
        return true;
    }

    /**
     * Restarts the physics loop, canceling any previous task.
     */
    public synchronized void restartPhysicsLoop() {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        if (currentFuture != null && !currentFuture.isDone()) {
            currentFuture.cancel(false);
        }

        long periodMs = Math.max(25, Math.round(BASE_PERIOD_MS / currentDiscreteSpeed));
        currentFuture = executor.scheduleAtFixedRate(() -> {
            if (!paused) {
                try {
                    tickTask.run();
                } catch (Throwable t) {
                    // Suppress to keep executor loop alive
                }
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Manually triggers exactly one physics tick (used in Step Mode).
     */
    public void stepOneTick() {
        if (executor != null && !executor.isShutdown()) {
            executor.execute(() -> {
                try {
                    tickTask.run();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    public synchronized void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public double getCurrentDiscreteSpeed() {
        return currentDiscreteSpeed;
    }

    public long getCurrentPeriodMs() {
        return Math.max(25, Math.round(BASE_PERIOD_MS / currentDiscreteSpeed));
    }

    public synchronized void stop() {
        if (currentFuture != null) {
            currentFuture.cancel(false);
        }
        if (managesExecutor && executor != null) {
            executor.shutdownNow();
        }
    }
}
