package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every response carries an {@code x-amz-request-id}, in the shape that Amazon S3 uses, and an error repeats
 * the same ID in its body. The error paths used to answer with an ID of another shape than the rest of the
 * service, and most of them sent no header at all.
 */
class RequestIdTest {

  /**
   * The shape of the request IDs of Amazon S3, e.g. {@code VGEKQFPHD810M604}.
   */
  private static final Pattern REQUEST_ID = Pattern.compile("[0-9A-Z]{16}");

  private static final Pattern BODY_REQUEST_ID = Pattern.compile("<RequestId>(.*?)</RequestId>");

  /**
   * An error that reports no ID at all writes the element as an empty one, which
   * {@linkplain #BODY_REQUEST_ID} doesn't match, so {@linkplain #check} looks for it too rather than
   * treating such a body as one that carries no RequestId element.
   */
  private static final String EMPTY_BODY_REQUEST_ID = "<RequestId/>";

  @Test
  void everyResponseCarriesARequestIdThatAnErrorRepeatsInItsBody() throws Exception {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .buckets("request-id-bucket")
        // Small enough that a modest body is rejected, to reach the error of the request decoder.
        .maxRequestBodySize(64)
        .build();
    localS3.start();
    try {
      HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      String base = "http://127.0.0.1:" + localS3.getPort();
      send(client, HttpRequest.newBuilder(URI.create(base + "/request-id-bucket/a.txt"))
          .PUT(HttpRequest.BodyPublishers.ofString("hello")));

      Set<String> seen = new HashSet<>();
      // A response that succeeded, and the errors of the exception handlers and of the request decoder.
      seen.add(check(send(client, HttpRequest.newBuilder(URI.create(base + "/request-id-bucket/a.txt")).GET())));
      seen.add(check(send(client, HttpRequest.newBuilder(URI.create(base + "/request-id-bucket"))
          .POST(HttpRequest.BodyPublishers.noBody()))));
      seen.add(check(send(client, HttpRequest.newBuilder(URI.create(base + "/request-id-bucket/missing")).GET())));
      seen.add(check(send(client, HttpRequest.newBuilder(
          URI.create(base + "/request-id-bucket?list-type=2&continuation-token=bad")).GET())));
      seen.add(check(send(client, HttpRequest.newBuilder(URI.create(base + "/request-id-bucket/large"))
          .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[128])))));
      // An operation that is routed but not implemented, which NotImplementedOperationController answers.
      seen.add(check(send(client, HttpRequest.newBuilder(URI.create(base + "/request-id-bucket?lifecycle"))
          .GET())));

      assertEquals(6, seen.size(), "Every request gets its own ID: " + seen);
    } finally {
      localS3.shutdown();
    }
  }

  private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Check the request ID of a response, and return it.
   */
  private static String check(HttpResponse<String> response) {
    String requestId = response.headers().firstValue("x-amz-request-id").orElse(null);
    assertTrue(requestId != null && REQUEST_ID.matcher(requestId).matches(),
        "The x-amz-request-id of a " + response.statusCode() + " must have the shape of Amazon S3: " + requestId);

    assertFalse(response.body().contains(EMPTY_BODY_REQUEST_ID),
        "An error body must report the request ID, not an empty element: " + response.body());

    Matcher body = BODY_REQUEST_ID.matcher(response.body());
    if (body.find()) {
      assertEquals(requestId, body.group(1),
          "The body of an error must repeat the request ID of the header.");
    } else {
      assertFalse(response.statusCode() >= 400 && response.body().contains("<Error>"),
          "An error body must carry a RequestId: " + response.body());
    }
    return requestId;
  }

  @Test
  void requestIdsHaveTheShapeOfAmazonS3AndDiffer() {
    String first = com.robothy.s3.rest.utils.ResponseUtils.nextRequestId();
    String second = com.robothy.s3.rest.utils.ResponseUtils.nextRequestId();

    assertTrue(REQUEST_ID.matcher(first).matches(), first);
    assertTrue(REQUEST_ID.matcher(second).matches(), second);
    assertNotEquals(first, second);
  }

}
