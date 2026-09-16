package com.robothy.s3.core.converters.deserializer;

import com.fasterxml.jackson.databind.util.StdConverter;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Reads the objects of a bucket, which are written as the metadata of each key, into the
 * {@linkplain ConcurrentSkipListMap} of {@linkplain ObjectMetadataRef} that a bucket holds them in.
 *
 * <p>The references are loaded: what is read here is a whole bucket, e.g. a copy of the initial data of an
 * {@code IN_MEMORY} service, so its objects are in heap already and have nothing to be read back from. A bucket whose
 * objects are read one at a time comes from a metadata store instead, which builds the references itself.
 */
public class ObjectMetadataMapConverter
    extends StdConverter<Map<String, ObjectMetadata>, ConcurrentSkipListMap<String, ObjectMetadataRef>> {

  @Override
  public ConcurrentSkipListMap<String, ObjectMetadataRef> convert(Map<String, ObjectMetadata> value) {
    ConcurrentSkipListMap<String, ObjectMetadataRef> refs = new ConcurrentSkipListMap<>();
    value.forEach((key, metadata) -> refs.put(key, ObjectMetadataRef.of(metadata)));
    return refs;
  }

}
