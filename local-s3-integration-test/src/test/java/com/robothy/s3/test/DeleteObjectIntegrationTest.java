package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;

public class DeleteObjectIntegrationTest {

  @LocalS3
  @Test
  void testDeleteObject(S3Client s3) {
    String versioningSuspendedBucket = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(versioningSuspendedBucket).build());
    s3.putBucketVersioning(PutBucketVersioningRequest.builder()
        .bucket(versioningSuspendedBucket)
        .versioningConfiguration(VersioningConfiguration.builder()
            .status(BucketVersioningStatus.SUSPENDED)
            .build())
        .build());

    assertDoesNotThrow(() -> s3.deleteObject(DeleteObjectRequest.builder()
        .bucket(versioningSuspendedBucket)
        .key("a.txt")
        .build()));

    ListObjectVersionsResponse versionListing = s3.listObjectVersions(ListObjectVersionsRequest.builder()
        .bucket(versioningSuspendedBucket)
        .prefix("a.txt")
        .build());
    assertEquals(1, versionListing.deleteMarkers().size());

    assertDoesNotThrow(() -> s3.deleteObject(DeleteObjectRequest.builder()
        .bucket(versioningSuspendedBucket)
        .key("a.txt")
        .build()));

    ListObjectVersionsResponse versionListing1 = s3.listObjectVersions(ListObjectVersionsRequest.builder()
        .bucket(versioningSuspendedBucket)
        .prefix("a.txt")
        .build());
    assertEquals(1, versionListing1.deleteMarkers().size());

    assertDoesNotThrow(() -> s3.deleteObject(DeleteObjectRequest.builder()
        .bucket(versioningSuspendedBucket)
        .key("a.txt")
        .versionId("null")
        .build()));

    ListObjectVersionsResponse versionListing2 = s3.listObjectVersions(ListObjectVersionsRequest.builder()
        .bucket(versioningSuspendedBucket)
        .prefix("a.txt")
        .build());
    assertEquals(0, versionListing2.versions().size());

    String unVersionedBucket = "un-versioned-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(unVersionedBucket).build());
    assertDoesNotThrow(() -> s3.deleteObject(DeleteObjectRequest.builder()
        .bucket(unVersionedBucket)
        .key("not-exists.txt")
        .build()));

    s3.putObject(PutObjectRequest.builder()
        .bucket(unVersionedBucket)
        .key("a.txt")
        .build(), 
        RequestBody.fromString("Hello"));

    ListObjectVersionsResponse versionListing3 = s3.listObjectVersions(ListObjectVersionsRequest.builder()
        .bucket(unVersionedBucket)
        .prefix("a.txt")
        .build());
    assertEquals(1, versionListing3.versions().size());

    s3.deleteObject(DeleteObjectRequest.builder()
        .bucket(unVersionedBucket)
        .key("a.txt")
        .build());

    ListObjectVersionsResponse versionListing4 = s3.listObjectVersions(ListObjectVersionsRequest.builder()
        .bucket(unVersionedBucket)
        .prefix("a.txt")
        .build());
    assertEquals(0, versionListing4.versions().size());

    assertDoesNotThrow(() -> s3.deleteBucket(DeleteBucketRequest.builder()
        .bucket(unVersionedBucket)
        .build()));
  }

  /**
   * Deleting a version that is already gone succeeds, like Amazon S3, so that the retries and the concurrent cleanups
   * of a client, e.g. Iceberg, Delta Lake or DuckLake on a versioned bucket, don't report it as an error.
   */
  @LocalS3
  @Test
  void deletingAVersionThatIsGoneIsIdempotent(S3Client s3) {
    String bucket = "versioned-bucket";
    s3.createBucket(request -> request.bucket(bucket));
    s3.putBucketVersioning(request -> request.bucket(bucket)
        .versioningConfiguration(configuration -> configuration.status(BucketVersioningStatus.ENABLED)));
    String versionId = s3.putObject(request -> request.bucket(bucket).key("a.txt"), RequestBody.fromString("Hello"))
        .versionId();

    s3.deleteObject(request -> request.bucket(bucket).key("a.txt").versionId(versionId));
    DeleteObjectResponse again = s3.deleteObject(request -> request.bucket(bucket).key("a.txt").versionId(versionId));
    assertEquals(204, again.sdkHttpResponse().statusCode());
    assertEquals(versionId, again.versionId());

    DeleteObjectsResponse batch = s3.deleteObjects(request -> request.bucket(bucket).delete(delete -> delete.objects(
        ObjectIdentifier.builder().key("a.txt").versionId(versionId).build(),
        ObjectIdentifier.builder().key("never-existed.txt").versionId(versionId).build())));
    assertTrue(batch.errors().isEmpty());
    assertEquals(2, batch.deleted().size());
    assertEquals(versionId, batch.deleted().get(0).versionId());

    // A precondition still fails on a key that holds no version.
    assertThrows(NoSuchKeyException.class, () -> s3.deleteObject(request -> request.bucket(bucket).key("a.txt")
        .versionId(versionId).ifMatch("\"d41d8cd98f00b204e9800998ecf8427e\"")));
  }
}
