package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.constants.LocalS3Constants;
import io.netty.handler.codec.http.HttpHeaderNames;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LocalS3 response utils.
 */
public class ResponseUtils {

  static final DateTimeFormatter RFC_1123_DATE_TIME =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'").withLocale(Locale.ENGLISH);

  /**
   * Add 'date' header.
   *
   * @param response the response to add 'date' header.
   */
  public static void addDateHeader(HttpResponse response) {
    response.putHeader(HttpHeaderNames.DATE.toString(),
        RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)));
  }

  public static String toRfc1123DateTime(long unixTimestamp) {
    return RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(unixTimestamp).atOffset(ZoneOffset.UTC));
  }

  /**
   * The characters of a request ID, like the ones of Amazon S3, e.g. {@code VGEKQFPHD810M604}.
   */
  private static final char[] REQUEST_ID_CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

  private static final int REQUEST_ID_LENGTH = 16;

  /**
   * Generate the ID of a request, which the {@code x-amz-request-id} header of its response carries, and
   * the {@code RequestId} of the body of an error response repeats. Every response uses this, so that the
   * IDs of a service all look the same; they have the shape of the ones of Amazon S3, so that code that
   * logs or parses them sees what it would there.
   *
   * @return a new request ID.
   */
  public static String nextRequestId() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    StringBuilder requestId = new StringBuilder(REQUEST_ID_LENGTH);
    for (int i = 0; i < REQUEST_ID_LENGTH; i++) {
      requestId.append(REQUEST_ID_CHARACTERS[random.nextInt(REQUEST_ID_CHARACTERS.length)]);
    }
    return requestId.toString();
  }

  /**
   * Add 'x-amz-request-id' header with a new request ID.
   *
   * @param response the response to add 'x-amz-request-id' header.
   */
  public static void addAmzRequestId(HttpResponse response) {
    addAmzRequestId(response, nextRequestId());
  }

  /**
   * Add 'x-amz-request-id' header with the given request ID, which an error response also carries in its
   * body, so that the two report the same ID.
   *
   * @param response the response to add 'x-amz-request-id' header.
   * @param requestId the ID of the request.
   */
  public static void addAmzRequestId(HttpResponse response, String requestId) {
    response.putHeader(AmzHeaderNames.X_AMZ_REQUEST_ID, requestId);
  }

  /**
   * Add 'server' header.
   *
   * @param response the response to add 'server' header.
   */
  public static void addServerHeader(HttpResponse response) {
    response.putHeader(HttpHeaderNames.SERVER.toString(), LocalS3Constants.SERVER_NAME);
  }

  /**
   * Add a header, unless its value is {@code null}. {@linkplain HttpResponse#putHeader} renders a
   * {@code null} value as the string {@code "null"}, which a client reads as a value rather than as an
   * absent header. A header whose value isn't known is left out instead, like Amazon S3 does, e.g.
   * {@code x-amz-version-id} of an object in a bucket that was never versioned.
   *
   * <p>An object in a bucket whose versioning is enabled or suspended, but that was stored before, does
   * have a version: the string {@code "null"}, which Amazon S3 answers with as well. That is a value, not
   * a missing one, and is added by this method like any other.
   *
   * @param response the response to add the header to.
   * @param name the name of the header.
   * @param value the value of the header; {@code null} to add no header.
   * @return the response, so that further headers can be chained.
   */
  public static HttpResponse putHeaderIfPresent(HttpResponse response, String name, Object value) {
    if (Objects.nonNull(value)) {
      response.putHeader(name, value);
    }
    return response;
  }

  /**
   * Quote an entity tag for an S3 response.
   *
   * @param etag the entity tag to quote.
   * @return the quoted entity tag, or {@code null} if {@code etag} is {@code null}.
   */
  public static String quoteEtag(String etag) {
    return S3ObjectUtils.quoteEtag(etag);
  }

  /**
   * Add 'ETag' header.
   *
   * @param response the response to add 'ETag' header.
   * @param etag the etag to add
   */
  public static void addETag(HttpResponse response, String etag) {
    if (Objects.nonNull(etag)) {
      response.putHeader(HttpHeaderNames.ETAG.toString(), quoteEtag(etag));
    }
  }

  /**
   * Add common headers to the give response.
   *
   * @param response HTTP response to set common headers.
   * @return the response parameter.
   */
  public static HttpResponse addCommonHeaders(HttpResponse response) {
    addDateHeader(response);
    addAmzRequestId(response);
    addServerHeader(response);
    return response;
  }

}
