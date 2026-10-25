package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.assertions.vectors.VectorBucketAssertions;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.util.vectors.CollectionProcessingUtils;
import com.robothy.s3.core.util.vectors.PaginationUtils;
import com.robothy.s3.core.util.vectors.ValidationUtils;
import com.robothy.s3.datatypes.s3vectors.VectorIndex;
import com.robothy.s3.datatypes.s3vectors.response.IndexSummary;
import com.robothy.s3.datatypes.s3vectors.response.ListIndexesResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public interface ListIndexesService extends S3VectorsMetadataAware {

  default ListIndexesResponse listIndexes(String vectorBucketName,
      Integer maxResults, String nextToken, String prefix) {

    int normalizedMaxResults = validateRequestParameters(maxResults, prefix);
    String lastName = PaginationUtils.parseNextToken(nextToken);

    VectorBucketMetadata bucketMetadata = assertBucketExists(vectorBucketName);
    List<VectorIndexMetadata> allIndexes = getAllIndexesFromBucket(bucketMetadata);
    List<VectorIndexMetadata> filteredIndexes = applyFiltersAndSorting(allIndexes, prefix);
    List<VectorIndexMetadata> paginatedResults = paginateResults(filteredIndexes, lastName, normalizedMaxResults);
    boolean truncated = paginatedResults.size() > normalizedMaxResults;
    if (truncated) {
      paginatedResults = paginatedResults.subList(0, normalizedMaxResults);
    }

    List<IndexSummary> summaries = convertToSummaries(paginatedResults, vectorBucketName);
    String responseNextToken = truncated
        ? PaginationUtils.generateNextToken(paginatedResults.getLast().getIndexName()) : null;

    return buildResponse(summaries, responseNextToken);
  }

  private int validateRequestParameters(Integer maxResults, String prefix) {
    ValidationUtils.validatePrefix(prefix);
    return PaginationUtils.validateAndNormalizeMaxResults(maxResults);
  }

  private VectorBucketMetadata assertBucketExists(String bucketName) {
    return VectorBucketAssertions.assertVectorBucketExists(this, bucketName);
  }

  private List<VectorIndexMetadata> getAllIndexesFromBucket(VectorBucketMetadata bucketMetadata) {
    return new ArrayList<>(bucketMetadata.getIndexes().values());
  }

  private List<VectorIndexMetadata> applyFiltersAndSorting(List<VectorIndexMetadata> indexes, String prefix) {
    List<VectorIndexMetadata> filtered = CollectionProcessingUtils.applyPrefixFilter(
        indexes, prefix, VectorIndexMetadata::getIndexName);
    return CollectionProcessingUtils.sortByName(filtered, VectorIndexMetadata::getIndexName);
  }

  private List<VectorIndexMetadata> paginateResults(List<VectorIndexMetadata> indexes, String lastName, int maxResults) {
    return CollectionProcessingUtils.applyPagination(indexes, lastName, maxResults, VectorIndexMetadata::getIndexName);
  }

  private List<IndexSummary> convertToSummaries(List<VectorIndexMetadata> indexes, String bucketName) {
    return indexes.stream()
        .map(indexMetadata -> createIndexSummary(indexMetadata, bucketName))
        .collect(Collectors.toList());
  }

  private IndexSummary createIndexSummary(VectorIndexMetadata indexMetadata, String bucketName) {
    return new IndexSummary(
        indexMetadata.getCreationDate() / 1000,
        VectorIndex.generateArn(bucketName, indexMetadata.getIndexName()),
        indexMetadata.getIndexName(),
        bucketName);
  }

  private ListIndexesResponse buildResponse(List<IndexSummary> summaries, String nextToken) {
    return new ListIndexesResponse(summaries, nextToken);
  }

}
