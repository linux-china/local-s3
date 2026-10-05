package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.assertions.vectors.VectorIndexAssertions;
import com.robothy.s3.core.model.internal.s3vectors.IndexIdentifier;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.request.DeleteIndexRequest;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP controller for deleting vector indexes.
 * Handles DELETE requests to remove vector indexes from a vector bucket.
 */
public class DeleteIndexController implements RouterHttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(DeleteIndexController.class);

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public DeleteIndexController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    DeleteIndexRequest deleteRequest = parseRequest(request);
    processRequest(deleteRequest);
    HttpRequestUtils.sendEmptyJsonResponse(response);
  }

  private DeleteIndexRequest parseRequest(RouterHttpRequest request) throws Exception {
    byte[] bodyBytes = HttpRequestUtils.extractRequestBody(request);
    return HttpRequestUtils.parseRequiredRequest(bodyBytes, DeleteIndexRequest.class, objectMapper);
  }

  private void processRequest(DeleteIndexRequest deleteRequest) {
    IndexIdentifier indexIdentifier = VectorIndexAssertions.resolveIndexIdentifier(
        deleteRequest.getVectorBucketName(),
        deleteRequest.getIndexArn(),
        deleteRequest.getIndexName()
    );
    
    s3VectorsService.deleteIndex(indexIdentifier.bucketName(), indexIdentifier.indexName());
  }
}
