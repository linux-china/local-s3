package com.robothy.s3.core.storage;

import com.robothy.s3.core.util.PathUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
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
 * <p>When the first holder opens the store of a data directory for writing, the content files that its metadata doesn't
 * reference are deleted in the background, see {@linkplain UnreferencedContentSweeper}.
 *
 * <p>The file is compacted when its last holder closes the store, so that a data directory rests at the size of the
 * metadata it holds rather than of everything that was ever written to it; see {@linkplain PersistencePolicy}. A store
 * open for writing is also compacted while it runs, once its file is mostly room that no metadata uses anymore, so that
 * a service that runs for long, e.g. in an IDE, doesn't keep the room of every write it took; see
 * {@linkplain #compactIfWasteful()}.
 *
 * <p>The one store of a file is open either for reading or for writing, and stays that way while a holder still reads
 * it. {@linkplain #readOnly(Path)} therefore shares a store that {@linkplain #persistent(Path)} opened, but not the
 * other way around: opening a data directory for writing while it is open read-only is rejected, see
 * {@linkplain #persistent(Path)}.
 */
@Slf4j
public final class LocalS3Store implements AutoCloseable {

  /**
   * The name of the metadata file of a data directory.
   */
  public static final String FILE_NAME = "buckets.mvstore";

  /**
   * Time allotted to compacting the file when the last holder closes the store. {@code -1} compacts it fully, by
   * writing the live metadata to a new file: the cost is the metadata that the store holds, not the size the file
   * grew to, and it is what leaves a data directory at the size of what it holds rather than of what was written to
   * it. See {@linkplain PersistencePolicy#DURABLE}.
   */
  private static final int CLOSE_COMPACTION_TIME = -1;

  /**
   * How often a store open for writing checks whether its file is worth compacting, in seconds.
   */
  private static final long COMPACTION_CHECK_INTERVAL = 30;

  /**
   * The size below which the file of a store isn't compacted while it runs: the room it could reclaim is too small to
   * matter.
   */
  static final long COMPACTION_MIN_FILE_SIZE = 16L * 1024 * 1024;

  /**
   * The percentage of the file that the live metadata fills, at or above which the file isn't compacted while it runs.
   */
  static final int COMPACTION_MAX_FILL_RATE = 50;

  /**
   * Time allotted to a compaction while the store runs, in milliseconds. It holds the store lock of MVStore, so the
   * commits of the requests wait for it; what it costs is the live metadata, which it rewrites, not the size of the
   * file.
   */
  private static final int RUNTIME_COMPACTION_TIME = 1000;

  /**
   * The stores of the files that are open, by the absolute path of the file. Guarded by itself.
   */
  private static final Map<Path, LocalS3Store> OPEN_FILES = new HashMap<>();

  private final MVStore store;

  /**
   * When the changes written to this store reach the disk; {@linkplain PersistencePolicy#DURABLE} for a store that
   * has no file, whose changes never do.
   */
  private final PersistencePolicy policy;

  /**
   * The file that the store holds open; {@code null} for an in-memory store, which is shared with nobody.
   */
  private final Path file;

  /**
   * How many holders {@linkplain #close()} the store before it is closed. Guarded by {@linkplain #OPEN_FILES}.
   */
  private int holders = 1;

  /**
   * The sweep of the unreferenced content files of the data directory, which the last holder stops before the store is
   * closed; {@code null} if there is none. Set before the store is handed out.
   */
  private UnreferencedContentSweeper sweeper;

  /**
   * The periodic compaction of the file, which the last holder stops before the store is closed; {@code null} for a
   * store that is not open for writing. Guarded by {@linkplain #compactionLock}.
   */
  private ScheduledFuture<?> compaction;

  /**
   * Held by a compaction while it runs and by {@linkplain #close()} while it stops them, so that the store isn't
   * closed under a compaction.
   */
  private final Object compactionLock = new Object();

  private LocalS3Store(MVStore store, Path file, PersistencePolicy policy) {
    this.store = store;
    this.file = file;
    this.policy = policy;
  }

  /**
   * Open a store that keeps the metadata in memory, and writes no file.
   *
   * @return a new store.
   */
  public static LocalS3Store inMemory() {
    // Nothing is written to a disk, so committing a change costs almost nothing and there is no policy to choose.
    return new LocalS3Store(MVStore.open(null), null, PersistencePolicy.DURABLE);
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
    return persistent(dataPath, PersistencePolicy.DURABLE);
  }

  /**
   * Open the store of a data directory for reading and writing, creating the directory and the file if they don't
   * exist.
   *
   * <p>If no holder has the store open, and the file exists, the content files of the directory that the metadata
   * doesn't reference are deleted in the background, see {@linkplain UnreferencedContentSweeper}, until the last holder
   * closes the store.
   *
   * @param dataPath the data directory.
   * @param policy when the changes written to the store reach the disk.
   * @return the store over {@code dataPath/}{@value #FILE_NAME}, shared with the other holders of the same file.
   * @throws IllegalStateException if the file is already open read-only, or open with another policy, see
   *     {@linkplain #persistent(Path)}.
   */
  public static LocalS3Store persistent(Path dataPath, PersistencePolicy policy) {
    Objects.requireNonNull(dataPath, "dataPath");
    Objects.requireNonNull(policy, "policy");
    PathUtils.createDirectoryIfNotExist(dataPath);
    return open(fileOf(dataPath), false, policy);
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
      return open(file, true, PersistencePolicy.DURABLE);
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
  private static LocalS3Store open(Path file, boolean readOnly, PersistencePolicy policy) {
    synchronized (OPEN_FILES) {
      LocalS3Store open = OPEN_FILES.get(file);
      if (open != null) {
        if (!readOnly && open.store.isReadOnly()) {
          throw new IllegalStateException("The metadata store " + file + " is open read-only, e.g. to load the"
              + " initial data of an IN_MEMORY service, so it can't be opened for writing at the same time."
              + " Give the service that writes it a data directory of its own, or open it once the read-only"
              + " holder has closed it.");
        }
        if (!readOnly && open.policy != policy) {
          throw new IllegalStateException("The metadata store " + file + " is open with the persistence policy "
              + open.policy + ", so it can't be opened with " + policy + " at the same time: the holders of a store"
              + " share when its changes reach the disk. Configure the services of a data directory the same way.");
        }
        open.holders++;
        return open;
      }
      MVStore.Builder builder = new MVStore.Builder().fileName(file.toString());
      if (readOnly) {
        builder.readOnly();
      }
      boolean existed = Files.isRegularFile(file);
      Instant openedAt = Instant.now();
      // A DURABLE store commits every change itself; a FAST one leaves that to the background thread of MVStore,
      // which commits at most a second after a change, or once a megabyte of them is unsaved.
      LocalS3Store store = new LocalS3Store(builder.open(), file, policy);
      if (!readOnly && existed) {
        // No holder of this JVM writes the directory, and MVStore's lock of the file keeps other processes out, so no
        // request is storing content that the metadata doesn't reference yet. A new file references nothing.
        store.sweeper = UnreferencedContentSweeper.start(store.store, file.getParent(), openedAt);
      }
      if (!readOnly) {
        synchronized (store.compactionLock) {
          store.compaction = Compactor.EXECUTOR.scheduleWithFixedDelay(store::compactQuietly,
              COMPACTION_CHECK_INTERVAL, COMPACTION_CHECK_INTERVAL, TimeUnit.SECONDS);
        }
      }
      OPEN_FILES.put(file, store);
      return store;
    }
  }

  /**
   * The sweep of the unreferenced content files that opening the store started; for tests.
   *
   * @return the sweep; {@code null} if none was started.
   */
  UnreferencedContentSweeper sweeper() {
    return sweeper;
  }

  /**
   * Compact the file if it is mostly room that no metadata uses anymore.
   *
   * <p>A commit appends a chunk to the file rather than replacing the chunks it supersedes, whose room MVStore reuses
   * only once they are older than its retention time, and never gives back while the store is open. A service that
   * takes many small writes, e.g. the files of a table that a big data engine writes, therefore grows the file to many
   * times the metadata it holds, which only closing the store would reclaim otherwise.
   *
   * <p>Compacting rewrites the live metadata next to each other and truncates the file, after syncing what it moves
   * to the disk. The file is compacted only once it is at least {@value #COMPACTION_MIN_FILE_SIZE} bytes and less than
   * {@value #COMPACTION_MAX_FILL_RATE}% full, so a store that is written little is left alone.
   *
   * @return {@code true} if the file was compacted.
   */
  boolean compactIfWasteful() {
    synchronized (compactionLock) {
      if (compaction == null || store.isClosed() || store.isReadOnly()) {
        return false;
      }
      if (store.getFileStore().size() < COMPACTION_MIN_FILE_SIZE || liveFillRate() >= COMPACTION_MAX_FILL_RATE) {
        return false;
      }
      // Compacting overwrites the chunks it frees whatever the retention time is, and leaves the retention time at
      // 0; the chunks that later commits free are kept for the usual time again.
      int retentionTime = store.getRetentionTime();
      try {
        store.compactFile(RUNTIME_COMPACTION_TIME);
      } finally {
        store.setRetentionTime(retentionTime);
      }
      return true;
    }
  }

  /**
   * The percentage of the file that live metadata fills: of the room that chunks take, what the chunks still use. A
   * chunk that no metadata uses anymore takes its room until a commit drops it, which a store that isn't written
   * doesn't do, so the room that chunks take alone overstates what is live.
   */
  int liveFillRate() {
    return store.getFillRate() * store.getFileStore().getChunksFillRate() / 100;
  }

  private void compactQuietly() {
    try {
      long before = store.getFileStore().size();
      if (compactIfWasteful()) {
        log.debug("Compacted {} from {} to {} bytes.", file, before, store.getFileStore().size());
      }
    } catch (RuntimeException e) {
      // Not compacting leaves the file larger than it needs to be, and closing the store compacts it anyway.
      log.warn("Failed to compact {}; it is compacted when the service is shut down.", file, e);
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
   * When the changes written to this store reach the disk.
   *
   * @return the policy the store was opened with.
   */
  public PersistencePolicy policy() {
    return policy;
  }

  /**
   * Whether a change must be committed as it is written, i.e. whether the store was opened as
   * {@linkplain PersistencePolicy#DURABLE}.
   *
   * @return {@code true} if every change is committed.
   */
  public boolean commitsEveryChange() {
    return policy == PersistencePolicy.DURABLE;
  }

  /**
   * Whether the store keeps its metadata in memory and writes no file, which {@linkplain #inMemory()} opens. Such a
   * store is the only copy of what it holds, so nothing that is read from it may be dropped and read again.
   *
   * @return {@code true} if the store has no file.
   */
  public boolean isInMemory() {
    return file == null;
  }

  /**
   * Whether the store is open for reading only, which {@linkplain #readOnly(Path)} opens a data directory as. Writing
   * such a store fails, so {@linkplain #persistent(Path)} refuses to hand one out.
   *
   * @return {@code true} if the store can only be read.
   */
  public boolean isReadOnly() {
    return store.isReadOnly();
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
    if (sweeper != null) {
      // It reads the store, and would keep touching the data directory after its services are gone.
      sweeper.cancel();
    }
    synchronized (compactionLock) {
      // Waits for a compaction that is running, and keeps the next one from starting.
      if (compaction != null) {
        compaction.cancel(false);
        compaction = null;
      }
    }
    if (!store.isClosed()) {
      // Writes what is left, and compacts the file: a commit appends a chunk rather than replacing what it
      // supersedes, so without this a data directory keeps the room that every write it ever took needed.
      store.close(CLOSE_COMPACTION_TIME);
    }
  }

  /**
   * The thread that compacts the files of the stores open for writing, created when the first one is opened.
   */
  private static final class Compactor {

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "local-s3-store-compactor");
      thread.setDaemon(true);
      return thread;
    });

  }

}
