package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.assertions.vectors.VectorResourceAssertions;
import com.robothy.s3.core.model.internal.s3vectors.VectorResourceIdentifier;
import com.robothy.s3.core.util.vectors.ValidationUtils;
import java.util.Collection;

public interface UntagResourceService extends S3VectorsMetadataAware {

  /**
   * Remove tags from a vector bucket or index. A key that the resource has no tag of is ignored.
   */
  default void untagResource(VectorResourceIdentifier resource, Collection<String> tagKeys) {
    ValidationUtils.validateTagKeys(tagKeys);
    changeBucket(resource.bucketName(), () -> {
      VectorResourceAssertions.assertResourceExists(this, resource).keySet().removeAll(tagKeys);
    });
  }

}
