package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.jupiter.LocalS3;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectAttributes;
import software.amazon.awssdk.services.s3.model.ObjectStorageClass;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * The {@code x-amz-storage-class} that an object is stored with is kept and answered, so that a client that decides
 * what to read by the storage class, e.g. an S3A or Spark job that skips {@code GLACIER} objects, sees the one it
 * stored. The object is read like a {@code STANDARD} one whatever its storage class.
 */
class StorageClassIntegrationTest {

  @Test
  @LocalS3
  void theStorageClassOfAnObjectIsAnswered(S3Client s3) {
    String bucket = "storage-class-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key("cold.txt").storageClass(StorageClass.GLACIER),
        RequestBody.fromString("Hello"));
    s3.putObject(b -> b.bucket(bucket).key("hot.txt"), RequestBody.fromString("Hello"));

    assertEquals(StorageClass.GLACIER, s3.headObject(b -> b.bucket(bucket).key("cold.txt")).storageClass());
    // Read without a restore.
    assertEquals(StorageClass.GLACIER,
        s3.getObjectAsBytes(b -> b.bucket(bucket).key("cold.txt")).response().storageClass());
    assertEquals("Hello", s3.getObjectAsBytes(b -> b.bucket(bucket).key("cold.txt")).asUtf8String());
    // STANDARD isn't answered in the headers, like Amazon S3 does.
    assertNull(s3.headObject(b -> b.bucket(bucket).key("hot.txt")).storageClass());

    assertEquals(List.of(ObjectStorageClass.GLACIER, ObjectStorageClass.STANDARD),
        s3.listObjectsV2(b -> b.bucket(bucket)).contents().stream().map(S3Object::storageClass).toList());
    assertEquals(List.of(ObjectStorageClass.GLACIER, ObjectStorageClass.STANDARD),
        s3.listObjects(b -> b.bucket(bucket)).contents().stream().map(S3Object::storageClass).toList());
    // The SDK models only STANDARD for a version, and reads any other storage class as a string.
    assertEquals(List.of("GLACIER", "STANDARD"), s3.listObjectVersions(b -> b.bucket(bucket)).versions().stream()
        .map(ObjectVersion::storageClassAsString).toList());
    assertEquals(StorageClass.GLACIER, s3.getObjectAttributes(b -> b.bucket(bucket).key("cold.txt")
        .objectAttributes(ObjectAttributes.STORAGE_CLASS)).storageClass());
  }

  @Test
  @LocalS3
  void aCopyHasTheStorageClassOfTheRequest(S3Client s3) {
    String bucket = "storage-class-copy-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key("a.txt").storageClass(StorageClass.GLACIER),
        RequestBody.fromString("Hello"));

    // Not copied from the source, whatever the metadata directive.
    s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("a.txt").destinationBucket(bucket).destinationKey("b.txt"));
    assertNull(s3.headObject(b -> b.bucket(bucket).key("b.txt")).storageClass());

    s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("a.txt").destinationBucket(bucket).destinationKey("c.txt")
        .metadataDirective(MetadataDirective.REPLACE).storageClass(StorageClass.STANDARD_IA));
    assertEquals(StorageClass.STANDARD_IA, s3.headObject(b -> b.bucket(bucket).key("c.txt")).storageClass());

    // An object copied onto itself changes its storage class.
    s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("c.txt").destinationBucket(bucket).destinationKey("c.txt")
        .storageClass(StorageClass.DEEP_ARCHIVE));
    assertEquals(StorageClass.DEEP_ARCHIVE, s3.headObject(b -> b.bucket(bucket).key("c.txt")).storageClass());
  }

  @Test
  @LocalS3
  void anUploadStoresTheObjectWithItsStorageClass(S3Client s3) {
    String bucket = "storage-class-upload-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    CreateMultipartUploadResponse created = s3.createMultipartUpload(b -> b.bucket(bucket).key(key)
        .storageClass(StorageClass.INTELLIGENT_TIERING));

    assertEquals("INTELLIGENT_TIERING",
        s3.listMultipartUploads(b -> b.bucket(bucket)).uploads().get(0).storageClassAsString());
    UploadPartResponse part = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(created.uploadId())
        .partNumber(1), RequestBody.fromString("Hello"));
    assertEquals(StorageClass.INTELLIGENT_TIERING,
        s3.listParts(b -> b.bucket(bucket).key(key).uploadId(created.uploadId())).storageClass());

    s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(created.uploadId())
        .multipartUpload(mu -> mu.parts(p -> p.partNumber(1).eTag(part.eTag()))));
    assertEquals(StorageClass.INTELLIGENT_TIERING, s3.headObject(b -> b.bucket(bucket).key(key)).storageClass());
  }

  @Test
  @LocalS3
  void anUnknownStorageClassIsRejected(S3Client s3) {
    String bucket = "storage-class-invalid-bucket";
    s3.createBucket(b -> b.bucket(bucket));

    S3Exception exception = assertThrows(S3Exception.class,
        () -> s3.putObject(b -> b.bucket(bucket).key("a.txt").storageClass("COLD"), RequestBody.fromString("Hello")));
    assertEquals(400, exception.statusCode());
    assertEquals("InvalidStorageClass", exception.awsErrorDetails().errorCode());
  }

}
