package com.robothy.s3.core.service;

import com.robothy.s3.core.event.S3ChangePublisher;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@linkplain BucketGuard} of an {@code IN_MEMORY} service that checks the contract the guard relies on: an operation
 * that rejects a request with a {@linkplain LocalS3Exception} does so before it changes the metadata of the bucket.
 *
 * <p>An {@code IN_MEMORY} service has no store to reload a bucket from, so {@linkplain DefaultBucketGuard} keeps
 * whatever a failed change did to the metadata, and a {@code PERSISTENCE} service doesn't reload a bucket after a
 * rejection either. This guard takes a snapshot of the bucket before the outermost change of a bucket, and compares it
 * with the bucket once the change is rejected; a rejection that left the bucket changed is recorded as a violation
 * rather than thrown, so that the operation under test fails the way it would without the check.
 */
final class RejectionCheckingBucketGuard implements BucketGuard {

  /**
   * Serializes the metadata of a bucket whole, its objects and uploads included, with the keys of its maps sorted, so
   * that two snapshots of the same metadata are the same string.
   */
  private static final JsonMapper SNAPSHOT_MAPPER = JsonMapper.builderWithJackson2Defaults()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .build();

  private final BucketGuard delegate = BucketGuard.inMemory();

  private final Supplier<LocalS3Metadata> metadata;

  private final ThreadLocal<Set<String>> changing = ThreadLocal.withInitial(HashSet::new);

  private final List<String> violations = new ArrayList<>();

  private int checkedRejections;

  RejectionCheckingBucketGuard(Supplier<LocalS3Metadata> metadata) {
    this.metadata = Objects.requireNonNull(metadata);
  }

  /**
   * The rejections that left a bucket changed, each with the operation's exception, the snapshot before and after.
   */
  synchronized List<String> violations() {
    return List.copyOf(violations);
  }

  /**
   * The number of rejections that were checked, i.e. that an operation raised within a change of a bucket rather than
   * before it.
   */
  synchronized int checkedRejections() {
    return checkedRejections;
  }

  @Override
  public <T> T read(String bucketName, Supplier<T> operation) {
    return delegate.read(bucketName, operation);
  }

  @Override
  public <T> T write(String bucketName, Supplier<T> operation) {
    return delegate.write(bucketName, operation);
  }

  @Override
  public <T> T change(String bucketName, Change change, Supplier<T> operation) {
    Set<String> buckets = changing.get();
    if (!buckets.add(bucketName)) {
      // Nested in a change of the same bucket, which the outermost one checks.
      return delegate.change(bucketName, change, operation);
    }
    try {
      return delegate.change(bucketName, change, () -> {
        // Taken under the write lock of the bucket, so that no other change of the bucket is in the snapshots.
        String before = snapshot(bucketName);
        try {
          return operation.get();
        } catch (LocalS3Exception e) {
          String after = snapshot(bucketName);
          countCheckedRejection();
          if (!before.equals(after)) {
            recordViolation(bucketName + ": " + e.getClass().getSimpleName() + " (" + e.getMessage() + ")"
                + "\n  before: " + before + "\n  after:  " + after);
          }
          throw e;
        }
      });
    } finally {
      buckets.remove(bucketName);
    }
  }

  @Override
  public <T> T exclusive(Supplier<T> operation) {
    return delegate.exclusive(operation);
  }

  @Override
  public S3ChangePublisher changePublisher() {
    return delegate.changePublisher();
  }

  private synchronized void countCheckedRejection() {
    checkedRejections++;
  }

  private synchronized void recordViolation(String violation) {
    violations.add(violation);
  }

  private String snapshot(String bucketName) {
    return metadata.get().getBucketMetadata(bucketName)
        .map(SNAPSHOT_MAPPER::writeValueAsString)
        .orElse("<no bucket>");
  }

}
