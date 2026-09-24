package com.robothy.s3.rest.utils;

import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.constants.AmzHeaderValues;
import com.robothy.s3.rest.model.request.DecodedAmzRequestBody;
import com.robothy.s3.rest.netty.RequestBodies;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import com.robothy.s3.core.util.Strings;

/**
 * HTTP Request related utils.
 */
public class RequestUtils {

  private static final int MAX_TAG_COUNT = 10;
  private static final int MAX_TAG_KEY_LENGTH = 128;
  private static final int MAX_TAG_VALUE_LENGTH = 256;

  /**
   * Get the decoded request body. Decode the request body if needed.
   *
   * @param request HTTP request.
   * @return decoded request body.
   */
  public static DecodedAmzRequestBody getBody(HttpRequest request) {
    DecodedAmzRequestBody result = new DecodedAmzRequestBody();

    String amzContentSha256 = request.header(AmzHeaderNames.X_AMZ_CONTENT_SHA256).orElse("").trim();
    switch (amzContentSha256) {
      case AmzHeaderValues.STREAMING_AWS4_HMAC_SHA_256_PAYLOAD:
      case AmzHeaderValues.STREAMING_AWS4_HMAC_SHA256_PAYLOAD_TRAILER:
        result.setDecodedBody(new AwsChunkedDecodingInputStream(RequestBodies.inputStream(request.getBody())));
        result.setDecodedContentLength(contentLength(request, AmzHeaderNames.X_AMZ_DECODED_CONTENT_LENGTH));
        break;
      case AmzHeaderValues.STREAMING_UNSIGNED_PAYLOAD_TRAILER:
      case AmzHeaderValues.STREAMING_UNSIGNED_PAYLOAD:
        result.setDecodedBody(new AwsUnsignedChunkedDecodingInputStream(RequestBodies.inputStream(request.getBody())));
        result.setDecodedContentLength(contentLength(request, AmzHeaderNames.X_AMZ_DECODED_CONTENT_LENGTH));
        break;
      case AmzHeaderValues.STREAMING_AWS4_ECDSA_P256_SHA256_PAYLOAD:
      case AmzHeaderValues.STREAMING_AWS4_ECDSA_P256_SHA256_PAYLOAD_TRAILER:
        throw new LocalS3RequestException(S3ErrorCode.NotImplemented,
            "The payload signing algorithm " + amzContentSha256 + " is not implemented.");
      default:
        // Taken before the body is read: the body is only the content of its file while it is unread.
        RequestBodies.file(request.getBody()).ifPresent(result::setBodyFile);
        result.setDecodedBody(RequestBodies.inputStream(request.getBody()));
        result.setDecodedContentLength(contentLength(request, HttpHeaderNames.CONTENT_LENGTH.toString()));
    }

    return result;
  }

  /**
   * Read the length of the content that a request stores, e.g. from {@code Content-Length}. Amazon S3 requires it,
   * so a request that sends its body with {@code Transfer-Encoding: chunked} instead is rejected, like Amazon S3
   * does.
   *
   * @throws LocalS3RequestException {@code MissingContentLength} if the request has no such header.
   * @throws LocalS3InvalidArgumentException if the header isn't a length.
   */
  private static long contentLength(HttpRequest request, String headerName) {
    String value = request.header(headerName)
        .orElseThrow(() -> new LocalS3RequestException(S3ErrorCode.MissingContentLength));
    try {
      long length = Long.parseLong(value.trim());
      if (length >= 0) {
        return length;
      }
    } catch (NumberFormatException e) {
      // Rejected below.
    }
    throw new LocalS3InvalidArgumentException(headerName, value, "The value of " + headerName + " is not valid.");
  }

  public static Optional<String> getETag(HttpRequest request) {
    return request.header(HttpHeaderNames.ETAG.toString());
  }

