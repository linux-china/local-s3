package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.VersioningConfiguration;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Objects;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketVersioning.html">GetBucketVersioning</a>
 */
class GetBucketVersioningController implements HttpRequestHandler {

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  GetBucketVersioningController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    Bucket bucket = bucketService.getBucket(bucketName);
    Boolean versioningEnabled = bucket.getVersioningEnabled();
    Boolean mfaDeleteEnabled = bucket.getMfaDeleteEnabled();
    VersioningConfiguration.VersioningConfigurationBuilder builder = VersioningConfiguration.builder();
    if (Objects.nonNull(versioningEnabled)) {
      builder.status(versioningEnabled ? VersioningConfiguration.Enabled : VersioningConfiguration.Suspended);
    }
    // Answered back as it was put, so that a tool that compares the configuration it applies, e.g. Terraform, sees no
    // change; LocalS3 asks for no MFA device either way.
    if (Objects.nonNull(mfaDeleteEnabled)) {
      builder.mfaDelete(mfaDeleteEnabled ? VersioningConfiguration.Enabled : VersioningConfiguration.Disabled);
    }

    String responseBody = xmlMapper.writeValueAsString(builder.build());
    response.status(HttpResponseStatus.OK)
        .write(responseBody);
    ResponseUtils.addAmzRequestId(response);
    ResponseUtils.addDateHeader(response);
  }

}
