package com.robothy.s3.core.storage;

/**
 * A storage whose changes can be grouped into a transaction of the current thread, so that the stored data stays
 * consistent with the persisted metadata that references it. Within a transaction, deleting data only records the
 * deletion, which {@link #commit()} performs once the metadata is persisted.
 *
 * @see TransactionalStorage
 * @see com.robothy.s3.core.storage.s3vectors.TransactionalVectorStorage
 */
public interface StorageTransactions {

  /**
   * Start a transaction on the current thread, unless one is already active.
   *
   * @return {@code true} if a transaction was started; {@code false} if the current thread already has one, which its
   *     owner commits or rolls back.
   */
  boolean begin();

  /**
   * End the transaction of the current thread and delete the data deleted within it.
   */
  void commit();

  /**
   * End the transaction of the current thread, keep the data deleted within it, and delete the data written within it.
   */
  void rollback();

}
