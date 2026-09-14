package com.robothy.s3.core.util;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PathUtils {

  private static final boolean IS_WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

  /**
   * The number of attempts of a file operation that an open file can make fail, and the delay before the
   * second one, which doubles for each further attempt. The attempts are spread over about half a second.
   */
  private static final int IN_USE_ATTEMPTS = 5;

  private static final long IN_USE_RETRY_DELAY_MILLIS = 30;

  /**
   * A file operation that fails with an {@linkplain IOException}.
   */
  @FunctionalInterface
  interface FileOperation {

    void run() throws IOException;

  }

  /**
   * Run a file operation, repeating it while it fails because a file it touches is open.
   *
   * <p>The content of an object is read after the bucket lock that the read was made under is released:
   * {@linkplain com.robothy.s3.core.service.GetObjectService} returns the stream of an object, which the
   * response is written from afterwards. Windows, unlike POSIX, refuses to replace or delete a file while
   * it is open, so a request that overwrites or deletes an object can fail because a response is still
   * being written from it. Repeating the operation lets it succeed once the reader is done, which is the
   * common case; a reader that keeps an object open for longer than the attempts take still fails, since
   * Windows cannot replace a file that is open however long it is waited for.
   *
   * <p>On POSIX the first attempt always succeeds, so nothing is repeated and nothing is waited for.
   *
   * @param operation the operation to run.
   * @throws IOException if the operation keeps failing, or fails for another reason.
   */
  // Visible for testing.
  static void retryingWhileInUse(FileOperation operation) throws IOException {
    long delay = IN_USE_RETRY_DELAY_MILLIS;
    for (int attempt = 1; ; attempt++) {
      try {
        operation.run();
        return;
      } catch (NoSuchFileException e) {
        throw e; // Repeating the operation won't make a missing file appear.
      } catch (FileSystemException e) {
        if (attempt == IN_USE_ATTEMPTS) {
          throw e;
        }

        try {
          Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
        delay *= 2;
      }
    }
  }

  /**
   * Move {@code source} to {@code target}, replacing {@code target} if it exists. The move is atomic
   * when the file system supports it, so readers see either the old or the new {@code target}. It is
   * {@linkplain #retryingWhileInUse(FileOperation) repeated} while {@code target} is open, which only
   * Windows refuses to replace.
   *
   * @param source the file to move.
   * @param target where the file is moved to.
   * @throws IOException if the file cannot be moved.
   */
  public static void moveAtomically(Path source, Path target) throws IOException {
    retryingWhileInUse(() -> {
      try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
      }
    });
  }

  /**
   * {@linkplain #moveAtomically(Path, Path) Move} a completely written {@code source} to {@code target} so that
   * the move survives a power loss or a crash of the operating system: the content of {@code source} is flushed
   * to the device before the move, and the directory of {@code target} after it. Without the first flush, a file
   * system that commits the rename before the data, e.g. ext4 or XFS, can leave an empty or zero-filled
   * {@code target} behind; without the second, the rename itself can be lost.
   *
   * @param source the file to move, which nothing writes to anymore.
   * @param target where the file is moved to.
   * @throws IOException if the file cannot be flushed or moved.
   */
  public static void moveDurably(Path source, Path target) throws IOException {
    syncFile(source);
    moveAtomically(source, target);
    syncDirectory(target.toAbsolutePath().getParent());
  }

  /**
   * Flush the content and the attributes of a file to the device, like {@code fsync}.
   *
   * @param file the file to flush.
   * @throws IOException if the file cannot be opened or flushed.
   */
  public static void syncFile(Path file) throws IOException {
    // Windows only flushes a file that is open for writing.
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  /**
   * Flush the entries of a directory to the device, so that the files created in it, moved into it or deleted
   * from it stay so after a power loss. It is a best effort: Windows can't open a directory, and NTFS journals
   * its entries anyway, and some file systems, e.g. those that Docker Desktop mounts into a container, refuse to
   * flush a directory; neither fails the write that the directory was changed for.
   *
   * @param directory the directory to flush.
   */
  public static void syncDirectory(Path directory) {
    if (IS_WINDOWS || directory == null) {
      return;
    }
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException e) {
      log.debug("Failed to flush the directory {}.", directory, e);
    }
  }

  /**
   * Delete {@code path}, {@linkplain #retryingWhileInUse(FileOperation) repeating} the deletion while the
   * file is open, which only Windows refuses to delete.
   *
   * @param path the file to delete.
   * @throws IOException if the file cannot be deleted.
   */
  public static void delete(Path path) throws IOException {
    retryingWhileInUse(() -> Files.delete(path));
  }

  /**
   * Resolve the file named {@code fileName} in {@code directory}, ensuring that it is a direct child of
   * {@code directory}. Stores that build file names from client supplied names, e.g. bucket names, resolve
   * them with this method, so that a name holding a path separator or a traversal segment cannot reach a
   * file outside {@code directory}.
   *
   * @param directory the directory that holds the file.
   * @param fileName the name of the file in {@code directory}.
   * @return the resolved file.
   * @throws IllegalArgumentException if {@code fileName} doesn't resolve to a file of {@code directory}.
   */
  public static Path resolveChild(Path directory, String fileName) {
    Path parent = directory.toAbsolutePath().normalize();
    Path resolved = parent.resolve(fileName).normalize();
    if (!parent.equals(resolved.getParent())) {
      throw new IllegalArgumentException("'" + fileName + "' is not a file of the directory " + parent + ".");
    }
    return resolved;
  }

  public static void createDirectoryIfNotExit(Path path) {
    File directory = path.toFile();
    if (!directory.exists() || !directory.isDirectory()) {
      if (!directory.mkdirs()) {
        throw new IllegalStateException("Cannot create directory " + path.toAbsolutePath());
      }
    }
  }

}
