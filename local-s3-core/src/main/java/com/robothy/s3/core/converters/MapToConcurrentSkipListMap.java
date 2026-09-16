package com.robothy.s3.core.converters;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import tools.jackson.databind.util.StdConverter;

/**
 * Restore data from a {@linkplain Map} to {@linkplain ConcurrentSkipListMap}.
 * @param <K> the key data type.
 * @param <V> the value data type.
 */
public class MapToConcurrentSkipListMap<K, V> extends StdConverter<Map<K, V>, ConcurrentSkipListMap<K, V>> {

  @Override
  public ConcurrentSkipListMap<K, V> convert(Map<K, V> value) {
    return new ConcurrentSkipListMap<>(value);
  }

}
