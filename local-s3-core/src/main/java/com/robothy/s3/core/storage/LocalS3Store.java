package com.robothy.s3.core.storage;

import com.robothy.s3.core.util.PathUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.h2.mvstore.MVStore;

/**
 * The key-value store of one LocalS3 service, which holds the metadata of its buckets.
 *
 * <p>A service has a single store, so all of its metadata lives in one file rather than in a file per bucket: the
 * writes of a change go to one place, and a data directory is a directory with one {@value #FILE_NAME} in it.
 *
 * <p>An {@code IN_MEMORY} service opens a store that is never written to a file, and a {@code PERSISTENCE} service
 * opens {@value #FILE_NAME} of its data directory. Both modes then read and write their metadata the same way.
 *
 * <p>MVStore locks the file it opens, so the services of a JVM that use the same data directory, e.g. a persistent
 * service and another one that starts from its directory, share one open store, which is closed once they all
 * {@linkplain #close()} it. Sharing it is also what keeps them consistent: they read and write the same metadata
 * rather than overwrite each other's.
 *
 * <p>The one store of a file is open either for reading or for writing, and stays that way while a holder still reads
 * it. {@linkplain #readOnly(Path)} therefore shares a store that {@linkplain #persistent(Path)} opened, but not the
 * other way around: opening a data directory for writing while it is open read-only is rejected, see
 * {@linkplain #persistent(Path)}.
 */
public final class LocalS3Store implements AutoCloseable {

  /**
   * The name of the metadata file of a data directory.
   */
  public static final String FILE_NAME = "buckets.mvstore";

  /**
   * The stores of the files that are open, by the absolute path of the file. Guarded by itself.
   */
  private static final Map<Path, LocalS3Store> OPEN_FILES = new HashMap<>();

  private final MVStore store;

  /**
   * The file that the store holds open; {@code null} for an in-memory store, which is shared with nobody.
   */
  private final Path file;

  /**
   * How many holders {@linkplain #close()} the store before it is closed. Guarded by {@linkplain #OPEN_FILES}.
   */
  private int holders = 1;

  private LocalS3Store(MVStore store, Path file) {
    this.store = store;
    this.file = file;
  }

  /**
   * Open a store that keeps the metadata in memory, and writes no file.
   *
   * @return a new store.
   */
  public static LocalS3Store inMemory() {
    return new LocalS3Store(MVStore.open(null), null);
  }

  /**
   * Open the store of a data directory for reading and writing, creating the directory and the file if they don't
   * exist.
   *
   * @param dataPath the data directory.
   * @return the store over {@code dataPath/}{@value #FILE_NAME}, shared with the other holders of the same file.
   * @throws IllegalStateException if the file is already open read-only, e.g. by an {@code IN_MEMORY} service that is
   *     loading its initial data from the same directory. Such a store can't be written, and can't become writable
   *     while its holder still reads it.
   */
  public static LocalS3Store persistent(Path dataPath) {
    Objects.requireNonNull(dataPath, "dataPath");
    PathUtils.createDirectoryIfNotExist(dataPath);
    return open(fileOf(dataPath), false);
  }

  /**
   * Open the store of a data directory for reading, e.g. to load the initial data of an {@code IN_MEMORY} service.
   *
   * @param dataPath the data directory.
   * @return the store over {@code dataPath/}{@value #FILE_NAME}, shared with the other holders of the same file, which
   *     is writable if one of them writes it; an empty in-memory store if the directory holds no {@value #FILE_NAME}.
   */
  public static LocalS3Store readOnly(Path dataPath) {
    Objects.requireNonNull(dataPath, "dataPath");
    Path file = fileOf(dataPath);
    synchronized (OPEN_FILES) {
      if (!OPEN_FILES.containsKey(file) && !Files.isRegularFile(file)) {
        // Nothing to read, and opening a file that doesn't exist read-only fails.
        return inMemory();
      }
      return open(file, true);
    }
  }

  private static Path fileOf(Path dataPath) {
    return dataPath.toAbsolutePath().normalize().resolve(FILE_NAME);
  }

  /**
   * Open the store of a file, or share the one that is already open.
   *
   * <p>MVStore locks the file it opens, so a file has a single open store, which every holder shares. The store was
   * opened for reading or for writing, and can't become the other afterwards while a holder still reads it: a caller
   * that needs to write a store that is open read-only is rejected here, where the reason is known, rather than by
   * MVStore at the first write, long after the store was handed out. A caller that only reads shares a writable store,
   * which reads the same metadata.
   *
   * @param file the file of the store.
   * @param readOnly whether the caller only reads the store.
   * @return the store, whose holder the caller now is.
   * @throws IllegalStateException if the caller writes the store and it is open read-only.
   */
  private static LocalS3Store open(Path file, boolean readOnly) {
    synchronized (OPEN_FILES) {
      LocalS3Store open = OPEN_FILES.get(file);
      if (open != null) {
        if (!readOnly && open.store.isReadOnly()) {
          throw new IllegalStateException("The metadata store " + file + " is open read-only, e.g. to load the"
              + " initial data of an IN_MEMORY service, so it can't be opened for writing at the same time."
              + " Give the service that writes it a data directory of its own, or open it once the read-only"
              + " holder has closed it.");
        }
        open.holders++;
        return open;
      }
      MVStore.Builder builder = new MVStore.Builder().fileName(file.toString());
      if (readOnly) {
        builder.readOnly();
      }
      LocalS3Store store = new LocalS3Store(builder.open(), file);
      OPEN_FILES.put(file, store);
      return store;
    }
  }

  /**
   * The MVStore that holds the metadata.
   *
   * @return the store.
   */
  public MVStore store() {
    return store;
  }

  /**
   * Write the pending changes to the file, if the store has one.
   */
  public void flush() {
    if (!store.isClosed()) {
      store.commit();
    }
  }

  /**
   * Release the store, closing it and its file once every holder has released it. Releasing it twice from the same
   * holder closes it while another one still reads it, so a holder closes it exactly once.
   */
  @Override
  public void close() {
    if (file == null) {
      if (!store.isClosed()) {
        store.close();
      }
      return;
    }
    synchronized (OPEN_FILES) {
      if (--holders > 0) {
        return;
      }
      OPEN_FILES.remove(file);
    }
    if (!store.isClosed()) {
      store.close();
    }
  }

}
