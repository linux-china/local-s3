package com.robothy.s3.core.converters.deserializer;

import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.util.ObjectKeys;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import tools.jackson.databind.util.StdConverter;

/**
 * Convert uploads map to a {@linkplain java.util.concurrent.ConcurrentSkipListMap} instance.
 */
public class UploadMetadataMapConverter extends StdConverter<Map<String, Map<String, UploadMetadata>>,
    NavigableMap<String, NavigableMap<String, UploadMetadata>>> {

  @Override
  public NavigableMap<String, NavigableMap<String, UploadMetadata>> convert(Map<String, Map<String, UploadMetadata>> value) {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> result = ObjectKeys.newMap();
    value.forEach((k, v) -> result.put(k, new ConcurrentSkipListMap<>(v)));
    return result;
  }

}
