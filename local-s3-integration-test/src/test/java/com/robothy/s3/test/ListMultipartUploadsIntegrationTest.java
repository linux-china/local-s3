package com.robothy.s3.test;

import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;

public class ListMultipartUploadsIntegrationTest {

  @LocalS3
  @Test
  void testListMultipartUploads(S3Client s3Client) {
    String bucketName = "test-bucket-" + UUID.randomUUID();
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    // Create multiple multipart uploads
    String key1 = "test-key-1";
    String key2 = "test-key-2";
    
    CreateMultipartUploadRequest request1 = CreateMultipartUploadRequest.builder()
        .bucket(bucketName)
        .key(key1)
        .build();
    CreateMultipartUploadRequest request2 = CreateMultipartUploadRequest.builder()
        .bucket(bucketName)
        .key(key2)
        .build();

    String uploadId1 = s3Client.createMultipartUpload(request1).uploadId();
    String uploadId2 = s3Client.createMultipartUpload(request2).uploadId();

    // List multipart uploads
    ListMultipartUploadsRequest listRequest = ListMultipartUploadsRequest.builder()
        .bucket(bucketName)
        .build();
    ListMultipartUploadsResponse response = s3Client.listMultipartUploads(listRequest);

    // Verify results
    assertEquals(2, response.uploads().size());
    assertTrue(response.uploads().stream()
        .anyMatch(upload -> upload.key().equals(key1) && upload.uploadId().equals(uploadId1)));
    assertTrue(response.uploads().stream()
        .anyMatch(upload -> upload.key().equals(key2) && upload.uploadId().equals(uploadId2)));
  }

  @LocalS3
  @Test
  void testEmptyBucketListMultipartUploads(S3Client s3Client) {
    String bucketName = "test-bucket-" + UUID.randomUUID();
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    ListMultipartUploadsRequest listRequest = ListMultipartUploadsRequest.builder()
        .bucket(bucketName)
        .build();
    ListMultipartUploadsResponse response = s3Client.listMultipartUploads(listRequest);

    assertTrue(response.uploads().isEmpty());
  }

  @LocalS3
  @Test
  void testListMultipartUploadsWithMaxKeys(S3Client s3Client) {
    String bucketName = "test-bucket-" + UUID.randomUUID();
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    // Create 3 multipart uploads
    String[] keys = {"key1", "key2", "key3"};
    String[] uploadIds = new String[3];
    
    for (int i = 0; i < keys.length; i++) {
      CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
          .bucket(bucketName)
          .key(keys[i])
          .build();
      uploadIds[i] = s3Client.createMultipartUpload(request).uploadId();
    }

    // List with maxKeys=2
    ListMultipartUploadsRequest listRequest = ListMultipartUploadsRequest.builder()
        .bucket(bucketName)
        .maxUploads(2)
        .build();
    ListMultipartUploadsResponse response = s3Client.listMultipartUploads(listRequest);

    assertEquals(2, response.uploads().size());
    assertTrue(response.isTruncated());
  }

  @LocalS3
  @Test
  void testListMultipartUploadsWithPrefix(S3Client s3Client) {
    String bucketName = "test-bucket-" + UUID.randomUUID();
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    // Create uploads with different prefixes
    String[] keys = {"foo/key1", "foo/key2", "bar/key3"};
    String[] uploadIds = new String[3];
    
    for (int i = 0; i < keys.length; i++) {
      CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
          .bucket(bucketName)
          .key(keys[i])
          .build();
      uploadIds[i] = s3Client.createMultipartUpload(request).uploadId();
    }

    // List with prefix "foo/"
    ListMultipartUploadsRequest listRequest = ListMultipartUploadsRequest.builder()
        .bucket(bucketName)
        .prefix("foo/")
        .build();
    ListMultipartUploadsResponse response = s3Client.listMultipartUploads(listRequest);

    assertEquals(2, response.uploads().size());
    assertTrue(response.uploads().stream().allMatch(upload -> upload.key().startsWith("foo/")));
  }

  @LocalS3
  @Test
  void testNonExistentBucket(S3Client s3Client) {
    String bucketName = "non-existent-" + UUID.randomUUID();

    S3Exception exception = assertThrows(S3Exception.class, () ->
        s3Client.listMultipartUploads(ListMultipartUploadsRequest.builder()
            .bucket(bucketName)
            .build())
    );
    assertEquals("NoSuchBucket", exception.awsErrorDetails().errorCode());
  }

  @LocalS3
  @Test
  void testListMultipartUploadsWithDelimiter(S3Client s3Client) {
    String bucketName = "test-bucket-" + UUID.randomUUID();
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    // Create uploads with a common prefix
    String[] keys = {"foo/bar1", "foo/bar2", "foo/baz/bar3"};
    String[] uploadIds = new String[3];
    for (int i = 0; i < keys.length; i++) {
      CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
          .bucket(bucketName)
          .key(keys[i])
          .build();
      uploadIds[i] = s3Client.createMultipartUpload(request).uploadId();
    }

    // List with delimiter "/"
    ListMultipartUploadsRequest listRequest = ListMultipartUploadsRequest.builder()
        .bucket(bucketName)
        .delimiter("/")
        .build();
    ListMultipartUploadsResponse response = s3Client.listMultipartUploads(listRequest);

    // Verify that "foo/" is a common prefix
    assertTrue(response.commonPrefixes().stream()
        .anyMatch(prefix -> prefix.prefix().equals("foo/")));
  }

