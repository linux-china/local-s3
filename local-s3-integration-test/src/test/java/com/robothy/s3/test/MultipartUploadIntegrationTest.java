package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.model.Part;

public class MultipartUploadIntegrationTest {

  @Test
  @LocalS3
  void multipartUpload(S3Client s3) throws IOException {
    s3.createBucket(b -> b.bucket("multipart-bucket"));
    CreateMultipartUploadResponse createResult = s3.createMultipartUpload(b -> b.bucket("multipart-bucket").key("example.txt"));
    UploadPartResponse part1 = s3.uploadPart(b ->
      b.bucket("multipart-bucket")
       .key("example.txt")
       .uploadId(createResult.uploadId())
       .partNumber(1), RequestBody.fromString(Parts.large("Hello")));
    UploadPartResponse part2 = s3.uploadPart(b ->
      b.bucket("multipart-bucket")
       .key("example.txt")
       .uploadId(createResult.uploadId())
       .partNumber(2), RequestBody.fromString("World"));
    s3.completeMultipartUpload(b ->
      b.bucket("multipart-bucket")
       .key("example.txt")
       .uploadId(createResult.uploadId())
       .multipartUpload(mu -> mu.parts(
         CompletedPart.builder().partNumber(1).eTag(part1.eTag()).build(),
         CompletedPart.builder().partNumber(2).eTag(part2.eTag()).build()
       )));
    ResponseInputStream<GetObjectResponse> objectContent = s3.getObject(b -> b.bucket("multipart-bucket").key("example.txt"));
    assertEquals(Parts.large("Hello") + "World", new String(objectContent.readAllBytes()));
  }

  @Test
  @LocalS3
  void completeMultipartUploadWithoutParts(S3Client s3) {
    s3.createBucket(b -> b.bucket("empty-multipart"));
    String uploadId = s3.createMultipartUpload(b -> b.bucket("empty-multipart").key("a.txt")).uploadId();
    S3Exception e = assertThrows(S3Exception.class, () -> s3.completeMultipartUpload(b ->
        b.bucket("empty-multipart").key("a.txt").uploadId(uploadId)));
    assertEquals(400, e.statusCode());
    assertEquals("MalformedXML", e.awsErrorDetails().errorCode());
  }

  @Test
  @LocalS3
  void testAbortMultipartUpload(S3Client s3) {
    String bucketName = "my-bucket";
    s3.createBucket(b -> b.bucket(bucketName));
    CreateMultipartUploadResponse initiateMultipartUploadResult =
        s3.createMultipartUpload(b -> b.bucket(bucketName).key("a.txt"));
    assertDoesNotThrow(() -> s3.abortMultipartUpload(
        b -> b.bucket(bucketName).key("a.txt").uploadId(initiateMultipartUploadResult.uploadId())));
    // abort again: the upload no longer exists
    S3Exception again = assertThrows(S3Exception.class, () -> s3.abortMultipartUpload(
        b -> b.bucket(bucketName).key("a.txt").uploadId(initiateMultipartUploadResult.uploadId())));
    assertEquals(404, again.statusCode());
    assertEquals("NoSuchUpload", again.awsErrorDetails().errorCode());

    // upload to an aborted multipart upload.
    assertThrows(S3Exception.class, () -> s3.uploadPart(b -> b.bucket(bucketName)
        .key("a.txt")
        .uploadId(initiateMultipartUploadResult.uploadId())
        .partNumber(1), RequestBody.fromString("Hello")));


    CreateMultipartUploadResponse initiateMultipartUploadResult1 =
        s3.createMultipartUpload(b -> b.bucket(bucketName).key("b.txt"));
    s3.uploadPart(b -> b.bucket(bucketName)
        .key("b.txt")
        .uploadId(initiateMultipartUploadResult1.uploadId())
        .partNumber(1), RequestBody.fromString("Hello"));
    s3.abortMultipartUpload(b -> b.bucket(bucketName).key("b.txt").uploadId(initiateMultipartUploadResult1.uploadId()));
    assertThrows(S3Exception.class, () ->
        s3.listParts(b -> b.bucket(bucketName).key("b.txt").uploadId(initiateMultipartUploadResult1.uploadId())));
  }

