package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * What a multipart upload is validated against: the part number and the entity tags of the parts are always
 * checked, while the minimum part size is only checked when the service is configured to, so that tests that
 * upload small parts keep working.
 */
public class MultipartUploadValidationIntegrationTest {

  private static final String BUCKET = "multipart-bucket";

  private static final String KEY = "target.txt";

  private static String startUpload(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    return s3.createMultipartUpload(b -> b.bucket(BUCKET).key(KEY)).uploadId();
  }

  /**
   * A part number outside 1..10000 is rejected before the part is stored.
   */
  @ValueSource(ints = {0, -1, 10001, Integer.MAX_VALUE})
  @ParameterizedTest
  @LocalS3
  void rejectsPartNumberOutsideTheAllowedRange(int partNumber, S3Client s3) {
    String uploadId = startUpload(s3);

    S3Exception thrown = assertThrows(S3Exception.class,
        () -> s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(partNumber),
            RequestBody.fromString("hello")));
    assertEquals(400, thrown.statusCode());
    assertEquals("InvalidArgument", thrown.awsErrorDetails().errorCode());
    // Nothing was added to the upload.
    assertEquals(0, s3.listParts(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId)).parts().size());
  }

  /**
   * UploadPartCopy checks the part number as well, before it reads the source object.
   */
  @ValueSource(ints = {0, 10001})
  @ParameterizedTest
  @LocalS3
  void rejectsPartNumberOutsideTheAllowedRangeWhenCopying(int partNumber, S3Client s3) {
    String uploadId = startUpload(s3);
    s3.putObject(b -> b.bucket(BUCKET).key("source.txt"), RequestBody.fromString("hello"));

    S3Exception thrown = assertThrows(S3Exception.class, () -> s3.uploadPartCopy(b -> b.bucket(BUCKET).key(KEY)
        .uploadId(uploadId).partNumber(partNumber).sourceBucket(BUCKET).sourceKey("source.txt")));
    assertEquals(400, thrown.statusCode());
    assertEquals("InvalidArgument", thrown.awsErrorDetails().errorCode());
  }

  @ValueSource(ints = {1, 2, 10000})
  @ParameterizedTest
  @LocalS3
  void acceptsPartNumberInTheAllowedRange(int partNumber, S3Client s3) {
    String uploadId = startUpload(s3);

    assertDoesNotThrow(() -> s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(partNumber),
        RequestBody.fromString("hello")));
  }

  /**
   * By default the size of a part is not checked, so an upload of small parts completes.
   */
  @Test
  @LocalS3
  void acceptsSmallPartsByDefault(S3Client s3) {
    String uploadId = startUpload(s3);
    UploadPartResponse first = s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("small"));
    UploadPartResponse second = s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(2),
        RequestBody.fromString("parts"));

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId)
        .multipartUpload(completed(first.eTag(), second.eTag())));

    assertEquals("smallparts", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(KEY)).asUtf8String());
  }

  /**
   * An entity tag that isn't the one that the upload of the part answered fails with {@code InvalidPart}, like
   * Amazon S3, and the upload can still be completed with the right one.
   */
  @Test
  @LocalS3
  void rejectsAnEtagThatDoesNotMatchThePart(S3Client s3) {
    String uploadId = startUpload(s3);
    UploadPartResponse first = s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("hello"));

    S3Exception thrown = assertThrows(S3Exception.class, () -> s3.completeMultipartUpload(b -> b.bucket(BUCKET)
        .key(KEY).uploadId(uploadId).multipartUpload(completed("\"00000000000000000000000000000000\""))));
    assertEquals(400, thrown.statusCode());
    assertEquals("InvalidPart", thrown.awsErrorDetails().errorCode());

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId)
        .multipartUpload(completed(first.eTag())));
    assertEquals("hello", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(KEY)).asUtf8String());
  }

  /**
   * A part that was never uploaded fails with {@code InvalidPart} as well.
   */
  @Test
  @LocalS3
  void rejectsAPartThatWasNotUploaded(S3Client s3) {
    String uploadId = startUpload(s3);
    UploadPartResponse first = s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("hello"));

    S3Exception thrown = assertThrows(S3Exception.class, () -> s3.completeMultipartUpload(b -> b.bucket(BUCKET)
        .key(KEY).uploadId(uploadId).multipartUpload(completed(first.eTag(), first.eTag()))));
    assertEquals(400, thrown.statusCode());
    assertEquals("InvalidPart", thrown.awsErrorDetails().errorCode());
  }

  private static CompletedMultipartUpload completed(String... etags) {
    CompletedPart[] parts = new CompletedPart[etags.length];
    for (int i = 0; i < etags.length; i++) {
      parts[i] = CompletedPart.builder().partNumber(i + 1).eTag(etags[i]).build();
    }
    return CompletedMultipartUpload.builder().parts(parts).build();
  }

  /**
   * With strict part sizes, a part that isn't the last one must be at least 5 MiB, like Amazon S3 requires.
   * The upload is rejected when it is completed, which is when it is known which part is the last one.
   */
  @Test
  @LocalS3(strictPartSizes = true)
  void rejectsSmallPartWhenPartSizesAreStrict(S3Client s3) {
    String uploadId = startUpload(s3);
    UploadPartResponse first = s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("small"));
    UploadPartResponse second = s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(2),
        RequestBody.fromString("parts"));

    S3Exception thrown = assertThrows(S3Exception.class,
        () -> s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId)
            .multipartUpload(completed(first.eTag(), second.eTag()))));
    assertEquals(400, thrown.statusCode());
    assertEquals("EntityTooSmall", thrown.awsErrorDetails().errorCode());
  }

  /**
   * The last part may be smaller than the minimum, and so may the single part of an upload.
   */
  @Test
  @LocalS3(strictPartSizes = true)
  void acceptsLargeEnoughPartsWhenPartSizesAreStrict(S3Client s3) {
    String uploadId = startUpload(s3);
    byte[] large = new byte[5 * 1024 * 1024];
    UploadPartResponse first = s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(1),
        RequestBody.fromBytes(large));
    // The last part is smaller than the minimum, which Amazon S3 allows.
    UploadPartResponse second = s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(2),
        RequestBody.fromString("tail"));

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId)
        .multipartUpload(completed(first.eTag(), second.eTag())));

    assertEquals(large.length + 4,
        s3.headObject(b -> b.bucket(BUCKET).key(KEY)).contentLength());
  }

  /**
   * An upload of a single small part completes even when part sizes are strict.
   */
  @Test
  @LocalS3(strictPartSizes = true)
  void acceptsSingleSmallPartWhenPartSizesAreStrict(S3Client s3) {
    String uploadId = startUpload(s3);
    UploadPartResponse only = s3.uploadPart(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("only"));

    s3.completeMultipartUpload(b -> b.bucket(BUCKET).key(KEY).uploadId(uploadId)
        .multipartUpload(completed(only.eTag())));

    assertEquals("only", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(KEY)).asUtf8String());
  }

}
