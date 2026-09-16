package com.robothy.s3.core.service.manager;

import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.storage.CopyBudget;
import com.robothy.s3.core.storage.CopyOnAccessStorage;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.util.TokenBuffer;

/**
 * The initial data of the data paths that {@code IN_MEMORY} managers start from: the loaded metadata of a path, and a
 * {@linkplain CopyOnAccessStorage} that keeps copies of the objects read from it, so that the tests that start from the
 * same path neither load its metadata nor read its objects from the disk again.
 *
 * <p>The cache is bounded twice. It keeps at most {@linkplain #getMaxEntries() a number of data paths}, dropping the
 * least recently used one beyond it. And the copies of the objects of all paths take at most
 * {@linkplain #getMaxBytes() a number of bytes} of heap: a copy that would exceed it first drops the least recently
 * used other paths, and if there is still no room for it, the object is read from the disk instead of copied. A dropped
 * path is loaded again when a manager is created for it; the managers that already use it keep working, and copy its
 * objects again as far as the budget allows.
 */
@Slf4j
final class InitialDataCache implements CopyBudget {

  /**
   * The default max number of data paths.
   */
  static final int DEFAULT_MAX_ENTRIES = 1024;

  /**
   * The default max number of bytes of the copies of objects: a quarter of the max heap.
   */
  static final long DEFAULT_MAX_BYTES = 128 * 1024 * 1024; // Runtime.getRuntime().maxMemory() / 4;

  /**
   * Environment variable, or system property, that configures the max number of data paths.
   */
  static final String MAX_ENTRIES_VARIABLE = "LOCAL_S3_INITIAL_DATA_CACHE_MAX_ENTRIES";

  /**
   * Environment variable, or system property, that configures the max number of bytes of the copies of objects, with
   * an optional {@code k}, {@code m} or {@code g} suffix, e.g. {@code 512m}.
   */
  static final String MAX_BYTES_VARIABLE = "LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES";

  /**
   * Guards {@linkplain #entries} and the limits. Held while an entry is looked up or chosen to be dropped, but neither
   * while a data path is loaded nor while copies are dropped, so that loading one path doesn't hold up the copies of
   * another, and dropping copies can't wait for a lock that a copying thread holds.
   */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * The entries by data path, ordered by access, so that the least recently used one comes first.
   */
  private final LinkedHashMap<String, CompletableFuture<CacheValue>> entries = new LinkedHashMap<>(16, 0.75f, true);

  private final AtomicLong usedBytes = new AtomicLong();

  /**
   * Written with the lock held; read without it by {@linkplain #tryReserve}.
   */
  private volatile int maxEntries;

  private volatile long maxBytes;

  InitialDataCache() {
    this(intVariable(MAX_ENTRIES_VARIABLE, DEFAULT_MAX_ENTRIES), bytesVariable(MAX_BYTES_VARIABLE, DEFAULT_MAX_BYTES));
  }

  InitialDataCache(int maxEntries) {
    this(maxEntries, DEFAULT_MAX_BYTES);
  }

  InitialDataCache(int maxEntries, long maxBytes) {
    checkLimits(maxEntries, maxBytes);
    this.maxEntries = maxEntries;
    this.maxBytes = maxBytes;
  }

  /**
   * Change the limits, dropping the least recently used data paths beyond them.
   *
   * @param maxEntries the max number of data paths, positive.
   * @param maxBytes the max number of bytes of the copies of objects, not negative; {@code 0} copies nothing.
   */
  void setLimits(int maxEntries, long maxBytes) {
    checkLimits(maxEntries, maxBytes);
    List<CompletableFuture<CacheValue>> dropped;
    lock.lock();
    try {
      this.maxEntries = maxEntries;
      this.maxBytes = maxBytes;
      dropped = removeBeyondMaxEntries();
      dropped.addAll(removeLeastRecentlyUsed(null, 0));
    } finally {
      lock.unlock();
    }
    dropCopies(dropped);
  }