  /**
   * The paginator follows NextKeyMarker and NextUploadIdMarker, so a page that cuts the uploads of a key loses none.
   */
  @LocalS3
  @Test
  void paginatesThroughSeveralUploadsOfTheSameKey(S3Client s3Client) {
    String bucketName = "test-bucket-" + UUID.randomUUID();
    s3Client.createBucket(b -> b.bucket(bucketName));
    List<String> keys = List.of("a", "a", "a", "b", "b", "b", "dir/x/1", "dir/y");
    List<String> created = keys.stream()
        .map(key -> key + " " + s3Client.createMultipartUpload(b -> b.bucket(bucketName).key(key)).uploadId())
        .toList();

    List<String> listed = s3Client.listMultipartUploadsPaginator(b -> b.bucket(bucketName).maxUploads(2)).uploads()
        .stream().map(upload -> upload.key() + " " + upload.uploadId()).toList();

    assertEquals(created, listed);

    ListMultipartUploadsResponse firstPage = s3Client.listMultipartUploads(b -> b.bucket(bucketName).maxUploads(2));
    assertTrue(firstPage.isTruncated());
    assertEquals("a", firstPage.nextKeyMarker());
    assertEquals(created.get(1).split(" ")[1], firstPage.nextUploadIdMarker());

    ListMultipartUploadsResponse secondPage = s3Client.listMultipartUploads(b -> b.bucket(bucketName).maxUploads(2)
        .keyMarker(firstPage.nextKeyMarker()).uploadIdMarker(firstPage.nextUploadIdMarker()));
    assertEquals("a", secondPage.keyMarker());
    assertEquals(firstPage.nextUploadIdMarker(), secondPage.uploadIdMarker());
    assertEquals(List.of(created.get(2), created.get(3)),
        secondPage.uploads().stream().map(upload -> upload.key() + " " + upload.uploadId()).toList());
  }

  @LocalS3
  @Test
  void rollsUpTheKeysAfterThePrefix(S3Client s3Client) {
    String bucketName = "test-bucket-" + UUID.randomUUID();
    s3Client.createBucket(b -> b.bucket(bucketName));
    for (String key : List.of("dir/x/1", "dir/x/2", "dir/y", "other/z")) {
      s3Client.createMultipartUpload(b -> b.bucket(bucketName).key(key));
    }

    ListMultipartUploadsResponse response = s3Client.listMultipartUploads(b -> b.bucket(bucketName)
        .prefix("dir/").delimiter("/"));

    assertEquals("dir/", response.prefix());
    assertEquals("/", response.delimiter());
    assertEquals(List.of("dir/y"), response.uploads().stream().map(MultipartUpload::key).toList());
    assertEquals(List.of("dir/x/"), response.commonPrefixes().stream().map(CommonPrefix::prefix).toList());
    assertFalse(response.isTruncated());
  }

  /**
   * The SDK decodes the keys of an url-encoded listing only if the response names its EncodingType.
   */
  @LocalS3
  @Test
  void answersTheEncodingTypeOfAnUrlEncodedListing(S3Client s3Client) {
    String bucketName = "test-bucket-" + UUID.randomUUID();
    s3Client.createBucket(b -> b.bucket(bucketName));
    String key = "dir with space/a+b.txt";
    s3Client.createMultipartUpload(b -> b.bucket(bucketName).key(key));

    ListMultipartUploadsResponse response = s3Client.listMultipartUploads(b -> b.bucket(bucketName)
        .prefix("dir with space/").encodingType(EncodingType.URL));

    assertEquals(EncodingType.URL, response.encodingType());
    assertEquals("dir with space/", response.prefix());
    assertEquals(List.of(key), response.uploads().stream().map(MultipartUpload::key).toList());
  }

  @LocalS3
  @Test
  void capsMaxUploadsAndRejectsANegativeOne(S3Client s3Client) {
    String bucketName = "test-bucket-" + UUID.randomUUID();
    s3Client.createBucket(b -> b.bucket(bucketName));
    s3Client.createMultipartUpload(b -> b.bucket(bucketName).key("a"));

    ListMultipartUploadsResponse response = s3Client.listMultipartUploads(b -> b.bucket(bucketName).maxUploads(100000));
    assertEquals(1000, response.maxUploads());
    assertEquals(1, response.uploads().size());

    S3Exception exception = assertThrows(S3Exception.class,
        () -> s3Client.listMultipartUploads(b -> b.bucket(bucketName).maxUploads(-1)));
    assertEquals(400, exception.statusCode());
    assertEquals("InvalidArgument", exception.awsErrorDetails().errorCode());
  }
}
