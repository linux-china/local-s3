package com.robothy.s3.core.event;

/**
 * Receives the {@linkplain S3Change}s that the services of a LocalS3 service commit, whether an HTTP request or a
 * direct call of a service made them.
 *
 * @see S3ChangePublisher
 */
@FunctionalInterface
public interface S3ChangeListener {

  /**
   * Receive a committed change. Called on the thread that made the change, once the change is persisted and the lock
   * of its bucket is released, so a listener may call the services again. An exception that it throws is logged and
   * doesn't fail the operation, which has completed already.
   *
   * @param change the change.
   */
  void onChange(S3Change change);

}
