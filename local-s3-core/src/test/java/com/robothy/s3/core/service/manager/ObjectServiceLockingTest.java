package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.answers.CopyObjectAns;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.BlockingInputStream;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.Storage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

/**
 * The operations that copy whole objects must not hold a bucket lock while the content is copied.
 */
class ObjectServiceLockingTest {

  @Test
  void copyObjectDoesNotLockTheDestinationBucketWhileCopyingContent() throws Exception {
    BlockingStorage storage = new BlockingStorage();
    InMemoryLocalS3Manager manager = new InMemoryLocalS3Manager(new LocalS3Metadata(), storage);
    BucketService bucketService = manager.bucketService();
    ObjectService objectService = manager.objectService();
    bucketService.createBucket("source-bucket");
    bucketService.createBucket("destination-bucket");
    putText(objectService, "source-bucket", "key", "Robothy");

    storage.blockNextRead();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<CopyObjectAns> copying = executor.submit(() -> objectService.copyObject("destination-bucket", "copied",
          CopyObjectOptions.builder().sourceBucket("source-bucket").sourceKey("key").build()));
      BlockingInputStream blocked = storage.awaitBlockedRead();

      // Writes to the destination bucket don't wait for the content of the copy.
      assertTimeoutPreemptively(Duration.ofSeconds(5),
          () -> putText(objectService, "destination-bucket", "another", "!"));

      blocked.release();
      assertEquals(DigestUtils.md5Hex("Robothy"), copying.get(5, TimeUnit.SECONDS).getEtag());
    } finally {
      storage.releaseBlockedRead();
      executor.shutdownNow();
    }
  }

  @Test
  void completeMultipartUploadDoesNotLockTheBucketWhileConcatenatingParts() throws Exception {
    BlockingStorage storage = new BlockingStorage();
    InMemoryLocalS3Manager manager = new InMemoryLocalS3Manager(new LocalS3Metadata(), storage);
    BucketService bucketService = manager.bucketService();
    ObjectService objectService = manager.objectService();
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = objectService.createMultipartUpload(bucket, key,
        CreateMultipartUploadOptions.builder().contentType("plain/text").build());
    objectService.uploadPart(bucket, key, uploadId, 1, part("Robo"));
    objectService.uploadPart(bucket, key, uploadId, 2, part("thy"));

    storage.blockNextRead();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<CompleteMultipartUploadAns> completing = executor.submit(() ->
          objectService.completeMultipartUpload(bucket, key, uploadId, List.of(
              CompleteMultipartUploadPartOption.builder().partNumber(1).build(),
              CompleteMultipartUploadPartOption.builder().partNumber(2).build())));
      BlockingInputStream blocked = storage.awaitBlockedRead();

      // Writes to the bucket don't wait for the parts to be concatenated.
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> putText(objectService, bucket, "another", "!"));

      blocked.release();
      assertEquals(DigestUtils.md5Hex("Robothy"), completing.get(5, TimeUnit.SECONDS).getEtag());
    } finally {
      storage.releaseBlockedRead();
      executor.shutdownNow();
    }
  }

  private static void putText(ObjectService objectService, String bucket, String key, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    objectService.putObject(bucket, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes))
        .contentType("plain/text")
        .size(bytes.length)
        .build());
  }

  private static UploadPartOptions part(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return UploadPartOptions.builder().contentLength(bytes.length).data(new ByteArrayInputStream(bytes)).build();
  }

  /**
   * A storage whose next read blocks until the test releases it, so that the test can act while a service
   * copies the content of an object.
   */
  private static class BlockingStorage implements Storage {

    private final Storage delegate = Storage.createInMemory();

    private final AtomicReference<BlockingInputStream> blocked = new AtomicReference<>();

    private volatile boolean blockNextRead;

    void blockNextRead() {
      blockNextRead = true;
    }

    /**
     * Wait until the storage blocks a read.
     */
    BlockingInputStream awaitBlockedRead() throws InterruptedException {
      long deadline = System.currentTimeMillis() + 5000;
      BlockingInputStream stream;
      while ((stream = blocked.get()) == null) {
        if (System.currentTimeMillis() > deadline) {
          fail("No read of the storage was blocked.");
        }
        Thread.sleep(10);
      }
      stream.awaitReading();
      return stream;
    }

    void releaseBlockedRead() {
      BlockingInputStream stream = blocked.get();
      if (stream != null) {
        stream.release();
      }
    }

    @Override
    public InputStream getInputStream(Long id) {
      if (blockNextRead && blocked.get() == null) {
        BlockingInputStream stream = new BlockingInputStream(delegate.getBytes(id));
        if (blocked.compareAndSet(null, stream)) {
          return stream;
        }
      }
      return delegate.getInputStream(id);
    }

    @Override
    public Long put(Long id, byte[] data) {
      return delegate.put(id, data);
    }

    @Override
    public Long put(Long id, InputStream data) {
      return delegate.put(id, data);
    }

    @Override
    public byte[] getBytes(Long id) {
      return delegate.getBytes(id);
    }

    @Override
    public Long delete(Long id) {
      return delegate.delete(id);
    }

    @Override
    public boolean isExist(Long id) {
      return delegate.isExist(id);
    }
  }

}
