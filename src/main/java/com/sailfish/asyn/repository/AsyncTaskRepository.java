package com.sailfish.asyn.repository;

import com.sailfish.asyn.model.AsyncTaskRecord;
import com.sailfish.asyn.model.TaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing AsyncTaskRecord entities.
 * Implementations will handle database interactions (e.g., using JPA, JDBC).
 */
public interface AsyncTaskRepository {

    /**
     * Saves or updates a task record.
     *
     * @param record The record to save.
     * @return The saved record, potentially with generated ID or updated timestamps.
     */
    AsyncTaskRecord save(AsyncTaskRecord record);

    /**
     * Finds a task record by its ID.
     *
     * @param id The ID of the task record.
     * @return An Optional containing the record if found, empty otherwise.
     */
    Optional<AsyncTaskRecord> findById(Long id);

    /**
     * Finds tasks that are in PENDING_RETRY status and whose nextRetryTime is due.
     *
     * @param now The current time, used to compare against nextRetryTime.
     * @param limit The maximum number of tasks to fetch in one go.
     * @return A list of task records ready for retry.
     */
    List<AsyncTaskRecord> findTasksPendingRetry(LocalDateTime now, int limit);

    /**
     * Updates the status of a task record. This might be optimized in implementations
     * to avoid fetching the entire entity.
     *
     * @param id The ID of the task record.
     * @param status The new status.
     * @param lastError Optional error message if status is FAILED.
     * @return true if the update was successful, false otherwise.
     */
    boolean updateStatus(Long id, TaskStatus status, String lastError);

    /**
     * Updates the record after a failed attempt, setting it up for the next retry.
     *
     * @param id The ID of the task record.
     * @param newRetryCount The incremented retry count.
     * @param nextRetryTime The calculated time for the next attempt.
     * @param lastError The error message from the last attempt.
     * @return true if the update was successful, false otherwise.
     */
    boolean scheduleForRetry(Long id, int newRetryCount, LocalDateTime nextRetryTime, String lastError);

}