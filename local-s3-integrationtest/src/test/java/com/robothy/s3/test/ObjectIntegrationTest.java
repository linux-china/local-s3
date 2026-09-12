package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.supplier.DataPathSupplier;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.StringUtils;

public class ObjectIntegrationTest {

  @Test
  @LocalS3
  void testGetObject(S3Client s3) throws IOException {
    String bucket = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

    String key = "a.txt";
    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString("Text1"));
    ResponseBytes<GetObjectResponse> objectBytes = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).build(),
        ResponseTransformer.toBytes());
    assertEquals("null", objectBytes.response().versionId());
    assertEquals("Text1", objectBytes.asUtf8String());
    assertEquals(DigestUtils.md5Hex("Text1"), objectBytes.response().eTag());

    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString("Text2"));
    ResponseBytes<GetObjectResponse> objectBytes1 = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).build(),
        ResponseTransformer.toBytes());
    assertEquals("null", objectBytes1.response().versionId());
    assertEquals("Text2", objectBytes1.asUtf8String());

    s3.putBucketVersioning(PutBucketVersioningRequest.builder()
        .bucket(bucket)
        .versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build())
        .build());
    PutObjectResponse putObjectResult = s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString("Text3"));
    assertNotNull(putObjectResult.versionId());
    ResponseBytes<GetObjectResponse> objectBytes2 = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).build(),
        ResponseTransformer.toBytes());
    assertEquals(putObjectResult.versionId(), objectBytes2.response().versionId());
    assertEquals("Text3", objectBytes2.asUtf8String());

    PutObjectResponse putObjectResult1 = s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString("Text4"));
    assertNotNull(putObjectResult1.versionId());
    ResponseBytes<GetObjectResponse> objectBytes3 = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).build(),
        ResponseTransformer.toBytes());
    assertEquals(putObjectResult1.versionId(), objectBytes3.response().versionId());
    assertEquals("Text4".length(), objectBytes3.response().contentLength());
    assertEquals("Text4", objectBytes3.asUtf8String());

    ResponseBytes<GetObjectResponse> objectBytes4 = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).versionId(putObjectResult.versionId()).build(),
        ResponseTransformer.toBytes());
    assertEquals(putObjectResult.versionId(), objectBytes4.response().versionId());
    assertEquals("Text3", objectBytes4.asUtf8String());

    HeadObjectResponse objectMetadata = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    assertEquals("Text4".length(), objectMetadata.contentLength());
    assertEquals(putObjectResult1.versionId(), objectMetadata.versionId());

    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    assertThrows(NoSuchKeyException.class, () -> s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), ResponseTransformer.toBytes()));
    assertThrows(NoSuchKeyException.class, () -> s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()));
  }

  /**
   * GetObject reports the Last-Modified of the object, like HeadObject does; code that caches objects or
   * syncs them incrementally reads it from every response.
   */
  @Test
  @LocalS3
  void testGetObjectReturnsLastModified(S3Client s3) {
    String bucket = "my-bucket";
    String key = "a.txt";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    s3.putBucketVersioning(PutBucketVersioningRequest.builder()
        .bucket(bucket)
        .versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build())
        .build());
    Instant beforePut = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    PutObjectResponse firstVersion =
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString("Text1"));
    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString("Text2"));

    GetObjectResponse latest = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
        ResponseTransformer.toBytes()).response();
    assertNotNull(latest.lastModified(), "GetObject must report the Last-Modified of the object.");
    // The header carries seconds, so the instant of the put is rounded down; it is never in the future.
    assertFalse(latest.lastModified().isBefore(beforePut));
    assertFalse(latest.lastModified().isAfter(Instant.now()));

    // The same object, so both operations report the same instant.
    HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    assertEquals(head.lastModified(), latest.lastModified());

    // A range request answers with 206 through the same path.
    GetObjectResponse ranged = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=0-1").build(),
        ResponseTransformer.toBytes()).response();
    assertEquals(latest.lastModified(), ranged.lastModified());

    // An older version reports when that version was written, not when the latest one was.
    GetObjectResponse older = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).versionId(firstVersion.versionId()).build(),
        ResponseTransformer.toBytes()).response();
    assertNotNull(older.lastModified());
    assertFalse(older.lastModified().isAfter(latest.lastModified()));
  }

  @Test
  @LocalS3
  void testGetObjectAttributes(S3Client s3) {
    String bucket = "object-attributes-bucket";
    String key = "attributes.txt";
    String content = "Text1";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString(content));

    GetObjectAttributesResponse attributes = s3.getObjectAttributes(GetObjectAttributesRequest.builder()
        .bucket(bucket)
        .key(key)
        .objectAttributes(ObjectAttributes.E_TAG, ObjectAttributes.OBJECT_SIZE, ObjectAttributes.STORAGE_CLASS)
        .build());

    assertEquals(DigestUtils.md5Hex(content), attributes.eTag());
    assertEquals(content.length(), attributes.objectSize());
    assertEquals(StorageClass.STANDARD, attributes.storageClass());
    assertEquals("null", attributes.versionId());
    assertNotNull(attributes.lastModified());

    GetObjectAttributesResponse etagOnly = s3.getObjectAttributes(GetObjectAttributesRequest.builder()
        .bucket(bucket)
        .key(key)
        .objectAttributes(ObjectAttributes.E_TAG)
        .build());
    assertEquals(DigestUtils.md5Hex(content), etagOnly.eTag());
  }

  @Test
  @LocalS3
  void listObjectVersions(S3Client s3) {
    String bucket = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    s3.putBucketVersioning(PutBucketVersioningRequest.builder()
        .bucket(bucket)
        .versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build())
        .build());
    PutObjectResponse putObjectResult1 = s3.putObject(PutObjectRequest.builder().bucket(bucket).key("dir1/key1").build(), RequestBody.fromString("Text1"));
    PutObjectResponse putObjectResult2 = s3.putObject(PutObjectRequest.builder().bucket(bucket).key("dir1/key1").build(), RequestBody.fromString("Text2"));
    DeleteObjectResponse deleteObjectResponse1 =
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key("dir1/key1").build());
    PutObjectResponse putObjectResult3 = s3.putObject(PutObjectRequest.builder().bucket(bucket).key("dir1/key1").build(), RequestBody.fromString("Text3"));

    ListObjectVersionsResponse versionListing1 = s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix("dir1/key1").build());
    assertEquals(3, versionListing1.versions().size());
    assertEquals(1, versionListing1.deleteMarkers().size());

    assertEquals(DigestUtils.md5Hex("Text3"), versionListing1.versions().get(0).eTag());
    assertTrue(versionListing1.versions().get(0).isLatest());
    assertEquals("dir1/key1", versionListing1.versions().get(0).key());
    assertEquals(5, versionListing1.versions().get(0).size());
    assertEquals(putObjectResult3.versionId(), versionListing1.versions().get(0).versionId());

    assertTrue(versionListing1.versions().get(0).isLatest());
    assertEquals("dir1/key1", versionListing1.versions().get(0).key());
    assertFalse(versionListing1.deleteMarkers().get(0).isLatest());

    assertEquals(DigestUtils.md5Hex("Text2"), versionListing1.versions().get(1).eTag());
    assertFalse(versionListing1.versions().get(1).isLatest());
    assertEquals("dir1/key1", versionListing1.versions().get(1).key());
    assertEquals(5, versionListing1.versions().get(1).size());
    assertEquals(putObjectResult2.versionId(), versionListing1.versions().get(1).versionId());

    assertEquals(DigestUtils.md5Hex("Text1"), versionListing1.versions().get(2).eTag());
    assertFalse(versionListing1.versions().get(2).isLatest());
    assertEquals("dir1/key1", versionListing1.versions().get(2).key());
    assertEquals(5, versionListing1.versions().get(2).size());
    assertEquals(putObjectResult1.versionId(), versionListing1.versions().get(2).versionId());

    assertEquals("", versionListing1.nextKeyMarker());
    assertTrue(StringUtils.isBlank(versionListing1.nextVersionIdMarker()));

    PutObjectResponse putObjectResult4 = s3.putObject(PutObjectRequest.builder().bucket(bucket).key("dir2/key1").build(), RequestBody.fromString("Text4"));
    PutObjectResponse putObjectResult5 = s3.putObject(PutObjectRequest.builder().bucket(bucket).key("dir2/key1").build(), RequestBody.fromString("Text5"));
    DeleteObjectResponse deleteObjectResponse2 =
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key("dir2/key1").build());
    PutObjectResponse putObjectResult6 = s3.putObject(PutObjectRequest.builder().bucket(bucket).key("dir2/key1").build(), RequestBody.fromString("Text6"));
    ListObjectVersionsResponse versionListing2 = s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).delimiter("/").maxKeys(1).build());
    assertEquals(0, versionListing2.versions().size());
    assertEquals(1, versionListing2.commonPrefixes().size());
    assertEquals("dir1/", versionListing2.commonPrefixes().get(0).prefix());
    assertEquals("dir1/key1", versionListing2.nextKeyMarker());
    assertTrue(StringUtils.isBlank(versionListing2.nextVersionIdMarker()));

    ListObjectVersionsResponse versionListing3 = s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix("dir2").delimiter("/").maxKeys(1).build());
    assertEquals(0, versionListing3.versions().size());
    assertEquals(1, versionListing3.commonPrefixes().size());
    assertEquals("dir2/", versionListing3.commonPrefixes().get(0).prefix());
    assertEquals("dir2/key1", versionListing3.nextKeyMarker());
    assertTrue(StringUtils.isBlank(versionListing3.nextVersionIdMarker()));

    ListObjectVersionsResponse versionListing4 = s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).delimiter("/").maxKeys(2).build());
    assertEquals(0, versionListing4.versions().size());
    assertEquals(2, versionListing4.commonPrefixes().size());
    assertEquals("dir1/", versionListing4.commonPrefixes().get(0).prefix());
    assertEquals("dir2/", versionListing4.commonPrefixes().get(1).prefix());

    List<ObjectVersion> allVersions = new ArrayList<>(6);
    List<DeleteMarkerEntry> deleteMarkers = new ArrayList<>(2);
    String nextKeyMarker = null;
    String nextVersionIdMarker = null;
    do {
      ListObjectVersionsResponse versionListing = s3.listObjectVersions(ListObjectVersionsRequest.builder()
          .bucket(bucket)
          .keyMarker(nextKeyMarker)
          .versionIdMarker(nextVersionIdMarker)
          .maxKeys(2).build());
      allVersions.addAll(versionListing.versions());
      deleteMarkers.addAll(versionListing.deleteMarkers());
      nextKeyMarker = versionListing.nextKeyMarker();
      nextVersionIdMarker = versionListing.nextVersionIdMarker();
    } while (Objects.nonNull(nextKeyMarker) && !"".equals(nextKeyMarker));

    assertEquals(6, allVersions.size());
    assertEquals(2, deleteMarkers.size());

    assertEquals("dir1/key1", allVersions.get(0).key());
    assertEquals(putObjectResult3.versionId(), allVersions.get(0).versionId());
    assertTrue(allVersions.get(0).isLatest());

    assertEquals("dir1/key1", allVersions.get(1).key());
    assertFalse(allVersions.get(1).isLatest());

    assertEquals("dir1/key1", deleteMarkers.get(0).key());
    assertEquals(deleteObjectResponse1.versionId(), deleteMarkers.get(0).versionId());
    assertFalse(deleteMarkers.get(0).isLatest());

    assertEquals("dir1/key1", allVersions.get(2).key());
    assertEquals(putObjectResult1.versionId(), allVersions.get(2).versionId());
    assertFalse(allVersions.get(2).isLatest());

    assertEquals("dir2/key1", allVersions.get(3).key());
    assertEquals(putObjectResult6.versionId(), allVersions.get(3).versionId());
    assertTrue(allVersions.get(3).isLatest());

    assertEquals("dir2/key1", allVersions.get(4).key());
    assertFalse(allVersions.get(4).isLatest());

    assertEquals("dir2/key1", deleteMarkers.get(1).key());
    assertEquals(deleteObjectResponse2.versionId(), deleteMarkers.get(1).versionId());
    assertFalse(deleteMarkers.get(1).isLatest());

    assertEquals("dir2/key1", allVersions.get(5).key());
    assertEquals(putObjectResult4.versionId(), allVersions.get(5).versionId());
    assertFalse(allVersions.get(5).isLatest());
  }

  @LocalS3
  @Test
  void testGetObjectWithByteRange(S3Client s3) throws IOException {
    String bucket = "range-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    // "Hello, World!" = 13 bytes (indices 0-12)
    String key = "data.txt";
    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
        RequestBody.fromString("Hello, World!"));

    // bytes=0-4  →  "Hello"
    ResponseBytes<GetObjectResponse> r1 = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=0-4").build(),
        ResponseTransformer.toBytes());
    assertEquals("Hello", r1.asUtf8String());
    assertEquals(5L, r1.response().contentLength());
    assertEquals("bytes 0-4/13", r1.response().contentRange());

    // bytes=7-11  →  "World"
    ResponseBytes<GetObjectResponse> r2 = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=7-11").build(),
        ResponseTransformer.toBytes());
    assertEquals("World", r2.asUtf8String());
    assertEquals(5L, r2.response().contentLength());
    assertEquals("bytes 7-11/13", r2.response().contentRange());

    // bytes=7-  →  "World!"
    ResponseBytes<GetObjectResponse> r3 = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=7-").build(),
        ResponseTransformer.toBytes());
    assertEquals("World!", r3.asUtf8String());
    assertEquals(6L, r3.response().contentLength());
    assertEquals("bytes 7-12/13", r3.response().contentRange());

    // bytes=-6  →  last 6 bytes "World!"
    ResponseBytes<GetObjectResponse> r4 = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=-6").build(),
        ResponseTransformer.toBytes());
    assertEquals("World!", r4.asUtf8String());
    assertEquals(6L, r4.response().contentLength());
    assertEquals("bytes 7-12/13", r4.response().contentRange());

    // No Range header  →  full object, HTTP 200
    ResponseBytes<GetObjectResponse> full = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).build(),
        ResponseTransformer.toBytes());
    assertEquals("Hello, World!", full.asUtf8String());
    assertEquals(13L, full.response().contentLength());

    // Range start beyond object size  →  416 InvalidRange
    assertThrows(Exception.class, () -> s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=100-200").build(),
        ResponseTransformer.toBytes()));
  }

  @LocalS3
  @Test
  void testDeleteObjects(S3Client s3) {
    String bucketName = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    s3.putBucketVersioning(PutBucketVersioningRequest.builder()
        .bucket(bucketName)
        .versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.SUSPENDED).build())
        .build());

    DeleteObjectsRequest deleteObjectsRequest = DeleteObjectsRequest.builder()
        .bucket(bucketName)
        .delete(Delete.builder().objects(
            ObjectIdentifier.builder().key("a.txt").build(),
            ObjectIdentifier.builder().key("b.txt").versionId("123").build()).build())
        .build();

    DeleteObjectsResponse deleteObjectsResponse = s3.deleteObjects(deleteObjectsRequest);
    assertEquals(1, deleteObjectsResponse.deleted().size());


    s3.putObject(PutObjectRequest.builder().bucket(bucketName).key("a.txt").build(), RequestBody.fromString("Hello"));
    s3.putObject(PutObjectRequest.builder().bucket(bucketName).key("b.txt").build(), RequestBody.fromString("World"));
    DeleteObjectsRequest deleteObjectsRequest1 = DeleteObjectsRequest.builder()
        .bucket(bucketName)
        .delete(Delete.builder().objects(
            ObjectIdentifier.builder().key("a.txt").build(),
            ObjectIdentifier.builder().key("b.txt").build()).build())
        .build();
    DeleteObjectsResponse deleteObjectsResult1 = s3.deleteObjects(deleteObjectsRequest1);
    assertEquals(2, deleteObjectsResult1.deleted().size());

    ListObjectVersionsResponse versionListing1 = s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucketName).prefix("a.txt").build());
    assertEquals(1, versionListing1.deleteMarkers().size());
    assertTrue(versionListing1.deleteMarkers().get(0).isLatest());

    ListObjectVersionsResponse versionListing2 = s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucketName).prefix("b.txt").build());
    assertEquals(1, versionListing2.deleteMarkers().size());
    assertTrue(versionListing2.deleteMarkers().get(0).isLatest());

    DeleteObjectsRequest deleteObjectsRequest2 = DeleteObjectsRequest.builder()
        .bucket(bucketName)
        .delete(Delete.builder().objects(
            ObjectIdentifier.builder().key("a.txt").versionId("null").build(),
            ObjectIdentifier.builder().key("b.txt").versionId("null").build()).build())
        .build();
    DeleteObjectsResponse deleteObjectsResult2 = s3.deleteObjects(deleteObjectsRequest2);
    assertEquals(2, deleteObjectsResult2.deleted().size());
    assertTrue(deleteObjectsResult2.deleted().get(0).deleteMarker());
    assertTrue(deleteObjectsResult2.deleted().get(1).deleteMarker());

    ListObjectVersionsResponse versionListing3 = s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucketName).prefix("a.txt").build());
    assertEquals(0, versionListing3.versions().size());
    ListObjectVersionsResponse versionListing4 = s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucketName).prefix("b.txt").build());
    assertEquals(0, versionListing4.versions().size());
  }

  @Test
  @LocalS3
  void testPutAndGetLargeObjectInMemory(S3Client s3) {
    assertLargeObjectRoundTrip(s3);
  }

  @Test
  @LocalS3(mode = LocalS3Mode.PERSISTENCE, dataPathSupplier = TempDataPathSupplier.class)
  void testPutAndGetLargeObjectPersisted(S3Client s3) {
    assertLargeObjectRoundTrip(s3);
  }

  private static void assertLargeObjectRoundTrip(S3Client s3) {
    String bucket = "large-object-bucket";
    String key = "large.bin";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    byte[] data = new byte[20 * 1024 * 1024 + 123];
    new Random(42).nextBytes(data);

    PutObjectResponse putResponse = s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
        RequestBody.fromBytes(data));
    assertEquals(DigestUtils.md5Hex(data), putResponse.eTag());

    ResponseBytes<GetObjectResponse> object = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).build(), ResponseTransformer.toBytes());
    assertEquals(DigestUtils.md5Hex(data), object.response().eTag());
    assertArrayEquals(data, object.asByteArray());

    int start = 10 * 1024 * 1024 + 7;
    int end = start + 3 * 1024 * 1024;
    ResponseBytes<GetObjectResponse> range = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=" + start + "-" + end).build(),
        ResponseTransformer.toBytes());
    assertEquals("bytes " + start + "-" + end + "/" + data.length, range.response().contentRange());
    assertArrayEquals(Arrays.copyOfRange(data, start, end + 1), range.asByteArray());
  }

  public static class TempDataPathSupplier implements DataPathSupplier {
    @Override
    public String get() {
      try {
        return Files.createTempDirectory("local-s3-large-object").toAbsolutePath().toString();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

}
