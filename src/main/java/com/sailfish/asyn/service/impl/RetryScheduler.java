package com.sailfish.asyn.service.impl;

import com.sailfish.asyn.factory.AsyncTaskFactory;
import com.sailfish.asyn.model.AsyncTaskRecord;
import com.sailfish.asyn.repository.AsyncTaskRepository;
import com.sailfish.asyn.retry.RetryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Service responsible for periodically querying for tasks pending retry
 * and submitting them back to the main execution pool.
 *
 * Assumes dependency injection for repository, factory, strategy, and executors.
 */
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final AsyncTaskRepository taskRepository;
    private final AsyncTaskFactory taskFactory;
    private final RetryStrategy retryStrategy; // Needed for TaskExecutionWrapper
    private final ExecutorService taskExecutor; // Main pool to submit tasks TO
    private final ScheduledExecutorService schedulerExecutor; // Pool for running the scheduler task ITSELF
    private final Duration checkInterval; // How often to check for retries
    private final int batchSize;          // How many tasks to fetch per check

    private ScheduledFuture<?> scheduledTask;

    // Default configuration values
    private static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(60);
    private static final int DEFAULT_BATCH_SIZE = 50;

    public RetryScheduler(AsyncTaskRepository taskRepository,
                          AsyncTaskFactory taskFactory,
                          RetryStrategy retryStrategy,
                          ExecutorService taskExecutor,
                          ScheduledExecutorService schedulerExecutor) {
        this(taskRepository, taskFactory, retryStrategy, taskExecutor, schedulerExecutor, DEFAULT_CHECK_INTERVAL, DEFAULT_BATCH_SIZE);
    }

    public RetryScheduler(AsyncTaskRepository taskRepository,
                          AsyncTaskFactory taskFactory,
                          RetryStrategy retryStrategy,
                          ExecutorService taskExecutor,
                          ScheduledExecutorService schedulerExecutor,
                          Duration checkInterval,
                          int batchSize) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository cannot be null");
        this.taskFactory = Objects.requireNonNull(taskFactory, "taskFactory cannot be null");
        this.retryStrategy = Objects.requireNonNull(retryStrategy, "retryStrategy cannot be null");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor cannot be null");
        this.schedulerExecutor = Objects.requireNonNull(schedulerExecutor, "schedulerExecutor cannot be null");
        this.checkInterval = Objects.requireNonNull(checkInterval, "checkInterval cannot be null");
        if (checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("checkInterval must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        log.info("RetryScheduler initialized with checkInterval={} and batchSize={}", checkInterval, batchSize);
    }

    @PostConstruct
    public void start() {
        log.info("Starting RetryScheduler...");
        if (schedulerExecutor.isShutdown() || schedulerExecutor.isTerminated()) {
             log.error("Cannot start RetryScheduler: schedulerExecutor is shut down or terminated.");
             return;
        }
         if (taskExecutor.isShutdown() || taskExecutor.isTerminated()) {
             log.error("Cannot start RetryScheduler: taskExecutor is shut down or terminated.");
             return;
         }

        // Schedule the task to run periodically.
        // scheduleAtFixedRate ensures that the task runs every `checkInterval`, regardless of how long the task takes.
        // If the task takes longer than the interval, the next run will start immediately after the current one finishes.
        // Consider scheduleWithFixedDelay if you want a fixed delay *between* the end of one run and the start of the next.
        scheduledTask = schedulerExecutor.scheduleAtFixedRate(
                this::runRetryCycle,
                checkInterval.toMillis(), // Initial delay (start after one interval)
                checkInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
        log.info("RetryScheduler started. Scheduling task to run every {}", checkInterval);
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping RetryScheduler...");
        if (scheduledTask != null && !scheduledTask.isDone()) {
            scheduledTask.cancel(false); // Allow running task to complete, but don't start new ones
        }
        // Note: We typically don't shut down the schedulerExecutor here if it's shared
        // or managed externally (e.g., by Spring). The AsyncExecutionService should shut it down.
        // If this scheduler owns the executor, shut it down here.
        log.info("RetryScheduler stopped.");
    }

    private void runRetryCycle() {
        log.debug("Running retry cycle...");
        try {
            LocalDateTime now = LocalDateTime.now();
            List<AsyncTaskRecord> tasksToRetry = taskRepository.findTasksPendingRetry(now, batchSize);

            if (tasksToRetry.isEmpty()) {
                log.debug("No tasks found pending retry at this time.");
                return;
            }

            log.info("Found {} tasks pending retry. Submitting them for execution.", tasksToRetry.size());

            for (AsyncTaskRecord record : tasksToRetry) {
                try {
                    // Create the wrapper that contains the actual execution logic
                    TaskExecutionWrapper wrapper = new TaskExecutionWrapper(
                            record.getId(),
                            taskRepository,
                            taskFactory,
                            retryStrategy // Pass the strategy for the wrapper to use
                    );
                    // Submit to the main task execution pool
                    taskExecutor.submit(wrapper);
                    log.debug("Submitted task ID {} for retry execution.", record.getId());
                } catch (RejectedExecutionException e) {
                    log.error("Task executor rejected retry submission for task ID {}. Pool might be full or shutting down.", record.getId(), e);
                    // The task remains PENDING_RETRY and should be picked up in the next cycle.
                } catch (Exception e) {
                    // Catch unexpected errors during submission loop for a single task
                    log.error("Unexpected error submitting task ID {} for retry: {}", record.getId(), e.getMessage(), e);
                    // The task remains PENDING_RETRY. Consider if specific errors warrant changing state?
                }
            }
             log.debug("Finished submitting tasks for retry cycle.");

        } catch (Exception e) {
            // Catch errors related to the database query or the overall cycle
            log.error("Error during retry cycle execution: {}", e.getMessage(), e);
            // Prevent scheduler task from dying due to unexpected exceptions
        }
    }

    // Getters for configuration if needed
    public Duration getCheckInterval() { return checkInterval; }
    public int getBatchSize() { return batchSize; }
}