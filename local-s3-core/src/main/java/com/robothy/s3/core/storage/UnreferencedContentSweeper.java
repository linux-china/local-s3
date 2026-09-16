package com.robothy.s3.core.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import lombok.extern.slf4j.Slf4j;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

/**
 * Deletes the content files of a data directory that its metadata doesn't reference.
 *
 * <p>A change deletes the content it replaces only after the metadata is persisted, so the metadata never references
 * a missing file, but a file may be left behind: when deleting it fails, when the process dies between persisting the
 * metadata and deleting the file, or when it dies after storing the content of an upload and before persisting its
 * metadata. Nothing references such a file anymore, so it would take disk space for as long as the data directory
 * lives.
 *
 * <p>{@linkplain LocalS3Store} starts a sweep when it opens the metadata of a data directory for writing and no service
 * of the JVM has it open, i.e. when no request of this process can be storing content that the metadata doesn't
 * reference yet; MVStore's lock of the file keeps other processes out. The sweep runs in the background, so that opening
 * a directory of many objects doesn't wait for their files to be listed, and is correct while the services use the
 * directory, because it only deletes a file that
 * <ul>
 *   <li>the metadata didn't reference when the store was opened: the sweep reads a snapshot of that moment, which no
 *   later change affects. A file that nothing referenced then is never referenced later, since a change only
 *   references the content it stores, or content that the metadata referenced already, e.g. the parts of an upload that
 *   it completes;</li>
 *   <li>and was last modified {@value #MODIFIED_BEFORE_OPEN_MINUTES} minute before the store was opened, so that no file
 *   that a service stored afterwards is deleted, even on a file system with coarse modification times, or a clock that
 *   was set back.</li>
 * </ul>
 *
 * <p>What isn't certain is kept:
 * <ul>
 *   <li>nothing is deleted if the metadata can't be read, or holds no objects and no uploads at all, e.g. a store that
 *   was replaced or rolled back;</li>
 *   <li>nothing is deleted in a directory of a LocalS3 before 2.5, whose {@code *.bucket.meta} files 2.5 doesn't read,
 *   and whose content it therefore doesn't see referenced;</li>
 *   <li>only the files of the layout of 2.5, {@code .storage/ab/cd/<id>}, are considered, not those still in the flat
 *   layout, nor the temporary files of request bodies and writes.</li>
 * </ul>
 */
@Slf4j
final class UnreferencedContentSweeper {

  /**
   * The directory of the content of a data directory; see
   * {@linkplain com.robothy.s3.core.service.manager.LocalS3Manager#STORAGE_DIRECTORY}.
   */
  static final String CONTENT_DIRECTORY = ".storage";

  static final long MODIFIED_BEFORE_OPEN_MINUTES = 1;

  static final String THREAD_NAME = "locals3-content-sweeper";

  private static final String LEGACY_BUCKET_METADATA_SUFFIX = ".bucket.meta";

  /**
   * What a sweep deleted.
   *
   * @param files the number of files deleted.
   * @param bytes the number of bytes of the files deleted.
   */
  record Result(long files, long bytes) {

    static final Result NONE = new Result(0, 0);
  }

  private final MVStore store;

  private final MVStore.TxCounter snapshotUsage;

  private final List<MVMap<String, String>> snapshot;

  private final Path dataDirectory;

  private final Instant modifiedBefore;

  private final Thread thread;

  private volatile boolean cancelled;

  private volatile Result result = Result.NONE;

  private UnreferencedContentSweeper(MVStore store, Path dataDirectory, Instant openedAt) {
    this.store = store;
    this.dataDirectory = dataDirectory;
    this.modifiedBefore = openedAt.minus(Duration.ofMinutes(MODIFIED_BEFORE_OPEN_MINUTES));
    // Keeps the version of the snapshot, and the pages it reads, from being dropped while the sweep reads it.
    this.snapshotUsage = store.registerVersionUsage();
    try {
      this.snapshot = MVStoreBucketMetadataStore.contentReferencingMaps(store);
    } catch (RuntimeException e) {
      store.deregisterVersionUsage(snapshotUsage);
      throw e;
    }
    this.thread = new Thread(this::run, THREAD_NAME);
    this.thread.setDaemon(true);
  }

  /**
   * Start a sweep of a data directory in the background. Takes the snapshot of the metadata that the sweep reads before
   * it returns, so the caller starts it before the store is changed. Never fails: a failure is logged, and leaves the
   * files that the sweep didn't get to.
   *
   * @param store the MVStore of the data directory, just opened for writing, which no service uses yet.
   * @param dataDirectory the data directory.
   * @param openedAt when the store was opened.
   * @return the sweep, which the owner of the store {@linkplain #cancel() cancels} before closing it; {@code null} if
   *     it couldn't be started.
   */
  static UnreferencedContentSweeper start(MVStore store, Path dataDirectory, Instant openedAt) {
    UnreferencedContentSweeper sweeper = null;
    try {
      sweeper = new UnreferencedContentSweeper(store, dataDirectory, openedAt);
      sweeper.thread.start();
      return sweeper;
    } catch (RuntimeException | OutOfMemoryError e) {
      if (sweeper != null) {
        sweeper.store.deregisterVersionUsage(sweeper.snapshotUsage);
      }
      log.warn("Failed to start deleting the unreferenced content files of {}; they are kept.", dataDirectory, e);
      return null;
    }
  }

