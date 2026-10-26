package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.assertions.vectors.VectorBucketAssertions;
import com.robothy.s3.core.assertions.vectors.VectorIndexAssertions;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import com.robothy.s3.datatypes.s3vectors.response.PutVectorsResponse;
import com.robothy.s3.core.util.JsonUtils;
import com.robothy.s3.datatypes.s3vectors.request.PutInputVector;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface PutVectorsService extends S3VectorsMetadataAware, S3VectorsStorageAware {

  /**
   * The maximum number of vectors of a PutVectors request.
   */
  int MAX_VECTORS_PER_REQUEST = 500;

  int MAX_KEY_LENGTH = 1024;

  int MAX_METADATA_BYTES = 40 * 1024;

  int MAX_FILTERABLE_METADATA_BYTES = 2 * 1024;

  /**
   * Put vectors into an index, replacing the vectors of the same keys; of vectors with the same key in one request, the
   * last one is kept.
   *
   * <p>The request is applied as a whole: if any vector is invalid, e.g. its dimension differs from the dimension of
   * the index, the request is rejected with a {@code ValidationException} and no vector is put.
   */
  default PutVectorsResponse putVectors(String vectorBucketName, String indexName,
      List<PutInputVector> vectors) {
    return changeBucket(vectorBucketName, () -> {
      VectorBucketMetadata bucketMetadata = VectorBucketAssertions.assertVectorBucketExists(this, vectorBucketName);
      VectorIndexMetadata indexMetadata = VectorIndexAssertions.assertVectorIndexExists(bucketMetadata, indexName);
      // Every vector is validated before any is stored, so that a rejected request changes nothing.
      validateVectors(vectors, indexMetadata);

      for (PutInputVector inputVector : vectors) {
        putVector(inputVector, indexMetadata);
      }
      return new PutVectorsResponse(null);
    });
  }

  private void validateVectors(List<PutInputVector> vectors, VectorIndexMetadata index) {
    if (vectors == null || vectors.isEmpty()) {
      throw invalid("Vectors list cannot be null or empty");
    }
    if (vectors.size() > MAX_VECTORS_PER_REQUEST) {
      throw invalid("Cannot put more than " + MAX_VECTORS_PER_REQUEST + " vectors at once");
    }
    for (int i = 0; i < vectors.size(); i++) {
      validateVector(vectors.get(i), i, index.getDimension(), index.getDistanceMetric());
      validateMetadata(vectors.get(i), index.getNonFilterableMetadataKeys());
    }
  }

  private void validateVector(PutInputVector inputVector, int position, int dimension,
      DistanceMetric distanceMetric) {
    if (inputVector == null) {
      throw invalid("Vector at position " + position + " is required");
    }
    String key = inputVector.getKey();
    if (key == null || key.trim().isEmpty()) {
      throw invalid("Vector key is required for the vector at position " + position);
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw invalid("Vector key at position " + position + " is longer than " + MAX_KEY_LENGTH + " characters");
    }
    if (inputVector.getData() == null || inputVector.getData().getValues() == null) {
      throw invalid("Vector data is required for key '" + key + "'");
    }
    float[] values = inputVector.getData().getValues();
    if (values.length != dimension) {
      throw invalid("Vector dimension " + values.length + " of key '" + key
          + "' does not match index dimension " + dimension);
    }
    int nonFinite = VectorSearchEngine.indexOfNonFinite(values);
    if (nonFinite >= 0) {
      throw invalid("Vector data of key '" + key + "' contains invalid value at index " + nonFinite + ": "
          + values[nonFinite]);
    }
    if (distanceMetric == DistanceMetric.COSINE && VectorSearchEngine.isZeroVector(values)) {
      throw invalid("Vector data of key '" + key + "' is a zero vector, which a cosine index does not accept");
    }
  }

  /**
   * The sizes are the ones of the metadata written as JSON, which AWS doesn't define precisely; they only have to reject
   * what AWS rejects, so that a request that works locally doesn't fail there.
   */
  private void validateMetadata(PutInputVector inputVector, List<String> nonFilterableKeys) {
    if (!hasMetadata(inputVector)) {
      return;
    }
    JsonNode metadata = inputVector.getMetadata();
    int size = jsonSize(metadata);
    if (size > MAX_METADATA_BYTES) {
      throw invalid("Metadata of key '" + inputVector.getKey() + "' is " + size + " bytes, more than the "
          + MAX_METADATA_BYTES + " bytes allowed");
    }
    if (size <= MAX_FILTERABLE_METADATA_BYTES) {
      return;
    }
    JsonNode filterable = metadata;
    if (metadata instanceof ObjectNode object && nonFilterableKeys != null && !nonFilterableKeys.isEmpty()) {
      filterable = object.deepCopy().remove(nonFilterableKeys);
    }
    int filterableSize = jsonSize(filterable);
    if (filterableSize > MAX_FILTERABLE_METADATA_BYTES) {
      throw invalid("Filterable metadata of key '" + inputVector.getKey() + "' is " + filterableSize
          + " bytes, more than the " + MAX_FILTERABLE_METADATA_BYTES + " bytes allowed; list the keys that needn't be "
          + "filtered by in the nonFilterableMetadataKeys of the index");
    }
  }

  private static int jsonSize(JsonNode node) {
    return JsonUtils.toJson(node).getBytes(StandardCharsets.UTF_8).length;
  }

  private static LocalS3VectorException invalid(String message) {
    return new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION, message);
  }

  private void putVector(PutInputVector inputVector, VectorIndexMetadata indexMetadata) {
    float[] vectorData = inputVector.getData().getValues();
    Long storageId = vectorStorage().putVectorData(vectorData);
    VectorObjectMetadata vectorMetadata = createVectorMetadata(inputVector, vectorData, storageId);

    VectorObjectMetadata replaced = indexMetadata.addVectorObject(vectorMetadata);
    deleteReplacedVectorData(replaced, storageId);
  }

  /**
   * Delete the data of the vector that a put replaced. Within a persisted change, the storage deletes it once the
   * vector bucket is persisted, so that the persisted metadata never references deleted data.
   */
  private void deleteReplacedVectorData(VectorObjectMetadata replaced, Long storageId) {
    if (replaced != null && replaced.getStorageId() != null && !replaced.getStorageId().equals(storageId)) {
      vectorStorage().deleteVectorData(replaced.getStorageId());
    }
  }

  private VectorObjectMetadata createVectorMetadata(PutInputVector inputVector, float[] vectorData, Long storageId) {
    VectorObjectMetadata vectorMetadata = new VectorObjectMetadata();
    vectorMetadata.setVectorId(inputVector.getKey());
    vectorMetadata.setDimension(vectorData.length);
    vectorMetadata.setStorageId(storageId);
    vectorMetadata.setCreationDate(System.currentTimeMillis());

    if (hasMetadata(inputVector)) {
      vectorMetadata.setMetadata(inputVector.getMetadata());
    }

    return vectorMetadata;
  }

  private boolean hasMetadata(PutInputVector inputVector) {
    return inputVector.getMetadata() != null && !inputVector.getMetadata().isNull();
  }
}
