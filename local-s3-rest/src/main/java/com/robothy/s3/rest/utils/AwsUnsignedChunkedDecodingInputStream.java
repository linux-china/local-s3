package com.robothy.s3.rest.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A counterpart to AwsUnsignedChunkedEncodingInputStream that decodes
 * chunked data without expecting any signature in the chunk extension.
 */
public class AwsUnsignedChunkedDecodingInputStream extends InputStream implements TrailingHeaders {
  private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
  private final InputStream source;
  private int remainingInChunk;
  private final Map<String, String> trailingHeaders = new LinkedHashMap<>();
  private boolean finished;

  public AwsUnsignedChunkedDecodingInputStream(InputStream source) {
    this.source = source;
  }

  @Override
  public int read() throws IOException {
    if (finished) {
      return -1;
    }
    if (remainingInChunk == 0) {
      String chunkSizeHex = readLine();
      while (chunkSizeHex != null && chunkSizeHex.trim().isEmpty()) {
        chunkSizeHex = readLine();
      }
      if (chunkSizeHex == null) {
        finished = true;
        return -1;
      }
      remainingInChunk = AwsChunkedDecodingInputStream.parseChunkSize(chunkSizeHex);
      if (remainingInChunk == 0) {
        // The trailing headers, if any, up to the empty line that ends the body.
        finished = true;
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
          TrailingHeaders.parse(trailingHeaders, line);
        }
        return -1;
      }
    }
    remainingInChunk--;
    return source.read();
  }

  private String readLine() throws IOException {
    StringBuilder sb = new StringBuilder();
    int prev = -1;
    int cur;
    while ((cur = source.read()) != -1) {
      if (prev == '\r' && cur == '\n') {
        sb.setLength(sb.length() - 1);
        break;
      }
      sb.append((char) cur);
      prev = cur;
    }
    if (sb.length() == 0 && cur == -1) {
      return null;
    }
    return sb.toString();
  }

  @Override
  public Map<String, String> trailingHeaders() {
    return Collections.unmodifiableMap(trailingHeaders);
  }

  @Override
  public void close() throws IOException {
    source.close();
  }
}
