package com.sailfish.asyn.retry;

import com.sailfish.asyn.model.AsyncTaskRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A retry strategy implementing exponential backoff with optional jitter.
 */
public class ExponentialBackoffRetryStrategy implements RetryStrategy {

    private final int maxRetries;
    private final Duration initialDelay;
    private final double multiplier;
    private final Duration maxDelay; // Optional: Cap the maximum delay
    private final boolean addJitter;

    public static final int DEFAULT_MAX_RETRIES = 15;
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(5);
    public static final double DEFAULT_MULTIPLIER = 2.0;
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofHours(1); // Default cap at 1 hour

    /**
     * Creates a default ExponentialBackoffRetryStrategy.
     * Max Retries: 15
     * Initial Delay: 5 seconds
     * Multiplier: 2.0
     * Max Delay: 1 hour
     * Jitter: true
     */
    public ExponentialBackoffRetryStrategy() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_DELAY, DEFAULT_MULTIPLIER, DEFAULT_MAX_DELAY, true);
    }

    /**
     * Creates a configurable ExponentialBackoffRetryStrategy.
     *
     * @param maxRetries Maximum number of retry attempts.
     * @param initialDelay Delay before the first retry.
     * @param multiplier Factor by which the delay increases for each subsequent retry.
     * @param maxDelay Optional maximum delay cap. Set to null or Duration.ZERO to disable.
     * @param addJitter If true, adds a small random variation to the delay to prevent thundering herd issues.
     */
    public ExponentialBackoffRetryStrategy(int maxRetries, Duration initialDelay, double multiplier, Duration maxDelay, boolean addJitter) {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be non-negative");
        if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()) throw new IllegalArgumentException("initialDelay must be positive");
        if (multiplier <= 1.0) throw new IllegalArgumentException("multiplier must be greater than 1.0");

        this.maxRetries = maxRetries;
        this.initialDelay = initialDelay;
        this.multiplier = multiplier;
        this.maxDelay = (maxDelay != null && !maxDelay.isNegative() && !maxDelay.isZero()) ? maxDelay : null;
        this.addJitter = addJitter;
    }


    @Override
    public boolean shouldRetry(AsyncTaskRecord record) {
        return record.getRetryCount() < this.maxRetries;
    }

    @Override
    public Optional<LocalDateTime> calculateNextRetryTime(AsyncTaskRecord record) {
        if (!shouldRetry(record)) {
            return Optional.empty();
        }

        int attempt = record.getRetryCount(); // 0-based attempt count for calculation
        long delayMillis = initialDelay.toMillis();

        if (attempt > 0) {
            delayMillis = (long) (initialDelay.toMillis() * Math.pow(multiplier, attempt));
        }

        // Apply max delay cap if configured
        if (maxDelay != null && delayMillis > maxDelay.toMillis()) {
            delayMillis = maxDelay.toMillis();
        }

        // Apply jitter: add random +/- 10% of the calculated delay
        if (addJitter && delayMillis > 0) {
            long jitter = (long) (delayMillis * 0.1 * (ThreadLocalRandom.current().nextDouble() * 2 - 1)); // Range [-0.1, 0.1]
             // Ensure delay doesn't become negative due to jitter, minimum 1ms if jittered below zero
            delayMillis = Math.max(1, delayMillis + jitter);
        } else if (delayMillis <= 0) {
             delayMillis = 1; // Ensure at least 1ms delay if calculation resulted in zero or negative
        }


        LocalDateTime nextAttemptTime = LocalDateTime.now().plus(Duration.ofMillis(delayMillis));
        return Optional.of(nextAttemptTime);
    }

    // --- Getters for configuration ---
    public int getMaxRetries() { return maxRetries; }
    public Duration getInitialDelay() { return initialDelay; }
    public double getMultiplier() { return multiplier; }
    public Duration getMaxDelay() { return maxDelay; }
    public boolean isAddJitter() { return addJitter; }
}