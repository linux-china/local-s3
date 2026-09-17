package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * {@code GetObject} and {@code HeadObject} with the {@code partNumber} query parameter read a single part of an
 * object uploaded in parts, and answer the number of parts in {@code x-amz-mp-parts-count}, which is how the multipart
 * download of the AWS SDK and the Transfer Manager read an object in parallel.
 */
class GetObjectPartIntegrationTest {

  @Test
  @LocalS3
  void readsEachPartOfAnObjectUploadedInParts(S3Client s3) {
    String bucket = "part-read-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    String first = Parts.large("Hello");
    String second = Parts.large("World!", 1);
    String third = "Again";
    // The parts are counted in the order of the content, not by the numbers they were uploaded with.
    upload(s3, bucket, key, new int[] {2, 5, 9}, first, second, third);
    long size = first.length() + second.length() + third.length();

    String[] contents = {first, second, third};
    long start = 0;
    for (int i = 0; i < contents.length; i++) {
      int partNumber = i + 1;
      ResponseBytes<GetObjectResponse> part = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)
          .partNumber(partNumber));
      long end = start + contents[i].length() - 1;
      assertEquals(contents[i], part.asUtf8String());
      assertEquals(3, part.response().partsCount());
      assertEquals(contents[i].length(), part.response().contentLength());
      assertEquals("bytes " + start + "-" + end + "/" + size, part.response().contentRange());

      HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(key).partNumber(partNumber));
      assertEquals(3, head.partsCount());
      assertEquals(contents[i].length(), head.contentLength());
      assertEquals(part.response().eTag(), head.eTag());
      start = end + 1;
    }

    // Without a part number, the whole object, with no part count.
    GetObjectResponse whole = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).response();
    assertEquals(size, whole.contentLength());
    assertNull(whole.partsCount());
  }

  @Test
  @LocalS3
  void anObjectStoredAtOnceHasASinglePart(S3Client s3) {
    String bucket = "part-read-single-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("Hello"));

    ResponseBytes<GetObjectResponse> part = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key).partNumber(1));
    assertEquals("Hello", part.asUtf8String());
    assertNull(part.response().partsCount());

    S3Exception exception = assertThrows(S3Exception.class,
        () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(key).partNumber(2)));
    assertEquals(416, exception.statusCode());
    assertEquals("InvalidPartNumber", exception.awsErrorDetails().errorCode());
  }

  @Test
  @LocalS3
  void rejectsAPartThatTheObjectDoesNotHaveAndAPartWithARange(S3Client s3) {
    String bucket = "part-read-invalid-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    upload(s3, bucket, key, new int[] {1, 2}, Parts.large("Hello"), "World");

    S3Exception beyond = assertThrows(S3Exception.class,
        () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(key).partNumber(3)));
    assertEquals(416, beyond.statusCode());
    assertEquals("InvalidPartNumber", beyond.awsErrorDetails().errorCode());

    S3Exception withRange = assertThrows(S3Exception.class,
        () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(key).partNumber(1).range("bytes=0-1")));
    assertEquals(400, withRange.statusCode());
    assertEquals("InvalidRequest", withRange.awsErrorDetails().errorCode());

    S3Exception zero = assertThrows(S3Exception.class,
        () -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(key).partNumber(0)));
    assertEquals(400, zero.statusCode());
  }

  /**
   * The multipart download of the AWS SDK reads the object a part at a time.
   */
  @Test
  @LocalS3
  void theMultipartDownloadOfTheSdkReadsTheWholeObject(S3Client s3, LocalS3Endpoint endpoint) {
    String bucket = "part-read-sdk-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    String first = Parts.large("Hello");
    String second = Parts.large("World!", 1);
    String third = "Again";
    upload(s3, bucket, key, new int[] {1, 2, 3}, first, second, third);

    try (S3AsyncClient async = S3AsyncClient.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + endpoint.port()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")))
        .forcePathStyle(true)
        .multipartEnabled(true)
        .build()) {
      ResponseBytes<GetObjectResponse> downloaded = async.getObject(b -> b.bucket(bucket).key(key),
          AsyncResponseTransformer.toBytes()).join();
      assertArrayEquals((first + second + third).getBytes(StandardCharsets.UTF_8), downloaded.asByteArray());
    }
  }

  private static void upload(S3Client s3, String bucket, String key, int[] partNumbers, String... contents) {
    CreateMultipartUploadResponse created = s3.createMultipartUpload(b -> b.bucket(bucket).key(key));
    List<CompletedPart> completed = new ArrayList<>();
    for (int i = 0; i < contents.length; i++) {
      int partNumber = partNumbers[i];
      UploadPartResponse part = s3.uploadPart(b -> b.bucket(bucket).key(key)
          .uploadId(created.uploadId()).partNumber(partNumber), RequestBody.fromString(contents[i]));
      completed.add(CompletedPart.builder().partNumber(partNumber).eTag(part.eTag()).build());
    }
    s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(created.uploadId())
        .multipartUpload(mu -> mu.parts(completed)));
  }

}
