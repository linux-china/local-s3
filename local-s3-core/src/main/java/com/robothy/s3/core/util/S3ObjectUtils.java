package com.robothy.s3.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.List;
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
   * The entity tag that Amazon S3 gives an object that was uploaded in parts: the MD5 digest of the
   * concatenated MD5 digests of its parts, followed by {@code -} and the number of parts, e.g.
   * {@code 3858f62230ac3c915f300c664312c11f-9}.
   *
   * <p>The digest of the whole content is not a substitute, even though it identifies the content just as
   * well: the {@code -<parts>} suffix is what a client reads an object's part layout off, so code that tells
   * an object uploaded in parts from one uploaded at once, e.g. to decide whether the entity tag may be
   * compared with the MD5 of a local file, takes the other branch against an entity tag without the suffix.
   *
   * @param partDigests the MD5 digests of the parts, in ascending order of their part number.
   * @return the entity tag of the object that the parts were concatenated into.
   */
  public static String compositeEtag(List<byte[]> partDigests) {
    MessageDigest md5 = DigestUtils.getMd5Digest();
    partDigests.forEach(md5::update);
    return etag(md5) + "-" + partDigests.size();
  }

  /**
   * Wrap the given input stream so that the MD5 digest of the content is computed while the stream is
   * consumed. The digest is complete once the stream is read to its end, and
   * {@linkplain DigestInputStream#getMessageDigest()} then hands it over.
   *
   * @param inputStream the content to digest.
   * @return a stream that digests the content it reads.
   */
  public static DigestInputStream digestingStream(InputStream inputStream) {
    return new DigestInputStream(inputStream, DigestUtils.getMd5Digest());
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
