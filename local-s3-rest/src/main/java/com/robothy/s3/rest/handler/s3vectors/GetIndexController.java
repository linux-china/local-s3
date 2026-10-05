package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.assertions.vectors.VectorIndexAssertions;
import com.robothy.s3.core.model.internal.s3vectors.IndexIdentifier;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.request.GetIndexRequest;
import com.robothy.s3.datatypes.s3vectors.response.GetIndexResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP controller for retrieving vector index details.
 * Handles GET requests to retrieve information about a specific vector index.
 */
public class GetIndexController implements RouterHttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(GetIndexController.class);

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public GetIndexController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    GetIndexRequest getRequest = parseRequest(request);
    GetIndexResponse getResponse = processRequest(getRequest);
    HttpRequestUtils.sendJsonResponse(response, getResponse, objectMapper);
  }

  private GetIndexRequest parseRequest(RouterHttpRequest request) throws Exception {
    byte[] bodyBytes = HttpRequestUtils.extractRequestBody(request);
    return HttpRequestUtils.parseRequiredRequest(bodyBytes, GetIndexRequest.class, objectMapper);
  }

  private GetIndexResponse processRequest(GetIndexRequest getRequest) {
    IndexIdentifier indexIdentifier = VectorIndexAssertions.resolveIndexIdentifier(
        getRequest.getVectorBucketName(),
        getRequest.getIndexArn(),
        getRequest.getIndexName()
    );
    
    return s3VectorsService.getIndex(indexIdentifier.bucketName(), indexIdentifier.indexName());
  }

}
