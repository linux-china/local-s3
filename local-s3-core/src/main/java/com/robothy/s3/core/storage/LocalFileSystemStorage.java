package com.robothy.s3.core.storage;

import com.robothy.s3.core.util.PathUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * An implementation of {@linkplain Storage} based on a local directory.
 *
 * <p>Objects are written to a temporary file first, which then replaces the object file. If the
 * process dies while writing, the object file keeps its previous content or doesn't exist at all.
 *
 * <p>The object files are spread over two levels of subdirectories, {@code ab/cd/<id>}, as
 * {@linkplain ShardedFileLayout} describes, so that no directory holds more than a small share of the objects.
 *
 * <p>A storage of a LocalS3 before 2.5 kept every object file directly in the directory. Such files are still found,
 * and a writable storage moves them into their subdirectories when it is created, renaming one at a time, so that a
 * process that dies meanwhile leaves every object readable in one place or the other.
 *
 * <p>A read-only storage, e.g. over the initial data of an {@code IN_MEMORY} service, neither creates nor changes
 * anything in its directory: it reads objects in either layout, and rejects writes.
 */
@Slf4j
class LocalFileSystemStorage implements Storage {

  /**
   * Suffix of the temporary files that objects are written to, which are named {@code .<id>.<uuid>.tmp} in the
   * directory.
   */
  static final String TEMP_FILE_SUFFIX = ".tmp";

  private final Path directory;

  private final boolean readOnly;

  /**
   * The content that readers hold, and the deletions that wait for them; {@code null} for a read-only storage, which
   * deletes nothing. A whole object of a multipart upload is read part by part, so its parts are kept rather than
   * held open, see {@linkplain Storage#retain(Collection)}.
   */
  private final DeferredDeletions deletions;

  /**
   * Construct a writable {@linkplain LocalFileSystemStorage} instance. The directory is created if it doesn't exist,
   * and the object files of the flat layout of a LocalS3 before 2.5 are moved into their subdirectories.
   *
   * <p>The temporary files of the directory are kept: another service of the JVM may be writing them, over the same
   * data directory. Those that a process which died left behind are deleted by {@linkplain UnreferencedContentSweeper},
   * when no service of the JVM uses the directory.
   *
   * @param dataPath the path is where data stores in.
   */
  public LocalFileSystemStorage(Path dataPath) {
    this(dataPath, false);
  }

  /**
   * Construct a {@linkplain LocalFileSystemStorage} instance.
   *
   * @param dataPath the path is where data stores in.
   * @param readOnly whether the storage only reads the objects of the directory, which it then neither creates nor
   *     changes.
   */
  LocalFileSystemStorage(Path dataPath, boolean readOnly) {
    this.directory = Objects.requireNonNull(dataPath);
    this.readOnly = readOnly;
    this.deletions = readOnly ? null : new DeferredDeletions(this::deleteNow);
    if (!readOnly) {
      PathUtils.createDirectoryIfNotExist(directory);
      moveFlatObjectFilesIntoSubdirectories();
    }
  }

  @Override
  public Long put(Long id, byte[] data) {
    return put(id, new ByteArrayInputStream(data));
  }

  @Override
  public Long put(Long id, InputStream data) {
    ensureWritable();
    Path temp = directory.resolve("." + id + "." + UUID.randomUUID() + TEMP_FILE_SUFFIX);
    try (InputStream in = data) {
      Files.copy(in, temp);
      Path target = createObjectDirectory(id);
      PathUtils.moveAtomically(temp, target);
      deleteFlatObjectFile(id);
    } catch (IOException e) {
      deleteQuietly(temp, e);
      throw new UncheckedIOException("Failed to store object " + id + ".", e);
    } catch (RuntimeException e) {
      deleteQuietly(temp, e);
      throw e;
    }
    return id;
  }

