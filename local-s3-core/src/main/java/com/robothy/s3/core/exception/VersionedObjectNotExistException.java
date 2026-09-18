package com.robothy.s3.core.exception;

/**
 * Versioned object not exists. Client side exception.
 *
 * <p>The message is the one of Amazon S3; the key and the version are reported by the {@code <Key>} and the
 * {@code <VersionId>} of the error, where Amazon S3 reports them.
 */
public class VersionedObjectNotExistException extends LocalS3Exception {

  /**
   * Create a {@linkplain VersionedObjectNotExistException} instance.
   *
   * @param key the object key.
   * @param versionId the version ID.
   */
  public VersionedObjectNotExistException(String key, String versionId) {
    this(versionId);
    setKey(key);
  }

  /**
   * Create an instance.
   *
   * @param version the object version.
   */
  public VersionedObjectNotExistException(String version) {
    super(S3ErrorCode.NoSuchVersion);
    setVersionId(version);
  }

}
