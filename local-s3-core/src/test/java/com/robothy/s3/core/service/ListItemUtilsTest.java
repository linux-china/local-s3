package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.jupiter.api.Test;

class ListItemUtilsTest {

  @Test
  void filterByPrefix() {
    NavigableMap<String, ObjectMetadata>
        filtered = ListItemUtils.filterByPrefix(new ConcurrentSkipListMap<>(Map.of()), "prefix");
    assertEquals(0, filtered.size());

    ObjectMetadata object = new ObjectMetadata();
    NavigableMap<String, ObjectMetadata> filtered1 = ListItemUtils.filterByPrefix(new ConcurrentSkipListMap<>(
        Map.of("prefix", object, "prefiu", object, "prefix1", object)), "prefix");
    assertEquals(2, filtered1.size());

    NavigableMap<String, ObjectMetadata> filtered2 =
        ListItemUtils.filterByPrefix(new ConcurrentSkipListMap<>(Map.of("prefix", object)), null);
    assertEquals(1, filtered2.size());

    NavigableMap<String, ObjectMetadata> filtered3 = ListItemUtils.filterByPrefix(new ConcurrentSkipListMap<>(Map.of(
        "prefix1", object, "prefix2", object, "prefiy", object)), "prefix");
    assertEquals(2, filtered3.size());

    NavigableMap<String, ObjectMetadata> filtered4 = ListItemUtils.filterByPrefix(new ConcurrentSkipListMap<>(Map.of(
        "prefiy", object, "prefiz", object)), "prefix");
    assertEquals(0, filtered4.size());
  }

  /**
   * Filtering by a prefix keeps the keys that continue it with {@code Character.MAX_VALUE}.
   */
  @Test
  void filterByPrefixKeepsKeysThatContinueThePrefixWithTheMaxCharacter() {
    ObjectMetadata object = new ObjectMetadata();
    NavigableMap<String, ObjectMetadata> items = new ConcurrentSkipListMap<>(Map.of(
        "a", object, "a\uFFFF", object, "a\uFFFFb", object, "b", object));
    assertEquals(List.of("a", "a\uFFFF", "a\uFFFFb"),
        List.copyOf(ListItemUtils.filterByPrefix(items, "a").keySet()));
    assertEquals(List.of("a\uFFFF", "a\uFFFFb"),
        List.copyOf(ListItemUtils.filterByPrefix(items, "a\uFFFF").keySet()));
  }

  @Test
  void lastKeyNotAfterPrefix() {
    ObjectMetadata object = new ObjectMetadata();
    NavigableMap<String, ObjectMetadata> items = new ConcurrentSkipListMap<>(Map.of(
        "a", object, "dir/a", object, "dir/\uFFFFz", object, "dir0", object));
    assertEquals("dir/\uFFFFz", ListItemUtils.lastKeyNotAfterPrefix(items, "dir/"));
    assertEquals("a", ListItemUtils.lastKeyNotAfterPrefix(items, "b"));
    assertNull(ListItemUtils.lastKeyNotAfterPrefix(items, "0"));
    assertEquals("dir0", ListItemUtils.lastKeyNotAfterPrefix(items, ""));
    assertNull(ListItemUtils.lastKeyNotAfterPrefix(new ConcurrentSkipListMap<String, ObjectMetadata>(), ""));
    NavigableMap<String, ObjectMetadata> view = items.headMap("dir/a", true);
    assertEquals("dir/a", ListItemUtils.lastKeyNotAfterPrefix(view, "dir/"));
  }

  @Test
  void prefixSuccessor() {
    assertEquals("dir0", ListItemUtils.prefixSuccessor("dir/"));
    assertEquals("dis", ListItemUtils.prefixSuccessor("dir\uFFFF\uFFFF"));
    assertNull(ListItemUtils.prefixSuccessor("\uFFFF"));
    assertNull(ListItemUtils.prefixSuccessor(""));
  }

  /**
   * Skipping a prefix skips every key that starts with it, also the keys that continue it with
   * {@code Character.MAX_VALUE}, and tolerates a view whose bounds end before the successor of the prefix.
   */
  @Test
  void skipPrefix() {
    ObjectMetadata object = new ObjectMetadata();
    NavigableMap<String, ObjectMetadata> items = new ConcurrentSkipListMap<>(Map.of(
        "dir/a", object, "dir/\uFFFF", object, "dir/\uFFFF\uFFFFz", object, "dir0", object, "e", object));

    assertEquals(List.of("dir0", "e"), List.copyOf(ListItemUtils.skipPrefix(items, "dir/").keySet()));
    assertEquals(List.of(), List.copyOf(ListItemUtils.skipPrefix(items, "e").keySet()));
    NavigableMap<String, ObjectMetadata> view = items.subMap("dir/", true, "dir/\uFFFF", true);
    assertTrue(ListItemUtils.skipPrefix(view, "dir/").isEmpty());
    assertEquals(List.of("dir/\uFFFF"), List.copyOf(ListItemUtils.skipPrefix(view, "dir/a").keySet()));
  }

}
