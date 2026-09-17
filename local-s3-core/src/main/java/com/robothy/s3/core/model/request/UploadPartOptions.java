package com.robothy.s3.core.model.request;

import com.robothy.s3.core.model.internal.CustomerEncryption;
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

  /**
   * The checksum to upload the part with; {@code null} for the one of the upload, if it has one.
   */
  private RequestChecksum checksum;

  /**
   * The customer-provided key of the request, which must be the one the upload was created with; {@code null} for
   * none.
   */
  private CustomerEncryption customerEncryption;

  public Optional<String> getETag() {
    return Optional.ofNullable(etag);
  }

}
