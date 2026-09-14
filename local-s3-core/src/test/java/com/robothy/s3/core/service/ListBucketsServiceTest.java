package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.answers.ListBucketsAns;
import com.robothy.s3.core.model.request.ListBucketsOptions;
import com.robothy.s3.core.util.ContinuationTokenUtils;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ListBucketsServiceTest extends LocalS3ServiceTestBase {

  @MethodSource("bucketServices")
  @ParameterizedTest
  void aRequestWithoutParametersListsEveryBucketOnOnePage(BucketService bucketService) {
    create(bucketService, 12);

    ListBucketsAns ans = bucketService.listBuckets(ListBucketsOptions.none());

    assertEquals(12, ans.buckets().size());
    assertNull(ans.continuationToken());
    assertEquals(names(bucketService.listBuckets()), names(ans.buckets()));
  }

  @MethodSource("bucketServices")
  @ParameterizedTest
  void thePagesOfMaxBucketsFollowTheOrderOfCreation(BucketService bucketService) {
    List<String> created = create(bucketService, 25);

    List<List<String>> pages = pages(bucketService, token -> new ListBucketsOptions(null, null, token, 10));

    assertEquals(List.of(10, 10, 5), pages.stream().map(List::size).toList());
    assertEquals(created, pages.stream().flatMap(List::stream).toList());
    // A page that exactly empties the listing has no token, so the listing doesn't end with an empty page.
    assertEquals(List.of(25), pages(bucketService, token -> new ListBucketsOptions(null, null, token, 25)).stream()
        .map(List::size).toList());
  }

  @MethodSource("bucketServices")
  @ParameterizedTest
  void aPrefixOrARegionFiltersTheBuckets(BucketService bucketService) {
    bucketService.createBucket("logs-2025", "eu-west-1");
    bucketService.createBucket("data-a");
    bucketService.createBucket("logs-2026");
    bucketService.createBucket("data-b", "eu-west-1");
    bucketService.createBucket("logs-2027", "eu-west-1");

    // Buckets created in the same millisecond are listed by name, so the expected order is the order of the listing.
    List<String> all = names(bucketService.listBuckets());
    assertEquals(inOrder(all, "logs-2025", "logs-2026", "logs-2027"),
        pages(bucketService, token -> new ListBucketsOptions("logs-", null, token, 2)).stream()
            .flatMap(List::stream).toList());
    assertEquals(inOrder(all, "logs-2025", "data-b", "logs-2027"),
        names(bucketService.listBuckets(new ListBucketsOptions(null, "eu-west-1", null, null)).buckets()));
    assertEquals(inOrder(all, "data-a", "logs-2026"),
        names(bucketService.listBuckets(new ListBucketsOptions(null, "local", null, null)).buckets()),
        "A bucket created without a location constraint is in the default region.");
    assertEquals(inOrder(all, "logs-2025", "logs-2027"),
        names(bucketService.listBuckets(new ListBucketsOptions("logs-", "eu-west-1", null, 10)).buckets()));
  }

  /**
   * The token marks the last bucket of a page rather than the number of buckets listed, so the buckets that are
   * created or deleted between two pages neither repeat a bucket nor skip one.
   */
  @MethodSource("bucketServices")
  @ParameterizedTest
  void aBucketCreatedOrDeletedBetweenPagesDoesNotShiftTheListing(BucketService bucketService) {
    List<String> created = create(bucketService, 6);

    ListBucketsAns first = bucketService.listBuckets(new ListBucketsOptions(null, null, null, 3));
    assertEquals(created.subList(0, 3), names(first.buckets()));
    bucketService.deleteBucket(created.get(1));
    bucketService.deleteBucket(created.get(2));
    bucketService.createBucket("created-meanwhile");

    ListBucketsAns second = bucketService.listBuckets(new ListBucketsOptions(null, null, first.continuationToken(), 3));
    assertEquals(List.of(created.get(3), created.get(4), created.get(5)), names(second.buckets()));
    assertNotNull(second.continuationToken());
    ListBucketsAns third = bucketService.listBuckets(new ListBucketsOptions(null, null, second.continuationToken(), 3));
    assertEquals(List.of("created-meanwhile"), names(third.buckets()));
    assertNull(third.continuationToken());
  }

  @MethodSource("bucketServices")
  @ParameterizedTest
  void rejectsAMaxBucketsOutOfRangeAndATokenItDidNotAnswerWith(BucketService bucketService) {
    create(bucketService, 2);

    assertThrows(LocalS3InvalidArgumentException.class,
        () -> bucketService.listBuckets(new ListBucketsOptions(null, null, null, 0)));
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> bucketService.listBuckets(new ListBucketsOptions(null, null, null, 10001)));
    assertEquals(2, bucketService.listBuckets(new ListBucketsOptions(null, null, null, 10000)).buckets().size());
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> bucketService.listBuckets(new ListBucketsOptions(null, null, "not-a-token", null)));
    assertThrows(LocalS3InvalidArgumentException.class, () -> bucketService.listBuckets(
        new ListBucketsOptions(null, null, ContinuationTokenUtils.encode("a key of ListObjectsV2"), null)));
  }

  /**
   * Create buckets, each in a millisecond of its own or not: the order is total either way, by name within a
   * millisecond.
   */
  private static List<String> create(BucketService bucketService, int count) {
    for (int i = 0; i < count; i++) {
      bucketService.createBucket(String.format("bucket-%02d", i));
    }
    return names(bucketService.listBuckets());
  }

  private static List<List<String>> pages(BucketService bucketService, java.util.function.Function<String,
      ListBucketsOptions> options) {
    List<List<String>> pages = new ArrayList<>();
    String token = null;
    do {
      ListBucketsAns page = bucketService.listBuckets(options.apply(token));
      pages.add(names(page.buckets()));
      token = page.continuationToken();
      if (pages.size() > 100) {
        throw new AssertionError("The listing doesn't end: " + pages);
      }
    } while (token != null);
    return pages;
  }

  private static List<String> inOrder(List<String> listing, String... names) {
    List<String> expected = List.of(names);
    return listing.stream().filter(expected::contains).toList();
  }

  private static List<String> names(List<Bucket> buckets) {
    return buckets.stream().map(Bucket::getName).toList();
  }

}
