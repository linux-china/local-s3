package com.robothy.s3.core.model.internal.s3vectors;

import java.util.concurrent.ConcurrentSkipListMap;
import tools.jackson.databind.util.StdConverter;

/**
 * Jackson converter for deserializing VectorObjectMetadata maps.
 * Ensures thread-safe concurrent maps are used for vector object metadata storage.
 */
public class VectorObjectMetadataMapConverter extends StdConverter<ConcurrentSkipListMap<String, VectorObjectMetadata>, ConcurrentSkipListMap<String, VectorObjectMetadata>> {

  @Override
  public ConcurrentSkipListMap<String, VectorObjectMetadata> convert(ConcurrentSkipListMap<String, VectorObjectMetadata> value) {
    if (value == null) {
      return new ConcurrentSkipListMap<>();
    }
    return value;
  }
}
