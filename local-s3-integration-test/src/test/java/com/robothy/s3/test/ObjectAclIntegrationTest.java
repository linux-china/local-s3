package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.jupiter.LocalS3;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AccessControlPolicy;
import software.amazon.awssdk.services.s3.model.BucketCannedACL;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketAclRequest;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.GetObjectAclRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAclResponse;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.Grantee;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.Owner;
import software.amazon.awssdk.services.s3.model.Permission;
import software.amazon.awssdk.services.s3.model.PutBucketAclRequest;
import software.amazon.awssdk.services.s3.model.PutObjectAclRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
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

  /**
   * A canned ACL or grant headers, without a body, like {@code aws s3api put-object-acl --acl public-read} sends.
   */
  @LocalS3
  @Test
  void objectAclOfHeaders(S3Client s3) {
    String bucketName = "my-bucket";
    String key = "key1";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(key).build(), RequestBody.fromString("Hello"));

    s3.putObjectAcl(PutObjectAclRequest.builder().bucket(bucketName).key(key).acl(ObjectCannedACL.PUBLIC_READ).build());
    GetObjectAclResponse cannedAcl = s3.getObjectAcl(GetObjectAclRequest.builder().bucket(bucketName).key(key).build());
    assertEquals("LocalS3", cannedAcl.owner().displayName());
    assertEquals(2, cannedAcl.grants().size());
    assertEquals(Permission.FULL_CONTROL, cannedAcl.grants().get(0).permission());
    assertEquals(cannedAcl.owner().id(), cannedAcl.grants().get(0).grantee().id());
    assertEquals(Type.GROUP, cannedAcl.grants().get(1).grantee().type());
    assertEquals("http://acs.amazonaws.com/groups/global/AllUsers", cannedAcl.grants().get(1).grantee().uri());
    assertEquals(Permission.READ, cannedAcl.grants().get(1).permission());

    s3.putObjectAcl(PutObjectAclRequest.builder().bucket(bucketName).key(key)
        .grantRead("id=\"111\", uri=\"http://acs.amazonaws.com/groups/global/AuthenticatedUsers\"")
        .grantFullControl("id=\"222\"")
        .build());
    GetObjectAclResponse grantAcl = s3.getObjectAcl(GetObjectAclRequest.builder().bucket(bucketName).key(key).build());
    assertEquals(3, grantAcl.grants().size());
    assertEquals("222", grantAcl.grants().get(0).grantee().id());
    assertEquals(Permission.FULL_CONTROL, grantAcl.grants().get(0).permission());
    assertEquals("111", grantAcl.grants().get(1).grantee().id());
    assertEquals(Type.GROUP, grantAcl.grants().get(2).grantee().type());

    S3Exception unknown = assertThrows(S3Exception.class, () -> s3.putObjectAcl(PutObjectAclRequest.builder()
        .bucket(bucketName).key(key).acl("log-delivery-write").build()));
    assertEquals(400, unknown.statusCode());
    assertEquals("InvalidArgument", unknown.awsErrorDetails().errorCode());

    S3Exception both = assertThrows(S3Exception.class, () -> s3.putObjectAcl(PutObjectAclRequest.builder()
        .bucket(bucketName).key(key).acl(ObjectCannedACL.PRIVATE).grantRead("id=\"111\"").build()));
    assertEquals("InvalidRequest", both.awsErrorDetails().errorCode());

    S3Exception none = assertThrows(S3Exception.class, () -> s3.putObjectAcl(PutObjectAclRequest.builder()
        .bucket(bucketName).key(key).build()));
    assertEquals(400, none.statusCode());
    assertEquals("MissingSecurityHeader", none.awsErrorDetails().errorCode());
  }

  @LocalS3
  @Test
  void bucketAclOfHeaders(S3Client s3) {
    String bucketName = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    s3.putBucketAcl(PutBucketAclRequest.builder().bucket(bucketName).acl(BucketCannedACL.AUTHENTICATED_READ).build());
    GetBucketAclResponse acl = s3.getBucketAcl(GetBucketAclRequest.builder().bucket(bucketName).build());
    assertEquals("LocalS3", acl.owner().displayName());
    assertEquals(2, acl.grants().size());
    assertEquals("http://acs.amazonaws.com/groups/global/AuthenticatedUsers", acl.grants().get(1).grantee().uri());

    s3.putBucketAcl(PutBucketAclRequest.builder().bucket(bucketName).grantWrite("id=\"333\"").build());
    acl = s3.getBucketAcl(GetBucketAclRequest.builder().bucket(bucketName).build());
    assertEquals(1, acl.grants().size());
    assertEquals(Permission.WRITE, acl.grants().get(0).permission());
  }

}
