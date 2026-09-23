package com.robothy.s3.core.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@linkplain Storage} whose changes can be grouped into a transaction, so that the stored objects
 * stay consistent with the persisted metadata that references them.
 *
 * <p>Within a transaction started by {@link #begin()} on the current thread, deleting an object only
 * records the deletion. {@link #commit()} performs the recorded deletions; {@link #rollback()}
 * discards them and deletes the objects written during the transaction instead. Outside a
 * transaction, this storage behaves like the underlying one.
 *
 * <p>Committing after the metadata is persisted means that the persisted metadata never references a
 * deleted object, even if the process dies in between; at worst, an unreferenced object is left behind.
 */
@Slf4j
public final class TransactionalStorage implements Storage, StorageTransactions {

  private final Storage delegate;

  private final ThreadLocal<Transaction> transaction = new ThreadLocal<>();

  /**
   * Create a {@linkplain TransactionalStorage} on top of {@code delegate}.
   *
   * @param delegate the storage that holds the objects.
   */
  public TransactionalStorage(Storage delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  /**
   * Start a transaction on the current thread, unless one is already active.
   *
   * @return {@code true} if a transaction was started; {@code false} if the current thread already has
   *     one, which its owner commits or rolls back.
   */
  @Override
  public boolean begin() {
    if (transaction.get() != null) {
      return false;
    }
    transaction.set(new Transaction());
    return true;
  }

  /**
   * End the transaction of the current thread and delete the objects deleted within it.
   */
  @Override
  public void commit() {
    end().deleted.forEach(this::deleteQuietly);
  }

  /**
   * End the transaction of the current thread, keep the objects deleted within it, and delete the
   * objects written within it.
   */
  @Override
  public void rollback() {
    end().written.forEach(this::deleteQuietly);
  }

  @Override
  public void abandon() {
    end();
  }

  @Override
  public Long put(Long id, byte[] data) {
    recordWrite(id);
    return delegate.put(id, data);
  }

  @Override
  public Long put(Long id, InputStream data) {
    recordWrite(id);
    return delegate.put(id, data);
  }

  @Override
  public Long put(Long id, Path file) {
    recordWrite(id);
    return delegate.put(id, file);
  }

  @Override
  public long size(Long id) {
    return delegate.size(id);
  }

  @Override
  public byte[] getBytes(Long id) {
    return delegate.getBytes(id);
  }

  @Override
  public InputStream getInputStream(Long id) {
    return delegate.getInputStream(id);
  }

  @Override
  public InputStream getInputStream(Long id, long position, long length) {
    return delegate.getInputStream(id, position, length);
  }

  /**
   * Retain the content in the underlying storage, which performs the deletions of a transaction when it is
   * committed: a deletion of retained content then waits for the last reader of it, like any other one.
   */
  @Override
  public Optional<ContentRetention> retain(Collection<Long> ids) {
    return delegate.retain(ids);
  }

  @Override
  public Long delete(Long id) {
    Transaction current = transaction.get();
    if (current == null) {
      return delegate.delete(id);
    }
    if (!delegate.isExist(id)) {
      throw new IllegalArgumentException("Object id='" + id + "' not exist.");
    }
    current.deleted.add(id);
    return id;
  }

  @Override
  public boolean isExist(Long id) {
    return delegate.isExist(id);
  }

  private void recordWrite(Long id) {
    Transaction current = transaction.get();
    // Overwriting an existing object can't be undone, so only new objects are deleted on rollback.
    if (current != null && !delegate.isExist(id)) {
      current.written.add(id);
    }
  }

  private Transaction end() {
    Transaction current = transaction.get();
    if (current == null) {
      throw new IllegalStateException("No active transaction on the current thread.");
    }
    transaction.remove();
    return current;
  }

  private void deleteQuietly(Long id) {
    try {
      if (delegate.isExist(id)) {
        delegate.delete(id);
      }
    } catch (RuntimeException e) {
      log.warn("Failed to delete object {}; it is left as an unreferenced object.", id, e);
    }
  }

  private static final class Transaction {

    private final Set<Long> deleted = new LinkedHashSet<>();

    private final Set<Long> written = new LinkedHashSet<>();

  }

}
