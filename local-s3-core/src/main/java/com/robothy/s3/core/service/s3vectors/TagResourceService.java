package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.assertions.vectors.VectorResourceAssertions;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.model.internal.s3vectors.VectorResourceIdentifier;
import com.robothy.s3.core.util.vectors.ValidationUtils;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public interface TagResourceService extends S3VectorsMetadataAware {

  /**
   * Add tags to a vector bucket or index, replacing the value of each tag whose key it already has. The tags are added
   * all or none: if the resource would have more than {@value ValidationUtils#MAX_TAGS} tags, none is added.
   */
  default void tagResource(VectorResourceIdentifier resource, Map<String, String> tags) {
    if (tags == null || tags.isEmpty()) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION, "tags is required");
    }
    ValidationUtils.validateTags(tags);
    changeBucket(resource.bucketName(), () -> {
      Map<String, String> resourceTags = VectorResourceAssertions.assertResourceExists(this, resource);
      Set<String> keys = new HashSet<>(resourceTags.keySet());
      keys.addAll(tags.keySet());
      ValidationUtils.validateTagCount(keys.size());
      resourceTags.putAll(tags);
    });
  }

}
