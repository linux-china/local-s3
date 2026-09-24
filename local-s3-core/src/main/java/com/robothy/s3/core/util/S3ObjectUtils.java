package com.robothy.s3.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public class S3ObjectUtils {

  /**
   * A new MD5 digest, which every JVM provides.
   *
   * @return the digest.
   */
  public static MessageDigest md5() {
    try {
      return MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("The JVM provides no MD5 digest.", e);
    }
  }

  /**
   * Calculate the etag of the given input stream.
   */
  public static String etag(InputStream inputStream) {
    DigestInputStream digesting = digestingStream(inputStream);
    try {
      digesting.transferTo(OutputStream.nullOutputStream());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return etag(digesting.getMessageDigest());
  }

  /**
   * Update a digest with the content of a stream, which is read to its end.
   *
   * @param digest the digest.
   * @param inputStream the content.
   * @throws IOException if the content can't be read.
   */
  public static void updateDigest(MessageDigest digest, InputStream inputStream) throws IOException {
    new DigestInputStream(inputStream, digest).transferTo(OutputStream.nullOutputStream());
  }

  /**
   * Calculate the etag from a digest that has consumed the whole object content.
   */
  public static String etag(MessageDigest md5) {
    return HexFormat.of().formatHex(md5.digest());
  }

  /**
   * Quote an entity tag for an S3 HTTP or XML response. Internally entity tags remain unquoted, while S3
   * represents them as quoted strings on the wire.
   *
   * @param etag the entity tag to quote.
   * @return the quoted entity tag, or {@code null} if {@code etag} is {@code null}.
   */
  public static String quoteEtag(String etag) {
    if (etag == null) {
      return null;
    }
    String value = etag.trim();
    boolean quoted = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
    boolean weaklyQuoted = value.length() >= 4 && value.startsWith("W/\"") && value.endsWith("\"");
    return quoted || weaklyQuoted ? value : "\"" + value + "\"";
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
    MessageDigest md5 = md5();
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
    return new DigestInputStream(inputStream, md5());
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
      super(in, md5());
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
   * Encode the given string to url format except slash, like {@linkplain #urlEncode(String, boolean)}.
   */
  public static String urlEncodeEscapeSlash(String str) {
    return urlEncode(str, true);
  }

  /**
   * Percent-encode a string like RFC 3986 does, which is how Amazon S3 answers {@code encoding-type=url}, and what a
   * URL path or query takes: every byte of its UTF-8 form but the unreserved characters ({@code A-Z a-z 0-9 - . _ ~})
   * is encoded. A space is {@code %20}, not the {@code +} of {@linkplain java.net.URLEncoder}, which a client that
   * decodes by RFC 3986 reads back as a plus, so that it asks for a key that doesn't exist.
   *
   * @param str the string to encode; {@code null} for none.
   * @param keepSlash whether a {@code /} is kept as it is, e.g. in an object key or a path.
   * @return the encoded string; {@code null} if {@code str} is {@code null}.
   */
  public static String urlEncode(String str, boolean keepSlash) {
    if (str == null) {
      return null;
    }
    byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(bytes.length + 16);
    for (byte b : bytes) {
      char c = (char) (b & 0xFF);
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '.' || c == '_' || c == '~' || (keepSlash && c == '/')) {
        encoded.append(c);
      } else {
        encoded.append('%').append(HEX_DIGITS[c >> 4]).append(HEX_DIGITS[c & 0xF]);
      }
    }
    return encoded.toString();
  }

  private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

}
