package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.core.exception.MethodNotAllowedException;
import com.robothy.s3.core.model.answers.GetObjectAclAns;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Owner;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ObjectAclServiceTest extends LocalS3ServiceTestBase {

  @MethodSource("localS3Services")
  @ParameterizedTest
  void testObjectAcl(BucketService bucketService, ObjectService objectService) {
    String bucketName = "my-bucket";
    String key = "key1";
    bucketService.createBucket(bucketName);
    bucketService.setVersioningEnabled(bucketName, false);
    objectService.putObject(bucketName, key, putOptions("Hello"));

    GetObjectAclAns defaultAcl = objectService.getObjectAcl(bucketName, key, null);
    assertNotNull(defaultAcl.getAcl().getOwner());
    assertNotNull(defaultAcl.getAcl().getGrants());
    assertEquals(ObjectMetadata.NULL_VERSION, defaultAcl.getVersionId());

    AccessControlPolicy acl = AccessControlPolicy.builder()
        .owner(new Owner("Bob", "002"))
        .grants(List.of(new Grant()))
        .build();
    assertEquals(ObjectMetadata.NULL_VERSION, objectService.putObjectAcl(bucketName, key, null, acl));
    assertEquals(acl, objectService.getObjectAcl(bucketName, key, null).getAcl());

    objectService.deleteObject(bucketName, key);
    assertThrows(MethodNotAllowedException.class, () -> objectService.getObjectAcl(bucketName, key, null));
    assertThrows(MethodNotAllowedException.class, () -> objectService.putObjectAcl(bucketName, key, null, acl));

    bucketService.setVersioningEnabled(bucketName, true);
    PutObjectAns firstVersion = objectService.putObject(bucketName, key, putOptions("First"));
    objectService.putObjectAcl(bucketName, key, firstVersion.getVersionId(), acl);
    PutObjectAns secondVersion = objectService.putObject(bucketName, key, putOptions("Second"));

    GetObjectAclAns latestAcl = objectService.getObjectAcl(bucketName, key, null);
    assertEquals(secondVersion.getVersionId(), latestAcl.getVersionId());
    assertEquals(0, latestAcl.getAcl().getGrants().size());

    GetObjectAclAns firstVersionAcl = objectService.getObjectAcl(bucketName, key, firstVersion.getVersionId());
    assertEquals(firstVersion.getVersionId(), firstVersionAcl.getVersionId());
    assertEquals(acl, firstVersionAcl.getAcl());
  }

  private PutObjectOptions putOptions(String content) {
    byte[] bytes = content.getBytes();
    return PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes))
        .contentType("text/plain")
        .size(bytes.length)
        .build();
  }

}
