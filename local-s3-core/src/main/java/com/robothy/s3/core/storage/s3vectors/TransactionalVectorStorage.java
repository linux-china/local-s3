package com.robothy.s3.core.storage.s3vectors;

import com.robothy.s3.core.storage.StorageTransactions;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@linkplain VectorStorage} whose deletions can be deferred to the end of a transaction, so that the vectors stay
 * consistent with the persisted metadata of the vector buckets that references them.
 *
 * <p>Within a transaction started by {@link #begin()} on the current thread, deleting a vector only records the
 * deletion. {@link #commit()} performs the recorded deletions; {@link #rollback()} discards them. Outside a
 * transaction, this storage behaves like the underlying one.
 *
 * <p>Committing after the metadata is persisted means that the persisted metadata never references a deleted vector,
 * even if the process dies in between; at worst, an unreferenced vector is left behind. Unlike
 * {@linkplain com.robothy.s3.core.storage.TransactionalStorage}, a rollback keeps the vectors written within the
 * transaction: the in-memory metadata of a vector bucket isn't reloaded after a failed change, so it may still
 * reference them.
 */
@Slf4j
public final class TransactionalVectorStorage implements VectorStorage, StorageTransactions {

  private final VectorStorage delegate;

  private final ThreadLocal<Set<Long>> deletions = new ThreadLocal<>();

  /**
   * Create a {@linkplain TransactionalVectorStorage} on top of {@code delegate}.
   *
   * @param delegate the storage that holds the vectors.
   */
  public TransactionalVectorStorage(VectorStorage delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public boolean begin() {
    if (deletions.get() != null) {
      return false;
    }
    deletions.set(new LinkedHashSet<>());
    return true;
  }

  @Override
  public void commit() {
    end().forEach(this::deleteQuietly);
  }

  @Override
  public void rollback() {
    end();
  }

  @Override
  public Long putVectorData(float[] vectorData) {
    return delegate.putVectorData(vectorData);
  }

  @Override
  public float[] getVectorData(Long storageId) {
    return delegate.getVectorData(storageId);
  }

  @Override
  public boolean deleteVectorData(Long storageId) {
    Set<Long> current = deletions.get();
    if (current == null) {
      return delegate.deleteVectorData(storageId);
    }
    if (!delegate.vectorDataExists(storageId)) {
      return false;
    }
    current.add(storageId);
    return true;
  }

  @Override
  public boolean vectorDataExists(Long storageId) {
    return delegate.vectorDataExists(storageId);
  }

  @Override
  public long getStoredVectorCount() {
    return delegate.getStoredVectorCount();
  }

  @Override
  public long getVectorDataSize(Long storageId) {
    return delegate.getVectorDataSize(storageId);
  }

  private Set<Long> end() {
    Set<Long> current = deletions.get();
    if (current == null) {
      throw new IllegalStateException("No active transaction on the current thread.");
    }
    deletions.remove();
    return current;
  }

  private void deleteQuietly(Long storageId) {
    try {
      delegate.deleteVectorData(storageId);
    } catch (RuntimeException e) {
      log.warn("Failed to delete vector {}; it is left as an unreferenced vector.", storageId, e);
    }
  }

}
