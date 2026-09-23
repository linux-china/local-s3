package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Reads the credential scope of a signed request without verifying it, which is how LocalS3 knows <em>which</em> of
 * the APIs it serves a request is addressed at.
 *
 * <p>The scope of an AWS Signature Version 4 is {@code <key>/<date>/<region>/<service>/aws4_request}, and the service
 * in it is the one the client meant: an SDK signs a request of Amazon S3 for {@code s3}, one of S3 Tables for
 * {@code s3tables}, one of STS for {@code sts}. That is the only thing in a request of the
 * {@link com.robothy.s3.rest.handler.s3tables.S3TablesController S3 Tables API} that tells it from an Amazon S3
 * request, whose paths it shares.
 *
 * <p>Reading the scope is not trusting it. The router uses it to pick the endpoint, and the endpoint then verifies the
 * signature <em>against that service</em>, so a request that claimed a scope it isn't signed for is refused there.
 */
public final class SigV4Requests {

  /**
   * The prefix of the {@code Authorization} header of a signed request.
   */
  private static final String ALGORITHM_PREFIX = "AWS4-";

  /**
   * The attribute of the {@code Authorization} header that carries the credential scope.
   */
  private static final String CREDENTIAL = "Credential=";

  /**
   * The query parameter that carries the credential scope of a presigned URL.
   */
  private static final String PRESIGNED_CREDENTIAL = "X-Amz-Credential";

  private SigV4Requests() {
  }

  /**
   * The service that a request is signed for.
   *
   * @param request the request.
   * @return the service, lower-cased, e.g. {@code s3} or {@code s3tables}; {@code null} if the request carries no
   *     signature, or one whose credential scope can't be read.
   */
  @Nullable
  public static String signingService(HttpRequest request) {
    String fromHeader = request.header(HttpHeaderNames.AUTHORIZATION.toString())
        .filter(authorization -> authorization.startsWith(ALGORITHM_PREFIX))
        .map(SigV4Requests::credentialOf)
        .map(SigV4Requests::serviceOf)
        .orElse(null);
    if (fromHeader != null) {
      return fromHeader;
    }
    List<String> presigned = RequestPaths.queryValues(request, PRESIGNED_CREDENTIAL);
    return presigned.isEmpty() ? null : serviceOf(presigned.get(0));
  }

  /**
   * Whether a request carries an AWS Signature Version 4 at all, rather than another kind of credential such as the
   * bearer token of the Iceberg REST protocol, or none.
   *
   * @param request the request.
   * @return {@code true} if it is signed.
   */
  public static boolean isSigned(HttpRequest request) {
    return request.header(HttpHeaderNames.AUTHORIZATION.toString())
        .map(authorization -> authorization.startsWith(ALGORITHM_PREFIX))
        .orElseGet(() -> !RequestPaths.queryValues(request, "X-Amz-Algorithm").isEmpty());
  }

  /**
   * The value of the {@code Credential} attribute of an {@code Authorization} header.
   */
  @Nullable
  private static String credentialOf(String authorization) {
    // The header is '<algorithm> Credential=..., SignedHeaders=..., Signature=...': past the algorithm first.
    int algorithmEnd = authorization.indexOf(' ');
    if (algorithmEnd < 0) {
      return null;
    }
    for (String attribute : authorization.substring(algorithmEnd + 1).split(",")) {
      String trimmed = attribute.trim();
      if (trimmed.startsWith(CREDENTIAL)) {
        return trimmed.substring(CREDENTIAL.length());
      }
    }
    return null;
  }

  /**
   * The service of a credential scope, i.e. the fourth of its five parts.
   */
  @Nullable
  private static String serviceOf(@Nullable String credential) {
    if (credential == null) {
      return null;
    }
    String[] parts = credential.split("/");
    if (parts.length < 5 || parts[3].isBlank()) {
      return null;
    }
    return parts[3].toLowerCase(Locale.ROOT);
  }

}
