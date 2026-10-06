package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.PublicAccessBlockConfiguration;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.netty.RequestBodies;
import java.io.InputStream;
import tools.jackson.dataformat.xml.XmlMapper;

class PutPublicAccessBlockController implements RouterHttpRequestHandler {

  private final BucketService bucketService;
  
  private final XmlMapper xmlMapper;

  PutPublicAccessBlockController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);

    try(InputStream in = RequestBodies.inputStream(request.getBody())) {
      PublicAccessBlockConfiguration configuration = xmlMapper.readValue(in, PublicAccessBlockConfiguration.class);
      bucketService.putPublicAccessBlock(bucketName, configuration);
    }

    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }
}
