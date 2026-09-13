package com.robothy.s3.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * A {@linkplain  CopyOnAccessStorage} contains a base storage. When access
 * to this storage, it creates a copy of the accessed object in memory. Update
 * on this storage won't affect the base one.
 *
 */
class CopyOnAccessStorage implements Storage {

  private final Storage base;

  private final Storage real;

  /**
   * Create a {@linkplain CopyOnAccessStorage} instance with a base storage.
   *
   * @param base the base storage is where objects copy from.
   */
  CopyOnAccessStorage(Storage base) {
    Objects.requireNonNull(base);
    this.base = base;
    this.real = Storage.createInMemory();
  }

  @Override
  public Long put(Long id, byte[] data) {
    return this.real.put(id, data);
  }

  @Override
  public Long put(Long id, InputStream data) {
    return this.real.put(id, data);
  }

  @Override
  public byte[] getBytes(Long id) {
    if (this.real.isExist(id)) {
      return this.real.getBytes(id);
    }

    byte[] data = this.base.getBytes(id);
    this.real.put(id, Arrays.copyOf(data, data.length));
    return this.real.getBytes(id);
  }

  @Override
  public Long put(Long id, Path file) {
    return this.real.put(id, file);
  }

  @Override
  public InputStream getInputStream(Long id) {
    copyIfAbsent(id);
    return this.real.getInputStream(id);
  }

  @Override
  public InputStream getInputStream(Long id, long position, long length) {
    copyIfAbsent(id);
    return this.real.getInputStream(id, position, length);
  }

  @Override
  public long size(Long id) {
    return this.real.isExist(id) ? this.real.size(id) : this.base.size(id);
  }

  private void copyIfAbsent(Long id) {
    if (this.real.isExist(id)) {
      return;
    }
    try (InputStream data = this.base.getInputStream(id)) {
      this.real.put(id, data);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to copy object " + id + ".", e);
    }
  }


  @Override
  public Long delete(Long id) {
    if (this.real.isExist(id)) {
      return this.real.delete(id);
    }

    if (!this.base.isExist(id)) {
      this.base.delete(id); // trigger exception.
    }

    return id;
  }

  @Override
  public boolean isExist(Long id) {
    return this.real.isExist(id) || this.base.isExist(id);
  }
}
