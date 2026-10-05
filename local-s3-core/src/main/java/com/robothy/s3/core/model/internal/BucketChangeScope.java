package com.robothy.s3.core.model.internal;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

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
 * makes it and the persistence that follows it, and asks it whether the bucket is already changing, in which case the
 * outer change persists it. A change is recorded with its owner, the guard, since the S3 and the vector buckets of a
 * service are guarded apart and may share a name.
 */
public final class BucketChangeScope {

  private record Changing(Object owner, String bucketName) {
  }

  private static final ScopedValue<Set<Changing>> CHANGING = ScopedValue.newInstance();

  private BucketChangeScope() {
  }

  /**
   * Whether the current thread is changing a bucket of that name, whoever changes it.
   *
   * @param bucketName the bucket name.
   * @return {@code true} if a change of the bucket is running on this thread.
   */
  public static boolean isChanging(String bucketName) {
    if (!CHANGING.isBound()) {
      return false;
    }
    for (Changing changing : CHANGING.get()) {
      if (changing.bucketName().equals(bucketName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the current thread runs a change of the bucket that {@code owner} began.
   *
   * @param owner the owner of the change, e.g. a guard.
   * @param bucketName the bucket name.
   * @return {@code true} if such a change is running on this thread.
   */
  public static boolean isChanging(Object owner, String bucketName) {
    return CHANGING.isBound() && CHANGING.get().contains(new Changing(owner, bucketName));
  }

  /**
   * Run {@code operation} within the scope of a change of a bucket on the current thread.
   *
   * @param owner the owner of the change, e.g. a guard.
   * @param bucketName the bucket name.
   * @param operation the operation.
   * @return what the operation answers.
   */
  public static <T> T change(Object owner, String bucketName, Supplier<T> operation) {
    Set<Changing> changing = new HashSet<>(CHANGING.isBound() ? CHANGING.get() : Set.of());
    changing.add(new Changing(owner, bucketName));
    return ScopedValue.where(CHANGING, Set.copyOf(changing)).call(operation::get);
  }

}
