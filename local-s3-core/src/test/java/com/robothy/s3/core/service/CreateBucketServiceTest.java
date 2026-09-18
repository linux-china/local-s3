package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.exception.BucketAlreadyExistsException;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.util.BucketPublicAccess;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Owner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CreateBucketServiceTest extends LocalS3ServiceTestBase {

  @MethodSource("bucketServices")
  @ParameterizedTest
  void createBucket(BucketService bucketService) {
    Bucket bucket1 = bucketService.createBucket("bucket1");
    assertNotNull(bucket1);
    assertEquals("bucket1", bucket1.getName());
    assertTrue(System.currentTimeMillis() - bucket1.getCreationDate() < 2000);
    assertThrows(BucketAlreadyExistsException.class, () -> bucketService.createBucket("bucket1"));

    Bucket bucket2 = bucketService.createBucket("bucket2", "my-region");
    assertNotNull(bucket2);
    assertEquals("bucket2", bucket2.getName());
    assertEquals("my-region", bucket2.getRegion().orElse(null));
  }

  @MethodSource("bucketServices")
  @ParameterizedTest
  void createBucketSetsPrivateWriteAndPublicReadAcl(BucketService bucketService) {
    Bucket bucket = bucketService.createBucket("public-read-bucket");

    AccessControlPolicy acl = bucketService.getBucketAcl(bucket.getName());
    assertEquals(Owner.DEFAULT_OWNER, acl.getOwner());
    assertEquals(2, acl.getGrants().size());

    Grant ownerGrant = acl.getGrants().get(0);
    assertAll(
        () -> assertEquals("CanonicalUser", ownerGrant.getGrantee().getType()),
        () -> assertEquals(Owner.DEFAULT_OWNER.getId(), ownerGrant.getGrantee().getId()),
        () -> assertEquals("FULL_CONTROL", ownerGrant.getPermission()));

    Grant publicGrant = acl.getGrants().get(1);
    assertAll(
        () -> assertEquals("Group", publicGrant.getGrantee().getType()),
        () -> assertEquals(BucketPublicAccess.ALL_USERS_GROUP, publicGrant.getGrantee().getUri()),
        () -> assertEquals("READ", publicGrant.getPermission()));
    assertTrue(bucketService.allowsAnonymousRead(bucket.getName(), "object-key"));
  }

}