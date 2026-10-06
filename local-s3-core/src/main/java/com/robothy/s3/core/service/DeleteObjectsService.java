package com.robothy.s3.core.service;

import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.answers.DeleteObjectAns;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.datatypes.ObjectIdentifier;
import com.robothy.s3.datatypes.request.DeleteObjectsRequest;
import com.robothy.s3.datatypes.response.DeleteResult;
import com.robothy.s3.datatypes.response.S3Error;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

public interface DeleteObjectsService extends DeleteObjectService {

  int MAX_OBJECTS = 1000;

  /**
   * See {@code docs/semantics.md#request-validation}. An {@linkplain Error} isn't caught, and fails the whole request.
   */
  default List<Object> deleteObjects(String bucketName, DeleteObjectsRequest request) {
    return deleteObjects(bucketName, request, false);
  }

  default List<Object> deleteObjects(String bucketName, DeleteObjectsRequest request,
                                     boolean bypassGovernanceRetention) {
    List<ObjectIdentifier> objects = request.getObjects();
    if (objects == null || objects.isEmpty() || objects.size() > MAX_OBJECTS) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
    // The objects that deleteObject deletes are deleted by DeleteObjects.
    return asOperation("DeleteObjects", () -> changeBucket(bucketName, () -> {
      List<Object> results = new ArrayList<>(objects.size());
      for (ObjectIdentifier id : objects) {
        String key = id.getKey();
        String versionId = id.getVersionId().orElse(null);
        try {
          ObjectPreconditions preconditions = preconditions(id);
          DeleteObjectAns deleteObjectAns = preconditions.isEmpty() && !bypassGovernanceRetention
              ? deleteObject(bucketName, key, versionId)
              : deleteObject(bucketName, key, versionId, preconditions, bypassGovernanceRetention);
          if (request.isQuiet()) {
            continue;
          }

          boolean deleteMarker = deleteObjectAns.isDeleteMarker();
          results.add(new DeleteResult.Deleted(deleteMarker, deleteMarker ? deleteObjectAns.getVersionId() : null,
              key, versionId));
        } catch (LocalS3Exception e) {
          results.add(deleteError(bucketName, key, versionId, e.getS3ErrorCode(), e.getMessage()));
        } catch (RuntimeException e) {
          LoggerFactory.getLogger(DeleteObjectsService.class)
              .error("Failed to delete the object {} (version {}) of bucket {}.", key, versionId, bucketName, e);
          results.add(deleteError(bucketName, key, versionId, S3ErrorCode.InternalError,
              S3ErrorCode.InternalError.description()));
        }
      }
      return results;
    }));
  }

  private static ObjectPreconditions preconditions(ObjectIdentifier id) {
    if (id.getETag() == null && id.getLastModifiedTime() == null && id.getSize() == null) {
      return ObjectPreconditions.none();
    }
    return ObjectPreconditions.builder()
        .ifMatch(id.getETag())
        .ifMatchLastModifiedTime(id.getLastModifiedTime() == null ? null : id.getLastModifiedTime().toEpochMilli())
        .ifMatchSize(id.getSize())
        .build();
  }

  private static S3Error deleteError(String bucketName, String key, String versionId, S3ErrorCode errorCode,
                                     String message) {
    return S3Error.builder()
        .bucketName(bucketName)
        .code(errorCode.code())
        .message(message)
        .key(key)
        .versionId(versionId)
        .build();
  }

}
