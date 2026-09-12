package com.robothy.s3.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

public class S3ObjectUtils {

  /**
   * Calculate the etag of the given input stream.
   */
  public static String etag(InputStream inputStream) {
    try {
      return DigestUtils.md5Hex(inputStream);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Calculate the etag from a digest that has consumed the whole object content.
   */
  public static String etag(MessageDigest md5) {
    return Hex.encodeHexString(md5.digest());
  }

  /**
   * Wrap the given input stream so that the MD5 digest and the length of the content are computed while the
   * stream is consumed. The length of a stored object is the number of bytes that were actually read, not
   * the length that the request declares: a client whose {@code Content-Length} or
   * {@code x-amz-decoded-content-length} doesn't match its body would otherwise leave a wrong size in the
   * metadata, which is what the {@code Content-Length} of the responses and the resolution of range requests
   * are computed from.
   *
   * @param inputStream the content to measure.
   * @return a stream that measures the content it reads.
   */
  public static MeasuredInputStream measuringStream(InputStream inputStream) {
    return new MeasuredInputStream(inputStream);
  }

  /**
   * An input stream that computes the MD5 digest of the content it reads and counts its bytes.
   */
  public static final class MeasuredInputStream extends DigestInputStream {

    private long size;

    private MeasuredInputStream(InputStream in) {
      super(in, DigestUtils.getMd5Digest());
    }

    @Override
    public int read() throws IOException {
      int read = super.read();
      if (read != -1) {
        size++;
      }
      return read;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int read = super.read(b, off, len);
      if (read > 0) {
        size += read;
      }
      return read;
    }

    /**
     * The number of bytes read so far, which is the length of the content once the stream is fully read.
     *
     * @return the length of the content read.
     */
    public long getSize() {
      return size;
    }

    /**
     * The etag of the content read, which is its MD5 digest. Call it once, after the stream is fully read.
     *
     * @return the etag of the content read.
     */
    public String etag() {
      return S3ObjectUtils.etag(getMessageDigest());
    }
  }

  /**
   * Encode the given string to url format except slash.
   */
  public static String urlEncodeEscapeSlash(String str) {
    if (str == null) {
      return null;
    }
    String[] ss = str.split("/");
    String encoded = Stream.of(ss)
        .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8))
        .collect(Collectors.joining("/"));
    return str.endsWith("/") ? encoded + "/" : encoded;
  }

}
