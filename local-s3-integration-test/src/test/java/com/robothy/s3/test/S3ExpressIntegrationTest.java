package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Builder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.BucketType;
import software.amazon.awssdk.services.s3.model.CreateSessionResponse;
import software.amazon.awssdk.services.s3.model.DataRedundancy;
import software.amazon.awssdk.services.s3.model.ListDirectoryBucketsResponse;
import software.amazon.awssdk.services.s3.model.LocationType;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * An AWS SDK addresses a directory bucket of S3 Express One Zone, named {@code base-name--zone-id--x-s3}, the S3
 * Express way: it creates a session with {@code CreateSession}, and signs the requests to the bucket with the session
 * credentials, for the {@code s3express} service.
 */
class S3ExpressIntegrationTest {

  private static final String ACCESS_KEY_ID = "local-access-key";

  private static final String SECRET_ACCESS_KEY = "local-secret-access-key";

  private static final String BUCKET = "express--usw2-az1--x-s3";

  private final List<AutoCloseable> resources = new ArrayList<>();

  @AfterEach
  void close() throws Exception {
    for (AutoCloseable resource : resources.reversed()) {
      resource.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void accessesADirectoryBucketWithSessionCredentials(boolean verifySignatures) throws Exception {
    URI endpoint = start(verifySignatures);
    S3Client s3 = s3(endpoint);

    s3.createBucket(b -> b.bucket(BUCKET).createBucketConfiguration(c -> c
        .location(l -> l.type(LocationType.AVAILABILITY_ZONE).name("usw2-az1"))
        .bucket(d -> d.type(BucketType.DIRECTORY).dataRedundancy(DataRedundancy.SINGLE_AVAILABILITY_ZONE))));
    s3.putObject(b -> b.bucket(BUCKET).key("dir/a b.txt"), RequestBody.fromString("Hello"));
    s3.putObject(b -> b.bucket(BUCKET).key("dir/a b.txt").writeOffsetBytes(5L), RequestBody.fromString(", World"));
    assertEquals("Hello, World", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("dir/a b.txt")).asUtf8String());
    s3.renameObject(b -> b.bucket(BUCKET).key("dir/b.txt").renameSource(BUCKET + "/dir/a b.txt"));
    assertEquals(List.of("dir/b.txt"), s3.listObjectsV2(b -> b.bucket(BUCKET)).contents().stream()
        .map(S3Object::key).toList());
    s3.headBucket(b -> b.bucket(BUCKET));
    s3.deleteObject(b -> b.bucket(BUCKET).key("dir/b.txt"));
    s3.deleteBucket(b -> b.bucket(BUCKET));
  }

  @Test
  void createsASession() {
    URI endpoint = start(true);
    S3Client s3 = s3(endpoint);
    s3.createBucket(b -> b.bucket(BUCKET));

    CreateSessionResponse session = s3.createSession(b -> b.bucket(BUCKET));
    assertTrue(session.credentials().accessKeyId().startsWith("ASIA"));
    Duration validity = Duration.between(Instant.now(), session.credentials().expiration());
    assertTrue(validity.compareTo(Duration.ofMinutes(4)) > 0 && validity.compareTo(Duration.ofMinutes(5)) <= 0,
        validity::toString);
    assertEquals("AES256", session.serverSideEncryptionAsString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void listsTheDirectoryBucketsOnly(boolean verifySignatures) {
    URI endpoint = start(verifySignatures);
    S3Client s3 = s3(endpoint);
    s3.createBucket(b -> b.bucket("general-purpose"));
    List<String> directoryBuckets = List.of("a--usw2-az1--x-s3", "b--usw2-az1--x-s3", "c--use1-az4--x-s3");
    directoryBuckets.forEach(bucket -> s3.createBucket(b -> b.bucket(bucket)));

    ListDirectoryBucketsResponse all = s3.listDirectoryBuckets(b -> { });
    assertEquals(directoryBuckets, all.buckets().stream().map(Bucket::name).toList());
    assertEquals("us-west-2", all.buckets().get(0).bucketRegion());
    assertNull(all.continuationToken());

    List<String> paged = s3.listDirectoryBucketsPaginator(b -> b.maxDirectoryBuckets(2)).buckets().stream()
        .map(Bucket::name).toList();
    assertEquals(directoryBuckets, paged);

    ListDirectoryBucketsResponse firstPage = s3.listDirectoryBuckets(b -> b.maxDirectoryBuckets(2));
    assertEquals(2, firstPage.buckets().size());
    assertNotNull(firstPage.continuationToken());

    // ListBuckets, signed for s3, still lists every bucket.
    assertEquals(4, s3.listBuckets().buckets().size());
  }

  @Test
  void rejectsAMaxDirectoryBucketsOutOfRange() {
    URI endpoint = start(false);
    S3Client s3 = s3(endpoint);
    S3Exception e = assertThrows(S3Exception.class, () -> s3.listDirectoryBuckets(b -> b.maxDirectoryBuckets(1001)));
    assertEquals(400, e.statusCode());
    assertEquals("InvalidArgument", e.awsErrorDetails().errorCode());
  }

  @Test
  void presignsARequestWithSessionCredentials() throws Exception {
    URI endpoint = start(true);
    S3Client s3 = s3(endpoint);
    s3.createBucket(b -> b.bucket(BUCKET).createBucketConfiguration(c -> c
        .locationConstraint(BucketLocationConstraint.US_WEST_2)));
    s3.putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello"));

    try (S3Presigner presigner = S3Presigner.builder()
        .endpointOverride(endpoint)
        .region(Region.US_WEST_2)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY)))
        .s3Client(s3)
        .build()) {
      URI url = presigner.presignGetObject(b -> b.signatureDuration(Duration.ofMinutes(1))
          .getObjectRequest(r -> r.bucket(BUCKET).key("a.txt"))).url().toURI();
      assertTrue(url.getRawQuery().contains("X-Amz-S3session-Token="), url::toString);
      HttpResponse<String> response = HttpClient.newHttpClient()
          .send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      assertEquals("Hello", response.body());
    }
  }

  private URI start(boolean verifySignatures) {
    LocalS3Builder builder = LocalS3.builder().port(-1);
    if (verifySignatures) {
      builder.credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    }
    LocalS3 localS3 = builder.build();
    localS3.start();
    resources.add(localS3::shutdown);
    return URI.create("http://localhost:" + localS3.getPort());
  }

  private S3Client s3(URI endpoint) {
    S3Client client = S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.US_WEST_2)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY)))
        .build();
    resources.add(client);
    return client;
  }

}
