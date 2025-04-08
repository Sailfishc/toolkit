package com.sailfish.asyn.repository;

import com.sailfish.asyn.model.AsyncTaskRecord;
import com.sailfish.asyn.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional; // Or org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA implementation of the AsyncTaskRepository.
 * Assumes a JPA environment is configured (e.g., via Spring Boot Data JPA or manual setup).
 */
// If using Spring, annotate with @Repository
public class JpaAsyncTaskRepository implements AsyncTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaAsyncTaskRepository.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional // Ensure save is transactional
    public AsyncTaskRecord save(AsyncTaskRecord record) {
        if (record.getId() == null) {
            entityManager.persist(record);
            log.debug("Persisted new AsyncTaskRecord with ID: {}", record.getId());
            return record;
        } else {
            AsyncTaskRecord merged = entityManager.merge(record);
            log.debug("Merged existing AsyncTaskRecord with ID: {}", merged.getId());
            return merged;
        }
    }

    @Override
    public Optional<AsyncTaskRecord> findById(Long id) {
        // Find by ID without lock first for read-only purposes generally
        AsyncTaskRecord record = entityManager.find(AsyncTaskRecord.class, id);
        return Optional.ofNullable(record);
        // If pessimistic locking is needed before processing:
        // return Optional.ofNullable(entityManager.find(AsyncTaskRecord.class, id, LockModeType.PESSIMISTIC_WRITE));
    }

    @Override
    public List<AsyncTaskRecord> findTasksPendingRetry(LocalDateTime now, int limit) {
        // This query benefits greatly from the index on (status, nextRetryTime)
        String jpql = "SELECT r FROM AsyncTaskRecord r " +
                      "WHERE r.status = :status AND r.nextRetryTime <= :now " +
                      "ORDER BY r.nextRetryTime ASC"; // Process oldest retry times first

        TypedQuery<AsyncTaskRecord> query = entityManager.createQuery(jpql, AsyncTaskRecord.class);
        query.setParameter("status", TaskStatus.PENDING_RETRY);
        query.setParameter("now", now);
        query.setMaxResults(limit);

        // Consider using PESSIMISTIC_WRITE lock if multiple schedulers might run concurrently
        // query.setLockMode(LockModeType.PESSIMISTIC_WRITE); // Be cautious with locking performance

        return query.getResultList();
    }

    @Override
    @Transactional // Updates should be transactional
    public boolean updateStatus(Long id, TaskStatus status, String lastError) {
        String jpql = "UPDATE AsyncTaskRecord r SET r.status = :status, r.lastError = :lastError, r.updatedAt = :updatedAt " +
                      "WHERE r.id = :id AND r.status <> :completedStatus AND r.status <> :failedStatus"; // Avoid updating terminal states accidentally? Or handle in caller

        int updatedCount = entityManager.createQuery(jpql)
                .setParameter("status", status)
                .setParameter("lastError", truncateError(lastError)) // Ensure error fits in column
                .setParameter("updatedAt", LocalDateTime.now())
                .setParameter("id", id)
                .setParameter("completedStatus", TaskStatus.COMPLETED) // Optional: prevent overwriting terminal state
                .setParameter("failedStatus", TaskStatus.FAILED)       // Optional: prevent overwriting terminal state
                .executeUpdate();

        if (updatedCount > 0) {
            log.debug("Updated status for task ID {} to {}. Success: {}", id, status, updatedCount > 0);
            return true;
        } else {
             // Could fail if ID doesn't exist OR if status was already COMPLETED/FAILED (if guards are used)
             // Check if record exists to differentiate
             AsyncTaskRecord record = entityManager.find(AsyncTaskRecord.class, id);
             if (record == null) {
                 log.warn("Attempted to update status for non-existent task ID {}", id);
             } else {
                 log.warn("Failed to update status for task ID {} to {} (maybe already in terminal state {} or modified concurrently?)", id, status, record.getStatus());
             }
            return false;
        }
    }


    @Override
    @Transactional // Updates should be transactional
    public boolean scheduleForRetry(Long id, int newRetryCount, LocalDateTime nextRetryTime, String lastError) {
        String jpql = "UPDATE AsyncTaskRecord r SET " +
                      "r.status = :newStatus, " +
                      "r.retryCount = :retryCount, " +
                      "r.nextRetryTime = :nextRetryTime, " +
                      "r.lastError = :lastError, " +
                      "r.updatedAt = :updatedAt " +
                      "WHERE r.id = :id AND r.status = :processingStatus"; // Ensure we are retrying a task that was PROCESSING

        int updatedCount = entityManager.createQuery(jpql)
                .setParameter("newStatus", TaskStatus.PENDING_RETRY)
                .setParameter("retryCount", newRetryCount)
                .setParameter("nextRetryTime", nextRetryTime)
                .setParameter("lastError", truncateError(lastError)) // Ensure error fits in column
                .setParameter("updatedAt", LocalDateTime.now())
                .setParameter("id", id)
                .setParameter("processingStatus", TaskStatus.PROCESSING) // Only schedule if it was PROCESSING
                .executeUpdate();

        if (updatedCount > 0) {
             log.debug("Scheduled task ID {} for retry {} at {}. Success: {}", id, newRetryCount, nextRetryTime, updatedCount > 0);
             return true;
        } else {
            // Could fail if ID doesn't exist or status wasn't PROCESSING
            AsyncTaskRecord record = entityManager.find(AsyncTaskRecord.class, id);
            if (record == null) {
                log.warn("Attempted to schedule retry for non-existent task ID {}", id);
            } else {
                log.warn("Failed to schedule retry for task ID {} (status was {}, expected {}). Maybe processed concurrently?", id, record.getStatus(), TaskStatus.PROCESSING);
            }
            return false;
        }
    }

    private String truncateError(String error) {
        if (error == null) return null;
        int maxLength = 2000; // Match the DB column length approx
        if (error.length() > maxLength) {
            return error.substring(0, maxLength - 3) + "...";
        }
        return error;
    }
}