  /**
   * Extract tagging from the HTTP header.
   *
   * @param request HTTP request.
   * @return tagging.
   */
  public static Optional<String[][]> extractTagging(HttpRequest request) {
    Optional<String> taggingOpt = request.header(AmzHeaderNames.X_AMZ_TAGGING);
    String tagging;
    if (taggingOpt.isEmpty() || Strings.isBlank(tagging = taggingOpt.get())) {
      return Optional.empty();
    }

    String[] tags = tagging.split("&", -1);
    if (tags.length > MAX_TAG_COUNT) {
      throw invalidTagging(tagging, "The number of tags must not exceed " + MAX_TAG_COUNT + ".");
    }

    String[][] tagSet = new String[tags.length][2];
    for (int i = 0; i < tags.length; i++) {
      // A tag without '=', e.g. the 'bar' of 'foo=bar&bar', has an empty value, like a parameter of a query string.
      int separator = tags[i].indexOf('=');
      if (separator == 0 || tags[i].isEmpty()) {
        throw invalidTagging(tagging, "Invalid tagging format.");
      }

      String key;
      String value;
      try {
        key = QueryStringDecoder.decodeComponent(separator < 0 ? tags[i] : tags[i].substring(0, separator));
        value = separator < 0 ? "" : QueryStringDecoder.decodeComponent(tags[i].substring(separator + 1));
      } catch (IllegalArgumentException exception) {
        throw invalidTagging(tagging, "Invalid URL encoding in tagging header.");
      }

      if (key.codePointCount(0, key.length()) > MAX_TAG_KEY_LENGTH) {
        throw invalidTagging(tagging, "Tag keys must not exceed " + MAX_TAG_KEY_LENGTH + " characters.");
      }
      if (value.codePointCount(0, value.length()) > MAX_TAG_VALUE_LENGTH) {
        throw invalidTagging(tagging, "Tag values must not exceed " + MAX_TAG_VALUE_LENGTH + " characters.");
      }

      tagSet[i][0] = key;
      tagSet[i][1] = value;
    }

    return Optional.of(tagSet);
  }

