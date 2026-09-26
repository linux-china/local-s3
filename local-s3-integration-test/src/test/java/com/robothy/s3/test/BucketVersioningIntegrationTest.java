package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.MFADeleteStatus;
import software.amazon.awssdk.services.s3.model.MFADelete;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@code PutBucketVersioning} reads its configuration like Amazon S3 does, and {@code ListObjectVersions} pages through
 * the versions of a suspended bucket with the markers that Amazon S3 answers.
 */
public class BucketVersioningIntegrationTest {

  @Test
  @LocalS3
  void malformedStatusIsRejected(S3Client s3) {
    String bucket = "malformed-status";
    s3.createBucket(b -> b.bucket(bucket));

    for (String status : new String[] {"enabled", "Disabled", "Foo"}) {
      S3Exception e = assertThrows(S3Exception.class, () -> s3.putBucketVersioning(b -> b.bucket(bucket)
          .versioningConfiguration(c -> c.status(status))), status);
      assertEquals(400, e.statusCode());
      assertEquals("MalformedXML", e.awsErrorDetails().errorCode());
    }
    // The bucket is left as it was: never versioned.
    assertNull(s3.getBucketVersioning(b -> b.bucket(bucket)).status());
  }

  @Test
  @LocalS3
  void configurationWithoutStatusKeepsTheVersioningOfTheBucket(S3Client s3) {
    String bucket = "no-status";
    s3.createBucket(b -> b.bucket(bucket));

    s3.putBucketVersioning(b -> b.bucket(bucket).versioningConfiguration(c -> { }));
    assertNull(s3.getBucketVersioning(b -> b.bucket(bucket)).status());
    s3.putObject(b -> b.bucket(bucket).key("k"), RequestBody.fromString("v"));
    assertNull(s3.headObject(b -> b.bucket(bucket).key("k")).versionId(), "the bucket is still never versioned");

    s3.putBucketVersioning(b -> b.bucket(bucket)
        .versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED)));
    s3.putBucketVersioning(b -> b.bucket(bucket).versioningConfiguration(c -> c.mfaDelete(MFADelete.DISABLED)));
    GetBucketVersioningResponse versioning = s3.getBucketVersioning(b -> b.bucket(bucket));
    assertEquals(BucketVersioningStatus.ENABLED, versioning.status());
    assertEquals(MFADeleteStatus.DISABLED, versioning.mfaDelete());
  }

  @Test
  @LocalS3
  void mfaDeleteIsAnsweredBack(S3Client s3) {
    String bucket = "mfa-delete";
    s3.createBucket(b -> b.bucket(bucket));
    assertNull(s3.getBucketVersioning(b -> b.bucket(bucket)).mfaDelete());

    s3.putBucketVersioning(b -> b.bucket(bucket).mfa("arn:aws:iam::123456789012:mfa/device 123456")
        .versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED).mfaDelete(MFADelete.ENABLED)));
    GetBucketVersioningResponse versioning = s3.getBucketVersioning(b -> b.bucket(bucket));
    assertEquals(BucketVersioningStatus.ENABLED, versioning.status());
    assertEquals(MFADeleteStatus.ENABLED, versioning.mfaDelete());

    // No MFA device is asked for: a version is still deleted without one.
    String versionId = s3.putObject(b -> b.bucket(bucket).key("k"), RequestBody.fromString("v")).versionId();
    s3.deleteObject(b -> b.bucket(bucket).key("k").versionId(versionId));
    assertTrue(s3.listObjectVersions(b -> b.bucket(bucket)).versions().isEmpty());
  }

  @Test
  @LocalS3
  void listObjectVersionsPagesThroughTheNullVersion(S3Client s3) {
    String bucket = "paged-versions";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putBucketVersioning(b -> b.bucket(bucket)
        .versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED)));
    s3.putObject(b -> b.bucket(bucket).key("a"), RequestBody.fromString("1"));
    s3.putBucketVersioning(b -> b.bucket(bucket)
        .versioningConfiguration(c -> c.status(BucketVersioningStatus.SUSPENDED)));
    s3.putObject(b -> b.bucket(bucket).key("a"), RequestBody.fromString("2"));
    s3.putObject(b -> b.bucket(bucket).key("b"), RequestBody.fromString("3"));

    ListObjectVersionsResponse first = s3.listObjectVersions(b -> b.bucket(bucket).maxKeys(1));
    assertTrue(first.isTruncated());
    assertEquals("null", first.versions().get(0).versionId());
    assertEquals("null", first.nextVersionIdMarker());

    List<ObjectVersion> all = new ArrayList<>();
    s3.listObjectVersionsPaginator(b -> b.bucket(bucket).maxKeys(1)).versions().forEach(all::add);
    assertEquals(List.of("a", "a", "b"), all.stream().map(ObjectVersion::key).toList());
    assertEquals("null", all.get(0).versionId());
    assertFalse("null".equals(all.get(1).versionId()), "the version stored while versioning was enabled");
    assertEquals("null", all.get(2).versionId());

    // A page that ends with the last version isn't truncated.
    ListObjectVersionsResponse exact = s3.listObjectVersions(b -> b.bucket(bucket).maxKeys(3));
    assertFalse(exact.isTruncated());
    // A key marker alone lists the keys after it.
    assertEquals(List.of("b"), s3.listObjectVersions(b -> b.bucket(bucket).keyMarker("a")).versions().stream()
        .map(ObjectVersion::key).toList());
  }

}
