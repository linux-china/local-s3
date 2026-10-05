package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.rest.LocalS3;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The changes of LocalS3 reach the {@code @EventListener} and {@code @TransactionalEventListener} methods of the
 * application.
 */
class LocalS3EventsTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(LocalS3AutoConfiguration.class))
      .withPropertyValues("local-s3.port=-1", "local-s3.buckets=events")
      .withUserConfiguration(Listeners.class);

  @Test
  void anEventListenerReceivesTheEventsOfTheRequestsAndOfTheEmbeddedCalls() {
    runner.run(context -> {
      Listeners listeners = context.getBean(Listeners.class);
      assertEquals(List.of("events"), listeners.buckets.stream().map(S3Change::bucketName).toList(),
          "The change of the default bucket, which is made before the listeners are registered, is kept for them.");

      context.getBean(S3Client.class).putObject(request -> request.bucket("events").key("by-client.txt"),
          RequestBody.fromString("Hello"));
      put(context.getBean(LocalS3.class), "embedded.txt");

      assertEquals(List.of("by-client.txt", "embedded.txt"),
          listeners.objects.stream().map(S3Change::key).toList());
      S3Change change = listeners.objects.getFirst();
      assertEquals(S3ChangeType.OBJECT_CREATED, change.type());
      assertEquals("PutObject", change.operation());
      assertEquals("s3:ObjectCreated:Put", change.s3EventName());
      assertEquals(5L, change.size());
    });
  }

  /**
   * A change made in a transaction, e.g. a put through the services of LocalS3 in a {@code @Transactional} method, is
   * delivered on its thread, so a {@code @TransactionalEventListener} receives it once the transaction commits, and not
   * at all if it rolls back. LocalS3 doesn't take part in the transaction: the object is stored either way.
   */
  @Test
  void aTransactionalEventListenerReceivesTheChangesOfACommittedTransaction() {
    runner.withUserConfiguration(Transactions.class).run(context -> {
      Listeners listeners = context.getBean(Listeners.class);
      TransactionTemplate transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      LocalS3 localS3 = context.getBean(LocalS3.class);

      transactions.executeWithoutResult(status -> {
        put(localS3, "committed.txt");
        assertEquals(List.of("committed.txt"), listeners.objects.stream().map(S3Change::key).toList(),
            "An @EventListener receives the change right away.");
        assertTrue(listeners.committed.isEmpty(), "A @TransactionalEventListener waits for the commit.");
      });
      assertEquals(List.of("committed.txt"), listeners.committed.stream().map(S3Change::key).toList());

      transactions.executeWithoutResult(status -> {
        put(localS3, "rolled-back.txt");
        status.setRollbackOnly();
      });
      assertEquals(List.of("committed.txt"), listeners.committed.stream().map(S3Change::key).toList());

      // A request of a client is handled outside any transaction of the application.
      context.getBean(S3Client.class).putObject(request -> request.bucket("events").key("by-client.txt"),
          RequestBody.fromString("Hello"));
      assertEquals(3, listeners.objects.size());
      assertEquals(1, listeners.committed.size());
    });
  }

  /**
   * The changes that beans make while they are initialized, before the application context registers the
   * {@code @EventListener} methods, are published in their order once it is refreshed.
   */
  @Test
  void theChangesMadeWhileTheContextIsRefreshedAreKept() {
    runner.withUserConfiguration(InitializingUploader.class).run(context -> {
      put(context.getBean(LocalS3.class), "after-refresh.txt");
      assertEquals(List.of("while-initializing.txt", "after-refresh.txt"),
          context.getBean(Listeners.class).objects.stream().map(S3Change::key).toList());
    });
  }

  @Test
  void theChangesCanBeDisabled() {
    runner.withPropertyValues("local-s3.events.enabled=false").run(context -> {
      put(context.getBean(LocalS3.class), "silent.txt");
      Listeners listeners = context.getBean(Listeners.class);
      assertTrue(listeners.buckets.isEmpty());
      assertTrue(listeners.objects.isEmpty());
    });
  }

  /**
   * With {@code local-s3.events.executor=virtual}, a slow listener doesn't hold up the request that made the change, and
   * the changes are still delivered in the order they were committed.
   */
  @Test
  void theVirtualExecutorDeliversInOrderWithoutHoldingUpTheRequests() {
    runner.withPropertyValues("local-s3.events.executor=virtual").withUserConfiguration(BlockingListener.class)
        .run(context -> {
          BlockingListener blocking = context.getBean(BlockingListener.class);
          S3Client s3 = context.getBean(S3Client.class);
          for (int i = 0; i < 5; i++) {
            int n = i;
            s3.putObject(request -> request.bucket("events").key("key-" + n), RequestBody.fromString("Hello"));
          }
          assertTrue(blocking.keys.isEmpty(), "The requests are answered while the listener waits.");

          blocking.release.countDown();
          List<S3Change> objects = context.getBean(Listeners.class).objects;
          awaitSize(objects, 5);
          assertEquals(List.of("key-0", "key-1", "key-2", "key-3", "key-4"),
              objects.stream().map(S3Change::key).toList());
          assertTrue(blocking.threads.stream().allMatch(thread -> thread.startsWith("local-s3-events-")),
              blocking.threads.toString());
        });
  }

  /**
   * A change published on another thread is outside the transaction that made it: a
   * {@code @TransactionalEventListener} ignores it, while an {@code @EventListener} receives it.
   */
  @Test
  void aTransactionalEventListenerIgnoresTheChangesOfAnAsynchronousExecutor() {
    runner.withPropertyValues("local-s3.events.executor=virtual").withUserConfiguration(Transactions.class)
        .run(context -> {
          Listeners listeners = context.getBean(Listeners.class);
          new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
              .executeWithoutResult(status -> put(context.getBean(LocalS3.class), "committed.txt"));
          awaitSize(listeners.objects, 1);
          // The delivery to both listeners is done once the @EventListener has received it.
          Thread.sleep(100);
          assertTrue(listeners.committed.isEmpty());
        });
  }

  @Test
  void theApplicationExecutorDeliversOnTheApplicationTaskExecutor() {
    runner.withPropertyValues("local-s3.events.executor=application")
        .withUserConfiguration(ApplicationTaskExecutor.class).run(context -> {
          put(context.getBean(LocalS3.class), "on-application-executor.txt");
          Listeners listeners = context.getBean(Listeners.class);
          awaitSize(listeners.objects, 1);
          assertEquals("application-task", listeners.objectThreads.getFirst());
        });
  }

  @Test
  void theApplicationExecutorNeedsAnExecutorBean() {
    runner.withPropertyValues("local-s3.events.executor=application").run(context -> {
      assertTrue(context.getStartupFailure() != null);
      assertTrue(context.getStartupFailure().getMessage().contains("local-s3.events.executor=application"),
          context.getStartupFailure().getMessage());
    });
  }

  /**
   * Closing the context delivers the changes that are still queued.
   */
  @Test
  void closingTheContextDeliversTheQueuedChanges() {
    List<S3Change> objects = new CopyOnWriteArrayList<>();
    runner.withPropertyValues("local-s3.events.executor=virtual").withUserConfiguration(BlockingListener.class)
        .run(context -> {
          put(context.getBean(LocalS3.class), "queued.txt");
          BlockingListener blocking = context.getBean(BlockingListener.class);
          // Released once the context is closing, while the change is still queued.
          new Thread(() -> {
            try {
              Thread.sleep(200);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            blocking.release.countDown();
          }).start();
          objects.addAll(context.getBean(Listeners.class).objects);
          context.getBean(Listeners.class).sink = objects;
        });
    assertEquals(List.of("queued.txt"), objects.stream().map(S3Change::key).distinct().toList());
  }

  private static void awaitSize(List<?> list, int size) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (list.size() < size && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(size, list.size(), list.toString());
  }

  private static void put(LocalS3 localS3, String key) {
    localS3.getS3Manager().objectService().putObject("events", key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream("Hello".getBytes()))
        .build());
  }

  @Configuration(proxyBeanMethods = false)
  static class Listeners {

    final List<S3Change> buckets = new CopyOnWriteArrayList<>();

    final List<S3Change> objects = new CopyOnWriteArrayList<>();

    final List<S3Change> committed = new CopyOnWriteArrayList<>();

    final List<String> objectThreads = new CopyOnWriteArrayList<>();

    /**
     * Also receives the changes of objects, when set: a test reads it after the context is closed.
     */
    volatile List<S3Change> sink;

    @EventListener
    void onChange(S3Change change) {
      // A change of a bucket names no object key.
      if (change.key() != null) {
        objectThreads.add(Thread.currentThread().getName());
        List<S3Change> current = sink;
        if (current != null) {
          current.add(change);
        }
      }
      (change.key() == null ? buckets : objects).add(change);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void afterCommit(S3Change change) {
      if (change.key() != null) {
        committed.add(change);
      }
    }

  }

  /**
   * Holds up the delivery of the changes of objects until it is released.
   */
  @Configuration(proxyBeanMethods = false)
  static class BlockingListener {

    final CountDownLatch release = new CountDownLatch(1);

    final List<String> keys = new CopyOnWriteArrayList<>();

    final List<String> threads = new CopyOnWriteArrayList<>();

    // Before the listener of Listeners, which only sees a change once this one has let it through.
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void onChange(S3Change change) throws InterruptedException {
      if (change.key() != null) {
        release.await();
        keys.add(change.key());
        threads.add(Thread.currentThread().getName());
      }
    }

  }

  @Configuration(proxyBeanMethods = false)
  static class ApplicationTaskExecutor {

    @Bean(destroyMethod = "shutdown")
    ExecutorService applicationTaskExecutor() {
      return Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "application-task"));
    }

  }

  @Configuration(proxyBeanMethods = false)
  static class InitializingUploader {

    @Bean
    org.springframework.beans.factory.InitializingBean uploader(S3Client s3) {
      return () -> s3.putObject(request -> request.bucket("events").key("while-initializing.txt"),
          RequestBody.fromString("Hello"));
    }

  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class Transactions {

    @Bean
    PlatformTransactionManager transactionManager() {
      return new NoResourceTransactionManager();
    }

  }

  /**
   * A transaction manager without a resource, which only runs the synchronizations of its transactions.
   */
  static class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(@NonNull Object transaction, @NonNull TransactionDefinition definition) {
    }

    @Override
    protected void doCommit(@NonNull DefaultTransactionStatus status) {
    }

    @Override
    protected void doRollback(@NonNull DefaultTransactionStatus status) {
    }

  }

}