  @LocalS3
  @Test
  void testListParts(S3Client s3) {
    String bucketName = "it-list-parts";
    s3.createBucket(b -> b.bucket(bucketName));

    // list parts of a non-exist multipart upload.
    assertThrows(S3Exception.class, () ->
        s3.listParts(b -> b.bucket(bucketName).key("a.txt").uploadId("non-exist-upload-id")));


    CreateMultipartUploadResponse initiateMultipartUploadResult =
        s3.createMultipartUpload(b -> b.bucket(bucketName).key("a.txt"));
    UploadPartResponse uploadPartRequest1 = s3.uploadPart(b -> b.bucket(bucketName)
        .key("a.txt")
        .uploadId(initiateMultipartUploadResult.uploadId())
        .partNumber(1), RequestBody.fromString(Parts.large("Hello")));
    UploadPartResponse uploadPartRequest2 = s3.uploadPart(b -> b.bucket(bucketName)
        .key("a.txt")
        .uploadId(initiateMultipartUploadResult.uploadId())
        .partNumber(2), RequestBody.fromString(Parts.large("World")));
    UploadPartResponse uploadPartRequest3 = s3.uploadPart(b -> b.bucket(bucketName)
        .key("a.txt")
        .uploadId(initiateMultipartUploadResult.uploadId())
        .partNumber(3), RequestBody.fromString("World"));

    // give: 3 parts
    // list parts with set max-parts and part-number-marker.
    List<Part> partListing =
        s3.listParts(b -> b.bucket(bucketName).key("a.txt").uploadId(initiateMultipartUploadResult.uploadId())).parts();
    assertEquals(3, partListing.size());

    Part part1 = partListing.get(0);
    assertEquals(1, part1.partNumber());
    assertEquals(uploadPartRequest1.eTag(), part1.eTag());

    Part part2 = partListing.get(1);
    assertEquals(2, part2.partNumber());
    assertEquals(uploadPartRequest2.eTag(), part2.eTag());

    Part part3 = partListing.get(2);
    assertEquals(3, part3.partNumber());
    assertEquals(uploadPartRequest3.eTag(), part3.eTag());


    // given: 3 parts
    // list parts with max-parts and part-number-marker.
    partListing = s3.listParts(b -> b.bucket(bucketName).key("a.txt").uploadId(initiateMultipartUploadResult.uploadId())
        .maxParts(2)
        .partNumberMarker(0)).parts();
    assertEquals(2, partListing.size());

    // given: completed multipart upload
    List<CompletedPart> partETags = List.of(CompletedPart.builder().partNumber(1).eTag(uploadPartRequest1.eTag()).build(),
        CompletedPart.builder().partNumber(2).eTag(uploadPartRequest2.eTag()).build(),
        CompletedPart.builder().partNumber(3).eTag(uploadPartRequest3.eTag()).build());
    s3.completeMultipartUpload(b -> b.bucket(bucketName).key("a.txt").uploadId(initiateMultipartUploadResult.uploadId()).multipartUpload(mu -> mu.parts(partETags)));
    assertThrows(S3Exception.class, () ->
        s3.listParts(b -> b.bucket(bucketName).key("a.txt").uploadId(initiateMultipartUploadResult.uploadId())));
  }

  @Test
  @LocalS3
  void testCreateMultipartUploadsWithTagging(S3Client s3) throws Exception {
    s3.createBucket(builder -> builder.bucket("my-bucket"));
    Tag tag1 = Tag.builder().key("k1").value("v1").build();
    Tag tag2 = Tag.builder().key("k2").value("v2").build();
    CreateMultipartUploadResponse multipartUpload = s3.createMultipartUpload(builder -> builder.bucket("my-bucket").key("a.txt")
        .tagging(Tagging.builder().tagSet(tag1, tag2).build()));
    UploadPartResponse part1 = s3.uploadPart(b -> b.bucket("my-bucket")
            .uploadId(multipartUpload.uploadId()).key("a.txt").partNumber(1), RequestBody.fromString(Parts.large("Hello")));
    UploadPartResponse part2 = s3.uploadPart(b -> b.bucket("my-bucket").uploadId(multipartUpload.uploadId()).key("a.txt").partNumber(2),
            RequestBody.fromString("World"));

    CompletedPart completedPart1 = CompletedPart.builder().partNumber(1).eTag(part1.eTag()).build();
    CompletedPart completedPart2 = CompletedPart.builder().partNumber(2).eTag(part2.eTag()).build();
    s3.completeMultipartUpload(b -> b.bucket("my-bucket").key("a.txt").multipartUpload(upload -> upload.parts(
        completedPart1, completedPart2)).uploadId(multipartUpload.uploadId()));

    ResponseInputStream<GetObjectResponse> completedObject =
        s3.getObject(b -> b.bucket("my-bucket").key("a.txt"));
    assertEquals(Parts.large("Hello") + "World", new String(completedObject.readAllBytes()));
    Integer tagCount = completedObject.response().tagCount();
    assertEquals(2, tagCount);

    List<Tag> tags = s3.getObjectTagging(b -> b.bucket("my-bucket").key("a.txt")).tagSet();
    assertEquals(2, tags.size());
    assertTrue(tags.contains(tag1));
    assertTrue(tags.contains(tag2));
  }

