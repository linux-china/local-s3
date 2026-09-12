package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The listings must be framed by the number of bytes of the response, not by the number of characters of the
 * XML: a key that isn't ASCII is encoded to more bytes than it has characters, so a {@code Content-Length} of
 * the characters cuts the end off the body and leaves the rest of it to be read as the next response of a
 * keep-alive connection.
 */
class ListObjectsContentLengthTest {

  private static final String BUCKET = "list-bucket";

  /**
   * 12 bytes of UTF-8, 4 characters.
   */
  private static final String NON_ASCII_KEY = "中文测试.txt";

  @ValueSource(strings = {"", "?list-type=2"})
  @ParameterizedTest
  void framesListingOfNonAsciiKeyByItsBytes(String listType) throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).buckets(BUCKET).build();
    localS3.start();
    try {
      // One client for both requests, so that the second one reuses the connection of the first.
      HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      String base = "http://127.0.0.1:" + localS3.getPort() + "/" + BUCKET;
      assertEquals(200, client.send(HttpRequest.newBuilder(objectUri(localS3.getPort()))
          .PUT(HttpRequest.BodyPublishers.ofString("hello")).build(),
          HttpResponse.BodyHandlers.discarding()).statusCode());

      HttpResponse<byte[]> listing = client.send(HttpRequest.newBuilder(URI.create(base + listType)).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(200, listing.statusCode());
      String body = new String(listing.body(), StandardCharsets.UTF_8);
      // The listing is not URL encoded, so it carries the key as it is.
      assertTrue(body.contains(NON_ASCII_KEY), body);
      // A Content-Length of the characters of the XML would cut the closing tag off.
      assertTrue(body.endsWith("</ListBucketResult>"), body);
      assertEquals(listing.body().length,
          Integer.parseInt(listing.headers().firstValue("content-length").orElseThrow()));

      // The bytes that a short Content-Length left behind would be read as the head of this response.
      HttpResponse<byte[]> next = client.send(HttpRequest.newBuilder(URI.create(base + listType)).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, next.statusCode());
      assertTrue(new String(next.body(), StandardCharsets.UTF_8).endsWith("</ListBucketResult>"));
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * The multi argument constructor percent encodes the key, which a URI parsed from a string wouldn't.
   */
  private static URI objectUri(int port) throws Exception {
    return new URI("http", null, "127.0.0.1", port, "/" + BUCKET + "/" + NON_ASCII_KEY, null, null);
  }

}
