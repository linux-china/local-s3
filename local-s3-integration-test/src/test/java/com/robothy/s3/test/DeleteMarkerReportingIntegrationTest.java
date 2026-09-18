package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/**
 * Delete markers are reported the way Amazon S3 reports them: {@code x-amz-delete-marker} only on a version
 * that is one, and the delete marker fields of a {@code DeleteObjects} result only on a deletion that created
 * one. LocalS3 answered {@code x-amz-delete-marker: false} on every {@code HeadObject}, and wrote the fields
 * of every deletion, through 2.4.
 */
class DeleteMarkerReportingIntegrationTest {

  @Test
  @LocalS3
  void headObjectAnswersTheDeleteMarkerHeaderOnlyForADeleteMarker(S3Client s3, LocalS3Endpoint endpoint)
      throws Exception {
    String bucket = "delete-markers";
    s3.createBucket(request -> request.bucket(bucket));
    s3.putBucketVersioning(request -> request.bucket(bucket)
        .versioningConfiguration(configuration -> configuration.status(BucketVersioningStatus.ENABLED)));
    String versionId = s3.putObject(request -> request.bucket(bucket).key("a.txt"),
        RequestBody.fromString("Hello")).versionId();

    // An object is no delete marker, so the header is left out, like Amazon S3 leaves it out.
    assertNull(head(endpoint, "/" + bucket + "/a.txt").headers().firstValue("x-amz-delete-marker").orElse(null));

    String markerVersionId = s3.deleteObject(request -> request.bucket(bucket).key("a.txt")).versionId();
    assertNotNull(markerVersionId);

    // The version that the deletion created is one, and says so.
    HttpResponse<Void> marker = head(endpoint, "/" + bucket + "/a.txt?versionId=" + markerVersionId);
    assertEquals(405, marker.statusCode());
    assertEquals("true", marker.headers().firstValue("x-amz-delete-marker").orElse(null));

    // The version it hid is still no delete marker.
    HttpResponse<Void> hidden = head(endpoint, "/" + bucket + "/a.txt?versionId=" + versionId);
    assertEquals(200, hidden.statusCode());
    assertNull(hidden.headers().firstValue("x-amz-delete-marker").orElse(null));
  }

  @Test
  @LocalS3
  void deleteObjectsNamesTheDeleteMarkerOnlyWhenItCreatedOne(S3Client s3, LocalS3Endpoint endpoint)
      throws Exception {
    String bucket = "delete-markers-batch";
    s3.createBucket(request -> request.bucket(bucket));
    s3.putObject(request -> request.bucket(bucket).key("a.txt"), RequestBody.fromString("Hello"));

    // A bucket that was never versioned deletes the object itself, so Amazon S3 answers the key alone.
    DeleteObjectsResponse deleted = s3.deleteObjects(request -> request.bucket(bucket)
        .delete(Delete.builder().objects(ObjectIdentifier.builder().key("a.txt").build()).build()));
    DeletedObject object = deleted.deleted().get(0);
    assertEquals("a.txt", object.key());
    assertNull(object.deleteMarker());
    assertNull(object.deleteMarkerVersionId());
    assertNull(object.versionId());

    String body = post(endpoint, "/" + bucket + "?delete",
        "<Delete><Object><Key>absent.txt</Key></Object></Delete>");
    assertTrue(body.contains("<Key>absent.txt</Key>"), body);
    assertFalse(body.contains("DeleteMarker"), body);
    assertFalse(body.contains("VersionId"), body);
  }

  @Test
  @LocalS3
  void deleteObjectsNamesTheDeleteMarkerItCreated(S3Client s3) {
    String bucket = "delete-markers-versioned";
    s3.createBucket(request -> request.bucket(bucket));
    s3.putBucketVersioning(request -> request.bucket(bucket)
        .versioningConfiguration(configuration -> configuration.status(BucketVersioningStatus.ENABLED)));
    s3.putObject(request -> request.bucket(bucket).key("a.txt"), RequestBody.fromString("Hello"));

    // A versioned deletion creates a delete marker, which Amazon S3 names in the result.
    DeleteObjectsResponse deleted = s3.deleteObjects(request -> request.bucket(bucket)
        .delete(Delete.builder().objects(List.of(ObjectIdentifier.builder().key("a.txt").build())).build()));
    DeletedObject object = deleted.deleted().get(0);
    assertEquals("a.txt", object.key());
    assertEquals(Boolean.TRUE, object.deleteMarker());
    assertNotNull(object.deleteMarkerVersionId());
  }

  private static HttpResponse<Void> head(LocalS3Endpoint endpoint, String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(endpoint.endpoint() + path))
            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private static String post(LocalS3Endpoint endpoint, String path, String body) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(endpoint.endpoint() + path))
            .header("content-type", "application/xml")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString()).body();
  }

}
