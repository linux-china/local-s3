package com.robothy.s3.core.storage.s3vectors;

import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.core.util.PathUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.slf4j.Slf4j;

/**
 * File system implementation of {@linkplain VectorStorage} with LRU memory caching.
 * Stores vector data as individual files in the specified directory.
 * Uses least recently used (LRU) eviction strategy for memory cache.
 *
 * <p>A vector is written to a temporary file first, which is then moved to the vector file, so that a process that
 * dies while writing leaves either the whole vector or no vector file at all, never a partial one. The temporary files
 * left behind by such a process are deleted when the storage is created.
 *
 * <p>A read-only storage, e.g. over the initial data of an {@code IN_MEMORY} service, neither creates nor changes
 * anything in its directory, which may not even exist, and rejects writes.
 */
@Slf4j
class FileSystemVectorStorage implements VectorStorage {

  /**
   * Suffix of the temporary files that vectors are written to.
   */
  private static final String TEMP_FILE_SUFFIX = ".tmp";

  private final Path storageDirectory;
  private final int maxCachedVectorCount;
  private final boolean readOnly;
  private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

  // LRU cache using LinkedHashMap with access order
  private final Map<Long, float[]> memoryCache;

  /**
   * Create a {@linkplain FileSystemVectorStorage} with specified directory and cache size.
   *
   * @param storageDirectory     the directory to store vector files
   * @param maxCachedVectorCount maximum number of vectors to keep in memory cache
   */
  FileSystemVectorStorage(Path storageDirectory, int maxCachedVectorCount) {
    this(storageDirectory, maxCachedVectorCount, false);
  }

