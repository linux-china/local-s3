package com.robothy.s3.rest.listener;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeListener;
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
 * <p>The dispatcher listens to the {@linkplain S3Change}s that the services commit, see
 * {@linkplain com.robothy.s3.core.service.manager.LocalS3Manager#addChangeListener}, so an event is delivered for
 * every change, whether an HTTP request or a direct call of a service made it.
 *
 * <p>Listeners run on the given {@linkplain Executor}. With a direct executor, events are delivered
 * synchronously on the thread that made the change, before the S3 response is sent. A failing listener
 * is logged and never fails the S3 request that triggered the event.
 */
public final class S3EventDispatcher implements S3ChangeListener {

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
   * Deliver the event of a committed change: a {@linkplain BucketEvent} for the change of a bucket, and an
   * {@linkplain ObjectEvent} for the others.
   *
   * @param change the change.
   */
  @Override
  public void onChange(@NonNull S3Change change) {
    S3EventType eventType = S3EventType.valueOf(change.type().name());
    switch (change.type()) {
      case BUCKET_CREATED, BUCKET_DELETED -> dispatch(new BucketEvent(eventType, change.operation(),
          change.bucketName(), change.bucketRegion()));
      default -> dispatch(new ObjectEvent(eventType, change.operation(), change.bucketName(), change.key(),
          change.versionId(), change.size(), change.etag(), change.deleteMarker(), change.uploadId()));
    }
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
