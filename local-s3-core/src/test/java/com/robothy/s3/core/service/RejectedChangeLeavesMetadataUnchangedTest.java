package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.model.ObjectLockMode;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.internal.ObjectLock;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.RenameObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.datatypes.ObjectIdentifier;
import com.robothy.s3.datatypes.request.DeleteObjectsRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * An operation that rejects a request does so before it changes the metadata of the bucket: an {@code IN_MEMORY}
 * service has no store to restore a bucket from, so {@linkplain DefaultBucketGuard} keeps whatever a rejected change did
 * to it. {@linkplain RejectionCheckingBucketGuard} compares the bucket before and after every rejected change of the
 * requests below, which reach the checks of the operations that change a bucket in several steps.
 */
class RejectedChangeLeavesMetadataUnchangedTest {

  private static final String BUCKET = "bucket";

  private static final String LOCKED_BUCKET = "locked";

  private static final long MIN_PART_SIZE = 5 * 1024 * 1024;

  private RejectionCheckingBucketGuard guard;

  private BucketService bucketService;

  private ObjectService objectService;

  @BeforeEach
  void setUp() {
    LocalS3Metadata metadata = new LocalS3Metadata();
    guard = new RejectionCheckingBucketGuard(() -> metadata);
    bucketService = DefaultBucketService.create(metadata, guard);
    objectService = DefaultObjectService.create(metadata, Storage.createInMemory(), guard);
    bucketService.createBucket(BUCKET);
    bucketService.createBucket(LOCKED_BUCKET, null, true);
  }

  @AfterEach
  void noRejectionChangedABucket() {
    assertEquals(List.of(), guard.violations(), "rejected changes that left a bucket changed");
    // Some of the rejections of every test are raised within a change of the bucket, where they could have changed it;
    // the others are raised before the bucket is locked.
    assertTrue(guard.checkedRejections() > 0, "no rejection was raised within a change of a bucket");
  }

  @Test
  void completeMultipartUpload() {
    put(BUCKET, "existing", "existing");
    String uploadId = objectService.createMultipartUpload(BUCKET, "multipart",
        CreateMultipartUploadOptions.builder().build());
    String etag1 = uploadPart("multipart", uploadId, 1, "first");
    String etag2 = uploadPart("multipart", uploadId, 2, "second");

    // An entity tag that isn't the one of the part, after a part that is valid.
    assertRejected(() -> objectService.completeMultipartUpload(BUCKET, "multipart", uploadId,
        List.of(part(1, etag1), part(2, "\"0123456789abcdef0123456789abcdef\""))));
    // A part that wasn't uploaded.
    assertRejected(() -> objectService.completeMultipartUpload(BUCKET, "multipart", uploadId,
        List.of(part(1, etag1), part(3, etag2))));
    // Parts out of order.
    assertRejected(() -> objectService.completeMultipartUpload(BUCKET, "multipart", uploadId,
        List.of(part(2, etag2), part(1, etag1))));
    // A part other than the last that is too small.
    assertRejected(() -> objectService.completeMultipartUpload(BUCKET, "multipart", uploadId,
        List.of(part(1, etag1), part(2, etag2)), MIN_PART_SIZE));
    // A size that isn't the sum of the parts.
    assertRejected(() -> objectService.completeMultipartUpload(BUCKET, "multipart", uploadId,
        List.of(part(1, etag1), part(2, etag2)), 0, true, ObjectPreconditions.none(), null, null, 1L));
    // A conditional write whose condition doesn't hold, which is evaluated under the write lock of the bucket.
    put(BUCKET, "multipart", "current");
    assertRejected(() -> objectService.completeMultipartUpload(BUCKET, "multipart", uploadId,
        List.of(part(1, etag1), part(2, etag2)), 0, true,
        ObjectPreconditions.builder().ifNoneMatch(ObjectPreconditions.WILDCARD).build(), null, null, null));
    assertRejected(() -> objectService.completeMultipartUpload(BUCKET, "multipart", uploadId,
        List.of(part(1, etag1), part(2, etag2)), 0, true,
        ObjectPreconditions.builder().ifMatch("\"0123456789abcdef0123456789abcdef\"").build(), null, null, null));

    // The upload survived every rejection, and completes.
    objectService.completeMultipartUpload(BUCKET, "multipart", uploadId, List.of(part(1, etag1), part(2, etag2)));
  }

