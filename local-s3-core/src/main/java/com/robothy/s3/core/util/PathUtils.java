package com.robothy.s3.core.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class PathUtils {

  /**
   * Move {@code source} to {@code target}, replacing {@code target} if it exists. The move is atomic
   * when the file system supports it, so readers see either the old or the new {@code target}.
   *
   * @param source the file to move.
   * @param target where the file is moved to.
   * @throws IOException if the file cannot be moved.
   */
  public static void moveAtomically(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
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
