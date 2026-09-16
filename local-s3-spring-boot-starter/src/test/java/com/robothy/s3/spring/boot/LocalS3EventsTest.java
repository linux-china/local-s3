package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.rest.LocalS3;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
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

    @EventListener
    void onChange(S3Change change) {
      // A change of a bucket names no object key.
      (change.key() == null ? buckets : objects).add(change);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void afterCommit(S3Change change) {
      if (change.key() != null) {
        committed.add(change);
      }
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
