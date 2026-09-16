package com.robothy.s3.core.model.internal;

import java.util.HashSet;
import java.util.Set;

/**
 * The buckets that the current thread is changing, which tells the metadata of a bucket whether an object it hands out
 * is about to be changed.
 *
 * <p>A service reaches the state it changes through the metadata of its bucket, e.g.
 * {@linkplain BucketMetadata#getObjectMetadata(String)}, and then changes it in place, e.g. by setting the tagging of
 * one of its versions. The metadata can't see such a change, so it records the object as changed as soon as it hands it
 * out within a change of its bucket. Outside a change, i.e. while a request only reads the bucket, nothing is recorded,
 * so reading a bucket doesn't make its store write anything.
 *
 * <p>{@linkplain com.robothy.s3.core.service.BucketGuard#change} opens the scope of a change, around the operation that
 * makes it and the persistence that follows it.
 */
public final class BucketChangeScope {

  private static final ThreadLocal<Set<String>> CHANGING = new ThreadLocal<>();

  private BucketChangeScope() {
  }

  /**
   * Whether the current thread is changing a bucket.
   *
   * @param bucketName the bucket name.
   * @return {@code true} if a change of the bucket is running on this thread.
   */
  public static boolean isChanging(String bucketName) {
    Set<String> changing = CHANGING.get();
    return changing != null && changing.contains(bucketName);
  }

  /**
   * Begin the scope of a change of a bucket on the current thread.
   *
   * @param bucketName the bucket name.
   * @return {@code true} if the scope was begun, and must be {@linkplain #end ended}; {@code false} if the thread is
   *     already changing the bucket, i.e. this change is nested in another one, which ends the scope.
   */
  public static boolean begin(String bucketName) {
    Set<String> changing = CHANGING.get();
    if (changing == null) {
      changing = new HashSet<>();
      CHANGING.set(changing);
    }
    return changing.add(bucketName);
  }

  /**
   * End the scope of a change that {@linkplain #begin} begun.
   *
   * @param bucketName the bucket name.
   */
  public static void end(String bucketName) {
    Set<String> changing = CHANGING.get();
    if (changing == null) {
      return;
    }
    changing.remove(bucketName);
    if (changing.isEmpty()) {
      CHANGING.remove();
    }
  }

}
