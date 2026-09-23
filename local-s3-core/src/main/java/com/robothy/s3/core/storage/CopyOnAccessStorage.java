package com.robothy.s3.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A {@linkplain  CopyOnAccessStorage} contains a base storage. When an object of the base storage is accessed, a copy
 * of it is made in memory, which later accesses read instead of the base storage. Updates on this storage don't affect
 * the base one.
 *
 * <p>The copies take heap within a {@linkplain CopyBudget}: an object that the budget has no room for is read from the
 * base storage instead, and the budget may {@linkplain #dropCopies() drop the copies} of the storage to make room for
 * the copies of another one. Objects put into this storage are not copies, and are never dropped.
 */
public final class CopyOnAccessStorage implements Storage {

  private static final int COPY_LOCK_STRIPES = 64;

  private final Storage base;

  private final Storage real;

  private final CopyBudget budget;

  /**
   * The objects of {@linkplain #real} that are copies of objects of the base storage, by ID, with the bytes that were
   * reserved for them.
   */
  private final Map<Long, Long> copies = new ConcurrentHashMap<>();

  /**
   * Serializes the copies of an object, so that concurrent accesses copy it once.
   */
  private final ReentrantLock[] copyLocks = new ReentrantLock[COPY_LOCK_STRIPES];

  /**
   * Guards {@linkplain #generation} and the transitions of {@linkplain #copies}. Held only briefly, never while the
   * content of an object is read or the budget is consulted.
   */
  private final ReentrantLock copiesLock = new ReentrantLock();

  /**
   * Incremented whenever the copies are dropped, so that a copy that was in progress meanwhile isn't kept.
   */
  private long generation;

  /**
   * Create a {@linkplain CopyOnAccessStorage} instance without a limit of the copies.
   *
   * @param base the base storage is where objects copy from.
   */
  CopyOnAccessStorage(Storage base) {
    this(base, CopyBudget.UNLIMITED);
  }

  /**
   * Create a {@linkplain CopyOnAccessStorage} instance.
   *
   * @param base the base storage is where objects copy from.
   * @param budget the budget that the copies take heap within.
   */
  CopyOnAccessStorage(Storage base, CopyBudget budget) {
    this.base = Objects.requireNonNull(base);
    this.budget = Objects.requireNonNull(budget);
    this.real = Storage.createInMemory();
    for (int i = 0; i < copyLocks.length; i++) {
      copyLocks[i] = new ReentrantLock();
    }
  }

  @Override
  public Long put(Long id, byte[] data) {
    Long stored = this.real.put(id, data);
    forgetCopy(id);
    return stored;
  }

  @Override
  public Long put(Long id, InputStream data) {
    Long stored = this.real.put(id, data);
    forgetCopy(id);
    return stored;
  }

  @Override
  public Long put(Long id, Path file) {
    Long stored = this.real.put(id, file);
    forgetCopy(id);
    return stored;
  }

  @Override
  public byte[] getBytes(Long id) {
    return readCopyOrBase(id, () -> real.getBytes(id), () -> base.getBytes(id));
  }

  @Override
  public InputStream getInputStream(Long id) {
    return readCopyOrBase(id, () -> real.getInputStream(id), () -> base.getInputStream(id));
  }

  @Override
  public InputStream getInputStream(Long id, long position, long length) {
    return readCopyOrBase(id, () -> real.getInputStream(id, position, length),
        () -> base.getInputStream(id, position, length));
  }

  @Override
  public long size(Long id) {
    return this.real.isExist(id) ? this.real.size(id) : this.base.size(id);
  }

  /**
   * Retain the content in the storage of the objects and copies of this one, which is the only one that objects are
   * deleted from. The content of the base storage stays readable anyway: a copy that is dropped while it is read is
   * read from the base storage again.
   */
  @Override
  public Optional<ContentRetention> retain(Collection<Long> ids) {
    return this.real.retain(ids);
  }

  @Override
  public Long delete(Long id) {
    if (this.real.isExist(id)) {
      Long deleted = this.real.delete(id);
      forgetCopy(id);
      return deleted;
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

  /**
   * Drop the copies of the objects of the base storage, releasing the heap they take. Later accesses copy the objects
   * again, as far as the budget has room for them. Streams that were opened on a copy keep reading it.
   */
  public void dropCopies() {
    copiesLock.lock();
    try {
      generation++;
      copies.forEach((id, bytes) -> {
        if (real.isExist(id)) {
          real.delete(id);
        }
        budget.release(bytes);
      });
      copies.clear();
    } finally {
      copiesLock.unlock();
    }
  }

  /**
   * The number of bytes that the copies of the objects of the base storage take.
   *
   * @return the bytes of the copies.
   */
  public long copiedBytes() {
    return copies.values().stream().mapToLong(Long::longValue).sum();
  }

  /**
   * Read the copy of an object, making it if the budget has room for it; read the base storage otherwise.
   */
  private <T> T readCopyOrBase(Long id, Supplier<T> readCopy, Supplier<T> readBase) {
    if (real.isExist(id) || copy(id)) {
      try {
        return readCopy.get();
      } catch (IllegalArgumentException e) {
        // The copy was dropped since; the base storage still has the object.
      }
    }
    return readBase.get();
  }

  /**
   * Copy an object of the base storage into memory.
   *
   * @return {@code true} if the copy exists; {@code false} if the budget had no room for it, or it was dropped while
   *     it was made.
   */
  private boolean copy(Long id) {
    ReentrantLock lock = copyLocks[Math.floorMod(Long.hashCode(id), copyLocks.length)];
    lock.lock();
    try {
      if (real.isExist(id)) {
        return true;
      }
      long bytes = base.size(id);
      if (!budget.reserve(this, bytes)) {
        return false;
      }

      long copyGeneration;
      copiesLock.lock();
      try {
        copyGeneration = generation;
      } finally {
        copiesLock.unlock();
      }
      try (InputStream data = base.getInputStream(id)) {
        real.put(id, data);
      } catch (IOException e) {
        budget.release(bytes);
        throw new UncheckedIOException("Failed to copy object " + id + ".", e);
      } catch (RuntimeException e) {
        budget.release(bytes);
        throw e;
      }

      copiesLock.lock();
      try {
        if (copyGeneration == generation) {
          copies.put(id, bytes);
          return true;
        }
        // The copies were dropped while this one was made; drop it too.
        real.delete(id);
        budget.release(bytes);
        return false;
      } finally {
        copiesLock.unlock();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * An object that was put or deleted is no longer a copy; release the bytes that were reserved for it.
   */
  private void forgetCopy(Long id) {
    copiesLock.lock();
    try {
      Long bytes = copies.remove(id);
      if (bytes != null) {
        budget.release(bytes);
      }
    } finally {
      copiesLock.unlock();
    }
  }

}
