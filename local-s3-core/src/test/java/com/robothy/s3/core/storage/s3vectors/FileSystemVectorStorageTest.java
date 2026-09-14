package com.robothy.s3.core.storage.s3vectors;

import static org.junit.jupiter.api.Assertions.*;

import com.robothy.s3.core.storage.ShardedFileLayout;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.FloatBuffer;
import java.nio.ReadOnlyBufferException;
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
    vectorStorage = VectorStorage.createFileSystem(tempDir);
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
  void getVectorDataReturnsACopy() {
    Long storageId = vectorStorage.putVectorData(new float[] {1.0f, 2.0f});

    vectorStorage.getVectorData(storageId)[0] = 42.0f;

    assertArrayEquals(new float[] {1.0f, 2.0f}, vectorStorage.getVectorData(storageId));
  }

  @Test
  void getVectorDataViewReadsTheStoredDataWithoutCopying() {
    vectorStorage.putVectorData(new float[] {9.0f, 9.0f, 9.0f});
    Long storageId = vectorStorage.putVectorData(new float[] {1.0f, 2.0f, 3.0f});

    FloatBuffer view = vectorStorage.getVectorDataView(storageId);

    assertEquals(0, view.position());
    assertEquals(3, view.limit());
    assertEquals(1.0f, view.get(0));
    assertEquals(3.0f, view.get(2));
    assertTrue(view.isReadOnly());
    assertThrows(ReadOnlyBufferException.class, () -> view.put(0, 42.0f));
    assertNull(vectorStorage.getVectorDataView(999L));
    assertNull(vectorStorage.getVectorDataView(null));
  }

  /**
   * The vectors of a dimension are kept in one file of fixed-length records: a header, and the storage ID and the
   * values of each vector.
   */
  @Test
  void storesTheVectorsOfADimensionInOneFileOfFixedLengthRecords() throws IOException {
    FileSystemVectorStorage storage = (FileSystemVectorStorage) vectorStorage;
    storage.putVectorData(new float[] {1.0f, 2.0f, 3.0f});
    storage.putVectorData(new float[] {4.0f, 5.0f, 6.0f});
    storage.putVectorData(new float[] {1.0f, 2.0f});

    assertEquals(List.of(tempDir.resolve("vectors-2.vec"), tempDir.resolve("vectors-3.vec")), vectorFiles());
    assertEquals(FileSystemVectorStorage.HEADER_BYTES + 2 * (8 + 3 * 4), Files.size(storage.vectorFile(3)));
    assertEquals(FileSystemVectorStorage.HEADER_BYTES + (8 + 2 * 4), Files.size(storage.vectorFile(2)));
  }

  /**
   * A new vector takes the record of a deleted vector of the same dimension, so the file doesn't grow.
   */
  @Test
  void reusesTheRecordOfADeletedVector() throws IOException {
    FileSystemVectorStorage storage = (FileSystemVectorStorage) vectorStorage;
    Long deleted = storage.putVectorData(new float[] {1.0f, 2.0f});
    Long kept = storage.putVectorData(new float[] {3.0f, 4.0f});
    long size = Files.size(storage.vectorFile(2));

    assertTrue(storage.deleteVectorData(deleted));
    Long reusing = storage.putVectorData(new float[] {5.0f, 6.0f});

    assertEquals(size, Files.size(storage.vectorFile(2)));
    assertNull(storage.getVectorData(deleted));
    assertArrayEquals(new float[] {3.0f, 4.0f}, storage.getVectorData(kept));
    assertArrayEquals(new float[] {5.0f, 6.0f}, storage.getVectorData(reusing));

    VectorStorage reopened = VectorStorage.createFileSystem(tempDir);
    assertEquals(2, reopened.getStoredVectorCount());
    assertFalse(reopened.vectorDataExists(deleted));
    assertArrayEquals(new float[] {3.0f, 4.0f}, reopened.getVectorData(kept));
    assertArrayEquals(new float[] {5.0f, 6.0f}, reopened.getVectorData(reusing));
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
    assertFalse(vectorStorage.deleteVectorData(storageId));
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
  void testConcurrentAccess() throws Exception {
    int threadCount = 10;
    int vectorsPerThread = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    List<Future<List<Long>>> futures = new ArrayList<>();

    // Submit tasks to store, read and delete vectors concurrently
    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      Future<List<Long>> future = executor.submit(() -> {
        List<Long> storageIds = new ArrayList<>();
        for (int i = 0; i < vectorsPerThread; i++) {
          float[] vectorData = {threadId, i};
          Long storageId = vectorStorage.putVectorData(vectorData);
          assertArrayEquals(vectorData, vectorStorage.getVectorData(storageId));
          Long deleted = vectorStorage.putVectorData(new float[] {-1.0f, -1.0f});
          assertTrue(vectorStorage.deleteVectorData(deleted));
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

    // Verify all vectors can be retrieved, also after a restart
    VectorStorage reopened = VectorStorage.createFileSystem(tempDir);
    assertEquals(threadCount * vectorsPerThread, reopened.getStoredVectorCount());
    for (Long storageId : allStorageIds) {
      assertArrayEquals(vectorStorage.getVectorData(storageId), reopened.getVectorData(storageId));
      assertEquals(2, reopened.getVectorData(storageId).length);
    }
  }

  @Test
  void testPersistenceAcrossInstances() {
    // Store vectors with first instance
    Long id1 = vectorStorage.putVectorData(new float[]{1.0f, 2.0f});
    Long id2 = vectorStorage.putVectorData(new float[]{3.0f, 4.0f});
    Long id3 = vectorStorage.putVectorData(new float[]{5.0f, 6.0f, 7.0f});

    // Create new instance with same directory
    VectorStorage newStorage = VectorStorage.createFileSystem(tempDir);

    // Verify vectors are still accessible
    assertArrayEquals(new float[]{1.0f, 2.0f}, newStorage.getVectorData(id1));
    assertArrayEquals(new float[]{3.0f, 4.0f}, newStorage.getVectorData(id2));
    assertArrayEquals(new float[]{5.0f, 6.0f, 7.0f}, newStorage.getVectorData(id3));
    assertEquals(3, newStorage.getStoredVectorCount());
  }

  /**
   * The vectors of a dimension take more than one array in memory.
   */
  @Test
  void storesManyVectors() {
    List<Long> storageIds = new ArrayList<>();
    for (int i = 0; i < 3000; i++) {
      storageIds.add(vectorStorage.putVectorData(new float[] {i, -i}));
    }

    VectorStorage reopened = VectorStorage.createFileSystem(tempDir);
    assertEquals(3000, reopened.getStoredVectorCount());
    for (int i = 0; i < 3000; i++) {
      assertArrayEquals(new float[] {i, -i}, reopened.getVectorData(storageIds.get(i)));
    }
  }

  @Test
  void testLargeVectorData() {
    // Test with a large vector (10,000 dimensions)
    float[] largeVector = new float[10000];
    for (int i = 0; i < largeVector.length; i++) {
      largeVector[i] = i * 0.001f;
    }

    Long storageId = vectorStorage.putVectorData(largeVector);
    Long another = vectorStorage.putVectorData(largeVector);
    float[] retrievedVector = vectorStorage.getVectorData(storageId);

    assertArrayEquals(largeVector, retrievedVector);
    assertEquals(40000L, vectorStorage.getVectorDataSize(storageId)); // 10,000 * 4 bytes
    assertArrayEquals(largeVector, VectorStorage.createFileSystem(tempDir).getVectorData(another));
  }

  @Test
  void writesVectorsWithoutLeavingTemporaryFiles() throws IOException {
    for (int i = 0; i < 10; i++) {
      vectorStorage.putVectorData(new float[] {i, i});
    }
    assertEquals(List.of(tempDir.resolve("vectors-2.vec")), vectorFiles(),
        "Only complete vector files are left in the directory.");
    assertEquals(10, vectorStorage.getStoredVectorCount());
  }

  /**
   * A process that died while writing a file left a partial temporary file, which a new storage deletes; the
   * complete vectors are kept.
   */
  @Test
  void deletesTheTemporaryFilesThatAProcessLeftBehind() throws IOException {
    Long complete = vectorStorage.putVectorData(new float[] {1.0f, 2.0f});
    Path partial = Files.write(tempDir.resolve(".42.0000.tmp"), new byte[] {0, 0});

    VectorStorage reopened = VectorStorage.createFileSystem(tempDir);

    assertFalse(Files.exists(partial));
    assertArrayEquals(new float[] {1.0f, 2.0f}, reopened.getVectorData(complete));
    assertEquals(1, reopened.getStoredVectorCount());
  }

  /**
   * A system crash while a vector was appended may leave a partial record at the end of a file, which is dropped.
   */
  @Test
  void dropsAPartialRecordAtTheEndOfAFile() throws IOException {
    FileSystemVectorStorage storage = (FileSystemVectorStorage) vectorStorage;
    Long complete = storage.putVectorData(new float[] {1.0f, 2.0f});
    long size = Files.size(storage.vectorFile(2));
    Files.write(storage.vectorFile(2), new byte[] {1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);

    VectorStorage readOnly = VectorStorage.createReadOnlyFileSystem(tempDir);
    assertEquals(1, readOnly.getStoredVectorCount());
    assertEquals(size + 3, Files.size(storage.vectorFile(2)), "A read-only storage doesn't change the file.");

    VectorStorage reopened = VectorStorage.createFileSystem(tempDir);
    assertEquals(size, Files.size(storage.vectorFile(2)));
    assertArrayEquals(new float[] {1.0f, 2.0f}, reopened.getVectorData(complete));
    Long added = reopened.putVectorData(new float[] {3.0f, 4.0f});
    assertArrayEquals(new float[] {3.0f, 4.0f}, VectorStorage.createFileSystem(tempDir).getVectorData(added));
  }

  /**
   * A file that isn't a valid vector file fails the storage, rather than losing its vectors silently.
   */
  @Test
  void failsOnACorruptVectorFile() throws IOException {
    FileSystemVectorStorage storage = (FileSystemVectorStorage) vectorStorage;
    storage.putVectorData(new float[] {1.0f, 2.0f});
    try (RandomAccessFile file = new RandomAccessFile(storage.vectorFile(2).toFile(), "rw")) {
      file.write(new byte[] {'X'});
    }
    assertThrows(IllegalStateException.class, () -> VectorStorage.createFileSystem(tempDir));
    assertThrows(IllegalStateException.class, () -> VectorStorage.createReadOnlyFileSystem(tempDir));

    Files.write(tempDir.resolve("vectors-3.vec"), new byte[] {1, 2});
    Files.delete(storage.vectorFile(2));
    assertThrows(IllegalStateException.class, () -> VectorStorage.createFileSystem(tempDir));
  }

  @Test
  void aReadOnlyStorageNeitherCreatesNorChangesItsDirectory() throws IOException {
    Path missing = tempDir.resolve("missing");
    VectorStorage readOnly = VectorStorage.createReadOnlyFileSystem(missing);
    assertFalse(Files.exists(missing));
    assertNull(readOnly.getVectorData(1L));
    assertFalse(readOnly.vectorDataExists(1L));
    assertEquals(0, readOnly.getStoredVectorCount());

    Long id = vectorStorage.putVectorData(new float[] {1.0f, 2.0f});
    Path leftover = Files.write(tempDir.resolve(".42.0000.tmp"), new byte[] {0});
    VectorStorage existing = VectorStorage.createReadOnlyFileSystem(tempDir);

    assertTrue(Files.exists(leftover), "The temporary file may belong to a service that is writing.");
    assertArrayEquals(new float[] {1.0f, 2.0f}, existing.getVectorData(id));
    assertEquals(8, existing.getVectorDataSize(id));
    assertThrows(UnsupportedOperationException.class, () -> existing.putVectorData(new float[] {1.0f}));
    assertThrows(UnsupportedOperationException.class, () -> existing.deleteVectorData(id));
    assertArrayEquals(new float[] {1.0f, 2.0f}, vectorStorage.getVectorData(id));
  }

  /**
   * A writable storage imports the vector files of a LocalS3 before 2.5, one file per vector in two levels of
   * subdirectories or directly in the directory, and deletes them. Every other entry of the directory is left alone.
   */
  @Test
  void importsTheVectorFilesOfTheFormerLayouts() throws IOException {
    Path sharded = writeLegacyVector(ShardedFileLayout.shardedPath(tempDir, 1001L), 1.0f, 2.0f);
    Path flat = writeLegacyVector(ShardedFileLayout.flatPath(tempDir, 1002L), 3.0f, 4.0f, 5.0f);
    Path notes = Files.writeString(tempDir.resolve("notes.txt"), "not a vector");

    VectorStorage imported = VectorStorage.createFileSystem(tempDir);

    assertFalse(Files.exists(sharded));
    assertFalse(Files.exists(flat));
    assertFalse(Files.exists(sharded.getParent().getParent()), "The empty subdirectories are deleted.");
    assertTrue(Files.exists(notes));
    assertArrayEquals(new float[] {1.0f, 2.0f}, imported.getVectorData(1001L));
    assertArrayEquals(new float[] {3.0f, 4.0f, 5.0f}, imported.getVectorData(1002L));
    assertEquals(2, imported.getStoredVectorCount());

    VectorStorage reopened = VectorStorage.createFileSystem(tempDir);
    assertArrayEquals(new float[] {1.0f, 2.0f}, reopened.getVectorData(1001L));
    assertArrayEquals(new float[] {3.0f, 4.0f, 5.0f}, reopened.getVectorData(1002L));
    assertTrue(reopened.deleteVectorData(1001L));
    assertFalse(VectorStorage.createFileSystem(tempDir).vectorDataExists(1001L));
  }

  /**
   * A process that died during the import left a vector file that was imported already, which is only deleted.
   */
  @Test
  void finishesAnInterruptedImport() throws IOException {
    Long id = vectorStorage.putVectorData(new float[] {1.0f, 2.0f});
    Path leftover = writeLegacyVector(ShardedFileLayout.shardedPath(tempDir, id), 1.0f, 2.0f);

    VectorStorage reopened = VectorStorage.createFileSystem(tempDir);

    assertFalse(Files.exists(leftover));
    assertEquals(1, reopened.getStoredVectorCount());
    assertArrayEquals(new float[] {1.0f, 2.0f}, reopened.getVectorData(id));
  }

  /**
   * A read-only storage reads the vector files of the former layouts without importing them.
   */
  @Test
  void aReadOnlyStorageReadsTheFormerLayoutsWithoutChangingThem() throws IOException {
    Long current = vectorStorage.putVectorData(new float[] {1.0f});
    Path sharded = writeLegacyVector(ShardedFileLayout.shardedPath(tempDir, 1001L), 2.0f);
    Path flat = writeLegacyVector(ShardedFileLayout.flatPath(tempDir, 1002L), 3.0f, 4.0f);

    VectorStorage readOnly = VectorStorage.createReadOnlyFileSystem(tempDir);

    assertArrayEquals(new float[] {1.0f}, readOnly.getVectorData(current));
    assertArrayEquals(new float[] {2.0f}, readOnly.getVectorData(1001L));
    assertArrayEquals(new float[] {3.0f, 4.0f}, readOnly.getVectorData(1002L));
    assertEquals(3.0f, readOnly.getVectorDataView(1002L).get(0));
    assertEquals(3, readOnly.getStoredVectorCount());
    assertTrue(Files.exists(sharded), "The former layouts aren't changed.");
    assertTrue(Files.exists(flat), "The former layouts aren't changed.");
  }

  /**
   * Write a vector file of a LocalS3 before 2.5: the dimension and the values, big-endian.
   */
  private static Path writeLegacyVector(Path file, float... values) throws IOException {
    Files.createDirectories(file.getParent());
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
      out.writeInt(values.length);
      for (float value : values) {
        out.writeFloat(value);
      }
    }
    return file;
  }

  private List<Path> vectorFiles() throws IOException {
    try (var files = Files.walk(tempDir)) {
      return files.filter(Files::isRegularFile).sorted().toList();
    }
  }
}
