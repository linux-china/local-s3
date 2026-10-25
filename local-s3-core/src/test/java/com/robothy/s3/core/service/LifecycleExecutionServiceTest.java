package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.event.S3ChangePublisher;
import com.robothy.s3.core.model.answers.LifecycleActionAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.storage.Storage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A run on a bucket with more keys than a batch holds the write lock of the bucket once per batch.
 */
class LifecycleExecutionServiceTest {

  private static final String BUCKET = "bucket";

  private static final String CONFIGURATION = """
      <LifecycleConfiguration>
        <Rule><ID>expire</ID><Status>Enabled</Status><Filter><Prefix>tmp/</Prefix></Filter>
          <Expiration><Days>1</Days></Expiration>
          <AbortIncompleteMultipartUpload><DaysAfterInitiation>1</DaysAfterInitiation></AbortIncompleteMultipartUpload>
        </Rule>
      </LifecycleConfiguration>
      """;

  private static final int EXPIRING = LifecycleExecutionService.LIFECYCLE_BATCH_SIZE * 2 + 500;

  private LocalS3Metadata metadata;

  private CountingBucketGuard guard;

  private BucketService bucketService;

  private ObjectService objectService;

  @BeforeEach
  void setUp() {
    metadata = new LocalS3Metadata();
    guard = new CountingBucketGuard();
    bucketService = DefaultBucketService.create(metadata, guard);
    objectService = DefaultObjectService.create(metadata, Storage.createInMemory(), guard);
    bucketService.createBucket(BUCKET);
    for (int i = 0; i < EXPIRING; i++) {
      put("tmp/" + i);
    }
    put("keep");
    objectService.createMultipartUpload(BUCKET, "tmp/upload", CreateMultipartUploadOptions.builder().build());
    bucketService.putBucketLifecycleConfiguration(BUCKET, CONFIGURATION, null);
  }

  @Test
  void appliesTheRulesInBatches() {
    guard.changes = 0;
    List<LifecycleActionAns> actions = objectService.applyLifecycle(BUCKET, Instant.now().plus(Duration.ofDays(3)));

    // tmp/0 ... tmp/2499 and keep: three batches, the last one also aborts the upload.
    assertEquals(3, guard.changes);
    assertEquals(EXPIRING, actions.stream().filter(a -> a.type() == LifecycleActionAns.Type.OBJECT_EXPIRED).count());
    assertEquals(List.of("tmp/upload"), actions.stream()
        .filter(a -> a.type() == LifecycleActionAns.Type.MULTIPART_UPLOAD_ABORTED).map(LifecycleActionAns::key).toList());
    assertEquals(List.of("keep"), List.copyOf(bucket().getObjectMap().keySet()));
    assertTrue(bucket().getUploads().isEmpty());
  }

  @Test
  void stopsOnceTheConfigurationIsDeletedBetweenBatches() {
    guard.afterChange = () -> bucketService.deleteBucketLifecycle(BUCKET);
    List<LifecycleActionAns> actions = objectService.applyLifecycle(BUCKET, Instant.now().plus(Duration.ofDays(3)));

    // The first batch is keep, which no rule selects, and the first expiring keys.
    int expired = LifecycleExecutionService.LIFECYCLE_BATCH_SIZE - 1;
    assertEquals(expired, actions.size());
    assertTrue(actions.stream().allMatch(a -> a.type() == LifecycleActionAns.Type.OBJECT_EXPIRED));
    assertEquals(EXPIRING - expired + 1, bucket().getObjectMap().size());
    assertFalse(bucket().getUploads().isEmpty());
  }

  private BucketMetadata bucket() {
    return metadata.getBucketMetadata(BUCKET).orElseThrow();
  }

  private void put(String key) {
    byte[] content = key.getBytes(StandardCharsets.UTF_8);
    objectService.putObject(BUCKET, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(content)).size(content.length).build());
  }

  /**
   * Counts the outermost changes of the bucket, and runs {@code afterChange} once after the first one, outside the lock.
   */
  private static final class CountingBucketGuard implements BucketGuard {

    private final BucketGuard delegate = BucketGuard.inMemory();

    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    private int changes;

    private Runnable afterChange;

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
      depth.set(depth.get() + 1);
      try {
        return delegate.change(bucketName, change, operation);
      } finally {
        depth.set(depth.get() - 1);
        if (depth.get() == 0) {
          changes++;
          Runnable hook = afterChange;
          afterChange = null;
          if (hook != null) {
            hook.run();
          }
        }
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
  }

}
