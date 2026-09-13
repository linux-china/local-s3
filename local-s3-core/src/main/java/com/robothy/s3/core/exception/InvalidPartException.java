package com.robothy.s3.core.exception;

/**
 * A part that completes a multipart upload doesn't identify a part of the upload: it was never uploaded, its
 * entity tag doesn't match the one the upload of the part answered, or it was uploaded again while the upload
 * was being completed.
 */
public class InvalidPartException extends LocalS3Exception {

  private InvalidPartException(String message) {
    super(S3ErrorCode.InvalidPart, S3ErrorCode.InvalidPart.description() + " " + message);
  }

  /**
   * The part was never uploaded to the upload.
   *
   * @param partNumber the part number that completes the upload.
   * @return the exception.
   */
  public static InvalidPartException notUploaded(int partNumber) {
    return new InvalidPartException("Part " + partNumber + " has not been uploaded.");
  }

  /**
   * The entity tag that completes the upload isn't the one of the part that was uploaded.
   *
   * @param partNumber the part number that completes the upload.
   * @param expectedEtag the entity tag of the uploaded part.
   * @param actualEtag the entity tag that completes the upload.
   * @return the exception.
   */
  public static InvalidPartException etagMismatch(int partNumber, String expectedEtag, String actualEtag) {
    return new InvalidPartException("Part " + partNumber + " has the entity tag " + expectedEtag
        + ", not " + actualEtag + ".");
  }

  /**
   * The part was uploaded again after the upload was validated, so the data that was concatenated is no
   * longer the data of the part.
   *
   * @param partNumber the part number that completes the upload.
   * @return the exception.
   */
  public static InvalidPartException replaced(int partNumber) {
    return new InvalidPartException("Part " + partNumber + " was uploaded again while the upload was being completed.");
  }

}
