package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.service.DeleteObjectsService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.datatypes.request.DeleteObjectsRequest;
import com.robothy.s3.datatypes.response.DeleteResult;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.InputStream;
import java.util.List;

class DeleteObjectsController extends ObjectHttpRequestHandler {

    private final DeleteObjectsService deleteObjectsService;

    DeleteObjectsController(ServiceFactory serviceFactory) {
        super(serviceFactory);
        this.deleteObjectsService = serviceFactory.getInstance(ObjectService.class);
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Exception {
        String bucketName = RequestAssertions.assertBucketNameProvided(request);

        try (InputStream decodedBody = RequestUtils.getBody(request).getDecodedBody()) {
            DeleteObjectsRequest deleteObjectsRequest =
                    xmlMapper.readValue(decodedBody, DeleteObjectsRequest.class);
            // The service leaves the deleted objects out of a quiet result, and publishes their events either way.
            List<Object> results = this.deleteObjectsService.deleteObjects(bucketName, deleteObjectsRequest);
            String xml = xmlMapper.writeValueAsString(new DeleteResult(results));
            response.status(HttpResponseStatus.OK)
                    .write(xml);
            ResponseUtils.addCommonHeaders(response);
        }

    }

}
