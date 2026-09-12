package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.robothy.s3.jupiter.LocalS3;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Response;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

/**
 * What the operations report as the version of an object, which travels in the {@code x-amz-version-id}
 * header. There are three cases, and LocalS3 used to answer the first two the same way:
 *
 * <ul>
 *   <li>a bucket that was never versioned: no version at all, so no header;</li>
 *   <li>a bucket whose versioning is enabled or suspended, holding an object stored before that: the version
 *       is the string {@code "null"}, which is a value and is sent;</li>
 *   <li>an object stored while versioning is enabled: its generated version ID.</li>
 * </ul>
 */
public class VersionIdHeaderIntegrationTest {

  private static final String KEY = "k.txt";

  private static Optional<String> versionIdHeader(S3Response response) {
    return response.sdkHttpResponse().firstMatchingHeader("x-amz-version-id");
  }

  private static void putObject(S3Client s3, String bucket) {
    s3.putObject(b -> b.bucket(bucket).key(KEY), RequestBody.fromString("hello"));
  }

  /**
   * An object of a bucket that was never versioned has no version, so no operation reports one.
   */
  @Test
  @LocalS3
  void neverVersionedBucketReportsNoVersion(S3Client s3) {
    String bucket = "never-versioned";
    s3.createBucket(b -> b.bucket(bucket));
    putObject(s3, bucket);

    assertNull(s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)).response().versionId());
    assertNull(s3.headObject(b -> b.bucket(bucket).key(KEY)).versionId());
    // GetObjectAclResponse doesn't model the header, so it is read off the raw response.
    assertEquals(Optional.empty(), versionIdHeader(s3.getObjectAcl(b -> b.bucket(bucket).key(KEY))));
    assertNull(s3.getObjectTagging(b -> b.bucket(bucket).key(KEY)).versionId());
    assertNull(s3.putObjectTagging(b -> b.bucket(bucket).key(KEY)
        .tagging(Tagging.builder().tagSet(Tag.builder().key("k").value("v").build()).build())).versionId());
    assertNull(s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(KEY)
        .destinationBucket(bucket).destinationKey("copy.txt")).versionId());
    assertNull(s3.deleteObject(b -> b.bucket(bucket).key("copy.txt")).versionId());
  }

  /**
   * Once versioning is suspended, the object stored before it has the "null" version, which is reported.
   */
  @Test
  @LocalS3
  void suspendedBucketReportsTheNullVersionOfAnObjectStoredBefore(S3Client s3) {
    String bucket = "suspended";
    s3.createBucket(b -> b.bucket(bucket));
    putObject(s3, bucket);
    s3.putBucketVersioning(b -> b.bucket(bucket)
        .versioningConfiguration(c -> c.status(BucketVersioningStatus.SUSPENDED)));

    assertEquals("null", s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)).response().versionId());
    assertEquals("null", s3.headObject(b -> b.bucket(bucket).key(KEY)).versionId());
    assertEquals(Optional.of("null"), versionIdHeader(s3.getObjectAcl(b -> b.bucket(bucket).key(KEY))));
    assertEquals("null", s3.getObjectTagging(b -> b.bucket(bucket).key(KEY)).versionId());
  }

  /**
   * An object stored while versioning is enabled reports the version ID it was given.
   */
  @Test
  @LocalS3
  void versionedBucketReportsTheVersionOfTheObject(S3Client s3) {
    String bucket = "versioned";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putBucketVersioning(b -> b.bucket(bucket)
        .versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED)));
    String versionId = s3.putObject(b -> b.bucket(bucket).key(KEY), RequestBody.fromString("hello")).versionId();

    assertNotNull(versionId);
    assertEquals(versionId, s3.getObjectAsBytes(b -> b.bucket(bucket).key(KEY)).response().versionId());
    assertEquals(versionId, s3.headObject(b -> b.bucket(bucket).key(KEY)).versionId());
    assertEquals(Optional.of(versionId), versionIdHeader(s3.getObjectAcl(b -> b.bucket(bucket).key(KEY))));
    assertEquals(versionId, s3.getObjectTagging(b -> b.bucket(bucket).key(KEY)).versionId());
  }

}
