package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import java.nio.FloatBuffer;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Basic implementation of {@linkplain VectorSearchEngine} using brute-force search.
 * This implementation calculates distances between the query vector and all candidate vectors,
 * then returns the K nearest neighbors using a heap-based approach for efficiency. The candidates are
 * read through {@linkplain VectorStorage#getVectorDataView(Long)}, so that they aren't copied.
 */
@Slf4j
class BasicVectorSearchEngine implements VectorSearchEngine {

  static final BasicVectorSearchEngine INSTANCE = new BasicVectorSearchEngine();

  /**
   * Nearest first. Candidates at the same distance, e.g. vectors of the same values, are ranked by vector ID, which is
   * unique within an index, so that a query answers the same vectors in the same order whatever order the index hands
   * out its candidates in.
   */
  private static final Comparator<VectorSearchResult> NEAREST_FIRST = Comparator
      .comparingDouble(VectorSearchResult::distance)
      .thenComparing(result -> result.vectorMetadata().getVectorId());

  @Override
  public double calculateDistance(float[] vector1, float[] vector2, DistanceMetric metric) {

    if (vector1 == null || vector2 == null) {
      throw new IllegalArgumentException("Vectors cannot be null");
    }
    if (vector1.length != vector2.length) {
      throw new IllegalArgumentException(
          String.format("Vector dimensions must match: %d vs %d", vector1.length, vector2.length));
    }
    if (vector1.length == 0) {
      throw new IllegalArgumentException("Vectors cannot be empty");
    }

    FloatBuffer vector2View = FloatBuffer.wrap(vector2);
    return switch (metric) {
      case EUCLIDEAN -> calculateEuclideanDistance(vector1, vector2View);
      case COSINE -> calculateCosineDistance(vector1, norm(vector1), vector2View);
    };
  }

  @Override
  public List<VectorSearchResult> findNearestVectors(
      float[] queryVector,
      Collection<VectorObjectMetadata> candidateVectors,
      VectorStorage vectorStorage,
      DistanceMetric distanceMetric,
      int k,
      MetadataFilterExpression metadataFilter) {

    if (queryVector == null) {
      throw new IllegalArgumentException("Query vector cannot be null");
    }
    if (vectorStorage == null) {
      throw new IllegalArgumentException("Vector storage cannot be null");
    }
    if (k <= 0) {
      throw new IllegalArgumentException("k must be positive, got: " + k);
    }
    if (candidateVectors == null || candidateVectors.isEmpty()) {
      return List.of();
    }

    log.debug("Searching for {} nearest vectors from {} candidates using {} metric",
        k, candidateVectors.size(), distanceMetric);

    // A max-heap of the K nearest vectors so far, whose head is the farthest of them.
    PriorityQueue<VectorSearchResult> maxHeap = new PriorityQueue<>(NEAREST_FIRST.reversed());

    double queryNorm = distanceMetric == DistanceMetric.COSINE ? norm(queryVector) : 0.0;
    for (VectorObjectMetadata vectorMetadata : candidateVectors) {
      // The metadata filter is checked before the data is read, to skip the distance of what it excludes.
      if (metadataFilter != null && !metadataFilter.matches(vectorMetadata)) {
        continue;
      }
      // The stored data is compared in place, without copying it.
      FloatBuffer candidateVector = candidateData(vectorMetadata, queryVector.length, vectorStorage);
      double distance = switch (distanceMetric) {
        case EUCLIDEAN -> calculateEuclideanDistance(queryVector, candidateVector);
        case COSINE -> calculateCosineDistance(queryVector, queryNorm, candidateVector);
      };

      if (maxHeap.size() < k) {
        maxHeap.offer(new VectorSearchResult(vectorMetadata, distance));
      } else if (isNearer(distance, vectorMetadata.getVectorId(), maxHeap.peek())) {
        maxHeap.poll();
        maxHeap.offer(new VectorSearchResult(vectorMetadata, distance));
      }
    }

    // Convert heap to sorted list (closest first)
    List<VectorSearchResult> results = maxHeap.stream()
        .sorted(NEAREST_FIRST)
        .collect(Collectors.toList());

    log.debug("Found {} nearest vectors", results.size());
    return results;
  }

  /**
   * Whether a candidate ranks before a result, in the order of {@linkplain #NEAREST_FIRST}, without creating a result
   * for every candidate that doesn't.
   */
  private static boolean isNearer(double distance, String vectorId, VectorSearchResult result) {
    int byDistance = Double.compare(distance, result.distance());
    return byDistance < 0 || (byDistance == 0 && vectorId.compareTo(result.vectorMetadata().getVectorId()) < 0);
  }

  /**
   * The stored data of a candidate, which must exist and have the dimension of the query vector: the metadata of a
   * vector is only stored with its data, so anything else means that the data is lost or corrupt.
   */
  private static FloatBuffer candidateData(VectorObjectMetadata vectorMetadata, int dimension,
                                           VectorStorage vectorStorage) {
    Long storageId = vectorMetadata.getStorageId();
    if (storageId == null) {
      throw new IllegalStateException("The vector '" + vectorMetadata.getVectorId() + "' has no storage ID.");
    }
    FloatBuffer data = vectorStorage.getVectorDataView(storageId);
    if (data == null) {
      throw new IllegalStateException(String.format("The data of the vector '%s' (storage ID %d) is missing.",
          vectorMetadata.getVectorId(), storageId));
    }
    if (data.limit() != dimension) {
      throw new IllegalStateException(String.format(
          "The data of the vector '%s' (storage ID %d) has %d dimensions, but the query vector has %d.",
          vectorMetadata.getVectorId(), storageId, data.limit(), dimension));
    }
    return data;
  }

  /**
   * Calculate Euclidean distance: √(∑(ai - bi)²)
   *
   * <p>The components are subtracted as doubles, here and in the other distances: a float difference or product is
   * rounded to the 24 bits of mantissa of a float before it is summed, and over many dimensions such errors rank
   * candidates of close distances in the wrong order.
   */
  private static double calculateEuclideanDistance(float[] vector1, FloatBuffer vector2) {
    double sumSquaredDiffs = 0.0;

    for (int i = 0; i < vector1.length; i++) {
      double diff = (double) vector1[i] - vector2.get(i);
      sumSquaredDiffs += diff * diff;
    }

    return Math.sqrt(sumSquaredDiffs);
  }

  /**
   * Calculate Cosine distance: 1 - (A·B)/(||A|| × ||B||)
   * Returns a value between 0 and 2, where 0 means identical direction.
   *
   * @param normA the norm of {@code vector1}, see {@linkplain #norm(float[])}, which is the same for every candidate
   */
  private static double calculateCosineDistance(float[] vector1, double normA, FloatBuffer vector2) {
    double dotProduct = 0.0;
    double normB = 0.0;

    for (int i = 0; i < vector1.length; i++) {
      double b = vector2.get(i);
      dotProduct += vector1[i] * b;
      normB += b * b;
    }

    normB = Math.sqrt(normB);

    // Handle zero vectors (avoid division by zero)
    if (normA == 0.0 || normB == 0.0) {
      return 1.0; // Maximum cosine distance for zero vectors
    }

    double cosineSimilarity = dotProduct / (normA * normB);
    // Clamp to [-1, 1] to handle floating point precision issues
    cosineSimilarity = Math.max(-1.0, Math.min(1.0, cosineSimilarity));

    return 1.0 - cosineSimilarity;
  }

  private static double norm(float[] vector) {
    double sum = 0.0;
    for (float value : vector) {
      sum += (double) value * value;
    }
    return Math.sqrt(sum);
  }

}
