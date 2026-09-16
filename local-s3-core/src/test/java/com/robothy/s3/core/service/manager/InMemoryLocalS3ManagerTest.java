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
import com.robothy.s3.core.storage.CopyBudget;
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
  void cachesServices() {
    LocalS3Manager manager = LocalS3Manager.createInMemoryS3Manager();

    assertSame(manager.bucketService(), manager.bucketService());
    assertSame(manager.objectService(), manager.objectService());
  }

  @Test
  void cacheDropsTheLeastRecentlyUsedDataPath() {
    InitialDataCache cache = new InitialDataCache(2);

    InitialDataCache.CacheValue first = cache.computeIfAbsent("/a", key -> cacheValue());
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

  private static InitialDataCache.CacheValue cacheValue() {
    return new InitialDataCache.CacheValue(new LocalS3Metadata(),
        Storage.createCopyOnAccess(Storage.createInMemory(), CopyBudget.UNLIMITED));
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


  /**
   * A reset drops the data of a service without initial data, and the services keep working on the new data.
   */
  @Test
  void resetDropsTheData() {
    LocalS3Manager manager = LocalS3Manager.createInMemoryS3Manager();
    BucketService bucketService = manager.bucketService();
    ObjectService objectService = manager.objectService();
    bucketService.createBucket("bucket");
    putObject(objectService, "bucket", "a.txt", "Hello");
    objectService.createMultipartUpload("bucket", "upload.txt",
        com.robothy.s3.core.model.request.CreateMultipartUploadOptions.builder().build());

    assertEquals(new ObjectStatistics(1, 1, 1, 0, 5, 1, 0, 0), withoutHeapCounts(manager.statistics()));

    manager.reset();

    assertEquals(new ObjectStatistics(0, 0, 0, 0, 0, 0, 0, 0), withoutHeapCounts(manager.statistics()));
    assertThrows(BucketNotExistException.class, () -> bucketService.getBucket("bucket"));
    bucketService.createBucket("bucket");
    putObject(objectService, "bucket", "b.txt", "World");
    assertEquals(1, objectService.listObjects("bucket", null, null, null, 10, null).getObjects().size());
  }

  /**
   * A reset restores the initial data of the data path, whether it is cached or not, dropping what the service changed.
   */
  @Test
  void resetRestoresTheInitialData() throws IOException {
    Path dataPath = Files.createTempDirectory("local-s3");
    try {
      LocalS3Manager persistent = LocalS3Manager.createFileSystemS3Manager(dataPath);
      persistent.bucketService().createBucket("bucket");
      putObject(persistent.objectService(), "bucket", "initial.txt", "Initial");
      assertThrows(UnsupportedOperationException.class, persistent::reset, "The data of the path isn't deleted.");

      for (boolean cached : new boolean[] {true, false}) {
        InMemoryLocalS3Manager manager = new InMemoryLocalS3Manager(dataPath, cached, new InitialDataCache(16));
        ObjectService objectService = manager.objectService();
        objectService.deleteObject("bucket", "initial.txt");
        putObject(objectService, "bucket", "added.txt", "Added");
        manager.bucketService().createBucket("another-bucket");
        assertEquals(2, manager.statistics().buckets());

        manager.reset();

        assertEquals(new ObjectStatistics(1, 1, 1, 0, 7, 0, 0, 0), withoutHeapCounts(manager.statistics()),
            "cached: " + cached);
        GetObjectAns object = objectService.getObject("bucket", "initial.txt", GetObjectOptions.builder().build());
        assertEquals("Initial", new String(object.getContent().readAllBytes()));
        assertThrows(Exception.class,
            () -> objectService.getObject("bucket", "added.txt", GetObjectOptions.builder().build()));
      }
    } finally {
      FileUtils.deleteDirectory(dataPath.toFile());
    }
  }

  @Test
  void statisticsCountVersionsAndDeleteMarkers() {
    LocalS3Manager manager = LocalS3Manager.createInMemoryS3Manager();
    manager.bucketService().createBucket("versioned");
    manager.bucketService().setVersioningEnabled("versioned", true);
    manager.bucketService().createBucket("empty");
    ObjectService objectService = manager.objectService();
    putObject(objectService, "versioned", "a.txt", "1");
    putObject(objectService, "versioned", "a.txt", "22");
    putObject(objectService, "versioned", "b.txt", "333");
    objectService.deleteObject("versioned", "b.txt");

    assertEquals(new ObjectStatistics(2, 1, 3, 1, 6, 0, 0, 0), withoutHeapCounts(manager.statistics()));
  }

  /**
   * The data of a service without the counts of what its metadata holds in heap, which depend on what the store of
   * the service happens to have read; {@code ObjectStatisticsTest} covers those.
   */
  private static ObjectStatistics withoutHeapCounts(ObjectStatistics statistics) {
    return new ObjectStatistics(statistics.buckets(), statistics.objects(), statistics.objectVersions(),
        statistics.deleteMarkers(), statistics.objectBytes(), statistics.multipartUploads(), 0, 0);
  }

  private static void putObject(ObjectService objectService, String bucket, String key, String content) {
    byte[] bytes = content.getBytes();
    objectService.putObject(bucket, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes))
        .size(bytes.length)
        .build());
  }

}
