package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PutObjectServiceTest extends LocalS3ServiceTestBase {

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void doesNotLockBucketWhileStoringContent(LocalS3Manager manager) throws Exception {
    ObjectService objectService = manager.objectService();
    String bucketName = "my-bucket";
    manager.bucketService().createBucket(bucketName);
    putText(objectService, bucketName, "existing", "Hello");

    BlockingInputStream content = new BlockingInputStream("World".getBytes(StandardCharsets.UTF_8));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<PutObjectAns> uploading = executor.submit(() -> objectService.putObject(bucketName, "uploading",
          PutObjectOptions.builder().content(content).contentType("plain/text").size(5).build()));
      content.awaitReading();

      // Reads and writes of the bucket don't wait for the content of the upload.
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        try (InputStream existing = objectService.getObject(bucketName, "existing",
            GetObjectOptions.builder().build()).getContent()) {
          assertEquals("Hello", new String(existing.readAllBytes(), StandardCharsets.UTF_8));
        }
        putText(objectService, bucketName, "another", "!");
      });

      content.release();
      assertEquals(DigestUtils.md5Hex("World"), uploading.get(5, TimeUnit.SECONDS).getEtag());
    } finally {
      content.release();
      executor.shutdownNow();
    }
  }

  /**
   * A client whose declared length doesn't match its body, e.g. a wrong {@code x-amz-decoded-content-length},
   * must not leave a size in the metadata that the responses and range requests are then computed from.
   */
  @MethodSource("localS3Managers")
  @ParameterizedTest
  void storesTheLengthOfTheContentInsteadOfTheDeclaredOne(LocalS3Manager manager) throws Exception {
    ObjectService objectService = manager.objectService();
    String bucketName = "my-bucket";
    manager.bucketService().createBucket(bucketName);
    String text = "Robothy";

    PutObjectAns tooLarge = objectService.putObject(bucketName, "declares-too-much", PutObjectOptions.builder()
        .content(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
        .contentType("plain/text")
        .size(700)
        .build());
    assertEquals(text.length(), tooLarge.getSize());

    GetObjectAns object = objectService.getObject(bucketName, "declares-too-much", GetObjectOptions.builder().build());
    assertEquals(text.length(), object.getSize());
    try (InputStream content = object.getContent()) {
      assertEquals(text, new String(content.readAllBytes(), StandardCharsets.UTF_8));
    }

    PutObjectAns tooSmall = objectService.putObject(bucketName, "declares-too-little", PutObjectOptions.builder()
        .content(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
        .contentType("plain/text")
        .size(2)
        .build());
    assertEquals(text.length(), tooSmall.getSize());
    assertEquals(text.length(), objectService.getObject(bucketName, "declares-too-little",
        GetObjectOptions.builder().build()).getSize());

    // A request that declares no length at all is measured too.
    objectService.putObject(bucketName, "declares-nothing", PutObjectOptions.builder()
        .content(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
        .contentType("plain/text")
        .build());
    assertEquals(text.length(), objectService.getObject(bucketName, "declares-nothing",
        GetObjectOptions.builder().build()).getSize());
  }

  private static void putText(ObjectService objectService, String bucketName, String key, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    objectService.putObject(bucketName, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes))
        .contentType("plain/text")
        .size(bytes.length)
        .build());
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void putObject(LocalS3Manager manager) {
    BucketService bucketService = manager.bucketService();
    ObjectService objectService = manager.objectService();
    String bucketName = "my-bucket";

    bucketService.createBucket(bucketName);

    /*-- Bucket versioning suspended. --*/
    bucketService.setVersioningEnabled(bucketName, false);
    assertNotEquals(Boolean.TRUE, bucketService.getVersioningEnabled(bucketName));

    String key1 = "key1";
    PutObjectAns putObjectAns1 = objectService.putObject(bucketName, key1, PutObjectOptions.builder()
        .content(new ByteArrayInputStream("Hello".getBytes()))
        .contentType("plain/text")
        .size(5)
        .tagging(new String[][]{{"key1", "value1"}, {"key2", "value2"}})
        .userMetadata(Map.of("key1", "value1", "key2", "value2"))
        .build());
    assertEquals(key1, putObjectAns1.getKey());
    assertEquals(ObjectMetadata.NULL_VERSION, putObjectAns1.getVersionId());
    assertEquals(DigestUtils.md5Hex("Hello"), putObjectAns1.getEtag());

    LocalS3Metadata s3Metadata = objectService.localS3Metadata();
    Optional<BucketMetadata> bucketMetadataOpt = s3Metadata.getBucketMetadata(bucketName);
    assertTrue(bucketMetadataOpt.isPresent());
    BucketMetadata bucketMetadata = bucketMetadataOpt.get();
    Optional<ObjectMetadata> objectMetadata1Opt = bucketMetadata.getObjectMetadata(key1);
    assertTrue(objectMetadata1Opt.isPresent());
    ObjectMetadata objectMetadata1 = objectMetadata1Opt.get();
    Optional<String> virtualVersion1Opt = objectMetadata1.getVirtualVersion();
    assertTrue(virtualVersion1Opt.isPresent());

    String virtualVersion1 = virtualVersion1Opt.get();
    assertEquals(virtualVersion1, objectMetadata1.getLatestVersion());
    Optional<VersionedObjectMetadata> versionedObjectMetadata1Opt =
        objectMetadata1.getVersionedObjectMetadata(virtualVersion1);
    assertTrue(versionedObjectMetadata1Opt.isPresent());
    VersionedObjectMetadata versionedObjectMetadata1 = versionedObjectMetadata1Opt.get();
    assertEquals("plain/text", versionedObjectMetadata1.getContentType());
    assertEquals(5, versionedObjectMetadata1.getSize());
    assertTrue(versionedObjectMetadata1.getTagging().isPresent());
    assertEquals(2, versionedObjectMetadata1.getTagging().get().length);
    assertTrue(objectService.storage().isExist(versionedObjectMetadata1.getFileId()));
    assertEquals(2, versionedObjectMetadata1.getUserMetadata().size());
    assertEquals("value1", versionedObjectMetadata1.getUserMetadata().get("key1"));
    assertEquals("value2", versionedObjectMetadata1.getUserMetadata().get("key2"));

    PutObjectAns putObjectAns2 = objectService.putObject(bucketName, key1, PutObjectOptions.builder()
        .contentType("application/json")
        .size(10)
        .content(new ByteArrayInputStream("{\"length\": 12}".getBytes()))
        .tagging(new String[][]{{"key1", "value1"}, {"key2", "value2"}})
        .build());
    assertEquals(key1, putObjectAns2.getKey());
    assertEquals(ObjectMetadata.NULL_VERSION, putObjectAns2.getVersionId());
    Optional<String> virtualVersion2Opt = objectMetadata1.getVirtualVersion();
    assertTrue(virtualVersion2Opt.isPresent());
    String virtualVersion2 = virtualVersion2Opt.get();
    assertEquals(virtualVersion2, objectMetadata1.getLatestVersion());
    assertEquals(1, objectMetadata1.getVersionedObjectMap().size());
    assertFalse(objectService.storage().isExist(versionedObjectMetadata1.getFileId())); // The previous file should be deleted.

    /*-- Enable bucket versioning. --*/
    bucketService.setVersioningEnabled(bucketName, true);
    PutObjectAns putObjectAns3 = objectService.putObject(bucketName, key1, PutObjectOptions.builder()
        .contentType("plain/text")
        .content(new ByteArrayInputStream("Hello".getBytes()))
        .build());
    assertEquals(key1, putObjectAns3.getKey());
    assertNotNull(putObjectAns3.getVersionId());
    assertNotEquals(ObjectMetadata.NULL_VERSION, putObjectAns3.getVersionId());
    assertEquals(putObjectAns3.getVersionId(), objectMetadata1.getLatestVersion());
    Optional<String> virtualVersion3Opt = objectMetadata1.getVirtualVersion();
    assertTrue(virtualVersion3Opt.isPresent());
    assertNotEquals(objectMetadata1.getLatestVersion(), virtualVersion3Opt.get());
    Optional<VersionedObjectMetadata> versionedObjectMetadataOpt =
        objectMetadata1.getVersionedObjectMetadata(putObjectAns3.getVersionId());
    assertTrue(versionedObjectMetadataOpt.isPresent());
    assertEquals(objectMetadata1.getLatest(), versionedObjectMetadataOpt.get());
    assertEquals(2, objectMetadata1.getVersionedObjectMap().size());


    /*-- Suspend bucket versioning --*/
    bucketService.setVersioningEnabled(bucketName, false);
    PutObjectAns putObjectAns4 = objectService.putObject(bucketName, key1, PutObjectOptions.builder()
        .content(new ByteArrayInputStream("World".getBytes()))
        .contentType("application/xml")
        .build());
    assertEquals(key1, putObjectAns4.getKey());
    assertEquals(ObjectMetadata.NULL_VERSION, putObjectAns4.getVersionId());
    Optional<String> virtualVersion4Opt = objectMetadata1.getVirtualVersion();
    assertTrue(virtualVersion4Opt.isPresent());
    assertEquals(objectMetadata1.getLatestVersion(), virtualVersion4Opt.get());
    assertEquals(2, objectMetadata1.getVersionedObjectMap().size());
  }

}