  @Test
  @LocalS3
  void testMultipartUploadWithMultiVersions(S3Client s3Client) throws Exception {
    s3Client.createBucket(builder -> builder.bucket("my-bucket"));
    s3Client.putBucketVersioning(builder -> builder.bucket("my-bucket").versioningConfiguration(c -> c.status("Enabled")));
    CreateMultipartUploadResponse multipartUploadV1 = s3Client.createMultipartUpload(builder -> builder.bucket("my-bucket").key("a.txt"));
    UploadPartResponse part1V1 = s3Client.uploadPart(b -> b.bucket("my-bucket")
            .uploadId(multipartUploadV1.uploadId()).key("a.txt").partNumber(1), RequestBody.fromString(Parts.large("v1")));
    UploadPartResponse part2V1 = s3Client.uploadPart(b -> b.bucket("my-bucket").uploadId(multipartUploadV1.uploadId()).key("a.txt").partNumber(2),
            RequestBody .fromString("v1"));
    CompleteMultipartUploadResponse multipartUploadResponseV1 = s3Client.completeMultipartUpload(b -> b
        .bucket("my-bucket")
        .key("a.txt")
        .multipartUpload(upload -> upload.parts(CompletedPart.builder().partNumber(1).eTag(part1V1.eTag()).build(),
            CompletedPart.builder().partNumber(2).eTag(part2V1.eTag()).build()))
        .uploadId(multipartUploadV1.uploadId()));


    CreateMultipartUploadResponse multipartUploadV2 =
        s3Client.createMultipartUpload(builder -> builder.bucket("my-bucket").key("a.txt"));
    UploadPartResponse part1V2 = s3Client.uploadPart(b -> b.bucket("my-bucket")
            .uploadId(multipartUploadV2.uploadId()).key("a.txt").partNumber(1), RequestBody.fromString(Parts.large("v2")));
    UploadPartResponse part2V2 = s3Client.uploadPart(b -> b.bucket("my-bucket").uploadId(multipartUploadV2.uploadId()).key("a.txt").partNumber(2),
            RequestBody.fromString("v2"));
    CompleteMultipartUploadResponse multipartUploadResponseV2 = s3Client.completeMultipartUpload(b -> b
        .bucket("my-bucket")
        .key("a.txt")
        .multipartUpload(upload -> upload.parts(CompletedPart.builder().partNumber(1).eTag(part1V2.eTag()).build(),
            CompletedPart.builder().partNumber(2).eTag(part2V2.eTag()).build()))
        .uploadId(multipartUploadV2.uploadId()));

    List<ObjectVersion> versions = s3Client.listObjectVersions(builder -> builder.bucket("my-bucket").prefix("a.txt")).versions();
    assertEquals(2, versions.size());
    assertEquals(multipartUploadResponseV1.versionId(), versions.get(1).versionId());
    assertEquals(multipartUploadResponseV2.versionId(), versions.get(0).versionId());
    ResponseInputStream<GetObjectResponse> objectV1 =
        s3Client.getObject(builder -> builder.bucket("my-bucket").key("a.txt").versionId(multipartUploadResponseV1.versionId()));
    assertEquals(Parts.large("v1") + "v1", new String(objectV1.readAllBytes()));
    ResponseInputStream<GetObjectResponse> objectV2 =
        s3Client.getObject(builder -> builder.bucket("my-bucket").key("a.txt").versionId(multipartUploadResponseV2.versionId()));
    assertEquals(Parts.large("v2") + "v2", new String(objectV2.readAllBytes()));
  }

