package com.robothy.s3.rest.handler;

import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.rest.utils.ChecksumHeaders;
import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.model.answers.UploadPartAns;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.UploadPartService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.model.request.DecodedAmzRequestBody;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.CustomerEncryptionHeaders;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.utils.ServerSideEncryptionHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPart.html">UploadPart</a>
 */
class UploadPartController implements RouterHttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(UploadPartController.class);

  private final UploadPartService uploadPartService;

  private final boolean acceptChunkedUploads;

  UploadPartController(ServiceFactory serviceFactory) {
    this.uploadPartService = serviceFactory.getInstance(ObjectService.class);
    this.acceptChunkedUploads = PutObjectController.acceptChunkedUploads(serviceFactory);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucket = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    int partNumber = RequestAssertions.assertPartNumberIsValid(request);
    String uploadId = RequestAssertions.assertUploadIdIsProvided(request);
    DecodedAmzRequestBody decodedBody = RequestUtils.getBody(request);
    RequestUtils.assertContentLengthAccepted(decodedBody, acceptChunkedUploads);
    CustomerEncryption customerEncryption = CustomerEncryptionHeaders.fromRequest(request);
    UploadPartAns uploadPartAns = uploadPartService.uploadPart(bucket, key, uploadId, partNumber, UploadPartOptions.builder()
        .contentLength(decodedBody.getDecodedContentLength())
        .data(decodedBody.getDecodedBody())
        .dataFile(decodedBody.getBodyFile())
        .heapData(decodedBody.getHeapContent())
        .etag(RequestUtils.getETag(request).orElse(null))
        .checksum(ChecksumHeaders.fromRequest(request, decodedBody))
        .customerEncryption(customerEncryption)
        .build());

    ResponseUtils.addCommonHeaders(response);
    ResponseUtils.addETag(response, uploadPartAns.getEtag());
    ChecksumHeaders.addHeaders(response, uploadPartAns.getChecksum());
    CustomerEncryptionHeaders.addHeaders(response, customerEncryption);
    ServerSideEncryptionHeaders.addHeaders(response, uploadPartAns.getServerSideEncryption(), false);
  }

}
