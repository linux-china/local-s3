package com.robothy.s3.rest;

import static com.robothy.s3.rest.fault.FaultMatcher.anyOp;
import static com.robothy.s3.rest.fault.FaultMatcher.op;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import com.robothy.s3.rest.fault.Fault;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

class FaultInjectionTest {

  private static final String BUCKET = "faults";

  private LocalS3 localS3;

  @BeforeEach
  void start() {
    localS3 = LocalS3.builder().port(-1).buckets(BUCKET, "other").build();
    localS3.start();
  }

  @AfterEach
  void stop() {
    localS3.shutdown();
  }

  @Test
  void slowDownIsRetriedByTheSdk() {
    Fault fault = localS3.faults().add(Fault.when(op("PutObject").bucket(BUCKET).times(2))
        .respond(S3ErrorCode.SlowDown));
    try (S3Client s3 = client(RetryMode.STANDARD)) {
      s3.putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString("hello"));
      assertEquals("hello", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")).asUtf8String());
      // Another bucket isn't matched.
      s3.putObject(b -> b.bucket("other").key("a.txt"), RequestBody.fromString("hello"));
    }
    assertEquals(2, fault.injected());
    assertEquals(3, fault.matched());
  }

  @Test
  void errorReachesAClientThatDoesNotRetry() {
    localS3.faults().add(Fault.when(op("GetObject").keyPrefix("data/")).respond(S3ErrorCode.InternalError));
    try (S3Client s3 = client(null)) {
      s3.putObject(b -> b.bucket(BUCKET).key("data/a.txt"), RequestBody.fromString("a"));
      s3.putObject(b -> b.bucket(BUCKET).key("logs/a.txt"), RequestBody.fromString("a"));

      S3Exception e = assertThrows(S3Exception.class, () -> s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("data/a.txt")));
      assertEquals(500, e.statusCode());
      assertEquals("InternalError", e.awsErrorDetails().errorCode());
      assertEquals("a", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("logs/a.txt")).asUtf8String());
    }
  }

  @Test
  void nthAndProbability() {
    Fault third = localS3.faults().add(Fault.when(op("HeadObject").nth(3)).respond(503, "ServiceUnavailable"));
    Fault never = localS3.faults().add(Fault.when(op("HeadObject")).probability(0).respond(500, "InternalError"));
    try (S3Client s3 = client(null)) {
      s3.putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString("a"));
      s3.headObject(b -> b.bucket(BUCKET).key("a.txt"));
      s3.headObject(b -> b.bucket(BUCKET).key("a.txt"));
      S3Exception e = assertThrows(S3Exception.class, () -> s3.headObject(b -> b.bucket(BUCKET).key("a.txt")));
      assertEquals(503, e.statusCode());
      s3.headObject(b -> b.bucket(BUCKET).key("a.txt"));
    }
    assertEquals(1, third.injected());
    assertEquals(4, never.matched());
    assertEquals(0, never.injected());
  }

  @Test
  void delayHoldsTheRequest() {
    localS3.faults().add(Fault.when(op("ListBuckets")).delay(Duration.ofMillis(500)));
    try (S3Client s3 = client(null)) {
      long start = System.nanoTime();
      s3.listBuckets();
      assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 500);
    }
  }

  @Test
  void closeConnectionDoesNotPerformTheOperation() throws IOException {
    localS3.faults().add(Fault.when(op("PutObject")).closeConnection());
    byte[] received = exchange("PUT /" + BUCKET + "/closed.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n"
        + "hello");
    assertEquals(0, received.length);
    localS3.faults().clear();
    try (S3Client s3 = client(null)) {
      assertThrows(NoSuchKeyException.class, () -> s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("closed.txt")));
    }
  }

  @Test
  void resetConnectionAfterBytesInMemory() throws IOException {
    assertBodyCutAfter(localS3, 1024);
  }

  @Test
  void resetConnectionAfterBytesOfAFile(@TempDir Path dataPath) throws IOException {
    LocalS3 persistent = LocalS3.builder().port(-1).mode(LocalS3Mode.PERSISTENCE).dataPath(dataPath.toString())
        .buckets(BUCKET).build();
    persistent.start();
    try {
      assertBodyCutAfter(persistent, 100_000);
    } finally {
      persistent.shutdown();
    }
  }

  @Test
  void throttleLimitsTheRate() {
    byte[] content = new byte[300 * 1024];
    localS3.faults().add(Fault.when(op("GetObject")).throttle(150 * 1024));
    try (S3Client s3 = client(null)) {
      s3.putObject(b -> b.bucket(BUCKET).key("big.bin"), RequestBody.fromBytes(content));
      long start = System.nanoTime();
      byte[] read = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("big.bin")).asByteArray();
      long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
      assertArrayEquals(content, read);
      assertTrue(millis >= 1000, "took " + millis + " ms");
      // The next response is no longer throttled.
      s3.headObject(b -> b.bucket(BUCKET).key("big.bin"));
    }
  }

  @Test
  void healthCheckAndAdminAreNeverFailed() throws IOException {
    localS3.faults().add(Fault.when(anyOp()).closeConnection());
    String response = new String(exchange("GET /_health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"),
        StandardCharsets.ISO_8859_1);
    assertTrue(response.startsWith("HTTP/1.1 200"), response);
  }

  @Test
  void resetRemovesTheFaults() {
    localS3.faults().add(Fault.when(anyOp()).respond(S3ErrorCode.SlowDown));
    localS3.reset();
    assertTrue(localS3.faults().list().isEmpty());
    try (S3Client s3 = client(null)) {
      s3.listBuckets();
    }
  }

  @Test
  void faultWithoutActionIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> localS3.faults().add(Fault.when(anyOp())));
    assertThrows(IllegalArgumentException.class,
        () -> localS3.faults().add(Fault.when(anyOp()).closeConnection().respond(S3ErrorCode.SlowDown)));
  }

  private static void assertBodyCutAfter(LocalS3 service, int bytes) throws IOException {
    byte[] content = new byte[256 * 1024];
    new Random(1).nextBytes(content);
    service.faults().add(Fault.when(op("GetObject").key("cut.bin")).resetConnectionAfterBytes(bytes));
    try (S3Client s3 = client(service, null)) {
      s3.putObject(b -> b.bucket(BUCKET).key("cut.bin"), RequestBody.fromBytes(content));
    }
    byte[] received = exchange(service, "GET /" + BUCKET + "/cut.bin HTTP/1.1\r\nHost: localhost\r\n\r\n");
    int headerEnd = indexOf(received, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)) + 4;
    String head = new String(received, 0, headerEnd, StandardCharsets.ISO_8859_1);
    assertTrue(head.startsWith("HTTP/1.1 200"), head);
    assertTrue(head.toLowerCase().contains("content-length: " + content.length), head);
    assertArrayEquals(Arrays.copyOf(content, bytes), Arrays.copyOfRange(received, headerEnd, received.length));
  }

  private byte[] exchange(String request) throws IOException {
    return exchange(localS3, request);
  }

  /**
   * Send a raw request and read until the server closes the connection.
   */
  private static byte[] exchange(LocalS3 service, String request) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", service.getPort())) {
      socket.setSoTimeout(10_000);
      OutputStream out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream received = new ByteArrayOutputStream();
      in.transferTo(received);
      return received.toByteArray();
    }
  }

  private static int indexOf(byte[] data, byte[] pattern) {
    outer:
    for (int i = 0; i <= data.length - pattern.length; i++) {
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) {
          continue outer;
        }
      }
      return i;
    }
    throw new AssertionError("No end of the headers.");
  }

  private S3Client client(RetryMode retryMode) {
    return client(localS3, retryMode);
  }

  /**
   * @param retryMode the retries; {@code null} for none.
   */
  private static S3Client client(LocalS3 service, RetryMode retryMode) {
    return S3Client.builder()
        .endpointOverride(URI.create(service.endpoint()))
        .region(Region.US_EAST_1)
        .credentialsProvider(AnonymousCredentialsProvider.create())
        .forcePathStyle(true)
        .overrideConfiguration(c -> {
          if (retryMode == null) {
            c.retryStrategy(b -> b.maxAttempts(1));
          } else {
            c.retryStrategy(retryMode);
          }
        })
        .build();
  }

}
