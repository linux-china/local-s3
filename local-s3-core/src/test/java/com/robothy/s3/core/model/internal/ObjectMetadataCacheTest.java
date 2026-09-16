package com.robothy.s3.core.model.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.util.JsonUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ObjectMetadataCacheTest {

  private final Map<String, String> store = new HashMap<>();

  private final AtomicInteger reads = new AtomicInteger();

  @Test
  void keepsOnlyTheMetadataThatWasReadMostRecently() {
    ObjectMetadataCache cache = ObjectMetadataCache.bounded(2);
    ObjectMetadataRef a = ref("a", cache);
    ObjectMetadataRef b = ref("b", cache);
    ObjectMetadataRef c = ref("c", cache);

    a.get();
    b.get();
    assertEquals(2, reads.get());
    assertTrue(a.isLoaded());
    assertTrue(b.isLoaded());

    c.get();
    assertFalse(a.isLoaded(), "The metadata read longest ago is dropped once the bound is reached.");
    assertTrue(b.isLoaded());
    assertTrue(c.isLoaded());
    assertEquals(2, cache.size());

    // Reading it again reads the store again, and answers the same metadata.
    assertEquals("a", a.get().getLatestVersion());
    assertEquals(4, reads.get());
    assertFalse(b.isLoaded(), "b was read longest ago now.");
  }

  @Test
  void readingAgainKeepsTheMetadataThatIsInUse() {
    ObjectMetadataCache cache = ObjectMetadataCache.bounded(2);
    ObjectMetadataRef a = ref("a", cache);
    ObjectMetadataRef b = ref("b", cache);
    ObjectMetadataRef c = ref("c", cache);

    a.get();
    b.get();
    a.get(); // a is in use, so b is the one that was read longest ago.
    c.get();

    assertTrue(a.isLoaded());
    assertFalse(b.isLoaded());
    assertTrue(c.isLoaded());
  }

  @Test
  void keepsTheMetadataOfAChangeThatHasNotBeenWritten() {
    ObjectMetadataCache cache = ObjectMetadataCache.bounded(1);
    ObjectMetadataRef changing = ref("a", cache);
    ObjectMetadata changed = changing.get();
    changing.pin();

    ref("b", cache).get();
    ref("c", cache).get();

    assertTrue(changing.isLoaded(), "A pinned reference keeps the metadata that the change is being made on.");
    assertSame(changed, changing.get(), "The change is made on the metadata that is still answered.");

    // Written: it may be dropped now, and is tracked again by the read above.
    changing.unpin();
    ref("d", cache).get();
    ref("e", cache).get();
    assertFalse(changing.isLoaded());
  }

  @Test
  void aReferenceThatIsOnlyInHeapIsNeverDropped() {
    ObjectMetadataCache cache = ObjectMetadataCache.bounded(1);
    ObjectMetadataRef inHeap = ObjectMetadataRef.of(objectMetadata("a"));

    assertFalse(inHeap.evict(), "There is nothing to read it back from.");
    assertTrue(inHeap.isLoaded());

    // Once a store has written it, it can be dropped and read back.
    store.put("a", JsonUtils.toJson(inHeap.persisted()));
    inHeap.attach("a", this::read, cache);
    assertTrue(inHeap.evict());
    assertFalse(inHeap.isLoaded());
    assertEquals("a", inHeap.get().getLatestVersion());
  }

  @Test
  void anUnboundedCacheDropsNothing() {
    ObjectMetadataCache cache = ObjectMetadataCache.unbounded();
    ObjectMetadataRef a = ref("a", cache);
    a.get();
    for (int i = 0; i < 100; i++) {
      ref("k" + i, cache).get();
    }
    assertTrue(a.isLoaded());
    assertEquals(0, cache.size(), "An unbounded cache tracks nothing: it never has to choose.");
  }

  /**
   * A reference that was pinned and then abandoned, e.g. by a change that was rolled back and reloaded its bucket,
   * is dropped from the cache rather than kept: keeping it would grow the cache without bound and leave it unable to
   * evict anything.
   */
  @Test
  void abandonedPinnedReferencesDoNotFillTheCache() {
    ObjectMetadataCache cache = ObjectMetadataCache.bounded(2);
    for (int i = 0; i < 50; i++) {
      ObjectMetadataRef abandoned = ref("abandoned" + i, cache);
      abandoned.get();
      abandoned.pin();
    }
    assertTrue(cache.size() <= 2, "The cache holds " + cache.size() + " entries.");
  }

  private ObjectMetadataRef ref(String key, ObjectMetadataCache cache) {
    store.put(key, JsonUtils.toJson(objectMetadata(key)));
    return ObjectMetadataRef.lazy(key, this::read, cache);
  }

  private String read(String key) {
    reads.incrementAndGet();
    return store.get(key);
  }

  private static ObjectMetadata objectMetadata(String versionId) {
    VersionedObjectMetadata version = new VersionedObjectMetadata();
    version.setEtag("etag-" + versionId);
    version.setSize(1L);
    return new ObjectMetadata(versionId, version);
  }

}
