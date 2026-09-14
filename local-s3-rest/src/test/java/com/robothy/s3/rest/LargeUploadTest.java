package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Uploads whose bodies are buffered in files, and objects completed from multipart uploads, served over HTTP.
 */
class LargeUploadTest {

  private static final int FILE_THRESHOLD = 1024;

  private final HttpClient client = HttpClient.newHttpClient();

  /**
   * In {@code PERSISTENCE} mode the body file of an upload is created in the storage directory and renamed into
   * place: no copy of the body is left behind, and no body file either.
   */
  @Test
  void storesTheBodyFileOfAnUploadWithoutCopyingIt(@TempDir Path dataPath) throws Exception {
    LocalS3 localS3 = start(LocalS3Mode.PERSISTENCE, dataPath);
    try {
      byte[] content = randomBytes(64 * 1024);
      HttpResponse<String> put = send(localS3, "PUT", "/bucket/large", content);
      assertEquals(200, put.statusCode(), put.body());

      assertArrayEquals(content, get(localS3, "/bucket/large", null).body());
      Path storageDirectory = dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY);
      assertEquals(1, countFiles(storageDirectory), "The object is stored once.");
      assertEquals(0, countFiles(storageDirectory.resolve(LocalS3.REQUEST_BODY_DIRECTORY)),
          "The body file was renamed into place.");
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * The body file of an upload is written off the event loop; a request pipelined after the upload on the same
   * connection is still answered after it, and sees the uploaded object.
   */
  @ParameterizedTest
  @EnumSource(LocalS3Mode.class)
  void answersARequestPipelinedAfterAnUploadInOrder(LocalS3Mode mode, @TempDir Path dataPath) throws Exception {
    LocalS3 localS3 = start(mode, dataPath);
    try {
      // Larger than the high water mark of a body file, so that reading is suspended while the file is written.
      byte[] content = randomBytes(6 * 1024 * 1024);
      byte[] head = ("PUT /bucket/pipelined HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + content.length
          + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
      byte[] get = "GET /bucket/pipelined HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
          .getBytes(StandardCharsets.ISO_8859_1);
      try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
        socket.setSoTimeout(30_000);
        socket.getOutputStream().write(concat(head, content, get));
        socket.getOutputStream().flush();
        byte[] responses = socket.getInputStream().readAllBytes();

        String text = new String(responses, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("HTTP/1.1 200 "), text.substring(0, Math.min(200, text.length())));
        int second = text.indexOf("HTTP/1.1 200 ", 1);
        assertTrue(second > 0, "The pipelined GET is answered after the PUT.");
        int bodyStart = text.indexOf("\r\n\r\n", second) + 4;
        assertArrayEquals(content, Arrays.copyOfRange(responses, bodyStart, responses.length));
      }
    } finally {
      localS3.shutdown();
    }
  }

  @ParameterizedTest
  @EnumSource(LocalS3Mode.class)
  void servesAnObjectCompletedFromParts(LocalS3Mode mode, @TempDir Path dataPath) throws Exception {
    LocalS3 localS3 = start(mode, dataPath);
    try {
      byte[] part1 = randomBytes(8 * 1024);
      byte[] part2 = randomBytes(300);
      byte[] part3 = randomBytes(5 * 1024);
      byte[] content = concat(part1, part2, part3);

      HttpResponse<String> created = send(localS3, "POST", "/bucket/parts?uploads", new byte[0]);
      Matcher uploadId = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(created.body());
      assertTrue(uploadId.find(), created.body());
      StringBuilder completion = new StringBuilder("<CompleteMultipartUpload>");
      List<byte[]> parts = List.of(part1, part2, part3);
      for (int i = 0; i < parts.size(); i++) {
        HttpResponse<String> uploaded = send(localS3, "PUT",
            "/bucket/parts?partNumber=" + (i + 1) + "&uploadId=" + uploadId.group(1), parts.get(i));
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        completion.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
            .append(uploaded.headers().firstValue("ETag").orElseThrow()).append("</ETag></Part>");
      }
      completion.append("</CompleteMultipartUpload>");
      HttpResponse<String> completed = send(localS3, "POST", "/bucket/parts?uploadId=" + uploadId.group(1),
          completion.toString().getBytes());
      assertEquals(200, completed.statusCode(), completed.body());
      assertTrue(completed.body().contains("-3"), "The entity tag of an object uploaded in 3 parts.");

      HttpResponse<byte[]> whole = get(localS3, "/bucket/parts", null);
      assertEquals(200, whole.statusCode());
      assertEquals(String.valueOf(content.length), whole.headers().firstValue("Content-Length").orElseThrow());
      assertArrayEquals(content, whole.body());

      // A range across the three parts.
      int start = part1.length - 10;
      int end = part1.length + part2.length + 10;
      HttpResponse<byte[]> range = get(localS3, "/bucket/parts", "bytes=" + start + "-" + end);
      assertEquals(206, range.statusCode());
      assertArrayEquals(Arrays.copyOfRange(content, start, end + 1), range.body());

      // A range within a part.
      range = get(localS3, "/bucket/parts", "bytes=100-199");
      assertArrayEquals(Arrays.copyOfRange(content, 100, 200), range.body());
    } finally {
      localS3.shutdown();
    }
  }

  private static LocalS3 start(LocalS3Mode mode, Path dataPath) {
    LocalS3Builder builder = LocalS3.builder()
        .port(-1)
        .mode(mode)
        .buckets("bucket")
        .requestBodyFileThreshold(FILE_THRESHOLD);
    if (mode == LocalS3Mode.PERSISTENCE) {
      builder.dataPath(dataPath.toString());
    }
    LocalS3 localS3 = builder.build();
    localS3.start();
    return localS3;
  }

  private HttpResponse<String> send(LocalS3 localS3, String method, String path, byte[] body) throws Exception {
    return client.send(HttpRequest.newBuilder(uri(localS3, path))
        .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<byte[]> get(LocalS3 localS3, String path, String range) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(localS3, path)).GET();
    if (range != null) {
      request.header("Range", range);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static URI uri(LocalS3 localS3, String path) {
    return URI.create("http://127.0.0.1:" + localS3.getPort() + path);
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }

  private static byte[] concat(byte[]... arrays) {
    byte[] result = new byte[Arrays.stream(arrays).mapToInt(array -> array.length).sum()];
    int offset = 0;
    for (byte[] array : arrays) {
      System.arraycopy(array, 0, result, offset, array.length);
      offset += array.length;
    }
    return result;
  }

  private static long countFiles(Path directory) throws IOException {
    // The object files are spread over subdirectories.
    try (Stream<Path> files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).count();
    }
  }

}
