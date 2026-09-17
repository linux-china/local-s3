package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.BucketObjectLockConfiguration;
import com.robothy.s3.core.model.DefaultRetention;
import com.robothy.s3.core.model.ObjectLockMode;
import com.robothy.s3.core.model.internal.ObjectLock;
import com.robothy.s3.core.service.BucketObjectLockService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectLockService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.datatypes.ObjectLockConfiguration;
import com.robothy.s3.datatypes.ObjectLockLegalHold;
import com.robothy.s3.datatypes.ObjectLockRetention;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.model.request.DecodedAmzRequestBody;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ObjectLockHeaders;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handles <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html">Object Lock</a>: the object
 * lock configuration of a bucket, and the retention and legal hold of an object version; see
 * {@linkplain BucketObjectLockService} and {@linkplain ObjectLockService}.
 */
class ObjectLockController {

  /**
   * The max number of days of a default retention.
   */
  private static final int MAX_DAYS = 36500;

  /**
   * The max number of years of a default retention.
   */
  private static final int MAX_YEARS = 100;

  private final BucketObjectLockService bucketService;

  private final ObjectLockService objectService;

  private final XmlMapper xmlMapper;

  ObjectLockController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.objectService = serviceFactory.getInstance(ObjectService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObjectLockConfiguration.html">PutObjectLockConfiguration</a>
   */
  void putConfiguration(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    ObjectLockConfiguration configuration = readBody(request, ObjectLockConfiguration.class);
    if (!ObjectLockConfiguration.ENABLED.equals(configuration.getObjectLockEnabled())) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
    DefaultRetention defaultRetention = null;
    if (configuration.getRule() != null) {
      ObjectLockConfiguration.DefaultRetention retention = configuration.getRule().getDefaultRetention();
      if (retention == null || retention.getMode() == null) {
        throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
      }
      ObjectLockMode mode = ObjectLockHeaders.parseMode("Mode", retention.getMode());
      Integer days = retention.getDays();
      Integer years = retention.getYears();
      if ((days == null) == (years == null)) {
        throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
      }
      if (days != null && (days <= 0 || days > MAX_DAYS)) {
        throw new LocalS3InvalidArgumentException("Days", String.valueOf(days),
            "Default retention period must be a positive integer value not greater than " + MAX_DAYS + " days.");
      }
      if (years != null && (years <= 0 || years > MAX_YEARS)) {
        throw new LocalS3InvalidArgumentException("Years", String.valueOf(years),
            "Default retention period must be a positive integer value not greater than " + MAX_YEARS + " years.");
      }
      defaultRetention = new DefaultRetention(mode, days, years);
    }
    bucketService.putObjectLockConfiguration(bucketName, new BucketObjectLockConfiguration(defaultRetention));
    ResponseUtils.addCommonHeaders(response).status(HttpResponseStatus.OK);
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectLockConfiguration.html">GetObjectLockConfiguration</a>
   */
  void getConfiguration(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    BucketObjectLockConfiguration configuration = bucketService.getObjectLockConfiguration(bucketName)
        .orElseThrow(() -> new LocalS3RequestException(S3ErrorCode.ObjectLockConfigurationNotFoundError));
    ObjectLockConfiguration.ObjectLockConfigurationBuilder result = ObjectLockConfiguration.builder()
        .objectLockEnabled(ObjectLockConfiguration.ENABLED);
    DefaultRetention defaultRetention = configuration.defaultRetention();
    if (defaultRetention != null) {
      result.rule(new ObjectLockConfiguration.Rule(new ObjectLockConfiguration.DefaultRetention(
          defaultRetention.mode().name(), defaultRetention.days(), defaultRetention.years())));
    }
    writeXml(response, result.build());
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObjectRetention.html">PutObjectRetention</a>
   */
  void putRetention(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    ObjectLockRetention retention = readBody(request, ObjectLockRetention.class);
    ObjectLockMode mode = retention.getMode() == null ? null
        : ObjectLockHeaders.parseMode("Mode", retention.getMode());
    Long retainUntilDate = retention.getRetainUntilDate() == null ? null
        : ObjectLockHeaders.parseDate("RetainUntilDate", retention.getRetainUntilDate());
    objectService.putObjectRetention(bucketName, key, request.parameter("versionId").orElse(null), mode,
        retainUntilDate, RequestUtils.isBypassGovernanceRetention(request));
    ResponseUtils.addCommonHeaders(response).status(HttpResponseStatus.OK);
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectRetention.html">GetObjectRetention</a>
   */
  void getRetention(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    ObjectLock lock = objectService.getObjectRetention(bucketName, key, request.parameter("versionId").orElse(null));
    writeXml(response, new ObjectLockRetention(lock.mode().name(), ObjectLockHeaders.formatDate(lock.retainUntilDate())));
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObjectLegalHold.html">PutObjectLegalHold</a>
   */
  void putLegalHold(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    ObjectLockLegalHold legalHold = readBody(request, ObjectLockLegalHold.class);
    if (legalHold.getStatus() == null) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
    boolean on = ObjectLockHeaders.parseLegalHold("Status", legalHold.getStatus());
    objectService.putObjectLegalHold(bucketName, key, request.parameter("versionId").orElse(null), on);
    ResponseUtils.addCommonHeaders(response).status(HttpResponseStatus.OK);
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectLegalHold.html">GetObjectLegalHold</a>
   */
  void getLegalHold(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    boolean on = objectService.getObjectLegalHold(bucketName, key, request.parameter("versionId").orElse(null));
    writeXml(response, new ObjectLockLegalHold(ObjectLockHeaders.formatLegalHold(on)));
  }

  private <T> T readBody(HttpRequest request, Class<T> type) throws Exception {
    DecodedAmzRequestBody body = RequestUtils.getBody(request);
    try (InputStream in = body.getDecodedBody()) {
      byte[] bytes = in.readAllBytes();
      if (bytes.length == 0) {
        throw new LocalS3RequestException(S3ErrorCode.MissingRequestBodyError, "Request Body is empty");
      }
      return xmlMapper.readValue(bytes, type);
    } catch (JacksonException e) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
  }

  private void writeXml(HttpResponse response, Object body) {
    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .write(xmlMapper.writeValueAsString(body));
  }

}
