package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Requests that Amazon S3 rejects are answered with the status and the error code of Amazon S3, rather than a
 * generic {@code InvalidArgument} or an {@code InternalError}.
 */
class ClientErrorResponseTest {

  private static LocalS3 localS3;

  @BeforeAll
  static void start() {
    localS3 = LocalS3.builder().port(-1).buckets("errors").build();
    localS3.start();
  }

  @AfterAll
  static void stop() {
    localS3.shutdown();
  }

  /**
   * Send a raw HTTP request on a connection of its own and read the whole response.
   */
  private static String exchange(String request) throws Exception {
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

  private static void assertError(String response, int status, String code) {
    assertTrue(response.startsWith("HTTP/1.1 " + status + " "), response);
    assertTrue(response.contains("<Code>" + code + "</Code>"), response);
  }

  @Test
  void chunkedPutWithoutContentLengthIsMissingContentLength() throws Exception {
    String response = exchange("PUT /errors/chunked.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
        + "Transfer-Encoding: chunked\r\n\r\n5\r\nHello\r\n0\r\n\r\n");

    assertError(response, 411, "MissingContentLength");
  }

  @Test
  void malformedCopySourceEncodingIsInvalidArgument() throws Exception {
    String response = exchange("PUT /errors/copy.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
        + "x-amz-copy-source: /errors/%zz\r\nContent-Length: 0\r\n\r\n");

    assertError(response, 400, "InvalidArgument");
  }

  @Test
  void nonNumericMaxUploadsIsInvalidArgument() throws Exception {
    String response = exchange("GET /errors?uploads&max-uploads=abc HTTP/1.1\r\nHost: localhost\r\n"
        + "Connection: close\r\n\r\n");

    assertError(response, 400, "InvalidArgument");
    assertTrue(!response.contains("For input string"), response);
  }

  @Test
  void versionIdMarkerWithoutKeyMarkerIsInvalidArgument() throws Exception {
    String response = exchange("GET /errors?versions&version-id-marker=1 HTTP/1.1\r\nHost: localhost\r\n"
        + "Connection: close\r\n\r\n");

    assertError(response, 400, "InvalidArgument");
  }

}
