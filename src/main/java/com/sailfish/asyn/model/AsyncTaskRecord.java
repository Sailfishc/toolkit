package com.sailfish.asyn.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents the persistent state of an asynchronous task in the database.
 */
@Entity
@Table(name = "async_task_records", indexes = {
    @Index(name = "idx_status_nextretry", columnList = "status, nextRetryTime")
})
public class AsyncTaskRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Or GenerationType.SEQUENCE depending on DB
    private Long id;

    @Column(nullable = false, length = 100)
    private String taskType; // Identifier for the type of task (used to find the correct AsyncTask implementation)

    @Lob // Use Lob for potentially large payloads
    @Column(nullable = false)
    private Serializable payload; // The actual data needed for the task execution (could be serialized JSON, etc.)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = true) // Can be null if retries are not configured or task succeeded/failed permanently
    private LocalDateTime nextRetryTime;

    @Column(nullable = true, length = 2000) // Store error details on failure
    private String lastError;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // --- Standard Getters and Setters ---

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
        if (status == null) {
            status = TaskStatus.PENDING; // Default status on creation
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public Serializable getPayload() {
        return payload;
    }

    public void setPayload(Serializable payload) {
        this.payload = payload;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getNextRetryTime() {
        return nextRetryTime;
    }

    public void setNextRetryTime(LocalDateTime nextRetryTime) {
        this.nextRetryTime = nextRetryTime;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // --- equals, hashCode, toString ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AsyncTaskRecord that = (AsyncTaskRecord) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "AsyncTaskRecord{" +
               "id=" + id +
               ", taskType='" + taskType + '\'' +
               ", status=" + status +
               ", retryCount=" + retryCount +
               ", nextRetryTime=" + nextRetryTime +
               ", createdAt=" + createdAt +
               ", updatedAt=" + updatedAt +
               '}';
    }
}