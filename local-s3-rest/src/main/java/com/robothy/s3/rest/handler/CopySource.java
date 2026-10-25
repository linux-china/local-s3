package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.core.util.UrlPaths;
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
   * @throws LocalS3InvalidArgumentException if the request has no {@code x-amz-copy-source} header.
   * @throws LocalS3InvalidArgumentException if the header doesn't name a bucket and a key.
   */
  static CopySource of(RouterHttpRequest request) {
    return parse(request.header(AmzHeaderNames.X_AMZ_COPY_SOURCE).orElseThrow(() ->
        new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_COPY_SOURCE, null, "Copy Source must mention the source bucket and key: sourcebucket/sourcekey")));
  }

  /**
   * Parse the value of an {@code x-amz-copy-source} header.
   *
   * @param copySource the header value.
   * @return the parsed source object.
   * @throws LocalS3InvalidArgumentException if the value doesn't name a bucket and a key.
   */
  static CopySource parse(String copySource) {
    String[] slices = copySource.split("\\?", 2);
    String path = slices[0];

    int delimiterIndex;
    if (-1 == (delimiterIndex = path.indexOf('/', 1)) || delimiterIndex == path.length() - 1) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_COPY_SOURCE, copySource, "Invalid copy source.");
    }

    String bucket = path.charAt(0) == '/' ? path.substring(1, delimiterIndex)
        : path.substring(0, delimiterIndex);
    // A trailing '/' belongs to the key: "dir/" and "dir" are distinct objects.
    String key = path.substring(delimiterIndex + 1);

    try {
      return new CopySource(UrlPaths.decode(bucket), UrlPaths.decode(key), UrlPaths.decode(versionId(slices)));
    } catch (IllegalArgumentException _) {
      // A malformed percent escape.
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_COPY_SOURCE, copySource, "Invalid copy source encoding.");
    }
  }

  /**
   * Extract the version ID from the query of the header, if it has one.
   */
  private static String versionId(String[] pathSlices) {
    if (pathSlices.length <= 1) {
      return null;
    }

    String[] pairs = pathSlices[1].split("&");
    return Stream.of(pairs)
        .map(pair -> pair.split("=", 2))
        .filter(nameAndValue -> nameAndValue.length == 2 && "versionId".equals(nameAndValue[0])
            && !nameAndValue[1].isEmpty())
        .map(nameAndValue -> nameAndValue[1])
        .findFirst()
        .orElse(null);
  }

}
