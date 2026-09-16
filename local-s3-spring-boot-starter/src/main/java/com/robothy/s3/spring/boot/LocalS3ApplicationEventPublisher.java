package com.robothy.s3.spring.boot;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeListener;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Publishes the {@linkplain S3Change}s that the embedded LocalS3 service commits to the application context, where
 * {@code @EventListener} and {@code @TransactionalEventListener} methods receive them.
 *
 * <p>The application context registers the {@code @EventListener} methods only once every singleton is created, while
 * the service may already change before, e.g. when it creates its buckets as the first client bean starts it. The
 * changes are kept, and published in their order once the application context is refreshed; a
 * {@code @TransactionalEventListener} doesn't receive them within the transaction that made the change, if any. Once the
 * application context is refreshed, a change is published on the thread that made it.
 */
public class LocalS3ApplicationEventPublisher implements S3ChangeListener, ApplicationListener<ContextRefreshedEvent> {

  private final ApplicationContext applicationContext;

  private final Queue<S3Change> pending = new ArrayDeque<>();

  private volatile boolean refreshed;

  public LocalS3ApplicationEventPublisher(ApplicationContext applicationContext) {
    this.applicationContext = Objects.requireNonNull(applicationContext);
  }

  /**
   * Publish a committed change, or keep it until the application context is refreshed.
   *
   * @param change the change.
   */
  @Override
  public void onChange(S3Change change) {
    if (!refreshed) {
      synchronized (pending) {
        if (!refreshed) {
          pending.add(change);
          return;
        }
      }
    }
    publisher().publishEvent(change);
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    // A child context refreshes on its own, and publishes its refresh to the listeners of its parents too.
    if (event.getApplicationContext() != applicationContext || refreshed) {
      return;
    }
    synchronized (pending) {
      // A listener that changes the service while the kept changes are published adds its changes to the queue, and
      // they are published after the others, like they would be once the context is refreshed.
      S3Change next;
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
