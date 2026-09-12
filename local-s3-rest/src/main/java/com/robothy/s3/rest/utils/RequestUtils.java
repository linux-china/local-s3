package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.constants.AmzHeaderValues;
import com.robothy.s3.rest.model.request.DecodedAmzRequestBody;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * HTTP Request related utils.
 */
public class RequestUtils {

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
        result.setDecodedBody(new AwsChunkedDecodingInputStream(new ByteBufInputStream(request.getBody())));
        result.setDecodedContentLength(request.header(AmzHeaderNames.X_AMZ_DECODED_CONTENT_LENGTH).map(Long::parseLong)
            .orElseThrow(() -> new IllegalArgumentException(AmzHeaderNames.X_AMZ_DECODED_CONTENT_LENGTH + "header not exist.")));
        break;
      case AmzHeaderValues.STREAMING_UNSIGNED_PAYLOAD_TRAILER:
      case AmzHeaderValues.STREAMING_UNSIGNED_PAYLOAD:
        result.setDecodedBody(new AwsUnsignedChunkedDecodingInputStream(new ByteBufInputStream(request.getBody())));
        result.setDecodedContentLength(request.header(AmzHeaderNames.X_AMZ_DECODED_CONTENT_LENGTH).map(Long::parseLong)
            .orElseThrow(() -> new IllegalArgumentException(AmzHeaderNames.X_AMZ_DECODED_CONTENT_LENGTH + "header not exist.")));
        break;
      case AmzHeaderValues.STREAMING_AWS4_ECDSA_P256_SHA256_PAYLOAD:
      case AmzHeaderValues.STREAMING_AWS4_ECDSA_P256_SHA256_PAYLOAD_TRAILER:
        throw new UnsupportedOperationException("Unsupported payload encoding: " + amzContentSha256);
      default:
        result.setDecodedBody(new ByteBufInputStream(request.getBody()));
        result.setDecodedContentLength(request.header(HttpHeaderNames.CONTENT_LENGTH.toString()).map(Long::parseLong)
            .orElseThrow(() -> new IllegalArgumentException("Content-Length is required.")));
    }

    return result;
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
    if (taggingOpt.isEmpty() || StringUtils.isBlank(tagging = taggingOpt.get())) {
      return Optional.empty();
    }

    String[] tags = tagging.split("&");
    String[][] tagSet = new String[tags.length][2];
    for (int i = 0; i < tags.length; i++) {
      String[] kv = tags[i].split("=");
      if (kv.length != 2) {
        throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_TAGGING, "Invalid tagging format.");
      }

      tagSet[i][0] = kv[0];
      tagSet[i][1] = kv[1];
    }

    return Optional.of(tagSet);
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
