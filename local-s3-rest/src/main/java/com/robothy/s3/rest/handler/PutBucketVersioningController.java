package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.VersioningConfiguration;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.netty.RequestBodies;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import java.util.Objects;
import tools.jackson.dataformat.xml.XmlMapper;

class PutBucketVersioningController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  PutBucketVersioningController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    InputStream requestBody = RequestBodies.inputStream(request.getBody());
    VersioningConfiguration versioningConfiguration = xmlMapper.readValue(requestBody, VersioningConfiguration.class);
    // A configuration without a Status, e.g. one that sets MfaDelete only, leaves the versioning of the bucket as it
    // is: a bucket that was never versioned stays so, rather than being suspended.
    Boolean versioningEnabled = parse(versioningConfiguration.getStatus(), VersioningConfiguration.Suspended);
    Boolean mfaDeleteEnabled = parse(versioningConfiguration.getMfaDelete(), VersioningConfiguration.Disabled);
    bucketService.putVersioningConfiguration(bucketName, versioningEnabled, mfaDeleteEnabled);
    response.status(HttpResponseStatus.OK);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

  // Case-sensitive, like Amazon S3 reads them; any other value is MalformedXML.
  private static Boolean parse(String value, String disabledValue) {
    if (Objects.isNull(value)) {
      return null;
    }
    if (VersioningConfiguration.Enabled.equals(value)) {
      return true;
    }
    if (disabledValue.equals(value)) {
      return false;
    }
    throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
  }

}
