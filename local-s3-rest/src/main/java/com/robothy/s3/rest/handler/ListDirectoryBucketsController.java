package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
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
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListDirectoryBuckets.html">ListDirectoryBuckets</a> of
 * S3 Express One Zone, with the {@code continuation-token} and {@code max-directory-buckets} parameters, see
 * {@linkplain BucketService#listDirectoryBuckets(String, Integer)}.
 *
 * <p>It is a {@code GET /} like {@code ListBuckets}: the router tells the two apart by the {@code max-directory-buckets}
 * parameter, or, of a request without it, by the {@code s3express} service that the AWS SDKs sign it for.
 */
class ListDirectoryBucketsController implements HttpRequestHandler {

  static final String OPERATION = "ListDirectoryBuckets";

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  ListDirectoryBucketsController(ServiceFactory factory) {
    this.bucketService = factory.getInstance(BucketService.class);
    this.xmlMapper = factory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
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
    } catch (NumberFormatException e) {
      throw new LocalS3InvalidArgumentException("max-directory-buckets", value,
          "Argument max-directory-buckets must be an integer between 0 and "
              + BucketService.MAX_DIRECTORY_BUCKETS + ".");
    }
  }

}
