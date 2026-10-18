package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.ListMultipartUploadsAns;
import com.robothy.s3.core.model.internal.UploadMetadata;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import java.util.NavigableMap;
import java.util.TreeMap;
import com.robothy.s3.core.model.answers.ListMultipartUploadsAns.UploadItem;
import com.robothy.s3.core.util.S3ObjectUtils;
import java.util.Arrays;
import java.util.List;

class ListMultipartUploadsServiceTest {

  @Test
  void testEncodeResultIfNeeded() {
    ListMultipartUploadsAns result = ListMultipartUploadsAns.builder()
        .prefix("test/prefix")
        .delimiter("/")
        .keyMarker("keyMarker")
        .nextKeyMarker("nextKeyMarker")
        .uploadIdMarker("uploadIdMarker")
        .nextUploadIdMarker("nextUploadIdMarker")
        .uploads(Collections.singletonList(UploadItem.builder().key("key1").build()))
        .commonPrefixes(Collections.singletonList("prefix1"))
        .build();

    ListMultipartUploadsAns encodedResult = ListMultipartUploadsService.encodeResultIfNeeded(result, "url");

    assertEquals("test/prefix", encodedResult.getPrefix());
    assertEquals("/", encodedResult.getDelimiter());
    assertEquals("keyMarker", encodedResult.getKeyMarker());
    assertEquals("nextKeyMarker", encodedResult.getNextKeyMarker());
    assertEquals("uploadIdMarker", encodedResult.getUploadIdMarker());
    assertEquals("nextUploadIdMarker", encodedResult.getNextUploadIdMarker());
  }

  @Test
  void testEncodeResultIfNeededWithInvalidEncodingType() {
    ListMultipartUploadsAns result = ListMultipartUploadsAns.builder().build();

    assertThrows(LocalS3InvalidArgumentException.class, () -> {
      ListMultipartUploadsService.encodeResultIfNeeded(result, "invalid");
    });
  }

  @Test
  void testListMultipartUploads() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    NavigableMap<String, UploadMetadata> uploadMap = new TreeMap<>();
    uploadMap.put("uploadId1", new UploadMetadata());
    uploads.put("key1", uploadMap);

    ListMultipartUploadsAns result = ListMultipartUploadsService.listMultipartUploads(uploads, "/", null, 1, null, null);

