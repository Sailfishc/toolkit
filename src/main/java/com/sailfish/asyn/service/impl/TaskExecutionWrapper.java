package com.sailfish.asyn.service.impl;

import com.sailfish.asyn.AsyncTask;
import com.sailfish.asyn.factory.AsyncTaskFactory;
import com.sailfish.asyn.model.AsyncTaskRecord;
import com.sailfish.asyn.model.TaskStatus;
import com.sailfish.asyn.repository.AsyncTaskRepository;
import com.sailfish.asyn.retry.RetryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * A Runnable responsible for executing a single asynchronous task,
 * handling its lifecycle (status updates, retries) within the execution thread.
 *
 * This class requires careful transaction management if repository operations
 * are not automatically transactional. Ideally, key status updates should be atomic.
 */
public class TaskExecutionWrapper implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionWrapper.class);

    private final Long recordId;
    private final AsyncTaskRepository taskRepository;
    private final AsyncTaskFactory taskFactory;
    private final RetryStrategy retryStrategy;

    public TaskExecutionWrapper(Long recordId,
                                AsyncTaskRepository taskRepository,
                                AsyncTaskFactory taskFactory,
                                RetryStrategy retryStrategy) {
        this.recordId = Objects.requireNonNull(recordId, "recordId cannot be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository cannot be null");
        this.taskFactory = Objects.requireNonNull(taskFactory, "taskFactory cannot be null");
        this.retryStrategy = Objects.requireNonNull(retryStrategy, "retryStrategy cannot be null");
    }

    @Override
    public void run() {
        log.debug("Starting execution for task ID: {}", recordId);

        // 1. Fetch the task record - Exit if not found (e.g., deleted externally)
        Optional<AsyncTaskRecord> recordOpt = taskRepository.findById(recordId);
        if (!recordOpt.isPresent()) {
            log.warn("AsyncTaskRecord with ID {} not found. Skipping execution.", recordId);
            return;
        }
        AsyncTaskRecord record = recordOpt.get();

        // 2. Check current status - Avoid re-processing completed/failed tasks
        //    Could potentially handle PENDING_RETRY here too if scheduler missed it, but
        //    primarily expecting PENDING or PENDING_RETRY status.
        if (record.getStatus() == TaskStatus.COMPLETED || record.getStatus() == TaskStatus.FAILED) {
            log.warn("Task ID {} already in terminal state {}. Skipping execution.", recordId, record.getStatus());
            return;
        }

        // 3. Mark as PROCESSING (Atomic update if possible)
        //    This prevents concurrent execution attempts by the scheduler.
        boolean updated = taskRepository.updateStatus(recordId, TaskStatus.PROCESSING, null);
        if (!updated) {
            log.warn("Failed to update task ID {} status to PROCESSING (maybe updated concurrently?). Skipping execution.", recordId);
            // It's possible another thread/node already started processing or finished.
            return;
        }
        log.debug("Task ID {} marked as PROCESSING.", recordId);


        try {
            // 4. Find and Execute the Actual Task Logic
            Optional<AsyncTask<Serializable>> taskOpt = taskFactory.getTask(record.getTaskType());
            if (!taskOpt.isPresent()) {
                throw new IllegalStateException("No AsyncTask implementation found for task type: " + record.getTaskType());
            }

            AsyncTask<Serializable> task = taskOpt.get();
            task.execute(record.getPayload()); // Execute the business logic

            // 5. Mark as COMPLETED on Success
            log.info("Task ID {} executed successfully.", recordId);
            taskRepository.updateStatus(recordId, TaskStatus.COMPLETED, null);

        } catch (Exception e) {
            // 6. Handle Execution Failure
            log.error("Task ID {} failed execution: {}", recordId, e.getMessage(), e);
            String errorDetails = getStackTraceAsString(e);

            // Check retry strategy
            if (retryStrategy.shouldRetry(record)) {
                Optional<LocalDateTime> nextRetryTimeOpt = retryStrategy.calculateNextRetryTime(record);
                if (nextRetryTimeOpt.isPresent()) {
                    // Schedule for retry
                    int nextRetryCount = record.getRetryCount() + 1; // Increment retry count here before saving
                    log.warn("Task ID {} failed. Scheduling retry {} for {}.", recordId, nextRetryCount, nextRetryTimeOpt.get());
                    taskRepository.scheduleForRetry(recordId, nextRetryCount, nextRetryTimeOpt.get(), errorDetails);
                } else {
                    // Should not happen if shouldRetry was true, but handle defensively
                    log.error("Task ID {} should be retried, but failed to calculate next retry time. Marking as FAILED.", recordId);
                    taskRepository.updateStatus(recordId, TaskStatus.FAILED, errorDetails);
                }
            } else {
                // Max retries reached or retries disabled
                log.error("Task ID {} failed and retry limit reached or retries disabled. Marking as FAILED.", recordId);
                taskRepository.updateStatus(recordId, TaskStatus.FAILED, errorDetails);
            }
        } finally{
            // Any cleanup if needed, though most state is managed via DB updates.
        }
    }

    private String getStackTraceAsString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();
        // Truncate if necessary
        int maxLength = 2000; // Match the DB column length approx
        if (stackTrace.length() > maxLength) {
            return stackTrace.substring(0, maxLength - 3) + "...";
        }
        return stackTrace;
    }
}