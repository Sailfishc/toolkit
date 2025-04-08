package com.sailfish.asyn.factory;

import com.sailfish.asyn.AsyncTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple implementation of {@link AsyncTaskFactory} using a Map.
 * Tasks should be registered during application startup.
 *
 * If using Spring, this could be a bean, and tasks could be injected or found via ApplicationContext.
 */
public class MapAsyncTaskFactory implements AsyncTaskFactory {

    private static final Logger log = LoggerFactory.getLogger(MapAsyncTaskFactory.class);

    // Using ConcurrentHashMap for thread safety if tasks are registered after startup (though usually not recommended)
    private final Map<String, AsyncTask<? extends Serializable>> taskRegistry = new ConcurrentHashMap<>();

    /**
     * Registers an AsyncTask implementation with its corresponding type identifier.
     * Should typically be called during application initialization.
     *
     * @param taskType The unique identifier for the task type.
     * @param task     The AsyncTask instance.
     * @param <T>      The payload type of the task.
     */
    public <T extends Serializable> void registerTask(String taskType, AsyncTask<T> task) {
        if (taskType == null || taskType.trim().isEmpty()) {
            throw new IllegalArgumentException("taskType cannot be blank");
        }
        if (task == null) {
            throw new IllegalArgumentException("task cannot be null");
        }
        log.info("Registering AsyncTask for type '{}': {}", taskType, task.getClass().getName());
        taskRegistry.put(taskType, task);
    }

    @Override
    @SuppressWarnings("unchecked") // Necessary cast, caller ensures consistency via taskType mapping
    public <T extends Serializable> Optional<AsyncTask<T>> getTask(String taskType) {
        if (taskType == null) {
            return Optional.empty();
        }
        AsyncTask<? extends Serializable> task = taskRegistry.get(taskType);
        if (task == null) {
            log.warn("No AsyncTask found for task type: {}", taskType);
            return Optional.empty();
        }
        try {
            // Cast to the expected type. This relies on the registration being correct.
            return Optional.of((AsyncTask<T>) task);
        } catch (ClassCastException e) {
            // This indicates a programming error: the registered task doesn't match the expected type T.
            log.error("Type mismatch for task type '{}'. Registered task type does not match expected type.", taskType, e);
            return Optional.empty();
        }
    }
}