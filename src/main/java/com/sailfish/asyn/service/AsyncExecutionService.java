package com.sailfish.asyn.service;

import com.sailfish.asyn.model.AsyncTaskRecord;
import java.io.Serializable;
import java.util.concurrent.Future; // Optional: return Future if caller needs execution status/result

/**
 * Service interface for submitting tasks for asynchronous execution.
 */
public interface AsyncExecutionService {

    /**
     * Submits a task for asynchronous execution.
     * The task details are persisted first, then the task is scheduled for immediate execution.
     * If execution fails, it will be retried according to the configured RetryStrategy.
     *
     * @param taskType A string identifier for the type of task (used by AsyncTaskFactory).
     * @param payload The data required for the task execution. Must be Serializable.
     * @param <T> The type of the payload.
     * @return The persisted AsyncTaskRecord representing the submitted task.
     * Could potentially return a Future<Void> or Future<AsyncTaskRecord> if needed.
     * @throws IllegalArgumentException if taskType is blank or payload is null.
     * @throws RuntimeException if persistence fails or task submission to executor fails critically.
     */
    <T extends Serializable> AsyncTaskRecord submitTask(String taskType, T payload);

    // Optional: Method to explicitly trigger a retry for a failed task?
    // Optional: Method to query task status?

    /**
     * Initiates a graceful shutdown of the underlying executor services.
     * Should be called during application shutdown.
     *
     * @param timeoutSeconds Time to wait for tasks to complete before forceful shutdown.
     */
    void shutdown(long timeoutSeconds);
}