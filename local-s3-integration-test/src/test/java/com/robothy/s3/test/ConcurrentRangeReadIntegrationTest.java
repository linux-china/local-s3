package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.admin.RequestStatistics;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Many small range reads at once, the way DuckDB, Trino or Iceberg read a Parquet file: the footer with a suffix range
 * ({@code bytes=-N}) first, then the column chunks that a query needs, from several threads, over as many connections.
 *
 * <p>Every response is compared with the bytes of the object, so that a read that returns the bytes of another read,
 * e.g. of a buffer or a file channel shared by the requests of an event loop, fails the test instead of a query
 * returning wrong results. Both storage modes are covered, and both an object uploaded at once and one uploaded in
 * parts, whose ranges cross the boundaries of the parts.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ConcurrentRangeReadIntegrationTest {

  private static final String BUCKET = "ranges";

  private static final int MIB = 1024 * 1024;

  private static final int THREADS = 32;

  private static final int READS_PER_THREAD = 250;

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

  @ParameterizedTest
  @EnumSource(LocalS3Mode.class)
  void concurrentSmallRangeReadsReturnTheBytesOfTheirRanges(LocalS3Mode mode, @TempDir Path dataPath)
      throws Exception {
    localS3 = LocalS3.builder().port(-1).mode(mode).dataPath(dataPath.toString()).buckets(BUCKET).build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
        .forcePathStyle(true)
        .httpClient(ApacheHttpClient.builder().maxConnections(THREADS).build())
        .build();

    byte[] single = randomBytes(8 * MIB + 123, 1);
    s3.putObject(request -> request.bucket(BUCKET).key("single.parquet"), RequestBody.fromBytes(single));
    byte[] multipart = randomBytes(3 * 5 * MIB + MIB + 7, 2);
    uploadInParts("multipart.parquet", multipart, 5 * MIB);

    List<Target> targets = List.of(new Target("single.parquet", single), new Target("multipart.parquet", multipart));

    CyclicBarrier start = new CyclicBarrier(THREADS);
    ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    try {
      List<Future<Integer>> readers = new ArrayList<>();
      for (int thread = 0; thread < THREADS; thread++) {
        SplittableRandom random = new SplittableRandom(thread);
        readers.add(executor.submit(() -> {
          start.await();
          for (int read = 0; read < READS_PER_THREAD; read++) {
            readAndVerify(targets.get(read % targets.size()), random);
          }
          return READS_PER_THREAD;
        }));
      }
      int reads = 0;
      for (Future<Integer> reader : readers) {
        reads += reader.get();
      }
      assertEquals(THREADS * READS_PER_THREAD, reads);
    } finally {
      executor.shutdownNow();
    }

    RequestStatistics.OperationStatistics gets = localS3.statistics().operations().get("GetObject");
    assertEquals(0, gets.clientErrors(), gets.toString());
    assertEquals(0, gets.serverErrors(), gets.toString());
  }

  /**
   * One of the ranges that a Parquet reader asks for: the footer, a small chunk anywhere, a chunk across the boundary
   * of two parts, or the rest of the object from an offset.
   */
  private void readAndVerify(Target target, SplittableRandom random) {
    int size = target.bytes().length;
    long first;
    long last;
    String range;
    switch (random.nextInt(4)) {
      case 0 -> {
        int suffix = 1 + random.nextInt(64 * 1024);
        first = size - suffix;
        last = size - 1;
        range = "bytes=-" + suffix;
      }
      case 1 -> {
        first = random.nextInt(size);
        last = Math.min(size - 1, first + random.nextInt(256 * 1024));
        range = "bytes=" + first + "-" + last;
      }
      case 2 -> {
        long boundary = 5L * MIB * (1 + random.nextInt(size / (5 * MIB)));
        first = Math.max(0, boundary - 1 - random.nextInt(4096));
        last = Math.min(size - 1, boundary + random.nextInt(4096));
        range = "bytes=" + first + "-" + last;
      }
      default -> {
        first = size - 1 - random.nextInt(128 * 1024);
        last = size - 1;
        range = "bytes=" + first + "-";
      }
    }

    ResponseBytes<GetObjectResponse> response =
        s3.getObjectAsBytes(request -> request.bucket(BUCKET).key(target.key()).range(range));
    String description = target.key() + " " + range;
    assertEquals("bytes " + first + "-" + last + "/" + size, response.response().contentRange(), description);
    assertEquals(last - first + 1, response.response().contentLength(), description);
    assertArrayEquals(Arrays.copyOfRange(target.bytes(), (int) first, (int) last + 1), response.asByteArray(),
        description);
  }

  private void uploadInParts(String key, byte[] bytes, int partSize) {
    String uploadId = s3.createMultipartUpload(request -> request.bucket(BUCKET).key(key)).uploadId();
    List<CompletedPart> parts = new ArrayList<>();
    for (int offset = 0, number = 1; offset < bytes.length; offset += partSize, number++) {
      byte[] part = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + partSize));
      int partNumber = number;
      String eTag = s3.uploadPart(request -> request.bucket(BUCKET).key(key).uploadId(uploadId).partNumber(partNumber),
          RequestBody.fromBytes(part)).eTag();
      parts.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
    }
    s3.completeMultipartUpload(request -> request.bucket(BUCKET).key(key).uploadId(uploadId)
        .multipartUpload(upload -> upload.parts(parts)));
  }

  private static byte[] randomBytes(int size, long seed) {
    byte[] bytes = new byte[size];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }

  private record Target(String key, byte[] bytes) {
  }

}
