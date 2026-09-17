package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.request.RenameObjectOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.RenameObjectService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_RenameObject.html">RenameObject</a>, see
 * {@linkplain RenameObjectService}.
 */
class RenameObjectController implements HttpRequestHandler {

  private final RenameObjectService renameObjectService;

  RenameObjectController(ServiceFactory serviceFactory) {
    this.renameObjectService = serviceFactory.getInstance(ObjectService.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    String renameSource = request.header(AmzHeaderNames.X_AMZ_RENAME_SOURCE).orElseThrow(() ->
        new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_RENAME_SOURCE, null,
            "The x-amz-rename-source header is required."));
    renameObjectService.renameObject(bucketName, key, RenameObjectOptions.builder()
        .sourceKey(sourceKey(bucketName, renameSource))
        .preconditions(RequestUtils.extractPreconditions(request))
        .sourcePreconditions(RequestUtils.extractRenameSourcePreconditions(request))
        .build());
    ResponseUtils.addCommonHeaders(response).status(HttpResponseStatus.OK);
  }

  /**
   * The key of the object to rename, from an {@code x-amz-rename-source} that names it as {@code /bucket/key}, like the
   * AWS SDKs send it, or as the key alone. The value is URL encoded.
   *
   * @throws LocalS3InvalidArgumentException if the value names no key, or isn't URL encoded properly.
   */
  static String sourceKey(String bucketName, String renameSource) {
    String path;
    try {
      path = URLDecoder.decode(renameSource.trim(), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_RENAME_SOURCE, renameSource,
          "Invalid rename source encoding.");
    }
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    String bucketPrefix = bucketName + "/";
    String key = path.startsWith(bucketPrefix) ? path.substring(bucketPrefix.length()) : path;
    if (key.isEmpty()) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_RENAME_SOURCE, renameSource,
          "Invalid rename source.");
    }
    return key;
  }

}
