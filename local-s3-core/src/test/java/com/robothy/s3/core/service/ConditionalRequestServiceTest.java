package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartCopyOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.datatypes.ObjectIdentifier;
import com.robothy.s3.datatypes.request.DeleteObjectsRequest;
import com.robothy.s3.datatypes.response.DeleteResult;
import com.robothy.s3.datatypes.response.S3Error;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The conditions of {@code CopyObject}, {@code UploadPartCopy}, {@code CompleteMultipartUpload}, {@code DeleteObject}
 * and {@code DeleteObjects}: the ones that the commit and the clean-up of table formats like Iceberg and Delta Lake
 * rely on, besides the conditional {@code PutObject} of {@linkplain ConditionalPutObjectServiceTest}.
 */
class ConditionalRequestServiceTest extends LocalS3ServiceTestBase {

  private static final String BUCKET = "conditional-bucket";

  private static String put(ObjectService objectService, String key, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return objectService.putObject(BUCKET, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes))
        .size(bytes.length)
        .build()).getEtag();
  }

  private static String content(ObjectService objectService, String key) throws IOException {
    GetObjectAns ans = objectService.getObject(BUCKET, key, GetObjectOptions.builder().build());
    try (InputStream content = ans.getContent()) {
      return new String(content.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static boolean exists(ObjectService objectService, String key) {
    try {
      objectService.headObject(BUCKET, key, GetObjectOptions.builder().build());
      return true;
    } catch (ObjectNotExistException e) {
      return false;
    }
  }

  private static ObjectPreconditions ifMatch(String etag) {
    return ObjectPreconditions.builder().ifMatch(etag).build();
  }

  private static ObjectPreconditions ifNoneMatch(String etag) {
    return ObjectPreconditions.builder().ifNoneMatch(etag).build();
  }

  private static void assertFailsOn(String condition, Executable request) {
    PreconditionFailedException thrown = assertThrows(PreconditionFailedException.class, request);
    assertEquals(condition, thrown.getCondition());
  }

  private static CopyObjectOptions.CopyObjectOptionsBuilder copyOf(String sourceKey) {
    return CopyObjectOptions.builder().sourceBucket(BUCKET).sourceKey(sourceKey);
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aCopyEvaluatesTheConditionsOfItsDestinationLikeAPut(LocalS3Manager manager) throws IOException {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    put(objectService, "source.json", "new");
    String existing = put(objectService, "target.json", "old");

    assertFailsOn("If-None-Match", () -> objectService.copyObject(BUCKET, "target.json",
        copyOf("source.json").preconditions(ifNoneMatch("*")).build()));
    assertFailsOn("If-Match", () -> objectService.copyObject(BUCKET, "target.json",
        copyOf("source.json").preconditions(ifMatch("\"not-the-etag\"")).build()));
    assertThrows(ObjectNotExistException.class, () -> objectService.copyObject(BUCKET, "absent.json",
        copyOf("source.json").preconditions(ifMatch("*")).build()));
    assertEquals("old", content(objectService, "target.json"), "A rejected copy leaves the destination alone.");
    assertTrue(!exists(objectService, "absent.json"));

    objectService.copyObject(BUCKET, "target.json", copyOf("source.json").preconditions(ifMatch(existing)).build());
    assertEquals("new", content(objectService, "target.json"));
    objectService.copyObject(BUCKET, "created.json", copyOf("source.json").preconditions(ifNoneMatch("*")).build());
    assertEquals("new", content(objectService, "created.json"));
  }

  /**
   * The conditions of the source object are ordered like the ones of a read, but a source that the client already
   * holds fails the copy instead of answering Not Modified.
   */
  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aCopyEvaluatesTheConditionsOfItsSource(LocalS3Manager manager) throws IOException {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    String etag = put(objectService, "source.json", "content");
    long past = Instant.now().minusSeconds(3600).toEpochMilli();
    long future = Instant.now().plusSeconds(3600).toEpochMilli();
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    manager.addChangeListener(changes::add);

    assertFailsOn("x-amz-copy-source-if-match", () -> objectService.copyObject(BUCKET, "copy.json",
        copyOf("source.json").sourcePreconditions(ifMatch("\"other\"")).build()));
    assertFailsOn("x-amz-copy-source-if-none-match", () -> objectService.copyObject(BUCKET, "copy.json",
        copyOf("source.json").sourcePreconditions(ifNoneMatch(etag)).build()));
    assertFailsOn("x-amz-copy-source-if-unmodified-since", () -> objectService.copyObject(BUCKET, "copy.json",
        copyOf("source.json").sourcePreconditions(ObjectPreconditions.builder().ifUnmodifiedSince(past).build())
            .build()));
    assertFailsOn("x-amz-copy-source-if-modified-since", () -> objectService.copyObject(BUCKET, "copy.json",
        copyOf("source.json").sourcePreconditions(ObjectPreconditions.builder().ifModifiedSince(future).build())
            .build()));
    // If-None-Match takes precedence over If-Modified-Since, which would hold.
    assertFailsOn("x-amz-copy-source-if-none-match", () -> objectService.copyObject(BUCKET, "copy.json",
        copyOf("source.json").sourcePreconditions(ObjectPreconditions.builder().ifNoneMatch(etag)
            .ifModifiedSince(past).build()).build()));
    assertTrue(!exists(objectService, "copy.json"));
    assertEquals(List.of(), changes, "A rejected copy creates nothing.");

    // If-Match takes precedence over If-Unmodified-Since, which doesn't hold.
    objectService.copyObject(BUCKET, "copy.json", copyOf("source.json")
        .sourcePreconditions(ObjectPreconditions.builder().ifMatch(etag).ifUnmodifiedSince(past).build()).build());
    assertEquals("content", content(objectService, "copy.json"));
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aPartCopyEvaluatesTheConditionsOfItsSource(LocalS3Manager manager) {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    String etag = put(objectService, "source.json", "content");
    String uploadId = objectService.createMultipartUpload(BUCKET, "big.bin", CreateMultipartUploadOptions.builder()
        .build());
    UploadPartCopyOptions.UploadPartCopyOptionsBuilder copy = UploadPartCopyOptions.builder()
        .sourceBucket(BUCKET).sourceKey("source.json");

    assertFailsOn("x-amz-copy-source-if-match", () -> objectService.uploadPartCopy(BUCKET, "big.bin", uploadId, 1,
        copy.sourcePreconditions(ifMatch("\"other\"")).build()));
    assertEquals(0, objectService.listParts(BUCKET, "big.bin", uploadId, null, null).getParts().size());

    objectService.uploadPartCopy(BUCKET, "big.bin", uploadId, 1, copy.sourcePreconditions(ifMatch(etag)).build());
    assertEquals(1, objectService.listParts(BUCKET, "big.bin", uploadId, null, null).getParts().size());
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aCompletedUploadEvaluatesTheConditionsOfTheKeyAndKeepsARejectedUpload(LocalS3Manager manager)
      throws IOException {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    String existing = put(objectService, "table/metadata.json", "old");
    String uploadId = upload(objectService, "table/metadata.json", "new");

    assertFailsOn("If-None-Match", () -> complete(objectService, "table/metadata.json", uploadId, ifNoneMatch("*")));
    assertFailsOn("If-Match", () -> complete(objectService, "table/metadata.json", uploadId, ifMatch("\"other\"")));
    assertEquals("old", content(objectService, "table/metadata.json"));
    assertEquals(1, objectService.listParts(BUCKET, "table/metadata.json", uploadId, null, null).getParts().size(),
        "The upload whose condition failed is kept, with its parts.");

    complete(objectService, "table/metadata.json", uploadId, ifMatch(existing));
    assertEquals("new", content(objectService, "table/metadata.json"));

    String absent = upload(objectService, "absent.json", "x");
    assertThrows(ObjectNotExistException.class, () -> complete(objectService, "absent.json", absent, ifMatch("*")));
  }

  /**
   * Of the uploads that race to complete the same key with {@code If-None-Match: *}, exactly one creates the object.
   */
  @MethodSource("localS3Managers")
  @ParameterizedTest
  void onlyOneOfTheRacingUploadsCreatesTheKey(LocalS3Manager manager) throws Exception {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    int racers = 8;
    List<String> uploads = new ArrayList<>();
    for (int i = 0; i < racers; i++) {
      uploads.add(upload(objectService, "lock.json", "writer-" + i));
    }

    CyclicBarrier start = new CyclicBarrier(racers);
    AtomicInteger winners = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(racers);
    try {
      List<Future<?>> attempts = new ArrayList<>();
      for (String uploadId : uploads) {
        attempts.add(executor.submit(() -> {
          start.await();
          try {
            complete(objectService, "lock.json", uploadId, ifNoneMatch("*"));
            winners.incrementAndGet();
          } catch (PreconditionFailedException e) {
            // Another upload completed first.
          }
          return null;
        }));
      }
      for (Future<?> attempt : attempts) {
        attempt.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, winners.get());
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aDeleteIfMatchOnlyDeletesTheCurrentObjectWithThatEntityTag(LocalS3Manager manager) {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    String etag = put(objectService, "data.parquet", "rows");

    assertFailsOn("If-Match", () -> objectService.deleteObject(BUCKET, "data.parquet", null, ifMatch("\"other\"")));
    assertTrue(exists(objectService, "data.parquet"));
    assertThrows(ObjectNotExistException.class,
        () -> objectService.deleteObject(BUCKET, "absent.parquet", null, ifMatch("*")));

    objectService.deleteObject(BUCKET, "data.parquet", null, ifMatch(etag));
    assertTrue(!exists(objectService, "data.parquet"));

    put(objectService, "any.parquet", "rows");
    objectService.deleteObject(BUCKET, "any.parquet", null, ifMatch("*"));
    assertTrue(!exists(objectService, "any.parquet"));
  }

  /**
   * In a versioned bucket, a condition is evaluated against the current version, whatever version is deleted, and a
   * delete marker is an object that doesn't exist.
   */
  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aDeleteIfMatchOfAVersionedObjectEvaluatesTheCurrentVersion(LocalS3Manager manager) {
    manager.bucketService().createBucket(BUCKET);
    manager.bucketService().setVersioningEnabled(BUCKET, true);
    ObjectService objectService = manager.objectService();
    String first = put(objectService, "v.json", "first");
    String firstVersion = objectService.headObject(BUCKET, "v.json", GetObjectOptions.builder().build()).getVersionId();
    String current = put(objectService, "v.json", "second");

    assertFailsOn("If-Match", () -> objectService.deleteObject(BUCKET, "v.json", firstVersion, ifMatch(first)));
    objectService.deleteObject(BUCKET, "v.json", firstVersion, ifMatch(current));
    assertThrows(RuntimeException.class, () -> objectService.getObject(BUCKET, "v.json",
        GetObjectOptions.builder().versionId(firstVersion).build()));

    // The delete marker becomes the current version, which If-Match: * doesn't match.
    assertTrue(objectService.deleteObject(BUCKET, "v.json", null, ifMatch("*")).isDeleteMarker());
    assertFailsOn("If-Match", () -> objectService.deleteObject(BUCKET, "v.json", null, ifMatch("*")));
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aDeleteIfMatchTimeOrSizeComparesTheCurrentObject(LocalS3Manager manager) {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    put(objectService, "sized.bin", "12345");
    long lastModified = objectService.headObject(BUCKET, "sized.bin", GetObjectOptions.builder().build())
        .getLastModified();

    assertFailsOn("x-amz-if-match-size", () -> objectService.deleteObject(BUCKET, "sized.bin", null,
        ObjectPreconditions.builder().ifMatchSize(4L).build()));
    assertFailsOn("x-amz-if-match-last-modified-time", () -> objectService.deleteObject(BUCKET, "sized.bin", null,
        ObjectPreconditions.builder().ifMatchLastModifiedTime(lastModified - 5000).build()));
    assertTrue(exists(objectService, "sized.bin"));

    // The time is compared by the second, which is the precision of the HTTP date that a client sends.
    objectService.deleteObject(BUCKET, "sized.bin", null, ObjectPreconditions.builder().ifMatchSize(5L)
        .ifMatchLastModifiedTime(lastModified / 1000 * 1000).build());
    assertTrue(!exists(objectService, "sized.bin"));
    // A key that holds no object satisfies them, like Amazon S3 answers such a delete with 204.
    objectService.deleteObject(BUCKET, "sized.bin", null, ObjectPreconditions.builder().ifMatchSize(5L).build());
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void deleteObjectsEvaluatesTheConditionsOfEachObject(LocalS3Manager manager) {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    String etag = put(objectService, "matching.json", "a");
    put(objectService, "changed.json", "b");

    ObjectIdentifier matching = new ObjectIdentifier("matching.json", null);
    matching.setETag(etag);
    ObjectIdentifier changed = new ObjectIdentifier("changed.json", null);
    changed.setETag("\"stale\"");
    ObjectIdentifier absent = new ObjectIdentifier("absent.json", null);
    absent.setETag("*");
    ObjectIdentifier unconditional = new ObjectIdentifier("other.json", null);

    List<Object> results = objectService.deleteObjects(BUCKET,
        new DeleteObjectsRequest(List.of(matching, changed, absent, unconditional), false));

    assertEquals("matching.json", assertInstanceOf(DeleteResult.Deleted.class, results.get(0)).getKey());
    assertEquals(S3ErrorCode.PreconditionFailed.code(), assertInstanceOf(S3Error.class, results.get(1)).getCode());
    assertEquals(S3ErrorCode.NoSuchKey.code(), assertInstanceOf(S3Error.class, results.get(2)).getCode());
    assertInstanceOf(DeleteResult.Deleted.class, results.get(3));
    assertTrue(!exists(objectService, "matching.json"));
    assertTrue(exists(objectService, "changed.json"));
  }

  private static String upload(ObjectService objectService, String key, String content) {
    String uploadId = objectService.createMultipartUpload(BUCKET, key, CreateMultipartUploadOptions.builder().build());
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    objectService.uploadPart(BUCKET, key, uploadId, 1, UploadPartOptions.builder()
        .data(new ByteArrayInputStream(bytes))
        .contentLength(bytes.length)
        .build());
    return uploadId;
  }

  private static void complete(ObjectService objectService, String key, String uploadId,
                               ObjectPreconditions preconditions) {
    objectService.completeMultipartUpload(BUCKET, key, uploadId,
        List.of(CompleteMultipartUploadPartOption.builder().partNumber(1).build()), 0, true, preconditions);
  }

}
