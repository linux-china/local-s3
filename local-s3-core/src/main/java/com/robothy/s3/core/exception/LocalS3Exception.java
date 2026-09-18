package com.robothy.s3.core.exception;

/**
 * Represents exceptions comes from LocalS3.
 */
public abstract class LocalS3Exception extends RuntimeException {

  private final S3ErrorCode s3ErrorCode;

  private String bucketName;

  private String key;

  private String versionId;

  LocalS3Exception(S3ErrorCode s3ErrorCode, String message, Throwable cause) {
    super(message, cause);
    this.s3ErrorCode = s3ErrorCode;
  }

  LocalS3Exception(S3ErrorCode s3ErrorCode, Throwable cause) {
    this(s3ErrorCode, s3ErrorCode.description(), cause);
  }

  LocalS3Exception(S3ErrorCode s3ErrorCode) {
    this(s3ErrorCode, s3ErrorCode.description());
  }

  LocalS3Exception(S3ErrorCode s3ErrorCode, String message) {
    this(s3ErrorCode, message, null);
  }

  LocalS3Exception(String bucketName, S3ErrorCode s3ErrorCode) {
    this(s3ErrorCode);
    this.bucketName = bucketName;
  }

  public S3ErrorCode getS3ErrorCode() {
    return s3ErrorCode;
  }

  public String getBucketName() {
    return this.bucketName;
  }

  protected void setBucketName(String bucketName) {
    this.bucketName = bucketName;
  }

  /**
   * The object key that the failed request named, which the {@code <Key>} of the error reports, e.g. the key
   * of a {@code NoSuchKey}. Amazon S3 names it in the error rather than in the message.
   *
   * @return the object key; {@code null} if the error is about no particular key.
   */
  public String getKey() {
    return this.key;
  }

  protected void setKey(String key) {
    this.key = key;
  }

  /**
   * The version ID that the failed request named, which the {@code <VersionId>} of the error reports.
   *
   * @return the version ID; {@code null} if the error is about no particular version.
   */
  public String getVersionId() {
    return this.versionId;
  }

  protected void setVersionId(String versionId) {
    this.versionId = versionId;
  }

}
