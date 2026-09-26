package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.service.DeleteObjectsService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.datatypes.ObjectIdentifier;
import com.robothy.s3.datatypes.request.DeleteObjectsRequest;
import com.robothy.s3.datatypes.response.DeleteResult;
import com.robothy.s3.datatypes.response.S3Error;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class DeleteObjectsController extends ObjectHttpRequestHandler {

    private final DeleteObjectsService deleteObjectsService;

    private final SessionPolicyAuthorizer sessionPolicyAuthorizer;

    /**
     * @param sessionPolicyAuthorizer authorizes each object by the session policy of the temporary credentials of the
     *     request, like Amazon S3 does; {@code null} to authorize none.
     */
    DeleteObjectsController(ServiceFactory serviceFactory, SessionPolicyAuthorizer sessionPolicyAuthorizer) {
        super(serviceFactory);
        this.deleteObjectsService = serviceFactory.getInstance(ObjectService.class);
        this.sessionPolicyAuthorizer = sessionPolicyAuthorizer;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Exception {
        String bucketName = RequestAssertions.assertBucketNameProvided(request);

        try (InputStream decodedBody = RequestUtils.getBody(request).getDecodedBody()) {
            DeleteObjectsRequest deleteObjectsRequest =
                    xmlMapper.readValue(decodedBody, DeleteObjectsRequest.class);
            List<Object> denied = deny(request, bucketName, deleteObjectsRequest);
            // The service leaves the deleted objects out of a quiet result, and publishes their events either way.
            List<Object> results = denied.isEmpty() || !deleteObjectsRequest.getObjects().isEmpty()
                    ? new ArrayList<>(this.deleteObjectsService.deleteObjects(bucketName, deleteObjectsRequest,
                            RequestUtils.isBypassGovernanceRetention(request)))
                    : new ArrayList<>();
            results.addAll(denied);
            String xml = xmlMapper.writeValueAsString(new DeleteResult(results));
            response.status(HttpResponseStatus.OK)
                    .write(xml);
            ResponseUtils.addCommonHeaders(response);
        }

    }

    /**
     * Remove the objects that the session policy of the request doesn't allow to delete from the request.
     *
     * @return an {@code AccessDenied} error for each of them, which a quiet result reports too.
     */
    private List<Object> deny(HttpRequest request, String bucketName, DeleteObjectsRequest deleteObjectsRequest) {
        List<ObjectIdentifier> objects = deleteObjectsRequest.getObjects();
        Optional<SessionPolicy> policy = sessionPolicyAuthorizer == null ? Optional.empty()
                : sessionPolicyAuthorizer.policy(request);
        // A request of no object or of too many is rejected as a whole by the service.
        if (policy.isEmpty() || objects == null || objects.isEmpty()
                || objects.size() > DeleteObjectsService.MAX_OBJECTS) {
            return List.of();
        }
        List<ObjectIdentifier> allowed = new ArrayList<>(objects.size());
        List<Object> denied = new ArrayList<>();
        for (ObjectIdentifier id : objects) {
            String versionId = id.getVersionId().orElse(null);
            if (SessionPolicyAuthorizer.allowsDelete(policy.get(), bucketName, id.getKey(), versionId)) {
                allowed.add(id);
            } else {
                denied.add(S3Error.builder()
                        .code(S3ErrorCode.AccessDenied.code())
                        .message(S3ErrorCode.AccessDenied.description())
                        .key(id.getKey())
                        .versionId(versionId)
                        .build());
            }
        }
        deleteObjectsRequest.setObjects(allowed);
        return denied;
    }

}
