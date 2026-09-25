package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
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
 * Answers CORS preflight requests, i.e. the {@code OPTIONS} requests of browsers, by the CORS configuration of the
 * bucket, like <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/RESTOPTIONSobject.html">OPTIONS object</a>.
 * Browsers don't sign preflight requests, so they are not authenticated.
 *
 * <p>Where no bucket configuration applies, i.e. the bucket has none or the request addresses no bucket, the default
 * CORS rule of the service applies, if it has one; see {@linkplain com.robothy.s3.rest.LocalS3Cors}.
 */
class CorsPreflightController implements HttpRequestHandler {

  private final BucketService bucketService;

  private final CORSConfiguration defaultConfiguration;

  CorsPreflightController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.defaultConfiguration = CorsResponseHeaders.defaultConfiguration(serviceFactory);
  }

  /**
   * Whether the service has a default CORS rule, which also answers the preflight requests that address no bucket.
   */
  boolean hasDefaultConfiguration() {
    return defaultConfiguration != null;
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) {
    answer(request, response, CorsResponseHeaders.bucketName(request));
  }

  /**
   * Answer a preflight request that addresses no bucket, e.g. one of the Iceberg REST catalog, by the default CORS
   * rule of the service.
   */
  void handleWithoutBucket(HttpRequest request, HttpResponse response) {
    answer(request, response, null);
  }

  private void answer(HttpRequest request, HttpResponse response, String bucketName) {
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

  /**
   * The CORS configuration of a bucket, or the default rule of the service if the bucket has none.
   *
   * @return the configuration; {@code null} if neither applies.
   */
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
