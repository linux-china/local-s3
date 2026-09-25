package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads the path of a request as it arrived, and splits it into segments that are decoded one at a time.
 *
 * <p>The order matters, and it is the whole reason this exists. The APIs that LocalS3 answers beside Amazon S3 carry
 * values in their paths that hold a {@code /}: a namespace level or a table name of the Iceberg REST protocol, and the
 * ARN of a table bucket in every path of the S3 Tables API, e.g.
 * {@code /buckets/arn%3Aaws%3As3tables%3Aus-east-1%3A000000000000%3Abucket%2Fsales}. A client escapes those as
 * {@code %2F} so that they stay inside one segment. Reading the decoded path of the request instead would split them
 * apart and lose the value, so the raw URI is taken and the segments are decoded after the split.
 */
public final class RequestPaths {

  private RequestPaths() {
  }

  /**
   * The path of a request as it arrived: without its query string, and without the escapes decoded.
   *
   * @param request the request.
   * @return the raw path; the decoded path of the request if it carries no URI.
   */
  public static String rawPath(HttpRequest request) {
    String uri = request.getUri();
    if (uri == null) {
      return Objects.toString(request.getPath(), "");
    }
    int end = uri.length();
    for (int i = 0; i < uri.length(); i++) {
      char c = uri.charAt(i);
      if (c == '?' || c == '#') {
        end = i;
        break;
      }
    }
    return uri.substring(0, end);
  }

  /**
   * The segments of a raw path, each one URL-decoded, with the empty ones dropped.
   *
   * @param rawPath the path, as it arrived.
   * @return the segments.
   * @throws IllegalArgumentException if an escape of the path is malformed.
   */
  public static List<String> decodedSegments(String rawPath) {
    List<String> segments = new ArrayList<>();
    for (String segment : rawPath.split("/")) {
      if (!segment.isEmpty()) {
        segments.add(decode(segment));
      }
    }
    return segments;
  }

  /**
   * Decode the {@code %XX} escapes of one path segment, as UTF-8.
   *
   * <p>Only {@code %XX} is decoded: a {@code +} in a path is a plus, not a space.
   *
   * @param segment the segment.
   * @return the decoded segment.
   * @throws IllegalArgumentException if an escape is malformed, which a client shouldn't send.
   */
  public static String decode(String segment) {
    if (segment.indexOf('%') < 0) {
      return segment;
    }
    ByteBuf decoded = Unpooled.buffer(segment.length());
    try {
      for (int i = 0; i < segment.length(); i++) {
        char c = segment.charAt(i);
        if (c != '%') {
          decoded.writeCharSequence(String.valueOf(c), StandardCharsets.UTF_8);
          continue;
        }
        if (i + 2 >= segment.length()) {
          throw new IllegalArgumentException("Malformed escape in the path: " + segment);
        }
        int high = Character.digit(segment.charAt(i + 1), 16);
        int low = Character.digit(segment.charAt(i + 2), 16);
        if (high < 0 || low < 0) {
          throw new IllegalArgumentException("Malformed escape in the path: " + segment);
        }
        decoded.writeByte((high << 4) + low);
        i += 2;
      }
      return decoded.toString(StandardCharsets.UTF_8);
    } finally {
      decoded.release();
    }
  }

  /**
   * Encode a value as one path segment, the reverse of {@linkplain #decode}: every byte but an unreserved character
   * is escaped as {@code %XX}, so a {@code /} of the value stays inside the segment.
   *
   * @param value the value.
   * @return the encoded segment.
   */
  public static String encode(String value) {
    StringBuilder encoded = new StringBuilder(value.length());
    for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
      int c = item & 0xff;
      if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
          || c == '-' || c == '_' || c == '.' || c == '~') {
        encoded.append((char) c);
      } else {
        encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return encoded.toString();
  }

  /**
   * Every value of a query parameter of a request, read off the raw URI.
   *
   * <p>A parameter that a client repeats, which is how the AWS SDKs send a list, e.g. the {@code tagKeys} of
   * {@code UntagResource}, reaches the handler as one value through {@link HttpRequest#parameter}; this answers them
   * all.
   *
   * @param request the request.
   * @param name the name of the parameter.
   * @return the values, in the order they were sent; empty if the request carries none.
   */
  public static List<String> queryValues(HttpRequest request, String name) {
    List<String> values = new ArrayList<>();
    String uri = Objects.toString(request.getUri(), "");
    int start = uri.indexOf('?');
    if (start < 0) {
      return values;
    }
    for (String entry : uri.substring(start + 1).split("&")) {
      if (entry.isEmpty()) {
        continue;
      }
      int separator = entry.indexOf('=');
      String key = separator < 0 ? entry : entry.substring(0, separator);
      if (!name.equals(decodeQuery(key))) {
        continue;
      }
      values.add(separator < 0 ? "" : decodeQuery(entry.substring(separator + 1)));
    }
    return values;
  }

  /**
   * Decode a piece of a query string, where a {@code +} is a space.
   */
  private static String decodeQuery(String value) {
    return decode(value.replace("+", "%20"));
  }

}
