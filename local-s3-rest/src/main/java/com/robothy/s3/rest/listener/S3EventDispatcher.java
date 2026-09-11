package com.robothy.s3.rest.listener;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers {@linkplain BucketEvent}s and {@linkplain ObjectEvent}s to the registered listeners.
 *
 * <p>Listeners run on the given {@linkplain Executor}. With a direct executor, events are delivered
 * synchronously on the thread handling the request, before the S3 response is sent. A failing listener
 * is logged and never fails the S3 request that triggered the event.
 */
public final class S3EventDispatcher {

  private static final Logger log = LoggerFactory.getLogger(S3EventDispatcher.class);

  private final BucketEventListener bucketEventListener;

  private final ObjectEventListener objectEventListener;

  private final Executor executor;

  /**
   * Create a dispatcher.
   *
   * @param bucketEventListener receives bucket events, may be {@code null}.
   * @param objectEventListener receives object events, may be {@code null}.
   * @param executor runs the listeners.
   */
  public S3EventDispatcher(@Nullable BucketEventListener bucketEventListener,
                           @Nullable ObjectEventListener objectEventListener,
                           @NonNull Executor executor) {
    this.bucketEventListener = bucketEventListener;
    this.objectEventListener = objectEventListener;
    this.executor = Objects.requireNonNull(executor);
  }

  /**
   * Deliver a bucket event to the bucket event listener, if any.
   *
   * @param event the event.
   */
  public void dispatch(@NonNull BucketEvent event) {
    if (bucketEventListener != null) {
      deliver(event, () -> bucketEventListener.onBucketEvent(event));
    }
  }

  /**
   * Deliver an object event to the object event listener, if any.
   *
   * @param event the event.
   */
  public void dispatch(@NonNull ObjectEvent event) {
    if (objectEventListener != null) {
      deliver(event, () -> objectEventListener.onObjectEvent(event));
    }
  }

  private void deliver(S3Event event, Runnable delivery) {
    try {
      executor.execute(() -> {
        try {
          delivery.run();
        } catch (VirtualMachineError e) {
          throw e;
        } catch (Throwable e) {
          log.error("Event listener failed to handle {} event {}.", event.getEventType(), event.getEventId(), e);
        }
      });
    } catch (RejectedExecutionException e) {
      log.error("Dropped {} event {}: the event listener executor rejected it.", event.getEventType(), event.getEventId(), e);
    }
  }

}
