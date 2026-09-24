package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.constants.ServiceConstants;
import com.robothy.s3.core.exception.BucketAlreadyOwnedByYouException;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.request.CreateBucketConfiguration;
import com.robothy.s3.datatypes.response.CreateBucketResult;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.constants.LocalS3Constants;
import com.robothy.s3.rest.service.BucketNameValidator;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.netty.RequestBodies;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html">CreateBucket</a>.
 */
@Slf4j
class CreateBucketController extends BucketHttpRequestHandler {

  private final BucketNameValidator bucketNameValidator;

  CreateBucketController(ServiceFactory serviceFactory) {
    super(serviceFactory);
    this.bucketNameValidator = serviceFactory.containsInstance(BucketNameValidator.class)
        ? serviceFactory.getInstance(BucketNameValidator.class)
        : new BucketNameValidator();
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    InputStream inputStream = RequestBodies.inputStream(request.getBody());

    String locationConstraint = LocalS3Constants.DEFAULT_LOCATION_CONSTRAINT;
    if (RequestBodies.length(request.getBody()) != 0) {
      CreateBucketConfiguration createBucketConfig = xmlMapper.readValue(inputStream, CreateBucketConfiguration.class);
      locationConstraint = createBucketConfig.getLocationConstraint();
    }

    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    bucketNameValidator.validate(bucketName);
    boolean objectLockEnabled = request.header(AmzHeaderNames.X_AMZ_BUCKET_OBJECT_LOCK_ENABLED)
        .map(value -> "true".equalsIgnoreCase(value.trim()))
        .orElse(false);
    try {
      bucketService.createBucket(bucketName, locationConstraint, objectLockEnabled);
    } catch (BucketAlreadyOwnedByYouException e) {
      // Amazon S3 answers the re-creation of a bucket that the requester owns in us-east-1 with 200 OK, for legacy
      // compatibility, and leaves the bucket and its objects as they are; in every other region it answers 409.
      if (!ServiceConstants.DEFAULT_REGION.equals(ServiceConstants.effectiveRegion(locationConstraint))
          || !ServiceConstants.DEFAULT_REGION.equals(bucketService.getBucket(bucketName).regionOrDefault())) {
        throw e;
      }
    }
    String bucketArn = "arn:aws:s3:::" + bucketName;
    CreateBucketResult createBucketResult = CreateBucketResult.builder()
        .bucketArn(bucketArn)
        .build();
    response.putHeader("Location", "/" + bucketName)
        .putHeader(AmzHeaderNames.X_AMZ_BUCKET_ARN, bucketArn)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .write(xmlMapper.writeValueAsString(createBucketResult));
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

}
