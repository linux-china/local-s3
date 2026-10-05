package com.robothy.s3.spring.boot;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

/**
 * The executor that delivers the changes of the embedded LocalS3 service to its listeners, as
 * {@code local-s3.events.executor} selects it; see {@code docs/embedding.md#events-of-the-spring-boot-starter}.
 *
 * <p>Deliberately not an {@linkplain Executor} itself: a bean of that type would make the task execution
 * auto-configuration of Spring Boot back off, and the application lose its {@code applicationTaskExecutor}.
 */
public class LocalS3EventExecutor implements DisposableBean {

  /**
   * The bean that Spring Boot defines for the {@code @Async} methods and the task executors of the application.
   */
  static final String APPLICATION_TASK_EXECUTOR = "applicationTaskExecutor";

  /**
   * How long closing the context waits for the changes that are still to be delivered.
   */
  static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

  private static final Logger log = LoggerFactory.getLogger(LocalS3EventExecutor.class);

  private final Executor executor;

  /**
   * The executor of its own that is shut down with the context, or {@code null} when it belongs to someone else.
   */
  private final ExecutorService owned;

  private LocalS3EventExecutor(Executor executor, ExecutorService owned) {
    this.executor = Objects.requireNonNull(executor);
    this.owned = owned;
  }

  static LocalS3EventExecutor of(LocalS3Properties.EventExecutor kind, ListableBeanFactory beanFactory) {
    return switch (kind) {
      case DIRECT -> new LocalS3EventExecutor(Runnable::run, null);
      case VIRTUAL -> {
        // A single thread keeps the changes in the order they were committed.
        ExecutorService service = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("local-s3-events-", 0).factory());
        yield new LocalS3EventExecutor(service, service);
      }
      case APPLICATION -> new LocalS3EventExecutor(applicationExecutor(beanFactory), null);
    };
  }

  private static Executor applicationExecutor(ListableBeanFactory beanFactory) {
    if (beanFactory.containsBean(APPLICATION_TASK_EXECUTOR)) {
      return beanFactory.getBean(APPLICATION_TASK_EXECUTOR, Executor.class);
    }
    // Spring Boot leaves applicationTaskExecutor out when the application defines an Executor of its own.
    try {
      return beanFactory.getBeanProvider(Executor.class).getObject();
    } catch (NoSuchBeanDefinitionException e) {
      throw new IllegalStateException("local-s3.events.executor=application needs the " + APPLICATION_TASK_EXECUTOR
          + " bean of Spring Boot, or a single Executor bean, but the context has "
          + (e.getNumberOfBeansFound() > 1 ? "several Executor beans" : "none") + ". Name one of them "
          + APPLICATION_TASK_EXECUTOR + ", or set local-s3.events.executor=virtual.", e);
    }
  }

  public Executor executor() {
    return executor;
  }

  /**
   * Delivers the changes that are still queued, so that the listeners hear of every change the service committed
   * before it stopped, then shuts the executor of its own down. The service is stopped before, with the lifecycle of
   * the context, so no change is queued afterwards.
   */
  @Override
  public void destroy() throws InterruptedException {
    if (owned == null) {
      return;
    }
    owned.shutdown();
    if (!owned.awaitTermination(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      log.warn("Dropped the changes of LocalS3 that the listeners didn't receive within {} s of closing the context.",
          DRAIN_TIMEOUT.toSeconds());
      owned.shutdownNow();
    }
  }

}
