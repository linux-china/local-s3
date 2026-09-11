package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.service.DeleteObjectsService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.datatypes.request.DeleteObjectsRequest;
import com.robothy.s3.datatypes.response.DeleteResult;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.listener.ObjectEvent;
import com.robothy.s3.rest.listener.S3EventType;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

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
            // Always collect the deleted objects to fire events for them; quiet mode only affects the response.
            boolean quiet = deleteObjectsRequest.isQuiet();
            deleteObjectsRequest.setQuiet(false);
            List<Object> results = this.deleteObjectsService.deleteObjects(bucketName, deleteObjectsRequest);
            List<Object> responseItems = quiet
                    ? results.stream().filter(item -> !(item instanceof DeleteResult.Deleted)).collect(Collectors.toList())
                    : results;
            String xml = xmlMapper.writeValueAsString(new DeleteResult(responseItems));
            response.status(HttpResponseStatus.OK)
                    .write(xml);
            ResponseUtils.addCommonHeaders(response);
            for (Object item : results) {
                if (item instanceof DeleteResult.Deleted deleted) {
                    String versionId = deleted.isDeleteMarker() ? deleted.getDeleteMarkerVersionId() : deleted.getVersionId();
                    fireObjectEvent(new ObjectEvent(S3EventType.OBJECT_DELETED, "DeleteObjects", bucketName,
                            deleted.getKey(), versionId, null, null, deleted.isDeleteMarker()));
                }
            }
        }

    }

}
