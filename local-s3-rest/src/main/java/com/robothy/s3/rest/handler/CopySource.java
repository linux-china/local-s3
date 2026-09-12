package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The source object of a copy, parsed from the {@code x-amz-copy-source} header that
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html">CopyObject</a> and
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPartCopy.html">UploadPartCopy</a> share.
 *
 * <p>The header names the source as {@code /bucket/key} or {@code bucket/key}, optionally followed by
 * {@code ?versionId=...}. Its parts are URL encoded, and are decoded here.
 */
record CopySource(String bucket, String key, String versionId) {

  /**
   * Read and parse the {@code x-amz-copy-source} header of a request.
   *
   * @param request the copy request.
   * @return the parsed source object.
   * @throws IllegalArgumentException if the request has no {@code x-amz-copy-source} header.
   * @throws LocalS3InvalidArgumentException if the header doesn't name a bucket and a key.
   */
  static CopySource of(HttpRequest request) {
    return parse(request.header(AmzHeaderNames.X_AMZ_COPY_SOURCE).orElseThrow(() ->
        new IllegalArgumentException(AmzHeaderNames.X_AMZ_COPY_SOURCE + " header is required.")));
  }

  /**
   * Parse the value of an {@code x-amz-copy-source} header.
   *
   * @param copySource the header value.
   * @return the parsed source object.
   * @throws LocalS3InvalidArgumentException if the value doesn't name a bucket and a key.
   */
  static CopySource parse(String copySource) {
    String[] slices = copySource.split("\\?");
    String path = slices[0];

    int delimiterIndex;
    if (-1 == (delimiterIndex = path.indexOf('/', 1)) || delimiterIndex == path.length() - 1) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_COPY_SOURCE, copySource, "Invalid copy source.");
    }

    String bucket = path.charAt(0) == '/' ? path.substring(1, delimiterIndex)
        : path.substring(0, delimiterIndex);
    String key = path.charAt(path.length() - 1) == '/' ? path.substring(delimiterIndex + 1, path.length() - 1)
        : path.substring(delimiterIndex + 1);

    return new CopySource(urlDecode(bucket), urlDecode(key), urlDecode(versionId(slices)));
  }

  /**
   * Extract the version ID from the query of the header, if it has one.
   */
  private static String versionId(String[] pathSlices) {
    if (pathSlices.length <= 1) {
      return null;
    }

    String[] pairs = pathSlices[1].split("\\&");
    return Stream.of(pairs)
        .filter(pair -> pair.startsWith("versionId") && pair.contains("="))
        .map(pair -> pair.split("=")[1])
        .findAny()
        .orElse(null);
  }

  private static String urlDecode(String value) {
    if (Objects.isNull(value)) {
      return null;
    }
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

}
