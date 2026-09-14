package com.robothy.s3.core.service;

import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Algorithm implementation of list objects.
 */
public class ListItemUtils {

  private static final ConcurrentSkipListMap<String, ?> EMPTY_OBJECT_MAP = new ConcurrentSkipListMap<>();

  private static <T> ConcurrentSkipListMap<String, T> emptyItemMap() {
    //noinspection unchecked
    return (ConcurrentSkipListMap<String, T>) EMPTY_OBJECT_MAP;
  }

  public static <T> NavigableMap<String, T> filterByKeyMarkerAndDelimiterForListObjects(
          NavigableMap<String, T> keyToItems, String keyMarker, String effectivePrefix, String delimiter) {

    if (Objects.isNull(keyMarker)) {
      return keyToItems;
    }

    NavigableMap<String, T> filteredByMarker = keyToItems.tailMap(keyMarker, false);
    if (Objects.isNull(delimiter) || filteredByMarker.isEmpty()) {
      return filteredByMarker;
    }

    Optional<String> commonPrefixOpt = commonPrefix(filteredByMarker.firstKey(), effectivePrefix, delimiter);
    if (commonPrefixOpt.isEmpty()) {
        return filteredByMarker;
    }
    String commonPrefix = commonPrefixOpt.get();
    if (commonPrefix.compareTo(keyMarker) > 0) {
      return filteredByMarker;
    }

    return skipPrefix(filteredByMarker, commonPrefix);
  }

  public static <T> NavigableMap<String, T> filterByKeyMarkerAndDelimiter(NavigableMap<String, T> keyToItems, String keyMarker, String delimiter) {
    if (Objects.isNull(keyMarker)) {
      return keyToItems;
    }

    NavigableMap<String, T> filteredByMarker = keyToItems.tailMap(keyMarker, false);
    if (Objects.isNull(delimiter) || filteredByMarker.isEmpty()) {
      return filteredByMarker;
    }

    String firstKey = filteredByMarker.firstKey();
    String firstKeyCommonPrefix;
    if (!firstKey.contains(delimiter) || (firstKeyCommonPrefix = calculateCommonPrefix(firstKey, delimiter)).compareTo(keyMarker) > 0) {
      return filteredByMarker;
    }

    return skipPrefix(filteredByMarker, firstKeyCommonPrefix);
  }



  public static <T> NavigableMap<String, T> filterByPrefix(NavigableMap<String, T> filteredByKeyMarker, String prefix) {
    if (Objects.isNull(prefix) || filteredByKeyMarker.isEmpty()) {
      return filteredByKeyMarker;
    }

    String fromKey = filteredByKeyMarker.floorKey(prefix);
    String toKey = filteredByKeyMarker.floorKey(prefix + Character.MAX_VALUE);
    if (Objects.isNull(toKey)) {
      return emptyItemMap();
    }

    if (Objects.isNull(fromKey)) {
      return filteredByKeyMarker.headMap(toKey, true);
    }

    boolean fromKeyInclusive = fromKey.startsWith(prefix);
    return filteredByKeyMarker.subMap(fromKey, fromKeyInclusive, toKey, true);
  }

  /**
   * The items after all keys that start with {@code prefix}, e.g. to go on listing after a common prefix without
   * visiting the keys that it rolls up, which takes a single lookup instead of one step per key.
   *
   * @param items the items, e.g. a view of the items that are listed.
   * @param prefix the prefix, e.g. a common prefix.
   * @return a view of the items whose keys are greater than every key that starts with {@code prefix}.
   */
  public static <T> NavigableMap<String, T> skipPrefix(NavigableMap<String, T> items, String prefix) {
    String successor = prefixSuccessor(prefix);
    // The lookup of the successor, unlike a view from it, tolerates a successor beyond the bounds of a view.
    String fromKey = Objects.isNull(successor) ? null : items.ceilingKey(successor);
    if (Objects.isNull(fromKey)) {
      return emptyItemMap();
    }
    return items.tailMap(fromKey, true);
  }

  /**
   * The least string that is greater than every string starting with {@code prefix}: the prefix with its last
   * character that isn't {@code Character.MAX_VALUE} incremented, and the characters after it dropped. Appending
   * {@code Character.MAX_VALUE} to the prefix instead would miss the keys that continue the prefix with that character.
   *
   * @param prefix the prefix.
   * @return the successor, or {@code null} if the prefix is empty or only {@code Character.MAX_VALUE}s, so that no
   *     string is greater than all strings starting with it.
   */
  static String prefixSuccessor(String prefix) {
    for (int i = prefix.length() - 1; i >= 0; i--) {
      char c = prefix.charAt(i);
      if (c != Character.MAX_VALUE) {
        return prefix.substring(0, i) + (char) (c + 1);
      }
    }
    return null;
  }

  public static String calculateCommonPrefix(String key, String delimiter) {
    return key.substring(0, key.indexOf(delimiter) + delimiter.length());
  }

  public static Optional<String> commonPrefix(String key, String effectivePrefix, String delimiter) {
    String suffix = key.substring(effectivePrefix.length());
    if (Objects.nonNull(delimiter) && suffix.contains(delimiter)) {
      return Optional.of(effectivePrefix + suffix.substring(0, suffix.indexOf(delimiter) + delimiter.length()));
    }
    return Optional.empty();
  }

}