  /**
   * A client that didn't receive the answer of a CompleteMultipartUpload, e.g. because it timed out, sends it again;
   * Amazon S3 answers the retry with the result of the completed upload, for as long as the object it stored exists.
   */
  @Test
  @LocalS3
  void completingAnUploadAgainAnswersTheSameResult(S3Client s3) throws IOException {
    s3.createBucket(b -> b.bucket("retry"));
    CompleteMultipartUploadRequest complete = uploadInTwoParts(s3, "retry", "a.txt");
    CompleteMultipartUploadResponse first = s3.completeMultipartUpload(complete);
    CompleteMultipartUploadResponse retried = s3.completeMultipartUpload(complete);

    assertEquals(first.eTag(), retried.eTag());
    assertEquals(first.location(), retried.location());
    assertEquals(first.versionId(), retried.versionId());
    assertEquals(first.checksumCRC32(), retried.checksumCRC32());
    assertEquals(Parts.large("Hello") + "World",
        new String(s3.getObject(b -> b.bucket("retry").key("a.txt")).readAllBytes()));
    assertEquals(1, s3.listObjectVersions(b -> b.bucket("retry")).versions().size(),
        "A retry stores nothing.");

    // Once another object replaced the one the upload stored, the upload is gone for good.
    s3.putObject(b -> b.bucket("retry").key("a.txt"), RequestBody.fromString("replaced"));
    assertThrows(NoSuchUploadException.class, () -> s3.completeMultipartUpload(complete));
  }

  @Test
  @LocalS3
  void completingAnUploadAgainInAVersionedBucketAnswersTheVersionItStored(S3Client s3) {
    s3.createBucket(b -> b.bucket("versioned-retry"));
    s3.putBucketVersioning(b -> b.bucket("versioned-retry")
        .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
    CompleteMultipartUploadRequest complete = uploadInTwoParts(s3, "versioned-retry", "a.txt");
    CompleteMultipartUploadResponse first = s3.completeMultipartUpload(complete);
    s3.putObject(b -> b.bucket("versioned-retry").key("a.txt"), RequestBody.fromString("newer"));

    CompleteMultipartUploadResponse retried = s3.completeMultipartUpload(complete);
    assertEquals(first.versionId(), retried.versionId());
    assertEquals(first.eTag(), retried.eTag());

    s3.deleteObject(b -> b.bucket("versioned-retry").key("a.txt").versionId(first.versionId()));
    assertThrows(NoSuchUploadException.class, () -> s3.completeMultipartUpload(complete));
  }

  @Test
  @LocalS3
  void anUploadThatWasNeverCreatedIsNotFound(S3Client s3) {
    s3.createBucket(b -> b.bucket("unknown-upload"));
    s3.putObject(b -> b.bucket("unknown-upload").key("a.txt"), RequestBody.fromString("a"));
    assertThrows(NoSuchUploadException.class, () -> s3.completeMultipartUpload(b -> b.bucket("unknown-upload")
        .key("a.txt").uploadId("123").multipartUpload(mu -> mu.parts(
            CompletedPart.builder().partNumber(1).eTag("\"0cc175b9c0f1b6a831c399e269772661\"").build()))));
  }

  private static CompleteMultipartUploadRequest uploadInTwoParts(S3Client s3, String bucket, String key) {
    String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(key)
        .checksumAlgorithm(ChecksumAlgorithm.CRC32)).uploadId();
    UploadPartResponse part1 = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(1)
        .checksumAlgorithm(ChecksumAlgorithm.CRC32), RequestBody.fromString(Parts.large("Hello")));
    UploadPartResponse part2 = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(2)
        .checksumAlgorithm(ChecksumAlgorithm.CRC32), RequestBody.fromString("World"));
    return CompleteMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId)
        .multipartUpload(mu -> mu.parts(
            CompletedPart.builder().partNumber(1).eTag(part1.eTag()).checksumCRC32(part1.checksumCRC32()).build(),
            CompletedPart.builder().partNumber(2).eTag(part2.eTag()).checksumCRC32(part2.checksumCRC32()).build()))
        .build();
  }

}
