package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A request whose header section exceeds the max request header size is answered, rather than left waiting for a
 * response until the client times out.
 */
class RequestHeaderSizeTest {

  private static String put(LocalS3 localS3, int metadataLength) throws Exception {
    String request = "PUT /headers/key HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n"
        + "x-amz-meta-large: " + "a".repeat(metadataLength) + "\r\n\r\nx";
    try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
      socket.setSoTimeout(10_000);
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      socket.shutdownOutput();
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(response);
      return response.toString(StandardCharsets.UTF_8);
    }
  }

  @Test
  void headersBeyondTheMaxSizeAreRejectedWithAnS3Error() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).buckets("headers").build();
    localS3.start();
    try {
      // Above the 8 KB of Netty's default, within the default of LocalS3.
      String accepted = put(localS3, 9 * 1024);
      assertTrue(accepted.startsWith("HTTP/1.1 200 "), accepted);

      String rejected = put(localS3, LocalS3Config.DEFAULT_MAX_REQUEST_HEADER_SIZE + 1);
      assertTrue(rejected.startsWith("HTTP/1.1 400 "), rejected);
      assertTrue(rejected.contains("<Code>RequestHeaderSectionTooLarge</Code>"), rejected);
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void maxRequestHeaderSizeIsConfigurable() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).buckets("headers")
        .netty(netty -> netty.maxRequestHeaderSize(1024)).build();
    localS3.start();
    try {
      String rejected = put(localS3, 2048);
      assertTrue(rejected.contains("<Code>RequestHeaderSectionTooLarge</Code>"), rejected);
    } finally {
      localS3.shutdown();
    }
  }

}
