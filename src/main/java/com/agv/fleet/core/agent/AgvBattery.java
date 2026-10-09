package com.agv.fleet.core.agent;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe representation of an AGV's on-board battery power management.
 */
public class AgvBattery {

    public static final double LOW_BATTERY_THRESHOLD = 15.0; // 15% threshold for critical battery
    public static final double MAX_BATTERY_LEVEL = 100.0;
    public static final double MIN_BATTERY_LEVEL = 0.0;

    private final ReentrantLock lock = new ReentrantLock();
    private double level;

    public AgvBattery() {
        this(MAX_BATTERY_LEVEL);
    }

    public AgvBattery(double initialLevel) {
        this.level = Math.clamp(initialLevel, MIN_BATTERY_LEVEL, MAX_BATTERY_LEVEL);
    }

    public double getLevel() {
        lock.lock();
        try {
            return level;
        } finally {
            lock.unlock();
        }
    }

    public void setLevel(double newLevel) {
        lock.lock();
        try {
            this.level = Math.clamp(newLevel, MIN_BATTERY_LEVEL, MAX_BATTERY_LEVEL);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Consumes an amount of battery power.
     *
     * @param amount energy consumed
     * @return remaining battery percentage
     */
    public double consume(double amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Consumption amount cannot be negative: " + amount);
        }
        lock.lock();
        try {
            this.level = Math.clamp(this.level - amount, MIN_BATTERY_LEVEL, MAX_BATTERY_LEVEL);
            return this.level;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Recharges an amount of battery power.
     *
     * @param amount energy recharged
     * @return resulting battery percentage
     */
    public double charge(double amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Recharge amount cannot be negative: " + amount);
        }
        lock.lock();
        try {
            this.level = Math.clamp(this.level + amount, MIN_BATTERY_LEVEL, MAX_BATTERY_LEVEL);
            return this.level;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if current battery is at or below the low battery threshold (&lt;= 15.0%).
     *
     * @return {@code true} if battery is critically low
     */
    public boolean isLowBattery() {
        lock.lock();
        try {
            return level <= LOW_BATTERY_THRESHOLD;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return String.format("AgvBattery[level=%.1f%%, isLow=%b]", level, level <= LOW_BATTERY_THRESHOLD);
        } finally {
            lock.unlock();
        }
    }
}
