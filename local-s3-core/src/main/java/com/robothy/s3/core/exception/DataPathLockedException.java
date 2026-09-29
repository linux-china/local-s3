package com.robothy.s3.core.exception;

import java.nio.file.Path;

/**
 * The data directory of a {@code PERSISTENCE} service is locked by another process, e.g. another LocalS3 that uses the
 * same directory, so the service can't open it.
 *
 * <p>Unlike a {@linkplain LocalS3Exception}, it isn't the answer to a request: it is raised when a service starts, so
 * that its caller can tell this failure from the others, e.g. to reuse the other LocalS3 or to pick another directory.
 */
public class DataPathLockedException extends RuntimeException {

  private final Path dataPath;

  /**
   * Construct a {@linkplain DataPathLockedException} instance.
   *
   * @param dataPath the data directory that is locked.
   * @param cause the failure to lock it.
   */
  public DataPathLockedException(Path dataPath, Throwable cause) {
    super("The data path " + dataPath + " is locked by another process, e.g. another LocalS3 that uses it.", cause);
    this.dataPath = dataPath;
  }

  /**
   * The data directory that is locked.
   *
   * @return the data directory.
   */
  public Path getDataPath() {
    return dataPath;
  }

}
