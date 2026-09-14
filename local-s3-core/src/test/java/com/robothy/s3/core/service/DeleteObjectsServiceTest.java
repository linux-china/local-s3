package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.answers.DeleteObjectAns;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.datatypes.ObjectIdentifier;
import com.robothy.s3.datatypes.request.DeleteObjectsRequest;
import com.robothy.s3.datatypes.response.DeleteResult;
import com.robothy.s3.datatypes.response.S3Error;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DeleteObjectsServiceTest extends LocalS3ServiceTestBase {

  @MethodSource("localS3Services")
  @ParameterizedTest
  void testDeleteObjects(BucketService bucketService, ObjectService objectService) {
    String bucketName = "my-bucket";
    bucketService.createBucket(bucketName);
    // Bucket versioning is suspended.
    bucketService.setVersioningEnabled(bucketName, Boolean.FALSE);

    List<ObjectIdentifier> objectsToDelete = List.of(
        new ObjectIdentifier("a.txt", "123"),
        new ObjectIdentifier("b.txt", null)
    );
    DeleteObjectsRequest request1 = new DeleteObjectsRequest(objectsToDelete, false);

    List<Object> results = objectService.deleteObjects(bucketName, request1);

    assertInstanceOf(S3Error.class, results.get(0));
    S3Error s3Error = (S3Error) results.get(0);
    assertEquals("a.txt", s3Error.getKey());
    assertEquals("123", s3Error.getVersionId());
    assertEquals(S3ErrorCode.NoSuchKey.code(), s3Error.getCode());

    assertInstanceOf(DeleteResult.Deleted.class, results.get(1));
    DeleteResult.Deleted deleted = (DeleteResult.Deleted) results.get(1);
    assertTrue(deleted.isDeleteMarker());
    assertEquals(ObjectMetadata.NULL_VERSION, deleted.getDeleteMarkerVersionId());

    DeleteObjectsRequest request2 = new DeleteObjectsRequest(objectsToDelete, true);
    List<Object> results2 = objectService.deleteObjects(bucketName, request2);
    assertEquals(1, results2.size());
    assertInstanceOf(S3Error.class, results2.get(0));
  }

  @MethodSource("localS3Services")
  @ParameterizedTest
  void testDeleteObjectsFromUnVersionedBucket(BucketService bucketService, ObjectService objectService) {
    String bucketName = "my-bucket";
    bucketService.createBucket(bucketName);

    List<Object> results = objectService.deleteObjects(bucketName, new DeleteObjectsRequest(List.of(
        new ObjectIdentifier("a.txt", "null"),
        new ObjectIdentifier("a.txt", "123"),
        new ObjectIdentifier("b.txt", "123")
    ), false));

    DeleteResult.Deleted deleted = assertInstanceOf(DeleteResult.Deleted.class, results.get(0));
    assertEquals("a.txt", deleted.getKey());
    assertEquals(ObjectMetadata.NULL_VERSION, deleted.getVersionId());
    assertFalse(deleted.isDeleteMarker());
    assertNull(deleted.getDeleteMarkerVersionId());

    S3Error s3Error = assertInstanceOf(S3Error.class, results.get(1));
    // AmazonS3 returns NoSuchVersion code.
    assertEquals(S3ErrorCode.InvalidArgument.code(), s3Error.getCode());

    assertInstanceOf(S3Error.class, results.get(2));
    assertEquals(S3ErrorCode.InvalidArgument.code(), s3Error.getCode());


    objectService.putObject(bucketName, "a.txt", PutObjectOptions.builder()
        .content(new ByteArrayInputStream("Hello".getBytes()))
        .contentType("plain.text")
        .size(5)
        .build());
    List<Object> results1 = objectService.deleteObjects(bucketName, new DeleteObjectsRequest(List.of(
        new ObjectIdentifier("a.txt", null)
    ), false));
    assertEquals(1, results1.size());
    DeleteResult.Deleted deleted1 = assertInstanceOf(DeleteResult.Deleted.class, results1.get(0));
    assertEquals("a.txt", deleted1.getKey());
    assertNull(deleted1.getVersionId());
    assertFalse(deleted1.isDeleteMarker());
    assertNull(deleted1.getDeleteMarkerVersionId());
    assertDoesNotThrow(() -> bucketService.deleteBucket(bucketName));
  }

  /**
   * Like Amazon S3, a request deletes at most 1000 objects, and at least one: any other request is rejected with
   * {@code MalformedXML} as a whole, before an object is deleted.
   */
  @MethodSource("localS3Services")
  @ParameterizedTest
  void rejectsRequestsWithoutObjectsOrWithMoreThan1000(BucketService bucketService, ObjectService objectService) {
    String bucketName = "my-bucket";
    bucketService.createBucket(bucketName);
    objectService.putObject(bucketName, "a.txt", PutObjectOptions.builder()
        .content(new ByteArrayInputStream("Hello".getBytes()))
        .size(5)
        .build());

    List<ObjectIdentifier> tooMany = IntStream.rangeClosed(0, DeleteObjectsService.MAX_OBJECTS)
        .mapToObj(i -> new ObjectIdentifier(i == 0 ? "a.txt" : "key-" + i, null))
        .toList();
    for (DeleteObjectsRequest request : List.of(new DeleteObjectsRequest(tooMany, false),
        new DeleteObjectsRequest(List.of(), false), new DeleteObjectsRequest(null, true))) {
      LocalS3Exception rejected = assertThrows(LocalS3Exception.class,
          () -> objectService.deleteObjects(bucketName, request));
      assertEquals(S3ErrorCode.MalformedXML, rejected.getS3ErrorCode());
    }
    assertDoesNotThrow(() -> objectService.headObject(bucketName, "a.txt", GetObjectOptions.builder().build()), "Nothing is deleted.");

    List<Object> results = objectService.deleteObjects(bucketName,
        new DeleteObjectsRequest(tooMany.subList(0, DeleteObjectsService.MAX_OBJECTS), false));
    assertEquals(DeleteObjectsService.MAX_OBJECTS, results.size());
    assertTrue(results.stream().allMatch(DeleteResult.Deleted.class::isInstance));
  }

  /**
   * An object that fails with an unexpected exception is reported as {@code InternalError}, without the message of
   * the exception, which may reveal internals; the other objects are still deleted.
   */
  @Test
  void reportsAnUnexpectedFailureAsAnInternalErrorWithoutItsMessage() {
    DeleteObjectsService service = service();
    doThrow(new IllegalStateException("/data/.storage/12/34 is not writable"))
        .when(service).deleteObject("my-bucket", "broken.txt", null);
    doThrow(new ObjectNotExistException("missing.txt"))
        .when(service).deleteObject("my-bucket", "missing.txt", "v1");

    List<Object> results = service.deleteObjects("my-bucket", new DeleteObjectsRequest(List.of(
        new ObjectIdentifier("broken.txt", null),
        new ObjectIdentifier("missing.txt", "v1"),
        new ObjectIdentifier("ok.txt", null)), false));

    S3Error internal = assertInstanceOf(S3Error.class, results.get(0));
    assertEquals(S3ErrorCode.InternalError.code(), internal.getCode());
    assertEquals(S3ErrorCode.InternalError.description(), internal.getMessage());
    assertEquals("broken.txt", internal.getKey());
    S3Error notFound = assertInstanceOf(S3Error.class, results.get(1));
    assertEquals(S3ErrorCode.NoSuchKey.code(), notFound.getCode());
    assertEquals("v1", notFound.getVersionId());
    assertEquals("ok.txt", assertInstanceOf(DeleteResult.Deleted.class, results.get(2)).getKey());
  }

  /**
   * An {@linkplain Error}, e.g. an {@linkplain OutOfMemoryError}, isn't reported as the error of an object, but fails
   * the request, so that the guard of the bucket rolls the change back.
   */
  @Test
  void doesNotSwallowErrors() {
    DeleteObjectsService service = service();
    doThrow(new OutOfMemoryError("Java heap space")).when(service).deleteObject("my-bucket", "a.txt", null);

    assertThrows(OutOfMemoryError.class, () -> service.deleteObjects("my-bucket", new DeleteObjectsRequest(List.of(
        new ObjectIdentifier("a.txt", null), new ObjectIdentifier("b.txt", null)), false)));
    verify(service, never()).deleteObject(eq("my-bucket"), eq("b.txt"), any());
  }

  /**
   * A service whose objects are deleted by stubs, and whose other objects are deleted without a delete marker.
   */
  private static DeleteObjectsService service() {
    DeleteObjectsService service = mock(DeleteObjectsService.class, CALLS_REAL_METHODS);
    doReturn(BucketGuard.inMemory()).when(service).bucketGuard();
    doReturn(DeleteObjectAns.builder().isDeleteMarker(false).build()).when(service).deleteObject(any(), any(), any());
    return service;
  }

}
