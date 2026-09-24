package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.ObjectAttributes;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * The entity tag that an object uploaded in parts gets, which
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CompleteMultipartUpload.html">Amazon S3</a>
 * builds from the entity tags of the parts: the MD5 digest of their concatenated digests, followed by
 * {@code -} and the number of parts. The {@code -<parts>} suffix is the part of it that client code reads,
 * to tell an object uploaded in parts from one that {@code PutObject} stored, so these tests assert the
 * shape of the entity tag rather than only that reads report the one that the upload answered.
 */
public class MultipartUploadEtagIntegrationTest {

  /**
   * The content of a part that other parts follow, which must be at least 5 MiB.
   */
  private static final String FIRST = Parts.large("Hello");

  @Test
  @LocalS3
  void completeMultipartUploadAnswersACompositeEtag(S3Client s3) {
    String bucket = "composite-etag-bucket";
    String key = "example.txt";
    s3.createBucket(b -> b.bucket(bucket));

    CompleteMultipartUploadResponse completed = upload(s3, bucket, key, FIRST, "World");

    // md5(md5(FIRST) + md5("World")) + "-2", the digests concatenated as bytes; computed outside of LocalS3.
    byte[] digests = ByteBuffer.allocate(32).put(Digests.md5(FIRST)).put(Digests.md5("World")).array();
    assertEquals(Etags.quoted(Digests.md5Hex(digests) + "-2"), completed.eTag());
    // Not the digest of the concatenated content, which is what LocalS3 answered before 2.5.
    assertNotEquals(Etags.md5(FIRST + "World"), completed.eTag());
  }

  /**
   * Client code that tells an object uploaded in parts from one uploaded at once reads the {@code -<parts>}
   * suffix of the entity tag, so it must be there for every read of the object, not only for the response of
   * the upload that created it.
   */
  @Test
  @LocalS3
  void readsOfTheObjectReportTheCompositeEtag(S3Client s3) {
    String bucket = "composite-etag-reads-bucket";
    String key = "example.txt";
    s3.createBucket(b -> b.bucket(bucket));

    String etag = upload(s3, bucket, key, FIRST, "World").eTag();

    assertEquals(etag, s3.headObject(b -> b.bucket(bucket).key(key)).eTag());
    assertEquals(etag, s3.getObject(b -> b.bucket(bucket).key(key)).response().eTag());
    // GetObjectAttributes answers the entity tag without its quotes, like Amazon S3 does.
    assertEquals(Etags.unquoted(etag),
        s3.getObjectAttributes(b -> b.bucket(bucket).key(key).objectAttributes(ObjectAttributes.E_TAG)).eTag());
    assertEquals(etag, s3.listObjectsV2(b -> b.bucket(bucket)).contents().get(0).eTag());
    assertEquals(etag, s3.listObjects(b -> b.bucket(bucket)).contents().get(0).eTag());
    assertTrue(Etags.unquoted(etag).endsWith("-2"), etag);
  }

  /**
   * An upload of a single part gets a composite entity tag as well, with the suffix {@code -1}: Amazon S3
   * builds the entity tag the same way however many parts an upload has.
   */
  @Test
  @LocalS3
  void anUploadOfASinglePartAnswersACompositeEtag(S3Client s3) {
    String bucket = "single-part-etag-bucket";
    String key = "example.txt";
    s3.createBucket(b -> b.bucket(bucket));

    CompleteMultipartUploadResponse completed = upload(s3, bucket, key, "Hello");

    assertEquals(Etags.quoted("49c24cf3c5af9ba03cec39ee4aac4f77-1"), completed.eTag());
    assertNotEquals(Etags.md5("Hello"), completed.eTag());
  }

  /**
   * {@code PutObject} stores an object at once, so its entity tag stays the digest of its content, with no
   * suffix. That is the distinction that the suffix of a composite entity tag carries.
   */
  @Test
  @LocalS3
  void putObjectAnswersTheDigestOfTheContent(S3Client s3) {
    String bucket = "put-object-etag-bucket";
    s3.createBucket(b -> b.bucket(bucket));

    String etag = s3.putObject(b -> b.bucket(bucket).key("a.txt"), RequestBody.fromString("HelloWorld")).eTag();

    assertEquals(Etags.md5("HelloWorld"), etag);
    assertFalse(etag.contains("-"), etag);
  }

  /**
   * The entity tag of a part stays the digest of the part, which is what the composite entity tag of the
   * object is built from, and what a client sends back in the {@code CompleteMultipartUpload} request.
   */
  @Test
  @LocalS3
  void thePartsKeepTheDigestsOfTheirData(S3Client s3) {
    String bucket = "part-etag-bucket";
    String key = "example.txt";
    s3.createBucket(b -> b.bucket(bucket));
    CreateMultipartUploadResponse created = s3.createMultipartUpload(b -> b.bucket(bucket).key(key));

    UploadPartResponse part = s3.uploadPart(b -> b.bucket(bucket).key(key)
        .uploadId(created.uploadId()).partNumber(1), RequestBody.fromString("Hello"));

    assertEquals(Etags.md5("Hello"), part.eTag());
    assertEquals(Etags.md5("Hello"),
        s3.listParts(b -> b.bucket(bucket).key(key).uploadId(created.uploadId())).parts().get(0).eTag());
  }

  /**
   * Copying an object that was uploaded in parts stores it at once, so the copy gets the digest of its
   * content with no suffix, which is what Amazon S3 answers for a copy that isn't itself a multipart one.
   */
  @Test
  @LocalS3
  void aCopyOfAnObjectUploadedInPartsGetsTheDigestOfTheContent(S3Client s3) {
    String bucket = "copy-etag-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    String uploaded = upload(s3, bucket, "source.txt", FIRST, "World").eTag();
    assertTrue(Etags.unquoted(uploaded).endsWith("-2"), uploaded);

    String copied = s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("source.txt")
        .destinationBucket(bucket).destinationKey("copy.txt")).copyObjectResult().eTag();

    assertEquals(Etags.md5(FIRST + "World"), copied);
  }

  /**
   * A service configured with {@code compositeMultipartEtags = false} answers the entity tags that LocalS3
   * answered before 2.5, so that a test that asserts the digest of the whole content keeps working.
   */
  @Test
  @LocalS3(compositeMultipartEtags = false)
  void theCompositeEtagsCanBeTurnedOff(S3Client s3) {
    String bucket = "legacy-etag-bucket";
    String key = "example.txt";
    s3.createBucket(b -> b.bucket(bucket));

    CompleteMultipartUploadResponse completed = upload(s3, bucket, key, FIRST, "World");

    assertEquals(Etags.md5(FIRST + "World"), completed.eTag());
    assertEquals(completed.eTag(), s3.headObject(b -> b.bucket(bucket).key(key)).eTag());
  }

  /**
   * Upload the given parts, in the order they are given, and complete the upload.
   */
  private static CompleteMultipartUploadResponse upload(S3Client s3, String bucket, String key, String... parts) {
    CreateMultipartUploadResponse created = s3.createMultipartUpload(b -> b.bucket(bucket).key(key));
    List<CompletedPart> completedParts = new ArrayList<>(parts.length);
    for (int i = 0; i < parts.length; i++) {
      int partNumber = i + 1;
      UploadPartResponse part = s3.uploadPart(b -> b.bucket(bucket).key(key)
          .uploadId(created.uploadId()).partNumber(partNumber), RequestBody.fromString(parts[partNumber - 1]));
      completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(part.eTag()).build());
    }
    return s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(created.uploadId())
        .multipartUpload(mu -> mu.parts(completedParts)));
  }

}