  int getMaxEntries() {
    return maxEntries;
  }

  long getMaxBytes() {
    return maxBytes;
  }

  /**
   * The number of bytes that the copies of objects take.
   */
  long usedBytes() {
    return usedBytes.get();
  }

  /**
   * Get the cached value of {@code key}, loading it if absent. Concurrent callers with the same key load the value
   * only once and share it. The value is loaded without the lock of the cache held.
   *
   * @param key the cache key.
   * @param loader loads the value of an absent key; the storage of the value takes this cache as its budget.
   * @return the cached value.
   */
  CacheValue computeIfAbsent(String key, Function<String, CacheValue> loader) {
    CompletableFuture<CacheValue> future;
    CompletableFuture<CacheValue> loading = null;
    List<CompletableFuture<CacheValue>> dropped;
    lock.lock();
    try {
      future = entries.get(key);
      if (future == null) {
        loading = new CompletableFuture<>();
        future = loading;
        entries.put(key, loading);
      }
      dropped = removeBeyondMaxEntries();
    } finally {
      lock.unlock();
    }
    dropCopies(dropped);

    if (loading != null) {
      try {
        loading.complete(loader.apply(key));
      } catch (RuntimeException | Error e) {
        lock.lock();
        try {
          entries.remove(key, loading);
        } finally {
          lock.unlock();
        }
        loading.completeExceptionally(e);
        throw e;
      }
    }

    try {
      return future.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException cause) {
        throw cause;
      }
      throw e;
    }
  }

  /**
   * Drop the cached data of every path, releasing the heap it holds.
   */
  void clear() {
    List<CompletableFuture<CacheValue>> dropped;
    lock.lock();
    try {
      dropped = new ArrayList<>(entries.values());
      entries.clear();
    } finally {
      lock.unlock();
    }
    dropCopies(dropped);
  }

  /**
   * The number of data paths currently cached.
   */
  int size() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean reserve(CopyOnAccessStorage requester, long bytes) {
    if (tryReserve(bytes)) {
      return true;
    }
    List<CompletableFuture<CacheValue>> dropped;
    lock.lock();
    try {
      if (bytes > maxBytes) {
        return false;
      }
      dropped = removeLeastRecentlyUsed(requester, bytes);
    } finally {
      lock.unlock();
    }
    dropCopies(dropped);
    return tryReserve(bytes);
  }

  @Override
  public void release(long bytes) {
    usedBytes.addAndGet(-bytes);
  }

  private boolean tryReserve(long bytes) {
    long limit = maxBytes;
    long current;
    do {
      current = usedBytes.get();
      if (current + bytes > limit) {
        return false;
      }
    } while (!usedBytes.compareAndSet(current, current + bytes));
    return true;
  }

  /**
   * Remove the least recently used entries beyond the max number of entries. Called with the lock held.
   */
  private List<CompletableFuture<CacheValue>> removeBeyondMaxEntries() {
    List<CompletableFuture<CacheValue>> removed = new ArrayList<>();
    Iterator<CompletableFuture<CacheValue>> iterator = entries.values().iterator();
    while (entries.size() - removed.size() > maxEntries && iterator.hasNext()) {
      removed.add(iterator.next());
      iterator.remove();
    }
    return removed;
  }

  /**
   * Remove the least recently used entries with copies, other than the requester's, until the copies that remain
   * leave room for {@code bytes}. Called with the lock held; the copies of the removed entries are dropped once it is
   * released.
   */
  private List<CompletableFuture<CacheValue>> removeLeastRecentlyUsed(CopyOnAccessStorage requester, long bytes) {
    List<CompletableFuture<CacheValue>> removed = new ArrayList<>();
    long freed = 0;
    Iterator<CompletableFuture<CacheValue>> iterator = entries.values().iterator();
    while (usedBytes.get() - freed + bytes > maxBytes && iterator.hasNext()) {
      CompletableFuture<CacheValue> entry = iterator.next();
      CacheValue value = entry.getNow(null);
      if (value == null || value.storage == requester || value.storage.copiedBytes() == 0) {
        continue;
      }
      freed += value.storage.copiedBytes();
      removed.add(entry);
      iterator.remove();
    }
    return removed;
  }

  private static void dropCopies(List<CompletableFuture<CacheValue>> dropped) {
    for (CompletableFuture<CacheValue> entry : dropped) {
      CacheValue value = entry.getNow(null);
      if (value != null) {
        value.storage.dropCopies();
      } else {
        // Still loading; drop the copies that the storage makes before the load completes, if any.
        entry.thenAccept(loaded -> loaded.storage.dropCopies());
      }
    }
  }

  private static void checkLimits(int maxEntries, long maxBytes) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be positive.");
    }
    if (maxBytes < 0) {
      throw new IllegalArgumentException("maxBytes must not be negative.");
    }
  }

  private static String variable(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? System.getProperty(name) : value;
  }

  static int intVariable(String name, int defaultValue) {
    String value = variable(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // Reported below.
    }
    log.warn("Ignoring {}={}: expected a positive integer.", name, value);
    return defaultValue;
  }

  static long bytesVariable(String name, long defaultValue) {
    String value = variable(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return parseBytes(value);
    } catch (IllegalArgumentException e) {
      log.warn("Ignoring {}={}: {}", name, value, e.getMessage());
      return defaultValue;
    }
  }

  /**
   * Parse a number of bytes with an optional {@code k}, {@code m} or {@code g} suffix, e.g. {@code 512m}.
   */
  static long parseBytes(String value) {
    String trimmed = value.trim().toLowerCase();
    long multiplier = 1;
    if (!trimmed.isEmpty()) {
      switch (trimmed.charAt(trimmed.length() - 1)) {
        case 'k' -> multiplier = 1024L;
        case 'm' -> multiplier = 1024L * 1024;
        case 'g' -> multiplier = 1024L * 1024 * 1024;
        default -> multiplier = 1;
      }
      if (multiplier != 1) {
        trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
      }
    }
    try {
      long parsed = Long.parseLong(trimmed);
      if (parsed < 0) {
        throw new IllegalArgumentException("expected a number of bytes that is not negative.");
      }
      return Math.multiplyExact(parsed, multiplier);
    } catch (NumberFormatException | ArithmeticException e) {
      throw new IllegalArgumentException("expected a number of bytes, e.g. 536870912 or 512m.");
    }
  }

  /**
   * The initial data of a data path.
   */
  static final class CacheValue {

    /**
     * The loaded metadata, as JSON tokens that every manager reads its own copy from.
     */
    private final TokenBuffer metadata;

    /**
     * The copies of the objects of the data path, over the storage of the path.
     */
    private final CopyOnAccessStorage storage;

    private final ReentrantLock metadataLock = new ReentrantLock();

    CacheValue(LocalS3Metadata metadata, CopyOnAccessStorage storage) {
      this.metadata = JsonUtils.toTokens(Objects.requireNonNull(metadata));
      this.storage = Objects.requireNonNull(storage);
    }

    /**
     * Create a {@code LayeredStorage} with the cached storage as backend and a new {@code InMemoryStorage} as
     * frontend.
     *
     * @return a {@code LayeredStorage} to make sure the real data won't be polluted.
     */
    Storage storage() {
      return Storage.createLayered(Storage.createInMemory(), storage);
    }

    /**
     * Create a copy of the cached metadata, which the manager that it is created for changes on its own.
     *
     * @return a copy of the metadata.
     */
    LocalS3Metadata metadata() {
      metadataLock.lock();
      try {
        return JsonUtils.fromTokens(metadata, LocalS3Metadata.class);
      } finally {
        metadataLock.unlock();
      }
    }

    /**
     * The storage that keeps the copies of the objects of the data path.
     */
    CopyOnAccessStorage copies() {
      return storage;
    }
  }

}
