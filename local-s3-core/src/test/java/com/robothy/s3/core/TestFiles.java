package com.robothy.s3.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Files of the tests.
 */
public final class TestFiles {

  private TestFiles() {
  }

  /**
   * Delete a directory and everything in it; nothing if it doesn't exist.
   *
   * @param directory the directory.
   * @throws IOException if a file can't be deleted.
   */
  public static void deleteDirectory(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    List<Path> paths;
    try (Stream<Path> walk = Files.walk(directory)) {
      // Children before their parents.
      paths = walk.sorted(Comparator.reverseOrder()).toList();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    for (Path path : paths) {
      Files.deleteIfExists(path);
    }
  }

}