  /**
   * Stop the sweep, and wait for it to stop, so that the store can be closed and the directory isn't touched anymore.
   */
  void cancel() {
    cancelled = true;
    if (Thread.currentThread() == thread) {
      return;
    }
    boolean interrupted = false;
    while (thread.isAlive()) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Wait for the sweep to end; for tests.
   *
   * @return what the sweep deleted.
   */
  Result await() throws InterruptedException {
    thread.join();
    return result;
  }

  private void run() {
    Path contentDirectory = dataDirectory.resolve(CONTENT_DIRECTORY);
    long start = System.nanoTime();
    try {
      if (!Files.isDirectory(contentDirectory)) {
        return;
      }
      if (hasLegacyBucketMetadata()) {
        log.warn("Kept the content files of {}: it holds bucket metadata of a LocalS3 before 2.5, which isn't read, so"
            + " the files it references can't be told apart from unreferenced ones.", dataDirectory);
        return;
      }
      long[] referenced = MVStoreBucketMetadataStore.referencedContentIds(snapshot, () -> cancelled);
      if (referenced.length == 0) {
        log.debug("Kept the content files of {}: its metadata references no content.", dataDirectory);
        return;
      }
      result = deleteUnreferenced(contentDirectory, referenced);
      if (result.files() > 0) {
        log.info("Deleted {} unreferenced content files ({} bytes) of {} in {} ms.", result.files(), result.bytes(),
            contentDirectory, (System.nanoTime() - start) / 1_000_000);
      }
    } catch (CancellationException e) {
      log.debug("Stopped deleting the unreferenced content files of {}.", contentDirectory);
    } catch (IOException | RuntimeException e) {
      if (!cancelled) {
        log.warn("Failed to delete the unreferenced content files of {}; they are kept.", contentDirectory, e);
      }
    } finally {
      store.deregisterVersionUsage(snapshotUsage);
    }
  }

  private boolean hasLegacyBucketMetadata() throws IOException {
    try (DirectoryStream<Path> legacy = Files.newDirectoryStream(dataDirectory,
        "*" + LEGACY_BUCKET_METADATA_SUFFIX)) {
      return legacy.iterator().hasNext();
    }
  }

  /**
   * Walk the subdirectories {@code ab/cd/} of the content directory, and delete the files named by an ID that isn't
   * referenced.
   */
  private Result deleteUnreferenced(Path contentDirectory, long[] referenced) throws IOException {
    long files = 0;
    long bytes = 0;
    try (DirectoryStream<Path> firstLevel = Files.newDirectoryStream(contentDirectory,
        UnreferencedContentSweeper::isShardDirectory)) {
      for (Path first : firstLevel) {
        try (DirectoryStream<Path> secondLevel = Files.newDirectoryStream(first,
            UnreferencedContentSweeper::isShardDirectory)) {
          for (Path second : secondLevel) {
            try (DirectoryStream<Path> contents = Files.newDirectoryStream(second, ShardedFileLayout::isIdFile)) {
              for (Path file : contents) {
                if (cancelled) {
                  throw new CancellationException();
                }
                long id = Long.parseLong(file.getFileName().toString());
                if (Arrays.binarySearch(referenced, id) >= 0) {
                  continue;
                }
                long size = deleteIfModifiedBefore(file);
                if (size >= 0) {
                  files++;
                  bytes += size;
                }
              }
            }
          }
        }
      }
    }
    return new Result(files, bytes);
  }

  /**
   * Delete a file if it was last modified before {@linkplain #modifiedBefore}, checked right before it is deleted.
   *
   * @return the size of the deleted file; {@code -1} if it was kept.
   */
  private long deleteIfModifiedBefore(Path file) {
    try {
      if (!Files.getLastModifiedTime(file).toInstant().isBefore(modifiedBefore)) {
        return -1;
      }
      long size = Files.size(file);
      return Files.deleteIfExists(file) ? size : -1;
    } catch (IOException e) {
      log.warn("Failed to delete the unreferenced content file {}.", file, e);
      return -1;
    }
  }

  /**
   * Whether a path is a subdirectory of the sharded layout, named by two lowercase hex digits; see
   * {@linkplain ShardedFileLayout#shardedPath(Path, long)}.
   */
  private static boolean isShardDirectory(Path path) {
    String name = path.getFileName().toString();
    return name.length() == 2 && isLowerHex(name.charAt(0)) && isLowerHex(name.charAt(1)) && Files.isDirectory(path);
  }

  private static boolean isLowerHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
  }

}
