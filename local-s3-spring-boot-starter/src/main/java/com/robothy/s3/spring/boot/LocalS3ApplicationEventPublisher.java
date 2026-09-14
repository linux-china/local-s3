package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.listener.BucketEvent;
import com.robothy.s3.rest.listener.ObjectEvent;
import com.robothy.s3.rest.listener.S3Event;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Publishes the {@linkplain BucketEvent}s and {@linkplain ObjectEvent}s of the embedded LocalS3 service to the
 * application context, where {@code @EventListener} and {@code @TransactionalEventListener} methods receive them.
 *
 * <p>The application context registers the {@code @EventListener} methods only once every singleton is created, while
 * the service may already change before, e.g. when it creates its buckets as the first client bean starts it. The
 * events of such changes are kept, and published in their order once the application context is refreshed; a
 * {@code @TransactionalEventListener} doesn't receive them within the transaction that made the change, if any. Once the
 * application context is refreshed, an event is published on the thread that made the change.
 */
public class LocalS3ApplicationEventPublisher implements ApplicationListener<ContextRefreshedEvent> {

  private final ApplicationContext applicationContext;

  private final Queue<S3Event> pending = new ArrayDeque<>();

  private volatile boolean refreshed;

  public LocalS3ApplicationEventPublisher(ApplicationContext applicationContext) {
    this.applicationContext = Objects.requireNonNull(applicationContext);
  }

  /**
   * Publish a bucket event, or keep it until the application context is refreshed.
   *
   * @param event the event.
   */
  public void onBucketEvent(BucketEvent event) {
    publish(event);
  }

  /**
   * Publish an object event, or keep it until the application context is refreshed.
   *
   * @param event the event.
   */
  public void onObjectEvent(ObjectEvent event) {
    publish(event);
  }

  private void publish(S3Event event) {
    if (!refreshed) {
      synchronized (pending) {
        if (!refreshed) {
          pending.add(event);
          return;
        }
      }
    }
    publisher().publishEvent(event);
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    // A child context refreshes on its own, and publishes its refresh to the listeners of its parents too.
    if (event.getApplicationContext() != applicationContext || refreshed) {
      return;
    }
    synchronized (pending) {
      // A listener that changes the service while the kept events are published adds its events to the queue, and
      // they are published after the others, like they would be once the context is refreshed.
      S3Event next;
      while ((next = pending.poll()) != null) {
        publisher().publishEvent(next);
      }
      refreshed = true;
    }
  }

  private ApplicationEventPublisher publisher() {
    return applicationContext;
  }

}
