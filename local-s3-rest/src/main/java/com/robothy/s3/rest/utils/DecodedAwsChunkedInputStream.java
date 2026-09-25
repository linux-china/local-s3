package com.robothy.s3.rest.utils;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * The content of an {@code aws-chunked} body that was decoded while it was received, see
 * {@linkplain com.robothy.s3.rest.netty.RequestBodies#awsChunkedTrailer}. Its trailing headers are known before it is
 * read, so that they are known even if the file of the body is taken over by a storage rather than read.
 */
class DecodedAwsChunkedInputStream extends FilterInputStream implements TrailingHeaders {

  private final Map<String, String> trailingHeaders;

  DecodedAwsChunkedInputStream(InputStream decoded, Map<String, String> trailingHeaders) {
    super(decoded);
    this.trailingHeaders = Collections.unmodifiableMap(trailingHeaders);
  }

  @Override
  public Map<String, String> trailingHeaders() {
    return trailingHeaders;
  }

}
