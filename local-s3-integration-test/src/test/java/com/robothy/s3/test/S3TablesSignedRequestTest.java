package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.ListTableBucketsRequest;
import software.amazon.awssdk.services.s3tables.model.S3TablesException;

/**
 * A service that verifies signatures, where a request of the S3 Tables API is told from an Amazon S3 one by the
 * {@code s3tables} service in its credential scope — the whole point of the design: the two APIs share their paths,
 * {@code PUT /buckets} being {@code CreateTableBucket} of the one and {@code CreateBucket} of a bucket named
 * {@code buckets} in the other, and only the signature says which was meant.
 */
@LocalS3(accessKey = S3TablesSignedRequestTest.ACCESS_KEY, secretKey = S3TablesSignedRequestTest.SECRET_KEY)
class S3TablesSignedRequestTest {

  static final String ACCESS_KEY = "local-s3-tables";

  static final String SECRET_KEY = "local-s3-tables-secret";

  @Test
  void a_signed_client_reaches_the_api_at_the_endpoint_of_the_service(S3TablesClient tables,
                                                                     LocalS3Endpoint endpoint) {
    // The injected client of a service with credentials is pointed at the endpoint itself, with no path of its own.
    assertEquals(endpoint.endpoint(), endpoint.s3TablesEndpoint(true));

    String arn = tables.createTableBucket(request -> request.name("signed")).arn();
    assertEquals("arn:aws:s3tables:us-east-1:000000000000:bucket/signed", arn);
    assertEquals(List.of("signed"), tables.listTableBuckets(ListTableBucketsRequest.builder().build())
        .tableBuckets().stream().map(bucket -> bucket.name()).toList());
  }

  @Test
  void the_same_paths_still_address_buckets_for_a_client_signed_for_s3(S3TablesClient tables, S3Client s3) {
    tables.createTableBucket(request -> request.name("shared-path"));

    // PUT /buckets signed for s3 is CreateBucket, not CreateTableBucket: the path is the same, the scope is not.
    s3.createBucket(request -> request.bucket("buckets"));
    assertTrue(s3.listBuckets().buckets().stream().anyMatch(bucket -> "buckets".equals(bucket.name())));
    // And the table bucket is not an S3 bucket of that name.
    assertTrue(s3.listBuckets().buckets().stream().noneMatch(bucket -> "shared-path".equals(bucket.name())));
    // A listing of the bucket named 'buckets' is an S3 listing, not a ListTableBuckets.
    assertEquals(0, s3.listObjectsV2(request -> request.bucket("buckets")).keyCount());
    assertThrows(NoSuchBucketException.class, () -> s3.listObjectsV2(request -> request.bucket("tables")));
  }

  @Test
  void a_request_signed_with_the_wrong_key_is_refused(LocalS3Endpoint endpoint) {
    try (S3TablesClient wrong = S3TablesClient.builder()
        .endpointOverride(URI.create(endpoint.endpoint()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY, "not-the-secret")))
        .build()) {
      // Claiming the s3tables scope picks the endpoint; the signature is then verified for that same service, so the
      // scope buys a client nothing it couldn't have signed for.
      S3TablesException failure = assertThrows(S3TablesException.class,
          () -> wrong.listTableBuckets(ListTableBucketsRequest.builder().build()));
      assertEquals(403, failure.statusCode());
    }
  }

}
