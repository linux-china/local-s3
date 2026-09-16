package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.rest.LocalS3;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

public class S3ChangeListenerIntegrationTest {

  private static final String BUCKET = "events";

  private final List<S3Change> bucketChanges = new CopyOnWriteArrayList<>();

  private final List<S3Change> objectChanges = new CopyOnWriteArrayList<>();

  private LocalS3 localS3;

  private S3Client s3;

  @BeforeEach
  void setUp() {
    localS3 = LocalS3.builder()
        .port(-1)
        .changeListener(this::record)
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

  /**
   * Sort a committed change into the changes of the buckets and those of the objects; only a change of a bucket names
   * no object key.
   */
  private void record(S3Change change) {
    (change.key() == null ? bucketChanges : objectChanges).add(change);
  }

  private S3Change lastObjectChange() {
    return objectChanges.get(objectChanges.size() - 1);
  }

  @Test
  void firesChangesWithObjectDetails() {
    s3.createBucket(request -> request.bucket(BUCKET));
    assertEquals(1, bucketChanges.size());
    assertEquals(S3ChangeType.BUCKET_CREATED, bucketChanges.get(0).type());
    assertEquals("CreateBucket", bucketChanges.get(0).operation());
    assertEquals(BUCKET, bucketChanges.get(0).bucketName());

    s3.putObject(request -> request.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello"));
    S3Change put = lastObjectChange();
    assertEquals(S3ChangeType.OBJECT_CREATED, put.type());
    assertEquals("PutObject", put.operation());
    assertEquals("a.txt", put.key());
    assertEquals(5L, put.size());
    assertEquals(Digests.md5Hex("Hello"), put.etag());
    assertNull(put.versionId());

    s3.copyObject(request -> request.sourceBucket(BUCKET).sourceKey("a.txt").destinationBucket(BUCKET).destinationKey("b.txt"));
    S3Change copy = lastObjectChange();
    assertEquals("CopyObject", copy.operation());
    assertEquals("b.txt", copy.key());
    assertEquals(5L, copy.size());
    assertEquals(Digests.md5Hex("Hello"), copy.etag());

    byte[] firstPart = new byte[5 * 1024 * 1024];
    byte[] lastPart = "tail".getBytes();
    String uploadId = s3.createMultipartUpload(request -> request.bucket(BUCKET).key("big.bin")).uploadId();
    String etag1 = s3.uploadPart(request -> request.bucket(BUCKET).key("big.bin").uploadId(uploadId).partNumber(1),
        RequestBody.fromBytes(firstPart)).eTag();
    String etag2 = s3.uploadPart(request -> request.bucket(BUCKET).key("big.bin").uploadId(uploadId).partNumber(2),
        RequestBody.fromBytes(lastPart)).eTag();
    int changesBeforeComplete = objectChanges.size();
    CompleteMultipartUploadResponse completed = s3.completeMultipartUpload(request -> request.bucket(BUCKET).key("big.bin")
        .uploadId(uploadId)
        .multipartUpload(upload -> upload.parts(
            CompletedPart.builder().partNumber(1).eTag(etag1).build(),
            CompletedPart.builder().partNumber(2).eTag(etag2).build())));
    assertEquals(changesBeforeComplete + 1, objectChanges.size());
    S3Change multipart = lastObjectChange();
    assertEquals(S3ChangeType.OBJECT_CREATED, multipart.type());
    assertEquals("CompleteMultipartUpload", multipart.operation());
    assertEquals("big.bin", multipart.key());
    assertEquals((long) firstPart.length + lastPart.length, multipart.size());
    // A change reports the entity tag without its quotes, like the eTag of an Amazon S3 event notification.
    assertEquals(Etags.unquoted(completed.eTag()), multipart.etag());

    s3.deleteObject(request -> request.bucket(BUCKET).key("a.txt"));
    S3Change delete = lastObjectChange();
    assertEquals(S3ChangeType.OBJECT_DELETED, delete.type());
    assertEquals("DeleteObject", delete.operation());
    assertEquals("a.txt", delete.key());
    assertFalse(delete.deleteMarker());
    assertNull(delete.size());
    assertNull(delete.etag());
  }

  /**
   * An application that embeds LocalS3 and changes its data through the services, rather than through HTTP, is
   * notified like a client of the HTTP API.
   */
  @Test
  void firesChangesMadeThroughTheServices() {
    localS3.getS3Manager().bucketService().createBucket(BUCKET);
    assertEquals(1, bucketChanges.size());
    assertEquals("CreateBucket", bucketChanges.get(0).operation());

    localS3.getS3Manager().objectService().putObject(BUCKET, "embedded.txt", PutObjectOptions.builder()
        .content(new ByteArrayInputStream("Hello".getBytes()))
        .build());
    S3Change put = lastObjectChange();
    assertEquals(S3ChangeType.OBJECT_CREATED, put.type());
    assertEquals("PutObject", put.operation());
    assertEquals("embedded.txt", put.key());
    assertEquals(5L, put.size());
    assertEquals("s3:ObjectCreated:Put", put.s3EventName());

    // The object is visible to clients by the time its change is delivered, and a client's delete is delivered too.
    s3.deleteObject(request -> request.bucket(BUCKET).key("embedded.txt"));
    assertEquals(S3ChangeType.OBJECT_DELETED, lastObjectChange().type());
    assertEquals(2, objectChanges.size());
  }

  @Test
  void firesChangesForTaggingAclAndAbortedUploads() {
    s3.createBucket(request -> request.bucket(BUCKET));
    s3.putBucketVersioning(request -> request.bucket(BUCKET)
        .versioningConfiguration(config -> config.status(BucketVersioningStatus.ENABLED)));
    String versionId = s3.putObject(request -> request.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello"))
        .versionId();

    objectChanges.clear();
    s3.putObjectTagging(request -> request.bucket(BUCKET).key("a.txt")
        .tagging(tagging -> tagging.tagSet(tag -> tag.key("k").value("v"))));
    s3.deleteObjectTagging(request -> request.bucket(BUCKET).key("a.txt"));
    s3.putObjectAcl(request -> request.bucket(BUCKET).key("a.txt")
        .accessControlPolicy(policy -> policy.owner(owner -> owner.id("001").displayName("LocalS3"))
            .grants(grant -> grant.permission("FULL_CONTROL")
                .grantee(grantee -> grantee.type("CanonicalUser").id("001")))));
    assertEquals(List.of(S3ChangeType.OBJECT_TAGGING_PUT, S3ChangeType.OBJECT_TAGGING_DELETED, S3ChangeType.OBJECT_ACL_PUT),
        objectChanges.stream().map(S3Change::type).toList());
    assertEquals(List.of("PutObjectTagging", "DeleteObjectTagging", "PutObjectAcl"),
        objectChanges.stream().map(S3Change::operation).toList());
    assertEquals(List.of("s3:ObjectTagging:Put", "s3:ObjectTagging:Delete", "s3:ObjectAcl:Put"),
        objectChanges.stream().map(S3Change::s3EventName).toList());
    assertTrue(objectChanges.stream().allMatch(change -> versionId.equals(change.versionId())));

    objectChanges.clear();
    String uploadId = s3.createMultipartUpload(request -> request.bucket(BUCKET).key("big.bin")).uploadId();
    s3.uploadPart(request -> request.bucket(BUCKET).key("big.bin").uploadId(uploadId).partNumber(1),
        RequestBody.fromString("part"));
    assertTrue(objectChanges.isEmpty(), "Neither creating an upload nor uploading a part fires a change.");
    s3.abortMultipartUpload(request -> request.bucket(BUCKET).key("big.bin").uploadId(uploadId));
    assertEquals(1, objectChanges.size());
    assertEquals(S3ChangeType.MULTIPART_UPLOAD_ABORTED, lastObjectChange().type());
    assertEquals(uploadId, lastObjectChange().uploadId());
  }

  @Test
  void firesChangesForTheDefaultBuckets() {
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    LocalS3 withBuckets = LocalS3.builder().port(-1).buckets("first", "second").changeListener(changes::add).build();
    withBuckets.start();
    try {
      assertEquals(List.of("first", "second"), changes.stream().map(S3Change::bucketName).toList());
      assertTrue(changes.stream().allMatch(change -> "CreateBucket".equals(change.operation())));
    } finally {
      withBuckets.shutdown();
    }
  }

  @Test
  void deleteObjectsFiresChangesOnlyForDeletedKeys() {
    s3.createBucket(request -> request.bucket(BUCKET));
    s3.putBucketVersioning(request -> request.bucket(BUCKET)
        .versioningConfiguration(config -> config.status(BucketVersioningStatus.SUSPENDED)));
    s3.putObject(request -> request.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello"));
    s3.putObject(request -> request.bucket(BUCKET).key("b.txt"), RequestBody.fromString("World"));
    ObjectIdentifier missingVersion = ObjectIdentifier.builder().key("missing.txt").versionId("123").build();

    objectChanges.clear();
    DeleteObjectsResponse response = s3.deleteObjects(request -> request.bucket(BUCKET)
        .delete(delete -> delete.objects(ObjectIdentifier.builder().key("a.txt").build(), missingVersion)));
    assertEquals(1, response.deleted().size());
    assertEquals(1, response.errors().size());
    assertEquals(1, objectChanges.size());
    S3Change deleted = objectChanges.get(0);
    assertEquals(S3ChangeType.OBJECT_DELETED, deleted.type());
    assertEquals("DeleteObjects", deleted.operation());
    assertEquals("a.txt", deleted.key());
    assertTrue(deleted.deleteMarker());

    objectChanges.clear();
    DeleteObjectsResponse quietResponse = s3.deleteObjects(request -> request.bucket(BUCKET)
        .delete(delete -> delete.quiet(true).objects(ObjectIdentifier.builder().key("b.txt").build(), missingVersion)));
    assertTrue(quietResponse.deleted().isEmpty());
    assertEquals(1, quietResponse.errors().size());
    assertEquals(1, objectChanges.size());
    assertEquals("b.txt", objectChanges.get(0).key());
  }

  @Test
  void failingListenerDoesNotFailRequest() {
    LocalS3 failing = LocalS3.builder()
        .port(-1)
        .changeListener(change -> {
          throw new IllegalStateException("change listener failed");
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
  void deliversChangesOnConfiguredExecutor() throws InterruptedException {
    ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "s3-change-listener"));
    BlockingQueue<String> listenerThreads = new LinkedBlockingQueue<>();
    LocalS3 async = LocalS3.builder()
        .port(-1)
        .changeListenerExecutor(executor)
        .changeListener(change -> listenerThreads.add(Thread.currentThread().getName()))
        .build();
    async.start();
    try (S3Client client = client(async.getPort())) {
      client.createBucket(request -> request.bucket(BUCKET));
      assertEquals("s3-change-listener", listenerThreads.poll(5, TimeUnit.SECONDS));
    } finally {
      async.shutdown();
      executor.shutdownNow();
    }
  }

}
