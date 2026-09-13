package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.service.CompleteMultipartUploadService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.listener.ObjectEvent;
import com.robothy.s3.rest.listener.S3EventType;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.model.request.CompleteMultipartUpload;
import com.robothy.s3.rest.model.response.CompleteMultipartUploadResult;
import com.robothy.s3.rest.service.MultipartUploadPolicy;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CompleteMultipartUpload.html">CompleteMultipartUpload</a>
 */
class CompleteMultipartUploadController extends ObjectHttpRequestHandler {

  private final CompleteMultipartUploadService uploadService;

  private final MultipartUploadPolicy multipartUploadPolicy;

  CompleteMultipartUploadController(ServiceFactory serviceFactory) {
    super(serviceFactory);
    this.uploadService = serviceFactory.getInstance(ObjectService.class);
    // A router built from a bare service factory, e.g. in a test, applies the default policy.
    this.multipartUploadPolicy = serviceFactory.containsInstance(MultipartUploadPolicy.class)
        ? serviceFactory.getInstance(MultipartUploadPolicy.class)
        : MultipartUploadPolicy.of(false);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucket = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    String uploadId = RequestAssertions.assertUploadIdIsProvided(request);

    CompleteMultipartUploadAns completeMultipartUploadAns;
    try(InputStream in = new ByteBufInputStream(request.getBody())) {
      CompleteMultipartUpload completeMultipartUpload = xmlMapper.readValue(in, CompleteMultipartUpload.class);
      List<CompleteMultipartUploadPartOption> parts = completeMultipartUpload.getParts().stream().map(part -> CompleteMultipartUploadPartOption.builder()
                  .etag(part.getEtag())
                  .partNumber(part.getPartNumber())
                  .build())
              .collect(Collectors.toList());
      completeMultipartUploadAns = uploadService.completeMultipartUpload(bucket, key, uploadId, parts,
          multipartUploadPolicy.minimumPartSize(), multipartUploadPolicy.compositeEtags());
    }

    CompleteMultipartUploadResult result = CompleteMultipartUploadResult.builder()
        .bucket(bucket)
        .key(key)
        .etag(ResponseUtils.quoteEtag(completeMultipartUploadAns.getEtag()))
        .location(completeMultipartUploadAns.getLocation())
        .build();
    response.status(HttpResponseStatus.OK)
        .write(xmlMapper.writeValueAsString(result));
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_VERSION_ID,
        completeMultipartUploadAns.getVersionId());

    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
    ResponseUtils.addServerHeader(response);
    fireObjectEvent(new ObjectEvent(S3EventType.OBJECT_CREATED, "CompleteMultipartUpload", bucket, key,
        completeMultipartUploadAns.getVersionId(), completeMultipartUploadAns.getSize(),
        completeMultipartUploadAns.getEtag(), false));
  }

}
