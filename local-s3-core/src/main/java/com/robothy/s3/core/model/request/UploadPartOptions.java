package com.robothy.s3.core.model.request;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UploadPartOptions {

  private long contentLength;

  private InputStream data;

  /**
   * A file that holds exactly the {@linkplain #data}, e.g. the file that a large request body was buffered in;
   * {@code null} if there is none. The storage may take the file over instead of copying the data, and the
   * caller must not rely on the file afterwards.
   */
  private Path dataFile;

  private String etag;

  public Optional<String> getETag() {
    return Optional.ofNullable(etag);
  }

}
