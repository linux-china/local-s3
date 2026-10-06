package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Owner;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * See {@code docs/semantics.md#access-control-lists}.
 */
public interface BucketAclService extends LocalS3MetadataApplicable {

  default void putBucketAcl(String bucketName, AccessControlPolicy acl) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setAcl(acl);
    });
  }

  default AccessControlPolicy getBucketAcl(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);

      Owner defaultOwner = new Owner("LocalS3", "001");
      Optional<AccessControlPolicy> aclOpt = bucketMetadata.getAcl();
      if (aclOpt.isEmpty()) {
        return AccessControlPolicy.builder()
            .owner(defaultOwner)
            .grants(Collections.emptyList())
            .build();
      } else {
        AccessControlPolicy acl = aclOpt.get();
        if (Objects.isNull(acl.getOwner())) {
          acl.setOwner(defaultOwner);
        }

        if (Objects.isNull(acl.getGrants())) {
          acl.setGrants(Collections.emptyList());
        }

        return acl;
      }
    });
  }

}
