package com.sailfish.asyn.factory;

import com.sailfish.asyn.AsyncTask;
import java.io.Serializable;
import java.util.Optional;

/**
 * Factory responsible for providing the correct AsyncTask implementation
 * based on a given task type identifier.
 *
 * Implementations could use a Map, service discovery, or other mechanisms.
 */
public interface AsyncTaskFactory {

    /**
     * Retrieves the appropriate AsyncTask implementation for the given type.
     *
     * @param taskType The identifier string for the task type (e.g., "emailNotification", "reportGeneration").
     * @param <T> The expected payload type (caller must ensure consistency).
     * @return An Optional containing the AsyncTask instance if found, empty otherwise.
     */
    <T extends Serializable> Optional<AsyncTask<T>> getTask(String taskType);

}