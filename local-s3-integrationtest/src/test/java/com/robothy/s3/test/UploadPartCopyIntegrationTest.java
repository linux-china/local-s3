package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartCopyResponse;

/**
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPartCopy.html">UploadPartCopy</a>, which
 * the AWS SDKs use to copy an object with a multipart upload.
 */
public class UploadPartCopyIntegrationTest {

  private static final String BUCKET = "upload-part-copy-bucket";

  private static final String SOURCE_KEY = "source.txt";

  private static final String SOURCE_CONTENT = "0123456789abcdefghij";

  private static final String TARGET_KEY = "target.txt";

  private static void prepare(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    s3.putObject(b -> b.bucket(BUCKET).key(SOURCE_KEY), RequestBody.fromString(SOURCE_CONTENT));
  }

  private static String startUpload(S3Client s3) {
    CreateMultipartUploadResponse created =
        s3.createMultipartUpload(b -> b.bucket(BUCKET).key(TARGET_KEY));
    assertNotNull(created.uploadId());
    return created.uploadId();
  }

  private static String download(S3Client s3, String key) {
    return s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asString(StandardCharsets.UTF_8);
  }

  /**
   * Two ranges of the source object are copied into two parts, which complete to the source object again.
   */
  @Test
  @LocalS3
  void copiesRangesOfSourceObjectIntoParts(S3Client s3) {
    prepare(s3);
    String uploadId = startUpload(s3);

    UploadPartCopyResponse first = s3.uploadPartCopy(b -> b.bucket(BUCKET).key(TARGET_KEY)
        .uploadId(uploadId).partNumber(1)
        .sourceBucket(BUCKET).sourceKey(SOURCE_KEY).copySourceRange("bytes=0-9"));
    UploadPartCopyResponse second = s3.uploadPartCopy(b -> b.bucket(BUCKET).key(TARGET_KEY)
        .uploadId(uploadId).partNumber(2)
        .sourceBucket(BUCKET).sourceKey(SOURCE_KEY).copySourceRange("bytes=10-19"));

    // The ETag of a part is the one of the copied bytes, not the one of the whole source object.
    assertNotNull(first.copyPartResult().eTag());
    assertNotNull(first.copyPartResult().lastModified());
    assertTrue(first.copyPartResult().eTag().contains(md5("0123456789")), first.copyPartResult().eTag());
    assertTrue(second.copyPartResult().eTag().contains(md5("abcdefghij")), second.copyPartResult().eTag());

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(TARGET_KEY).uploadId(uploadId)
        .multipartUpload(CompletedMultipartUpload.builder().parts(
            CompletedPart.builder().partNumber(1).eTag(first.copyPartResult().eTag()).build(),
            CompletedPart.builder().partNumber(2).eTag(second.copyPartResult().eTag()).build()).build()));

