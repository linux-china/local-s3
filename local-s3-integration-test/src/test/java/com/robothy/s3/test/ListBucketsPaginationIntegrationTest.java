package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListBucketsIterable;

/**
 * Paginated <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListBuckets.html">ListBuckets</a> through the
 * {@code listBucketsPaginator} of the AWS SDK.
 */
class ListBucketsPaginationIntegrationTest {

  @Test
  @LocalS3
  void thePaginatorListsEveryBucketInPagesOfMaxBuckets(S3Client s3) {
    List<String> created = IntStream.range(0, 25).mapToObj(i -> String.format("paged-%02d", i)).toList();
    created.forEach(name -> s3.createBucket(request -> request.bucket(name)));

    ListBucketsIterable pages = s3.listBucketsPaginator(request -> request.maxBuckets(10));

    assertEquals(List.of(10, 10, 5), pages.stream().map(page -> page.buckets().size()).toList());
    assertEquals(created, pages.buckets().stream().map(Bucket::name).toList());
    assertTrue(pages.buckets().stream().allMatch(bucket -> "us-east-1".equals(bucket.bucketRegion())),
        "Every bucket reports its region.");

    ListBucketsResponse unpaginated = s3.listBuckets();
    assertEquals(25, unpaginated.buckets().size());
    assertNull(unpaginated.continuationToken(), "A request without parameters lists every bucket on one page.");
  }

  @Test
  @LocalS3
  void thePrefixAndTheRegionFilterTheBuckets(S3Client s3) {
    s3.createBucket(request -> request.bucket("logs-2025"));
    s3.createBucket(request -> request.bucket("data-a"));
    s3.createBucket(request -> request.bucket("logs-2026").createBucketConfiguration(
        configuration -> configuration.locationConstraint(BucketLocationConstraint.EU_WEST_1)));
    s3.createBucket(request -> request.bucket("logs-2027"));

    ListBucketsIterable logs = s3.listBucketsPaginator(request -> request.prefix("logs-").maxBuckets(2));
    assertEquals(List.of("logs-2025", "logs-2026", "logs-2027"), logs.buckets().stream().map(Bucket::name).toList());
    assertTrue(logs.stream().allMatch(page -> "logs-".equals(page.prefix())), "Every page reports the prefix.");

    ListBucketsResponse europe = s3.listBuckets(request -> request.bucketRegion("eu-west-1"));
    assertEquals(List.of("logs-2026"), europe.buckets().stream().map(Bucket::name).toList());
    assertEquals("eu-west-1", europe.buckets().get(0).bucketRegion());
    assertNull(europe.prefix(), "A request without a prefix gets none back.");
  }

  @Test
  @LocalS3
  void aTokenContinuesAfterTheLastBucketOfItsPage(S3Client s3) {
    List.of("first", "second", "third").forEach(name -> s3.createBucket(request -> request.bucket(name)));

    ListBucketsResponse page = s3.listBuckets(request -> request.maxBuckets(2));
    assertEquals(List.of("first", "second"), page.buckets().stream().map(Bucket::name).toList());
    assertNotNull(page.continuationToken());
    s3.deleteBucket(request -> request.bucket("first"));

    ListBucketsResponse next = s3.listBuckets(request -> request.maxBuckets(2).continuationToken(page.continuationToken()));
    assertEquals(List.of("third"), next.buckets().stream().map(Bucket::name).toList());
    assertNull(next.continuationToken());
  }

  @Test
  @LocalS3
  void rejectsInvalidParameters(S3Client s3) {
    S3Exception tooMany = assertThrows(S3Exception.class, () -> s3.listBuckets(request -> request.maxBuckets(10001)));
    assertEquals(400, tooMany.statusCode());
    assertEquals("InvalidArgument", tooMany.awsErrorDetails().errorCode());

    S3Exception badToken = assertThrows(S3Exception.class,
        () -> s3.listBuckets(request -> request.continuationToken("not-a-token")));
    assertEquals(400, badToken.statusCode());
  }

}
