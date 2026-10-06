package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.ListBucketsAns;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.model.response.ListAllMyDirectoryBucketsResult;
import com.robothy.s3.rest.model.response.S3Bucket;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Instant;
import java.util.List;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * See {@code docs/apis.md#supported-amazon-s3-apis}, which also tells how the router tells it from ListBuckets.
 */
class ListDirectoryBucketsController implements RouterHttpRequestHandler {

  static final String OPERATION = "ListDirectoryBuckets";

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  ListDirectoryBucketsController(ServiceFactory factory) {
    this.bucketService = factory.getInstance(BucketService.class);
    this.xmlMapper = factory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    ListBucketsAns ans = bucketService.listDirectoryBuckets(
        request.parameter("continuation-token").orElse(null),
        request.parameter("max-directory-buckets").map(ListDirectoryBucketsController::parseMax).orElse(null));

    List<S3Bucket> buckets = ans.buckets().stream()
        .map(bucket -> new S3Bucket(bucket.getName(), Instant.ofEpochMilli(bucket.getCreationDate()),
            bucket.regionOrDefault()))
        .toList();
    response.status(HttpResponseStatus.OK)
        .write(xmlMapper.writeValueAsString(new ListAllMyDirectoryBucketsResult(buckets, ans.continuationToken())));
    ResponseUtils.addCommonHeaders(response);
  }

  private static int parseMax(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException _) {
      throw new LocalS3InvalidArgumentException("max-directory-buckets", value,
          "Argument max-directory-buckets must be an integer between 0 and "
              + BucketService.MAX_DIRECTORY_BUCKETS + ".");
    }
  }

}
