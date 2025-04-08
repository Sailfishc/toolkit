package com.sailfish.asyn.retry;

import com.sailfish.asyn.model.AsyncTaskRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Defines the strategy for handling retries of failed tasks.
 */
public interface RetryStrategy {

    /**
     * Determines if a task should be retried based on its current state.
     *
     * @param record The task record.
     * @return true if the task should be retried, false otherwise (e.g., retry limit reached).
     */
    boolean shouldRetry(AsyncTaskRecord record);

    /**
     * Calculates the time for the next retry attempt.
     *
     * @param record The task record containing the current retry count.
     * @return An Optional containing the LocalDateTime for the next attempt, or empty if no retry should occur.
     */
    Optional<LocalDateTime> calculateNextRetryTime(AsyncTaskRecord record);

}