    assertEquals(SOURCE_CONTENT, download(s3, TARGET_KEY));
  }

  /**
   * Without a range, the whole source object becomes the part.
   */
  @Test
  @LocalS3
  void copiesWholeSourceObjectWhenNoRangeIsGiven(S3Client s3) {
    prepare(s3);
    String uploadId = startUpload(s3);

    UploadPartCopyResponse part = s3.uploadPartCopy(b -> b.bucket(BUCKET).key(TARGET_KEY)
        .uploadId(uploadId).partNumber(1).sourceBucket(BUCKET).sourceKey(SOURCE_KEY));

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(TARGET_KEY).uploadId(uploadId)
        .multipartUpload(CompletedMultipartUpload.builder().parts(
            CompletedPart.builder().partNumber(1).eTag(part.copyPartResult().eTag()).build()).build()));

    assertEquals(SOURCE_CONTENT, download(s3, TARGET_KEY));
  }

  /**
   * The source can be a specific version of an object, and is reported back.
   */
  @Test
  @LocalS3
  void copiesTheRequestedVersionOfTheSource(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    s3.putBucketVersioning(b -> b.bucket(BUCKET).versioningConfiguration(c -> c.status("Enabled")));
    PutObjectResponse v1 = s3.putObject(b -> b.bucket(BUCKET).key(SOURCE_KEY), RequestBody.fromString("first"));
    s3.putObject(b -> b.bucket(BUCKET).key(SOURCE_KEY), RequestBody.fromString("second"));
    String uploadId = startUpload(s3);

    UploadPartCopyResponse part = s3.uploadPartCopy(b -> b.bucket(BUCKET).key(TARGET_KEY)
        .uploadId(uploadId).partNumber(1)
        .sourceBucket(BUCKET).sourceKey(SOURCE_KEY).sourceVersionId(v1.versionId()));
    assertEquals(v1.versionId(), part.copySourceVersionId());

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(TARGET_KEY).uploadId(uploadId)
        .multipartUpload(CompletedMultipartUpload.builder().parts(
            CompletedPart.builder().partNumber(1).eTag(part.copyPartResult().eTag()).build()).build()));

    assertEquals("first", download(s3, TARGET_KEY));
  }

  /**
   * The part replaces the one with the same number, whether it was copied or uploaded.
   */
  @Test
  @LocalS3
  void replacesAnUploadedPartWithTheSameNumber(S3Client s3) {
    prepare(s3);
    String uploadId = startUpload(s3);

    s3.uploadPart(b -> b.bucket(BUCKET).key(TARGET_KEY).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("replaced"));
    UploadPartCopyResponse copied = s3.uploadPartCopy(b -> b.bucket(BUCKET).key(TARGET_KEY)
        .uploadId(uploadId).partNumber(1)
        .sourceBucket(BUCKET).sourceKey(SOURCE_KEY).copySourceRange("bytes=0-4"));

    assertEquals(1, s3.listParts(b -> b.bucket(BUCKET).key(TARGET_KEY).uploadId(uploadId)).parts().size());
    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(TARGET_KEY).uploadId(uploadId)
        .multipartUpload(CompletedMultipartUpload.builder().parts(
            CompletedPart.builder().partNumber(1).eTag(copied.copyPartResult().eTag()).build()).build()));

    assertEquals("01234", download(s3, TARGET_KEY));
  }

  /**
   * A part can be copied from another bucket, which is what a copy between buckets does.
   */
  @Test
  @LocalS3
  void copiesAcrossBuckets(S3Client s3) {
    String sourceBucket = "other-bucket";
    s3.createBucket(b -> b.bucket(sourceBucket));
    s3.putObject(b -> b.bucket(sourceBucket).key(SOURCE_KEY), RequestBody.fromString(SOURCE_CONTENT));
    s3.createBucket(b -> b.bucket(BUCKET));
    String uploadId = startUpload(s3);

    UploadPartCopyResponse part = s3.uploadPartCopy(b -> b.bucket(BUCKET).key(TARGET_KEY)
        .uploadId(uploadId).partNumber(1).sourceBucket(sourceBucket).sourceKey(SOURCE_KEY));

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(TARGET_KEY).uploadId(uploadId)
        .multipartUpload(CompletedMultipartUpload.builder().parts(
            CompletedPart.builder().partNumber(1).eTag(part.copyPartResult().eTag()).build()).build()));

    assertEquals(SOURCE_CONTENT, download(s3, TARGET_KEY));
  }

  /**
   * A key that isn't ASCII reaches the source object, so that the header is URL decoded.
   */
  @Test
  @LocalS3
  void copiesSourceWhoseKeyIsNotAscii(S3Client s3) {
    String sourceKey = "目录/源文件 v1.txt";
    s3.createBucket(b -> b.bucket(BUCKET));
    s3.putObject(b -> b.bucket(BUCKET).key(sourceKey), RequestBody.fromString(SOURCE_CONTENT));
    String uploadId = startUpload(s3);

    UploadPartCopyResponse part = s3.uploadPartCopy(b -> b.bucket(BUCKET).key(TARGET_KEY)
        .uploadId(uploadId).partNumber(1).sourceBucket(BUCKET).sourceKey(sourceKey));

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(TARGET_KEY).uploadId(uploadId)
        .multipartUpload(CompletedMultipartUpload.builder().parts(
            CompletedPart.builder().partNumber(1).eTag(part.copyPartResult().eTag()).build()).build()));

    assertEquals(SOURCE_CONTENT, download(s3, TARGET_KEY));
  }

  @Test
  @LocalS3
  void rejectsAnUploadThatDoesNotExist(S3Client s3) {
    prepare(s3);

    assertThrows(NoSuchUploadException.class, () -> s3.uploadPartCopy(b -> b.bucket(BUCKET).key(TARGET_KEY)
        .uploadId("no-such-upload").partNumber(1).sourceBucket(BUCKET).sourceKey(SOURCE_KEY)));
  }

  @Test
  @LocalS3
  void rejectsASourceThatDoesNotExist(S3Client s3) {
    prepare(s3);
    String uploadId = startUpload(s3);

    assertThrows(NoSuchKeyException.class, () -> s3.uploadPartCopy(b -> b.bucket(BUCKET).key(TARGET_KEY)
        .uploadId(uploadId).partNumber(1).sourceBucket(BUCKET).sourceKey("missing.txt")));
  }

  @Test
  @LocalS3
  void rejectsARangeThatTheSourceCannotSatisfy(S3Client s3) {
    prepare(s3);
    String uploadId = startUpload(s3);

    S3Exception thrown = assertThrows(S3Exception.class, () -> s3.uploadPartCopy(b -> b.bucket(BUCKET)
        .key(TARGET_KEY).uploadId(uploadId).partNumber(1)
        .sourceBucket(BUCKET).sourceKey(SOURCE_KEY).copySourceRange("bytes=100-200")));
    assertEquals(416, thrown.statusCode());
    // The upload keeps the parts it had, so nothing was committed.
    assertEquals(List.of(), s3.listParts(b -> b.bucket(BUCKET).key(TARGET_KEY).uploadId(uploadId)).parts());
  }

  private static String md5(String content) {
    return org.apache.commons.codec.digest.DigestUtils.md5Hex(content);
  }

}
