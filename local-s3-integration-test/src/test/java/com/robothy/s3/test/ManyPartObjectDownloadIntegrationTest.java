package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Reads an object of many parts, the way Spark or DuckDB read one that was written in parts: the content of the
 * object is the content of its parts, which are sent one after another.
 *
 * <p>The parts are opened one at a time, so that a read of an object of hundreds of parts doesn't hold a file per
 * part open, which a handful of concurrent reads would run the process out of file descriptors with. The test reads
 * the object in small steps, so that the response is still in flight while it counts the files the process has open.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class ManyPartObjectDownloadIntegrationTest {

  private static final String BUCKET = "many-parts";

  private static final String KEY = "many-parts.bin";

  /**
   * The smallest part that Amazon S3, and this service, accept for a part that isn't the last one.
   */
  private static final int PART_SIZE = 5 * 1024 * 1024;

  private static final int PARTS = 48;

  /**
   * The parts that the process may hold open at a time while it sends the object; far below {@linkplain #PARTS},
   * with room for the files that the service and the test client hold anyway.
   */
  private static final long MAX_FILES_OPENED_WHILE_READING = 24;

  private LocalS3 localS3;

  private S3Client s3;

  @AfterEach
  void tearDown() {
    if (s3 != null) {
      s3.close();
    }
    if (localS3 != null) {
      localS3.shutdown();
    }
  }

  @Test
  void readsAnObjectOfManyPartsWithoutHoldingThemAllOpen(@TempDir Path dataPath) throws IOException {
    localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.PERSISTENCE).dataPath(dataPath.toString())
        .buckets(BUCKET).build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
        .forcePathStyle(true)
        .build();

    uploadInParts();

    long openFilesBefore = openFiles();
    long peakOpenFiles = openFilesBefore;
    long total = 0;
    // A buffer far smaller than a part, so that the response is still in flight while the files are counted.
    byte[] buffer = new byte[16 * 1024];
    byte[] expected = new byte[buffer.length];
    try (ResponseInputStream<GetObjectResponse> in = s3.getObject(request -> request.bucket(BUCKET).key(KEY))) {
      for (int part = 0; part < PARTS; part++) {
        Random content = partContent(part);
        for (int offset = 0; offset < PART_SIZE; offset += buffer.length) {
          in.readNBytes(buffer, 0, buffer.length);
          content.nextBytes(expected);
          assertArrayEquals(expected, buffer, "The content at " + total + ".");
          total += buffer.length;
          if (offset % (buffer.length * 16) == 0) {
            peakOpenFiles = Math.max(peakOpenFiles, openFiles());
          }
        }
      }
      assertEquals(-1, in.read(), "The content ends with the last part.");
    }

    assertEquals((long) PARTS * PART_SIZE, total);
    if (openFilesBefore >= 0) {
      long opened = peakOpenFiles - openFilesBefore;
      assertTrue(opened <= MAX_FILES_OPENED_WHILE_READING, "Sending an object of " + PARTS + " parts held " + opened
          + " more files open; the parts are opened one at a time.");
    }
  }

  /**
   * Upload the object in {@linkplain #PARTS} parts, each of which becomes a file of its own.
   */
  private void uploadInParts() {
    String uploadId = s3.createMultipartUpload(request -> request.bucket(BUCKET).key(KEY)).uploadId();
    List<CompletedPart> parts = new ArrayList<>();
    byte[] content = new byte[PART_SIZE];
    for (int number = 1; number <= PARTS; number++) {
      partContent(number - 1).nextBytes(content);
      int partNumber = number;
      String eTag = s3.uploadPart(request -> request.bucket(BUCKET).key(KEY).uploadId(uploadId)
          .partNumber(partNumber), RequestBody.fromBytes(content)).eTag();
      parts.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
    }
    s3.completeMultipartUpload(request -> request.bucket(BUCKET).key(KEY).uploadId(uploadId)
        .multipartUpload(upload -> upload.parts(parts)));
  }

  /**
   * The content of a part, which is generated rather than kept, so that the test holds no copy of the object.
   */
  private static Random partContent(int part) {
    return new Random(1000L + part);
  }

  /**
   * The number of files the process has open; {@code -1} where the JVM doesn't tell, e.g. on Windows.
   */
  private static long openFiles() {
    OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    return os instanceof com.sun.management.UnixOperatingSystemMXBean unix ? unix.getOpenFileDescriptorCount() : -1;
  }

}
