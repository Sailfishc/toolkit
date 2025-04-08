package com.sailfish.asyn.service.impl;

import com.sailfish.asyn.AsyncTask;
import com.sailfish.asyn.factory.AsyncTaskFactory;
import com.sailfish.asyn.model.AsyncTaskRecord;
import com.sailfish.asyn.model.TaskStatus;
import com.sailfish.asyn.repository.AsyncTaskRepository;
import com.sailfish.asyn.retry.RetryStrategy;
import com.sailfish.asyn.service.AsyncExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.transaction.Transactional; // Or Spring's @Transactional
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Default implementation of the AsyncExecutionService.
 * Manages persistence, task submission, and coordinates with retry mechanisms.
 *
 * Assumes dependency injection for repositories, factories, strategies, and executors.
 */
public class AsyncExecutionServiceImpl implements AsyncExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AsyncExecutionServiceImpl.class);

    private final AsyncTaskRepository taskRepository;
    private final AsyncTaskFactory taskFactory;
    private final ExecutorService taskExecutor; // Main pool for task execution
    private final ScheduledExecutorService schedulerExecutor; // Pool used by RetryScheduler
    private final RetryStrategy defaultRetryStrategy; // Used when executing tasks

    // Constructor injection is recommended
    public AsyncExecutionServiceImpl(AsyncTaskRepository taskRepository,
                                     AsyncTaskFactory taskFactory,
                                     ExecutorService taskExecutor,
                                     ScheduledExecutorService schedulerExecutor, // Added
                                     RetryStrategy defaultRetryStrategy) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository cannot be null");
        this.taskFactory = Objects.requireNonNull(taskFactory, "taskFactory cannot be null");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor cannot be null");
        this.schedulerExecutor = Objects.requireNonNull(schedulerExecutor, "schedulerExecutor cannot be null"); // Added
        this.defaultRetryStrategy = Objects.requireNonNull(defaultRetryStrategy, "defaultRetryStrategy cannot be null");
        log.info("AsyncExecutionService initialized.");
    }

    @Override
    @Transactional // IMPORTANT: Persistence and initial submission should be atomic
    public <T extends Serializable> AsyncTaskRecord submitTask(String taskType, T payload) {
        if (taskType == null || taskType.trim().isEmpty()) {
            throw new IllegalArgumentException("taskType cannot be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload cannot be null");
        }

        log.debug("Submitting task of type '{}'", taskType);

        // 1. Create and Persist the initial record
        AsyncTaskRecord record = new AsyncTaskRecord();
        record.setTaskType(taskType);
        record.setPayload(payload);
        record.setStatus(TaskStatus.PENDING); // Initial status
        // Timestamps and initial retryCount set by @PrePersist

        AsyncTaskRecord savedRecord;
        try {
            // --- Transaction Start ---
            savedRecord = taskRepository.save(record);
             // If save is successful, submit for execution *within the same transaction*
             // This ensures that if submission fails, the DB record is rolled back.
             // However, if the *execution* later fails, the record remains.
            submitToExecutor(savedRecord.getId());
            // --- Transaction Commit ---
            log.info("Task record {} persisted and submitted for initial execution.", savedRecord.getId());

        } catch (Exception e) {
            // --- Transaction Rollback --- (should happen automatically with @Transactional on exception)
            log.error("Failed to persist or submit task type '{}': {}", taskType, e.getMessage(), e);
            // Rethrow a runtime exception to indicate critical failure and ensure rollback
            throw new RuntimeException("Failed to submit task: " + e.getMessage(), e);
        }

        return savedRecord; // Return the persisted record
    }

    /**
     * Internal method to submit the actual execution logic to the ExecutorService.
     * @param recordId The ID of the persisted AsyncTaskRecord.
     */
    private void submitToExecutor(Long recordId) {
         log.debug("Submitting task ID {} to executor pool.", recordId);
        TaskExecutionWrapper wrapper = new TaskExecutionWrapper(recordId, taskRepository, taskFactory, defaultRetryStrategy);
        try {
            taskExecutor.submit(wrapper);
        } catch (RejectedExecutionException e) {
             log.error("Executor pool rejected task ID {}. The task will rely on the RetryScheduler.", recordId, e);
             // Optional: Update status to PENDING_RETRY immediately? Or let scheduler pick it up based on PENDING status?
             // Current design: Let it remain PENDING. If the app restarts, it stays PENDING.
             // If RetryScheduler is running, it might not pick up PENDING tasks.
             // A safer approach might be to update status to PENDING_RETRY with nextRetryTime = now() here.
             // For now, we rely on the transaction failing if the submit fails critically.
             // If submit succeeds but execution fails later, TaskExecutionWrapper handles it.
             // If RejectedExecutionException occurs, it means the pool is full or shut down.
             throw e; // Re-throw to potentially trigger transaction rollback in submitTask
        }
    }

    @Override
    @PreDestroy // Called on application shutdown (if managed by a framework like Spring)
    public void shutdown(long timeoutSeconds) {
        // Shutdown RetryScheduler's executor first (or concurrently)
        shutdownExecutor("RetryScheduler Executor", schedulerExecutor, timeoutSeconds);

        // Shutdown main task executor
        shutdownExecutor("Task Executor", taskExecutor, timeoutSeconds);
    }

    /** Helper method to shutdown an executor service */
    private void shutdownExecutor(String name, ExecutorService executor, long timeoutSeconds) {
        log.info("Shutting down {}...", name);
        executor.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("{} did not terminate in {} seconds.", name, timeoutSeconds);
                List<Runnable> droppedTasks = executor.shutdownNow(); // Cancel currently executing tasks
                log.warn("Forcefully shutting down {}. {} tasks were dropped.", name, droppedTasks.size());
                // Re-check termination status
                if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    log.error("{} did not terminate even after forceful shutdown.", name);
                }
            } else {
                log.info("{} terminated gracefully.", name);
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            log.warn("{} shutdown interrupted. Forcing shutdown now.", name);
            executor.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    // PostConstruct could be used to log readiness or perform checks, but not strictly required here.
    @PostConstruct

    public void start() {
       log.info("AsyncExecutionService started and ready to accept tasks.");
       // Basic check
       if (taskExecutor.isShutdown() || taskExecutor.isTerminated()) {
           log.error("FATAL: Task executor is not operational on startup!");
           // Depending on the application, might want to throw an exception here
           // to prevent the application from starting in a bad state.
       }
       if (schedulerExecutor.isShutdown() || schedulerExecutor.isTerminated()) {
           log.error("FATAL: Scheduler executor is not operational on startup!");
           // Depending on the application, might want to throw an exception here
       }
    }
}