package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.model.answers.UploadPartCopyAns;
import com.robothy.s3.core.model.request.Range;
import com.robothy.s3.core.model.request.UploadPartCopyOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.UploadPartCopyService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.model.response.CopyPartResult;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.utils.XmlUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Instant;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPartCopy.html">UploadPartCopy</a>,
 * the PUT of a part that carries an {@code x-amz-copy-source} header instead of the data of the part. The AWS
 * SDKs use it to copy an object with a multipart upload, e.g. when it is larger than the threshold of the
 * transfer manager.
 */
class UploadPartCopyController implements HttpRequestHandler {

  private final UploadPartCopyService uploadPartCopyService;

  UploadPartCopyController(ServiceFactory serviceFactory) {
    this.uploadPartCopyService = serviceFactory.getInstance(ObjectService.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucket = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    int partNumber = RequestAssertions.assertPartNumberIsValid(request);
    String uploadId = RequestAssertions.assertUploadIdIsProvided(request);
    CopySource copySource = CopySource.of(request);
    Range copySourceRange = request.header(AmzHeaderNames.X_AMZ_COPY_SOURCE_RANGE).map(Range::parse).orElse(null);

    UploadPartCopyAns ans = uploadPartCopyService.uploadPartCopy(bucket, key, uploadId, partNumber,
        UploadPartCopyOptions.builder()
            .sourceBucket(copySource.bucket())
            .sourceKey(copySource.key())
            .sourceVersion(copySource.versionId())
            .copySourceRange(copySourceRange)
            .build());

    CopyPartResult result = CopyPartResult.builder()
        .lastModified(Instant.ofEpochMilli(ans.getLastModified()))
        .etag(ans.getEtag())
        .build();

    // The Content-Length is set from the bytes of the body by LocalS3HttpMessageHandler.
    response.status(HttpResponseStatus.OK)
        .write(XmlUtils.toXml(result))
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .putHeader(AmzHeaderNames.X_AMZ_COPY_SOURCE_VERSION_ID, ans.getSourceVersionId());
    ResponseUtils.addCommonHeaders(response);
  }

}
