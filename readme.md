# Asynchronous Execution Component

This component provides a framework for executing tasks asynchronously with reliability guarantees through database persistence and automated retries.

## Core Concepts

1.  **Task Definition (`AsyncTask`)**: An interface representing the work to be done. Implementations contain specific business logic.
2.  **Persistence (`AsyncTaskRecord`, `AsyncTaskRepository`)**: Tasks submitted are first saved to a database table (`async_task_records`) before execution is attempted. This ensures tasks are not lost if the application restarts. The `AsyncTaskRecord` entity stores the task payload, status, retry count, etc. The `AsyncTaskRepository` provides methods to interact with the database.
3.  **Immediate Execution**: After successful persistence, the task is submitted to a thread pool (`ExecutorService`) for immediate execution.
4.  **Status Tracking**: The task's state (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `PENDING_RETRY`) is tracked in the database record.
5.  **Retry Mechanism (`RetryStrategy`, `RetryScheduler`)**:
    * If a task fails execution, the `RetryStrategy` determines if it should be retried and calculates the next attempt time (e.g., using exponential backoff).
    * The task record's status is updated to `PENDING_RETRY` with the calculated `nextRetryTime`.
    * A separate `RetryScheduler` (using a `ScheduledExecutorService`) periodically queries the database for tasks due for retry and resubmits them to the main execution thread pool.
6.  **Task Identification**: A `taskType` string is stored in the `AsyncTaskRecord`. This string is used by the system (likely via a factory or map) to locate and instantiate the correct `AsyncTask` implementation when executing or retrying a task.
7.  **Payload**: The data required for task execution is serialized and stored in the `payload` field of the `AsyncTaskRecord`.

## Default Retry Strategy

* **Type**: Exponential Backoff with Jitter
* **Max Retries**: 15
* **Initial Delay**: 5 seconds
* **Multiplier**: 2.0 (delay doubles each time)
* **Max Delay Cap**: 1 hour
* **Jitter**: Enabled (adds +/- 10% randomness to delay)

## Key Components (Interfaces/Classes)

* `com.sailfish.asyn.AsyncTask<T>`: Interface for tasks.
* `com.sailfish.asyn.model.AsyncTaskRecord`: JPA Entity for database storage.
* `com.sailfish.asyn.model.TaskStatus`: Enum for task states.
* `com.sailfish.asyn.repository.AsyncTaskRepository`: Interface for DB operations.
* `com.sailfish.asyn.retry.RetryStrategy`: Interface for retry logic.
* `com.sailfish.asyn.retry.ExponentialBackoffRetryStrategy`: Default retry implementation.
* `com.sailfish.asyn.service.AsyncExecutionService`: Main service facade (To be implemented).
* `com.sailfish.asyn.service.TaskExecutorWrapper`: Internal runnable/callable handling execution and state updates (To be implemented).
* `com.sailfish.asyn.service.RetryScheduler`: Service responsible for scheduling retries (To be implemented).
* `com.sailfish.asyn.factory.AsyncTaskFactory`: Interface/Mechanism to get `AsyncTask` instances based on `taskType` (To be implemented).

## Dependencies (Conceptual)

* Java 8+
* JPA Provider (e.g., Hibernate)
* Database Driver (e.g., PostgreSQL, MySQL)
* Logging Framework (e.g., SLF4j + Logback/Log4j2)
* (Optional but Recommended) Dependency Injection Framework (e.g., Spring Boot)
* (Optional) JSON Library if using JSON for payload (e.g., Jackson, Gson)

## Next Steps (Implementation Plan)

1.  Implement `AsyncTaskRepository` (e.g., using Spring Data JPA or direct JPA/JDBC).
2.  Implement `AsyncExecutionService` to handle incoming requests, persistence, and submission to `ExecutorService`.
3.  Implement `TaskExecutorWrapper` (likely a `Runnable` or `Callable`) to encapsulate task execution, state updates, error handling, and interaction with `RetryStrategy` and `AsyncTaskRepository`.
4.  Implement `RetryScheduler` using `ScheduledExecutorService` to query for and resubmit retryable tasks.
5.  Implement a mechanism (`AsyncTaskFactory`) to map `taskType` strings to `AsyncTask` instances.
6.  Configure `ExecutorService` and `ScheduledExecutorService` (thread pool sizes, etc.).
7.  Set up database schema (using JPA DDL generation or manual scripts).
8.  Integrate logging.
9.  Add transaction management (crucial for consistency between DB state and execution attempts).

好的，我们来一步步设计和规划这个异步执行组件。