    assertEquals(1, result.getUploads().size());
    assertEquals("key1", result.getUploads().get(0).getKey());
    assertEquals("uploadId1", result.getUploads().get(0).getUploadId());
  }

  @Test
  void testListMultipartUploadsWithDelimiter() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    NavigableMap<String, UploadMetadata> uploadMap = new TreeMap<>();
    uploadMap.put("uploadId1", new UploadMetadata());
    uploads.put("key1/test", uploadMap);

    ListMultipartUploadsAns result = ListMultipartUploadsService.listMultipartUploads(uploads, "/", null, 1, null, null);

    assertEquals(1, result.getCommonPrefixes().size());
    assertEquals("key1/", result.getCommonPrefixes().get(0));
  }

  @Test
  void testListMultipartUploadsWithUploadIdMarker() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    NavigableMap<String, UploadMetadata> uploadMap = new TreeMap<>();
    uploadMap.put("uploadId1", new UploadMetadata());
    uploadMap.put("uploadId2", new UploadMetadata());
    uploads.put("key1", uploadMap);

    ListMultipartUploadsAns result =
        ListMultipartUploadsService.listMultipartUploads(uploads, null, "key1", 1, null, "uploadId1");

    assertEquals(1, result.getUploads().size());
    assertEquals("key1", result.getUploads().get(0).getKey());
    assertEquals("uploadId2", result.getUploads().get(0).getUploadId());
  }


  @Test
  void testListMultipartUploadsWithMaxUploads() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    NavigableMap<String, UploadMetadata> uploadMetadataMap1 = new TreeMap<>();
    uploadMetadataMap1.put("uploadId1", UploadMetadata.builder().createDate(123456789L).contentType("contentType").build());
    uploads.put("key1", uploadMetadataMap1);

    NavigableMap<String, UploadMetadata> uploadMetadataMap2 = new TreeMap<>();
    uploadMetadataMap2.put("uploadId2", UploadMetadata.builder().createDate(987654321L).contentType("contentType").build());
    uploads.put("key2", uploadMetadataMap2);

    ListMultipartUploadsAns result = ListMultipartUploadsService.listMultipartUploads(uploads, null, null, 1, null, null);

    assertEquals(1, result.getUploads().size());
    assertTrue(result.isTruncated());
    assertEquals("key1", result.getNextKeyMarker());
    assertEquals("uploadId1", result.getNextUploadIdMarker());
  }

  @Test
  void testEncodeResultIfNeeded_NullEncodingType() {
    ListMultipartUploadsAns result = ListMultipartUploadsAns.builder()
        .uploads(Arrays.asList(UploadItem.builder().key("key1").build()))
        .commonPrefixes(Arrays.asList("prefix1"))
        .delimiter("delimiter")
        .prefix("prefix")
        .keyMarker("keyMarker")
        .nextKeyMarker("nextKeyMarker")
        .uploadIdMarker("uploadIdMarker")
        .nextUploadIdMarker("nextUploadIdMarker")
        .build();

    ListMultipartUploadsAns encodedResult = ListMultipartUploadsService.encodeResultIfNeeded(result, null);

    assertEquals(result, encodedResult);
  }

  @Test
  void testEncodeResultIfNeeded_InvalidEncodingType() {
    ListMultipartUploadsAns result = ListMultipartUploadsAns.builder().build();

    assertThrows(LocalS3InvalidArgumentException.class, () -> {
      ListMultipartUploadsService.encodeResultIfNeeded(result, "invalid");
    });
  }

  @Test
  void testEncodeResultIfNeeded_UrlEncodingType() {
    ListMultipartUploadsAns result = ListMultipartUploadsAns.builder()
        .uploads(Arrays.asList(UploadItem.builder().key("key1").build()))
        .commonPrefixes(Arrays.asList("prefix1"))
        .delimiter("delimiter")
        .prefix("prefix")
        .keyMarker("keyMarker")
        .nextKeyMarker("nextKeyMarker")
        .uploadIdMarker("uploadIdMarker")
        .nextUploadIdMarker("nextUploadIdMarker")
        .build();

    ListMultipartUploadsAns encodedResult = ListMultipartUploadsService.encodeResultIfNeeded(result, "url");

    assertEquals("url", encodedResult.getEncodingType());
    assertEquals(S3ObjectUtils.urlEncodeEscapeSlash("key1"), encodedResult.getUploads().get(0).getKey());
    assertEquals(S3ObjectUtils.urlEncodeEscapeSlash("prefix1"), encodedResult.getCommonPrefixes().get(0));
    assertEquals(S3ObjectUtils.urlEncodeEscapeSlash("delimiter"), encodedResult.getDelimiter());
    assertEquals(S3ObjectUtils.urlEncodeEscapeSlash("prefix"), encodedResult.getPrefix());
    assertEquals(S3ObjectUtils.urlEncodeEscapeSlash("keyMarker"), encodedResult.getKeyMarker());
    assertEquals(S3ObjectUtils.urlEncodeEscapeSlash("nextKeyMarker"), encodedResult.getNextKeyMarker());
    assertEquals(S3ObjectUtils.urlEncodeEscapeSlash("uploadIdMarker"), encodedResult.getUploadIdMarker());
    assertEquals(S3ObjectUtils.urlEncodeEscapeSlash("nextUploadIdMarker"), encodedResult.getNextUploadIdMarker());
  }

  /**
   * The keys that a common prefix rolls up are skipped rather than visited, and the prefix is listed once.
   */
  @Test
  void testListMultipartUploadsSkipsTheKeysOfACommonPrefix() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    int[] visits = new int[1];
    uploads.put("a", uploadsOf("uploadA"));
    for (int i = 0; i < 10_000; i++) {
      uploads.put("logs/" + i, new TreeMap<>(uploadsOf("upload" + i)) {
        @Override
        public boolean isEmpty() {
          visits[0]++;
          return super.isEmpty();
        }
      });
    }
    uploads.put("logs/\uFFFF\uFFFF", uploadsOf("uploadMax"));
    uploads.put("z", uploadsOf("uploadZ"));

    ListMultipartUploadsAns all = ListMultipartUploadsService.listMultipartUploads(uploads, "/", null, 10, null, null);
    assertEquals(List.of("a", "z"), all.getUploads().stream().map(UploadItem::getKey).toList());
    assertEquals(List.of("logs/"), all.getCommonPrefixes());
    assertFalse(all.isTruncated());

    ListMultipartUploadsAns firstPage = ListMultipartUploadsService.listMultipartUploads(uploads, "/", null, 2, null, null);
    assertEquals(List.of("logs/"), firstPage.getCommonPrefixes());
    assertTrue(firstPage.isTruncated());
    assertEquals("logs/", firstPage.getNextKeyMarker());

    ListMultipartUploadsAns secondPage =
        ListMultipartUploadsService.listMultipartUploads(uploads, "/", "logs/", 2, null, null);
    assertEquals(List.of("z"), secondPage.getUploads().stream().map(UploadItem::getKey).toList());
    assertTrue(secondPage.getCommonPrefixes().isEmpty());
    assertEquals(0, visits[0], "The uploads of the keys under the common prefix aren't visited.");
  }

  /**
   * The uploads of a key that a page cuts are listed by the next page, from the (key-marker, upload-id-marker) cursor.
   */
  @Test
  void testListMultipartUploadsPagesThroughTheUploadsOfAKey() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    uploads.put("a", uploadsOf("1", "2", "3"));
    uploads.put("b", uploadsOf("4", "5", "6"));

    List<String> listed = new java.util.ArrayList<>();
    String keyMarker = null;
    String uploadIdMarker = null;
    int pages = 0;
    ListMultipartUploadsAns page;
    do {
      page = ListMultipartUploadsService.listMultipartUploads(uploads, null, keyMarker, 2, null, uploadIdMarker);
      page.getUploads().forEach(upload -> listed.add(upload.getKey() + upload.getUploadId()));
      keyMarker = page.getNextKeyMarker();
      uploadIdMarker = page.getNextUploadIdMarker();
      pages++;
    } while (page.isTruncated());

    assertEquals(List.of("a1", "a2", "a3", "b4", "b5", "b6"), listed);
    assertEquals(3, pages);
  }

  @Test
  void testListMultipartUploadsAfterTheUploadIdMarkerOfTheKeyMarkerOnly() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    uploads.put("a", uploadsOf("5", "7"));
    // Started before the uploads of the key marker, so its IDs sort before the upload-id marker.
    uploads.put("b", uploadsOf("1", "2"));

    ListMultipartUploadsAns result =
        ListMultipartUploadsService.listMultipartUploads(uploads, null, "a", 10, null, "5");

    assertEquals(List.of("a7", "b1", "b2"), keysAndIds(result));
    assertFalse(result.isTruncated());
    assertEquals("a", result.getKeyMarker());
    assertEquals("5", result.getUploadIdMarker());
  }

  @Test
  void testListMultipartUploadsAfterAKeyMarkerWithoutUploadIdMarker() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    uploads.put("a", uploadsOf("1", "2"));
    uploads.put("b", uploadsOf("3"));

    assertEquals(List.of("b3"), keysAndIds(ListMultipartUploadsService.listMultipartUploads(uploads, null, "a", 10,
        null, null)));
    // Amazon S3 ignores an upload-id marker without a key marker.
    assertEquals(List.of("a1", "a2", "b3"), keysAndIds(ListMultipartUploadsService.listMultipartUploads(uploads, null,
        null, 10, null, "1")));
  }

  @Test
  void testListMultipartUploadsRollsUpTheKeysAfterThePrefix() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    uploads.put("dir/x/1", uploadsOf("1"));
    uploads.put("dir/x/2", uploadsOf("2"));
    uploads.put("dir/y", uploadsOf("3"));
    uploads.put("other/z", uploadsOf("4"));

    ListMultipartUploadsAns result =
        ListMultipartUploadsService.listMultipartUploads(uploads, "/", null, 10, "dir/", null);

    assertEquals(List.of("dir/y3"), keysAndIds(result));
    assertEquals(List.of("dir/x/"), result.getCommonPrefixes());
    assertEquals("dir/", result.getPrefix());
    assertEquals("/", result.getDelimiter());
    assertFalse(result.isTruncated());

    ListMultipartUploadsAns firstPage =
        ListMultipartUploadsService.listMultipartUploads(uploads, "/", null, 1, "dir/", null);
    assertEquals(List.of("dir/x/"), firstPage.getCommonPrefixes());
    assertTrue(firstPage.isTruncated());
    assertEquals("dir/x/", firstPage.getNextKeyMarker());
    assertNull(firstPage.getNextUploadIdMarker());

    ListMultipartUploadsAns secondPage = ListMultipartUploadsService.listMultipartUploads(uploads, "/",
        firstPage.getNextKeyMarker(), 1, "dir/", firstPage.getNextUploadIdMarker());
    assertEquals(List.of("dir/y3"), keysAndIds(secondPage));
    assertTrue(secondPage.getCommonPrefixes().isEmpty());
    assertFalse(secondPage.isTruncated());
  }

  @Test
  void testListMultipartUploadsEndingWithTheLastUploadIsNotTruncated() {
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new TreeMap<>();
    uploads.put("a", uploadsOf("1", "2"));

    ListMultipartUploadsAns result = ListMultipartUploadsService.listMultipartUploads(uploads, null, null, 2, null, null);

    assertEquals(List.of("a1", "a2"), keysAndIds(result));
    assertFalse(result.isTruncated());
    assertNull(result.getNextKeyMarker());
    assertNull(result.getNextUploadIdMarker());
  }

  private static List<String> keysAndIds(ListMultipartUploadsAns result) {
    return result.getUploads().stream().map(upload -> upload.getKey() + upload.getUploadId()).toList();
  }

  private static NavigableMap<String, UploadMetadata> uploadsOf(String... uploadIds) {
    NavigableMap<String, UploadMetadata> uploads = new TreeMap<>();
    for (String uploadId : uploadIds) {
      uploads.put(uploadId, UploadMetadata.builder().createDate(1L).build());
    }
    return uploads;
  }

}
