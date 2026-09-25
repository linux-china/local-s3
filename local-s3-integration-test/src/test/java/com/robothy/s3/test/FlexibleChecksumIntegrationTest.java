package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.jupiter.LocalS3;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectAttributes;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html">Flexible
 * checksums</a>: the checksum that a client sends content with is verified, stored, and answered by the reads of the
 * object, like Amazon S3 does.
 */
public class FlexibleChecksumIntegrationTest {

  private static final String BUCKET = "checksum-bucket";

  /**
   * The AWS SDK sends a CRC32 in a trailing header of an {@code aws-chunked} body by default.
   */
  @Test
  @LocalS3
  void storesTheChecksumThatTheSdkSendsByDefault(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    PutObjectResponse put = s3.putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello"));
    assertEquals(Checksums.crc32("Hello"), put.checksumCRC32());
    assertEquals(ChecksumType.FULL_OBJECT, put.checksumType());

    // Only answered when it is asked for.
    assertNull(s3.headObject(b -> b.bucket(BUCKET).key("a.txt")).checksumCRC32());
    HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("a.txt").checksumMode(ChecksumMode.ENABLED));
    assertEquals(Checksums.crc32("Hello"), head.checksumCRC32());
    assertEquals(ChecksumType.FULL_OBJECT, head.checksumType());

