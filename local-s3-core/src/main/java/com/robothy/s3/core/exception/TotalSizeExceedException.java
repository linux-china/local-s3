package com.robothy.s3.core.exception;

/**
 * The content of an in-memory storage would exceed its max total size. It is answered with
 * {@linkplain S3ErrorCode#InsufficientStorage}, so that a client gets an explicit error before the heap of the JVM runs
 * out.
 */
public class TotalSizeExceedException extends LocalS3Exception {

  private final long limit;

  private final long actual;

  public TotalSizeExceedException(long limit, long actual) {
    super(S3ErrorCode.InsufficientStorage, "The in-memory storage of LocalS3 holds at most " + limit
        + " bytes, and storing the request would take " + actual + " bytes. Delete objects, raise the limit with "
        + "LOCAL_S3_IN_MEMORY_MAX_BYTES (local-s3.in-memory.max-size), or use the PERSISTENCE mode, which stores the "
        + "content on disk.");
    this.limit = limit;
    this.actual = actual;
  }

  /**
   * The max total size in bytes.
   */
  public long getLimit() {
    return limit;
  }

  /**
   * The total size in bytes that storing the request would take.
   */
  public long getActual() {
    return actual;
  }

}
