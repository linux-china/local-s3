package com.robothy.s3.core.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers the {@linkplain S3Change}s of the services of a LocalS3 service to its {@linkplain S3ChangeListener}s,
 * however an operation was called: through an HTTP request, or directly on a service.
 *
 * <p>A change is only delivered once it is committed. The {@linkplain com.robothy.s3.core.service.BucketGuard} runs
 * every operation that changes a bucket {@linkplain #withinChange within a change}, and the changes that the
 * operation {@linkplain #publish publishes} are held back until the outermost change of the thread has ended: those of
 * a change that succeeded, i.e. was persisted, are then delivered, and those of a change that failed are dropped. So a
 * listener never hears of a change that didn't happen, and runs once the locks of the buckets are released, which lets
 * it call the services again.
 *
 * <p>The listeners run on the {@linkplain #executor(Executor) executor}, which delivers on the thread that made the
 * change by default.
 */
@Slf4j
public final class S3ChangePublisher {

  private final List<S3ChangeListener> listeners = new CopyOnWriteArrayList<>();

  /**
   * Runs the listeners; delivers on the thread that made the change by default.
   */
  private volatile Executor executor = Runnable::run;

  /**
   * The changes of the thread that are held back, while it runs a change.
   */
  private final ThreadLocal<Pending> pending = new ThreadLocal<>();

  /**
   * The outermost operation that the thread runs {@linkplain #asOperation as}, which the changes it publishes name.
   */
  private final ThreadLocal<String> operation = new ThreadLocal<>();

  public void addListener(S3ChangeListener listener) {
    listeners.add(Objects.requireNonNull(listener));
  }

  public void removeListener(S3ChangeListener listener) {
    listeners.remove(listener);
  }

  /**
   * The executor that runs the listeners.
   *
   * @return the executor.
   */
  public Executor executor() {
    return executor;
  }

  /**
   * Set the executor that runs the listeners. With the default direct executor, a listener runs on the thread that
   * made the change, before the operation returns. Another executor, e.g.
   * {@code Executors.newSingleThreadExecutor()}, runs the listeners apart from the operation, so that a slow listener
   * doesn't hold it up; a single-threaded executor keeps the changes in the order they were committed. The publisher
   * doesn't shut the executor down.
   *
   * @param executor the executor.
   */
  public void executor(Executor executor) {
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /**
   * Publish a change that the current operation made. Within a change, it is delivered once the outermost change of the
   * thread has ended, if the change it was published in succeeded; otherwise it is delivered right away.
   *
   * @param change the change.
   */
  public void publish(S3Change change) {
    Objects.requireNonNull(change);
    if (listeners.isEmpty()) {
      return;
    }
    String outerOperation = operation.get();
    S3Change named = outerOperation == null ? change : change.withOperation(outerOperation);
    Pending current = pending.get();
    if (current == null) {
      deliver(List.of(named));
    } else {
      current.scopes.peek().add(named);
    }
  }

  /**
   * Run a change, holding back the changes that it publishes: they are kept if it succeeds, and dropped if it fails.
   * The changes kept are delivered once the outermost change of the thread has ended, even if that one fails: a change
   * nested in it, e.g. of another bucket, has been persisted on its own.
   *
   * @param change the change, which returns once it is persisted and its locks are released.
   * @return the result of the change.
   */
  public <T> T withinChange(Supplier<T> change) {
    Pending current = pending.get();
    boolean outermost = current == null;
    if (outermost) {
      current = new Pending();
      pending.set(current);
    }
    List<S3Change> published = new ArrayList<>();
    current.scopes.push(published);
    boolean succeeded = false;
    try {
      T result = change.get();
      succeeded = true;
      return result;
    } finally {
      current.scopes.pop();
      if (succeeded) {
        current.committed.addAll(published);
      }
      if (outermost) {
        pending.remove();
        deliver(current.committed);
      }
    }
  }

  /**
   * Run an operation as a part of another, so that the changes it publishes name the other one, e.g. the object that
   * {@code CopyObject} stores through {@code PutObject} is created by {@code CopyObject}. The outermost operation of
   * the thread is named.
   *
   * @param operationName the name of the operation, e.g. {@code CopyObject}.
   * @param action the operation.
   * @return the result of the operation.
   */
  public <T> T asOperation(String operationName, Supplier<T> action) {
    Objects.requireNonNull(operationName);
    if (operation.get() != null) {
      return action.get();
    }
    operation.set(operationName);
    try {
      return action.get();
    } finally {
      operation.remove();
    }
  }

  private void deliver(List<S3Change> changes) {
    for (S3Change change : changes) {
      try {
        executor.execute(() -> notifyListeners(change));
      } catch (RejectedExecutionException e) {
        log.error("Dropped {} of {}/{}: the change listener executor rejected it.", change.type(),
            change.bucketName(), Objects.toString(change.key(), ""), e);
      }
    }
  }

  private void notifyListeners(S3Change change) {
    for (S3ChangeListener listener : listeners) {
      try {
        listener.onChange(change);
      } catch (VirtualMachineError e) {
        throw e;
      } catch (Throwable e) {
        log.error("Change listener failed to handle {} of {}/{}.", change.type(), change.bucketName(),
            Objects.toString(change.key(), ""), e);
      }
    }
  }

  /**
   * The changes of a thread that are held back.
   */
  private static final class Pending {

    /**
     * The changes published within each change that is running, the innermost first.
     */
    private final Deque<List<S3Change>> scopes = new ArrayDeque<>();

    /**
     * The changes of the changes that succeeded, in the order they were committed.
     */
    private final List<S3Change> committed = new ArrayList<>();

  }

}
