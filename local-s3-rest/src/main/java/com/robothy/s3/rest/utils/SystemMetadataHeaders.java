package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.datatypes.enums.StorageClass;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads the <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html#SysMetadata">system-defined
 * metadata</a> of an object from the headers of the request that stores it, and writes it to the headers of the
 * responses that serve the object.
 *
 * <p>A {@code GetObject} or {@code HeadObject} request overrides a header of its response with the
 * {@code response-*} query parameter of the header, e.g. {@code response-content-disposition}, which lets a
 * presigned URL download an object under a name of its choosing.
 */
public final class SystemMetadataHeaders {

  /**
   * A header of the system-defined metadata, the query parameter that overrides it in a response, and where
   * {@linkplain SystemMetadata} holds it.
   */
  private enum Header {
    CACHE_CONTROL(HttpHeaderNames.CACHE_CONTROL.toString(), SystemMetadata::getCacheControl),
    CONTENT_DISPOSITION(HttpHeaderNames.CONTENT_DISPOSITION.toString(), SystemMetadata::getContentDisposition),
    CONTENT_ENCODING(HttpHeaderNames.CONTENT_ENCODING.toString(), SystemMetadata::getContentEncoding),
    CONTENT_LANGUAGE(HttpHeaderNames.CONTENT_LANGUAGE.toString(), SystemMetadata::getContentLanguage),
    EXPIRES(HttpHeaderNames.EXPIRES.toString(), SystemMetadata::getExpires);

    private final String headerName;

    private final Function<SystemMetadata, String> getter;

    Header(String headerName, Function<SystemMetadata, String> getter) {
      this.headerName = headerName;
      this.getter = getter;
    }

    String overrideParameter() {
      return RESPONSE_PARAMETER_PREFIX + headerName;
    }
  }

  private static final String RESPONSE_PARAMETER_PREFIX = "response-";

  private static final String AWS_CHUNKED = "aws-chunked";

  private SystemMetadataHeaders() {
  }

  /**
   * Read the system-defined metadata that a request stores an object with.
   *
   * @param request a {@code PutObject}, {@code CreateMultipartUpload} or {@code CopyObject} request.
   * @return the system-defined metadata of the request; {@code null} if it carries none.
   */
  public static SystemMetadata fromRequest(HttpRequest request) {
    return fromValues(name -> request.header(name).orElse(null));
  }

  /**
   * Read the system-defined metadata of an object from values named like the headers, e.g. the fields of the form
   * of a {@code PostObject} request.
   *
   * @param values the value of a header name, e.g. {@code cache-control}; {@code null} if there is none.
   * @return the system-defined metadata; {@code null} if there is none.
   */
  public static SystemMetadata fromValues(Function<String, String> values) {
    SystemMetadata systemMetadata = SystemMetadata.builder()
        .cacheControl(values.apply(Header.CACHE_CONTROL.headerName))
        .contentDisposition(values.apply(Header.CONTENT_DISPOSITION.headerName))
        .contentEncoding(storedContentEncoding(values.apply(Header.CONTENT_ENCODING.headerName)))
        .contentLanguage(values.apply(Header.CONTENT_LANGUAGE.headerName))
        .expires(values.apply(Header.EXPIRES.headerName))
        .storageClass(storedStorageClass(storageClass(values)))
        .build();
    return systemMetadata.equals(new SystemMetadata()) ? null : systemMetadata;
  }

  /**
   * Read the {@code x-amz-storage-class} that an object is stored with.
   *
   * @param values the value of a header name; {@code null} if there is none.
   * @return the storage class; {@code null} if there is none.
   * @throws LocalS3RequestException {@code InvalidStorageClass} if it names no storage class.
   */
  public static StorageClass storageClass(Function<String, String> values) {
    String value = values.apply(AmzHeaderNames.X_AMZ_STORAGE_CLASS);
    if (value == null) {
      return null;
    }
    try {
      return StorageClass.valueOf(value.trim());
    } catch (IllegalArgumentException e) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidStorageClass);
    }
  }

  /**
   * {@code STANDARD} is stored as no storage class, so that an object stored with it has no system-defined metadata.
   */
  private static StorageClass storedStorageClass(StorageClass storageClass) {
    return storageClass == StorageClass.STANDARD ? null : storageClass;
  }

  /**
   * The content encoding that an object is stored with: the one of the request without {@code aws-chunked}, which
   * only encodes the transfer of a request signed chunk by chunk and isn't stored, like Amazon S3 does.
   */
  static String storedContentEncoding(String contentEncoding) {
    if (contentEncoding == null) {
      return null;
    }
    String stored = Arrays.stream(contentEncoding.split(","))
        .map(String::trim)
        .filter(coding -> !coding.isEmpty() && !AWS_CHUNKED.equalsIgnoreCase(coding))
        .collect(Collectors.joining(","));
    return stored.isEmpty() ? null : stored;
  }

  /**
   * Add the {@code Content-Type} and the system-defined metadata of an object to a response that serves it, each
   * overridden by the {@code response-*} query parameter of the request if it carries one.
   *
   * @param request        the {@code GetObject} or {@code HeadObject} request.
   * @param response       the response to add the headers to.
   * @param contentType    the content type of the object; {@code null} if it has none.
   * @param systemMetadata the system-defined metadata of the object; {@code null} if it has none.
   */
  public static void addResponseHeaders(HttpRequest request, HttpResponse response, String contentType,
                                        SystemMetadata systemMetadata) {
    String contentTypeHeader = HttpHeaderNames.CONTENT_TYPE.toString();
    ResponseUtils.putHeaderIfPresent(response, contentTypeHeader,
        request.parameter(RESPONSE_PARAMETER_PREFIX + contentTypeHeader).orElse(contentType));
    for (Header header : Header.values()) {
      String value = systemMetadata == null ? null : header.getter.apply(systemMetadata);
      ResponseUtils.putHeaderIfPresent(response, header.headerName,
          request.parameter(header.overrideParameter()).orElse(value));
    }
    // Answered only for a storage class other than STANDARD, like Amazon S3 does.
    if (systemMetadata != null) {
      ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_STORAGE_CLASS, systemMetadata.getStorageClass());
    }
  }

}
