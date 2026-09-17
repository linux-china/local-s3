package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Appends ({@code x-amz-write-offset-bytes}) and {@code RenameObject}, which Amazon S3 offers for S3 Express One Zone,
 * through the AWS SDK.
 */
class AppendAndRenameObjectIntegrationTest {

  private static final String BUCKET = "express-bucket";

  @Test
  @LocalS3(buckets = BUCKET)
  void appendsToAnObject(S3Client s3) {
    s3.putObject(b -> b.bucket(BUCKET).key("log.txt").writeOffsetBytes(0L).contentType("text/plain")
        .metadata(java.util.Map.of("owner", "me")), RequestBody.fromString("Hello"));
    PutObjectResponse appended = s3.putObject(b -> b.bucket(BUCKET).key("log.txt").writeOffsetBytes(5L),
        RequestBody.fromString(", World"));
    assertEquals(12L, appended.size());

    assertEquals("Hello, World", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("log.txt")).asUtf8String());
    HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("log.txt"));
    assertEquals("text/plain", head.contentType());
    assertEquals("me", head.metadata().get("owner"));
    assertEquals(appended.eTag(), head.eTag());

    S3Exception wrongOffset = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET).key("log.txt")
        .writeOffsetBytes(5L), RequestBody.fromString("!")));
    assertEquals(400, wrongOffset.statusCode());
    assertEquals("InvalidWriteOffset", wrongOffset.awsErrorDetails().errorCode());
    assertEquals("InvalidWriteOffset", assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(BUCKET)
        .key("missing.txt").writeOffsetBytes(1L), RequestBody.fromString("!"))).awsErrorDetails().errorCode());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void renamesAnObject(S3Client s3) {
    s3.putObject(b -> b.bucket(BUCKET).key("old.txt").contentType("text/plain"), RequestBody.fromString("content"));
    s3.putObject(b -> b.bucket(BUCKET).key("taken.txt"), RequestBody.fromString("taken"));
    String etag = s3.headObject(b -> b.bucket(BUCKET).key("old.txt")).eTag();

    S3Exception exists = assertThrows(S3Exception.class, () -> s3.renameObject(b -> b.bucket(BUCKET)
        .key("taken.txt").renameSource(BUCKET + "/old.txt").destinationIfNoneMatch("*")));
    assertEquals(412, exists.statusCode());
    assertEquals(412, assertThrows(S3Exception.class, () -> s3.renameObject(b -> b.bucket(BUCKET).key("new.txt")
        .renameSource(BUCKET + "/old.txt").sourceIfMatch("\"not-the-etag\""))).statusCode());

    s3.renameObject(b -> b.bucket(BUCKET).key("dir/new.txt").renameSource(BUCKET + "/old.txt").sourceIfMatch(etag));
    assertEquals(404, assertThrows(S3Exception.class,
        () -> s3.headObject(b -> b.bucket(BUCKET).key("old.txt"))).statusCode());
    HeadObjectResponse renamed = s3.headObject(b -> b.bucket(BUCKET).key("dir/new.txt"));
    assertEquals(etag, renamed.eTag());
    assertEquals("text/plain", renamed.contentType());
    assertEquals("content", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("dir/new.txt")).asUtf8String());

    // The destination is replaced.
    s3.renameObject(b -> b.bucket(BUCKET).key("taken.txt").renameSource(BUCKET + "/dir/new.txt"));
    assertEquals("content", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("taken.txt")).asUtf8String());
    assertEquals(1, s3.listObjectsV2(b -> b.bucket(BUCKET)).keyCount());

    assertEquals(404, assertThrows(S3Exception.class, () -> s3.renameObject(b -> b.bucket(BUCKET).key("x.txt")
        .renameSource(BUCKET + "/missing.txt"))).statusCode());
    s3.putBucketVersioning(b -> b.bucket(BUCKET).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
    assertEquals("InvalidRequest", assertThrows(S3Exception.class, () -> s3.renameObject(b -> b.bucket(BUCKET)
        .key("y.txt").renameSource(BUCKET + "/taken.txt"))).awsErrorDetails().errorCode());
  }

}
