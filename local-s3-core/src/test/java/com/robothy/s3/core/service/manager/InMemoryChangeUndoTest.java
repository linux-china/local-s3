package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.Storage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An {@code IN_MEMORY} service undoes a change that fails after it changed the metadata, which it has no store to
 * reload from.
 */
class InMemoryChangeUndoTest {

  private static final String BUCKET = "bucket";

  private static final String KEY = "a.txt";

  private final FailingStorage storage = new FailingStorage(Storage.createInMemory());

  private LocalS3Metadata metadata;

  private ObjectService objectService;

  @BeforeEach
  void setUp() {
    metadata = new LocalS3Metadata();
    InMemoryLocalS3Manager manager = new InMemoryLocalS3Manager(metadata, storage);
    manager.bucketService().createBucket(BUCKET);
    objectService = manager.objectService();
  }

  /**
   * Replacing the null version deletes its content after the new version is attached; a failure there leaves the
   * object as it was, with its content.
   */
  @Test
  void aPutThatFailsAfterAttachingTheNewVersionKeepsTheOldOne() {
    put("one");
    long oldFileId = latest().getLatest().getFileId();

    storage.failOn = oldFileId;
    assertThrows(IllegalStateException.class, () -> put("two"));
    storage.failOn = null;

    ObjectMetadata object = latest();
    assertEquals(1, object.getVersionedObjectMap().size());
    assertEquals(oldFileId, object.getLatest().getFileId());
    assertEquals("one", read());
    assertEquals(1, storage.count, "The content of the failed put is discarded.");

    put("three");
    assertEquals("three", read());
    assertFalse(storage.isExist(oldFileId), "The replaced content is deleted once a put succeeds.");
  }

  @Test
  void anUploadPartThatFailsAfterReplacingThePartKeepsTheOldPart() {
    String uploadId = objectService.createMultipartUpload(BUCKET, KEY, CreateMultipartUploadOptions.builder().build());
    objectService.uploadPart(BUCKET, KEY, uploadId, 1, part("first"));
    UploadPartMetadata first = upload(uploadId).getParts().get(1);

    storage.failOn = first.getFileId();
    assertThrows(IllegalStateException.class, () -> objectService.uploadPart(BUCKET, KEY, uploadId, 1, part("second")));
    storage.failOn = null;

    assertEquals(first.getFileId(), upload(uploadId).getParts().get(1).getFileId());
    assertEquals(1, upload(uploadId).getParts().size());
    assertTrue(storage.isExist(first.getFileId()));
  }

  private void put(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    objectService.putObject(BUCKET, KEY, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes)).size(bytes.length).build());
  }

  private String read() {
    try (InputStream content = objectService.getObject(BUCKET, KEY, GetObjectOptions.builder().build()).getContent()) {
      return new String(content.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ObjectMetadata latest() {
    return bucket().getObjectMetadataRef(KEY).orElseThrow().get();
  }

  private UploadMetadata upload(String uploadId) {
    return bucket().getUploads().get(KEY).get(uploadId);
  }

  private BucketMetadata bucket() {
    return metadata.getBucketMetadata(BUCKET).orElseThrow();
  }

  private static UploadPartOptions part(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return UploadPartOptions.builder().contentLength(bytes.length).data(new ByteArrayInputStream(bytes)).build();
  }

  /**
   * Fails to delete, or to tell whether it holds, one piece of content.
   */
  private static final class FailingStorage implements Storage {

    private final Storage delegate;

    private volatile Long failOn;

    private int count;

    FailingStorage(Storage delegate) {
      this.delegate = delegate;
    }

    @Override
    public Long put(Long id, byte[] data) {
      count++;
      return delegate.put(id, data);
    }

    @Override
    public Long put(Long id, InputStream data) {
      count++;
      return delegate.put(id, data);
    }

    @Override
    public byte[] getBytes(Long id) {
      return delegate.getBytes(id);
    }

    @Override
    public InputStream getInputStream(Long id) {
      return delegate.getInputStream(id);
    }

    @Override
    public Long delete(Long id) {
      fail(id);
      count--;
      return delegate.delete(id);
    }

    @Override
    public boolean isExist(Long id) {
      fail(id);
      return delegate.isExist(id);
    }

    private void fail(Long id) {
      if (id.equals(failOn)) {
        throw new IllegalStateException("Failed on content " + id + ".");
      }
    }

  }

}
