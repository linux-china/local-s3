package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.core.util.S3VectorsArnUtils;
import com.robothy.s3.datatypes.s3vectors.request.CreateIndexRequest;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP controller for creating vector indexes.
 * Handles POST requests to create new vector indexes in a vector bucket.
 */
@Slf4j
public class CreateIndexController implements RouterHttpRequestHandler {

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public CreateIndexController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    CreateIndexRequest createRequest = parseRequest(request);
    processRequest(createRequest);
    HttpRequestUtils.sendEmptyJsonResponse(response);
  }

  private CreateIndexRequest parseRequest(RouterHttpRequest request) throws Exception {
    byte[] bodyBytes = HttpRequestUtils.extractRequestBody(request);
    return HttpRequestUtils.parseRequiredRequest(bodyBytes, CreateIndexRequest.class, objectMapper);
  }

  private void processRequest(CreateIndexRequest createRequest) {
    String vectorBucketName = S3VectorsArnUtils.resolveBucketName(
        createRequest.getVectorBucketName(), 
        createRequest.getVectorBucketArn()
    );
    List<String> nonFilterableMetadataKeys = extractNonFilterableMetadataKeys(createRequest);
    
    s3VectorsService.createIndex(
        vectorBucketName,
        createRequest.getIndexName(),
        createRequest.getDataType(),
        createRequest.getDimension(),
        createRequest.getDistanceMetric(),
        nonFilterableMetadataKeys,
        createRequest.getTags()
    );
  }

  private List<String> extractNonFilterableMetadataKeys(CreateIndexRequest createRequest) {
    if (createRequest.getMetadataConfiguration() != null && 
        createRequest.getMetadataConfiguration().getNonFilterableMetadataKeys() != null) {
      return createRequest.getMetadataConfiguration().getNonFilterableMetadataKeys();
    }
    return null;
  }

}
