package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesParts;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesResponse;
import software.amazon.awssdk.services.s3.model.ObjectAttributes;
import software.amazon.awssdk.services.s3.model.ObjectPart;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectAttributes.html">GetObjectAttributes</a>
 * answers the attributes that the {@code x-amz-object-attributes} header of the request names, and nothing
 * else: a client reads an attribute it didn't ask for as absent, so answering one it didn't ask for tells it
 * something that isn't true of the request it made.
 */
public class GetObjectAttributesIntegrationTest {

  @Test
  @LocalS3
  void answersOnlyTheAttributeThatWasAskedFor(S3Client s3) {
    String bucket = "attributes-only-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("Hello"));

    GetObjectAttributesResponse etagOnly = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.E_TAG));
    assertNotNull(etagOnly.eTag());
    assertNull(etagOnly.objectSize());
    assertNull(etagOnly.storageClass());
    assertNull(etagOnly.objectParts());

    GetObjectAttributesResponse sizeOnly = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.OBJECT_SIZE));
    assertEquals(5L, sizeOnly.objectSize());
    assertNull(sizeOnly.eTag());
    assertNull(sizeOnly.storageClass());

    GetObjectAttributesResponse storageClassOnly = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.STORAGE_CLASS));
    assertNotNull(storageClassOnly.storageClass());
    assertNull(storageClassOnly.eTag());
    assertNull(storageClassOnly.objectSize());
  }

  /**
   * LocalS3 stores no checksum of an object, so the {@code Checksum} attribute is answered with nothing,
   * like Amazon S3 answers it for an object that was stored without one. What it may not be answered with
   * is another attribute: a client that asks for the checksum and reads back an entity tag has no way to
   * tell that it didn't get what it asked for.
   */
  @Test
  @LocalS3
  void askingForTheChecksumAnswersNoOtherAttribute(S3Client s3) {
    String bucket = "attributes-checksum-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("Hello"));

    GetObjectAttributesResponse attributes = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.CHECKSUM));

    assertNull(attributes.checksum());
    assertNull(attributes.eTag());
    assertNull(attributes.objectSize());
    assertNull(attributes.storageClass());
  }

  /**
   * Every attribute that was asked for is answered, so a request that asks for all of them reads the same
   * answer that it read before the attributes were honoured at all.
   */
  @Test
  @LocalS3
  void answersEveryAttributeThatWasAskedFor(S3Client s3) {
    String bucket = "attributes-all-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("Hello"));

    GetObjectAttributesResponse attributes = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.E_TAG, ObjectAttributes.OBJECT_SIZE,
            ObjectAttributes.STORAGE_CLASS, ObjectAttributes.OBJECT_PARTS, ObjectAttributes.CHECKSUM));

    assertNotNull(attributes.eTag());
    assertEquals(5L, attributes.objectSize());
    assertNotNull(attributes.storageClass());
    assertNotNull(attributes.lastModified());
  }

  /**
   * The part layout of an object is what client code that reads an object in parts, e.g. a parallel
   * downloader, asks for. It outlives the upload that stored the object: the upload and its parts are
   * removed once it is completed.
   */
  @Test
  @LocalS3
  void answersThePartLayoutOfAnObjectUploadedInParts(S3Client s3) {
    String bucket = "attributes-parts-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    upload(s3, bucket, key, "Hello", "World!", "Again");

    GetObjectAttributesParts parts = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.OBJECT_PARTS)).objectParts();

    assertNotNull(parts);
    assertEquals(3, parts.totalPartsCount());
    assertEquals(3, parts.parts().size());
    assertFalse(parts.isTruncated());
    assertEquals(List.of(1, 2, 3), parts.parts().stream().map(ObjectPart::partNumber).toList());
    // The lengths of the parts that were concatenated, which add up to the size of the object.
    assertEquals(List.of(5L, 6L, 5L), parts.parts().stream().map(ObjectPart::size).toList());
    assertEquals(16L, s3.headObject(b -> b.bucket(bucket).key(key)).contentLength());
  }

  /**
   * An object that {@code PutObject} stored was not uploaded in parts, so it has no part layout to answer.
   */
  @Test
  @LocalS3
  void anObjectStoredAtOnceHasNoParts(S3Client s3) {
    String bucket = "attributes-no-parts-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("Hello"));

    GetObjectAttributesResponse attributes = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.OBJECT_PARTS));

    assertNull(attributes.objectParts());
  }

  /**
   * The parts are paged the way {@code ListParts} pages them, so that the layout of an object with many
   * parts can be read a page at a time.
   */
  @Test
  @LocalS3
  void thePartsArePaged(S3Client s3) {
    String bucket = "attributes-paged-parts-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    upload(s3, bucket, key, "Hello", "World!", "Again");

    GetObjectAttributesParts firstPage = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.OBJECT_PARTS).maxParts(2)).objectParts();

    // The count is of the whole object, not of the page.
    assertEquals(3, firstPage.totalPartsCount());
    assertEquals(2, firstPage.parts().size());
    assertTrue(firstPage.isTruncated());
    assertEquals(2, firstPage.nextPartNumberMarker());
    assertEquals(2, firstPage.maxParts());

    GetObjectAttributesParts secondPage = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.OBJECT_PARTS)
        .partNumberMarker(firstPage.nextPartNumberMarker())).objectParts();

    assertEquals(1, secondPage.parts().size());
    assertEquals(3, secondPage.parts().get(0).partNumber());
    assertFalse(secondPage.isTruncated());
    assertEquals(2, secondPage.partNumberMarker());
  }

  /**
   * The parts of an upload don't have to be numbered consecutively, so the layout reports the numbers that
   * the parts were uploaded with rather than renumbering them.
   */
  @Test
  @LocalS3
  void thePartsKeepTheNumbersTheyWereUploadedWith(S3Client s3) {
    String bucket = "attributes-sparse-parts-bucket";
    String key = "a.txt";
    s3.createBucket(b -> b.bucket(bucket));
    CreateMultipartUploadResponse created = s3.createMultipartUpload(b -> b.bucket(bucket).key(key));
    List<CompletedPart> completed = new ArrayList<>();
    for (int partNumber : new int[] {2, 7}) {
      UploadPartResponse part = s3.uploadPart(b -> b.bucket(bucket).key(key)
          .uploadId(created.uploadId()).partNumber(partNumber), RequestBody.fromString("Hello"));
      completed.add(CompletedPart.builder().partNumber(partNumber).eTag(part.eTag()).build());
    }
    s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(created.uploadId())
        .multipartUpload(mu -> mu.parts(completed)));

    GetObjectAttributesParts parts = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.OBJECT_PARTS)).objectParts();

    assertEquals(List.of(2, 7), parts.parts().stream().map(ObjectPart::partNumber).toList());
    assertEquals(2, parts.totalPartsCount());

    GetObjectAttributesParts afterTheFirst = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
        .objectAttributes(ObjectAttributes.OBJECT_PARTS).partNumberMarker(2)).objectParts();
    assertEquals(List.of(7), afterTheFirst.parts().stream().map(ObjectPart::partNumber).toList());
  }

  /**
   * Upload the given parts, in the order they are given, and complete the upload.
   */
  private static void upload(S3Client s3, String bucket, String key, String... parts) {
    CreateMultipartUploadResponse created = s3.createMultipartUpload(b -> b.bucket(bucket).key(key));
    List<CompletedPart> completedParts = new ArrayList<>(parts.length);
    for (int i = 0; i < parts.length; i++) {
      int partNumber = i + 1;
      UploadPartResponse part = s3.uploadPart(b -> b.bucket(bucket).key(key)
          .uploadId(created.uploadId()).partNumber(partNumber), RequestBody.fromString(parts[partNumber - 1]));
      completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(part.eTag()).build());
    }
    s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(created.uploadId())
        .multipartUpload(mu -> mu.parts(completedParts)));
  }

}
