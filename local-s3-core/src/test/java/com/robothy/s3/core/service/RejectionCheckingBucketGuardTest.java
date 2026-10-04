package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.storage.Storage;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class RejectionCheckingBucketGuardTest {

  /**
   * The check itself: a rejection that changed an object, or an upload, of its bucket is recorded.
   */
  @Test
  void guardRecordsARejectionThatChangedTheBucket() {
    LocalS3Metadata metadata = new LocalS3Metadata();
    RejectionCheckingBucketGuard checking = new RejectionCheckingBucketGuard(() -> metadata);
    ObjectService objects = DefaultObjectService.create(metadata, Storage.createInMemory(), checking);
    DefaultBucketService.create(metadata, checking).createBucket("bucket");
    objects.putObject("bucket", "a",
        PutObjectOptions.builder().content(new ByteArrayInputStream(new byte[] {'a'})).size(1).build());
    objects.createMultipartUpload("bucket", "upload", CreateMultipartUploadOptions.builder().build());

    assertThrows(LocalS3Exception.class, () -> checking.change("bucket", BucketGuard.Change.UPDATE, () -> {
      metadata.getBucketMetadata("bucket").orElseThrow().getObjectMetadata("a").orElseThrow().getLatest()
          .setContentType("text/changed");
      throw new LocalS3InvalidArgumentException("x", "y", "rejected after a change");
    }));
    assertEquals(1, checking.violations().size(), checking.violations().toString());

    assertThrows(LocalS3Exception.class, () -> checking.change("bucket", BucketGuard.Change.UPDATE, () -> {
      metadata.getBucketMetadata("bucket").orElseThrow().getUploads().clear();
      throw new LocalS3InvalidArgumentException("x", "y", "rejected after a change");
    }));
    assertEquals(2, checking.violations().size(), checking.violations().toString());

    // A rejection that changed nothing isn't recorded.
    assertThrows(LocalS3Exception.class, () -> checking.change("bucket", BucketGuard.Change.UPDATE, () -> {
      throw new LocalS3InvalidArgumentException("x", "y", "rejected");
    }));
    assertEquals(2, checking.violations().size());
  }

}
