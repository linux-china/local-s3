package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * Server-side encryption with customer-provided keys through the AWS SDK: the headers are validated and echoed, and a
 * read needs the key the object was stored with.
 */
class CustomerEncryptionIntegrationTest {

  private static final String BUCKET = "sse-c-bucket";

  private static final String KEY = key((byte) 1);

  private static final String KEY_MD5 = md5(KEY);

  private static final String OTHER_KEY = key((byte) 2);

  @Test
  @LocalS3(buckets = BUCKET)
  void storesAndReadsAnObjectWithACustomerKey(S3Client s3) {
    PutObjectResponse put = s3.putObject(b -> b.bucket(BUCKET).key("secret.txt")
        .sseCustomerAlgorithm("AES256").sseCustomerKey(KEY).sseCustomerKeyMD5(KEY_MD5), RequestBody.fromString("hi"));
    assertEquals("AES256", put.sseCustomerAlgorithm());
    assertEquals(KEY_MD5, put.sseCustomerKeyMD5());

    ResponseBytes<GetObjectResponse> get = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("secret.txt")
        .sseCustomerAlgorithm("AES256").sseCustomerKey(KEY).sseCustomerKeyMD5(KEY_MD5));
    assertEquals("hi", get.asUtf8String());
    assertEquals(KEY_MD5, get.response().sseCustomerKeyMD5());
    HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("secret.txt")
        .sseCustomerAlgorithm("AES256").sseCustomerKey(KEY).sseCustomerKeyMD5(KEY_MD5));
    assertEquals("AES256", head.sseCustomerAlgorithm());

    S3Exception withoutKey = assertThrows(S3Exception.class,
        () -> s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("secret.txt")));
    assertEquals(400, withoutKey.statusCode());
    assertEquals("InvalidRequest", withoutKey.awsErrorDetails().errorCode());
    S3Exception otherKey = assertThrows(S3Exception.class, () -> s3.getObjectAsBytes(b -> b.bucket(BUCKET)
        .key("secret.txt").sseCustomerAlgorithm("AES256").sseCustomerKey(OTHER_KEY).sseCustomerKeyMD5(md5(OTHER_KEY))));
    assertEquals(403, otherKey.statusCode());
    // A HEAD needs the key as well; its error has no body, only the status.
    assertEquals(400, assertThrows(S3Exception.class,
        () -> s3.headObject(b -> b.bucket(BUCKET).key("secret.txt"))).statusCode());
    assertEquals(403, assertThrows(S3Exception.class, () -> s3.headObject(b -> b.bucket(BUCKET).key("secret.txt")
        .sseCustomerAlgorithm("AES256").sseCustomerKey(OTHER_KEY).sseCustomerKeyMD5(md5(OTHER_KEY)))).statusCode());

    // A copy reads the source with its key, and stores the copy with another one.
    s3.copyObject(b -> b.sourceBucket(BUCKET).sourceKey("secret.txt").destinationBucket(BUCKET).destinationKey("copy.txt")
        .copySourceSSECustomerAlgorithm("AES256").copySourceSSECustomerKey(KEY).copySourceSSECustomerKeyMD5(KEY_MD5)
        .sseCustomerAlgorithm("AES256").sseCustomerKey(OTHER_KEY).sseCustomerKeyMD5(md5(OTHER_KEY)));
    assertEquals("hi", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("copy.txt").sseCustomerAlgorithm("AES256")
        .sseCustomerKey(OTHER_KEY).sseCustomerKeyMD5(md5(OTHER_KEY))).asUtf8String());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void validatesTheKey(S3Client s3) {
    S3Exception wrongMd5 = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("a")
        .sseCustomerAlgorithm("AES256").sseCustomerKey(KEY).sseCustomerKeyMD5(md5(OTHER_KEY)),
        RequestBody.fromString("a")));
    assertEquals("InvalidArgument", wrongMd5.awsErrorDetails().errorCode());
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
    S3Exception invalidKey = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("a")
        .sseCustomerAlgorithm("AES256").sseCustomerKey(shortKey).sseCustomerKeyMD5(md5(shortKey)),
        RequestBody.fromString("a")));
    assertEquals("InvalidArgument", invalidKey.awsErrorDetails().errorCode());
    S3Exception algorithm = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("a")
        .sseCustomerAlgorithm("AES128").sseCustomerKey(KEY).sseCustomerKeyMD5(KEY_MD5), RequestBody.fromString("a")));
    assertEquals("InvalidEncryptionAlgorithmError", algorithm.awsErrorDetails().errorCode());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void uploadsAnObjectInPartsWithACustomerKey(S3Client s3) {
    CreateMultipartUploadResponse upload = s3.createMultipartUpload(b -> b.bucket(BUCKET).key("parts.bin")
        .sseCustomerAlgorithm("AES256").sseCustomerKey(KEY).sseCustomerKeyMD5(KEY_MD5));
    assertEquals(KEY_MD5, upload.sseCustomerKeyMD5());

    S3Exception withoutKey = assertThrows(S3Exception.class, () -> s3.uploadPart(b -> b.bucket(BUCKET).key("parts.bin")
        .uploadId(upload.uploadId()).partNumber(1), RequestBody.fromString("part")));
    assertEquals("InvalidRequest", withoutKey.awsErrorDetails().errorCode());
    UploadPartResponse part = s3.uploadPart(b -> b.bucket(BUCKET).key("parts.bin").uploadId(upload.uploadId())
        .partNumber(1).sseCustomerAlgorithm("AES256").sseCustomerKey(KEY).sseCustomerKeyMD5(KEY_MD5),
        RequestBody.fromString("part"));
    assertEquals(KEY_MD5, part.sseCustomerKeyMD5());

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key("parts.bin")
        .uploadId(upload.uploadId())
        .multipartUpload(m -> m.parts(p -> p.partNumber(1).eTag(part.eTag()))));
    assertEquals("part", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("parts.bin").sseCustomerAlgorithm("AES256")
        .sseCustomerKey(KEY).sseCustomerKeyMD5(KEY_MD5)).asUtf8String());
  }

  private static String key(byte fill) {
    byte[] key = new byte[32];
    Arrays.fill(key, fill);
    return Base64.getEncoder().encodeToString(key);
  }

  private static String md5(String base64Key) {
    try {
      return Base64.getEncoder().encodeToString(
          MessageDigest.getInstance("MD5").digest(Base64.getDecoder().decode(base64Key)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

}
