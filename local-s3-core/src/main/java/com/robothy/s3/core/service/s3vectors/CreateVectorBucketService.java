package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.BucketAlreadyExistsException;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.util.s3vectors.DateTimeUtils;
import com.robothy.s3.core.util.vectors.ValidationUtils;
import com.robothy.s3.datatypes.s3vectors.EncryptionConfiguration;
import com.robothy.s3.datatypes.s3vectors.VectorBucket;
import com.robothy.s3.datatypes.s3vectors.response.CreateVectorBucketResponse;
import java.util.Map;

public interface CreateVectorBucketService extends S3VectorsMetadataAware {

  default CreateVectorBucketResponse createVectorBucket(String bucketName, EncryptionConfiguration encryptionConfiguration) {
    return createVectorBucket(bucketName, encryptionConfiguration, null);
  }

  /**
   * Create a vector bucket with tags.
   *
   * @param tags the tags of the bucket; {@code null} for none.
   */
  default CreateVectorBucketResponse createVectorBucket(String bucketName, EncryptionConfiguration encryptionConfiguration,
                                                        Map<String, String> tags) {
    return changeBucket(bucketName, BucketGuard.Change.CREATE, () -> {
      validateBucketName(bucketName);
      ValidationUtils.validateTags(tags);
      assertBucketDoesNotExist(bucketName);
      VectorBucketMetadata bucketMetadata = createBucketMetadata(bucketName, encryptionConfiguration);
      if (tags != null) {
        bucketMetadata.getTags().putAll(tags);
      }
      storeBucketMetadata(bucketMetadata);
      return buildResponse(bucketMetadata);
    });
  }

  private static void validateBucketName(String bucketName) {
    if (bucketName == null || bucketName.trim().isEmpty()) {
      throw new LocalS3VectorException(
          LocalS3VectorErrorType.INVALID_REQUEST,
          "Vector bucket name is required"
      );
    }
    // Reuse existing bucket validation from LocalS3
    BucketAssertions.assertBucketNameIsValid(bucketName);
  }

  private void assertBucketDoesNotExist(String bucketName) {
    if (metadata().getVectorBucketMetadataMap().containsKey(bucketName)) {
      throw new BucketAlreadyExistsException("Vector bucket already exists: " + bucketName);
    }
  }

  private VectorBucketMetadata createBucketMetadata(String bucketName, EncryptionConfiguration encryptionConfiguration) {
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName(bucketName);
    bucketMetadata.setCreationDate(DateTimeUtils.getCurrentTimestamp());
    bucketMetadata.setEncryptionConfiguration(encryptionConfiguration);
    return bucketMetadata;
  }

  private void storeBucketMetadata(VectorBucketMetadata bucketMetadata) {
    metadata().addVectorBucketMetadata(bucketMetadata);
  }

  private CreateVectorBucketResponse buildResponse(VectorBucketMetadata bucketMetadata) {
    return CreateVectorBucketResponse.builder()
        .vectorBucketName(bucketMetadata.getVectorBucketName())
        .vectorBucketArn(VectorBucket.generateArn(bucketMetadata.getVectorBucketName()))
        .creationDate(DateTimeUtils.formatTimestamp(bucketMetadata.getCreationDate()))
        .build();
  }

}
