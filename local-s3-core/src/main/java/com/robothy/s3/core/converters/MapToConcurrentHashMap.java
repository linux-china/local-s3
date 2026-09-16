package com.robothy.s3.core.converters;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import tools.jackson.databind.util.StdConverter;

/**
 * Restore data from a {@linkplain Map} to {@linkplain ConcurrentHashMap}.
 * @param <K> the key data type.
 * @param <V> the value data type.
 */
public class MapToConcurrentHashMap<K, V> extends StdConverter<Map<K, V>, ConcurrentHashMap<K, V>> {

  @Override
  public ConcurrentHashMap<K, V> convert(Map<K, V> value) {
    return new ConcurrentHashMap<>(value);
  }

}