    // The SDK verifies the content that it reads against the checksum.
    ResponseBytes<GetObjectResponse> get = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")
        .checksumMode(ChecksumMode.ENABLED));
    assertEquals("Hello", get.asUtf8String());
    assertEquals(Checksums.crc32("Hello"), get.response().checksumCRC32());

    // A range has no checksum of its own.
    ResponseBytes<GetObjectResponse> range = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")
        .range("bytes=0-1").checksumMode(ChecksumMode.ENABLED));
    assertEquals("He", range.asUtf8String());
    assertNull(range.response().checksumCRC32());

    S3Object listed = s3.listObjectsV2(b -> b.bucket(BUCKET)).contents().get(0);
    assertEquals(List.of(ChecksumAlgorithm.CRC32), listed.checksumAlgorithm());
    assertEquals(ChecksumType.FULL_OBJECT, listed.checksumType());
  }

  @Test
  @LocalS3
  void storesAChecksumOfEveryAlgorithm(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    String content = "The quick brown fox jumps over the lazy dog";

    assertEquals(Checksums.crc32c(content), put(s3, "crc32c", content, ChecksumAlgorithm.CRC32_C).checksumCRC32C());
    // The SDK computes a CRC64NVME only with aws-crt, so the checksum is sent as a header instead.
    assertEquals(Checksums.crc64Nvme(content), s3.putObject(b -> b.bucket(BUCKET).key("crc64nvme")
        .checksumCRC64NVME(Checksums.crc64Nvme(content)), RequestBody.fromString(content)).checksumCRC64NVME());
    assertEquals(Checksums.sha1(content), put(s3, "sha1", content, ChecksumAlgorithm.SHA1).checksumSHA1());
    assertEquals(Checksums.sha256(content), put(s3, "sha256", content, ChecksumAlgorithm.SHA256).checksumSHA256());

    GetObjectAttributesResponse attributes = s3.getObjectAttributes(b -> b.bucket(BUCKET).key("sha256")
        .objectAttributes(ObjectAttributes.CHECKSUM));
    assertEquals(Checksums.sha256(content), attributes.checksum().checksumSHA256());
    assertNull(attributes.checksum().checksumCRC32());
    assertEquals(ChecksumType.FULL_OBJECT, attributes.checksum().checksumType());

    assertEquals(Checksums.crc64Nvme(content), s3.headObject(b -> b.bucket(BUCKET).key("crc64nvme")
        .checksumMode(ChecksumMode.ENABLED)).checksumCRC64NVME());
    ResponseBytes<GetObjectResponse> get = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("sha1")
        .checksumMode(ChecksumMode.ENABLED));
    assertEquals(content, get.asUtf8String());
    assertEquals(Checksums.sha1(content), get.response().checksumSHA1());
  }

  /**
   * Content that doesn't match the checksum that the client sent is rejected, and not stored.
   */
  @Test
  @LocalS3
  void rejectsContentThatDoesNotMatchItsChecksum(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));

    S3Exception mismatch = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("a.txt")
        .checksumCRC32(Checksums.crc32("Other")), RequestBody.fromString("Hello")));
    assertEquals(400, mismatch.statusCode());
    assertEquals("BadDigest", mismatch.awsErrorDetails().errorCode());
    assertThrows(NoSuchKeyException.class, () -> s3.headObject(b -> b.bucket(BUCKET).key("a.txt")));

    S3Exception invalid = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("a.txt")
        .checksumSHA256(Checksums.crc32("Hello")), RequestBody.fromString("Hello")));
    assertEquals(400, invalid.statusCode());
    assertEquals("BadDigest", invalid.awsErrorDetails().errorCode());
  }

  /**
   * The composite checksum of an object uploaded in parts is the checksum of the checksums of its parts, followed by
   * the number of parts.
   */
  @Test
  @LocalS3
  void answersTheCompositeChecksumOfAMultipartUpload(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    String part1 = Parts.large("Hello");
    String part2 = "World";

    CreateMultipartUploadResponse upload = s3.createMultipartUpload(b -> b.bucket(BUCKET).key("a.txt")
        .checksumAlgorithm(ChecksumAlgorithm.SHA256));
    assertEquals(ChecksumAlgorithm.SHA256, upload.checksumAlgorithm());
    assertEquals(ChecksumType.COMPOSITE, upload.checksumType());

    UploadPartResponse uploaded1 = uploadPart(s3, upload.uploadId(), 1, part1, ChecksumAlgorithm.SHA256);
    UploadPartResponse uploaded2 = uploadPart(s3, upload.uploadId(), 2, part2, ChecksumAlgorithm.SHA256);
    assertEquals(Checksums.sha256(part1), uploaded1.checksumSHA256());
    assertEquals(Checksums.sha256(part2), uploaded2.checksumSHA256());

    ListPartsResponse parts = s3.listParts(b -> b.bucket(BUCKET).key("a.txt").uploadId(upload.uploadId()));
    assertEquals(ChecksumAlgorithm.SHA256, parts.checksumAlgorithm());
    assertEquals(Checksums.sha256(part2), parts.parts().get(1).checksumSHA256());

    CompleteMultipartUploadResponse completed = s3.completeMultipartUpload(b -> b.bucket(BUCKET).key("a.txt")
        .uploadId(upload.uploadId())
        .multipartUpload(m -> m.parts(
            CompletedPart.builder().partNumber(1).eTag(uploaded1.eTag()).checksumSHA256(uploaded1.checksumSHA256())
                .build(),
            CompletedPart.builder().partNumber(2).eTag(uploaded2.eTag()).checksumSHA256(uploaded2.checksumSHA256())
                .build())));

    ByteArrayOutputStream partChecksums = new ByteArrayOutputStream();
    partChecksums.writeBytes(Checksums.decode(uploaded1.checksumSHA256()));
    partChecksums.writeBytes(Checksums.decode(uploaded2.checksumSHA256()));
    String composite = Checksums.encode(Checksums.digest("SHA-256", partChecksums.toByteArray())) + "-2";
    assertEquals(composite, completed.checksumSHA256());
    assertEquals(ChecksumType.COMPOSITE, completed.checksumType());

    GetObjectAttributesResponse attributes = s3.getObjectAttributes(b -> b.bucket(BUCKET).key("a.txt")
        .objectAttributes(ObjectAttributes.CHECKSUM, ObjectAttributes.OBJECT_PARTS));
    assertEquals(composite, attributes.checksum().checksumSHA256());
    assertEquals(ChecksumType.COMPOSITE, attributes.checksum().checksumType());
    assertEquals(Checksums.sha256(part1), attributes.objectParts().parts().get(0).checksumSHA256());

    // The SDK doesn't verify a composite checksum, but reads the object with it.
    ResponseBytes<GetObjectResponse> get = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")
        .checksumMode(ChecksumMode.ENABLED));
    assertEquals(part1 + part2, get.asUtf8String());
    assertEquals(composite, get.response().checksumSHA256());

    // A part is read with its own checksum, of the type of the checksum of the object.
    HeadObjectResponse headPart = s3.headObject(b -> b.bucket(BUCKET).key("a.txt").partNumber(1)
        .checksumMode(ChecksumMode.ENABLED));
    assertEquals(Checksums.sha256(part1), headPart.checksumSHA256());
    assertEquals(ChecksumType.COMPOSITE, headPart.checksumType());
  }

  /**
   * The full object checksum of an object uploaded in parts is the CRC of its whole content, which the SDK verifies
   * the content that it reads against.
   */
  @Test
  @LocalS3
  void answersTheFullObjectChecksumOfAMultipartUpload(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    String part1 = Parts.large("Hello", 3);
    String part2 = "World";

    CreateMultipartUploadResponse upload = s3.createMultipartUpload(b -> b.bucket(BUCKET).key("a.txt")
        .checksumAlgorithm(ChecksumAlgorithm.CRC32_C).checksumType(ChecksumType.FULL_OBJECT));
    assertEquals(ChecksumType.FULL_OBJECT, upload.checksumType());
    UploadPartResponse uploaded1 = uploadPart(s3, upload.uploadId(), 1, part1, ChecksumAlgorithm.CRC32_C);
    UploadPartResponse uploaded2 = uploadPart(s3, upload.uploadId(), 2, part2, ChecksumAlgorithm.CRC32_C);

    CompleteMultipartUploadResponse completed = s3.completeMultipartUpload(b -> b.bucket(BUCKET).key("a.txt")
        .uploadId(upload.uploadId())
        .checksumType(ChecksumType.FULL_OBJECT)
        .checksumCRC32C(Checksums.crc32c(part1 + part2))
        .multipartUpload(m -> m.parts(
            CompletedPart.builder().partNumber(1).eTag(uploaded1.eTag()).build(),
            CompletedPart.builder().partNumber(2).eTag(uploaded2.eTag()).build())));
    assertEquals(Checksums.crc32c(part1 + part2), completed.checksumCRC32C());
    assertEquals(ChecksumType.FULL_OBJECT, completed.checksumType());

    ResponseBytes<GetObjectResponse> get = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")
        .checksumMode(ChecksumMode.ENABLED));
    assertArrayEquals((part1 + part2).getBytes(StandardCharsets.UTF_8), get.asByteArray());
    assertEquals(Checksums.crc32c(part1 + part2), get.response().checksumCRC32C());
  }

  @Test
  @LocalS3
  void rejectsAMultipartUploadWhoseChecksumsDoNotMatch(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    CreateMultipartUploadResponse upload = s3.createMultipartUpload(b -> b.bucket(BUCKET).key("a.txt")
        .checksumAlgorithm(ChecksumAlgorithm.CRC32).checksumType(ChecksumType.FULL_OBJECT));

    // A part of another algorithm than the one of the upload.
    S3Exception otherAlgorithm = assertThrows(S3Exception.class,
        () -> uploadPart(s3, upload.uploadId(), 1, "Hello", ChecksumAlgorithm.SHA1));
    assertEquals("InvalidRequest", otherAlgorithm.awsErrorDetails().errorCode());

    UploadPartResponse uploaded = uploadPart(s3, upload.uploadId(), 1, "Hello", ChecksumAlgorithm.CRC32);
    S3Exception wrongChecksum = assertThrows(S3Exception.class, () -> s3.completeMultipartUpload(b -> b
        .bucket(BUCKET).key("a.txt").uploadId(upload.uploadId())
        .checksumCRC32(Checksums.crc32("Other"))
        .multipartUpload(m -> m.parts(CompletedPart.builder().partNumber(1).eTag(uploaded.eTag()).build()))));
    assertEquals("BadDigest", wrongChecksum.awsErrorDetails().errorCode());

    S3Exception wrongPart = assertThrows(S3Exception.class, () -> s3.completeMultipartUpload(b -> b
        .bucket(BUCKET).key("a.txt").uploadId(upload.uploadId())
        .multipartUpload(m -> m.parts(CompletedPart.builder().partNumber(1).eTag(uploaded.eTag())
            .checksumCRC32(Checksums.crc32("Other")).build()))));
    assertEquals("InvalidPart", wrongPart.awsErrorDetails().errorCode());
  }

  /**
   * A copy is stored with a checksum of the algorithm of its source, or of the one that the request names.
   */
  @Test
  @LocalS3
  void copiesTheChecksumOfAnObject(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    s3.putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello"));

    CopyObjectResponse copy = s3.copyObject(b -> b.sourceBucket(BUCKET).sourceKey("a.txt")
        .destinationBucket(BUCKET).destinationKey("b.txt"));
    assertEquals(Checksums.crc32("Hello"), copy.copyObjectResult().checksumCRC32());

    CopyObjectResponse sha1 = s3.copyObject(b -> b.sourceBucket(BUCKET).sourceKey("a.txt")
        .destinationBucket(BUCKET).destinationKey("c.txt").checksumAlgorithm(ChecksumAlgorithm.SHA1));
    assertEquals(Checksums.sha1("Hello"), sha1.copyObjectResult().checksumSHA1());
    assertEquals(Checksums.sha1("Hello"), s3.headObject(b -> b.bucket(BUCKET).key("c.txt")
        .checksumMode(ChecksumMode.ENABLED)).checksumSHA1());
  }

  private static PutObjectResponse put(S3Client s3, String key, String content, ChecksumAlgorithm algorithm) {
    return s3.putObject(b -> b.bucket(BUCKET).key(key).checksumAlgorithm(algorithm), RequestBody.fromString(content));
  }

  private static UploadPartResponse uploadPart(S3Client s3, String uploadId, int partNumber, String content,
                                               ChecksumAlgorithm algorithm) {
    return s3.uploadPart(b -> b.bucket(BUCKET).key("a.txt").uploadId(uploadId).partNumber(partNumber)
        .checksumAlgorithm(algorithm), RequestBody.fromString(content));
  }

}
