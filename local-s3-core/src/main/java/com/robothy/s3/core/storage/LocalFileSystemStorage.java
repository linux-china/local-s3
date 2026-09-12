package com.robothy.s3.core.storage;

import com.robothy.s3.core.util.PathUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import lombok.SneakyThrows;

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
  @SneakyThrows
  public Long put(Long id, InputStream data) {
    Path temp = directory.resolve("." + id + "." + UUID.randomUUID() + TEMP_FILE_SUFFIX);
    try (InputStream in = data) {
      Files.copy(in, temp);
      PathUtils.moveAtomically(temp, objectPath(id));
    } catch (Exception e) {
      Files.deleteIfExists(temp);
      throw e;
    }
    return id;
  }

  @Override
  @SneakyThrows
  public byte[] getBytes(Long id) {
    ensureExists(id);
    return Files.readAllBytes(objectPath(id));
  }

  /**
   * Open the content of an object. The caller reads it after the bucket lock that the read was made under
   * is released, so the object it holds open can be overwritten or deleted meanwhile; on Windows, which
   * refuses to replace or delete an open file, those operations are repeated while this stream is open.
   */
  @Override
  @SneakyThrows
  public InputStream getInputStream(Long id) {
    ensureExists(id);
    return Files.newInputStream(objectPath(id));
  }

  @Override
  @SneakyThrows
  public Long delete(Long id) {
    ensureExists(id);
    PathUtils.delete(objectPath(id));
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
  @SneakyThrows
  private void deleteTempFiles() {
    try (DirectoryStream<Path> tempFiles = Files.newDirectoryStream(directory, ".*" + TEMP_FILE_SUFFIX)) {
      for (Path tempFile : tempFiles) {
        Files.deleteIfExists(tempFile);
      }
    }
  }

}
