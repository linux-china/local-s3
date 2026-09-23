package com.robothy.s3.core.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * The deferred deletions of a {@linkplain Storage}: the content that a reader {@linkplain
 * Storage#retain(Collection) retains}, and the deletions of retained content that wait for the last reader of it.
 *
 * <p>A storage deletes content through {@linkplain #defer(Long)}: while the content is retained, the deletion is only
 * recorded, and performed when the last retention of it is released. So an object that is read part by part keeps
 * every part it still has to open, without holding them open, and an overwrite or a deletion of it doesn't cut the
 * content that is being read short.
 *
 * <p>A deferred deletion that is never released, e.g. because the process dies while a read is in progress, leaves
 * content that the metadata doesn't reference anymore; {@linkplain UnreferencedContentSweeper} deletes it when the
 * data directory is opened again.
 */
@Slf4j
final class DeferredDeletions {

  /**
   * Deletes the content of an object, which is what a storage does when nothing retains it.
   */
  private final Consumer<Long> deleter;

  /**
   * How many retentions each retained object has; guarded by {@code this}.
   */
  private final Map<Long, Integer> retained = new HashMap<>();

  /**
   * The retained objects whose deletion waits for their last retention; guarded by {@code this}.
   */
  private final Set<Long> deleting = new HashSet<>();

  DeferredDeletions(Consumer<Long> deleter) {
    this.deleter = deleter;
  }

  /**
   * Retain the content of the given objects. IDs that the storage doesn't hold are retained as well, so that a
   * caller needn't check what exists: nothing is deleted for them.
   *
   * @param ids the IDs of the objects to retain.
   * @return the retention.
   */
  ContentRetention retain(Collection<Long> ids) {
    List<Long> retaining = List.copyOf(ids);
    synchronized (this) {
      retaining.forEach(id -> retained.merge(id, 1, Integer::sum));
    }
    return new Retention(retaining);
  }

  /**
   * Record the deletion of an object if its content is retained.
   *
   * @param id the ID of the object to delete.
   * @return {@code true} if the deletion was recorded, i.e. the caller must not delete the content now; {@code false}
   *     if nothing retains it and the caller deletes it.
   */
  synchronized boolean defer(Long id) {
    if (!retained.containsKey(id)) {
      return false;
    }
    deleting.add(id);
    return true;
  }

  /**
   * Release one retention of each of the given objects, and delete the content of those that were deleted meanwhile
   * and aren't retained anymore. A failure to delete leaves the content behind rather than failing the reader that
   * releases the retention.
   */
  private void release(List<Long> ids) {
    List<Long> toDelete = new ArrayList<>();
    synchronized (this) {
      for (Long id : ids) {
        Integer count = retained.get(id);
        if (count == null) {
          continue;
        }
        if (count > 1) {
          retained.put(id, count - 1);
        } else {
          retained.remove(id);
          if (deleting.remove(id)) {
            toDelete.add(id);
          }
        }
      }
    }
    for (Long id : toDelete) {
      try {
        deleter.accept(id);
      } catch (RuntimeException e) {
        log.warn("Failed to delete object {} after the last read of it; it is left as an unreferenced object.", id, e);
      }
    }
  }

  /**
   * A retention of a set of objects, which releases them once.
   */
  private final class Retention implements ContentRetention {

    /**
     * The retained IDs; {@code null} once they are released. Guarded by the {@linkplain DeferredDeletions}.
     */
    private List<Long> ids;

    private Retention(List<Long> ids) {
      this.ids = ids;
    }

    @Override
    public void close() {
      List<Long> releasing;
      synchronized (DeferredDeletions.this) {
        releasing = ids;
        ids = null;
      }
      if (releasing != null) {
        release(releasing);
      }
    }
  }

}
