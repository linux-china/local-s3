package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.request.RequestChecksum;
import com.robothy.s3.core.util.Checksums;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.model.request.DecodedAmzRequestBody;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The headers of the
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html">checksums</a>
 * that a request stores content with, and that a response answers the checksum of an object with.
 *
 * <p>A client sends a checksum in a header, e.g. {@code x-amz-checksum-crc32}, or, when it sends the content
 * {@code aws-chunked} encoded, in a trailing header that {@code x-amz-trailer} names, which is what the AWS SDKs do by
 * default. {@code x-amz-sdk-checksum-algorithm} names the algorithm without a checksum.
 */
public final class ChecksumHeaders {

  private ChecksumHeaders() {
  }

  /**
   * The checksum that a request stores its body with.
   *
   * @param request the request.
   * @param body the decoded body of the request, whose trailing headers carry a checksum that {@code x-amz-trailer}
   *     names.
   * @return the checksum; {@code null} if the request names no checksum.
   * @throws LocalS3RequestException {@code InvalidRequest} if the request names more than one checksum, a checksum
   *     that isn't one, or an algorithm that doesn't exist.
   */
  public static RequestChecksum fromRequest(HttpRequest request, DecodedAmzRequestBody body) {
    List<CheckSumAlgorithm> inHeaders = headerAlgorithms(request);
    List<CheckSumAlgorithm> inTrailer = request.header(AmzHeaderNames.X_AMZ_TRAILER)
        .map(names -> Arrays.stream(names.split(","))
            .map(Checksums::algorithmOfHeader)
            .flatMap(Optional::stream)
            .toList())
        .orElse(List.of());
    if (inHeaders.size() + inTrailer.size() > 1) {
      throw multipleChecksums();
    }

    CheckSumAlgorithm sdkAlgorithm = algorithm(request, AmzHeaderNames.X_AMZ_SDK_CHECKSUM_ALGORITHM);
    CheckSumAlgorithm algorithm = !inHeaders.isEmpty() ? inHeaders.get(0)
        : !inTrailer.isEmpty() ? inTrailer.get(0) : sdkAlgorithm;
    if (Objects.isNull(algorithm)) {
      return null;
    }
    if (Objects.nonNull(sdkAlgorithm) && sdkAlgorithm != algorithm) {
      throw invalidValue(AmzHeaderNames.X_AMZ_SDK_CHECKSUM_ALGORITHM);
    }

    String headerName = Checksums.headerName(algorithm);
    if (!inHeaders.isEmpty()) {
      String value = request.header(headerName).orElseThrow();
      // Rejected before the content is stored.
      Checksums.decode(algorithm, value);
      return RequestChecksum.of(algorithm, value);
    }
    if (!inTrailer.isEmpty()) {
      return new RequestChecksum(algorithm, () -> body.trailingHeader(headerName)
          .orElseThrow(() -> new LocalS3RequestException(S3ErrorCode.InvalidRequest, "The " + headerName
              + " trailing header that x-amz-trailer names is missing from the request body.")));
    }
    return RequestChecksum.of(algorithm, null);
  }

  /**
   * The checksum of the whole object that a request without content sends in a header, e.g.
   * {@code CompleteMultipartUpload}.
   *
   * @return the checksum; {@code null} if the request sends none.
   * @throws LocalS3RequestException {@code InvalidRequest} if the request sends more than one, or one that isn't a
   *     checksum.
   */
  public static RequestChecksum fromHeaders(HttpRequest request) {
    List<CheckSumAlgorithm> inHeaders = headerAlgorithms(request);
    if (inHeaders.size() > 1) {
      throw multipleChecksums();
    }
    if (inHeaders.isEmpty()) {
      return null;
    }
    CheckSumAlgorithm algorithm = inHeaders.get(0);
    return RequestChecksum.of(algorithm, request.header(Checksums.headerName(algorithm)).orElseThrow());
  }

  /**
   * The algorithm that a header names, e.g. {@code x-amz-checksum-algorithm}.
   *
   * @return the algorithm; {@code null} if the request has no such header.
   * @throws LocalS3RequestException {@code InvalidRequest} if the header names no algorithm.
   */
  public static CheckSumAlgorithm algorithm(HttpRequest request, String headerName) {
    Optional<String> value = request.header(headerName).filter(name -> !name.isBlank());
    if (value.isEmpty()) {
      return null;
    }
    return Checksums.algorithmOf(value.get()).orElseThrow(() -> invalidValue(headerName));
  }

  /**
   * The checksum type that the {@code x-amz-checksum-type} header names.
   *
   * @return the type; {@code null} if the request has no such header.
   * @throws LocalS3RequestException {@code InvalidRequest} if the header names no type.
   */
  public static ChecksumType type(HttpRequest request) {
    Optional<String> value = request.header(AmzHeaderNames.X_AMZ_CHECKSUM_TYPE).filter(type -> !type.isBlank());
    if (value.isEmpty()) {
      return null;
    }
    String name = value.get().trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(ChecksumType.values())
        .filter(type -> type.name().equals(name))
        .findFirst()
        .orElseThrow(() -> invalidValue(AmzHeaderNames.X_AMZ_CHECKSUM_TYPE));
  }

  /**
   * Whether a read asks for the checksum of the object with {@code x-amz-checksum-mode: ENABLED}, which Amazon S3
   * answers {@code GetObject} and {@code HeadObject} with the checksum for, and only then.
   */
  public static boolean isChecksumModeEnabled(HttpRequest request) {
    return request.header(AmzHeaderNames.X_AMZ_CHECKSUM_MODE)
        .map(mode -> "ENABLED".equalsIgnoreCase(mode.trim()))
        .orElse(false);
  }

  /**
   * Add the headers of a checksum, e.g. {@code x-amz-checksum-crc32} and {@code x-amz-checksum-type}.
   *
   * @param response the response.
   * @param checksum the checksum; {@code null} to add nothing.
   */
  public static void addHeaders(HttpResponse response, ObjectChecksum checksum) {
    if (Objects.isNull(checksum) || Objects.isNull(checksum.getAlgorithm())) {
      return;
    }
    response.putHeader(Checksums.headerName(checksum.getAlgorithm()), checksum.getValue());
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_CHECKSUM_TYPE, checksum.getType());
  }

  private static List<CheckSumAlgorithm> headerAlgorithms(HttpRequest request) {
    List<CheckSumAlgorithm> algorithms = new ArrayList<>(1);
    for (CheckSumAlgorithm algorithm : CheckSumAlgorithm.values()) {
      if (request.header(Checksums.headerName(algorithm)).isPresent()) {
        algorithms.add(algorithm);
      }
    }
    return algorithms;
  }

  private static LocalS3RequestException multipleChecksums() {
    return new LocalS3RequestException(S3ErrorCode.InvalidRequest,
        "Expecting a single x-amz-checksum- header. Multiple checksum Types are not allowed.");
  }

  private static LocalS3RequestException invalidValue(String headerName) {
    return new LocalS3RequestException(S3ErrorCode.InvalidRequest, "Value for " + headerName + " header is invalid.");
  }

}