  private static LocalS3InvalidArgumentException invalidTagging(String tagging, String message) {
    return new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_TAGGING, tagging, message);
  }


  /**
   * Extract the preconditions of a
   * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13">conditional request</a> from its
   * {@code If-Match}, {@code If-None-Match}, {@code If-Modified-Since} and {@code If-Unmodified-Since}
   * headers. The entity tag headers are passed on as they are; a date that isn't an HTTP date is left out,
   * i.e. is not a precondition at all, like RFC 9110 requires.
   *
   * @param request HTTP request.
   * @return the preconditions of the request; {@linkplain ObjectPreconditions#none()} if it carries none.
   */
  public static ObjectPreconditions extractPreconditions(HttpRequest request) {
    return ObjectPreconditions.builder()
        .ifMatch(request.header(HttpHeaderNames.IF_MATCH).orElse(null))
        .ifNoneMatch(request.header(HttpHeaderNames.IF_NONE_MATCH).orElse(null))
        .ifModifiedSince(httpDate(request, HttpHeaderNames.IF_MODIFIED_SINCE))
        .ifUnmodifiedSince(httpDate(request, HttpHeaderNames.IF_UNMODIFIED_SINCE))
        .build();
  }

  /**
   * Extract the conditions of the source object of a copy from the {@code x-amz-copy-source-if-match},
   * {@code x-amz-copy-source-if-none-match}, {@code x-amz-copy-source-if-modified-since} and
   * {@code x-amz-copy-source-if-unmodified-since} headers of {@code CopyObject} or {@code UploadPartCopy}. Like the
   * conditions of a read, a date that isn't an HTTP date is left out.
   *
   * @param request HTTP request.
   * @return the conditions of the source object.
   */
  public static ObjectPreconditions extractCopySourcePreconditions(HttpRequest request) {
    return ObjectPreconditions.builder()
        .ifMatch(request.header(AmzHeaderNames.X_AMZ_COPY_SOURCE_IF_MATCH).orElse(null))
        .ifNoneMatch(request.header(AmzHeaderNames.X_AMZ_COPY_SOURCE_IF_NONE_MATCH).orElse(null))
        .ifModifiedSince(httpDate(request, AmzHeaderNames.X_AMZ_COPY_SOURCE_IF_MODIFIED_SINCE))
        .ifUnmodifiedSince(httpDate(request, AmzHeaderNames.X_AMZ_COPY_SOURCE_IF_UNMODIFIED_SINCE))
        .build();
  }

  /**
   * Extract the conditions of the object that {@code RenameObject} renames from its {@code x-amz-rename-source-if-*}
   * headers. Like the conditions of a read, a date that isn't an HTTP date is left out.
   *
   * @param request HTTP request.
   * @return the conditions of the object to rename.
   */
  public static ObjectPreconditions extractRenameSourcePreconditions(HttpRequest request) {
    return ObjectPreconditions.builder()
        .ifMatch(request.header(AmzHeaderNames.X_AMZ_RENAME_SOURCE_IF_MATCH).orElse(null))
        .ifNoneMatch(request.header(AmzHeaderNames.X_AMZ_RENAME_SOURCE_IF_NONE_MATCH).orElse(null))
        .ifModifiedSince(httpDate(request, AmzHeaderNames.X_AMZ_RENAME_SOURCE_IF_MODIFIED_SINCE))
        .ifUnmodifiedSince(httpDate(request, AmzHeaderNames.X_AMZ_RENAME_SOURCE_IF_UNMODIFIED_SINCE))
        .build();
  }

  /**
   * Whether a request bypasses the governance mode retention of the object versions it deletes or changes.
   *
   * @param request HTTP request.
   * @return {@code true} if the request sends {@code x-amz-bypass-governance-retention: true}.
   */
  public static boolean isBypassGovernanceRetention(HttpRequest request) {
    return request.header(AmzHeaderNames.X_AMZ_BYPASS_GOVERNANCE_RETENTION)
        .map(value -> "true".equalsIgnoreCase(value.trim()))
        .orElse(false);
  }

  /**
   * Extract the conditions of a
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-deletes.html">conditional delete</a>
   * from the {@code If-Match}, {@code x-amz-if-match-last-modified-time} and {@code x-amz-if-match-size} headers of
   * {@code DeleteObject}. Unlike a date of a read, a malformed value is rejected rather than left out, which would
   * delete the object unconditionally.
   *
   * @param request HTTP request.
   * @return the conditions of the delete.
   * @throws LocalS3InvalidArgumentException if the time isn't an HTTP or ISO 8601 date, or the size isn't a number.
   */
  public static ObjectPreconditions extractDeletePreconditions(HttpRequest request) {
    Long lastModifiedTime = request.header(AmzHeaderNames.X_AMZ_IF_MATCH_LAST_MODIFIED_TIME)
        .map(value -> parseTimestamp(AmzHeaderNames.X_AMZ_IF_MATCH_LAST_MODIFIED_TIME, value))
        .orElse(null);
    Long size = request.header(AmzHeaderNames.X_AMZ_IF_MATCH_SIZE)
        .map(value -> parseSize(AmzHeaderNames.X_AMZ_IF_MATCH_SIZE, value))
        .orElse(null);
    return ObjectPreconditions.builder()
        .ifMatch(request.header(HttpHeaderNames.IF_MATCH).orElse(null))
        .ifMatchLastModifiedTime(lastModifiedTime)
        .ifMatchSize(size)
        .build();
  }

  private static long parseTimestamp(String headerName, String value) {
    Date httpDate = DateFormatter.parseHttpDate(value);
    if (httpDate != null) {
      return httpDate.getTime();
    }
    try {
      return Instant.parse(value.trim()).toEpochMilli();
    } catch (DateTimeParseException e) {
      throw new LocalS3InvalidArgumentException(headerName, value, "The timestamp must be an HTTP date.");
    }
  }

  private static long parseSize(String headerName, String value) {
    try {
      long size = Long.parseLong(value.trim());
      if (size >= 0) {
        return size;
      }
    } catch (NumberFormatException e) {
      // Reported below.
    }
    throw new LocalS3InvalidArgumentException(headerName, value, "The size must be a non-negative number of bytes.");
  }

  /**
   * Parse a header that carries an HTTP date into epoch milliseconds. {@linkplain DateFormatter} accepts
   * all three formats that RFC 9110 requires a recipient to, i.e. the preferred {@code IMF-fixdate} and the
   * two obsolete ones.
   *
   * @param request HTTP request.
   * @param headerName the name of the header.
   * @return the date in epoch milliseconds; {@code null} if the request doesn't carry the header, or its
   *     value isn't an HTTP date.
   */
  private static Long httpDate(HttpRequest request, CharSequence headerName) {
    return request.header(headerName)
        .map(DateFormatter::parseHttpDate)
        .map(Date::getTime)
        .orElse(null);
  }

  /**
   * Extract user metadata from headers. User metadata in headers that start with {@linkplain AmzHeaderNames#X_AMZ_META_PREFIX}.
   *
   * <p><a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html#UserMetadata">User-defined object metadata</a>
   *
   * @param request HTTP request
   * @return fetched user metadata.
   */
  public static Map<String, String> extractUserMetadata(HttpRequest request) {
    Map<String, String> userMetadata = new HashMap<>();
    request.getHeaders()
        .forEach((k, v) -> {
          if (k.toString().startsWith(AmzHeaderNames.X_AMZ_META_PREFIX)) {
            String metaName = RequestAssertions.assertUserMetadataHeaderIsValid(k.toString());
            userMetadata.put(metaName, v);
          }
        });
    return userMetadata;
  }


  public boolean isSdk1() {
    return false;
  }

}
