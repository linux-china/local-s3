package com.robothy.s3.core.storage;

/**
 * Keeps the content of objects readable while a reader still has to open it, so that content which is read part by
 * part needn't be opened all at once.
 *
 * <p>A reader that opens content later than it resolves it, e.g. the parts of an object of a multipart upload that
 * are opened one at a time while the object is sent, {@linkplain Storage#retain(java.util.Collection) retains} the
 * content it is going to open while it still holds the lock the object was resolved under. Deleting retained content
 * only marks it: the storage deletes it once the last retention of it is released, which the reader does when it is
 * done, whether it read all of the content or not.
 *
 * <p>A retention is released once; releasing it again does nothing. Implementations are thread safe: the retention is
 * usually taken by the thread of a request and released by the thread that finishes sending the response.
 */
@FunctionalInterface
public interface ContentRetention extends AutoCloseable {

  /**
   * The retention of content that can't be lost while it is read, e.g. the content of a read-only storage: retaining
   * and releasing it does nothing.
   */
  ContentRetention NONE = () -> {
  };

  /**
   * Release this retention. The content it retained is deleted now if it was deleted meanwhile and nothing else
   * retains it.
   */
  @Override
  void close();

}
