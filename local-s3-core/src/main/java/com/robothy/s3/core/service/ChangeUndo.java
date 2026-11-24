package com.robothy.s3.core.service;

/**
 * Undoes a failed change of a bucket in memory, for a service whose metadata is kept nowhere it could be reloaded from.
 * See {@code docs/architecture.md#a-change}.
 */
@FunctionalInterface
public interface ChangeUndo {

  /**
   * Start recording a change of a bucket, under its write lock, before the change runs.
   *
   * @param bucketName the bucket that is about to change.
   * @return what undoes the change, or forgets it.
   */
  Recording record(String bucketName);

  /**
   * What a change of one bucket changed, recorded until it ends.
   */
  interface Recording {

    /**
     * Restore the bucket as it was before the change; called under the write lock of the bucket.
     */
    void undo();

    /**
     * Keep the change, and drop what was recorded.
     */
    void forget();

  }

}
