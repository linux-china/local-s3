package com.robothy.s3.rest.model.request;

import java.io.InputStream;
import java.nio.file.Path;
import lombok.Data;

/**
 * Decoded Amazon request body.
 */
@Data
public class DecodedAmzRequestBody {

  /**
   * Represents decoded input stream.
   */
  private InputStream decodedBody;

  /**
   * Represents decoded content length.
   */
  private long decodedContentLength;

  /**
   * The temporary file that holds exactly the decoded body, which a storage may take over instead of copying the body;
   * {@code null} if the body is buffered on the heap, or is {@code aws-chunked} encoded, so that the file holds the
   * encoded body.
   */
  private Path bodyFile;

}
