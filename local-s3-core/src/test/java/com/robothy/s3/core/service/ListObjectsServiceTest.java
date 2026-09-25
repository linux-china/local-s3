package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.ListObjectsAns;
import com.robothy.s3.core.model.answers.ListObjectsV2Ans;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.datatypes.response.S3Object;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

import com.robothy.s3.core.Digests;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ListObjectsServiceTest extends LocalS3ServiceTestBase {

  @MethodSource("localS3Services")
  @ParameterizedTest
  void list(BucketService bucketService, ObjectService objectService) {

    String bucketName = "my-bucket";
    bucketService.createBucket(bucketName);
    String key1 = "dir1/key1";
    String key2 = "dir1/key2";
    String key3 = "dir2/key3";
    String key4 = "dir2/key4";

    ByteArrayInputStream content = new ByteArrayInputStream("Hello".getBytes());
    PutObjectOptions putObjectOptions = PutObjectOptions.builder().content(content)
        .contentType("plain/text")
        .size(5).build();
    objectService.putObject(bucketName, key1, putObjectOptions);
    content.reset();
    objectService.putObject(bucketName, key2, putObjectOptions);
    objectService.deleteObject(bucketName, key3);
    content.reset();
    objectService.putObject(bucketName, key4, putObjectOptions);

    ListObjectsAns listObjectsAns = objectService.listObjects(bucketName, null, null, null, 2, null);
    assertEquals(2, listObjectsAns.getObjects().size());

    S3Object dir1Key1 = listObjectsAns.getObjects().get(0);
    assertEquals("\"" + Digests.md5Hex("Hello") + "\"", dir1Key1.getEtag());
    assertEquals("dir1/key1", dir1Key1.getKey());
    assertEquals(5, dir1Key1.getSize());

    S3Object dir1Key2 = listObjectsAns.getObjects().get(1);
    assertEquals("\"" + Digests.md5Hex("Hello") + "\"", dir1Key2.getEtag());
    assertEquals("dir1/key2", dir1Key2.getKey());
    assertEquals(5, dir1Key2.getSize());

    assertEquals(0, listObjectsAns.getCommonPrefixes().size());
    assertTrue(listObjectsAns.getNextMarker().isPresent());
    assertEquals("dir1/key2", listObjectsAns.getNextMarker().get());

    ListObjectsAns listObjectsAns1 = objectService.listObjects(bucketName, null, null, "dir1/key2", 2, null);
    assertEquals(1, listObjectsAns1.getObjects().size());
    assertEquals(0, listObjectsAns1.getCommonPrefixes().size());
    assertTrue(listObjectsAns1.getNextMarker().isEmpty());

    ListObjectsAns listObjectsAns2 = objectService.listObjects(bucketName, "/", null, null, 1, null);
    assertEquals(0, listObjectsAns2.getObjects().size());
    assertEquals(1, listObjectsAns2.getCommonPrefixes().size());
    assertEquals("dir1/", listObjectsAns2.getCommonPrefixes().get(0));
    assertTrue(listObjectsAns2.getNextMarker().isPresent());
    assertEquals("dir1/", listObjectsAns2.getNextMarker().get());

    ListObjectsAns listObjectsAns3 = objectService.listObjects(bucketName, "/", null, null, 5, null);
    assertEquals(0, listObjectsAns3.getObjects().size());
    assertEquals(2, listObjectsAns3.getCommonPrefixes().size());
    assertEquals("dir1/", listObjectsAns3.getCommonPrefixes().get(0));
    assertEquals("dir2/", listObjectsAns3.getCommonPrefixes().get(1));
    assertTrue(listObjectsAns3.getNextMarker().isEmpty());

    ListObjectsAns listObjectsAns4 = objectService.listObjects(bucketName, "/", null, null, 5, "dir1");
    assertEquals(0, listObjectsAns4.getObjects().size());
    assertEquals(1, listObjectsAns4.getCommonPrefixes().size());
    assertEquals("dir1/", listObjectsAns4.getCommonPrefixes().get(0));
    assertTrue(listObjectsAns4.getNextMarker().isEmpty());
  }

  @MethodSource("localS3Services")
  @ParameterizedTest
  void listObjectsWithUrlEncoding(BucketService bucketService, ObjectService objectService) {
    String bucketName = "my-bucket";
    bucketService.createBucket(bucketName);
    String key1 = "dir1@/key1@";
    objectService.putObject(bucketName, key1, PutObjectOptions.builder()
        .content(new ByteArrayInputStream("Hello".getBytes()))
        .contentType("plain/text")
        .build());

    assertThrows(LocalS3InvalidArgumentException.class, () ->
        objectService.listObjects(bucketName, null, "invalid", null, 2, null));

    ListObjectsAns listObjectsAns = objectService.listObjects(bucketName, null, "url", null, 2, null);
    assertEquals(1, listObjectsAns.getObjects().size());
    assertEquals("dir1%40/key1%40", listObjectsAns.getObjects().get(0).getKey());

    ListObjectsAns listObjectsAns1 = objectService.listObjects(bucketName, null, null, null, 2, null);
    assertEquals(1, listObjectsAns1.getObjects().size());
    assertEquals("dir1@/key1@", listObjectsAns1.getObjects().get(0).getKey());

    ListObjectsAns listObjectsAns2 = objectService.listObjects(bucketName, "/", "url", null, 2, null);
    assertEquals(0, listObjectsAns2.getObjects().size());
    assertEquals(1, listObjectsAns2.getCommonPrefixes().size());
    assertEquals("dir1%40/", listObjectsAns2.getCommonPrefixes().get(0));

    // Like Amazon S3, ListObjects (v1) encodes the keys and common prefixes, but not the Prefix itself.
    ListObjectsAns listObjectsAns3 = objectService.listObjects(bucketName, "/", "url", null, 2, "dir1@");
    assertEquals("dir1@", listObjectsAns3.getPrefix());
    assertEquals("dir1%40/", listObjectsAns3.getCommonPrefixes().get(0));
  }



  /**
   * The keys that a common prefix rolls up aren't visited: listing the root of a bucket with a million keys under
   * {@code logs/} takes a few steps per page, not a million.
   */
  @org.junit.jupiter.api.Test
  void skipsTheKeysOfACommonPrefix() {
    int[] visits = new int[1];
    NavigableMap<String, ObjectMetadataRef> objects = new ConcurrentSkipListMap<>();
    objects.put("a.txt", countingObject(visits, false));
    // Only deleted objects: the prefix isn't listed.
    objects.put("empty/1", countingObject(visits, true));
    objects.put("empty/2", countingObject(visits, true));
    for (int i = 0; i < 100_000; i++) {
      objects.put("logs/" + i, countingObject(visits, false));
    }
    objects.put("logs/\uFFFF\uFFFF", countingObject(visits, false));
    objects.put("logs0", countingObject(visits, false));
    objects.put("z/1", countingObject(visits, false));
    objects.put("z/2", countingObject(visits, false));

    ListObjectsAns all = ListObjectsService.listObjectsAndCommonPrefixes(objects, "", "/", 10);
    assertEquals(List.of("a.txt", "logs0"), all.getObjects().stream().map(S3Object::getKey).toList());
    assertEquals(List.of("logs/", "z/"), all.getCommonPrefixes());
    assertTrue(all.getNextMarker().isEmpty());
    assertTrue(visits[0] < 10, "Visited " + visits[0] + " objects.");

    visits[0] = 0;
    ListObjectsAns firstPage = ListObjectsService.listObjectsAndCommonPrefixes(objects, "", "/", 2);
    assertEquals(List.of("logs/"), firstPage.getCommonPrefixes());
    assertEquals("logs/", firstPage.getNextMarker().orElseThrow());
    assertTrue(visits[0] < 10, "Visited " + visits[0] + " objects.");

    visits[0] = 0;
    ListObjectsAns secondPage = ListObjectsService.listObjectsAndCommonPrefixes(
        ListItemUtils.filterByKeyMarkerAndDelimiterForListObjects(objects, "logs/", "", "/"), "", "/", 2);
    assertEquals(List.of("logs0"), secondPage.getObjects().stream().map(S3Object::getKey).toList());
    assertEquals(List.of("z/"), secondPage.getCommonPrefixes());
    assertTrue(secondPage.getNextMarker().isEmpty());
    assertTrue(visits[0] < 10, "Visited " + visits[0] + " objects.");
  }

  /**
   * A full page followed only by deleted objects is the last page, whether it ends at an object or a common prefix.
   */
  @org.junit.jupiter.api.Test
  void notTruncatedWhenOnlyDeletedObjectsAreLeft() {
    int[] visits = new int[1];
    NavigableMap<String, ObjectMetadataRef> objects = new ConcurrentSkipListMap<>();
    objects.put("a.txt", countingObject(visits, false));
    objects.put("b.txt", countingObject(visits, false));
    objects.put("c.txt", countingObject(visits, true));
    objects.put("d/1", countingObject(visits, true));

    ListObjectsAns endsAtObject = ListObjectsService.listObjectsAndCommonPrefixes(objects, "", "/", 2);
    assertEquals(List.of("a.txt", "b.txt"), endsAtObject.getObjects().stream().map(S3Object::getKey).toList());
    assertTrue(endsAtObject.getNextMarker().isEmpty());
    assertFalse(endsAtObject.isTruncated());

    objects.put("e.txt", countingObject(visits, false));
    ListObjectsAns truncated = ListObjectsService.listObjectsAndCommonPrefixes(objects, "", "/", 2);
    assertEquals("b.txt", truncated.getNextMarker().orElseThrow());
    assertTrue(truncated.isTruncated());

    objects.clear();
    objects.put("a/1", countingObject(visits, false));
    objects.put("a/2", countingObject(visits, false));
    objects.put("b.txt", countingObject(visits, true));
    ListObjectsAns endsAtPrefix = ListObjectsService.listObjectsAndCommonPrefixes(objects, "", "/", 1);
    assertEquals(List.of("a/"), endsAtPrefix.getCommonPrefixes());
    assertTrue(endsAtPrefix.getNextMarker().isEmpty());
    assertFalse(endsAtPrefix.isTruncated());
  }

  @MethodSource("localS3Services")
  @ParameterizedTest
  void notTruncatedWhenOnlyDeleteMarkersAreLeft(BucketService bucketService, ObjectService objectService) {
    String bucketName = "trailing-delete-markers";
    bucketService.createBucket(bucketName);
    ByteArrayInputStream content = new ByteArrayInputStream("Hello".getBytes());
    PutObjectOptions putObjectOptions = PutObjectOptions.builder().content(content).size(5).build();
    for (String key : List.of("a", "b", "c", "d")) {
      content.reset();
      objectService.putObject(bucketName, key, putObjectOptions);
    }
    objectService.deleteObject(bucketName, "c");
    objectService.deleteObject(bucketName, "d");

    ListObjectsAns v1 = objectService.listObjects(bucketName, null, null, null, 2, null);
    assertEquals(List.of("a", "b"), v1.getObjects().stream().map(S3Object::getKey).toList());
    assertFalse(v1.isTruncated());

    ListObjectsV2Ans v2 = objectService.listObjectsV2(bucketName, null, null, null, false, 2, null, null);
    assertEquals(List.of("a", "b"), v2.getObjects().stream().map(S3Object::getKey).toList());
    assertFalse(v2.isTruncated());
    assertTrue(v2.getNextContinuationToken().isEmpty());
  }

  private static ObjectMetadataRef countingObject(int[] visits, boolean deleted) {
    VersionedObjectMetadata version = new VersionedObjectMetadata();
    version.setDeleted(deleted);
    return ObjectMetadataRef.of(new ObjectMetadata(ObjectMetadata.NULL_VERSION, version) {
      @Override
      public VersionedObjectMetadata getLatest() {
        visits[0]++;
        return super.getLatest();
      }
    });
  }

}