  @Test
  void uploadPartAndAbort() {
    String uploadId = objectService.createMultipartUpload(BUCKET, "multipart",
        CreateMultipartUploadOptions.builder().build());
    assertRejected(() -> uploadPart("multipart", "no-such-upload", 1, "part"));
    assertRejected(() -> uploadPart("other-key", uploadId, 1, "part"));
    assertRejected(() -> uploadPart("multipart", uploadId, 10001, "part"));
    assertRejected(() -> objectService.abortMultipartUpload(BUCKET, "multipart", "no-such-upload"));
  }

  @Test
  void copyObject() {
    put(BUCKET, "source", "source");
    put(BUCKET, "destination", "destination");

    // A copy of an object onto itself that changes nothing.
    assertRejected(() -> objectService.copyObject(BUCKET, "source", CopyObjectOptions.builder()
        .sourceBucket(BUCKET).sourceKey("source").build()));
    assertRejected(() -> objectService.copyObject(BUCKET, "copy", CopyObjectOptions.builder()
        .sourceBucket(BUCKET).sourceKey("missing").build()));
    assertRejected(() -> objectService.copyObject(BUCKET, "destination", CopyObjectOptions.builder()
        .sourceBucket(BUCKET).sourceKey("source")
        .preconditions(ObjectPreconditions.builder().ifNoneMatch(ObjectPreconditions.WILDCARD).build()).build()));
    assertRejected(() -> objectService.copyObject(BUCKET, "copy", CopyObjectOptions.builder()
        .sourceBucket(BUCKET).sourceKey("source")
        .sourcePreconditions(ObjectPreconditions.builder().ifMatch("\"0123456789abcdef0123456789abcdef\"").build())
        .build()));
    // Object Lock settings for a bucket without Object Lock, which are rejected once the content is copied.
    assertRejected(() -> objectService.copyObject(BUCKET, "copy", CopyObjectOptions.builder()
        .sourceBucket(BUCKET).sourceKey("source")
        .objectLock(new ObjectLock(ObjectLockMode.GOVERNANCE, inOneDay(), null)).build()));
  }

  @Test
  void putObject() {
    put(BUCKET, "existing", "existing");
    assertRejected(() -> objectService.putObject(BUCKET, "existing", content("other")
        .preconditions(ObjectPreconditions.builder().ifNoneMatch(ObjectPreconditions.WILDCARD).build()).build()));
    assertRejected(() -> objectService.putObject(BUCKET, "existing", content("other")
        .preconditions(ObjectPreconditions.builder().ifMatch("\"0123456789abcdef0123456789abcdef\"").build())
        .build()));
    assertRejected(() -> objectService.putObject(BUCKET, "new", content("other")
        .objectLock(new ObjectLock(ObjectLockMode.GOVERNANCE, inOneDay(), null)).build()));
    assertRejected(() -> objectService.putObject(BUCKET, "new", content("other")
        .contentMd5("AAAAAAAAAAAAAAAAAAAAAA==").build()));
    // An append at an offset that isn't the size of the object.
    assertRejected(() -> objectService.putObject(BUCKET, "existing", content("more").writeOffsetBytes(1L).build()));
  }

  @Test
  void renameObject() {
    put(BUCKET, "source", "source");
    put(BUCKET, "destination", "destination");
    assertRejected(() -> objectService.renameObject(BUCKET, "renamed",
        RenameObjectOptions.builder().sourceKey("missing").build()));
    assertRejected(() -> objectService.renameObject(BUCKET, "destination", RenameObjectOptions.builder()
        .sourceKey("source")
        .preconditions(ObjectPreconditions.builder().ifNoneMatch(ObjectPreconditions.WILDCARD).build()).build()));
    assertRejected(() -> objectService.renameObject(BUCKET, "renamed", RenameObjectOptions.builder()
        .sourceKey("source")
        .sourcePreconditions(ObjectPreconditions.builder().ifMatch("\"0123456789abcdef0123456789abcdef\"").build())
        .build()));
    // A bucket whose versioning was ever configured can't rename.
    bucketService.setVersioningEnabled(BUCKET, true);
    assertRejected(() -> objectService.renameObject(BUCKET, "renamed",
        RenameObjectOptions.builder().sourceKey("source").build()));
  }