这个组件的核心目标是提供一个可靠的、支持自动重试的异步任务执行框架。

**第一步：定义核心接口和数据模型 (Defining Core Interfaces and Data Models)**

我们需要定义任务本身、任务的持久化表示以及任务的状态。

1.  **`AsyncTask<T>` 接口**:
    * **目的**: 代表一个可异步执行的任务单元。具体的业务逻辑将由这个接口的实现类来完成。
    * **位置**: `com.sailfish.asyn.AsyncTask` (已提供)
    * **关键方法**: `void execute(T payload) throws Exception;`
    * **泛型 `<T>`**: 代表任务执行所需的数据（载荷），必须是 `Serializable` 以便存储到数据库。

2.  **`TaskStatus` 枚举**:
    * **目的**: 定义任务在生命周期中可能存在的状态。
    * **位置**: `com.sailfish.asyn.model.TaskStatus` (已提供)
    * **枚举值**:
        * `PENDING`: 任务已提交，等待首次执行。
        * `PROCESSING`: 任务正在执行中。
        * `COMPLETED`: 任务成功完成。
        * `FAILED`: 任务执行失败，且已达到重试次数上限或不允许重试。
        * `PENDING_RETRY`: 任务执行失败，等待下一次重试。

3.  **`AsyncTaskRecord` 实体类**:
    * **目的**: 代表存储在数据库中的异步任务记录。这是实现可靠性的关键，即使应用重启，任务也不会丢失。
    * **位置**: `com.sailfish.asyn.model.AsyncTaskRecord` (已提供)
    * **关键字段**:
        * `id`: 主键。
        * `taskType`: 字符串，用于标识任务类型，以便找到对应的 `AsyncTask` 实现。
        * `payload`: `Serializable` 类型，存储任务执行所需的数据 (使用 `@Lob` 存储可能较大的数据)。
        * `status`: `TaskStatus` 枚举，当前任务状态。
        * `retryCount`: `int`，已重试次数。
        * `nextRetryTime`: `LocalDateTime`，下次重试的计划时间（可为空）。
        * `lastError`: `String`，记录最后一次执行失败的错误信息。
        * `createdAt`, `updatedAt`: `LocalDateTime`，记录创建和更新时间 (通过 `@PrePersist`, `@PreUpdate` 自动维护)。
    * **数据库索引**: 在 `(status, nextRetryTime)` 上建立索引，对于后续的重试调度查询至关重要。

**第二步：定义数据访问和任务查找机制 (Defining Data Access and Task Lookup)**

1.  **`AsyncTaskRepository` 接口**:
    * **目的**: 定义与数据库交互的方法，用于管理 `AsyncTaskRecord` 实体。具体的实现（如使用 Spring Data JPA 或原生 JPA/JDBC）将在别处提供。
    * **位置**: `com.sailfish.asyn.repository.AsyncTaskRepository` (已提供)
    * **关键方法**:
        * `save(AsyncTaskRecord record)`: 保存或更新任务记录。
        * `findById(Long id)`: 根据 ID 查找任务记录。
        * `findTasksPendingRetry(LocalDateTime now, int limit)`: 查找状态为 `PENDING_RETRY` 且 `nextRetryTime` 已到期的任务，用于重试调度。
        * `updateStatus(Long id, TaskStatus status, String lastError)`: 更新任务状态（实现时可能优化，避免加载整个实体）。
        * `scheduleForRetry(Long id, int newRetryCount, LocalDateTime nextRetryTime, String lastError)`: 更新记录以准备下一次重试（更新重试次数、下次重试时间、错误信息，并将状态设置为 `PENDING_RETRY`）。

2.  **`AsyncTaskFactory` 接口**:
    * **目的**: 根据 `AsyncTaskRecord` 中的 `taskType` 字符串，获取对应的 `AsyncTask` 实例。这是将持久化的任务记录与其执行逻辑关联起来的桥梁。
    * **位置**: `com.sailfish.asyn.factory.AsyncTaskFactory` (已提供)
    * **关键方法**: `<T extends Serializable> Optional<AsyncTask<T>> getTask(String taskType);`
    * **实现方式**: 可以通过 Map、Spring Bean 查找、服务发现 (ServiceLoader) 等方式实现。

**第三步：定义重试策略 (Defining Retry Strategy)**

1.  **`RetryStrategy` 接口**:
    * **目的**: 定义决定任务失败后是否重试以及何时重试的逻辑。
    * **位置**: `com.sailfish.asyn.retry.RetryStrategy` (已提供)
    * **关键方法**:
        * `boolean shouldRetry(AsyncTaskRecord record)`: 判断当前任务是否应该重试（例如，检查 `retryCount` 是否小于最大限制）。
        * `Optional<LocalDateTime> calculateNextRetryTime(AsyncTaskRecord record)`: 计算下一次重试的时间。如果 `shouldRetry` 返回 `false`，则返回 `Optional.empty()`。

