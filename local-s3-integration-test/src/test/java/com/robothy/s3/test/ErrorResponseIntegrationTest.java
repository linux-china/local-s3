package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The {@code <Error>} document and the IDs of a failed request, which LocalS3 answers the way Amazon S3 does:
 * the message of Amazon S3, the bucket and the key in fields of their own rather than in the message, no field
 * that the error doesn't carry, and an {@code x-amz-id-2} that the body repeats as its {@code HostId}.
 */
class ErrorResponseIntegrationTest {

  private static final String BUCKET = "error-responses";

  @Test
  @LocalS3
  void aMissingKeyIsReportedLikeAmazonS3ReportsIt(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    s3.createBucket(request -> request.bucket(BUCKET));

    NoSuchKeyException thrown = assertThrows(NoSuchKeyException.class,
        () -> s3.headObject(request -> request.bucket(BUCKET).key("nope.txt")));
    assertEquals("NoSuchKey", thrown.awsErrorDetails().errorCode());
    // The SDK reads it from x-amz-id-2, which Amazon S3 answers on every response.
    assertNotNull(thrown.extendedRequestId());

    // HeadObject carries no body, so the document itself is read from a GetObject.
    String body = get(endpoint, "/" + BUCKET + "/nope.txt");
    assertTrue(body.contains("<Message>The specified key does not exist.</Message>"), body);
    assertTrue(body.contains("<Key>nope.txt</Key>"), body);
    assertTrue(body.contains("<BucketName>" + BUCKET + "</BucketName>"), body);
    assertTrue(body.contains("<HostId>"), body);
  }

  @Test
  @LocalS3
  void aMissingBucketIsReportedLikeAmazonS3ReportsIt(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    NoSuchBucketException thrown = assertThrows(NoSuchBucketException.class,
        () -> s3.headBucket(request -> request.bucket("absent")));
    assertEquals("NoSuchBucket", thrown.awsErrorDetails().errorCode());

    String body = get(endpoint, "/absent/a.txt");
    assertTrue(body.contains("<Message>The specified bucket does not exist.</Message>"), body);
    assertTrue(body.contains("<BucketName>absent</BucketName>"), body);
  }

  /**
   * Amazon S3 names only what is relevant to an error; LocalS3 wrote every field, empty when it had no value,
   * through 2.4.
   */
  @Test
  @LocalS3
  void anErrorCarriesNoEmptyFields(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    s3.createBucket(request -> request.bucket(BUCKET));

    String body = get(endpoint, "/" + BUCKET + "/nope.txt");
    assertFalse(body.contains("<ArgumentName"), body);
    assertFalse(body.contains("<ArgumentValue"), body);
    assertFalse(body.contains("<VersionId"), body);
    assertFalse(body.contains("/>"), body);
  }

  @Test
  @LocalS3
  void everyResponseCarriesTheTwoIdsOfAmazonS3(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    s3.createBucket(request -> request.bucket(BUCKET));
    s3.putObject(request -> request.bucket(BUCKET).key("a.txt"),
        software.amazon.awssdk.core.sync.RequestBody.fromString("a"));

    // A successful response answers both, like an error does.
    HttpResponse<String> ok = send(endpoint, "/" + BUCKET + "/a.txt");
    assertEquals(200, ok.statusCode());
    assertTrue(ok.headers().firstValue("x-amz-request-id").isPresent());
    assertTrue(ok.headers().firstValue("x-amz-id-2").isPresent());

    // The headers and the body of an error agree, so that a report of either identifies the same request.
    HttpResponse<String> failed = send(endpoint, "/" + BUCKET + "/nope.txt");
    assertEquals(404, failed.statusCode());
    assertTrue(failed.body().contains("<RequestId>" + failed.headers().firstValue("x-amz-request-id").orElseThrow()
        + "</RequestId>"), failed.body());
    assertTrue(failed.body().contains("<HostId>" + failed.headers().firstValue("x-amz-id-2").orElseThrow()
        + "</HostId>"), failed.body());
  }

  private static String get(LocalS3Endpoint endpoint, String path) throws Exception {
    return send(endpoint, path).body();
  }

  private static HttpResponse<String> send(LocalS3Endpoint endpoint, String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(endpoint.endpoint() + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

}
