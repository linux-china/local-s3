package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.CopyObjectAns;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.service.CopyObjectService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.listener.ObjectEvent;
import com.robothy.s3.rest.listener.S3EventType;
import com.robothy.s3.rest.model.response.CopyObjectResult;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html">CopyObject</a>
 */
class CopyObjectController extends ObjectHttpRequestHandler {

  private final CopyObjectService objectService;

  CopyObjectController(ServiceFactory serviceFactory) {
    super(serviceFactory);
    this.objectService = serviceFactory.getInstance(ObjectService.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String destinationBucket = RequestAssertions.assertBucketNameProvided(request);
    String destinationKey = RequestAssertions.assertObjectKeyProvided(request);
    CopyObjectOptions copyObjectOptions = parseCopyOptions(request);
    CopyObjectAns copyObjectAns = objectService.copyObject(destinationBucket, destinationKey, copyObjectOptions);
    CopyObjectResult result = CopyObjectResult.builder()
        .lastModified(Instant.ofEpochMilli(copyObjectAns.getLastModified()))
        .etag(copyObjectAns.getEtag())
        .build();

    response.status(HttpResponseStatus.OK)
        .write(xmlMapper.writeValueAsString(result))
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML);
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_VERSION_ID, copyObjectAns.getVersionId());
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_COPY_SOURCE_VERSION_ID,
        copyObjectAns.getSourceVersionId());
    ResponseUtils.addAmzRequestId(response);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
    fireObjectEvent(new ObjectEvent(S3EventType.OBJECT_CREATED, "CopyObject", destinationBucket, destinationKey,
        copyObjectAns.getVersionId(), copyObjectAns.getSize(), copyObjectAns.getEtag(), false));
  }

  /**
   * Parse the request and build CopyObjectOptions.
   *
   * @param request The HTTP request
   * @return CopyObjectOptions instance
   */
  CopyObjectOptions parseCopyOptions(HttpRequest request) {
    // Parse copy source information (bucket, key, version)
    CopySource copySource = CopySource.of(request);

    // Parse metadata directive and user metadata
    CopyObjectOptions.MetadataDirective metadataDirective = parseMetadataDirective(request);
    Map<String, String> userMetadata = extractUserMetadata(request, metadataDirective);

    // Parse tagging directive and the tagging that replaces the one of the source object.
    CopyObjectOptions.TaggingDirective taggingDirective = parseTaggingDirective(request);
    String[][] tagging = taggingDirective == CopyObjectOptions.TaggingDirective.REPLACE
        ? RequestUtils.extractTagging(request).orElse(null)
        : null;

    return CopyObjectOptions.builder()
        .sourceBucket(copySource.bucket())
        .sourceKey(copySource.key())
        .sourceVersion(copySource.versionId())
        .metadataDirective(metadataDirective)
        .userMetadata(userMetadata)
        .taggingDirective(taggingDirective)
        .tagging(tagging)
        .build();
  }

  /**
   * Parse the x-amz-tagging-directive header.
   *
   * @param request The HTTP request
   * @return TaggingDirective enum value (defaults to COPY)
   */
  private CopyObjectOptions.TaggingDirective parseTaggingDirective(HttpRequest request) {
    String taggingDirectiveHeader = request.header(AmzHeaderNames.X_AMZ_TAGGING_DIRECTIVE).orElse(null);
    if (taggingDirectiveHeader == null) {
      return CopyObjectOptions.TaggingDirective.COPY;
    }

    try {
      return CopyObjectOptions.TaggingDirective.valueOf(taggingDirectiveHeader);
    } catch (IllegalArgumentException e) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_TAGGING_DIRECTIVE,
          taggingDirectiveHeader, "Invalid tagging directive.");
    }
  }

  /**
   * Parse the x-amz-metadata-directive header.
   *
   * @param request The HTTP request
   * @return MetadataDirective enum value (defaults to COPY)
   */
  private CopyObjectOptions.MetadataDirective parseMetadataDirective(HttpRequest request) {
    String metadataDirectiveHeader = request.header(AmzHeaderNames.X_AMZ_METADATA_DIRECTIVE).orElse(null);
    if (metadataDirectiveHeader == null) {
      return CopyObjectOptions.MetadataDirective.COPY;
    }
    
    try {
      return CopyObjectOptions.MetadataDirective.valueOf(metadataDirectiveHeader);
    } catch (IllegalArgumentException e) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_METADATA_DIRECTIVE, 
          metadataDirectiveHeader, "Invalid metadata directive.");
    }
  }
  
  /**
   * Extract user metadata from request headers if directive is REPLACE.
   *
   * @param request The HTTP request
   * @param metadataDirective The metadata directive
   * @return Map of user metadata key-value pairs
   */
  private Map<String, String> extractUserMetadata(HttpRequest request, 
                                                  CopyObjectOptions.MetadataDirective metadataDirective) {
    if (metadataDirective != CopyObjectOptions.MetadataDirective.REPLACE) {
      return Collections.emptyMap();
    }
    
    Map<String, String> userMetadata = new HashMap<>();
    for (Map.Entry<CharSequence, String> entry : request.getHeaders().entrySet()) {
      String name = entry.getKey().toString();
      String value = entry.getValue();
      
      if (name != null && name.startsWith(AmzHeaderNames.X_AMZ_META_PREFIX)) {
        String key = name.substring(AmzHeaderNames.X_AMZ_META_PREFIX.length());
        userMetadata.put(key, value);
      }
    }
    
    return userMetadata;
  }
}
