package com.robothy.s3.rest.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import java.util.ArrayList;
import java.util.List;
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

  @Test
  void deliversTheEventOfEveryChangeType() {
    List<S3Event> events = new ArrayList<>();
    S3EventDispatcher dispatcher = new S3EventDispatcher(events::add, events::add, Runnable::run);

    dispatcher.onChange(S3Change.bucketCreated("CreateBucket", "bucket", "eu-west-1"));
    dispatcher.onChange(S3Change.objectVersion(S3ChangeType.OBJECT_TAGGING_PUT, "PutObjectTagging", "bucket", "key",
        "42", 5, "etag"));
    dispatcher.onChange(S3Change.multipartUploadAborted("AbortMultipartUpload", "bucket", "key", "upload-1"));

    BucketEvent created = (BucketEvent) events.get(0);
    assertEquals(S3EventType.BUCKET_CREATED, created.getEventType());
    assertEquals("eu-west-1", created.getBucketRegion());
    assertNull(created.getS3EventName());

    ObjectEvent tagged = (ObjectEvent) events.get(1);
    assertEquals(S3EventType.OBJECT_TAGGING_PUT, tagged.getEventType());
    assertEquals("PutObjectTagging", tagged.getSource());
    assertEquals("42", tagged.getVersionId());
    assertEquals(5L, tagged.getSize());
    assertEquals("s3:ObjectTagging:Put", tagged.getS3EventName());

    ObjectEvent aborted = (ObjectEvent) events.get(2);
    assertEquals(S3EventType.MULTIPART_UPLOAD_ABORTED, aborted.getEventType());
    assertEquals("upload-1", aborted.getUploadId());
    assertNull(aborted.getS3EventName());

    // Every change type has an event type of the same name, which onChange maps it to.
    for (S3ChangeType type : S3ChangeType.values()) {
      assertEquals(type.name(), S3EventType.valueOf(type.name()).name());
    }
    assertEquals(S3ChangeType.values().length, S3EventType.values().length);
  }

}
