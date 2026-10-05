package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.assertions.vectors.VectorIndexAssertions;
import com.robothy.s3.core.model.internal.s3vectors.IndexIdentifier;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.request.DeleteVectorsRequest;
import com.robothy.s3.datatypes.s3vectors.response.DeleteVectorsResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

public class DeleteVectorsController implements RouterHttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(DeleteVectorsController.class);

  private final S3VectorsService vectorsService;
  private final ObjectMapper objectMapper;

  public DeleteVectorsController(ServiceFactory serviceFactory) {
    this.vectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    DeleteVectorsRequest vectorsRequest = parseRequest(request);
    DeleteVectorsResponse vectorsResponse = processRequest(vectorsRequest);
    HttpRequestUtils.sendJsonResponse(response, vectorsResponse, objectMapper);
  }

  private DeleteVectorsRequest parseRequest(RouterHttpRequest request) throws Exception {
    byte[] requestBody = HttpRequestUtils.extractRequestBody(request);
    return HttpRequestUtils.parseRequiredRequest(requestBody, DeleteVectorsRequest.class, objectMapper);
  }

  private DeleteVectorsResponse processRequest(DeleteVectorsRequest request) {
    IndexIdentifier indexIdentifier = VectorIndexAssertions.resolveIndexIdentifier(
        request.getVectorBucketName(),
        request.getIndexArn(),
        request.getIndexName()
    );

    return vectorsService.deleteVectors(
        indexIdentifier.bucketName(),
        indexIdentifier.indexName(),
        request.getKeys()
    );
  }

}
