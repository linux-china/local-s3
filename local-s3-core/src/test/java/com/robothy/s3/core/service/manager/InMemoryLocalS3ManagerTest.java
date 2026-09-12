package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.model.answers.DeleteObjectAns;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.Storage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

class InMemoryLocalS3ManagerTest {

  @Test
  void testConstructor() throws IOException {
    InMemoryLocalS3Manager managerWithoutInitData = new InMemoryLocalS3Manager(null, true);
    assertInstanceOf(BucketService.class, managerWithoutInitData.bucketService());
    assertInstanceOf(ObjectService.class, managerWithoutInitData.objectService());

    Path tempDirectory = Files.createTempDirectory("local-s3");
    InMemoryLocalS3Manager managerWithInitData = new InMemoryLocalS3Manager(tempDirectory, true);
    assertInstanceOf(BucketService.class, managerWithInitData.bucketService());
    assertInstanceOf(ObjectService.class, managerWithInitData.objectService());
    FileUtils.deleteDirectory(tempDirectory.toFile());
  }

  @Test
  void cacheDropsTheLeastRecentlyUsedDataPath() {
    InMemoryLocalS3Manager.InitialDataCache cache = new InMemoryLocalS3Manager.InitialDataCache(2);

    InMemoryLocalS3Manager.InitialDataCache.CacheValue first = cache.computeIfAbsent("/a", key -> cacheValue());
    assertSame(first, cache.computeIfAbsent("/a", key -> fail("The cached data path was loaded again.")));
    cache.computeIfAbsent("/b", key -> cacheValue());
    assertEquals(2, cache.size());

    // The third data path drops the least recently used one, which is "/a".
    cache.computeIfAbsent("/c", key -> cacheValue());
    assertEquals(2, cache.size());
    AtomicBoolean loadedAgain = new AtomicBoolean();
    cache.computeIfAbsent("/a", key -> {
      loadedAgain.set(true);
      return cacheValue();
    });
    assertTrue(loadedAgain.get(), "The dropped data path should be loaded again.");
    assertEquals(2, cache.size());
  }

  @Test
  void clearInitialDataCacheDropsTheCachedDataPaths() throws IOException {
    Path dataPath = Files.createTempDirectory("local-s3");
    LocalS3Manager.createInMemoryS3Manager(dataPath, true);
    assertTrue(InMemoryLocalS3Manager.initialDataCacheSize() > 0);

    LocalS3Manager.clearInitialDataCache();
    assertEquals(0, InMemoryLocalS3Manager.initialDataCacheSize());

    FileUtils.deleteDirectory(dataPath.toFile());
  }

  private static InMemoryLocalS3Manager.InitialDataCache.CacheValue cacheValue() {
    return new InMemoryLocalS3Manager.InitialDataCache.CacheValue(new LocalS3Metadata(), Storage.createInMemory());
  }

  @Test
  void testCache() throws IOException {
    Path dataPath = Files.createTempDirectory("local-s3");
    LocalS3Manager fileSystemS3Manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    BucketService fsBucketService = fileSystemS3Manager.bucketService();
    ObjectService fsObjectService = fileSystemS3Manager.objectService();
    String bucket = "my-bucket";
    String key = "a.txt";
    fsBucketService.createBucket(bucket);
    fsBucketService.setVersioningEnabled(bucket, false);
    fsObjectService.putObject(bucket, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream("Robothy".getBytes()))
        .contentType("plain/text")
        .size(7L)
        .build());

    LocalS3Manager inMemoryS3Manager = LocalS3Manager.createInMemoryS3Manager(dataPath, true);
    BucketService inMemoBucketService = inMemoryS3Manager.bucketService();
    ObjectService inMemoObjectService = inMemoryS3Manager.objectService();
    assertDoesNotThrow(() -> inMemoBucketService.getBucket(bucket));
    assertDoesNotThrow(() -> inMemoObjectService.getObject(bucket, key, GetObjectOptions.builder().build()));
    GetObjectAns object = inMemoObjectService.getObject(bucket, key, GetObjectOptions.builder().build());
    assertEquals("Robothy", new String(object.getContent().readAllBytes()));
    assertEquals("plain/text", object.getContentType());
    assertEquals(7L, object.getSize());

    DeleteObjectAns deleteObjectAns = inMemoObjectService.deleteObject(bucket, key);
    GetObjectAns deletedObject = inMemoObjectService.getObject(bucket, key, GetObjectOptions.builder()
        .versionId(deleteObjectAns.getVersionId()).build());
    assertTrue(deletedObject.isDeleteMarker());
    inMemoBucketService.createBucket("your-bucket");

    LocalS3Manager inMemoryS3Manager1 = LocalS3Manager.createInMemoryS3Manager(dataPath, true);
    BucketService inMemoBucketService1 = inMemoryS3Manager1.bucketService();
    ObjectService inMemoObjectService1 = inMemoryS3Manager1.objectService();
    assertThrows(BucketNotExistException.class, () -> inMemoBucketService1.getBucket("your-bucket"));
    GetObjectAns object1 = inMemoObjectService1.getObject(bucket, key, GetObjectOptions.builder().build());
    assertEquals("Robothy", new String(object1.getContent().readAllBytes()));
    assertEquals("plain/text", object1.getContentType());
    assertEquals(7L, object1.getSize());

    FileUtils.deleteDirectory(dataPath.toFile());
  }


}