  /**
   * Create a {@linkplain FileSystemVectorStorage} with specified directory and cache size.
   *
   * @param storageDirectory     the directory to store vector files
   * @param maxCachedVectorCount maximum number of vectors to keep in memory cache
   * @param readOnly             whether the storage only reads the vectors of the directory, which is then neither
   *                             created nor cleaned
   */
  FileSystemVectorStorage(Path storageDirectory, int maxCachedVectorCount, boolean readOnly) {
    this.storageDirectory = storageDirectory;
    this.maxCachedVectorCount = Math.max(0, maxCachedVectorCount);
    this.readOnly = readOnly;

    // Create LRU cache with access order
    this.memoryCache = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, float[]> eldest) {
        return size() > FileSystemVectorStorage.this.maxCachedVectorCount;
      }
    };

    if (readOnly) {
      log.info("Read-only FileSystemVectorStorage initialized with directory: {}, max cache size: {}",
          storageDirectory, maxCachedVectorCount);
      return;
    }
    try {
      // Create storage directory if it doesn't exist
      Files.createDirectories(storageDirectory);
      deleteTempFiles();

      log.info("FileSystemVectorStorage initialized with directory: {}, max cache size: {}",
          storageDirectory, maxCachedVectorCount);
    } catch (IOException e) {
      throw new RuntimeException("Failed to initialize storage directory: " + storageDirectory, e);
    }
  }

  @Override
  public Long putVectorData(float[] vectorData) {
    ensureWritable();
    if (vectorData == null || vectorData.length == 0) {
      throw new IllegalArgumentException("Vector data cannot be null or empty");
    }

    // Validate float32 values
    for (int i = 0; i < vectorData.length; i++) {
      float value = vectorData[i];
      if (!Float.isFinite(value)) {
        throw new IllegalArgumentException("Vector data contains invalid value at index " + i + ": " + value);
      }
    }

    Long storageId = IdUtils.defaultGenerator().nextId();
    Path vectorFile = storageDirectory.resolve(String.valueOf(storageId));

    try {
      // Write vector data to file
      writeVectorToFile(vectorFile, vectorData);

      // Add to cache
      cacheLock.writeLock().lock();
      try {
        memoryCache.put(storageId, vectorData.clone());
      } finally {
        cacheLock.writeLock().unlock();
      }

      log.debug("Stored vector data with storage ID: {}, dimensions: {}, file: {}",
          storageId, vectorData.length, vectorFile);
      return storageId;

    } catch (IOException e) {
      log.error("Failed to write vector data to file: {}", vectorFile, e);
      throw new RuntimeException("Failed to store vector data", e);
    }
  }

  @Override
  public float[] getVectorData(Long storageId) {
    if (storageId == null) {
      return null;
    }

    // Try to get from cache first
    cacheLock.writeLock().lock();
    try {
      float[] cachedData = memoryCache.get(storageId);
      if (cachedData != null) {
        return cachedData.clone(); // Return defensive copy
      }
    } finally {
      cacheLock.writeLock().unlock();
    }

    // Load from file if not in cache
    Path vectorFile = storageDirectory.resolve(String.valueOf(storageId));

    try {
      if (!Files.exists(vectorFile)) {
        return null;
      }

      float[] vectorData = readVectorFromFile(vectorFile);

      // Add to cache
      cacheLock.writeLock().lock();
      try {
        memoryCache.put(storageId, vectorData.clone());
      } finally {
        cacheLock.writeLock().unlock();
      }

      return vectorData;

    } catch (IOException e) {
      log.error("Failed to read vector data from file: {}", vectorFile, e);
      return null;
    }
  }

  @Override
  public boolean deleteVectorData(Long storageId) {
    ensureWritable();
    if (storageId == null) {
      return false;
    }

    Path vectorFile = storageDirectory.resolve(String.valueOf(storageId));
    boolean deleted = false;

    try {
      // Remove from file system
      if (Files.exists(vectorFile)) {
        Files.delete(vectorFile);
        deleted = true;
        log.debug("Deleted vector file: {}", vectorFile);
      }

      // Remove from cache
      cacheLock.writeLock().lock();
      try {
        memoryCache.remove(storageId);
      } finally {
        cacheLock.writeLock().unlock();
      }

      return deleted;

    } catch (IOException e) {
      log.error("Failed to delete vector file: {}", vectorFile, e);
      return false;
    }
  }

  @Override
  public boolean vectorDataExists(Long storageId) {
    if (storageId == null) {
      return false;
    }

    // Check memory cache
    cacheLock.readLock().lock();
    try {
      if (memoryCache.containsKey(storageId)) {
        return true;
      }
    } finally {
      cacheLock.readLock().unlock();
    }

    // Check file system
    Path vectorFile = storageDirectory.resolve(String.valueOf(storageId));
    return Files.exists(vectorFile);
  }

  @Override
  public long getStoredVectorCount() {
    if (!Files.isDirectory(storageDirectory)) {
      return 0;
    }
    try (var files = Files.list(storageDirectory)) {
      return files.filter(Files::isRegularFile)
          .filter(file -> {
            try {
              Long.parseLong(file.getFileName().toString());
              return true;
            } catch (NumberFormatException e) {
              return false;
            }
          })
          .count();
    } catch (IOException e) {
      log.error("Failed to count vector files in directory: {}", storageDirectory, e);
      return 0;
    }
  }

  @Override
  public long getVectorDataSize(Long storageId) {
    if (storageId == null) {
      return -1;
    }

    // Try cache first
    cacheLock.readLock().lock();
    try {
      float[] cachedData = memoryCache.get(storageId);
      if (cachedData != null) {
        return cachedData.length * 4L;
      }
    } finally {
      cacheLock.readLock().unlock();
    }

    // Check file system
    Path vectorFile = storageDirectory.resolve(String.valueOf(storageId));
    try {
      if (!Files.exists(vectorFile)) {
        return -1;
      }

      // Read just the dimension count to calculate size
      try (DataInputStream dis = new DataInputStream(Files.newInputStream(vectorFile))) {
        int dimensions = dis.readInt();
        return dimensions * 4L;
      }

    } catch (IOException e) {
      log.error("Failed to get vector data size for storage ID: {}", storageId, e);
      return -1;
    }
  }

  /**
   * Get the current number of vectors in memory cache.
   *
   * @return the number of cached vectors
   */
  public int getCachedVectorCount() {
    cacheLock.readLock().lock();
    try {
      return memoryCache.size();
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  /**
   * Clear the memory cache.
   */
  public void clearCache() {
    cacheLock.writeLock().lock();
    try {
      memoryCache.clear();
      log.debug("Cleared vector memory cache");
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Write a vector to a temporary file of the storage directory, and move it to its file once it is complete.
   */
  private void writeVectorToFile(Path file, float[] vectorData) throws IOException {
    Path temp = storageDirectory.resolve("." + file.getFileName() + "." + UUID.randomUUID() + TEMP_FILE_SUFFIX);
    try {
      try (DataOutputStream dos = new DataOutputStream(
          new BufferedOutputStream(Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW)))) {
        // Write dimensions first
        dos.writeInt(vectorData.length);

        // Write vector data
        for (float value : vectorData) {
          dos.writeFloat(value);
        }
      }
      PathUtils.moveAtomically(temp, file);
    } catch (IOException | RuntimeException e) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  /**
   * Delete the temporary files left behind by a process that died while writing vectors.
   */
  private void deleteTempFiles() throws IOException {
    try (DirectoryStream<Path> tempFiles = Files.newDirectoryStream(storageDirectory, ".*" + TEMP_FILE_SUFFIX)) {
      for (Path tempFile : tempFiles) {
        Files.deleteIfExists(tempFile);
      }
    }
  }

  private void ensureWritable() {
    if (readOnly) {
      throw new UnsupportedOperationException("The vector storage of " + storageDirectory + " is read-only.");
    }
  }

  private float[] readVectorFromFile(Path file) throws IOException {
    // Buffered, so that a float doesn't take a read of the file of its own.
    try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      // Read dimensions
      int dimensions = dis.readInt();

      if (dimensions <= 0) {
        throw new IOException("Invalid vector dimensions: " + dimensions);
      }

      // Read vector data
      float[] vectorData = new float[dimensions];
      for (int i = 0; i < dimensions; i++) {
        vectorData[i] = dis.readFloat();
      }

      return vectorData;
    }
  }
}
