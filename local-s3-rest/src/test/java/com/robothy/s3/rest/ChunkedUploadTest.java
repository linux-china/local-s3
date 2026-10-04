package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A {@code PutObject} or {@code UploadPart} whose body is sent with {@code Transfer-Encoding: chunked} and no
 * {@code Content-Length}, e.g. by {@code curl -T -} or a streaming {@code fetch}, is stored by default, and answered
 * {@code 411 MissingContentLength} like Amazon S3 does with {@code acceptChunkedUploads(false)}.
 */
class ChunkedUploadTest {

  /**
   * Larger than the default threshold above which a body is received into a file, or into the in-memory storage.
   */
  private static final int LARGE = 6 * 1024 * 1024;

  private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @TempDir
  Path dataPath;

  @ParameterizedTest
  @EnumSource(LocalS3Mode.class)
  void storesSmallAndLargeChunkedPutObjectBodies(LocalS3Mode mode) throws Exception {
    try (LocalS3 localS3 = start(mode, builder -> { })) {
      for (int size : new int[] {0, 5, LARGE}) {
        byte[] content = randomBytes(size);
        HttpResponse<String> put = send(localS3, "PUT", "/chunked/object-" + size, content);
        assertEquals(200, put.statusCode(), put.body());
        assertEquals("\"" + md5Hex(content) + "\"", put.headers().firstValue("ETag").orElseThrow());

        HttpResponse<byte[]> get = HTTP.send(HttpRequest.newBuilder(uri(localS3, "/chunked/object-" + size)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, get.statusCode());
        assertEquals(String.valueOf(size), get.headers().firstValue("Content-Length").orElseThrow());
        assertArrayEquals(content, get.body());
      }
    }
  }

  @ParameterizedTest
  @EnumSource(LocalS3Mode.class)
  void storesChunkedUploadPartBodies(LocalS3Mode mode) throws Exception {
    try (LocalS3 localS3 = start(mode, builder -> { })) {
      HttpResponse<String> create = send(localS3, "POST", "/chunked/multipart?uploads", null);
      Matcher uploadId = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(create.body());
      assertTrue(uploadId.find(), create.body());

      byte[] first = randomBytes(LARGE);
      byte[] last = randomBytes(1024);
      String firstEtag = send(localS3, "PUT", "/chunked/multipart?partNumber=1&uploadId=" + uploadId.group(1), first)
          .headers().firstValue("ETag").orElseThrow();
      String lastEtag = send(localS3, "PUT", "/chunked/multipart?partNumber=2&uploadId=" + uploadId.group(1), last)
          .headers().firstValue("ETag").orElseThrow();
      assertEquals("\"" + md5Hex(first) + "\"", firstEtag);
      assertEquals("\"" + md5Hex(last) + "\"", lastEtag);

      String complete = "<CompleteMultipartUpload>"
          + "<Part><PartNumber>1</PartNumber><ETag>" + firstEtag + "</ETag></Part>"
          + "<Part><PartNumber>2</PartNumber><ETag>" + lastEtag + "</ETag></Part>"
          + "</CompleteMultipartUpload>";
      HttpResponse<String> completed = HTTP.send(HttpRequest.newBuilder(
              uri(localS3, "/chunked/multipart?uploadId=" + uploadId.group(1)))
          .POST(HttpRequest.BodyPublishers.ofString(complete)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, completed.statusCode(), completed.body());

      HttpResponse<byte[]> get = HTTP.send(HttpRequest.newBuilder(uri(localS3, "/chunked/multipart")).build(),
          HttpResponse.BodyHandlers.ofByteArray());
      ByteArrayOutputStream expected = new ByteArrayOutputStream();
      expected.write(first);
      expected.write(last);
      assertArrayEquals(expected.toByteArray(), get.body());
    }
  }

  @Test
  void answersMissingContentLengthLikeAmazonS3WhenNotAccepted() throws Exception {
    try (LocalS3 localS3 = start(LocalS3Mode.IN_MEMORY, builder -> builder.s3Api(s3 -> s3.acceptChunkedUploads(false)))) {
      String response = exchange(localS3, "PUT /chunked/object.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
          + "Transfer-Encoding: chunked\r\n\r\n5\r\nHello\r\n0\r\n\r\n");
      assertTrue(response.startsWith("HTTP/1.1 411 "), response);
      assertTrue(response.contains("<Code>MissingContentLength</Code>"), response);

      HttpResponse<String> create = send(localS3, "POST", "/chunked/multipart?uploads", null);
      Matcher uploadId = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(create.body());
      assertTrue(uploadId.find(), create.body());
      HttpResponse<String> part = send(localS3, "PUT",
          "/chunked/multipart?partNumber=1&uploadId=" + uploadId.group(1), randomBytes(5));
      assertEquals(411, part.statusCode(), part.body());

      // A body that declares its length is stored as usual.
      HttpResponse<String> declared = HTTP.send(HttpRequest.newBuilder(uri(localS3, "/chunked/declared.txt"))
          .PUT(HttpRequest.BodyPublishers.ofString("Hello")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, declared.statusCode(), declared.body());
    }
  }

  private LocalS3 start(LocalS3Mode mode, Consumer<LocalS3Builder> customizer) {
    LocalS3Builder builder = LocalS3.builder().port(0).mode(mode).buckets("chunked");
    if (mode == LocalS3Mode.PERSISTENCE) {
      builder.dataPath(dataPath.toString());
    }
    customizer.accept(builder);
    LocalS3 localS3 = builder.build();
    localS3.start();
    return localS3;
  }

  /**
   * Send a request whose body, if any, is a stream of unknown length, which {@linkplain HttpClient} sends with
   * {@code Transfer-Encoding: chunked} and no {@code Content-Length}.
   */
  private static HttpResponse<String> send(LocalS3 localS3, String method, String path, byte[] content)
      throws Exception {
    HttpRequest.BodyPublisher body = content == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(content));
    return HTTP.send(HttpRequest.newBuilder(uri(localS3, path)).method(method, body).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(LocalS3 localS3, String path) {
    return URI.create("http://127.0.0.1:" + localS3.getPort() + path);
  }

  private static String exchange(LocalS3 localS3, String request) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
      socket.setSoTimeout(30_000);
      OutputStream out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      in.transferTo(response);
      return response.toString(StandardCharsets.UTF_8);
    }
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }

  private static String md5Hex(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
  }

}
