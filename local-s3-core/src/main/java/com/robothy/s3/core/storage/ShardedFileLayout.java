package com.robothy.s3.core.storage;

import com.robothy.s3.core.util.PathUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The layout of a directory that keeps one file per ID, e.g. the objects of a storage or the vectors of a vector
 * storage: the files are spread over two levels of subdirectories, {@code ab/cd/<id>}, so that no directory holds more
 * than a small share of them. A directory with millions of entries is slow to list, and slow to look files up in on some
 * file systems. The subdirectories are named by a hash of the ID rather than by its bits, since the high bits of the IDs
 * of the generator are a timestamp, and the low bits a sequence that is mostly zero, so either would put the files
 * stored around the same time in the same directory.
 *
 * <p>The files of the flat layout, which kept every file directly in the directory, can be moved into their
 * subdirectories, one rename at a time, so that a process that dies meanwhile leaves every file in one place or the
 * other.
 *
 * <p>The layout decides where the files that are already stored are found, so it must never change.
 */
public final class ShardedFileLayout {

  private static final String[] SHARD_NAMES = new String[256];

  static {
    for (int i = 0; i < SHARD_NAMES.length; i++) {
      SHARD_NAMES[i] = String.format("%02x", i);
    }
  }

  private ShardedFileLayout() {
  }

  /**
   * The file of an ID: {@code <directory>/ab/cd/<id>}, where {@code ab} and {@code cd} are the two highest bytes of
   * {@linkplain #shardHash(long) the hash of the ID}, as two lowercase hex digits each.
   *
   * @param directory the directory of the files.
   * @param id the ID.
   * @return the file of the ID, whose subdirectories may not exist.
   */
  public static Path shardedPath(Path directory, long id) {
    long hash = shardHash(id);
    return directory.resolve(SHARD_NAMES[(int) (hash >>> 56)])
        .resolve(SHARD_NAMES[(int) (hash >>> 48) & 0xff])
        .resolve(String.valueOf(id));
  }

  /**
   * The file of an ID in the flat layout: {@code <directory>/<id>}.
   *
   * @param directory the directory of the files.
   * @param id the ID.
   * @return the file of the ID.
   */
  public static Path flatPath(Path directory, long id) {
    return directory.resolve(String.valueOf(id));
  }

  /**
   * The finalizer of MurmurHash3 ({@code fmix64}), whose every output bit depends on every input bit, so the
   * subdirectories are used evenly whatever the IDs have in common, e.g. the IDs generated in a burst, which differ
   * only in their low bits. Fibonacci hashing, i.e. a single multiplication, leaves about a fifth of the subdirectories
   * unused for such IDs.
   */
  static long shardHash(long id) {
    long hash = id;
    hash ^= hash >>> 33;
    hash *= 0xff51afd7ed558ccdL;
    hash ^= hash >>> 33;
    hash *= 0xc4ceb9fe1a85ec53L;
    hash ^= hash >>> 33;
    return hash;
  }

  /**
   * Move the files of the flat layout into their subdirectories. Each file is renamed atomically, so a file is always in
   * one of the layouts. Entries that aren't files named by an ID are left alone.
   *
   * @param directory the directory of the files.
   * @return the number of files moved.
   * @throws UncheckedIOException if a file can't be moved.
   */
  public static long moveFlatFilesIntoSubdirectories(Path directory) {
    long moved = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, ShardedFileLayout::isIdFile)) {
      for (Path file : entries) {
        Path target = shardedPath(directory, Long.parseLong(file.getFileName().toString()));
        Files.createDirectories(target.getParent());
        PathUtils.moveAtomically(file, target);
        moved++;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to move the files of " + directory + " into subdirectories.", e);
    }
    return moved;
  }

  /**
   * Whether a path is a regular file named by an ID, i.e. by a non-negative {@code long}.
   *
   * @param path the path.
   * @return whether the path is the file of an ID.
   */
  public static boolean isIdFile(Path path) {
    return isIdName(path.getFileName().toString()) && Files.isRegularFile(path);
  }

  private static boolean isIdName(String name) {
    if (name.isEmpty() || name.length() > 19) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      if (name.charAt(i) < '0' || name.charAt(i) > '9') {
        return false;
      }
    }
    try {
      Long.parseLong(name);
      return true;
    } catch (NumberFormatException e) {
      // Too large to be an ID.
      return false;
    }
  }

}
