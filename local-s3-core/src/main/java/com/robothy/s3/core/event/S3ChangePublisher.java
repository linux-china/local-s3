package com.robothy.s3.core.event;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers the {@linkplain S3Change}s of the services of a LocalS3 service to its {@linkplain S3ChangeListener}s,
 * however an operation was called; see {@code docs/semantics.md#change-events}.
 *
 * <p>The changes an operation {@linkplain #publish publishes} are held back until the outermost
 * {@linkplain #withinChange change} of the thread has ended, then delivered if it was persisted and dropped if it failed:
 * a listener never hears of a change that didn't happen, and runs after the bucket locks are released, so it may call
 * the services again. A listener slower than {@linkplain #SLOW_LISTENER_THRESHOLD} on the thread that made the change is
 * logged once, as a hint to set another {@linkplain #executor(Executor) executor}.
 */
@Slf4j
public final class S3ChangePublisher {

  /**
   * How long a listener may run on the thread that made the change before it is logged as slow.
   */
  public static final Duration SLOW_LISTENER_THRESHOLD = Duration.ofSeconds(1);

  private final List<S3ChangeListener> listeners = new CopyOnWriteArrayList<>();

  /**
   * The listeners that were logged as slow, which aren't logged again.
   */
  private final Set<S3ChangeListener> slowListeners = Collections.synchronizedSet(
      Collections.newSetFromMap(new IdentityHashMap<>()));

  /**
   * Runs the listeners; delivers on the thread that made the change by default.
   */
  private volatile Executor executor = Runnable::run;

  /**
   * The changes of the thread that are held back, while it runs a change.
   */
  private final ScopedValue<Pending> pending = ScopedValue.newInstance();

  /**
   * The outermost operation that the thread runs {@linkplain #asOperation as}, which the changes it publishes name.
   */
  private final ScopedValue<String> operation = ScopedValue.newInstance();

  public void addListener(S3ChangeListener listener) {
    listeners.add(Objects.requireNonNull(listener));
  }

  public void removeListener(S3ChangeListener listener) {
    listeners.remove(listener);
    slowListeners.remove(listener);
  }

  /**
   * Unsubscribe every listener, e.g. those of an application that is gone, when another one takes the data over.
   */
  public void clearListeners() {
    listeners.clear();
    slowListeners.clear();
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
   * made the change, before the operation returns, so the time it takes adds to the latency of the operation. Another
   * executor, e.g.
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
    S3Change named = operation.isBound() ? change.withOperation(operation.get()) : change;
    if (pending.isBound()) {
      pending.get().scopes.peek().add(named);
    } else {
      deliver(List.of(named));
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
    if (pending.isBound()) {
      return pending.get().run(change);
    }
    Pending outermost = new Pending();
    try {
      return ScopedValue.where(pending, outermost).call(() -> outermost.run(change));
    } finally {
      // Out of the scope, so that a listener that runs on this thread and changes a bucket starts a change of its own.
      deliver(outermost.committed);
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
    return operation.isBound() ? action.get() : ScopedValue.where(operation, operationName).call(action::get);
  }

  private void deliver(List<S3Change> changes) {
    Thread changingThread = Thread.currentThread();
    for (S3Change change : changes) {
      try {
        executor.execute(() -> notifyListeners(change, Thread.currentThread() == changingThread));
      } catch (RejectedExecutionException e) {
        log.error("Dropped {} of {}/{}: the change listener executor rejected it.", change.type(),
            change.bucketName(), Objects.toString(change.key(), ""), e);
      }
    }
  }

  /**
   * @param onChangingThread whether the listeners run on the thread that made the change, which they hold up.
   */
  private void notifyListeners(S3Change change, boolean onChangingThread) {
    for (S3ChangeListener listener : listeners) {
      long start = System.nanoTime();
      try {
        listener.onChange(change);
      } catch (VirtualMachineError e) {
        throw e;
      } catch (Throwable e) {
        log.error("Change listener failed to handle {} of {}/{}.", change.type(), change.bucketName(),
            Objects.toString(change.key(), ""), e);
      } finally {
        if (onChangingThread) {
          warnIfSlow(listener, change, System.nanoTime() - start);
        }
      }
    }
  }

  private void warnIfSlow(S3ChangeListener listener, S3Change change, long elapsedNanos) {
    if (elapsedNanos > SLOW_LISTENER_THRESHOLD.toNanos() && slowListeners.add(listener)) {
      log.warn("Change listener {} took {} ms to handle {} of {}/{} on the thread that made the change, which held up "
              + "the operation, e.g. its HTTP request. Deliver the changes on another executor, e.g. "
              + "LocalS3.builder().events(e -> e.executor(Executors.newSingleThreadExecutor())), or "
              + "LocalS3Manager#changeListenerExecutor, or local-s3.events.executor=virtual with the Spring Boot "
              + "starter. This is logged once per listener.",
          listener, Duration.ofNanos(elapsedNanos).toMillis(), change.type(), change.bucketName(),
          Objects.toString(change.key(), ""));
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

    /**
     * Run a change, keeping the changes published within it if it succeeds.
     */
    private <T> T run(Supplier<T> change) {
      List<S3Change> published = new ArrayList<>();
      scopes.push(published);
      boolean succeeded = false;
      try {
        T result = change.get();
        succeeded = true;
        return result;
      } finally {
        scopes.pop();
        if (succeeded) {
          committed.addAll(published);
        }
      }
    }

  }

}
