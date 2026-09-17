package com.robothy.s3.core.model.internal.s3vectors;

import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.util.S3VectorsArnUtils;
import com.robothy.s3.core.util.Strings;

/**
 * A resource that can be tagged, named by its ARN: a vector bucket, or an index of one.
 *
 * @param bucketName the name of the vector bucket, or of the bucket of the index.
 * @param indexName the name of the index; {@code null} if the resource is the vector bucket.
 */
public record VectorResourceIdentifier(String bucketName, String indexName) {

  private static final String INDEX_SEPARATOR = "/index/";

  public VectorResourceIdentifier {
    if (Strings.isBlank(bucketName)) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST, "Vector bucket name is required");
    }
  }

  /**
   * The resource of an ARN of a vector bucket, {@code arn:aws:s3vectors:::vector-bucket/{bucketName}}, or of an index,
   * {@code arn:aws:s3vectors:::vector-bucket/{bucketName}/index/{indexName}}.
   *
   * @throws LocalS3VectorException if the ARN is neither.
   */
  public static VectorResourceIdentifier fromArn(String resourceArn) {
    if (resourceArn != null && resourceArn.contains(INDEX_SEPARATOR)) {
      IndexIdentifier index = IndexIdentifier.fromIndexArn(resourceArn);
      return new VectorResourceIdentifier(index.bucketName(), index.indexName());
    }
    String bucketName = S3VectorsArnUtils.extractBucketNameFromArn(resourceArn);
    if (Strings.isBlank(bucketName) || bucketName.contains("/")) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST, "Invalid resource ARN: " + resourceArn);
    }
    return new VectorResourceIdentifier(bucketName, null);
  }

  /**
   * Whether the resource is an index rather than a vector bucket.
   */
  public boolean isIndex() {
    return indexName != null;
  }

}
