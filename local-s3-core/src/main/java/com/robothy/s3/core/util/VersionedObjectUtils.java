package com.robothy.s3.core.util;

import com.robothy.s3.core.assertions.VersionedObjectAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import java.util.Objects;

public class VersionedObjectUtils {

  /**
   * Get versioned object by version ID.
   *
   * @param objectMetadata object metadata that contains the versioned object.
   * @param inputVersionId version ID. May be {@code null}.
   * @return versioned object metadata of the specified version ID.
   */
  public static VersionedObjectMetadata getVersionedObjectMetadata(ObjectMetadata objectMetadata, String inputVersionId) {
    if (Objects.isNull(inputVersionId)) {
      return objectMetadata.getLatest();
    }

    if (ObjectMetadata.NULL_VERSION.equals(inputVersionId)) {
      return VersionedObjectAssertions.assertVirtualVersionExist(objectMetadata);
    }

    return VersionedObjectAssertions.assertVersionedObjectExist(objectMetadata, inputVersionId);
  }

  /**
   * Resolve the version to report for an object of {@code bucketMetadata}.
   *
   * <p>An object of a bucket that was never versioned has no version at all, so {@code null} is returned and
   * the caller reports no version, like Amazon S3 does. Once versioning is enabled or suspended, an object
   * that was stored before does have a version, the {@linkplain ObjectMetadata#NULL_VERSION} that Amazon S3
   * reports as well.
   *
   * @param bucketMetadata metadata of the bucket that holds the object.
   * @param objectMetadata object metadata.
   * @param inputVersionId input version ID. May be {@code null}.
   * @return the version to report; {@code null} if the bucket was never versioned.
   */
  public static String resolveReturnedVersion(BucketMetadata bucketMetadata, ObjectMetadata objectMetadata,
                                              String inputVersionId) {
    String version = resolveReturnedVersion(objectMetadata, inputVersionId);
    if (Objects.isNull(bucketMetadata.getVersioningEnabled()) && ObjectMetadata.NULL_VERSION.equals(version)) {
      return null;
    }
    return version;
  }

  /**
   * Resolve the returned version if the input version ID is null; or else return the input version ID.
   *
   * @param objectMetadata object metadata.
   * @param inputVersionId input version ID. May be {@code null}.
   * @return resolved return version.
   */
  public static String resolveReturnedVersion(ObjectMetadata objectMetadata, String inputVersionId) {
    if (Objects.isNull(inputVersionId)) {
      if (objectMetadata.getVirtualVersion().map(virtualVersion -> virtualVersion.equals(objectMetadata.getLatestVersion())).orElse(false)) {
        return ObjectMetadata.NULL_VERSION;
      }

      return objectMetadata.getLatestVersion();
    }

    return inputVersionId;
  }

}