package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * A negative {@code max-keys} used to be accepted and to return every key of the bucket, because the number of
 * collected keys never reaches it. The listings reject it instead, like Amazon S3 does.
 */
public class MaxKeysIntegrationTest {

  private static final String BUCKET = "max-keys-bucket";

  /**
   * The three listings that take {@code max-keys}, each asking for the given number of keys.
   */
  private static Stream<Consumer<Integer>> listings(S3Client s3) {
    return Stream.of(
        maxKeys -> s3.listObjects(b -> b.bucket(BUCKET).maxKeys(maxKeys)),
        maxKeys -> s3.listObjectsV2(b -> b.bucket(BUCKET).maxKeys(maxKeys)),
        maxKeys -> s3.listObjectVersions(b -> b.bucket(BUCKET).maxKeys(maxKeys)));
  }

  private static void prepare(S3Client s3) {
    s3.createBucket(b -> b.bucket(BUCKET));
    s3.putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString("a"));
    s3.putObject(b -> b.bucket(BUCKET).key("b.txt"), RequestBody.fromString("b"));
  }

  @Test
  @LocalS3
  void rejectsNegativeMaxKeys(S3Client s3) {
    prepare(s3);
    listings(s3).forEach(listing -> {
      S3Exception thrown = assertThrows(S3Exception.class, () -> listing.accept(-1));
      assertEquals(400, thrown.statusCode());
      assertEquals("InvalidArgument", thrown.awsErrorDetails().errorCode());
    });
  }

  /**
   * Amazon S3 answers a request for more than 1000 keys with 1000 keys, rather than rejecting it.
   */
  @Test
  @LocalS3
  void capsMaxKeysAboveTheLimitInsteadOfRejectingIt(S3Client s3) {
    prepare(s3);
    listings(s3).forEach(listing -> listing.accept(5000));

    assertEquals(2, s3.listObjectsV2(b -> b.bucket(BUCKET).maxKeys(5000)).contents().size());
    assertEquals(1000, s3.listObjectsV2(b -> b.bucket(BUCKET).maxKeys(5000)).maxKeys());
  }

  @Test
  @LocalS3
  void acceptsZeroMaxKeys(S3Client s3) {
    prepare(s3);
    listings(s3).forEach(listing -> listing.accept(0));

    assertEquals(0, s3.listObjectsV2(b -> b.bucket(BUCKET).maxKeys(0)).contents().size());
  }

}
