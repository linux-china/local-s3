package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * {@code docs/semantics.md#request-validation}: a key that starts with {@code /} is its own object.
 */
class LeadingSlashKeyIntegrationTest {

  @Test
  @LocalS3
  void aKeyThatStartsWithSlashIsADifferentObject(S3Client s3) {
    s3.createBucket(b -> b.bucket("bucket"));
    s3.putObject(p -> p.bucket("bucket").key("/lead"), RequestBody.fromString("with slash"));
    s3.putObject(p -> p.bucket("bucket").key("lead"), RequestBody.fromString("without slash"));

    assertEquals("with slash", s3.getObjectAsBytes(g -> g.bucket("bucket").key("/lead")).asUtf8String());
    assertEquals("without slash", s3.getObjectAsBytes(g -> g.bucket("bucket").key("lead")).asUtf8String());
    assertEquals(List.of("/lead", "lead"), keys(s3));

    s3.copyObject(c -> c.sourceBucket("bucket").sourceKey("/lead").destinationBucket("bucket").destinationKey("//copy"));
    assertEquals("with slash", s3.getObjectAsBytes(g -> g.bucket("bucket").key("//copy")).asUtf8String());

    s3.deleteObject(d -> d.bucket("bucket").key("/lead"));
    assertThrows(NoSuchKeyException.class, () -> s3.headObject(h -> h.bucket("bucket").key("/lead")));
    assertEquals(List.of("//copy", "lead"), keys(s3));
  }

  @Test
  @LocalS3
  void aPathStyleRequestNamesTheKeyAfterTheBucket(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    s3.createBucket(b -> b.bucket("bucket"));
    HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(endpoint.endpoint() + "/bucket//lead"))
        .PUT(HttpRequest.BodyPublishers.ofString("content")).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, put.statusCode(), put.body());

    HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(endpoint.endpoint() + "/bucket/lead"))
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, get.statusCode(), get.body());
    assertEquals(List.of("/lead"), keys(s3));
  }

  private static List<String> keys(S3Client s3) {
    return s3.listObjectsV2(l -> l.bucket("bucket")).contents().stream().map(S3Object::key).toList();
  }

}
