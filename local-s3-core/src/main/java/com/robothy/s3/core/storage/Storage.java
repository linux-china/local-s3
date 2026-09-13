package com.robothy.s3.core.storage;

import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.core.util.RangeUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Key-Value based storage abstraction.
 *
 * <p>An object that doesn't exist is reported with an {@linkplain IllegalArgumentException}. Every other
 * failure to read or write an object is reported with an {@linkplain java.io.UncheckedIOException}, whose
 * cause is the {@linkplain java.io.IOException} of the file system, so that a caller can tell a failing
 * storage from a request that asks for something that isn't there.
 */
public interface Storage {

  /**
   * Create an {@linkplain InMemoryStorage} without total size limitation.
   *
   * @return a {@linkplain Storage} instance.
   */
  static Storage createInMemory() {
    return new InMemoryStorage();
  }

  /**
   * Create an {@linkplain InMemoryStorage} instance with max total size limit.
   *
   * @param maxTotalSize max total size.
   * @return a {@linkplain Storage} instance.
   */
  static Storage createInMemory(int maxTotalSize) {
    return new InMemoryStorage(maxTotalSize);
  }

  /**
   * Create a persistent storage with a specified path. The path
   * will be created if not exists.
   *
   * @param path where data stores in.
   * @return a {@linkplain Storage} instance.
   */
  static Storage createPersistent(Path path) {
    return new LocalFileSystemStorage(path);
  }

  /**
   * Create a {@linkplain LayeredStorage} instance.
   *
   * @param frontend the fronted storage of the created instance.
   * @param backend the backend storage of the created instance.
   * @return a new {@linkplain LayeredStorage} instance.
   */
  static Storage createLayered(Storage frontend, Storage backend) {
    return new LayeredStorage(frontend, backend);
  }

  /**
   * Create a {@linkplain CopyOnAccessStorage} instance.
   *
   * @param base the base storage of the {@linkplain CopyOnAccessStorage}.
   * @return a {@linkplain CopyOnAccessStorage} instance.
   */
  static Storage createCopyOnAccess(Storage base) {
    return new CopyOnAccessStorage(base);
  }

  /**
   * Create a {@linkplain CopyOnAccessStorage} instance whose copies take heap within a budget.
   *
   * @param base the base storage of the {@linkplain CopyOnAccessStorage}.
   * @param budget the budget that the copies take heap within, which may be shared by several storages.
   * @return a {@linkplain CopyOnAccessStorage} instance.
   */
  static CopyOnAccessStorage createCopyOnAccess(Storage base, CopyBudget budget) {
    return new CopyOnAccessStorage(base, budget);
  }

  /**
   * Put binary data to the storage.
   *
   * @param data data.
   * @return object id.
   */
  default Long put(byte[] data) {
    return put(IdUtils.defaultGenerator().nextId(), data);
  }

  /**
   * Put data to the storage.
   *
   * @param id the file ID. Override data if the ID already exist.
   * @param data data.
   * @return the object ID.
   */
  Long put(Long id, byte[] data);

  /**
   * Put data from an {@linkplain InputStream} to the storage.
   *
   * @param data octet-stream.
   * @return the storage generated object ID.
   */
  default Long put(InputStream data) {
    return put(IdUtils.defaultGenerator().nextId(), data);
  }

  /**
   * Put octet-stream to the storage.
   */
  Long put(Long id, InputStream data);

  /**
   * Put the content of a file to the storage, with a generated ID.
   *
   * @param file a file that holds exactly the content of the object.
   * @return the storage generated object ID.
   * @see #put(Long, Path)
   */
  default Long put(Path file) {
    return put(IdUtils.defaultGenerator().nextId(), file);
  }

  /**
   * Put the content of a file to the storage. The storage may take the file over instead of copying it, e.g. a
   * storage on the same file system renames it, so that storing a large object doesn't write its content a second
   * time. The caller must not rely on the file afterwards: it may be gone, and if it still exists the caller
   * deletes it.
   *
   * <p>The default implementation copies the content of the file.
   *
   * @param id the object ID.
   * @param file a file that holds exactly the content of the object.
   * @return the object ID.
   */
  default Long put(Long id, Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return put(id, in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open " + file + " to store object " + id + ".", e);
    }
  }

  /**
   * The number of bytes of an object.
   *
   * <p>The default implementation reads the whole object; storages that know the size override it.
   *
   * @param id the object ID.
   * @return the size of the object in bytes.
   */
  default long size(Long id) {
    try (InputStream in = getInputStream(id)) {
      return in.transferTo(OutputStream.nullOutputStream());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read object " + id + ".", e);
    }
  }

  /**
   * Get all bytes of the object by ID.
   *
   * @param id the object ID.
   * @return fetched data.
   */
  byte[] getBytes(Long id);

  /**
   * Get the {@linkplain InputStream} of the object.
   *
   * @param id the object ID.
   * @return octet-stream.
   */
  InputStream getInputStream(Long id);

  /**
   * Get a bounded region of the object. Storage implementations that support seeking should override
   * this method so that the requested position can be selected without skipping through the stream.
   *
   * @param id the object ID.
   * @param position the first byte to read.
   * @param length the number of bytes to read.
   * @return a stream of the requested region.
   */
  default InputStream getInputStream(Long id, long position, long length) {
    return RangeUtils.applyRange(getInputStream(id), position, length);
  }

  /**
   * Delete an object by ID.
   *
   * @param id the object ID.
   * @return deleted Object ID.
   */
  Long delete(Long id);

  /**
   * Is the object with specified ID exists.
   *
   * @param id object ID.
   * @return {@code true} if the ID exists; otherwise {@code false}.
   */
  boolean isExist(Long id);

}
