package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import java.util.Collection;
import java.util.List;

/**
 * Vector search engine interface for S3 Vectors similarity search operations.
 * Provides distance-based vector similarity calculation and TopK result selection.
 * <p>
 * The search engine works with the new separated architecture where vector metadata
 * and vector data are stored separately.
 */
public interface VectorSearchEngine {

  /**
   * The basic vector search engine implementation, which is stateless, so that every search shares one instance.
   *
   * @return the basic vector search engine instance
   */
  static VectorSearchEngine createBasic() {
    return BasicVectorSearchEngine.INSTANCE;
  }

  /**
   * Calculate the distance between two vectors using the specified metric.
   *
   * @param vector1 the first vector
   * @param vector2 the second vector
   * @param metric  the distance metric to use
   * @return the calculated distance between the vectors
   * @throws IllegalArgumentException if vectors have different dimensions or are invalid
   */
  double calculateDistance(float[] vector1, float[] vector2, DistanceMetric metric);

  /**
   * Find the K nearest vectors to the query vector from a collection of vectors.
   *
   * <p>Every candidate that satisfies the filter is compared with the query vector. A candidate whose data is missing,
   * or whose dimension differs from the query vector, means that the stored vectors are inconsistent with their
   * metadata, which fails the search rather than silently returning fewer vectors.
   *
   * @param queryVector      the query vector to search for
   * @param candidateVectors the collection of vector metadata to search in, which is only iterated, not copied
   * @param vectorStorage    the storage of the data of the candidates, which must not be deleted during the search
   * @param distanceMetric   the distance metric to use for calculations
   * @param k                the number of nearest neighbors to return
   * @param metadataFilter   condition that the metadata of a vector must satisfy to be searched at all;
   *                         {@linkplain MetadataFilterExpression#none()} to search every candidate
   * @return list of search results ordered by distance (closest first)
   * @throws IllegalArgumentException if k is invalid, or the query vector or the storage is null
   * @throws IllegalStateException    if the data of a candidate is missing or has another dimension
   */
  List<VectorSearchResult> findNearestVectors(
      float[] queryVector,
      Collection<VectorObjectMetadata> candidateVectors,
      VectorStorage vectorStorage,
      DistanceMetric distanceMetric,
      int k,
      MetadataFilterExpression metadataFilter
  );

  /**
   * Represents a vector search result with distance information.
   */
  record VectorSearchResult(VectorObjectMetadata vectorMetadata, double distance) {

    @Override
    public String toString() {
      return String.format("VectorSearchResult{vectorId='%s', distance=%.6f}",
          vectorMetadata.getVectorId(), distance);
    }
  }
}