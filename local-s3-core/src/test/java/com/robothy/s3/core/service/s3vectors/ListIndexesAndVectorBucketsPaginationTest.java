package com.robothy.s3.core.service.s3vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.service.manager.vectors.LocalS3VectorsManager;
import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import com.robothy.s3.datatypes.s3vectors.VectorDataType;
import com.robothy.s3.datatypes.s3vectors.response.IndexSummary;
import com.robothy.s3.datatypes.s3vectors.response.ListIndexesResponse;
import com.robothy.s3.datatypes.s3vectors.response.ListVectorBucketsResponse;
import com.robothy.s3.datatypes.s3vectors.response.VectorBucketSummary;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListIndexesAndVectorBucketsPaginationTest {

  private static final String BUCKET = "bucket";

  private final S3VectorsService service = LocalS3VectorsManager.createInMemory().s3VectorsService();

  @Test
  void listIndexesContinuesAfterTheLastNameWhateverChangesBetweenPages() {
    service.createVectorBucket(BUCKET, null);
    List.of("a", "b", "c", "d").forEach(this::createIndex);

    ListIndexesResponse first = service.listIndexes(BUCKET, 2, null, null);
    assertEquals(List.of("a", "b"), indexNames(first));

    service.deleteIndex(BUCKET, "a");
    createIndex("aa");
    ListIndexesResponse second = service.listIndexes(BUCKET, 2, first.nextToken(), null);
    assertEquals(List.of("c", "d"), indexNames(second));
    assertNull(second.nextToken());
  }

  @Test
  void listVectorBucketsContinuesAfterTheLastNameWhateverChangesBetweenPages() {
    List.of("bucket-a", "bucket-b", "bucket-c").forEach(name -> service.createVectorBucket(name, null));

    ListVectorBucketsResponse first = service.listVectorBuckets(1, null, null);
    assertEquals(List.of("bucket-a"), bucketNames(first));

    service.deleteVectorBucket("bucket-a");
    ListVectorBucketsResponse second = service.listVectorBuckets(1, first.nextToken(), null);
    assertEquals(List.of("bucket-b"), bucketNames(second));
    ListVectorBucketsResponse third = service.listVectorBuckets(1, second.nextToken(), null);
    assertEquals(List.of("bucket-c"), bucketNames(third));
    assertNull(third.nextToken());
  }

  @Test
  void aFullPageAtTheEndHasNoNextToken() {
    service.createVectorBucket(BUCKET, null);
    List.of("a", "b").forEach(this::createIndex);

    ListIndexesResponse response = service.listIndexes(BUCKET, 2, null, null);
    assertEquals(List.of("a", "b"), indexNames(response));
    assertNull(response.nextToken());
  }

  @Test
  void anOffsetTokenIsRejected() {
    service.createVectorBucket(BUCKET, null);
    String negativeOffset = Base64.getEncoder().encodeToString("-1".getBytes(StandardCharsets.UTF_8));

    LocalS3VectorException indexes = assertThrows(LocalS3VectorException.class,
        () -> service.listIndexes(BUCKET, null, negativeOffset, null));
    assertEquals(LocalS3VectorErrorType.VALIDATION, indexes.getErrorType());
    LocalS3VectorException buckets = assertThrows(LocalS3VectorException.class,
        () -> service.listVectorBuckets(null, negativeOffset, null));
    assertEquals(LocalS3VectorErrorType.VALIDATION, buckets.getErrorType());
  }

  private void createIndex(String name) {
    service.createIndex(BUCKET, name, VectorDataType.FLOAT32, 2, DistanceMetric.EUCLIDEAN, null);
  }

  private static List<String> indexNames(ListIndexesResponse response) {
    return response.indexes().stream().map(IndexSummary::indexName).toList();
  }

  private static List<String> bucketNames(ListVectorBucketsResponse response) {
    return response.vectorBuckets().stream().map(VectorBucketSummary::vectorBucketName).toList();
  }
}
