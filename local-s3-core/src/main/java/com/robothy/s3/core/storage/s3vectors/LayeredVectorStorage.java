package com.robothy.s3.core.storage.s3vectors;

import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@linkplain VectorStorage} with a writable frontend over a read-only backend, e.g. an in-memory storage over the
 * vectors of the initial data of an {@code IN_MEMORY} service, so that the service changes its vectors without
 * changing the initial data.
 *
 * <p>New vectors are stored in the frontend. Vectors are read from the frontend, and then from the backend. Deleting a
 * vector of the backend only hides it from this storage.
 */
class LayeredVectorStorage implements VectorStorage {

  private final VectorStorage front;

  private final VectorStorage back;

  /**
   * The vectors of the backend that were deleted from this storage.
   */
  private final Set<Long> deletedFromBack = ConcurrentHashMap.newKeySet();

  /**
   * Create a {@linkplain LayeredVectorStorage}.
   *
   * @param front the storage that new vectors are stored in.
   * @param back  the storage that is only read.
   */
  LayeredVectorStorage(VectorStorage front, VectorStorage back) {
    this.front = Objects.requireNonNull(front);
    this.back = Objects.requireNonNull(back);
  }

  @Override
  public Long putVectorData(float[] vectorData) {
    return front.putVectorData(vectorData);
  }

  @Override
  public float[] getVectorData(Long storageId) {
    float[] vectorData = front.getVectorData(storageId);
    if (vectorData != null || isDeletedFromBack(storageId)) {
      return vectorData;
    }
    return back.getVectorData(storageId);
  }

  @Override
  public FloatBuffer getVectorDataView(Long storageId) {
    FloatBuffer vectorData = front.getVectorDataView(storageId);
    if (vectorData != null || isDeletedFromBack(storageId)) {
      return vectorData;
    }
    return back.getVectorDataView(storageId);
  }

  @Override
  public boolean deleteVectorData(Long storageId) {
    if (storageId == null) {
      return false;
    }
    if (front.deleteVectorData(storageId)) {
      return true;
    }
    return back.vectorDataExists(storageId) && deletedFromBack.add(storageId);
  }

  @Override
  public boolean vectorDataExists(Long storageId) {
    return front.vectorDataExists(storageId)
        || (!isDeletedFromBack(storageId) && back.vectorDataExists(storageId));
  }

  @Override
  public long getStoredVectorCount() {
    return front.getStoredVectorCount() + back.getStoredVectorCount() - deletedFromBack.size();
  }

  @Override
  public long getVectorDataSize(Long storageId) {
    long size = front.getVectorDataSize(storageId);
    if (size >= 0 || isDeletedFromBack(storageId)) {
      return size;
    }
    return back.getVectorDataSize(storageId);
  }

  private boolean isDeletedFromBack(Long storageId) {
    return storageId != null && deletedFromBack.contains(storageId);
  }

}
