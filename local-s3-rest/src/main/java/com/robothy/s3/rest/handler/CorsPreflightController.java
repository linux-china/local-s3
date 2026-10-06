package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.InvalidCORSRequestException;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * See {@code docs/semantics.md#cors}. Browsers don't sign preflight requests, so they are not authenticated.
 */
class CorsPreflightController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  private final CORSConfiguration defaultConfiguration;

  CorsPreflightController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.defaultConfiguration = CorsResponseHeaders.defaultConfiguration(serviceFactory);
  }

  boolean hasDefaultConfiguration() {
    return defaultConfiguration != null;
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) {
    answer(request, response, CorsResponseHeaders.bucketName(request));
  }

  void handleWithoutBucket(RouterHttpRequest request, RouterHttpResponse response) {
    answer(request, response, null);
  }

  private void answer(RouterHttpRequest request, RouterHttpResponse response, String bucketName) {
    Optional<String> origin = request.header(HttpHeaderNames.ORIGIN.toString());
    if (origin.isEmpty()) {
      throw new InvalidCORSRequestException(S3ErrorCode.BadRequest,
          "Insufficient information. Origin request header needed.");
    }
    Optional<String> method = request.header(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD.toString());
    if (method.isEmpty()) {
      throw new InvalidCORSRequestException(S3ErrorCode.BadRequest,
          "Insufficient information. Access-Control-Request-Method request header needed.");
    }
    if (bucketName == null && defaultConfiguration == null) {
      throw new InvalidCORSRequestException(S3ErrorCode.BadRequest, "A CORS preflight request must address a bucket.");
    }

    CORSConfiguration configuration = bucketName == null ? defaultConfiguration : bucketConfiguration(bucketName);
    if (configuration == null) {
      throw new InvalidCORSRequestException(S3ErrorCode.AccessForbidden,
          "CORSResponse: CORS is not enabled for this bucket.");
    }
    List<String> requestHeaders = request.header(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS.toString())
        .map(CorsPreflightController::splitHeaderNames)
        .orElse(List.of());
    CORSRule rule = CorsRules.match(configuration, origin.get(), method.get(), requestHeaders)
        .orElseThrow(() -> new InvalidCORSRequestException(S3ErrorCode.AccessForbidden,
            "CORSResponse: This CORS request is not allowed. This is usually because the evaluation of Origin, "
                + "request method / Access-Control-Request-Method or Access-Control-Request-Headers are not "
                + "whitelisted by the resource's CORS spec."));

    CorsResponseHeaders.addHeaders(response, rule, origin.get(), requestHeaders);
    response.status(HttpResponseStatus.OK);
    ResponseUtils.addCommonHeaders(response);
  }

  private CORSConfiguration bucketConfiguration(String bucketName) {
    try {
      return bucketService.getBucketCors(bucketName).orElse(defaultConfiguration);
    } catch (LocalS3Exception e) {
      // E.g. the bucket doesn't exist yet, which a page may be about to create.
      if (defaultConfiguration == null) {
        throw e;
      }
      return defaultConfiguration;
    }
  }

  private static List<String> splitHeaderNames(String headerNames) {
    return Arrays.stream(headerNames.split(","))
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .map(name -> name.toLowerCase(Locale.ROOT))
        .toList();
  }

}
