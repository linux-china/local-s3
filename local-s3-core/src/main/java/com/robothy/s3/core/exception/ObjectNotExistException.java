package com.robothy.s3.core.exception;

/**
 * Object not exists exception. Client side exception.
 *
 * <p>The message is the one of Amazon S3, {@code The specified key does not exist.}; the key is reported by
 * the {@code <Key>} of the error, where Amazon S3 reports it.
 */
public class ObjectNotExistException extends LocalS3Exception {

  private final String deleteMarkerVersionId;

  public ObjectNotExistException(String key) {
    this(key, null);
  }

  private ObjectNotExistException(String key, String deleteMarkerVersionId) {
    super(S3ErrorCode.NoSuchKey);
    setKey(key);
    this.deleteMarkerVersionId = deleteMarkerVersionId;
  }

  /**
   * The key whose current version is a delete marker, which Amazon S3 answers with a {@code NoSuchKey} whose
   * {@code x-amz-delete-marker} and {@code x-amz-version-id} headers name the marker.
   *
   * @param key the object key.
   * @param versionId the version ID of the delete marker, {@code "null"} for the null version.
   * @return the exception.
   */
  public static ObjectNotExistException deleteMarker(String key, String versionId) {
    return new ObjectNotExistException(key, versionId);
  }

  /**
   * Whether the current version of the key is a delete marker.
   *
   * @return {@code true} if it is.
   */
  public boolean isDeleteMarker() {
    return deleteMarkerVersionId != null;
  }

  /**
   * The version ID of the delete marker that is the current version of the key.
   *
   * @return the version ID; {@code null} if the key has no delete marker as its current version.
   */
  public String getDeleteMarkerVersionId() {
    return deleteMarkerVersionId;
  }

}
