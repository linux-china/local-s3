package com.robothy.s3.core.storage;

import com.robothy.s3.core.exception.TotalSizeExceedException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.SneakyThrows;

/**
 * {@linkplain Storage} implementation based on Java Heap.
 */
class InMemoryStorage implements Storage {

  private final Map<Long, byte[]> store = new ConcurrentHashMap<>();

  private final AtomicLong totalSize = new AtomicLong(0);

  private final AtomicLong maxTotalSize = new AtomicLong(Long.MAX_VALUE);

  /**
   * Create an {@linkplain InMemoryStorage} instance with total size limitation.
   *
   * @param maxTotalSize max total size.
   */
  InMemoryStorage(long maxTotalSize) {
    this.maxTotalSize.set(maxTotalSize);
  }

  /**
   * Create an {@linkplain InMemoryStorage} instance without total size limitation.
   */
  InMemoryStorage() {

  }

  @Override
  public Long put(Long id, byte[] data) {
    // Copy the data, since the caller may change the array afterwards.
    return putData(id, Arrays.copyOf(data, data.length));
  }

  @Override
  @SneakyThrows
  public Long put(Long id, InputStream data) {
    // The array returned by readAllBytes() isn't referenced by anyone else.
    return putData(id, data.readAllBytes());
  }

  private Long putData(Long id, byte[] data) {
    ensureNotExceedTotalSize(data.length);
    byte[] previous = store.put(id, data);
    totalSize.addAndGet(data.length - (previous == null ? 0 : previous.length));
    return id;
  }

  @Override
  public byte[] getBytes(Long id) {
    byte[] data = getData(id);
    // Copy the data, since the caller may change the returned array.
    return Arrays.copyOf(data, data.length);
  }

  @Override
  public InputStream getInputStream(Long id) {
    // A ByteArrayInputStream never changes its array, so the stored data needn't be copied.
    return new ByteArrayInputStream(getData(id));
  }

  @Override
  public Long delete(Long id) {
    byte[] removed = store.remove(id);
    if (removed == null) {
      throw notExist(id);
    }
    totalSize.addAndGet(-removed.length);
    return id;
  }

  @Override
  public boolean isExist(Long id) {
    return store.containsKey(id);
  }

  private byte[] getData(Long id) {
    byte[] data = store.get(id);
    if (data == null) {
      throw notExist(id);
    }
    return data;
  }

  private static IllegalArgumentException notExist(Long id) {
    return new IllegalArgumentException("Object id='" + id + "' not exists.");
  }

  private void ensureNotExceedTotalSize(int incrementalSize) {
    if (totalSize.get() + incrementalSize > maxTotalSize.get()) {
      throw new TotalSizeExceedException(maxTotalSize.get(), totalSize.get() + incrementalSize);
    }
  }

}