  @Test
  void deleteObjectsAndDeleteObject() {
    put(BUCKET, "a", "a");
    DeleteObjectsRequest tooMany = new DeleteObjectsRequest();
    List<ObjectIdentifier> identifiers = new ArrayList<>();
    for (int i = 0; i < 1001; i++) {
      ObjectIdentifier identifier = new ObjectIdentifier();
      identifier.setKey(i == 0 ? "a" : "key-" + i);
      identifiers.add(identifier);
    }
    tooMany.setObjects(identifiers);
    assertRejected(() -> objectService.deleteObjects(BUCKET, tooMany));

    assertRejected(() -> objectService.deleteObject(BUCKET, "a", null,
        ObjectPreconditions.builder().ifMatch("\"0123456789abcdef0123456789abcdef\"").build()));
    assertRejected(() -> objectService.deleteObject(BUCKET, "a", "no-such-version"));
  }

  @Test
  void objectLock() {
    String versionId = objectService.putObject(LOCKED_BUCKET, "locked", content("locked")
        .objectLock(new ObjectLock(ObjectLockMode.COMPLIANCE, inOneDay(), null)).build()).getVersionId();

    // A version that a retention protects isn't deleted, and its retention isn't shortened.
    assertRejected(() -> objectService.deleteObject(LOCKED_BUCKET, "locked", versionId));
    assertRejected(() -> objectService.putObjectRetention(LOCKED_BUCKET, "locked", versionId,
        ObjectLockMode.GOVERNANCE, inOneDay(), true));
    assertRejected(() -> objectService.putObjectRetention(LOCKED_BUCKET, "locked", versionId,
        ObjectLockMode.COMPLIANCE, Instant.now().plusSeconds(60).toEpochMilli(), true));
    // A legal hold or a retention of an object of a bucket without Object Lock.
    put(BUCKET, "plain", "plain");
    assertRejected(() -> objectService.putObjectLegalHold(BUCKET, "plain", null, true));
    assertRejected(() -> objectService.putObjectRetention(BUCKET, "plain", null, ObjectLockMode.GOVERNANCE,
        inOneDay(), false));
  }

  @Test
  void bucketConfiguration() {
    put(BUCKET, "a", "a");
    assertRejected(() -> bucketService.deleteBucket(BUCKET));
    assertRejected(() -> bucketService.createBucket(BUCKET));
    assertRejected(() -> bucketService.putBucketLifecycleConfiguration(BUCKET,
        "<LifecycleConfiguration><Rule><ID>r</ID><Status>Enabled</Status><Filter/>"
            + "<Expiration><Days>1</Days></Expiration></Rule></LifecycleConfiguration>", "bogus"));
    // Disabling the Object Lock of a bucket that has it.
    assertRejected(() -> bucketService.setVersioningEnabled(LOCKED_BUCKET, false));
  }

  @Test
  void lifecycle() {
    put(BUCKET, "a", "a");
    assertRejected(() -> objectService.applyLifecycle("no-such-bucket", Instant.now()));
    // A lifecycle that expires a version that Object Lock protects keeps the version, and changes the rest.
    objectService.putObject(LOCKED_BUCKET, "locked", content("locked")
        .objectLock(new ObjectLock(ObjectLockMode.COMPLIANCE, inOneDay(), null)).build());
    objectService.applyLifecycle(Instant.now().plus(Duration.ofDays(400)));
  }

  /**
   * Assert that the request is rejected with a {@linkplain LocalS3Exception}; whether it left its bucket unchanged is
   * checked by the guard, and asserted once the test is done.
   */
  private static void assertRejected(Executable request) {
    LocalS3Exception rejection = assertThrows(LocalS3Exception.class, request);
    assertTrue(rejection.getMessage() == null || !rejection.getMessage().isEmpty());
  }

  private void put(String bucket, String key, String content) {
    objectService.putObject(bucket, key, content(content).build());
  }

  private static PutObjectOptions.PutObjectOptionsBuilder content(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return PutObjectOptions.builder().content(new ByteArrayInputStream(bytes)).size(bytes.length);
  }

  private String uploadPart(String key, String uploadId, int partNumber, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return objectService.uploadPart(BUCKET, key, uploadId, partNumber, UploadPartOptions.builder()
        .data(new ByteArrayInputStream(bytes)).contentLength(bytes.length).build()).getEtag();
  }

  private static CompleteMultipartUploadPartOption part(int partNumber, String etag) {
    return CompleteMultipartUploadPartOption.builder().partNumber(partNumber).etag(etag).build();
  }

  private static long inOneDay() {
    return Instant.now().plus(Duration.ofDays(1)).toEpochMilli();
  }

}
