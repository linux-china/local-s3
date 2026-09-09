package com.robothy.s3.core.service;

import com.robothy.s3.core.annotations.BucketChanged;
import com.robothy.s3.core.annotations.BucketReadLock;
import com.robothy.s3.core.annotations.BucketWriteLock;
import com.robothy.s3.core.asserionts.BucketAssertions;
import com.robothy.s3.core.asserionts.ObjectAssertions;
import com.robothy.s3.core.exception.MethodNotAllowedException;
import com.robothy.s3.core.model.answers.GetObjectAclAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.util.VersionedObjectUtils;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Owner;
import java.util.Collections;
import java.util.Objects;

/**
 * Object access control service.
 */
public interface ObjectAclService extends LocalS3MetadataApplicable {

  /**
   * Put ACL to the specified versioned object.
   *
   * @param bucketName bucket name.
   * @param key object key.
   * @param versionId version ID.
   * @param acl new ACL.
   * @return version ID where the new ACL applies to.
   */
  @BucketChanged
  @BucketWriteLock
  default String putObjectAcl(String bucketName, String key, String versionId, AccessControlPolicy acl) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
    VersionedObjectMetadata versionedObjectMetadata = VersionedObjectUtils.getVersionedObjectMetadata(objectMetadata, versionId);
    if (versionedObjectMetadata.isDeleted()) {
      throw new MethodNotAllowedException("Cannot put object ACL to a delete marker.");
    }

    versionedObjectMetadata.setAcl(acl);
    return VersionedObjectUtils.resolveReturnedVersion(objectMetadata, versionId);
  }

  /**
   * Get ACL from the specified versioned object.
   *
   * @param bucketName bucket name.
   * @param key object key.
   * @param versionId version ID.
   * @return versioned object ACL.
   */
  @BucketReadLock
  default GetObjectAclAns getObjectAcl(String bucketName, String key, String versionId) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
    VersionedObjectMetadata versionedObjectMetadata = VersionedObjectUtils.getVersionedObjectMetadata(objectMetadata, versionId);
    if (versionedObjectMetadata.isDeleted()) {
      throw new MethodNotAllowedException("Cannot get object ACL from a delete marker.");
    }

    AccessControlPolicy acl = versionedObjectMetadata.getAcl().orElseGet(() -> AccessControlPolicy.builder()
        .owner(new Owner("LocalS3", "001"))
        .grants(Collections.emptyList())
        .build());
    if (Objects.isNull(acl.getOwner())) {
      acl.setOwner(new Owner("LocalS3", "001"));
    }
    if (Objects.isNull(acl.getGrants())) {
      acl.setGrants(Collections.emptyList());
    }

    return GetObjectAclAns.builder()
        .acl(acl)
        .versionId(VersionedObjectUtils.resolveReturnedVersion(objectMetadata, versionId))
        .build();
  }

}
