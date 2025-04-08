package com.sailfish.asyn.model;

/**
 * Represents the possible statuses of an asynchronous task.
 */
public enum TaskStatus {
    /**
     * Task has been received and persisted, waiting for initial execution.
     */
    PENDING,
    /**
     * Task is currently being executed.
     */
    PROCESSING,
    /**
     * Task execution completed successfully.
     */
    COMPLETED,
    /**
     * Task execution failed, and retry limit has been reached or retries are disabled.
     */
    FAILED,
    /**
     * Task execution failed, waiting for the next retry attempt.
     */
    PENDING_RETRY
}