2.  **`ExponentialBackoffRetryStrategy` 类**:
    * **目的**: 提供一个具体的、默认的重试策略实现，采用指数退避算法并增加了随机抖动（Jitter）以避免“惊群效应”。
    * **位置**: `com.sailfish.asyn.retry.ExponentialBackoffRetryStrategy` (已提供)
    * **核心逻辑**:
        * 根据 `retryCount` 计算延迟：`initialDelay * (multiplier ^ retryCount)`。
        * 应用 `maxDelay` 上限。
        * 如果 `addJitter` 为 true，则在计算出的延迟上增加一个小的随机变化量（例如 +/- 10%）。
    * **默认配置**:
        * `maxRetries`: 15
        * `initialDelay`: 5 秒
        * `multiplier`: 2.0
        * `maxDelay`: 1 小时
        * `addJitter`: true

**第四步：设计核心服务和执行逻辑 (Designing Core Service and Execution Logic)**

1.  **`AsyncExecutionService` 接口**:
    * **目的**: 作为客户端提交任务的入口。
    * **位置**: `com.sailfish.asyn.service.AsyncExecutionService` (已提供)
    * **关键方法**:
        * `<T extends Serializable> AsyncTaskRecord submitTask(String taskType, T payload)`: 接收任务类型和载荷，持久化任务记录，并触发首次执行尝试。需要处理参数验证和持久化/提交失败的情况。
        * `void shutdown(long timeoutSeconds)`: 用于在应用程序关闭时优雅地停止内部的线程池。

2.  **`AsyncExecutionServiceImpl` 类**:
    * **目的**: `AsyncExecutionService` 的默认实现。协调 `AsyncTaskRepository`, `AsyncTaskFactory`, `RetryStrategy`, 和 `ExecutorService`。
    * **位置**: `com.sailfish.asyn.service.impl.AsyncExecutionServiceImpl` (已提供)
    * **依赖**: `AsyncTaskRepository`, `AsyncTaskFactory`, `ExecutorService` (用于执行任务), `RetryStrategy` (用于执行时的失败处理)。通过构造函数注入。
    * **`submitTask` 实现**:
        * 参数校验 (`taskType`, `payload`不能为空)。
        * 创建 `AsyncTaskRecord` 实例，设置 `taskType`, `payload`, 初始状态为 `PENDING`。
        * **在一个事务中**:
            * 调用 `taskRepository.save(record)` 持久化记录。
            * 调用内部方法 `submitToExecutor(savedRecord.getId())` 将任务 ID 提交给 `ExecutorService`。
        * **事务保证**: 如果保存成功但提交到线程池失败 (`RejectedExecutionException`)，事务应回滚，任务记录不会被持久化。如果事务成功提交，则记录已保存，任务已在队列中等待执行。
        * 返回持久化后的 `AsyncTaskRecord`。
    * **`submitToExecutor` 实现**:
        * 创建一个 `TaskExecutionWrapper` 实例（传入 `recordId` 和所需依赖）。
        * 调用 `taskExecutor.submit(wrapper)`。
        * 处理 `RejectedExecutionException` (例如，日志记录，事务将因此失败回滚)。
    * **`shutdown` 实现**:
        * 调用 `taskExecutor.shutdown()`，然后 `taskExecutor.awaitTermination()`，如果超时则 `taskExecutor.shutdownNow()`。实现优雅关闭。
    * **`@PostConstruct` / `@PreDestroy`**: 用于启动日志记录/检查和执行关闭逻辑。

