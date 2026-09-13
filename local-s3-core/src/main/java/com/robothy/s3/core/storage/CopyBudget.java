package com.robothy.s3.core.storage;

/**
 * A budget of the Java heap that {@linkplain CopyOnAccessStorage}s may fill with copies of the objects of their base
 * storages, shared by several of them.
 */
public interface CopyBudget {

  /**
   * A budget without a limit.
   */
  CopyBudget UNLIMITED = new CopyBudget() {
    @Override
    public boolean reserve(CopyOnAccessStorage requester, long bytes) {
      return true;
    }

    @Override
    public void release(long bytes) {
    }
  };

  /**
   * Reserve the bytes of a copy that a storage is about to make. The budget may make room by
   * {@linkplain CopyOnAccessStorage#dropCopies() dropping the copies} of other storages, but never those of the
   * requester. It is called without a lock of the requester held, other than the one of the object it copies.
   *
   * @param requester the storage that makes the copy.
   * @param bytes the size of the copy.
   * @return {@code true} if the bytes are reserved, and the copy may be made; {@code false} if the budget has no
   *     room for them, in which case the object is read from the base storage instead.
   */
  boolean reserve(CopyOnAccessStorage requester, long bytes);

  /**
   * Release bytes that were reserved, once the copy that took them is dropped, or wasn't made after all.
   *
   * @param bytes the number of bytes to release.
   */
  void release(long bytes);

}
