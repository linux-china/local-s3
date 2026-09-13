package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wire behaviour of a conditional request: what the {@code If-Match}, {@code If-None-Match},
 * {@code If-Modified-Since} and {@code If-Unmodified-Since} headers of a read and of a put answer, checked
 * over a raw keep-alive connection rather than through an SDK, because the framing of a
 * {@code 304 Not Modified}, which carries no content, is part of what has to be right.
 */
class ConditionalRequestControllerTest {

  private static final String BUCKET = "conditional";

  private static final String KEY = "k.txt";

  private LocalS3 localS3;

  /**
   * One client for the whole test, so that every request after the first reuses its connection: a response
   * that frames its body wrongly would leave bytes behind that the next response is then read from.
   */
  private HttpClient client;

  private String url;

  @BeforeEach
  void startServer() {
    localS3 = LocalS3.builder().port(-1).buckets(BUCKET).build();
    localS3.start();
    client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    url = "http://127.0.0.1:" + localS3.getPort() + "/" + BUCKET + "/" + KEY;
  }

  @AfterEach
  void shutdownServer() {
    localS3.shutdown();
  }

  private HttpResponse<String> send(Consumer<HttpRequest.Builder> request) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
    request.accept(builder);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> put(String content, Consumer<HttpRequest.Builder> conditions) throws Exception {
    return send(request -> {
      conditions.accept(request);
      request.PUT(HttpRequest.BodyPublishers.ofString(content));
    });
  }

