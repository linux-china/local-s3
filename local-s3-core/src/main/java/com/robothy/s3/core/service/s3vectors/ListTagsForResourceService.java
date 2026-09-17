package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.assertions.vectors.VectorResourceAssertions;
import com.robothy.s3.core.model.internal.s3vectors.VectorResourceIdentifier;
import com.robothy.s3.datatypes.s3vectors.response.ListTagsForResourceResponse;
import java.util.TreeMap;

public interface ListTagsForResourceService extends S3VectorsMetadataAware {

  default ListTagsForResourceResponse listTagsForResource(VectorResourceIdentifier resource) {
    return ListTagsForResourceResponse.builder()
        .tags(new TreeMap<>(VectorResourceAssertions.assertResourceExists(this, resource)))
        .build();
  }

}
