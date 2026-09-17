package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.model.answers.LifecycleActionAns;
import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;

/**
 * A lifecycle configuration put through the AWS SDK is applied when a test asks for it, at a time in the future.
 */
class LifecycleExecutionIntegrationTest {

  private LocalS3 localS3;

  private S3Client s3;

  @BeforeEach
  void setUp() {
    localS3 = LocalS3.builder().port(-1).buckets("plain", "versioned").build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
        .forcePathStyle(true)
        .build();
  }

  @AfterEach
  void tearDown() {
    s3.close();
    localS3.shutdown();
  }

  @Test
  void expiresObjectsAndAbortsUploadsOnDemand() {
    s3.putBucketLifecycleConfiguration(b -> b.bucket("plain").lifecycleConfiguration(c -> c.rules(
        LifecycleRule.builder().id("expire-tmp").status(ExpirationStatus.ENABLED)
            .filter(f -> f.prefix("tmp/")).expiration(e -> e.days(7)).build(),
        LifecycleRule.builder().id("expire-tagged").status(ExpirationStatus.ENABLED)
            .filter(f -> f.tag(Tag.builder().key("temporary").value("true").build())).expiration(e -> e.days(1))
            .build(),
        LifecycleRule.builder().id("abort").status(ExpirationStatus.ENABLED)
            .filter(f -> f.prefix("")).abortIncompleteMultipartUpload(a -> a.daysAfterInitiation(2)).build())));
    s3.putObject(b -> b.bucket("plain").key("tmp/a.txt"), RequestBody.fromString("a"));
    s3.putObject(b -> b.bucket("plain").key("keep/b.txt"), RequestBody.fromString("b"));
    s3.putObject(b -> b.bucket("plain").key("keep/tagged.txt").tagging("temporary=true"), RequestBody.fromString("t"));
    String uploadId = s3.createMultipartUpload(b -> b.bucket("plain").key("big.bin")).uploadId();

    // Nothing is due yet.
    assertEquals(List.of(), localS3.applyLifecycle(Instant.now()));
    assertEquals(3, s3.listObjectsV2(b -> b.bucket("plain")).keyCount());

    List<LifecycleActionAns> actions = localS3.applyLifecycle(Instant.now().plus(Duration.ofDays(3)));
    assertEquals(List.of(
        new LifecycleActionAns("plain", "expire-tagged", LifecycleActionAns.Type.OBJECT_EXPIRED, "keep/tagged.txt",
            null, null),
        new LifecycleActionAns("plain", "abort", LifecycleActionAns.Type.MULTIPART_UPLOAD_ABORTED, "big.bin", null,
            uploadId)), actions);
    assertEquals(0, s3.listMultipartUploads(b -> b.bucket("plain")).uploads().size());

    actions = localS3.applyLifecycle("plain", Instant.now().plus(Duration.ofDays(8)));
    assertEquals(1, actions.size());
    assertEquals("tmp/a.txt", actions.get(0).key());
    assertEquals(List.of("keep/b.txt"),
        s3.listObjectsV2(b -> b.bucket("plain")).contents().stream().map(o -> o.key()).toList());
  }

  @Test
  void expiresNoncurrentVersionsAndDeleteMarkersOfAVersionedBucket() throws Exception {
    s3.putBucketVersioning(b -> b.bucket("versioned")
        .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
    s3.putBucketLifecycleConfiguration(b -> b.bucket("versioned").lifecycleConfiguration(c -> c.rules(
        LifecycleRule.builder().id("current").status(ExpirationStatus.ENABLED).filter(f -> f.prefix("docs/"))
            .expiration(e -> e.days(30)).build(),
        LifecycleRule.builder().id("noncurrent").status(ExpirationStatus.ENABLED).filter(f -> f.prefix("notes"))
            .noncurrentVersionExpiration(n -> n.noncurrentDays(1).newerNoncurrentVersions(1)).build(),
        LifecycleRule.builder().id("markers").status(ExpirationStatus.ENABLED).filter(f -> f.prefix("gone/"))
            .noncurrentVersionExpiration(n -> n.noncurrentDays(1))
            .expiration(e -> e.expiredObjectDeleteMarker(true)).build())));
    for (String content : List.of("v1", "v2", "v3")) {
      s3.putObject(b -> b.bucket("versioned").key("notes.txt"), RequestBody.fromString(content));
    }
    s3.putObject(b -> b.bucket("versioned").key("docs/readme.md"), RequestBody.fromString("doc"));
    s3.putObject(b -> b.bucket("versioned").key("gone/x.txt"), RequestBody.fromString("x"));
    s3.deleteObject(b -> b.bucket("versioned").key("gone/x.txt"));

    // Through the admin endpoint, two days from now: v1 of notes.txt goes, v2 is kept as the newest noncurrent
    // version, and the noncurrent version of gone/x.txt goes, which leaves its delete marker alone, so it goes too.
    HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + localS3.getPort() + "/_admin/lifecycle?bucket=versioned&days=2"))
        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(response.body().contains("NONCURRENT_VERSION_EXPIRED"), response.body());
    assertTrue(response.body().contains("EXPIRED_DELETE_MARKER_REMOVED"), response.body());

    List<ObjectVersion> notes = s3.listObjectVersions(b -> b.bucket("versioned").prefix("notes.txt")).versions();
    assertEquals(2, notes.size());
    assertEquals(0, s3.listObjectVersions(b -> b.bucket("versioned").prefix("gone/")).deleteMarkers().size());

    List<LifecycleActionAns> actions = localS3.applyLifecycle("versioned", Instant.now().plus(Duration.ofDays(31)));
    assertEquals(new LifecycleActionAns("versioned", "current", LifecycleActionAns.Type.DELETE_MARKER_CREATED,
        "docs/readme.md", actions.get(0).versionId(), null), actions.get(0));
    assertEquals(404, assertThrows(S3Exception.class,
        () -> s3.headObject(b -> b.bucket("versioned").key("docs/readme.md"))).statusCode());

    assertEquals(404, HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + localS3.getPort() + "/_admin/lifecycle?bucket=missing"))
        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
  }

}
