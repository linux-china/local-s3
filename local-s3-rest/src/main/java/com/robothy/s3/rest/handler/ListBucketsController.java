package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.ListBucketsAns;
import com.robothy.s3.core.model.request.ListBucketsOptions;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.rest.model.response.ListAllMyBucketsResult;
import com.robothy.s3.rest.model.response.S3Bucket;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Instant;
import java.util.List;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * See {@code docs/apis.md#supported-amazon-s3-apis}.
 */
class ListBucketsController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  ListBucketsController(ServiceFactory factory) {
    this.bucketService = factory.getInstance(BucketService.class);
    this.xmlMapper = factory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String prefix = request.parameter("prefix").orElse(null);
    ListBucketsAns ans = bucketService.listBuckets(new ListBucketsOptions(
        prefix,
        request.parameter("bucket-region").orElse(null),
        request.parameter("continuation-token").orElse(null),
        request.parameter("max-buckets").map(ListBucketsController::parseMaxBuckets).orElse(null)));

    List<S3Bucket> buckets = ans.buckets().stream()
        .map(bucket -> new S3Bucket(bucket.getName(), Instant.ofEpochMilli(bucket.getCreationDate()),
            bucket.regionOrDefault()))
        .toList();
    ListAllMyBucketsResult result = new ListAllMyBucketsResult(buckets, Owner.DEFAULT_OWNER,
        ans.continuationToken(), prefix);
    response.status(HttpResponseStatus.OK)
        .write(xmlMapper.writeValueAsString(result));
    ResponseUtils.addCommonHeaders(response);
  }

  private static int parseMaxBuckets(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException _) {
      throw new LocalS3InvalidArgumentException("max-buckets", value,
          "Argument max-buckets must be an integer between 1 and " + BucketService.MAX_BUCKETS + ".");
    }
  }

}
