package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartCopyResponse;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * Server-side encryption with S3 managed keys (SSE-S3) and KMS keys (SSE-KMS) through the AWS SDK: the headers are
 * validated, stored with the object and answered like Amazon S3 answers them.
 */
class ServerSideEncryptionIntegrationTest {

  private static final String BUCKET = "sse-bucket";

  private static final String KMS_KEY = "arn:aws:kms:us-east-1:123456789012:key/1234abcd-12ab-34cd-56ef-1234567890ab";

  private static final String AWS_MANAGED_KMS_KEY =
      com.robothy.s3.core.model.internal.ServerSideEncryption.AWS_MANAGED_KMS_KEY_ID;

  private static final String CONTEXT = Base64.getEncoder()
      .encodeToString("{\"department\":\"data\"}".getBytes(StandardCharsets.UTF_8));

  @Test
  @LocalS3(buckets = BUCKET)
  void storesAndAnswersSseS3(S3Client s3) {
    PutObjectResponse put = s3.putObject(b -> b.bucket(BUCKET).key("s3.txt")
        .serverSideEncryption(ServerSideEncryption.AES256), RequestBody.fromString("hi"));
    assertEquals(ServerSideEncryption.AES256, put.serverSideEncryption());
    assertNull(put.ssekmsKeyId());

    ResponseBytes<GetObjectResponse> get = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("s3.txt"));
    assertEquals("hi", get.asUtf8String());
    assertEquals(ServerSideEncryption.AES256, get.response().serverSideEncryption());
    // A range is answered with the encryption of the object as well.
    assertEquals(ServerSideEncryption.AES256,
        s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("s3.txt").range("bytes=0-0")).response().serverSideEncryption());
    assertEquals(ServerSideEncryption.AES256,
        s3.headObject(b -> b.bucket(BUCKET).key("s3.txt")).serverSideEncryption());

    s3.putObject(b -> b.bucket(BUCKET).key("plain.txt"), RequestBody.fromString("plain"));
    HeadObjectResponse plain = s3.headObject(b -> b.bucket(BUCKET).key("plain.txt"));
    assertNull(plain.serverSideEncryption());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void storesAndAnswersSseKms(S3Client s3) {
    PutObjectResponse put = s3.putObject(b -> b.bucket(BUCKET).key("kms.txt")
        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(KMS_KEY).ssekmsEncryptionContext(CONTEXT)
        .bucketKeyEnabled(true), RequestBody.fromString("secret"));
    assertEquals(ServerSideEncryption.AWS_KMS, put.serverSideEncryption());
    assertEquals(KMS_KEY, put.ssekmsKeyId());
    assertEquals(CONTEXT, put.ssekmsEncryptionContext());
    assertEquals(Boolean.TRUE, put.bucketKeyEnabled());

    HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("kms.txt"));
    assertEquals(ServerSideEncryption.AWS_KMS, head.serverSideEncryption());
    assertEquals(KMS_KEY, head.ssekmsKeyId());
    assertEquals(Boolean.TRUE, head.bucketKeyEnabled());
    GetObjectResponse get = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("kms.txt")).response();
    assertEquals(KMS_KEY, get.ssekmsKeyId());

    // A copy doesn't keep the encryption of its source: it is stored with the one that the request names.
    CopyObjectResponse copy = s3.copyObject(b -> b.sourceBucket(BUCKET).sourceKey("kms.txt")
        .destinationBucket(BUCKET).destinationKey("copy.txt").serverSideEncryption(ServerSideEncryption.AES256));
    assertEquals(ServerSideEncryption.AES256, copy.serverSideEncryption());
    HeadObjectResponse copyHead = s3.headObject(b -> b.bucket(BUCKET).key("copy.txt"));
    assertEquals(ServerSideEncryption.AES256, copyHead.serverSideEncryption());
    assertNull(copyHead.ssekmsKeyId());
    s3.copyObject(b -> b.sourceBucket(BUCKET).sourceKey("kms.txt").destinationBucket(BUCKET).destinationKey("bare.txt"));
    assertNull(s3.headObject(b -> b.bucket(BUCKET).key("bare.txt")).serverSideEncryption());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void usesTheAwsManagedKeyForSseKmsWithoutAKey(S3Client s3) {
    PutObjectResponse put = s3.putObject(b -> b.bucket(BUCKET).key("managed.txt")
        .serverSideEncryption(ServerSideEncryption.AWS_KMS), RequestBody.fromString("secret"));
    assertEquals(ServerSideEncryption.AWS_KMS, put.serverSideEncryption());
    assertEquals(AWS_MANAGED_KMS_KEY, put.ssekmsKeyId());

    HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("managed.txt"));
    assertEquals(ServerSideEncryption.AWS_KMS, head.serverSideEncryption());
    assertEquals(AWS_MANAGED_KMS_KEY, head.ssekmsKeyId());
    ResponseBytes<GetObjectResponse> get = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("managed.txt"));
    assertEquals("secret", get.asUtf8String());
    assertEquals(AWS_MANAGED_KMS_KEY, get.response().ssekmsKeyId());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void uploadsAnObjectInPartsWithSseKms(S3Client s3) {
    s3.putObject(b -> b.bucket(BUCKET).key("source.bin"), RequestBody.fromBytes(new byte[5 * 1024 * 1024]));
    CreateMultipartUploadResponse upload = s3.createMultipartUpload(b -> b.bucket(BUCKET).key("parts.bin")
        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(KMS_KEY).ssekmsEncryptionContext(CONTEXT));
    assertEquals(ServerSideEncryption.AWS_KMS, upload.serverSideEncryption());
    assertEquals(KMS_KEY, upload.ssekmsKeyId());
    assertEquals(CONTEXT, upload.ssekmsEncryptionContext());

    // The parts don't name the encryption; they are answered with the one of their upload.
    UploadPartCopyResponse first = s3.uploadPartCopy(b -> b.sourceBucket(BUCKET).sourceKey("source.bin")
        .destinationBucket(BUCKET).destinationKey("parts.bin").uploadId(upload.uploadId()).partNumber(1));
    assertEquals(ServerSideEncryption.AWS_KMS, first.serverSideEncryption());
    assertEquals(KMS_KEY, first.ssekmsKeyId());
    UploadPartResponse second = s3.uploadPart(b -> b.bucket(BUCKET).key("parts.bin").uploadId(upload.uploadId())
        .partNumber(2), RequestBody.fromString("tail"));
    assertEquals(ServerSideEncryption.AWS_KMS, second.serverSideEncryption());

    CompleteMultipartUploadResponse complete = s3.completeMultipartUpload(b -> b.bucket(BUCKET).key("parts.bin")
        .uploadId(upload.uploadId()).multipartUpload(m -> m.parts(
            p -> p.partNumber(1).eTag(first.copyPartResult().eTag()),
            p -> p.partNumber(2).eTag(second.eTag()))));
    assertEquals(ServerSideEncryption.AWS_KMS, complete.serverSideEncryption());
    assertEquals(KMS_KEY, complete.ssekmsKeyId());
    assertEquals(KMS_KEY, s3.headObject(b -> b.bucket(BUCKET).key("parts.bin")).ssekmsKeyId());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void appliesTheDefaultEncryptionOfTheBucket(S3Client s3) {
    s3.putBucketEncryption(b -> b.bucket(BUCKET).serverSideEncryptionConfiguration(c -> c.rules(r -> r
        .applyServerSideEncryptionByDefault(d -> d.sseAlgorithm(ServerSideEncryption.AWS_KMS).kmsMasterKeyID(KMS_KEY))
        .bucketKeyEnabled(true))));

    PutObjectResponse put = s3.putObject(b -> b.bucket(BUCKET).key("default.txt"), RequestBody.fromString("a"));
    assertEquals(ServerSideEncryption.AWS_KMS, put.serverSideEncryption());
    assertEquals(KMS_KEY, put.ssekmsKeyId());
    assertEquals(Boolean.TRUE, put.bucketKeyEnabled());
    assertEquals(KMS_KEY, s3.headObject(b -> b.bucket(BUCKET).key("default.txt")).ssekmsKeyId());

    // What a request names takes precedence over the default, and a customer-provided key replaces it.
    s3.putObject(b -> b.bucket(BUCKET).key("named.txt").serverSideEncryption(ServerSideEncryption.AES256),
        RequestBody.fromString("a"));
    HeadObjectResponse named = s3.headObject(b -> b.bucket(BUCKET).key("named.txt"));
    assertEquals(ServerSideEncryption.AES256, named.serverSideEncryption());
    assertNull(named.ssekmsKeyId());
    byte[] customerKey = new byte[32];
    String key = Base64.getEncoder().encodeToString(customerKey);
    s3.putObject(b -> b.bucket(BUCKET).key("customer.txt")
        .sseCustomerAlgorithm("AES256").sseCustomerKey(key).sseCustomerKeyMD5(md5(customerKey)),
        RequestBody.fromString("a"));
    assertNull(s3.headObject(b -> b.bucket(BUCKET).key("customer.txt")
        .sseCustomerAlgorithm("AES256").sseCustomerKey(key).sseCustomerKeyMD5(md5(customerKey))).serverSideEncryption());

    CopyObjectResponse copy = s3.copyObject(b -> b.sourceBucket(BUCKET).sourceKey("named.txt")
        .destinationBucket(BUCKET).destinationKey("copy.txt"));
    assertEquals(ServerSideEncryption.AWS_KMS, copy.serverSideEncryption());

    CreateMultipartUploadResponse upload = s3.createMultipartUpload(b -> b.bucket(BUCKET).key("parts.bin"));
    assertEquals(ServerSideEncryption.AWS_KMS, upload.serverSideEncryption());
    assertEquals(KMS_KEY, upload.ssekmsKeyId());
    UploadPartResponse part = s3.uploadPart(b -> b.bucket(BUCKET).key("parts.bin").uploadId(upload.uploadId())
        .partNumber(1), RequestBody.fromString("part"));
    assertEquals(ServerSideEncryption.AWS_KMS, part.serverSideEncryption());
    CompleteMultipartUploadResponse complete = s3.completeMultipartUpload(b -> b.bucket(BUCKET).key("parts.bin")
        .uploadId(upload.uploadId()).multipartUpload(m -> m.parts(p -> p.partNumber(1).eTag(part.eTag()))));
    assertEquals(KMS_KEY, complete.ssekmsKeyId());

    // Once the configuration is deleted, objects are stored without an encryption again.
    s3.deleteBucketEncryption(b -> b.bucket(BUCKET));
    s3.putObject(b -> b.bucket(BUCKET).key("after.txt"), RequestBody.fromString("a"));
    assertNull(s3.headObject(b -> b.bucket(BUCKET).key("after.txt")).serverSideEncryption());
    assertEquals(KMS_KEY, s3.headObject(b -> b.bucket(BUCKET).key("default.txt")).ssekmsKeyId());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void validatesTheHeaders(S3Client s3) {
    S3Exception algorithm = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("a")
        .serverSideEncryption("AES128"), RequestBody.fromString("a")));
    assertEquals(400, algorithm.statusCode());
    assertEquals("InvalidArgument", algorithm.awsErrorDetails().errorCode());

    S3Exception keyWithoutKms = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("a")
        .serverSideEncryption(ServerSideEncryption.AES256).ssekmsKeyId(KMS_KEY), RequestBody.fromString("a")));
    assertEquals("InvalidArgument", keyWithoutKms.awsErrorDetails().errorCode());

    S3Exception keyWithoutAlgorithm = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET)
        .key("a").ssekmsKeyId(KMS_KEY), RequestBody.fromString("a")));
    assertEquals("InvalidArgument", keyWithoutAlgorithm.awsErrorDetails().errorCode());

    S3Exception context = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("a")
        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsEncryptionContext("not-json"),
        RequestBody.fromString("a")));
    assertEquals("InvalidArgument", context.awsErrorDetails().errorCode());

    byte[] customerKey = new byte[32];
    Arrays.fill(customerKey, (byte) 1);
    String key = Base64.getEncoder().encodeToString(customerKey);
    S3Exception withCustomerKey = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("a")
        .serverSideEncryption(ServerSideEncryption.AES256)
        .sseCustomerAlgorithm("AES256").sseCustomerKey(key).sseCustomerKeyMD5(md5(customerKey)),
        RequestBody.fromString("a")));
    assertEquals("InvalidArgument", withCustomerKey.awsErrorDetails().errorCode());
    assertTrue(s3.listObjectsV2(b -> b.bucket(BUCKET)).contents().isEmpty());
  }

  @Test
  void keepsTheEncryptionAcrossARestart(@TempDir Path dataPath) {
    com.robothy.s3.rest.LocalS3 first = persistent(dataPath);
    first.start();
    try {
      S3Client s3 = client(first.getPort());
      s3.createBucket(b -> b.bucket(BUCKET));
      s3.putObject(b -> b.bucket(BUCKET).key("kms.txt").serverSideEncryption(ServerSideEncryption.AWS_KMS)
          .ssekmsKeyId(KMS_KEY).bucketKeyEnabled(true), RequestBody.fromString("secret"));
      CreateMultipartUploadResponse upload = s3.createMultipartUpload(b -> b.bucket(BUCKET).key("pending.bin")
          .serverSideEncryption(ServerSideEncryption.AES256));
      s3.uploadPart(b -> b.bucket(BUCKET).key("pending.bin").uploadId(upload.uploadId()).partNumber(1),
          RequestBody.fromString("part"));
    } finally {
      first.shutdown();
    }

    com.robothy.s3.rest.LocalS3 second = persistent(dataPath);
    second.start();
    try {
      S3Client s3 = client(second.getPort());
      HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("kms.txt"));
      assertEquals(ServerSideEncryption.AWS_KMS, head.serverSideEncryption());
      assertEquals(KMS_KEY, head.ssekmsKeyId());
      assertEquals(Boolean.TRUE, head.bucketKeyEnabled());

      String uploadId = s3.listMultipartUploads(b -> b.bucket(BUCKET)).uploads().getFirst().uploadId();
      String etag = s3.listParts(b -> b.bucket(BUCKET).key("pending.bin").uploadId(uploadId)).parts().getFirst().eTag();
      CompleteMultipartUploadResponse complete = s3.completeMultipartUpload(b -> b.bucket(BUCKET).key("pending.bin")
          .uploadId(uploadId).multipartUpload(m -> m.parts(p -> p.partNumber(1).eTag(etag))));
      assertEquals(ServerSideEncryption.AES256, complete.serverSideEncryption());
    } finally {
      second.shutdown();
    }
  }

  private static com.robothy.s3.rest.LocalS3 persistent(Path dataPath) {
    return com.robothy.s3.rest.LocalS3.builder()
        .mode(LocalS3Mode.PERSISTENCE)
        .dataPath(dataPath.toAbsolutePath().toString())
        .port(-1)
        .build();
  }

  private static S3Client client(int port) {
    return S3Client.builder()
        .endpointOverride(URI.create("http://localhost:" + port))
        .region(Region.of("local"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")))
        .forcePathStyle(true)
        .build();
  }

  private static String md5(byte[] bytes) {
    try {
      return Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

}
