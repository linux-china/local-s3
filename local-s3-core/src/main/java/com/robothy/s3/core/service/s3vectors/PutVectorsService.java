package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.assertions.vectors.VectorBucketAssertions;
import com.robothy.s3.core.assertions.vectors.VectorIndexAssertions;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.datatypes.s3vectors.response.PutVectorsResponse;
import com.robothy.s3.datatypes.s3vectors.request.PutInputVector;
import java.util.List;

public interface PutVectorsService extends S3VectorsMetadataAware, S3VectorsStorageAware {

  /**
   * The maximum number of vectors of a PutVectors request.
   */
  int MAX_VECTORS_PER_REQUEST = 500;

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
      validateVectors(vectors, indexMetadata.getDimension());

      for (PutInputVector inputVector : vectors) {
        putVector(inputVector, indexMetadata);
      }
      return new PutVectorsResponse();
    });
  }

  private void validateVectors(List<PutInputVector> vectors, int dimension) {
    if (vectors == null || vectors.isEmpty()) {
      throw invalid("Vectors list cannot be null or empty");
    }
    if (vectors.size() > MAX_VECTORS_PER_REQUEST) {
      throw invalid("Cannot put more than " + MAX_VECTORS_PER_REQUEST + " vectors at once");
    }
    for (int i = 0; i < vectors.size(); i++) {
      validateVector(vectors.get(i), i, dimension);
    }
  }

  private void validateVector(PutInputVector inputVector, int position, int dimension) {
    if (inputVector == null) {
      throw invalid("Vector at position " + position + " is required");
    }
    String key = inputVector.getKey();
    if (key == null || key.trim().isEmpty()) {
      throw invalid("Vector key is required for the vector at position " + position);
    }
    if (inputVector.getData() == null || inputVector.getData().getValues() == null) {
      throw invalid("Vector data is required for key '" + key + "'");
    }
    float[] values = inputVector.getData().getValues();
    if (values.length != dimension) {
      throw invalid(String.format("Vector dimension %d of key '%s' does not match index dimension %d",
          values.length, key, dimension));
    }
    for (int i = 0; i < values.length; i++) {
      if (!Float.isFinite(values[i])) {
        throw invalid("Vector data of key '" + key + "' contains invalid value at index " + i + ": " + values[i]);
      }
    }
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
