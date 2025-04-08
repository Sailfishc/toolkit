package com.sailfish.asyn;

import java.io.Serializable;

/**
 * Represents a task that can be executed asynchronously.
 * Implementations should contain the actual business logic.
 *
 * @param <T> The type of the payload data associated with the task.
 */
@FunctionalInterface
public interface AsyncTask<T extends Serializable> {

    /**
     * Executes the task logic.
     *
     * @param payload The data required to execute the task.
     * @throws Exception if the task execution fails.
     */
    void execute(T payload) throws Exception;
}