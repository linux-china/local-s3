package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.VersionedObjectNotExistException;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The version IDs that the requests which address a version are answered for: a malformed one is an invalid argument,
 * and the ID that the null version is held by inside LocalS3 names no version.
 */
class VersionIdArgumentTest extends LocalS3ServiceTestBase {

  @ParameterizedTest
  @MethodSource("localS3Services")
  void malformedVersionIdIsAnInvalidArgument(BucketService bucketService, ObjectService objectService) {
    String bucket = "versioned-bucket";
    bucketService.createBucket(bucket);
    bucketService.setVersioningEnabled(bucket, true);
    String versionId = put(objectService, bucket, "a");

    for (String malformed : new String[] {"abc", "12a", "", "-", "１２"}) {
      assertThrows(LocalS3InvalidArgumentException.class, () -> objectService.getObject(bucket, "a",
          GetObjectOptions.builder().versionId(malformed).build()), malformed);
      assertThrows(LocalS3InvalidArgumentException.class,
          () -> objectService.getObjectTagging(bucket, "a", malformed), malformed);
      assertThrows(LocalS3InvalidArgumentException.class,
          () -> objectService.getObjectAcl(bucket, "a", malformed), malformed);
      // A delete never fails on its version ID.
      assertDoesNotThrow(() -> objectService.deleteObject(bucket, "a", malformed), malformed);
    }

    // A well-formed version ID that names no version doesn't exist.
    String missing = String.valueOf(Long.parseLong(versionId) + 1);
    assertThrows(VersionedObjectNotExistException.class, () -> objectService.getObject(bucket, "a",
        GetObjectOptions.builder().versionId(missing).build()));
    assertThrows(VersionedObjectNotExistException.class, () -> objectService.getObjectTagging(bucket, "a", missing));
    assertEquals(versionId, objectService.getObjectTagging(bucket, "a", versionId).getVersionId());
  }

  @ParameterizedTest
  @MethodSource("localS3Services")
  void versionIdOtherThanNullIsInvalidForABucketThatWasNeverVersioned(BucketService bucketService,
                                                                        ObjectService objectService) {
    String bucket = "unversioned-bucket";
    bucketService.createBucket(bucket);
    put(objectService, bucket, "a");

    assertThrows(LocalS3InvalidArgumentException.class, () -> objectService.getObject(bucket, "a",
        GetObjectOptions.builder().versionId("123").build()));
    assertThrows(LocalS3InvalidArgumentException.class, () -> objectService.getObjectTagging(bucket, "a", "123"));
    assertThrows(LocalS3InvalidArgumentException.class, () -> objectService.putObjectTagging(bucket, "a", "123",
        new String[][] {{"k", "v"}}));
    assertThrows(LocalS3InvalidArgumentException.class, () -> objectService.getObjectAcl(bucket, "a", "123"));
    assertDoesNotThrow(() -> objectService.getObjectTagging(bucket, "a", ObjectMetadata.NULL_VERSION));
  }

  @ParameterizedTest
  @MethodSource("localS3Services")
  void internalIdOfTheNullVersionNamesNoVersion(BucketService bucketService, ObjectService objectService) {
    String bucket = "suspended-bucket";
    bucketService.createBucket(bucket);
    bucketService.setVersioningEnabled(bucket, false);
    put(objectService, bucket, "a");
    ObjectMetadata objectMetadata = BucketAssertions.assertBucketExists(bucketService.localS3Metadata(), bucket)
        .getObjectMetadata("a").orElseThrow();
    String internalId = objectMetadata.getVirtualVersion().orElseThrow();

    assertThrows(VersionedObjectNotExistException.class, () -> objectService.getObject(bucket, "a",
        GetObjectOptions.builder().versionId(internalId).build()));
    assertThrows(VersionedObjectNotExistException.class,
        () -> objectService.getObjectTagging(bucket, "a", internalId));
    assertThrows(VersionedObjectNotExistException.class, () -> objectService.putObjectTagging(bucket, "a", internalId,
        new String[][] {{"k", "v"}}));
    assertThrows(VersionedObjectNotExistException.class, () -> objectService.getObjectAcl(bucket, "a", internalId));

    // Deleting it deletes nothing.
    objectService.deleteObject(bucket, "a", internalId);
    assertEquals(ObjectMetadata.NULL_VERSION, objectService.getObject(bucket, "a", GetObjectOptions.builder()
        .versionId(ObjectMetadata.NULL_VERSION).build()).getVersionId());
  }

  private static String put(ObjectService objectService, String bucket, String key) {
    return objectService.putObject(bucket, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream("Robothy".getBytes()))
        .size(7)
        .build()).getVersionId();
  }

}
