package com.robothy.s3.core.storage.s3vectors;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemVectorStorageTest {

  @TempDir
  Path tempDir;

  private VectorStorage vectorStorage;

  @BeforeEach
  void setUp() {
    vectorStorage = VectorStorage.createFileSystem(tempDir, 3); // Cache size of 3
  }

  @Test
  void testPutAndGetVectorData() {
    float[] vectorData = {1.0f, 2.0f, 3.0f, 4.0f};
    
    Long storageId = vectorStorage.putVectorData(vectorData);
    assertNotNull(storageId);
    assertTrue(storageId > 0);
    
    float[] retrievedData = vectorStorage.getVectorData(storageId);
    assertArrayEquals(vectorData, retrievedData);
  }

  @Test
  void testPutVectorDataCreatesFile() throws IOException {
    float[] vectorData = {1.0f, 2.0f, 3.0f};
    
    Long storageId = vectorStorage.putVectorData(vectorData);
    
    Path expectedFile = ((FileSystemVectorStorage) vectorStorage).vectorFile(storageId);
    assertTrue(Files.isRegularFile(expectedFile));
    assertEquals(tempDir, expectedFile.getParent().getParent().getParent(), "The file is in two levels of subdirectories.");
  }

  @Test
  void testGetNonExistentVector() {
    float[] result = vectorStorage.getVectorData(999L);
    assertNull(result);
  }

  @Test
  void testGetNullStorageId() {
    float[] result = vectorStorage.getVectorData(null);
    assertNull(result);
  }

  @Test
  void testPutNullVectorData() {
    assertThrows(IllegalArgumentException.class, () -> {
      vectorStorage.putVectorData(null);
    });
  }

  @Test
  void testPutEmptyVectorData() {
    assertThrows(IllegalArgumentException.class, () -> {
      vectorStorage.putVectorData(new float[0]);
    });
  }

  @Test
  void testPutInvalidVectorData() {
    float[] invalidData = {1.0f, Float.NaN, 3.0f};
    assertThrows(IllegalArgumentException.class, () -> {
      vectorStorage.putVectorData(invalidData);
    });
    
    float[] infiniteData = {1.0f, Float.POSITIVE_INFINITY, 3.0f};
    assertThrows(IllegalArgumentException.class, () -> {
      vectorStorage.putVectorData(infiniteData);
    });
  }

  @Test
  void testDeleteVectorData() {
    float[] vectorData = {1.0f, 2.0f, 3.0f};
    Long storageId = vectorStorage.putVectorData(vectorData);
    
    assertTrue(vectorStorage.vectorDataExists(storageId));
    
    boolean deleted = vectorStorage.deleteVectorData(storageId);
    assertTrue(deleted);
    
    assertFalse(vectorStorage.vectorDataExists(storageId));
    assertNull(vectorStorage.getVectorData(storageId));
  }

  @Test
  void testDeleteNonExistentVector() {
    boolean deleted = vectorStorage.deleteVectorData(999L);
    assertFalse(deleted);
  }

  @Test
  void testDeleteNullStorageId() {
    boolean deleted = vectorStorage.deleteVectorData(null);
    assertFalse(deleted);
  }

  @Test
  void testVectorDataExists() {
    float[] vectorData = {1.0f, 2.0f, 3.0f};
    Long storageId = vectorStorage.putVectorData(vectorData);
    
    assertTrue(vectorStorage.vectorDataExists(storageId));
    assertFalse(vectorStorage.vectorDataExists(999L));
    assertFalse(vectorStorage.vectorDataExists(null));
  }

  @Test
  void testGetStoredVectorCount() {
    assertEquals(0, vectorStorage.getStoredVectorCount());

    Long vector1Id = vectorStorage.putVectorData(new float[] {1.0f, 2.0f});
    assertEquals(1, vectorStorage.getStoredVectorCount());
    
    vectorStorage.putVectorData(new float[]{3.0f, 4.0f});
    assertEquals(2, vectorStorage.getStoredVectorCount());
    
    vectorStorage.deleteVectorData(vector1Id);
    assertEquals(1, vectorStorage.getStoredVectorCount());
  }

  @Test
  void testGetVectorDataSize() {
    float[] vectorData = {1.0f, 2.0f, 3.0f, 4.0f}; // 4 floats = 16 bytes
    Long storageId = vectorStorage.putVectorData(vectorData);
    
    assertEquals(16L, vectorStorage.getVectorDataSize(storageId));
    assertEquals(-1L, vectorStorage.getVectorDataSize(999L));
    assertEquals(-1L, vectorStorage.getVectorDataSize(null));
  }

  @Test
  void testLRUCacheEviction() {
    FileSystemVectorStorage fsStorage = (FileSystemVectorStorage) vectorStorage;
    
    // Add 5 vectors to exceed cache size of 3
    Long id1 = vectorStorage.putVectorData(new float[]{1.0f});
    Long id2 = vectorStorage.putVectorData(new float[]{2.0f});
    Long id3 = vectorStorage.putVectorData(new float[]{3.0f});
    Long id4 = vectorStorage.putVectorData(new float[]{4.0f});
    Long id5 = vectorStorage.putVectorData(new float[]{5.0f});
    
    // Cache should contain at most 3 vectors
    assertEquals(3, fsStorage.getCachedVectorCount());
    
    // Access id1 to make it recently used
    vectorStorage.getVectorData(id1);
    
    // Add another vector
    vectorStorage.putVectorData(new float[]{6.0f});
    
    // Cache should still be 3
    assertEquals(3, fsStorage.getCachedVectorCount());
  }

  @Test
  void testCacheHitAndMiss() {
    FileSystemVectorStorage fsStorage = (FileSystemVectorStorage) vectorStorage;
    
    float[] vectorData = {1.0f, 2.0f, 3.0f};
    Long storageId = vectorStorage.putVectorData(vectorData);
    
    // First access should be from cache (put operation caches the data)
    float[] result1 = vectorStorage.getVectorData(storageId);
    assertArrayEquals(vectorData, result1);
    
    // Clear cache to force file system read
    fsStorage.clearCache();
    assertEquals(0, fsStorage.getCachedVectorCount());
    
    // Second access should load from file system
    float[] result2 = vectorStorage.getVectorData(storageId);
    assertArrayEquals(vectorData, result2);
    
    // Now it should be in cache again
    assertEquals(1, fsStorage.getCachedVectorCount());
  }

  @Test
  void testConcurrentAccess() throws Exception {
    int threadCount = 10;
    int vectorsPerThread = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    
    List<Future<List<Long>>> futures = new ArrayList<>();
    
    // Submit tasks to store vectors concurrently
    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      Future<List<Long>> future = executor.submit(() -> {
        List<Long> storageIds = new ArrayList<>();
        for (int i = 0; i < vectorsPerThread; i++) {
          float[] vectorData = {threadId + i * 0.1f, threadId + i * 0.2f};
          Long storageId = vectorStorage.putVectorData(vectorData);
          storageIds.add(storageId);
        }
        return storageIds;
      });
      futures.add(future);
    }
    
    // Collect all storage IDs
    List<Long> allStorageIds = new ArrayList<>();
    for (Future<List<Long>> future : futures) {
      allStorageIds.addAll(future.get());
    }
    
    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    
    // Verify all vectors were stored
    assertEquals(threadCount * vectorsPerThread, allStorageIds.size());
    assertEquals(threadCount * vectorsPerThread, vectorStorage.getStoredVectorCount());
    
    // Verify all vectors can be retrieved
    for (Long storageId : allStorageIds) {
      float[] vectorData = vectorStorage.getVectorData(storageId);
      assertNotNull(vectorData);
      assertEquals(2, vectorData.length);
    }
  }

  @Test
  void testPersistenceAcrossInstances() {
    // Store vectors with first instance
    Long id1 = vectorStorage.putVectorData(new float[]{1.0f, 2.0f});
    Long id2 = vectorStorage.putVectorData(new float[]{3.0f, 4.0f});
    
    // Create new instance with same directory
    VectorStorage newStorage = VectorStorage.createFileSystem(tempDir, 2);
    
    // Verify vectors are still accessible
    assertArrayEquals(new float[]{1.0f, 2.0f}, newStorage.getVectorData(id1));
    assertArrayEquals(new float[]{3.0f, 4.0f}, newStorage.getVectorData(id2));
    assertEquals(2, newStorage.getStoredVectorCount());
  }

  @Test
  void testZeroCacheSize() {
    VectorStorage noCacheStorage = VectorStorage.createFileSystem(tempDir, 0);
    FileSystemVectorStorage fsStorage = (FileSystemVectorStorage) noCacheStorage;
    
    Long storageId = noCacheStorage.putVectorData(new float[]{1.0f, 2.0f});
    
    // Should not cache anything
    assertEquals(0, fsStorage.getCachedVectorCount());
    
    // Should still be able to retrieve data from file
    float[] vectorData = noCacheStorage.getVectorData(storageId);
    assertArrayEquals(new float[]{1.0f, 2.0f}, vectorData);
    
    // Still no cache
    assertEquals(0, fsStorage.getCachedVectorCount());
  }

  @Test
  void testLargeVectorData() {
    // Test with a large vector (10,000 dimensions)
    float[] largeVector = new float[10000];
    for (int i = 0; i < largeVector.length; i++) {
      largeVector[i] = i * 0.001f;
    }
    
    Long storageId = vectorStorage.putVectorData(largeVector);
    float[] retrievedVector = vectorStorage.getVectorData(storageId);
    
    assertArrayEquals(largeVector, retrievedVector);
    assertEquals(40000L, vectorStorage.getVectorDataSize(storageId)); // 10,000 * 4 bytes
  }

  @Test
  void writesVectorsWithoutLeavingTemporaryFiles() throws IOException {
    for (int i = 0; i < 10; i++) {
      vectorStorage.putVectorData(new float[] {i, i});
    }
    try (var files = Files.walk(tempDir)) {
      assertTrue(files.filter(Files::isRegularFile).allMatch(file -> file.getFileName().toString().matches("[0-9]+")),
          "Only complete vector files are left in the directory.");
    }
    assertEquals(10, vectorStorage.getStoredVectorCount());
  }

  /**
   * A process that died while writing a vector left a partial temporary file, which a new storage deletes; the
   * complete vectors are kept.
   */
  @Test
  void deletesTheTemporaryFilesThatAProcessLeftBehind() throws IOException {
    Long complete = vectorStorage.putVectorData(new float[] {1.0f, 2.0f});
    Path partial = Files.write(tempDir.resolve(".42.0000.tmp"), new byte[] {0, 0});

    VectorStorage reopened = VectorStorage.createFileSystem(tempDir, 3);

    assertFalse(Files.exists(partial));
    assertArrayEquals(new float[] {1.0f, 2.0f}, reopened.getVectorData(complete));
    assertEquals(1, reopened.getStoredVectorCount());
  }

  @Test
  void aReadOnlyStorageNeitherCreatesNorChangesItsDirectory() throws IOException {
    Path missing = tempDir.resolve("missing");
    VectorStorage readOnly = VectorStorage.createReadOnlyFileSystem(missing, 3);
    assertFalse(Files.exists(missing));
    assertNull(readOnly.getVectorData(1L));
    assertFalse(readOnly.vectorDataExists(1L));
    assertEquals(0, readOnly.getStoredVectorCount());

    Long id = vectorStorage.putVectorData(new float[] {1.0f, 2.0f});
    Path leftover = Files.write(tempDir.resolve(".42.0000.tmp"), new byte[] {0});
    VectorStorage existing = VectorStorage.createReadOnlyFileSystem(tempDir, 3);

    assertTrue(Files.exists(leftover), "The temporary file may belong to a service that is writing.");
    assertArrayEquals(new float[] {1.0f, 2.0f}, existing.getVectorData(id));
    assertEquals(8, existing.getVectorDataSize(id));
    assertThrows(UnsupportedOperationException.class, () -> existing.putVectorData(new float[] {1.0f}));
    assertThrows(UnsupportedOperationException.class, () -> existing.deleteVectorData(id));
    assertTrue(Files.exists(((FileSystemVectorStorage) vectorStorage).vectorFile(id)));
  }

  /**
   * The vector files are spread over two levels of subdirectories, like the object files of a storage. The layout
   * decides where the vectors that are already stored are found, so it must never change.
   */
  @Test
  void storesVectorsInTwoLevelsOfSubdirectories() throws IOException {
    FileSystemVectorStorage storage = (FileSystemVectorStorage) vectorStorage;
    assertEquals(tempDir.resolve("b4").resolve("56").resolve("1"), storage.vectorFile(1L));
    assertEquals(tempDir.resolve("81").resolve("08").resolve("42"), storage.vectorFile(42L));

    Long id = storage.putVectorData(new float[] {1.0f, 2.0f});
    assertEquals(List.of(storage.vectorFile(id)), vectorFiles());
  }

  /**
   * A writable storage moves the vector files of the flat layout of a LocalS3 before 2.5 into their subdirectories,
   * and leaves every other entry of the directory alone.
   */
  @Test
  void movesTheVectorFilesOfTheFlatLayout() throws IOException {
    Long id = vectorStorage.putVectorData(new float[] {1.0f, 2.0f});
    Path sharded = ((FileSystemVectorStorage) vectorStorage).vectorFile(id);
    Path flat = Files.move(sharded, tempDir.resolve(String.valueOf(id)));
    Path notes = Files.writeString(tempDir.resolve("notes.txt"), "not a vector");

    FileSystemVectorStorage reopened = new FileSystemVectorStorage(tempDir, 0);

    assertFalse(Files.exists(flat));
    assertTrue(Files.isRegularFile(sharded));
    assertTrue(Files.exists(notes));
    assertArrayEquals(new float[] {1.0f, 2.0f}, reopened.getVectorData(id));
    assertEquals(1, reopened.getStoredVectorCount());
  }

  /**
   * A vector file of the flat layout that appears after the storage was created, e.g. one that a process which died
   * during the move left, is still found, counted and deleted.
   */
  @Test
  void findsAndDeletesAVectorFileOfTheFlatLayout() throws IOException {
    FileSystemVectorStorage storage = new FileSystemVectorStorage(tempDir, 0);
    Long id = storage.putVectorData(new float[] {3.0f});
    Path flat = Files.move(storage.vectorFile(id), tempDir.resolve(String.valueOf(id)));

    assertTrue(storage.vectorDataExists(id));
    assertArrayEquals(new float[] {3.0f}, storage.getVectorData(id));
    assertEquals(4, storage.getVectorDataSize(id));
    assertEquals(1, storage.getStoredVectorCount());

    assertTrue(storage.deleteVectorData(id));
    assertFalse(Files.exists(flat));
    assertFalse(storage.vectorDataExists(id));
  }

  /**
   * A read-only storage reads the vectors of both layouts without moving the files of the flat layout.
   */
  @Test
  void aReadOnlyStorageReadsBothLayoutsWithoutMovingFiles() throws IOException {
    Long sharded = vectorStorage.putVectorData(new float[] {1.0f});
    Long flatId = vectorStorage.putVectorData(new float[] {2.0f});
    Path flat = Files.move(((FileSystemVectorStorage) vectorStorage).vectorFile(flatId),
        tempDir.resolve(String.valueOf(flatId)));

    VectorStorage readOnly = VectorStorage.createReadOnlyFileSystem(tempDir, 0);

    assertArrayEquals(new float[] {1.0f}, readOnly.getVectorData(sharded));
    assertArrayEquals(new float[] {2.0f}, readOnly.getVectorData(flatId));
    assertEquals(2, readOnly.getStoredVectorCount());
    assertTrue(Files.exists(flat), "The flat layout isn't changed.");
  }

  private List<Path> vectorFiles() throws IOException {
    try (var files = Files.walk(tempDir)) {
      return files.filter(Files::isRegularFile).toList();
    }
  }
}
