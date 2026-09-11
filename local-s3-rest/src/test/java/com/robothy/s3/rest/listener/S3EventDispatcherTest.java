package com.robothy.s3.rest.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class S3EventDispatcherTest {

  private static ObjectEvent objectEvent() {
    return new ObjectEvent(S3EventType.OBJECT_CREATED, "PutObject", "bucket", "key", null, 5L, "etag", false);
  }

  @Test
  void listenerFailureDoesNotPropagate() {
    AtomicInteger objectEvents = new AtomicInteger();
    S3EventDispatcher dispatcher = new S3EventDispatcher(
        event -> {
          throw new IllegalStateException("bucket listener failed");
        },
        event -> {
          objectEvents.incrementAndGet();
          throw new AssertionError("object listener failed");
        },
        Runnable::run);

    assertDoesNotThrow(() -> dispatcher.dispatch(new BucketEvent(S3EventType.BUCKET_CREATED, "CreateBucket", "bucket", null)));
    assertDoesNotThrow(() -> dispatcher.dispatch(objectEvent()));
    assertEquals(1, objectEvents.get());
  }

  @Test
  void deliversEventsOnTheGivenExecutor() throws InterruptedException {
    ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "event-listener"));
    try {
      AtomicReference<String> listenerThread = new AtomicReference<>();
      CountDownLatch delivered = new CountDownLatch(1);
      S3EventDispatcher dispatcher = new S3EventDispatcher(null, event -> {
        listenerThread.set(Thread.currentThread().getName());
        delivered.countDown();
      }, executor);

      dispatcher.dispatch(objectEvent());

      assertTrue(delivered.await(5, TimeUnit.SECONDS));
      assertEquals("event-listener", listenerThread.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void dropsEventRejectedByTheExecutor() {
    S3EventDispatcher dispatcher = new S3EventDispatcher(null, event -> fail("Must not be delivered."), runnable -> {
      throw new RejectedExecutionException("rejected");
    });

    assertDoesNotThrow(() -> dispatcher.dispatch(objectEvent()));
  }

  @Test
  void ignoresEventsWithoutListener() {
    S3EventDispatcher dispatcher = new S3EventDispatcher(null, null, runnable -> fail("Nothing to deliver."));

    assertDoesNotThrow(() -> dispatcher.dispatch(objectEvent()));
    assertDoesNotThrow(() -> dispatcher.dispatch(new BucketEvent(S3EventType.BUCKET_DELETED, "DeleteBucket", "bucket", null)));
  }

}
