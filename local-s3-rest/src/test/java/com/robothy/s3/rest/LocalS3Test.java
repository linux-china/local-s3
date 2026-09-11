package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Test;

class LocalS3Test {

  @Test
  void start() throws Exception {
    LocalS3 localS3 = LocalS3.builder()
        .bindHost("127.0.0.1")
        .port(-1)
        .build();
    localS3.start();
    localS3.shutdown();

    Path tempDirectory = Files.createTempDirectory("local-s3");
    localS3 = LocalS3.builder()
        .port(-1)
        .dataPath(tempDirectory.toAbsolutePath().toString())
        .build();
    localS3.start();
    localS3.shutdown();
    localS3.shutdown();
  }

  @Test
  void testBuilder() {
    LocalS3 localS3 = LocalS3.builder()
        .dataPath("/tmp")
        .build();
    assertEquals("127.0.0.1", localS3.getBindHost());
    assertTrue(localS3.getDataPath().endsWith("tmp"));
    assertTrue(localS3.getPort() > 0);

    LocalS3 loopbackOnly = LocalS3.builder()
        .bindHost("127.0.0.1")
        .build();
    assertEquals("127.0.0.1", loopbackOnly.getBindHost());
    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().bindHost(" "));
    assertEquals(LocalS3.DEFAULT_MAX_REQUEST_BODY_SIZE, localS3.getMaxRequestBodySize());
    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().maxRequestBodySize(0));
    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().maxRequestBodySize(Integer.MAX_VALUE + 1L));
  }

  @Test
  void rejectsRequestBodyLargerThanLimit() throws Exception {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .maxRequestBodySize(1024)
        .buckets("limit-bucket")
        .build();
    localS3.start();
    try {
      HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      String objectUrl = "http://127.0.0.1:" + localS3.getPort() + "/limit-bucket/";

      HttpResponse<String> accepted = client.send(HttpRequest.newBuilder(URI.create(objectUrl + "small"))
          .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[1024])).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());

      HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(URI.create(objectUrl + "large"))
          .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[1025])).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(400, rejected.statusCode());
      assertTrue(rejected.body().contains("EntityTooLarge"));
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void shutdownRemovesShutdownHook() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).build();
    localS3.start();
    Thread hook = (Thread) FieldUtils.readField(localS3, "shutdownHook", true);
    assertNotNull(hook);

    localS3.shutdown();

    assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "The shutdown hook should already be removed.");
    assertDoesNotThrow(localS3::shutdown);
  }

  @Test
  void handlesRequestsOnExecutorThreads() throws Exception {
    AtomicReference<String> handlerThread = new AtomicReference<>();
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .buckets("thread-bucket")
        .objectEventListener(event -> handlerThread.set(Thread.currentThread().getName()))
        .build();
    localS3.start();
    try {
      HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      HttpResponse<String> response = client.send(HttpRequest.newBuilder(
              URI.create("http://127.0.0.1:" + localS3.getPort() + "/thread-bucket/a.txt"))
          .PUT(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      // Object event listeners run synchronously on the thread handling the request.
      assertTrue(handlerThread.get().startsWith("locals3-executor-group"),
          "Request handled on " + handlerThread.get() + " instead of the executor group.");
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void createsTrimmedDefaultBucketsAndPersistsThem() throws Exception {
    Path dataPath = Files.createTempDirectory("local-s3");
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .mode(LocalS3Mode.PERSISTENCE)
        .dataPath(dataPath.toString())
        .buckets("a-bucket", " b-bucket ", " ", "")
        .build();
    localS3.start();
    try {
      BucketService bucketService = localS3.getS3Manager().bucketService();
      assertEquals(2, bucketService.listBuckets().size());
      assertDoesNotThrow(() -> bucketService.getBucket("a-bucket"));
      assertDoesNotThrow(() -> bucketService.getBucket("b-bucket"));
      assertThrows(BucketNotExistException.class, () -> bucketService.getBucket(" b-bucket "));
      assertTrue(Files.isRegularFile(dataPath.resolve("a-bucket.bucket.meta")));
      assertTrue(Files.isRegularFile(dataPath.resolve("b-bucket.bucket.meta")));
    } finally {
      localS3.shutdown();
      FileUtils.deleteDirectory(dataPath.toFile());
    }
  }

  @Test
  void storesBodiesBufferedInTemporaryFiles() throws Exception {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .requestBodyFileThreshold(1024)
        .buckets("file-bucket")
        .build();
    localS3.start();
    try {
      byte[] content = new byte[1024 * 1024 + 7];
      new Random(42).nextBytes(content);
      HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      URI objectUrl = URI.create("http://127.0.0.1:" + localS3.getPort() + "/file-bucket/large");

      HttpResponse<Void> put = client.send(HttpRequest.newBuilder(objectUrl)
          .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.discarding());
      assertEquals(200, put.statusCode());

      HttpResponse<byte[]> get = client.send(HttpRequest.newBuilder(objectUrl).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, get.statusCode());
      assertArrayEquals(content, get.body());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void builtInstancesDontDependOnTheBuilder() {
    LocalS3.Builder builder = LocalS3.builder().port(-1).buckets("first");
    LocalS3 first = builder.build();
    builder.bindHost("0.0.0.0").buckets("second");
    LocalS3 second = builder.build();

    assertEquals("127.0.0.1", first.getBindHost());
    assertEquals("0.0.0.0", second.getBindHost());
    first.start();
    try {
      BucketService bucketService = first.getS3Manager().bucketService();
      assertEquals(1, bucketService.listBuckets().size());
      assertDoesNotThrow(() -> bucketService.getBucket("first"));
    } finally {
      first.shutdown();
    }
  }

  @Test
  void rejectsSecondStartAndRestartsAfterShutdown() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).build();
    localS3.start();
    try {
      assertThrows(IllegalStateException.class, localS3::start);
      assertEquals(200, listBuckets(localS3.getPort()));
    } finally {
      localS3.shutdown();
    }

    localS3.start();
    try {
      assertEquals(200, listBuckets(localS3.getPort()));
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void failedStartReleasesResourcesAndKeepsItsException() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      long threads = countLocalS3Threads();
      LocalS3 localS3 = LocalS3.builder().port(occupied.getLocalPort()).build();

      Throwable thrown = assertThrows(Throwable.class, () -> {
        try (LocalS3 closed = localS3) {
          closed.start();
        }
      });
      assertInstanceOf(BindException.class, thrown, "close() must not hide the exception of start().");
      // Netty's sync() records its call site as a suppressed CompletionException; close() adds nothing.
      for (Throwable suppressed : thrown.getSuppressed()) {
        assertInstanceOf(CompletionException.class, suppressed);
      }

      long deadline = System.currentTimeMillis() + 5_000;
      while (countLocalS3Threads() > threads && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
      }
      assertEquals(threads, countLocalS3Threads(), "The event loop threads are released.");
      assertDoesNotThrow(localS3::shutdown);
    }
  }

  @Test
  void failureBeforeBindingKeepsItsException() {
    LocalS3 invalid = LocalS3.builder().port(-1).mode(LocalS3Mode.PERSISTENCE).build();
    NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
      try (LocalS3 closed = invalid) {
        closed.start();
      }
    });
    assertEquals(0, thrown.getSuppressed().length);
  }

  private static int listBuckets(int port) throws Exception {
    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
        HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static long countLocalS3Threads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.isAlive() && thread.getName().startsWith("locals3-")
            && !thread.getName().equals("locals3-shutdown-hook"))
        .count();
  }

  @Test
  void respondsWithS3ErrorsToUnsupportedRequests() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).build();
    localS3.start();
    try {
      HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      URI uri = URI.create("http://127.0.0.1:" + localS3.getPort() + "/bucket");

      HttpResponse<String> xml = client.send(HttpRequest.newBuilder(uri)
          .method("PATCH", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(501, xml.statusCode());
      assertEquals("application/xml", xml.headers().firstValue("content-type").orElse(null));
      assertTrue(xml.body().contains("<Code>NotImplemented</Code>"), xml.body());

      HttpResponse<String> json = client.send(HttpRequest.newBuilder(uri)
          .header("Content-Type", "application/json")
          .method("PATCH", HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(404, json.statusCode());
      assertEquals("NotFoundException", json.headers().firstValue("x-amzn-errortype").orElse(null));
      assertTrue(json.body().startsWith("{\"message\":"), json.body());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void validatesBucketNamesStrictlyWhenEnabled() throws Exception {
    assertThrows(InvalidBucketNameException.class,
        () -> LocalS3.builder().port(-1).strictBucketNames(true).buckets("My_Bucket").build().start());

    LocalS3 strict = LocalS3.builder().port(-1).strictBucketNames(true).build();
    strict.start();
    LocalS3 lenient = LocalS3.builder().port(-1).build();
    lenient.start();
    try {
      assertTrue(strict.isStrictBucketNames());
      HttpResponse<String> rejected = createBucket(strict.getPort(), "My_Bucket");
      assertEquals(400, rejected.statusCode());
      assertTrue(rejected.body().contains("<Code>InvalidBucketName</Code>"), rejected.body());
      assertEquals(200, createBucket(strict.getPort(), "my-bucket").statusCode());

      assertFalse(lenient.isStrictBucketNames());
      assertEquals(200, createBucket(lenient.getPort(), "My_Bucket").statusCode());
    } finally {
      strict.shutdown();
      lenient.shutdown();
    }
  }

  private static HttpResponse<String> createBucket(int port, String bucketName) throws Exception {
    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + bucketName))
        .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void bindsRandomPortOnStart() throws Exception {
    LocalS3 first = LocalS3.builder().port(-1).build();
    LocalS3 second = LocalS3.builder().port(0).build();
    assertEquals(0, first.getPort(), "A random port is only known once bound.");
    first.start();
    second.start();
    try {
      assertTrue(first.getPort() > 0);
      assertTrue(second.getPort() > 0);
      assertNotEquals(first.getPort(), second.getPort());

      HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      HttpResponse<String> response = client.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + first.getPort() + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
    } finally {
      first.shutdown();
      second.shutdown();
    }
    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().port(65536));
  }

  @Test
  void restartsOnSamePort() {
    LocalS3 random = LocalS3.builder().port(-1).build();
    random.start();
    int port = random.getPort();
    random.shutdown();
    for (int i = 0; i < 3; i++) {
      LocalS3 localS3 = LocalS3.builder().port(port).build();
      localS3.start();
      long begin = System.nanoTime();
      localS3.shutdown();
      long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
      assertTrue(elapsedMillis < 5_000, "shutdown() took " + elapsedMillis + " ms");
    }
  }
}
