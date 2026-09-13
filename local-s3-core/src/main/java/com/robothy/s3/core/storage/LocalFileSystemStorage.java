package com.robothy.s3.core.storage;

import com.robothy.s3.core.util.PathUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

/**
 * An implementation of {@linkplain Storage} based on a local directory.
 *
 * <p>Objects are written to a temporary file first, which then replaces the object file. If the
 * process dies while writing, the object file keeps its previous content or doesn't exist at all.
 */
class LocalFileSystemStorage implements Storage {

  /**
   * Suffix of the temporary files that objects are written to.
   */
  private static final String TEMP_FILE_SUFFIX = ".tmp";

  private final Path directory;

  /**
   * Construct a {@linkplain LocalFileSystemStorage} instance.
   *
   * @param dataPath the path is where data stores in.
   */
  public LocalFileSystemStorage(Path dataPath) {
    Objects.requireNonNull(dataPath);
    this.directory = dataPath;
    PathUtils.createDirectoryIfNotExit(directory);
    deleteTempFiles();
  }

  @Override
  public Long put(Long id, byte[] data) {
    return put(id, new ByteArrayInputStream(data));
  }

  @Override
  public Long put(Long id, InputStream data) {
    Path temp = directory.resolve("." + id + "." + UUID.randomUUID() + TEMP_FILE_SUFFIX);
    try (InputStream in = data) {
      Files.copy(in, temp);
      PathUtils.moveAtomically(temp, objectPath(id));
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
   */
  @Override
  public Long put(Long id, Path file) {
    try {
      Files.move(file, objectPath(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
    ensureExists(id);
    try {
      return Files.size(objectPath(id));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the size of object " + id + ".", e);
    }
  }

  @Override
  public byte[] getBytes(Long id) {
    ensureExists(id);
    try {
      return Files.readAllBytes(objectPath(id));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read object " + id + ".", e);
    }
  }

  /**
   * Open the content of an object. The caller reads it after the bucket lock that the read was made under
   * is released, so the object it holds open can be overwritten or deleted meanwhile; on Windows, which
   * refuses to replace or delete an open file, those operations are repeated while this stream is open.
   */
  @Override
  public InputStream getInputStream(Long id) {
    ensureExists(id);
    try {
      return FileRegionInputStream.open(objectPath(id));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open object " + id + ".", e);
    }
  }

  @Override
  public InputStream getInputStream(Long id, long position, long length) {
    ensureExists(id);
    try {
      return FileRegionInputStream.open(objectPath(id), position, length);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open object " + id + ".", e);
    }
  }

  @Override
  public Long delete(Long id) {
    ensureExists(id);
    try {
      PathUtils.delete(objectPath(id));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete object " + id + ".", e);
    }
    return id;
  }

  @Override
  public boolean isExist(Long id) {
    return Files.exists(objectPath(id));
  }

  private Path objectPath(Long id) {
    return directory.resolve(String.valueOf(id));
  }

  private void ensureExists(Long id) {
    if (!isExist(id)) {
      throw new IllegalArgumentException("Object id='" + id + "' not exist.");
    }
  }

  /**
   * Delete the temporary files left behind by a process that died while writing objects.
   */
  private void deleteTempFiles() {
    try (DirectoryStream<Path> tempFiles = Files.newDirectoryStream(directory, ".*" + TEMP_FILE_SUFFIX)) {
      for (Path tempFile : tempFiles) {
        Files.deleteIfExists(tempFile);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete the temporary files in " + directory + ".", e);
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
