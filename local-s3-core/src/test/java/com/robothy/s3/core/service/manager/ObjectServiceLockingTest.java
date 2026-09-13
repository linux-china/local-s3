package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;
import com.robothy.s3.core.exception.InvalidPartException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.answers.CopyObjectAns;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.BlockingInputStream;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.S3ObjectUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
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
      // The entity tag of an object uploaded in parts, i.e. the digest of the digests of "Robo" and "thy".
      assertEquals(S3ObjectUtils.compositeEtag(List.of(DigestUtils.md5("Robo"), DigestUtils.md5("thy"))),
          completing.get(5, TimeUnit.SECONDS).getEtag());
    } finally {
      storage.releaseBlockedRead();
      executor.shutdownNow();
    }
  }

  /**
   * A part uploaded again while the parts are concatenated isn't the part that was concatenated, so the upload
   * isn't completed with the stale data; it can be completed again with the part as it is now.
   */
  @Test
  void completeMultipartUploadRejectsAPartUploadedAgainWhileConcatenating() throws Exception {
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
          objectService.completeMultipartUpload(bucket, key, uploadId, completeParts(2)));
      BlockingInputStream blocked = storage.awaitBlockedRead();

      objectService.uploadPart(bucket, key, uploadId, 1, part("Andy"));
      blocked.release();

      ExecutionException thrown = assertThrows(ExecutionException.class, () -> completing.get(5, TimeUnit.SECONDS));
      assertInstanceOf(InvalidPartException.class, thrown.getCause());
    } finally {
      storage.releaseBlockedRead();
      executor.shutdownNow();
    }

    assertThrows(ObjectNotExistException.class,
        () -> objectService.getObject(bucket, key, GetObjectOptions.builder().build()));
    objectService.completeMultipartUpload(bucket, key, uploadId, completeParts(2));
    assertEquals("Andythy", new String(objectService.getObject(bucket, key, GetObjectOptions.builder().build())
        .getContent().readAllBytes(), StandardCharsets.UTF_8));
  }

  /**
   * A part uploaded again after the upload was validated, but before its data was opened, has its data
   * deleted; that is an {@code InvalidPart} too, rather than an error of the storage.
   */
  @Test
  void completeMultipartUploadRejectsAPartUploadedAgainBeforeItIsOpened() {
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

    storage.beforeNextOpen(() -> objectService.uploadPart(bucket, key, uploadId, 1, part("Andy")));

    assertThrows(InvalidPartException.class,
        () -> objectService.completeMultipartUpload(bucket, key, uploadId, completeParts(2)));
    assertThrows(ObjectNotExistException.class,
        () -> objectService.getObject(bucket, key, GetObjectOptions.builder().build()));
  }

  private static List<CompleteMultipartUploadPartOption> completeParts(int parts) {
    return IntStream.rangeClosed(1, parts)
        .mapToObj(partNumber -> CompleteMultipartUploadPartOption.builder().partNumber(partNumber).build())
        .toList();
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

    private final AtomicReference<Runnable> beforeNextOpen = new AtomicReference<>();

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

    /**
     * Run an action right before the next stream of the storage is opened, e.g. to change the object it opens.
     */
    void beforeNextOpen(Runnable action) {
      beforeNextOpen.set(action);
    }

    void releaseBlockedRead() {
      BlockingInputStream stream = blocked.get();
      if (stream != null) {
        stream.release();
      }
    }

    @Override
    public InputStream getInputStream(Long id) {
      Runnable action = beforeNextOpen.getAndSet(null);
      if (action != null) {
        action.run();
      }
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
