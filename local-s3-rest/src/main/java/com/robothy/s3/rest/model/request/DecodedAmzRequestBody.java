package com.robothy.s3.rest.model.request;

import java.io.InputStream;
import com.robothy.s3.rest.utils.TrailingHeaders;
import java.nio.file.Path;
import java.util.Optional;
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
   * {@code null} if the body is buffered on the heap, or is {@code aws-chunked} encoded and wasn't decoded while it was
   * received, so that the file holds the encoded body.
   */
  private Path bodyFile;

  /**
   * A trailing header of an {@code aws-chunked} body, which is only known once the body is read to its end.
   *
   * @param name the name of the header, in lower case.
   * @return the value of the header; empty if the body has no such trailing header, or isn't read to its end yet,
   *     unless it was decoded while it was received, whose trailing headers are known before it is read.
   */
  public Optional<String> trailingHeader(String name) {
    return decodedBody instanceof TrailingHeaders trailing
        ? Optional.ofNullable(trailing.trailingHeaders().get(name))
        : Optional.empty();
  }

}
