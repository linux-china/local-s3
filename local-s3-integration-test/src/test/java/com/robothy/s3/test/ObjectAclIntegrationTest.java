package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.robothy.s3.jupiter.LocalS3;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AccessControlPolicy;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAclRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAclResponse;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.Grantee;
import software.amazon.awssdk.services.s3.model.Owner;
import software.amazon.awssdk.services.s3.model.Permission;
import software.amazon.awssdk.services.s3.model.PutObjectAclRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.Type;

public class ObjectAclIntegrationTest {

  @LocalS3
  @Test
  void objectAcl(S3Client s3) {
    String bucketName = "my-bucket";
    String key = "key1";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(key).build(), RequestBody.fromString("Hello"));

    GetObjectAclResponse defaultAcl = s3.getObjectAcl(
        GetObjectAclRequest.builder().bucket(bucketName).key(key).build());
    assertNotNull(defaultAcl.owner());
    assertEquals("LocalS3", defaultAcl.owner().displayName());

    Owner owner = Owner.builder().displayName("Bob").id("002").build();
    Grant grant = Grant.builder()
        .grantee(Grantee.builder().id("002").type(Type.CANONICAL_USER).build())
        .permission(Permission.FULL_CONTROL)
        .build();
    s3.putObjectAcl(PutObjectAclRequest.builder()
        .bucket(bucketName)
        .key(key)
        .accessControlPolicy(AccessControlPolicy.builder()
            .owner(owner)
            .grants(List.of(grant))
            .build())
        .build());

    GetObjectAclResponse updatedAcl = s3.getObjectAcl(
        GetObjectAclRequest.builder().bucket(bucketName).key(key).build());
    assertEquals(owner.id(), updatedAcl.owner().id());
    assertEquals(1, updatedAcl.grants().size());
    assertEquals(Permission.FULL_CONTROL, updatedAcl.grants().get(0).permission());
  }

}
