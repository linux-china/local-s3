package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.listener.BucketEvent;
import com.robothy.s3.rest.listener.ObjectEvent;
import com.robothy.s3.rest.listener.S3EventType;
import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

public class S3EventListenerIntegrationTest {

  private static final String BUCKET = "events";

  private final List<BucketEvent> bucketEvents = new CopyOnWriteArrayList<>();

  private final List<ObjectEvent> objectEvents = new CopyOnWriteArrayList<>();

  private LocalS3 localS3;

  private S3Client s3;

  @BeforeEach
  void setUp() {
    localS3 = LocalS3.builder()
        .port(-1)
        .bucketEventListener(bucketEvents::add)
        .objectEventListener(objectEvents::add)
        .build();
    localS3.start();
    s3 = client(localS3.getPort());
  }

  @AfterEach
  void tearDown() {
    s3.close();
    localS3.shutdown();
  }

  private static S3Client client(int port) {
    return S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + port))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
        .forcePathStyle(true)
        .build();
  }

  private ObjectEvent lastObjectEvent() {
    return objectEvents.get(objectEvents.size() - 1);
  }

  @Test
  void firesEventsWithObjectDetails() {
    s3.createBucket(request -> request.bucket(BUCKET));
    assertEquals(1, bucketEvents.size());
    assertEquals(S3EventType.BUCKET_CREATED, bucketEvents.get(0).getEventType());
    assertEquals("CreateBucket", bucketEvents.get(0).getSource());
    assertEquals(BUCKET, bucketEvents.get(0).getBucketName());

    s3.putObject(request -> request.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello"));
    ObjectEvent put = lastObjectEvent();
    assertEquals(S3EventType.OBJECT_CREATED, put.getEventType());
    assertEquals("PutObject", put.getSource());
    assertEquals("a.txt", put.getObjectKey());
    assertEquals(5L, put.getSize());
    assertEquals(DigestUtils.md5Hex("Hello"), put.getEtag());
    assertNull(put.getVersionId());

    s3.copyObject(request -> request.sourceBucket(BUCKET).sourceKey("a.txt").destinationBucket(BUCKET).destinationKey("b.txt"));
    ObjectEvent copy = lastObjectEvent();
    assertEquals("CopyObject", copy.getSource());
    assertEquals("b.txt", copy.getObjectKey());
    assertEquals(5L, copy.getSize());
    assertEquals(DigestUtils.md5Hex("Hello"), copy.getEtag());

    byte[] firstPart = new byte[5 * 1024 * 1024];
    byte[] lastPart = "tail".getBytes();
    String uploadId = s3.createMultipartUpload(request -> request.bucket(BUCKET).key("big.bin")).uploadId();
    String etag1 = s3.uploadPart(request -> request.bucket(BUCKET).key("big.bin").uploadId(uploadId).partNumber(1),
        RequestBody.fromBytes(firstPart)).eTag();
    String etag2 = s3.uploadPart(request -> request.bucket(BUCKET).key("big.bin").uploadId(uploadId).partNumber(2),
        RequestBody.fromBytes(lastPart)).eTag();
    int eventsBeforeComplete = objectEvents.size();
    CompleteMultipartUploadResponse completed = s3.completeMultipartUpload(request -> request.bucket(BUCKET).key("big.bin")
        .uploadId(uploadId)
        .multipartUpload(upload -> upload.parts(
            CompletedPart.builder().partNumber(1).eTag(etag1).build(),
            CompletedPart.builder().partNumber(2).eTag(etag2).build())));
    assertEquals(eventsBeforeComplete + 1, objectEvents.size());
    ObjectEvent multipart = lastObjectEvent();
    assertEquals(S3EventType.OBJECT_CREATED, multipart.getEventType());
    assertEquals("CompleteMultipartUpload", multipart.getSource());
    assertEquals("big.bin", multipart.getObjectKey());
    assertEquals((long) firstPart.length + lastPart.length, multipart.getSize());
    assertEquals(completed.eTag(), multipart.getEtag());

    s3.deleteObject(request -> request.bucket(BUCKET).key("a.txt"));
    ObjectEvent delete = lastObjectEvent();
    assertEquals(S3EventType.OBJECT_DELETED, delete.getEventType());
    assertEquals("DeleteObject", delete.getSource());
    assertEquals("a.txt", delete.getObjectKey());
    assertFalse(delete.isDeleteMarker());
    assertNull(delete.getSize());
    assertNull(delete.getEtag());
  }

  @Test
  void deleteObjectsFiresEventsOnlyForDeletedKeys() {
    s3.createBucket(request -> request.bucket(BUCKET));
    s3.putBucketVersioning(request -> request.bucket(BUCKET)
        .versioningConfiguration(config -> config.status(BucketVersioningStatus.SUSPENDED)));
    s3.putObject(request -> request.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello"));
    s3.putObject(request -> request.bucket(BUCKET).key("b.txt"), RequestBody.fromString("World"));
    ObjectIdentifier missingVersion = ObjectIdentifier.builder().key("missing.txt").versionId("123").build();

    objectEvents.clear();
    DeleteObjectsResponse response = s3.deleteObjects(request -> request.bucket(BUCKET)
        .delete(delete -> delete.objects(ObjectIdentifier.builder().key("a.txt").build(), missingVersion)));
    assertEquals(1, response.deleted().size());
    assertEquals(1, response.errors().size());
    assertEquals(1, objectEvents.size());
    ObjectEvent deleted = objectEvents.get(0);
    assertEquals(S3EventType.OBJECT_DELETED, deleted.getEventType());
    assertEquals("DeleteObjects", deleted.getSource());
    assertEquals("a.txt", deleted.getObjectKey());
    assertTrue(deleted.isDeleteMarker());

    objectEvents.clear();
    DeleteObjectsResponse quietResponse = s3.deleteObjects(request -> request.bucket(BUCKET)
        .delete(delete -> delete.quiet(true).objects(ObjectIdentifier.builder().key("b.txt").build(), missingVersion)));
    assertTrue(quietResponse.deleted().isEmpty());
    assertEquals(1, quietResponse.errors().size());
    assertEquals(1, objectEvents.size());
    assertEquals("b.txt", objectEvents.get(0).getObjectKey());
  }

  @Test
  void failingListenerDoesNotFailRequest() {
    LocalS3 failing = LocalS3.builder()
        .port(-1)
        .bucketEventListener(event -> {
          throw new IllegalStateException("bucket listener failed");
        })
        .objectEventListener(event -> {
          throw new IllegalStateException("object listener failed");
        })
        .build();
    failing.start();
    try (S3Client client = client(failing.getPort())) {
      assertDoesNotThrow(() -> client.createBucket(request -> request.bucket(BUCKET)));
      assertDoesNotThrow(() -> client.putObject(request -> request.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello")));
      assertEquals("Hello", client.getObjectAsBytes(request -> request.bucket(BUCKET).key("a.txt")).asUtf8String());
    } finally {
      failing.shutdown();
    }
  }

  @Test
  void deliversEventsOnConfiguredExecutor() throws InterruptedException {
    ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "s3-event-listener"));
    BlockingQueue<String> listenerThreads = new LinkedBlockingQueue<>();
    LocalS3 async = LocalS3.builder()
        .port(-1)
        .eventListenerExecutor(executor)
        .bucketEventListener(event -> listenerThreads.add(Thread.currentThread().getName()))
        .build();
    async.start();
    try (S3Client client = client(async.getPort())) {
      client.createBucket(request -> request.bucket(BUCKET));
      assertEquals("s3-event-listener", listenerThreads.poll(5, TimeUnit.SECONDS));
    } finally {
      async.shutdown();
      executor.shutdownNow();
    }
  }

}