3.  **`TaskExecutionWrapper` 类**:
    * **目的**: 这是一个 `Runnable` 实现，封装了单个任务的实际执行、状态更新和错误处理逻辑。它将在 `ExecutorService` 的线程中运行。
    * **位置**: `com.sailfish.asyn.service.impl.TaskExecutionWrapper` (已提供)
    * **依赖**: `recordId`, `AsyncTaskRepository`, `AsyncTaskFactory`, `RetryStrategy`。
    * **`run` 方法核心流程**:
        * **获取记录**: `taskRepository.findById(recordId)`。如果找不到，记录警告并退出。
        * **状态检查**: 如果状态是 `COMPLETED` 或 `FAILED`，记录警告并退出（避免重复处理）。
        * **标记处理中**: **尝试原子更新**状态为 `PROCESSING` (`taskRepository.updateStatus(recordId, TaskStatus.PROCESSING, null)`)。如果更新失败（可能已被其他线程/节点处理），记录警告并退出。
        * **查找任务实现**: `taskFactory.getTask(record.getTaskType())`。如果找不到，抛出 `IllegalStateException` (这将进入下面的 catch块)。
        * **执行任务**: `task.execute(record.getPayload())`。
        * **成功**: 更新状态为 `COMPLETED` (`taskRepository.updateStatus(recordId, TaskStatus.COMPLETED, null)`)。
        * **失败 (catch Exception)**:
            * 记录错误日志，获取堆栈信息。
            * 调用 `retryStrategy.shouldRetry(record)`。
            * **如果需要重试**:
                * 调用 `retryStrategy.calculateNextRetryTime(record)` 获取下次重试时间。
                * 调用 `taskRepository.scheduleForRetry(...)` 更新记录（设置状态为 `PENDING_RETRY`，更新重试次数、下次时间和错误信息）。
            * **如果不需要重试**:
                * 更新状态为 `FAILED` (`taskRepository.updateStatus(recordId, TaskStatus.FAILED, errorDetails)`)。

**第五步：设计重试调度器 (Designing the Retry Scheduler)**

这一部分在提供的代码中没有具体实现，但在 `readme.md` 中提到了。

1.  **`RetryScheduler` (概念性)**:
    * **目的**: 定期检查数据库中是否有到期需要重试的任务，并将它们重新提交给执行器。
    * **实现方式**: 通常使用 `java.util.concurrent.ScheduledExecutorService`。
    * **调度逻辑**:
        * 例如，每分钟执行一次。
        * 调用 `taskRepository.findTasksPendingRetry(LocalDateTime.now(), batchSize)` 获取一批需要重试的任务记录。
        * 对每个获取到的 `record`:
            * 创建一个 `TaskExecutionWrapper`。
            * 调用 `taskExecutor.submit(wrapper)` 将其提交到主执行线程池 (`AsyncExecutionServiceImpl` 中的 `taskExecutor`)。
        * 需要处理在调度任务本身中可能发生的异常（例如数据库连接问题），确保调度器不会因为单次查询失败而停止工作。
    * **启动和关闭**: 需要在应用程序启动时启动 `ScheduledExecutorService`，并在应用程序关闭时（与主 `taskExecutor` 一起）优雅地关闭它。

**第六步：整合与配置 (Integration and Configuration)**

1.  **依赖注入**: 使用 Spring 或其他 DI 框架来管理 `AsyncTaskRepository` 的实现、`AsyncTaskFactory` 的实现、`RetryStrategy`、`ExecutorService`、`ScheduledExecutorService` 等组件的生命周期和依赖关系。
2.  **线程池配置**: `ExecutorService` (用于任务执行) 和 `ScheduledExecutorService` (用于重试调度) 的线程池大小、队列类型等需要根据预期负载进行配置。
3.  **数据库配置**: 配置 JPA/JDBC 数据源。
4.  **事务管理**: 确保在关键操作（如 `submitTask`）上有正确的事务边界。Spring 的 `@Transactional` 是一个常用的选择。
5.  **`AsyncTask` 实现和注册**: 开发具体的 `AsyncTask` 实现类，并通过 `AsyncTaskFactory` 的实现将它们注册（例如，如果使用 Spring，可以将它们声明为 Bean，然后 `AsyncTaskFactory` 可以通过 `ApplicationContext` 按名称或类型查找）。

**总结**

这个设计提供了一个功能相对完善的异步任务执行框架：

* **可靠性**: 通过数据库持久化 `AsyncTaskRecord`，任务不会因应用崩溃而丢失。
* **立即执行**: 任务提交后会立即尝试执行。
* **自动重试**: 内置了可配置的重试机制，默认采用指数退避+抖动策略。
* **状态跟踪**: 清晰地跟踪任务的生命周期状态。
* **解耦**: 任务定义 (`AsyncTask`)、持久化 (`AsyncTaskRecord`/`Repository`)、执行逻辑 (`TaskExecutionWrapper`)、重试策略 (`RetryStrategy`) 和调度 (`RetryScheduler`) 各司其职。
* **可扩展**: 可以方便地添加新的 `AsyncTask` 实现，并通过 `AsyncTaskFactory` 注册；也可以实现不同的 `RetryStrategy`。

提供的代码已经实现了大部分核心组件，只需要再实现 `AsyncTaskRepository`、`AsyncTaskFactory` 和 `RetryScheduler`，并进行适当的配置即可运行。