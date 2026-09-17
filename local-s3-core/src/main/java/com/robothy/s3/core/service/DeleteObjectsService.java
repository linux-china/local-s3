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

  /**
   * The max number of objects that a request deletes, like Amazon S3.
   */
  int MAX_OBJECTS = 1000;

  /**
   * Delete objects from a specified bucket.
   *
   * <p>A request must name between 1 and {@value #MAX_OBJECTS} objects, otherwise it is rejected with
   * {@code MalformedXML} before the bucket is locked, like Amazon S3 does.
   *
   * <p>An object that can't be deleted is reported as an error of its own, and the other objects are still deleted:
   * with the S3 error of a {@linkplain LocalS3Exception}, or with {@code InternalError} for any other exception, which
   * is logged, and whose message isn't revealed to the client. An {@linkplain Error}, e.g. an
   * {@linkplain OutOfMemoryError}, isn't caught, and fails the whole request.
   *
   * @param bucketName bucket name.
   * @param request    delete objects request.
   * @return delete results.
   * @throws LocalS3RequestException of {@code MalformedXML} if the request names no object or more than
   *     {@value #MAX_OBJECTS}.
   */
  default List<Object> deleteObjects(String bucketName, DeleteObjectsRequest request) {
    return deleteObjects(bucketName, request, false);
  }

  /**
   * Delete objects from a specified bucket, like {@linkplain #deleteObjects(String, DeleteObjectsRequest)}. A version
   * that Object Lock protects is reported as an {@code AccessDenied} error of its own.
   *
   * @param bucketName bucket name.
   * @param request delete objects request.
   * @param bypassGovernanceRetention whether the request bypasses the governance mode retention of the versions.
   * @return delete results.
   */
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

          DeleteResult.Deleted deleted = new DeleteResult.Deleted();
          deleted.setKey(key);
          deleted.setVersionId(versionId);
          if (deleteObjectAns.isDeleteMarker()) {
            deleted.setDeleteMarker(true);
            deleted.setDeleteMarkerVersionId(deleteObjectAns.getVersionId());
          }
          results.add(deleted);
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

  /**
   * The conditions of an object to delete: its {@code ETag}, {@code LastModifiedTime} and {@code Size}, which are
   * evaluated like the {@code If-Match}, {@code x-amz-if-match-last-modified-time} and {@code x-amz-if-match-size}
   * headers of {@code DeleteObject}. An object whose condition fails is reported as an error of its own.
   */
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
