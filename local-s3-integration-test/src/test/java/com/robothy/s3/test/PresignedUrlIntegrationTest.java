package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * The URLs that {@linkplain LocalS3#presign} signs, against the objects that the AWS SDK stores: the two agree on
 * how a key is spelled in a path, so a URL reads the object that the SDK put under the key.
 */
class PresignedUrlIntegrationTest {

  private static final String ACCESS_KEY_ID = "local-access-key";

  private static final String SECRET_ACCESS_KEY = "local-secret-access-key";

  @Test
  void presignedUrlsReadAndWriteTheObjectsOfTheSdk() throws Exception {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .buckets("artifacts")
        .credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY)
        .build();
    localS3.start();

    try (S3Client s3 = client(localS3.endpoint())) {
      HttpClient http = HttpClient.newHttpClient();
      // Keys with the characters that a path encodes, a slash that it keeps, and characters outside ASCII.
      for (String key : new String[] {"report.pdf", "runs/2026-09-19/plan v2.md", "结果/摘要.txt", "a+b=c&d.json"}) {
        s3.putObject(request -> request.bucket("artifacts").key(key), RequestBody.fromString("of " + key));

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create(localS3.presign("artifacts", key, Duration.ofMinutes(5))))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), key);
        assertEquals("of " + key, response.body(), key);

        String uploaded = "uploaded " + key;
        assertEquals(200, http.send(
            HttpRequest.newBuilder(URI.create(localS3.presign("artifacts", key, Duration.ofMinutes(5), "PUT")))
                .PUT(HttpRequest.BodyPublishers.ofString(uploaded)).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode(), key);
        assertEquals(uploaded, s3.getObjectAsBytes(GetObjectRequest.builder().bucket("artifacts").key(key).build())
            .asUtf8String(), key);
      }
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void presignedUrlsOfATlsServiceAreHttps() {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .tlsSelfSigned()
        .credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY)
        .build();
    localS3.start();

    try {
      String url = localS3.presign("artifacts", "a.txt", Duration.ofMinutes(5));
      assertTrue(url.startsWith("https://127.0.0.1:" + localS3.getPort() + "/artifacts/a.txt?"), url);
    } finally {
      localS3.shutdown();
    }
  }

  private static S3Client client(String endpoint) {
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

}
