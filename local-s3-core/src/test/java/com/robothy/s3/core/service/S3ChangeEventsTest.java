package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.ObjectIdentifier;
import com.robothy.s3.datatypes.request.DeleteObjectsRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The services publish the changes they commit, however they are called: these tests call them directly, like an
 * application that embeds LocalS3 does.
 */
class S3ChangeEventsTest extends LocalS3ServiceTestBase {

  private static final String BUCKET = "events";

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void publishesTheChangesOfBucketsAndObjects(LocalS3Manager manager) throws Exception {
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    manager.addChangeListener(changes::add);
    BucketService bucketService = manager.bucketService();
    ObjectService objectService = manager.objectService();

    bucketService.createBucket(BUCKET, "eu-west-1");
    assertEquals(List.of(S3Change.bucketCreated("CreateBucket", BUCKET, "eu-west-1")), changes);

    changes.clear();
    PutObjectAns put = put(objectService, "a.txt", "Hello");
    assertEquals(List.of(S3Change.objectVersion(S3ChangeType.OBJECT_CREATED, "PutObject", BUCKET, "a.txt", null, 5,
        put.getEtag())), changes);
    assertEquals("s3:ObjectCreated:Put", changes.get(0).s3EventName());

    changes.clear();
    objectService.copyObject(BUCKET, "b.txt", CopyObjectOptions.builder().sourceBucket(BUCKET).sourceKey("a.txt").build());
    assertEquals(1, changes.size(), "The put that the copy is made of isn't published on its own.");
    assertEquals("CopyObject", changes.get(0).operation());
    assertEquals("b.txt", changes.get(0).key());
    assertEquals("s3:ObjectCreated:Copy", changes.get(0).s3EventName());

    changes.clear();
    objectService.putObject(BUCKET, "form.txt", PutObjectOptions.builder()
        .operation("PostObject")
        .content(new ByteArrayInputStream("form".getBytes(StandardCharsets.UTF_8)))
        .size(4)
        .build());
    assertEquals("PostObject", changes.get(0).operation());
    assertEquals("s3:ObjectCreated:Post", changes.get(0).s3EventName());
    objectService.deleteObject(BUCKET, "form.txt");

    changes.clear();
    objectService.putObjectTagging(BUCKET, "a.txt", null, new String[][] {{"k", "v"}});
    objectService.deleteObjectTagging(BUCKET, "a.txt", null);
    objectService.putObjectAcl(BUCKET, "a.txt", null, AccessControlPolicy.builder().build());
    assertEquals(List.of(S3ChangeType.OBJECT_TAGGING_PUT, S3ChangeType.OBJECT_TAGGING_DELETED,
        S3ChangeType.OBJECT_ACL_PUT), changes.stream().map(S3Change::type).toList());
    assertEquals(List.of("s3:ObjectTagging:Put", "s3:ObjectTagging:Delete", "s3:ObjectAcl:Put"),
        changes.stream().map(S3Change::s3EventName).toList());
    assertTrue(changes.stream().allMatch(change -> put.getEtag().equals(change.etag()) && change.size() == 5L));

    changes.clear();
    objectService.deleteObject(BUCKET, "a.txt");
    assertEquals(List.of(S3Change.objectDeleted("DeleteObject", BUCKET, "a.txt", null, false)), changes);
    assertEquals("s3:ObjectRemoved:Delete", changes.get(0).s3EventName());

    changes.clear();
    objectService.deleteObject(BUCKET, "b.txt");
    bucketService.deleteBucket(BUCKET);
    assertEquals(S3Change.bucketDeleted("DeleteBucket", BUCKET, "eu-west-1"), changes.get(1));
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void publishesTheChangesOfMultipartUploads(LocalS3Manager manager) {
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    manager.addChangeListener(changes::add);
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();

    String aborted = objectService.createMultipartUpload(BUCKET, "aborted.bin", CreateMultipartUploadOptions.builder().build());
    uploadPart(objectService, "aborted.bin", aborted);
    changes.clear();
    objectService.abortMultipartUpload(BUCKET, "aborted.bin", aborted);
    objectService.abortMultipartUpload(BUCKET, "aborted.bin", aborted);
    assertEquals(List.of(S3Change.multipartUploadAborted("AbortMultipartUpload", BUCKET, "aborted.bin", aborted)),
        changes, "Aborting an upload that doesn't exist changes nothing.");

    String completed = objectService.createMultipartUpload(BUCKET, "big.bin", CreateMultipartUploadOptions.builder().build());
    uploadPart(objectService, "big.bin", completed);
    changes.clear();
    objectService.completeMultipartUpload(BUCKET, "big.bin", completed,
        List.of(CompleteMultipartUploadPartOption.builder().partNumber(1).build()));
    assertEquals(1, changes.size(), "Neither creating an upload nor uploading a part is published.");
    assertEquals(S3ChangeType.OBJECT_CREATED, changes.get(0).type());
    assertEquals("CompleteMultipartUpload", changes.get(0).operation());
    assertEquals("s3:ObjectCreated:CompleteMultipartUpload", changes.get(0).s3EventName());
    assertEquals(4L, changes.get(0).size());
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void publishesTheObjectsThatDeleteObjectsDeleted(LocalS3Manager manager) {
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    manager.addChangeListener(changes::add);
    manager.bucketService().createBucket(BUCKET);
    manager.bucketService().setVersioningEnabled(BUCKET, true);
    ObjectService objectService = manager.objectService();
    put(objectService, "a.txt", "Hello");

    changes.clear();
    List<Object> results = objectService.deleteObjects(BUCKET, new DeleteObjectsRequest(List.of(
        new ObjectIdentifier("a.txt", null), new ObjectIdentifier("missing.txt", "123")), true));

    assertEquals(0, results.size(), "A quiet result only reports errors, and a version that is gone isn't one.");
    assertEquals(1, changes.size(), "A version that is already gone changes nothing, so it isn't published.");
    S3Change deleted = changes.get(0);
    assertEquals("DeleteObjects", deleted.operation());
    assertEquals("a.txt", deleted.key());
    assertTrue(deleted.deleteMarker());
    assertEquals("s3:ObjectRemoved:DeleteMarkerCreated", deleted.s3EventName());
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void publishesNothingForARejectedOperation(LocalS3Manager manager) {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    put(objectService, "a.txt", "Hello");
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    manager.addChangeListener(changes::add);

    assertThrows(PreconditionFailedException.class, () -> objectService.putObject(BUCKET, "a.txt",
        PutObjectOptions.builder()
            .content(new ByteArrayInputStream("World".getBytes(StandardCharsets.UTF_8)))
            .preconditions(ObjectPreconditions.builder().ifNoneMatch(ObjectPreconditions.WILDCARD).build())
            .build()));
    assertThrows(RuntimeException.class, () -> objectService.putObjectTagging(BUCKET, "missing.txt", null,
        new String[][] {{"k", "v"}}));

    assertEquals(List.of(), changes);
  }

  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aRemovedListenerReceivesNothing(LocalS3Manager manager) {
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    com.robothy.s3.core.event.S3ChangeListener listener = changes::add;
    manager.addChangeListener(listener);
    manager.removeChangeListener(listener);

    manager.bucketService().createBucket(BUCKET);

    assertFalse(changes.iterator().hasNext());
    assertNull(S3Change.bucketCreated("CreateBucket", BUCKET, null).s3EventName(),
        "Amazon S3 doesn't notify of created buckets.");
  }

  /**
   * A listener that runs on the thread of the change, which it does by default, and fails with an error that isn't
   * caught, e.g. a {@linkplain StackOverflowError}, fails the operation once the change is committed. The content that
   * the operation stored is referenced by then, and is kept.
   */
  @MethodSource("localS3Managers")
  @ParameterizedTest
  void anErrorOfAListenerKeepsTheContentOfTheCommittedObject(LocalS3Manager manager) throws Exception {
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    put(objectService, "source.txt", "Source");
    manager.addChangeListener(change -> {
      throw new StackOverflowError("listener");
    });

    assertThrows(StackOverflowError.class, () -> put(objectService, "a.txt", "Hello"));
    assertThrows(StackOverflowError.class, () -> objectService.copyObject(BUCKET, "b.txt",
        CopyObjectOptions.builder().sourceBucket(BUCKET).sourceKey("source.txt").build()));
    assertThrows(StackOverflowError.class, () -> objectService.putObject(BUCKET, "a.txt", PutObjectOptions.builder()
        .content(new ByteArrayInputStream("World".getBytes(StandardCharsets.UTF_8)))
        .build()));

    assertEquals("World", read(objectService, "a.txt"));
    assertEquals("Source", read(objectService, "b.txt"));
  }

  private static PutObjectAns put(ObjectService objectService, String key, String content) {
    return objectService.putObject(BUCKET, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
        .contentType("text/plain")
        .build());
  }

  private static String read(ObjectService objectService, String key) throws IOException {
    try (InputStream in = objectService.getObject(BUCKET, key, GetObjectOptions.builder().build()).getContent()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void uploadPart(ObjectService objectService, String key, String uploadId) {
    objectService.uploadPart(BUCKET, key, uploadId, 1, UploadPartOptions.builder()
        .data(new ByteArrayInputStream("part".getBytes(StandardCharsets.UTF_8)))
        .contentLength(4)
        .build());
  }

}
