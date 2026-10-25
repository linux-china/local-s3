package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@code UploadPart} checks the {@code Content-MD5} of a part like {@code PutObject} does; see
 * {@code docs/semantics.md#request-validation}.
 */
public class UploadPartContentMd5IntegrationTest {

  @Test
  @LocalS3
  void aMatchingContentMd5IsAccepted(S3Client s3) {
    String bucket = "part-md5-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key("a.txt")).uploadId();

    String md5 = Base64.getEncoder().encodeToString(Digests.md5("Hello"));
    String etag = s3.uploadPart(b -> b.bucket(bucket).key("a.txt").uploadId(uploadId).partNumber(1).contentMD5(md5),
        RequestBody.fromString("Hello")).eTag();
    assertEquals(Etags.md5("Hello"), etag);
  }

  @Test
  @LocalS3
  void aContentMd5ThatDoesNotMatchIsBadDigest(S3Client s3) {
    String bucket = "part-md5-bad-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key("a.txt")).uploadId();

    S3Exception ex = assertThrows(S3Exception.class, () -> s3.uploadPart(b -> b.bucket(bucket).key("a.txt")
        .uploadId(uploadId).partNumber(1).contentMD5("AAAAAAAAAAAAAAAAAAAAAA=="), RequestBody.fromString("Hello")));
    assertEquals(400, ex.statusCode());
    assertEquals("BadDigest", ex.awsErrorDetails().errorCode());
    // The rejected part isn't stored.
    assertTrue(s3.listParts(b -> b.bucket(bucket).key("a.txt").uploadId(uploadId)).parts().isEmpty());
  }

  @Test
  @LocalS3
  void aMalformedContentMd5IsInvalidDigest(S3Client s3) {
    String bucket = "part-md5-malformed-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key("a.txt")).uploadId();

    // The base64 of 11 bytes, and not base64.
    for (String malformedMd5 : new String[] {"YWJyYWNhZGFicmE=", "not base64!"}) {
      S3Exception ex = assertThrows(S3Exception.class, () -> s3.uploadPart(b -> b.bucket(bucket).key("a.txt")
          .uploadId(uploadId).partNumber(1).contentMD5(malformedMd5), RequestBody.fromString("Hello")));
      assertEquals(400, ex.statusCode());
      assertEquals("InvalidDigest", ex.awsErrorDetails().errorCode());
    }
  }

}
