package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Conditional requests through the AWS SDK, i.e. the way the code under test reaches them: a commit
 * protocol that takes a lock with {@code If-None-Match: *} or updates a pointer with {@code If-Match},
 * like the ones of Delta Lake and Iceberg, and a client that revalidates a cached object with
 * {@code If-None-Match} or {@code If-Modified-Since}.
 */
public class ConditionalRequestIntegrationTest {

  private static final String KEY = "metadata/v1.json";

  private static String createBucket(S3Client s3, String name) {
    s3.createBucket(b -> b.bucket(name));
    return name;
  }

  private static String put(S3Client s3, String bucket, String content) {
    return s3.putObject(b -> b.bucket(bucket).key(KEY), RequestBody.fromString(content)).eTag();
  }

  private static String content(S3Client s3, String bucket) {
    return s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)).asUtf8String();
  }

  private static S3Exception assertFails(int expectedStatus, Callable<?> request) {
    S3Exception thrown = assertThrows(S3Exception.class, request::call);
    assertEquals(expectedStatus, thrown.statusCode(), thrown.getMessage());
    return thrown;
  }

  /**
   * The lock that a commit protocol takes: of the writers that race to create the same object, the SDK
   * hands exactly one of them a result and the others a 412, so only one of them believes it holds the
   * lock.
   */
  @Test
  @LocalS3
  void onlyOneOfTheRacingWritersTakesTheLock(S3Client s3) throws Exception {
    String bucket = createBucket(s3, "commit-lock");
    int writers = 8;
    CyclicBarrier start = new CyclicBarrier(writers);
    ExecutorService executor = Executors.newFixedThreadPool(writers);
    try {
      List<Future<Boolean>> attempts = new ArrayList<>();
      for (int writer = 0; writer < writers; writer++) {
        String owner = "writer-" + writer;
        attempts.add(executor.submit(() -> {
          start.await();
          try {
            s3.putObject(b -> b.bucket(bucket).key(KEY).ifNoneMatch("*"), RequestBody.fromString(owner));
            return true;
          } catch (S3Exception e) {
            assertEquals(412, e.statusCode(), e.getMessage());
            assertEquals("PreconditionFailed", e.awsErrorDetails().errorCode());
            return false;
          }
        }));
      }

      int winners = 0;
      for (Future<Boolean> attempt : attempts) {
        winners += attempt.get(30, TimeUnit.SECONDS) ? 1 : 0;
      }
      assertEquals(1, winners);
    } finally {
      executor.shutdownNow();
    }

    assertTrue(content(s3, bucket).startsWith("writer-"), content(s3, bucket));
  }

  /**
   * The compare-and-swap that an optimistic update is built on: the put goes through while the object is
   * the one that was read, and is rejected once somebody else has replaced it.
   */
  @Test
  @LocalS3
  void aPutIfMatchSwapsTheObjectThatWasRead(S3Client s3) {
    String bucket = createBucket(s3, "optimistic-update");
    String firstEtag = put(s3, bucket, "version-0");

    String secondEtag = s3.putObject(b -> b.bucket(bucket).key(KEY).ifMatch(firstEtag),
        RequestBody.fromString("version-1")).eTag();
    assertNotEquals(firstEtag, secondEtag);

    // Another writer got there first, so the entity tag that was read is stale.
    S3Exception thrown = assertFails(412, () -> s3.putObject(b -> b.bucket(bucket).key(KEY).ifMatch(firstEtag),
        RequestBody.fromString("version-2")));
    assertEquals("PreconditionFailed", thrown.awsErrorDetails().errorCode());
    assertEquals("version-1", content(s3, bucket));

    // Reading the object again yields the entity tag that the swap goes through with.
    assertEquals(200, s3.putObject(b -> b.bucket(bucket).key(KEY).ifMatch(secondEtag),
        RequestBody.fromString("version-2")).sdkHttpResponse().statusCode());
    assertEquals("version-2", content(s3, bucket));
  }

  /**
   * A swap of a key that holds no object is reported as a missing object rather than as a failed
   * condition, which is what Amazon S3 answers.
   */
  @Test
  @LocalS3
  void aPutIfMatchOnAKeyThatHoldsNoObjectReportsNoSuchKey(S3Client s3) {
    String bucket = createBucket(s3, "swap-absent");
    assertEquals("NoSuchKey", assertFails(404, () -> s3.putObject(b -> b.bucket(bucket).key(KEY)
        .ifMatch("\"d41d8cd98f00b204e9800998ecf8427e\""), RequestBody.fromString("x")))
        .awsErrorDetails().errorCode());
  }

  /**
   * A lock that is released by deleting the object can be taken again, and in a versioned bucket a delete
   * marker counts as a key that holds no object, like it does on Amazon S3.
   */
  @Test
  @LocalS3
  void aDeleteMarkerLeavesTheKeyOpenToBeCreatedAgain(S3Client s3) {
    String bucket = createBucket(s3, "versioned-lock");
    s3.putBucketVersioning(b -> b.bucket(bucket)
        .versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED)));
    s3.putObject(b -> b.bucket(bucket).key(KEY).ifNoneMatch("*"), RequestBody.fromString("held"));
    assertFails(412, () -> s3.putObject(b -> b.bucket(bucket).key(KEY).ifNoneMatch("*"),
        RequestBody.fromString("rejected")));

    s3.deleteObject(b -> b.bucket(bucket).key(KEY));

    s3.putObject(b -> b.bucket(bucket).key(KEY).ifNoneMatch("*"), RequestBody.fromString("held again"));
    assertEquals("held again", content(s3, bucket));
  }

  /**
   * A client revalidating a cached object is told that it is unchanged. The SDK reports the 304 as a
   * failure, because {@code GetObjectResponse} has no content to hand back.
   */
  @Test
  @LocalS3
  void aReadOfAnUnchangedObjectReportsNotModified(S3Client s3) {
    String bucket = createBucket(s3, "revalidate");
    String etag = put(s3, bucket, "cached");
    Instant lastModified = s3.headObject(b -> b.bucket(bucket).key(KEY)).lastModified();

    assertFails(304, () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY).ifNoneMatch(etag)));
    assertFails(304, () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY).ifNoneMatch("*")));
    assertFails(304, () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)
        .ifModifiedSince(lastModified)));
    assertFails(304, () -> s3.headObject(b -> b.bucket(bucket).key(KEY).ifNoneMatch(etag)));

    // An object that did change is answered with, and the entity tag of a stale cache doesn't match it.
    put(s3, bucket, "changed");
    assertEquals("changed", s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY).ifNoneMatch(etag))
        .asUtf8String());
    assertEquals("changed", s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)
        .ifModifiedSince(lastModified.minus(Duration.ofDays(1)))).asUtf8String());
  }

  /**
   * The conditions of a read that don't hold are answered with 412, and the entity tag condition of a read
   * takes precedence over the date condition it pairs with.
   */
  @Test
  @LocalS3
  void aReadWhoseConditionFailedReportsPreconditionFailed(S3Client s3) {
    String bucket = createBucket(s3, "read-conditions");
    String etag = put(s3, bucket, "hello");
    Instant lastModified = s3.headObject(b -> b.bucket(bucket).key(KEY)).lastModified();
    Instant longBefore = lastModified.minus(Duration.ofDays(1));

    assertEquals("PreconditionFailed",
        assertFails(412, () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)
            .ifMatch("\"d41d8cd98f00b204e9800998ecf8427e\""))).awsErrorDetails().errorCode());
    assertFails(412, () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)
        .ifUnmodifiedSince(longBefore)));

    // The entity tag matches, so the If-Unmodified-Since that would fail on its own is irrelevant.
    assertEquals("hello", s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)
        .ifMatch(etag).ifUnmodifiedSince(longBefore)).asUtf8String());
    // The entity tag doesn't match, so the If-Modified-Since that would report it unchanged is irrelevant.
    assertEquals("hello", s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)
        .ifNoneMatch("\"d41d8cd98f00b204e9800998ecf8427e\"").ifModifiedSince(lastModified)).asUtf8String());
  }

  /**
   * A conditional read of a version resolves the condition against that version, not against the latest
   * one, so a client that caches a version is told about that version.
   */
  @Test
  @LocalS3
  void aConditionalReadOfAVersionResolvesAgainstThatVersion(S3Client s3) {
    String bucket = createBucket(s3, "versioned-read");
    s3.putBucketVersioning(b -> b.bucket(bucket)
        .versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED)));
    String firstVersion = s3.putObject(b -> b.bucket(bucket).key(KEY),
        RequestBody.fromString("version-0")).versionId();
    String firstEtag = s3.headObject(b -> b.bucket(bucket).key(KEY)).eTag();
    put(s3, bucket, "version-1");

    assertFails(304, () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)
        .versionId(firstVersion).ifNoneMatch(firstEtag)));
    // The latest version is another one, so the same entity tag doesn't match it.
    assertEquals("version-1", s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)
        .ifNoneMatch(firstEtag)).asUtf8String());
  }

  /**
   * A conditional put doesn't lose the rest of the request: the content, the metadata and the tagging of
   * one that goes through are stored like those of an unconditional one.
   */
  @Test
  @LocalS3
  void aConditionalPutStoresWhatItCarries(S3Client s3) {
    String bucket = createBucket(s3, "conditional-metadata");
    s3.putObject(b -> b.bucket(bucket).key(KEY).ifNoneMatch("*")
        .contentType("application/json")
        .metadata(Map.of("commit", "42"))
        .tagging("stage=committed"), RequestBody.fromString("{}"));

    assertEquals("application/json", s3.headObject(b -> b.bucket(bucket).key(KEY)).contentType());
    assertEquals("42", s3.headObject(b -> b.bucket(bucket).key(KEY)).metadata().get("commit"));
    assertEquals(1, s3.getObjectTagging(b -> b.bucket(bucket).key(KEY)).tagSet().size());
  }

  /**
   * A copy evaluates {@code If-Match} and {@code If-None-Match} against the destination, like a put, and the
   * {@code x-amz-copy-source-if-*} conditions against the source, e.g. to promote a staged metadata file only if it
   * is still the one that was validated, and only if no other writer has committed that version yet.
   */
  @Test
  @LocalS3
  void aCopyEvaluatesTheConditionsOfItsDestinationAndItsSource(S3Client s3) {
    String bucket = createBucket(s3, "conditional-copy");
    String staged = s3.putObject(b -> b.bucket(bucket).key("staged.json"), RequestBody.fromString("new")).eTag();
    String current = put(s3, bucket, "old");

    S3Exception destination = assertFails(412, () -> s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("staged.json")
        .destinationBucket(bucket).destinationKey(KEY).ifNoneMatch("*")));
    assertEquals("PreconditionFailed", destination.awsErrorDetails().errorCode());
    assertFails(412, () -> s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("staged.json")
        .destinationBucket(bucket).destinationKey(KEY).copySourceIfMatch("\"not-the-staged-one\"")));
    assertFails(412, () -> s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("staged.json")
        .destinationBucket(bucket).destinationKey(KEY).copySourceIfNoneMatch(staged)));
    assertFails(412, () -> s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("staged.json")
        .destinationBucket(bucket).destinationKey(KEY)
        .copySourceIfUnmodifiedSince(Instant.now().minus(Duration.ofHours(1)))));
    assertFails(404, () -> s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("staged.json")
        .destinationBucket(bucket).destinationKey("absent.json").ifMatch("*")));
    assertEquals("old", content(s3, bucket));

    s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("staged.json").destinationBucket(bucket).destinationKey(KEY)
        .ifMatch(current).copySourceIfMatch(staged));
    assertEquals("new", content(s3, bucket));
  }

  @Test
  @LocalS3
  void aPartCopyEvaluatesTheConditionsOfItsSource(S3Client s3) {
    String bucket = createBucket(s3, "conditional-part-copy");
    String etag = put(s3, bucket, "content");
    String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key("copy.bin")).uploadId();

    assertFails(412, () -> s3.uploadPartCopy(b -> b.sourceBucket(bucket).sourceKey(KEY).destinationBucket(bucket)
        .destinationKey("copy.bin").uploadId(uploadId).partNumber(1).copySourceIfMatch("\"other\"")));
    s3.uploadPartCopy(b -> b.sourceBucket(bucket).sourceKey(KEY).destinationBucket(bucket)
        .destinationKey("copy.bin").uploadId(uploadId).partNumber(1).copySourceIfMatch(etag));
    assertEquals(1, s3.listParts(b -> b.bucket(bucket).key("copy.bin").uploadId(uploadId)).parts().size());
  }

  /**
   * A large commit file uploaded in parts takes the same lock as a put: the upload that loses keeps its parts, so
   * it can be aborted.
   */
  @Test
  @LocalS3
  void aCompletedUploadEvaluatesTheConditionsOfTheKey(S3Client s3) {
    String bucket = createBucket(s3, "conditional-complete");
    String current = put(s3, bucket, "old");
    String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(KEY)).uploadId();
    String part = s3.uploadPart(b -> b.bucket(bucket).key(KEY).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("new")).eTag();
    CompletedMultipartUpload parts = CompletedMultipartUpload.builder()
        .parts(CompletedPart.builder().partNumber(1).eTag(part).build()).build();

    assertFails(412, () -> s3.completeMultipartUpload(b -> b.bucket(bucket).key(KEY).uploadId(uploadId)
        .multipartUpload(parts).ifNoneMatch("*")));
    assertFails(412, () -> s3.completeMultipartUpload(b -> b.bucket(bucket).key(KEY).uploadId(uploadId)
        .multipartUpload(parts).ifMatch("\"stale\"")));
    assertEquals("old", content(s3, bucket));
    assertEquals(1, s3.listParts(b -> b.bucket(bucket).key(KEY).uploadId(uploadId)).parts().size());

    s3.completeMultipartUpload(b -> b.bucket(bucket).key(KEY).uploadId(uploadId).multipartUpload(parts)
        .ifMatch(current));
    assertEquals("new", content(s3, bucket));
  }

  /**
   * The clean-up of a table deletes a file only if it is still the one that was planned for deletion.
   */
  @Test
  @LocalS3
  void aConditionalDeleteOnlyDeletesTheObjectItWasGiven(S3Client s3) {
    String bucket = createBucket(s3, "conditional-delete");
    String etag = put(s3, bucket, "12345");

    assertFails(412, () -> s3.deleteObject(b -> b.bucket(bucket).key(KEY).ifMatch("\"stale\"")));
    assertFails(412, () -> s3.deleteObject(b -> b.bucket(bucket).key(KEY).ifMatchSize(4L)));
    assertFails(404, () -> s3.deleteObject(b -> b.bucket(bucket).key("absent.json").ifMatch("*")));
    assertEquals("12345", content(s3, bucket));

    s3.deleteObject(b -> b.bucket(bucket).key(KEY).ifMatch(etag).ifMatchSize(5L));
    assertFails(404, () -> s3.headObject(b -> b.bucket(bucket).key(KEY)));
  }

  @Test
  @LocalS3
  void aDeleteIfMatchInAVersionedBucketTreatsADeleteMarkerAsNoObject(S3Client s3) {
    String bucket = createBucket(s3, "conditional-versioned-delete");
    s3.putBucketVersioning(b -> b.bucket(bucket)
        .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
    put(s3, bucket, "content");

    assertTrue(s3.deleteObject(b -> b.bucket(bucket).key(KEY).ifMatch("*")).deleteMarker());
    assertFails(412, () -> s3.deleteObject(b -> b.bucket(bucket).key(KEY).ifMatch("*")));
  }

  @Test
  @LocalS3
  void deleteObjectsEvaluatesTheEntityTagOfEachObject(S3Client s3) {
    String bucket = createBucket(s3, "conditional-delete-objects");
    String etag = s3.putObject(b -> b.bucket(bucket).key("a.json"), RequestBody.fromString("a")).eTag();
    s3.putObject(b -> b.bucket(bucket).key("b.json"), RequestBody.fromString("b"));

    DeleteObjectsResponse response = s3.deleteObjects(b -> b.bucket(bucket).delete(d -> d.objects(
        ObjectIdentifier.builder().key("a.json").eTag(etag).build(),
        ObjectIdentifier.builder().key("b.json").eTag("\"stale\"").build())));

    assertEquals(List.of("a.json"), response.deleted().stream().map(deleted -> deleted.key()).toList());
    assertEquals(1, response.errors().size());
    assertEquals("b.json", response.errors().get(0).key());
    assertEquals("PreconditionFailed", response.errors().get(0).code());
    assertEquals("b", s3.getObjectAsBytes(b -> b.bucket(bucket).key("b.json")).asUtf8String());
  }

}
