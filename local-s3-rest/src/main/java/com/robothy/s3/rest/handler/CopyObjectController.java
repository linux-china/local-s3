package com.robothy.s3.rest.handler;

import com.robothy.s3.rest.utils.ChecksumHeaders;
import com.robothy.s3.rest.model.response.ChecksumElements;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.CopyObjectAns;
import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.service.CopyObjectService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.model.response.CopyObjectResult;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.CustomerEncryptionHeaders;
import com.robothy.s3.rest.utils.ObjectLockHeaders;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.utils.ServerSideEncryptionHeaders;
import com.robothy.s3.rest.utils.SystemMetadataHeaders;
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
        .etag(ResponseUtils.quoteEtag(copyObjectAns.getEtag()))
        .checksum(ChecksumElements.of(copyObjectAns.getChecksum()))
        .build();

    response.status(HttpResponseStatus.OK)
        .write(xmlMapper.writeValueAsString(result))
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML);
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_VERSION_ID, copyObjectAns.getVersionId());
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_COPY_SOURCE_VERSION_ID,
        copyObjectAns.getSourceVersionId());
    CustomerEncryptionHeaders.addHeaders(response, copyObjectOptions.getCustomerEncryption());
    ServerSideEncryptionHeaders.addHeaders(response, copyObjectAns.getServerSideEncryption(), true);
    ResponseUtils.addAmzRequestId(response);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
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
    boolean replaceMetadata = metadataDirective == CopyObjectOptions.MetadataDirective.REPLACE;

    // Parse tagging directive and the tagging that replaces the one of the source object.
    CopyObjectOptions.TaggingDirective taggingDirective = parseTaggingDirective(request);
    String[][] tagging = taggingDirective == CopyObjectOptions.TaggingDirective.REPLACE
        ? RequestUtils.extractTagging(request).orElse(null)
        : null;

    CustomerEncryption customerEncryption = CustomerEncryptionHeaders.fromRequest(request);
    return CopyObjectOptions.builder()
        .sourceBucket(copySource.bucket())
        .sourceKey(copySource.key())
        .sourceVersion(copySource.versionId())
        .metadataDirective(metadataDirective)
        .userMetadata(userMetadata)
        .contentType(replaceMetadata ? request.header(HttpHeaderNames.CONTENT_TYPE).orElse(null) : null)
        .systemMetadata(replaceMetadata ? SystemMetadataHeaders.fromRequest(request) : null)
        .taggingDirective(taggingDirective)
        .tagging(tagging)
        // If-Match and If-None-Match of the destination, like a PutObject; x-amz-copy-source-if-* of the source.
        .preconditions(RequestUtils.extractPreconditions(request))
        .sourcePreconditions(RequestUtils.extractCopySourcePreconditions(request))
        .checksumAlgorithm(ChecksumHeaders.algorithm(request, AmzHeaderNames.X_AMZ_CHECKSUM_ALGORITHM))
        .objectLock(ObjectLockHeaders.fromRequest(request))
        .customerEncryption(customerEncryption)
        .serverSideEncryption(ServerSideEncryptionHeaders.fromRequest(request, customerEncryption))
        .sourceCustomerEncryption(CustomerEncryptionHeaders.fromCopySourceRequest(request))
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
