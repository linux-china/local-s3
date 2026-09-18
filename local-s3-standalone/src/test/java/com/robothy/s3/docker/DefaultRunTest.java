package com.robothy.s3.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.testcontainers.LocalS3Container;
import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code docker run luofuxiang/local-s3} with nothing else: the image runs {@code PERSISTENCE} over {@code /data},
 * and {@code docker run} creates that as an anonymous volume.
 *
 * <p>The volume is initialized from the {@code /data} of the image, ownership included, so the directory has to
 * belong to the {@code locals3} user the service runs as. Left to root, the service started and then failed to
 * open its store with {@code AccessDeniedException: /data/buckets.mvstore}. The other image tests bind-mount a
 * directory of the host, which keeps the ownership of the host and hides this, so this one takes the volume the
 * image makes by itself.
 */
@Tag(ImageUnderTest.JUNIT_TAG)
@Testcontainers
class DefaultRunTest {

  /**
   * No mode and no data path: the defaults of the image alone.
   */
  @Container
  private final LocalS3Container container = new LocalS3Container(ImageUnderTest.TAG).withRandomHttpPort();

  @Test
  void persistsToTheVolumeThatTheImageDeclares() {
    assertTrue(container.isRunning());
    try (S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create("http://localhost:" + container.getPort()))
        .forcePathStyle(true)
        .region(Region.US_EAST_1)
        .credentialsProvider(AnonymousCredentialsProvider.create())
        .build()) {

      s3.createBucket(request -> request.bucket("my-bucket"));
      s3.putObject(request -> request.bucket("my-bucket").key("a.txt"), RequestBody.fromString("Hello World"));

      assertEquals("Hello World",
          s3.getObjectAsBytes(request -> request.bucket("my-bucket").key("a.txt")).asUtf8String());
    }
  }

}