  private static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElse(null);
  }

  private static String unquote(String etag) {
    assertTrue(etag.startsWith("\"") && etag.endsWith("\""), etag);
    return etag.substring(1, etag.length() - 1);
  }

  /**
   * A read that the client already holds the object of answers 304 with no content, but with the ETag and
   * the Last-Modified that identify the version it holds, like RFC 9110 requires of a response that leaves
   * the content out. The connection stays usable, which a 304 that framed a body wrongly would break.
   */
  @Test
  void aReadOfAnObjectTheClientHoldsAnswersNotModified() throws Exception {
    assertEquals(200, put("hello", request -> { }).statusCode());
    HttpResponse<String> stored = send(HttpRequest.Builder::GET);
    String etag = header(stored, "etag");
    String unquotedEtag = unquote(etag);
    String lastModified = header(stored, "last-modified");

    for (String ifNoneMatch : new String[] {"*", etag, unquotedEtag, "\"other\", " + etag}) {
      HttpResponse<String> notModified = send(request -> request.header("If-None-Match", ifNoneMatch).GET());
      assertEquals(304, notModified.statusCode(), ifNoneMatch);
      assertTrue(notModified.body().isEmpty(), notModified.body());
      assertEquals(etag, header(notModified, "etag"));
      assertEquals(lastModified, header(notModified, "last-modified"));
      // None of the headers that describe content belong in a 304.
      assertFalse(notModified.headers().firstValue("content-type").isPresent());
      assertFalse(notModified.headers().firstValue("accept-ranges").isPresent());
    }

    // HeadObject answers the same way.
    assertEquals(304, send(request -> request.header("If-None-Match", "*")
        .method("HEAD", HttpRequest.BodyPublishers.noBody())).statusCode());

    // The date the client read off Last-Modified is the second the object was stored in, not before it.
    HttpResponse<String> unchangedSince = send(request ->
        request.header("If-Modified-Since", lastModified).GET());
    assertEquals(304, unchangedSince.statusCode());
    assertTrue(unchangedSince.body().isEmpty());

    // The connection carried four 304s; a request on it still answers with the object.
    HttpResponse<String> unconditional = send(HttpRequest.Builder::GET);
    assertEquals(200, unconditional.statusCode());
    assertEquals("hello", unconditional.body());
  }

  @Test
  void aReadOfAnObjectTheClientDoesNotHoldAnswersWithIt() throws Exception {
    put("hello", request -> { });
    String lastModified = header(send(HttpRequest.Builder::GET), "last-modified");

    assertEquals("hello", send(request -> request.header("If-None-Match", "\"other\"").GET()).body());
    assertEquals("hello", send(request -> request.header("If-Modified-Since",
        "Wed, 21 Oct 2015 07:28:00 GMT").GET()).body());
    assertEquals("hello", send(request -> request.header("If-Unmodified-Since", lastModified).GET()).body());
  }

  /**
   * A date that isn't an HTTP date is no precondition at all, which RFC 9110 requires a recipient to
   * ignore rather than to reject.
   */
  @Test
  void aReadWithADateThatIsNotAnHttpDateIsUnconditional() throws Exception {
    put("hello", request -> { });
    assertEquals("hello", send(request -> request.header("If-Modified-Since", "not-a-date").GET()).body());
    assertEquals("hello", send(request -> request.header("If-Unmodified-Since", "0").GET()).body());
  }

  /**
   * A failed condition of a read answers 412 with the error that Amazon S3 answers with, naming the header
   * whose condition didn't hold.
   */
  @Test
  void aReadWhoseConditionFailedAnswersPreconditionFailed() throws Exception {
    put("hello", request -> { });

    HttpResponse<String> staleTag = send(request -> request.header("If-Match", "\"other\"").GET());
    assertEquals(412, staleTag.statusCode());
    assertTrue(staleTag.body().contains("<Code>PreconditionFailed</Code>"), staleTag.body());
    assertTrue(staleTag.body().contains("<Condition>If-Match</Condition>"), staleTag.body());

    HttpResponse<String> modified = send(request ->
        request.header("If-Unmodified-Since", "Wed, 21 Oct 2015 07:28:00 GMT").GET());
    assertEquals(412, modified.statusCode());
    assertTrue(modified.body().contains("<Condition>If-Unmodified-Since</Condition>"), modified.body());
  }

  /**
   * {@code If-None-Match: *} stores an object only while the key holds none, which is the "create if
   * absent" that a commit protocol builds a lock on.
   */
  @Test
  void aPutIfNoneMatchOnlyStoresWhileTheKeyHoldsNoObject() throws Exception {
    assertEquals(200, put("first", request -> request.header("If-None-Match", "*")).statusCode());

    HttpResponse<String> rejected = put("second", request -> request.header("If-None-Match", "*"));
    assertEquals(412, rejected.statusCode());
    assertTrue(rejected.body().contains("<Condition>If-None-Match</Condition>"), rejected.body());
    assertEquals("first", send(HttpRequest.Builder::GET).body());

    // Once the object is deleted the key holds none again, so the condition holds.
    assertEquals(204, send(request -> request.DELETE()).statusCode());
    assertEquals(200, put("third", request -> request.header("If-None-Match", "*")).statusCode());
    assertEquals("third", send(HttpRequest.Builder::GET).body());
  }

  /**
   * {@code If-Match} makes a put a compare-and-swap: it stores the object only while the key still holds
   * the one whose entity tag the request carries. A key that holds no object is reported as missing, which
   * is what Amazon S3 answers.
   */
  @Test
  void aPutIfMatchSwapsTheObjectItWasGivenTheEntityTagOf() throws Exception {
    HttpResponse<String> absent = put("x", request -> request.header("If-Match", "\"whatever\""));
    assertEquals(404, absent.statusCode());
    assertTrue(absent.body().contains("<Code>NoSuchKey</Code>"), absent.body());

    put("version-0", request -> { });
    String firstEtag = header(send(HttpRequest.Builder::GET), "etag");

    // The entity tag is matched whether the client quotes it, like Amazon S3 sends it, or not.
    assertEquals(200, put("version-1", request -> request.header("If-Match", firstEtag)).statusCode());
    HttpResponse<String> stale = put("version-2", request -> request.header("If-Match", unquote(firstEtag)));
    assertEquals(412, stale.statusCode());
    assertTrue(stale.body().contains("<Condition>If-Match</Condition>"), stale.body());
    assertEquals("version-1", send(HttpRequest.Builder::GET).body());

    String secondEtag = header(send(HttpRequest.Builder::GET), "etag");
    assertEquals(200, put("version-2", request -> request.header("If-Match", unquote(secondEtag))).statusCode());
    assertEquals("version-2", send(HttpRequest.Builder::GET).body());
  }

}
