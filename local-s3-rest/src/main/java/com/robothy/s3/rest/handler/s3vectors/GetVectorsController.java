package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.assertions.vectors.VectorIndexAssertions;
import com.robothy.s3.core.model.internal.s3vectors.IndexIdentifier;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.request.GetVectorsRequest;
import com.robothy.s3.datatypes.s3vectors.response.GetVectorsResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

public class GetVectorsController implements RouterHttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(GetVectorsController.class);

  private final S3VectorsService vectorsService;
  private final ObjectMapper objectMapper;

  public GetVectorsController(ServiceFactory serviceFactory) {
    this.vectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    GetVectorsRequest vectorsRequest = parseRequest(request);
    GetVectorsResponse vectorsResponse = processRequest(vectorsRequest);
    HttpRequestUtils.sendJsonResponse(response, vectorsResponse, objectMapper);
  }

  private GetVectorsRequest parseRequest(RouterHttpRequest request) throws Exception {
    byte[] requestBody = HttpRequestUtils.extractRequestBody(request);
    return HttpRequestUtils.parseRequiredRequest(requestBody, GetVectorsRequest.class, objectMapper);
  }

  private GetVectorsResponse processRequest(GetVectorsRequest request) {
    IndexIdentifier indexIdentifier = VectorIndexAssertions.resolveIndexIdentifier(
        request.getVectorBucketName(),
        request.getIndexArn(),
        request.getIndexName()
    );

    return vectorsService.getVectors(
        indexIdentifier.bucketName(),
        indexIdentifier.indexName(),
        request.getKeys(),
        request.getReturnData(),
        request.getReturnMetadata()
    );
  }
}
