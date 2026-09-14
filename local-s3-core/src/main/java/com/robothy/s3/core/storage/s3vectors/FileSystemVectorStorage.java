package com.robothy.s3.core.storage.s3vectors;

import com.robothy.s3.core.storage.ShardedFileLayout;
import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.core.util.PathUtils;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * File system implementation of {@linkplain VectorStorage}, which keeps the vectors of each dimension in one file of
 * fixed-length records, and every vector in memory, in contiguous {@code float} arrays.
 *
 * <p>The file of the vectors of dimension {@code d} is {@code vectors-<d>.vec}: a header of {@value #HEADER_BYTES}
 * bytes, the magic {@code LS3VECTS}, the version of the format and the dimension, followed by records of
 * {@code 8 + 4 * d} bytes each, the storage ID of the vector and its values as {@code float32}, all little-endian. The
 * record of a vector is found by its offset, and a record whose storage ID is {@code 0} is free: deleting a vector
 * overwrites its storage ID, and a new vector of the same dimension reuses the record. A query thus reads its vectors
 * from memory, without a file or an object per vector, and without copying them, see
 * {@linkplain #getVectorDataView(Long)}.
 *
 * <p>The vectors are read into memory when the storage is created, rather than memory-mapped: a mapped file can't be
 * deleted on Windows until the mapping is garbage collected, and the storage is never closed, so the data directory of
 * a stopped service couldn't be deleted. The memory used is about the size of the files.
 *
 * <p>A vector is written as a free record first, and then its storage ID is written, so that a process that dies while
 * writing leaves either the whole vector or a free record, never a partial vector. The files are written one record
 * at a time and are never rewritten as a whole.
 *
 * <p>A LocalS3 before 2.5 kept every vector in a file of its own, {@code <id>} or {@code ab/cd/<id>}, see
 * {@linkplain ShardedFileLayout}. A writable storage imports such files into the files of their dimensions and deletes
 * them when it is created, so that a process that dies meanwhile leaves every vector in one place or both, and the next
 * storage finishes the import. The temporary files left behind by a process are deleted too.
 *
 * <p>A read-only storage, e.g. over the initial data of an {@code IN_MEMORY} service, neither creates nor changes
 * anything in its directory, which may not even exist: it reads the vectors of either layout, and rejects writes.
 */
@Slf4j
class FileSystemVectorStorage implements VectorStorage {

  /**
   * Suffix of the temporary files that files are written to.
   */
  private static final String TEMP_FILE_SUFFIX = ".tmp";

  private static final Pattern VECTOR_FILE_NAME = Pattern.compile("vectors-([1-9][0-9]{0,8})\\.vec");

  static final int HEADER_BYTES = 16;

  private static final byte[] MAGIC = "LS3VECTS".getBytes(StandardCharsets.US_ASCII);

  private static final int FORMAT_VERSION = 1;

  /**
   * The storage ID of a free record. The generated IDs are timestamps, never 0.
   */
  private static final long FREE = 0L;

  /**
   * The vectors are kept in arrays of at most this many values, or of one vector if it is larger, so that no array
   * needs to be copied to grow, and a storage of a few small vectors takes little memory.
   */
  private static final int MAX_CHUNK_VALUES = 1 << 20;

  private static final int MAX_CHUNK_VECTORS = 1024;

  /**
   * The number of bytes that are read from a file at once when it is loaded.
   */
  private static final int READ_BUFFER_BYTES = 1 << 20;

  private final Path storageDirectory;

  private final boolean readOnly;

  /**
   * Guards {@linkplain #slots} and {@linkplain #columns}. Reads share the lock; the files are written without it.
   */
  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  private final Map<Long, Slot> slots = new HashMap<>();

  private final Map<Integer, Column> columns = new HashMap<>();

  /**
   * Create a {@linkplain FileSystemVectorStorage} and load the vectors of its directory.
   *
   * @param storageDirectory the directory to store vector files
   * @param readOnly         whether the storage only reads the vectors of the directory, which is then neither
   *                         created nor cleaned
   * @throws UncheckedIOException  if the directory can't be read, or a writable one can't be prepared.
   * @throws IllegalStateException if a file of the directory isn't a valid vector file.
   */
  FileSystemVectorStorage(Path storageDirectory, boolean readOnly) {
    this.storageDirectory = storageDirectory;
    this.readOnly = readOnly;

    long start = System.nanoTime();
    try {
      if (readOnly) {
        if (Files.isDirectory(storageDirectory)) {
          loadVectorFiles();
          loadLegacyVectorFiles();
        }
      } else {
        Files.createDirectories(storageDirectory);
        deleteTempFiles();
        loadVectorFiles();
        importLegacyVectorFiles();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load the vectors of " + storageDirectory, e);
    }
    log.info("{}FileSystemVectorStorage initialized with directory: {}, {} vectors loaded in {} ms.",
        readOnly ? "Read-only " : "", storageDirectory, slots.size(), (System.nanoTime() - start) / 1_000_000);
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
    Slot slot = allocate(vectorData.length);
    try {
      store(slot, storageId, vectorData);
    } catch (IOException e) {
      release(slot);
      throw new UncheckedIOException("Failed to store vector data in " + slot.column().file, e);
    }
    publish(storageId, slot);

    log.debug("Stored vector data with storage ID: {}, dimensions: {}", storageId, vectorData.length);
    return storageId;
  }

  @Override
  public float[] getVectorData(Long storageId) {
    if (storageId == null) {
      return null;
    }
    lock.readLock().lock();
    try {
      Slot slot = slots.get(storageId);
      return slot == null ? null
          : Arrays.copyOfRange(slot.values(), slot.offset(), slot.offset() + slot.column().dimension);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public FloatBuffer getVectorDataView(Long storageId) {
    if (storageId == null) {
      return null;
    }
    lock.readLock().lock();
    try {
      Slot slot = slots.get(storageId);
      return slot == null ? null
          : FloatBuffer.wrap(slot.values(), slot.offset(), slot.column().dimension).slice().asReadOnlyBuffer();
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public boolean deleteVectorData(Long storageId) {
    ensureWritable();
    if (storageId == null) {
      return false;
    }

    Slot slot;
    lock.writeLock().lock();
    try {
      slot = slots.remove(storageId);
    } finally {
      lock.writeLock().unlock();
    }
    if (slot == null) {
      return false;
    }

    // The record is only reused once it is free in the file too, so that a vector stored in it isn't freed again.
    try {
      writeStorageId(slot, FREE);
    } catch (IOException e) {
      publish(storageId, slot);
      throw new UncheckedIOException("Failed to delete vector " + storageId + " from " + slot.column().file, e);
    }
    release(slot);
    log.debug("Deleted vector data with storage ID: {}", storageId);
    return true;
  }

  @Override
  public boolean vectorDataExists(Long storageId) {
    if (storageId == null) {
      return false;
    }
    lock.readLock().lock();
    try {
      return slots.containsKey(storageId);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public long getStoredVectorCount() {
    lock.readLock().lock();
    try {
      return slots.size();
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public long getVectorDataSize(Long storageId) {
    if (storageId == null) {
      return -1;
    }
    lock.readLock().lock();
    try {
      Slot slot = slots.get(storageId);
      return slot == null ? -1 : slot.column().dimension * (long) Float.BYTES;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * The file of the vectors of a dimension: {@code <directory>/vectors-<dimension>.vec}.
   */
  Path vectorFile(int dimension) {
    return storageDirectory.resolve("vectors-" + dimension + ".vec");
  }

  /**
   * Reserve a record for a vector of a dimension, which no other vector gets until it is released.
   */
  private Slot allocate(int dimension) {
    lock.writeLock().lock();
    try {
      Column column = columns.get(dimension);
      if (column == null) {
        column = new Column(dimension, vectorFile(dimension));
        createVectorFile(column);
        columns.put(dimension, column);
      }
      Integer free = column.freeRecords.poll();
      return free != null ? column.slot(free) : column.append();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create the vector file " + vectorFile(dimension), e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  private void release(Slot slot) {
    lock.writeLock().lock();
    try {
      slot.column().freeRecords.push(slot.record());
    } finally {
      lock.writeLock().unlock();
    }
  }

  private void publish(Long storageId, Slot slot) {
    lock.writeLock().lock();
    try {
      slots.put(storageId, slot);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Write a vector to its reserved record, in the file and in memory. The record isn't visible until it is published,
   * so neither needs the lock.
   */
  private void store(Slot slot, long storageId, float[] vectorData) throws IOException {
    Column column = slot.column();
    ByteBuffer record = ByteBuffer.allocate(column.recordBytes()).order(ByteOrder.LITTLE_ENDIAN);
    record.putLong(FREE);
    record.asFloatBuffer().put(vectorData);
    try (FileChannel channel = FileChannel.open(column.file, StandardOpenOption.WRITE)) {
      writeFully(channel, record.clear(), column.offset(slot.record()));
      writeFully(channel, storageIdBuffer(storageId), column.offset(slot.record()));
    }
    System.arraycopy(vectorData, 0, slot.values(), slot.offset(), vectorData.length);
  }

  private void writeStorageId(Slot slot, long storageId) throws IOException {
    try (FileChannel channel = FileChannel.open(slot.column().file, StandardOpenOption.WRITE)) {
      writeFully(channel, storageIdBuffer(storageId), slot.column().offset(slot.record()));
    }
  }

  /**
   * Write the header of a new vector file to a temporary file, and move it to the vector file once it is complete.
   */
  private void createVectorFile(Column column) throws IOException {
    if (Files.exists(column.file)) {
      throw new IOException("The vector file " + column.file + " was created by another storage.");
    }
    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        .put(MAGIC).putInt(FORMAT_VERSION).putInt(column.dimension).flip();
    Path temp = tempFile(column.file);
    try {
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        writeFully(channel, header, 0);
      }
      PathUtils.moveAtomically(temp, column.file);
    } catch (IOException | RuntimeException e) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  private void loadVectorFiles() throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(storageDirectory,
        path -> VECTOR_FILE_NAME.matcher(path.getFileName().toString()).matches() && Files.isRegularFile(path))) {
      for (Path file : files) {
        Matcher matcher = VECTOR_FILE_NAME.matcher(file.getFileName().toString());
        if (matcher.matches()) {
          loadVectorFile(file, Integer.parseInt(matcher.group(1)));
        }
      }
    }
  }

  private void loadVectorFile(Path file, int dimension) throws IOException {
    Column column = new Column(dimension, file);
    columns.put(dimension, column);
    int recordBytes = column.recordBytes();
    try (FileChannel channel = readOnly ? FileChannel.open(file, StandardOpenOption.READ)
        : FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
      try {
        readFully(channel, header, 0);
      } catch (EOFException e) {
        throw new IllegalStateException("The vector file " + file + " has no complete header.", e);
      }
      header.flip();
      byte[] magic = new byte[MAGIC.length];
      header.get(magic);
      int version = header.getInt();
      int headerDimension = header.getInt();
      if (!Arrays.equals(MAGIC, magic) || version != FORMAT_VERSION || headerDimension != dimension) {
        throw new IllegalStateException(String.format(
            "%s is not a vector file of %d dimensions of format version %d.", file, dimension, FORMAT_VERSION));
      }

      long recordsBytes = channel.size() - HEADER_BYTES;
      long records = recordsBytes / recordBytes;
      if (recordsBytes % recordBytes != 0) {
        // Only a system crash while a vector was appended leaves a partial record, which is free.
        log.warn("Ignoring the partial record at the end of the vector file {}.", file);
        if (!readOnly) {
          channel.truncate(HEADER_BYTES + records * recordBytes);
        }
      }

      ByteBuffer buffer = ByteBuffer.allocate(Math.max(recordBytes, READ_BUFFER_BYTES / recordBytes * recordBytes))
          .order(ByteOrder.LITTLE_ENDIAN);
      long position = HEADER_BYTES;
      long remaining = records * recordBytes;
      while (remaining > 0) {
        buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
        readFully(channel, buffer, position);
        position += buffer.limit();
        remaining -= buffer.limit();
        buffer.flip();
        while (buffer.hasRemaining()) {
          long storageId = buffer.getLong();
          Slot slot = column.append();
          if (storageId == FREE) {
            buffer.position(buffer.position() + dimension * Float.BYTES);
            column.freeRecords.add(slot.record());
            continue;
          }
          buffer.asFloatBuffer().get(slot.values(), slot.offset(), dimension);
          buffer.position(buffer.position() + dimension * Float.BYTES);
          if (slots.putIfAbsent(storageId, slot) != null) {
            throw new IllegalStateException("The vector " + storageId + " is stored twice, the second time in " + file);
          }
        }
      }
    }
  }

  /**
   * Load the vector files of a LocalS3 before 2.5 into memory, without changing them.
   */
  private void loadLegacyVectorFiles() throws IOException {
    for (Path file : legacyVectorFiles()) {
      long storageId = Long.parseLong(file.getFileName().toString());
      if (!slots.containsKey(storageId)) {
        float[] vectorData = readLegacyVectorFile(file);
        Column column = columns.computeIfAbsent(vectorData.length, dimension -> new Column(dimension, null));
        Slot slot = column.append();
        System.arraycopy(vectorData, 0, slot.values(), slot.offset(), vectorData.length);
        slots.put(storageId, slot);
      }
    }
  }

  /**
   * Import the vector files of a LocalS3 before 2.5 into the vector files of their dimensions, and delete them.
   */
  private void importLegacyVectorFiles() throws IOException {
    List<Path> files = legacyVectorFiles();
    if (files.isEmpty()) {
      return;
    }
    long start = System.nanoTime();
    for (Path file : files) {
      long storageId = Long.parseLong(file.getFileName().toString());
      // A process that died during the import may have imported the vector already.
      if (!slots.containsKey(storageId)) {
        float[] vectorData = readLegacyVectorFile(file);
        Slot slot = allocate(vectorData.length);
        store(slot, storageId, vectorData);
        slots.put(storageId, slot);
      }
      Files.delete(file);
    }
    deleteEmptyShardDirectories();
    log.info("Imported {} vector files of {} into the vector files of their dimensions in {} ms.", files.size(),
        storageDirectory, (System.nanoTime() - start) / 1_000_000);
  }

  /**
   * The vector files of a LocalS3 before 2.5, in the subdirectories and directly in the directory.
   */
  private List<Path> legacyVectorFiles() throws IOException {
    try (Stream<Path> files = Files.walk(storageDirectory, 3)) {
      return files.filter(ShardedFileLayout::isIdFile).toList();
    }
  }

  private static float[] readLegacyVectorFile(Path file) throws IOException {
    // Buffered, so that a float doesn't take a read of the file of its own.
    try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      int dimensions = dis.readInt();
      if (dimensions <= 0 || Files.size(file) != Integer.BYTES + (long) dimensions * Float.BYTES) {
        throw new IllegalStateException("The vector file " + file + " is corrupt.");
      }
      float[] vectorData = new float[dimensions];
      for (int i = 0; i < dimensions; i++) {
        vectorData[i] = dis.readFloat();
      }
      return vectorData;
    }
  }

  private void deleteEmptyShardDirectories() throws IOException {
    try (DirectoryStream<Path> shards = Files.newDirectoryStream(storageDirectory, FileSystemVectorStorage::isShard)) {
      for (Path shard : shards) {
        try (DirectoryStream<Path> subShards = Files.newDirectoryStream(shard, FileSystemVectorStorage::isShard)) {
          for (Path subShard : subShards) {
            deleteIfEmpty(subShard);
          }
        }
        deleteIfEmpty(shard);
      }
    }
  }

  private static boolean isShard(Path path) {
    return path.getFileName().toString().matches("[0-9a-f]{2}") && Files.isDirectory(path);
  }

  private static void deleteIfEmpty(Path directory) throws IOException {
    try (Stream<Path> entries = Files.list(directory)) {
      if (entries.findAny().isPresent()) {
        return;
      }
    }
    Files.delete(directory);
  }

  /**
   * Delete the temporary files left behind by a process that died while writing.
   */
  private void deleteTempFiles() throws IOException {
    try (DirectoryStream<Path> tempFiles = Files.newDirectoryStream(storageDirectory, ".*" + TEMP_FILE_SUFFIX)) {
      for (Path tempFile : tempFiles) {
        Files.deleteIfExists(tempFile);
      }
    }
  }

  private Path tempFile(Path file) {
    return storageDirectory.resolve("." + file.getFileName() + "." + UUID.randomUUID() + TEMP_FILE_SUFFIX);
  }

  private void ensureWritable() {
    if (readOnly) {
      throw new UnsupportedOperationException("The vector storage of " + storageDirectory + " is read-only.");
    }
  }

  private static ByteBuffer storageIdBuffer(long storageId) {
    return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(storageId).flip();
  }

  private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
    while (buffer.hasRemaining()) {
      position += channel.write(buffer, position);
    }
  }

  private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, position);
      if (read < 0) {
        throw new EOFException();
      }
      position += read;
    }
  }

  /**
   * The vectors of a dimension: the records of its file, and their values in memory.
   */
  private static final class Column {

    private final int dimension;

    /**
     * The vector file; {@code null} if the vectors were only read from vector files of a LocalS3 before 2.5.
     */
    private final Path file;

    private final int chunkVectors;

    /**
     * The values of the records, {@linkplain #chunkVectors} records per array. The arrays are never replaced, so that
     * a view of a vector stays valid.
     */
    private final List<float[]> chunks = new ArrayList<>();

    /**
     * The number of records, used or free.
     */
    private int records;

    private final ArrayDeque<Integer> freeRecords = new ArrayDeque<>();

    private Column(int dimension, Path file) {
      this.dimension = dimension;
      this.file = file;
      this.chunkVectors = Math.max(1, Math.min(MAX_CHUNK_VECTORS, MAX_CHUNK_VALUES / dimension));
    }

    private int recordBytes() {
      return Long.BYTES + dimension * Float.BYTES;
    }

    private long offset(int record) {
      return HEADER_BYTES + (long) record * recordBytes();
    }

    private Slot append() {
      int record = records++;
      if (record / chunkVectors == chunks.size()) {
        chunks.add(new float[chunkVectors * dimension]);
      }
      return slot(record);
    }

    private Slot slot(int record) {
      return new Slot(this, record, chunks.get(record / chunkVectors), record % chunkVectors * dimension);
    }

  }

  /**
   * The record of a vector, and where its values are in memory, so that they are read without the lock.
   */
  private record Slot(Column column, int record, float[] values, int offset) {
  }

}