  /**
   * Store the content of a file by renaming it to the object file, so that its content isn't written a second
   * time. A file that can't be renamed atomically, e.g. because it is on another file system, or because Windows
   * refuses to rename a file that is memory-mapped, is copied instead, like a stream.
   *
   * <p>The file is marked as modified before it is renamed, since a rename keeps the time the file was last written,
   * e.g. when its upload began. Every object file is then last modified when it was stored, or later, which is what
   * {@linkplain UnreferencedContentSweeper} relies on to keep the files stored after it read the metadata.
   */
  @Override
  public Long put(Long id, Path file) {
    ensureWritable();
    try {
      Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
      Files.move(file, createObjectDirectory(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      deleteFlatObjectFile(id);
      return id;
    } catch (IOException | UnsupportedOperationException e) {
      // The file is left where it was; copy it.
    }
    try (InputStream in = Files.newInputStream(file)) {
      return put(id, in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store object " + id + " from " + file + ".", e);
    }
  }

  @Override
  public long size(Long id) {
    Path path = existingObjectPath(id);
    try {
      return Files.size(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the size of object " + id + ".", e);
    }
  }

  @Override
  public byte[] getBytes(Long id) {
    Path path = existingObjectPath(id);
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read object " + id + ".", e);
    }
  }

  /**
   * Open the content of an object. The caller reads it after the bucket lock that the read was made under
   * is released, so the object it holds open can be overwritten or deleted meanwhile; on Windows, which
   * refuses to replace or delete an open file, those operations are repeated while this stream is open.
   * Content that is opened later than it is resolved is {@linkplain #retain(Collection) retained} instead.
   */
  @Override
  public InputStream getInputStream(Long id) {
    Path path = existingObjectPath(id);
    try {
      return FileRegionInputStream.open(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open object " + id + ".", e);
    }
  }

  @Override
  public InputStream getInputStream(Long id, long position, long length) {
    Path path = existingObjectPath(id);
    try {
      return FileRegionInputStream.open(path, position, length);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open object " + id + ".", e);
    }
  }

  /**
   * Retain the files of the given objects. A read-only storage deletes nothing, so its files stay readable without
   * being retained.
   */
  @Override
  public Optional<ContentRetention> retain(Collection<Long> ids) {
    return Optional.of(readOnly ? ContentRetention.NONE : deletions.retain(ids));
  }

  @Override
  public Long delete(Long id) {
    existingObjectPath(id);
    ensureWritable();
    // The file of content that a reader still has to open is deleted once it released it.
    return deletions.defer(id) ? id : deleteNow(id);
  }

  private Long deleteNow(Long id) {
    try {
      // An emptied subdirectory is kept: another object may be stored in it at the same time.
      if (Files.exists(objectPath(id))) {
        PathUtils.delete(objectPath(id));
      }
      deleteFlatObjectFile(id);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete object " + id + ".", e);
    }
    return id;
  }

  @Override
  public boolean isExist(Long id) {
    return Files.exists(objectPath(id)) || Files.exists(flatObjectPath(id));
  }

  /**
   * The file of an object: {@code <directory>/ab/cd/<id>}, see {@linkplain ShardedFileLayout#shardedPath(Path, long)}.
   */
  Path objectPath(Long id) {
    return ShardedFileLayout.shardedPath(directory, id);
  }

  /**
   * The file of an object in the flat layout of a LocalS3 before 2.5.
   */
  private Path flatObjectPath(Long id) {
    return ShardedFileLayout.flatPath(directory, id);
  }

  /**
   * The file that holds an object, in either layout.
   *
   * @throws IllegalArgumentException if the object doesn't exist.
   */
  private Path existingObjectPath(Long id) {
    Path path = objectPath(id);
    if (Files.exists(path)) {
      return path;
    }
    Path flat = flatObjectPath(id);
    if (Files.exists(flat)) {
      return flat;
    }
    throw new IllegalArgumentException("Object id='" + id + "' not exist.");
  }

  /**
   * Create the subdirectories of an object, if they don't exist.
   *
   * @return the file of the object.
   */
  private Path createObjectDirectory(Long id) throws IOException {
    Path path = objectPath(id);
    Files.createDirectories(path.getParent());
    return path;
  }

  /**
   * Delete the file of an object in the flat layout, which an object stored again replaces.
   */
  private void deleteFlatObjectFile(Long id) throws IOException {
    Path flat = flatObjectPath(id);
    if (Files.isRegularFile(flat)) {
      PathUtils.delete(flat);
    }
  }

  private void ensureWritable() {
    if (readOnly) {
      throw new UnsupportedOperationException("The storage of " + directory + " is read-only.");
    }
  }

  /**
   * Move the object files of the flat layout of a LocalS3 before 2.5 into their subdirectories. Each file is renamed
   * atomically, so an object is always in one of the layouts, which both are read.
   */
  private void moveFlatObjectFilesIntoSubdirectories() {
    long start = System.nanoTime();
    long moved = ShardedFileLayout.moveFlatFilesIntoSubdirectories(directory);
    if (moved > 0) {
      log.info("Moved {} object files of {} into subdirectories in {} ms.", moved, directory,
          (System.nanoTime() - start) / 1_000_000);
    }
  }

  /**
   * Delete the temporary file that a failed write left behind. A failure to delete it is reported with the
   * failure of the write, rather than in place of it.
   */
  private static void deleteQuietly(Path file, Throwable cause) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }

}
