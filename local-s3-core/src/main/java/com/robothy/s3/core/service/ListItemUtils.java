package com.robothy.s3.core.service;

import com.robothy.s3.core.util.ObjectKeys;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Algorithm implementation of list objects.
 */
public class ListItemUtils {

  private static final ConcurrentSkipListMap<String, ?> EMPTY_OBJECT_MAP = ObjectKeys.newMap();

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
    if (ObjectKeys.compare(commonPrefix, keyMarker) > 0) {
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
    if (!firstKey.contains(delimiter) || ObjectKeys.compare(firstKeyCommonPrefix = calculateCommonPrefix(firstKey, delimiter), keyMarker) > 0) {
      return filteredByMarker;
    }

    return skipPrefix(filteredByMarker, firstKeyCommonPrefix);
  }



  public static <T> NavigableMap<String, T> filterByPrefix(NavigableMap<String, T> filteredByKeyMarker, String prefix) {
    if (Objects.isNull(prefix) || filteredByKeyMarker.isEmpty()) {
      return filteredByKeyMarker;
    }

    String fromKey = filteredByKeyMarker.floorKey(prefix);
    String toKey = lastKeyNotAfterPrefix(filteredByKeyMarker, prefix);
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
   * The greatest key that is not greater than every key starting with {@code prefix}, e.g. the last key that a common
   * prefix rolls up, if any key starts with it. Unlike {@code floorKey(prefix + Character.MAX_VALUE)}, it doesn't miss
   * the keys that continue the prefix with a character that sorts after {@code Character.MAX_VALUE}.
   *
   * @param items the items, e.g. a view of the items that are listed.
   * @param prefix the prefix.
   * @return the key, or {@code null} if there is none.
   */
  static <T> String lastKeyNotAfterPrefix(NavigableMap<String, T> items, String prefix) {
    String successor = prefixSuccessor(prefix);
    if (Objects.nonNull(successor)) {
      // The lookup of the successor, unlike a view to it, tolerates a successor beyond the bounds of a view.
      return items.lowerKey(successor);
    }
    return items.isEmpty() ? null : items.lastKey();
  }

  /**
   * The least key that sorts after every key starting with {@code prefix}, in the order of the keys that S3 lists.
   *
   * @see ObjectKeys#prefixSuccessor(String)
   */
  static String prefixSuccessor(String prefix) {
    return ObjectKeys.prefixSuccessor(prefix);
  }

  public static String calculateCommonPrefix(String key, String delimiter) {
    return key.substring(0, key.indexOf(delimiter) + delimiter.length());
  }

  public static Optional<String> commonPrefix(String key, String effectivePrefix, String delimiter) {
    // A marker before the prefix, e.g. an empty one, leaves the keys before the prefix in the view.
    if (!key.startsWith(effectivePrefix)) {
      return Optional.empty();
    }
    String suffix = key.substring(effectivePrefix.length());
    if (Objects.nonNull(delimiter) && suffix.contains(delimiter)) {
      return Optional.of(effectivePrefix + suffix.substring(0, suffix.indexOf(delimiter) + delimiter.length()));
    }
    return Optional.empty();
  }

}
