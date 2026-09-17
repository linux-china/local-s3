package com.robothy.s3.core.model.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ObjectMetadataRefTest {

  private static final String JSON = JsonUtils.toJson(objectMetadata());

  /**
   * The store is read without holding a lock of the reference, which would pin the carrier thread of a virtual thread
   * on JDK 21 while the store reads the disk: two readers of a cold key are both inside the store at once.
   */
  @Test
  void readsTheStoreWithoutHoldingALock() throws Exception {
    CountDownLatch bothReading = new CountDownLatch(2);
    Function<String, String> source = key -> {
      bothReading.countDown();
      try {
        if (!bothReading.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("The other reader never got to the store.");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return JSON;
    };
    ObjectMetadataRef ref = ObjectMetadataRef.lazy("a", source, ObjectMetadataCache.unbounded());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ObjectMetadata> first = executor.submit(ref::get);
      Future<ObjectMetadata> second = executor.submit(ref::get);
      assertSame(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS),
          "Readers that race for a key answer one instance.");
    }
  }

  @Test
  void concurrentReadersOfAColdKeyGetOneInstance() throws Exception {
    ObjectMetadataRef ref = ObjectMetadataRef.lazy("a", key -> JSON, ObjectMetadataCache.unbounded());
    List<Future<ObjectMetadata>> results = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 100; i++) {
        results.add(executor.submit(ref::get));
      }
    }
    ObjectMetadata expected = results.get(0).get();
    for (Future<ObjectMetadata> result : results) {
      assertSame(expected, result.get());
    }
  }

  @Test
  void evictDropsTheMetadataUnlessPinned() {
    ObjectMetadataRef ref = ObjectMetadataRef.lazy("a", key -> JSON, ObjectMetadataCache.unbounded());
    assertFalse(ref.evict(), "Nothing to drop before it is read.");

    ObjectMetadata loaded = ref.get();
    ref.pin();
    assertFalse(ref.evict());
    assertSame(loaded, ref.get());

    ref.unpin();
    assertTrue(ref.evict());
    assertFalse(ref.isLoaded());
    assertEquals("v1", ref.get().getLatestVersion());
  }

  @Test
  void aReferenceOnlyInHeapIsNeverEvicted() {
    ObjectMetadataRef ref = ObjectMetadataRef.of(objectMetadata());
    assertFalse(ref.evict());
    assertTrue(ref.isLoaded());
  }

  private static ObjectMetadata objectMetadata() {
    VersionedObjectMetadata version = new VersionedObjectMetadata();
    version.setEtag("etag");
    version.setSize(1L);
    return new ObjectMetadata("v1", version);
  }

}
