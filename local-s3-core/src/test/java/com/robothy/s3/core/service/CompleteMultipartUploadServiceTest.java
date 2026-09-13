package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.InvalidPartException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.UploadNotExistException;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CompleteMultipartUploadServiceTest extends LocalS3ServiceTestBase {

  @ParameterizedTest
  @MethodSource("localS3Services")
  void completeMultipartUpload(BucketService bucketService, ObjectService objectService) throws IOException {
    String bucket = "my-bucket";
    String key = "a.txt";

    assertThrows(BucketNotExistException.class, () -> objectService.completeMultipartUpload(bucket, key, "123", null));
    bucketService.createBucket(bucket);
    assertThrows(UploadNotExistException.class, () -> objectService.completeMultipartUpload(bucket, key, "123", null));

    String uploadId = objectService.createMultipartUpload(bucket, key, CreateMultipartUploadOptions.builder()
        .contentType("plain/text")
        .build());
    assertThrows(UploadNotExistException.class, () -> objectService.completeMultipartUpload(bucket, key, "123", Collections.emptyList()));
    assertThrows(IllegalArgumentException.class, () -> objectService.completeMultipartUpload(bucket, key, uploadId, Collections.emptyList()));

    objectService.uploadPart(bucket, key, uploadId, 1, UploadPartOptions.builder()
            .data(new ByteArrayInputStream("Hello".getBytes()))
            .contentLength(5)
        .build());

    objectService.uploadPart(bucket, key, uploadId, 2, UploadPartOptions.builder()
        .data(new ByteArrayInputStream("World".getBytes()))
        .contentLength(5)
        .build());

    CompleteMultipartUploadAns completeAns = objectService.completeMultipartUpload(bucket, key, uploadId, Arrays.asList(
            CompleteMultipartUploadPartOption.builder().partNumber(1).build(),
            CompleteMultipartUploadPartOption.builder().partNumber(2).build()));
    assertEquals("/" + bucket + "/" + key, completeAns.getLocation());
    assertNull(completeAns.getVersionId());

    GetObjectAns object = objectService.getObject(bucket, key, GetObjectOptions.builder().build());
    assertEquals("plain/text", object.getContentType());
    assertEquals("HelloWorld", new String(object.getContent().readAllBytes()));
  }

  /**
   * The object of a completed upload gets the entity tag that Amazon S3 gives an object uploaded in parts,
   * which the reads of the object report as well, so that a client that asks for the object afterwards sees
   * the same part layout as the one that uploaded it.
   */
  @ParameterizedTest
  @MethodSource("localS3Services")
  void completeMultipartUploadAnswersTheCompositeEtag(BucketService bucketService, ObjectService objectService) {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = upload(objectService, bucket, key, "Hello", "World");

    CompleteMultipartUploadAns completeAns = complete(objectService, bucket, key, uploadId, 2);

    // md5(md5("Hello") + md5("World")) + "-2", computed outside of LocalS3.
    assertEquals("64d1e57a34042883053ec1c5d8d60167-2", completeAns.getEtag());
    assertEquals("64d1e57a34042883053ec1c5d8d60167-2",
        objectService.getObject(bucket, key, GetObjectOptions.builder().build()).getEtag());
    assertEquals("64d1e57a34042883053ec1c5d8d60167-2",
        objectService.headObject(bucket, key, GetObjectOptions.builder().build()).getEtag());
  }

  /**
   * An upload of a single part gets a composite entity tag too, with the suffix {@code -1}, which is what
   * distinguishes it from an object that {@code PutObject} stored.
   */
  @ParameterizedTest
  @MethodSource("localS3Services")
  void completeMultipartUploadOfASinglePartAnswersTheCompositeEtag(BucketService bucketService,
                                                                   ObjectService objectService) {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = upload(objectService, bucket, key, "Hello");

    CompleteMultipartUploadAns completeAns = complete(objectService, bucket, key, uploadId, 1);

    assertEquals("49c24cf3c5af9ba03cec39ee4aac4f77-1", completeAns.getEtag());
    // The entity tag of the same content stored at once, which the composite entity tag must not be.
    assertNotEquals(DigestUtils.md5Hex("Hello"), completeAns.getEtag());
  }

  /**
   * The entity tag is computed from the parts as they were stored, so an upload whose parts reported an
   * entity tag that isn't their digest, which LocalS3 lets a request supply, still gets the entity tag that
   * describes its content.
   */
  @ParameterizedTest
  @MethodSource("localS3Services")
  void completeMultipartUploadIgnoresTheReportedPartEtags(BucketService bucketService,
                                                           ObjectService objectService) {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = objectService.createMultipartUpload(bucket, key, CreateMultipartUploadOptions.builder()
        .contentType("plain/text")
        .build());
    objectService.uploadPart(bucket, key, uploadId, 1, UploadPartOptions.builder()
        .data(new ByteArrayInputStream("Hello".getBytes()))
        .contentLength(5)
        .etag("not-a-digest")
        .build());
    objectService.uploadPart(bucket, key, uploadId, 2, UploadPartOptions.builder()
        .data(new ByteArrayInputStream("World".getBytes()))
        .contentLength(5)
        .etag("not-a-digest-either")
        .build());

    assertEquals("64d1e57a34042883053ec1c5d8d60167-2",
        complete(objectService, bucket, key, uploadId, 2).getEtag());
  }

  /**
   * A service configured to answer the entity tags that LocalS3 answered before 2.5 gives the object the
   * digest of its whole content, so that a test that asserts that entity tag keeps working.
   */
  @ParameterizedTest
  @MethodSource("localS3Services")
  void completeMultipartUploadAnswersTheContentDigestWhenCompositeEtagsAreOff(BucketService bucketService,
                                                                              ObjectService objectService) {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = upload(objectService, bucket, key, "Hello", "World");

    CompleteMultipartUploadAns completeAns = objectService.completeMultipartUpload(bucket, key, uploadId,
        completeParts(2), 0, false);

    assertEquals(DigestUtils.md5Hex("HelloWorld"), completeAns.getEtag());
  }

  /**
   * The entity tags that complete an upload must be the ones that the uploads of its parts answered, with or
   * without the quotes that Amazon S3 answers them with.
   */
  @ParameterizedTest
  @MethodSource("localS3Services")
  void completeMultipartUploadAcceptsTheEtagsOfTheParts(BucketService bucketService, ObjectService objectService)
      throws IOException {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = upload(objectService, bucket, key, "Hello", "World");

    objectService.completeMultipartUpload(bucket, key, uploadId, List.of(
        CompleteMultipartUploadPartOption.builder().partNumber(1).etag(DigestUtils.md5Hex("Hello")).build(),
        CompleteMultipartUploadPartOption.builder().partNumber(2).etag("\"" + DigestUtils.md5Hex("World") + "\"").build()));

    assertEquals("HelloWorld", new String(objectService.getObject(bucket, key, GetObjectOptions.builder().build())
        .getContent().readAllBytes()));
  }

  /**
   * An entity tag that isn't the one of the uploaded part is rejected with {@code InvalidPart}, like Amazon S3
   * does, and leaves the upload as it was, so that it can be completed with the right entity tags.
   */
  @ParameterizedTest
  @MethodSource("localS3Services")
  void completeMultipartUploadRejectsAnEtagThatDoesNotMatchThePart(BucketService bucketService,
                                                                  ObjectService objectService) {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = upload(objectService, bucket, key, "Hello", "World");

    InvalidPartException thrown = assertThrows(InvalidPartException.class, () ->
        objectService.completeMultipartUpload(bucket, key, uploadId, List.of(
            CompleteMultipartUploadPartOption.builder().partNumber(1).etag(DigestUtils.md5Hex("Hello")).build(),
            CompleteMultipartUploadPartOption.builder().partNumber(2).etag("\"00000000000000000000000000000000\"").build())));
    assertEquals(S3ErrorCode.InvalidPart, thrown.getS3ErrorCode());
    assertThrows(ObjectNotExistException.class,
        () -> objectService.getObject(bucket, key, GetObjectOptions.builder().build()));

    assertEquals(2, objectService.listParts(bucket, key, uploadId, null, null).getParts().size());
    assertDoesNotThrow(() -> complete(objectService, bucket, key, uploadId, 2));
  }

  /**
   * A part that was never uploaded is rejected with {@code InvalidPart}.
   */
  @ParameterizedTest
  @MethodSource("localS3Services")
  void completeMultipartUploadRejectsAPartThatWasNotUploaded(BucketService bucketService,
                                                             ObjectService objectService) {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = upload(objectService, bucket, key, "Hello");

    InvalidPartException thrown = assertThrows(InvalidPartException.class, () ->
        objectService.completeMultipartUpload(bucket, key, uploadId, List.of(
            CompleteMultipartUploadPartOption.builder().partNumber(1).build(),
            CompleteMultipartUploadPartOption.builder().partNumber(7).build())));
    assertEquals(S3ErrorCode.InvalidPart, thrown.getS3ErrorCode());
    assertTrue(thrown.getMessage().contains("Part 7"), thrown.getMessage());
  }

  /**
   * Start an upload of the given parts, in the order they are given.
   *
   * @return the upload ID.
   */
  private static String upload(ObjectService objectService, String bucket, String key, String... parts) {
    String uploadId = objectService.createMultipartUpload(bucket, key, CreateMultipartUploadOptions.builder()
        .contentType("plain/text")
        .build());
    for (int i = 0; i < parts.length; i++) {
      byte[] content = parts[i].getBytes();
      objectService.uploadPart(bucket, key, uploadId, i + 1, UploadPartOptions.builder()
          .data(new ByteArrayInputStream(content))
          .contentLength(content.length)
          .build());
    }
    return uploadId;
  }

  private static CompleteMultipartUploadAns complete(ObjectService objectService, String bucket, String key,
                                                     String uploadId, int parts) {
    return objectService.completeMultipartUpload(bucket, key, uploadId, completeParts(parts));
  }

  private static List<CompleteMultipartUploadPartOption> completeParts(int parts) {
    return IntStream.rangeClosed(1, parts)
        .mapToObj(partNumber -> CompleteMultipartUploadPartOption.builder().partNumber(partNumber).build())
        .collect(Collectors.toList());
  }

}