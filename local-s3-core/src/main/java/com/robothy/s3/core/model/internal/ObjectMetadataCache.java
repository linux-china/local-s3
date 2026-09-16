package com.robothy.s3.core.model.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds how much object metadata a LocalS3 service that reads its buckets from a store keeps in heap.
 *
 * <p>The buckets of such a service hold an {@linkplain ObjectMetadataRef} per key, which reads the metadata of its key
 * when something needs it. This keeps the references whose metadata was read most recently, and evicts the others, so
 * that walking a bucket of a million objects costs the objects it walks rather than all of them. A reference that a
 * change has taken hold of is pinned until the change is written, and is kept whatever its age.
 *
 * <p>One cache serves every bucket of a service, so the bound is on the service rather than on each of its buckets: a
 * service with one large bucket gets the same heap as one with a thousand small ones.
 */
public final class ObjectMetadataCache {

  private static final Logger log = LoggerFactory.getLogger(ObjectMetadataCache.class);

  /**
   * The number of objects whose metadata is kept in heap by default.
   */
  public static final int DEFAULT_MAX_ENTRIES = 50_000;

  /**
   * Environment variable, or system property, that configures {@linkplain #maxEntries()}.
   */
  public static final String MAX_ENTRIES_VARIABLE = "LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES";

  /**
   * A cache that keeps everything, for the services that hold their objects in memory anyway.
   */
  public static ObjectMetadataCache unbounded() {
    return new ObjectMetadataCache(Integer.MAX_VALUE);
  }

  /**
   * A cache bounded by {@value #MAX_ENTRIES_VARIABLE}, or by {@linkplain #DEFAULT_MAX_ENTRIES}.
   *
   * @return a new cache.
   */
  public static ObjectMetadataCache bounded() {
    return new ObjectMetadataCache(maxEntriesVariable());
  }

  /**
   * A cache of a given size, for tests.
   *
   * @param maxEntries the number of objects whose metadata is kept in heap, positive.
   * @return a new cache.
   */
  public static ObjectMetadataCache bounded(int maxEntries) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be positive.");
    }
    return new ObjectMetadataCache(maxEntries);
  }

  /**
   * Guards {@linkplain #entries}. Held only while entries are recorded or chosen to be evicted, never while metadata
   * is read from a store.
   */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * The references whose metadata is in heap, ordered by access, so that the least recently used one comes first.
   * Identity based, since a reference stands for one key of one bucket and defines no {@code equals}.
   */
  private final LinkedHashMap<ObjectMetadataRef, Boolean> entries = new LinkedHashMap<>(16, 0.75f, true);

  private final int maxEntries;

  private ObjectMetadataCache(int maxEntries) {
    this.maxEntries = maxEntries;
  }

  /**
   * The number of objects whose metadata is kept in heap.
   *
   * @return the bound; {@linkplain Integer#MAX_VALUE} if the cache keeps everything.
   */
  public int maxEntries() {
    return maxEntries;
  }

  /**
   * The number of references whose metadata is in heap.
   *
   * @return the number of cached references.
   */
  public int size() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Record that the metadata of a reference was read, and evict the references that have not been read for longest
   * until the cache is within its bound. A reference that is pinned is kept and stops being a candidate.
   *
   * @param ref the reference whose metadata is in heap.
   */
  public void recordRead(ObjectMetadataRef ref) {
    if (maxEntries == Integer.MAX_VALUE) {
      return;
    }
    List<ObjectMetadataRef> candidates;
    lock.lock();
    try {
      entries.put(ref, Boolean.TRUE);
      int excess = entries.size() - maxEntries;
      if (excess <= 0) {
        return;
      }
      candidates = new ArrayList<>(excess);
      for (Map.Entry<ObjectMetadataRef, Boolean> entry : entries.entrySet()) {
        if (candidates.size() == excess) {
          break;
        }
        candidates.add(entry.getKey());
      }
      // Dropped from the map here, so that the lock is held for the map alone and never while a reference is evicted.
      candidates.forEach(entries::remove);
    } finally {
      lock.unlock();
    }

    // A pinned reference keeps its metadata and is simply no longer tracked: it is tracked again the next time it is
    // read. Keeping it here instead would let the references of a change that was rolled back, which stay pinned and
    // are never read again, grow the map without bound and leave it unable to evict anything.
    candidates.forEach(ObjectMetadataRef::evict);
  }

  /**
   * Stop tracking every reference, e.g. when the data of a service is replaced.
   */
  public void clear() {
    lock.lock();
    try {
      entries.clear();
    } finally {
      lock.unlock();
    }
  }

  private static int maxEntriesVariable() {
    String value = System.getProperty(MAX_ENTRIES_VARIABLE, System.getenv(MAX_ENTRIES_VARIABLE));
    if (value == null || value.isBlank()) {
      return DEFAULT_MAX_ENTRIES;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // Reported below.
    }
    log.warn("Ignoring {}={}: expected a positive integer.", MAX_ENTRIES_VARIABLE, value);
    return DEFAULT_MAX_ENTRIES;
  }

}
