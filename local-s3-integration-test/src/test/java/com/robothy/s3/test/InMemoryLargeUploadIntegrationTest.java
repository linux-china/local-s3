package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * An {@code IN_MEMORY} service receives the large bodies of {@code PutObject} and {@code UploadPart} into the heap, in
 * chunks that its storage takes over, rather than into temporary files. The AWS SDK sends them {@code aws-chunked}
 * over plain HTTP, signed, with a checksum in the trailer, which is decoded and verified as it is received.
 */
class InMemoryLargeUploadIntegrationTest {

  private static final int MIB = 1024 * 1024;

  private static final String BUCKET = "large-uploads";

  private LocalS3 localS3;

  private S3Client s3;

  @BeforeEach
  void setUp() {
    localS3 = LocalS3.builder().port(-1).buckets(BUCKET)
        .credentials("in-memory", "in-memory-secret")
        .storage(storage -> storage.maxInMemoryBytes(48L * MIB))
        .build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(URI.create("http://localhost:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("in-memory", "in-memory-secret")))
        .forcePathStyle(true)
        .build();
  }

  @AfterEach
  void tearDown() {
    s3.close();
    localS3.shutdown();
  }

  @Test
  void storesLargeObjectsAndPartsThatWereReceivedIntoTheHeap() {
    byte[] object = randomBytes(20 * MIB, 1);
    s3.putObject(put -> put.bucket(BUCKET).key("object.bin").checksumAlgorithm(ChecksumAlgorithm.CRC32),
        RequestBody.fromBytes(object));
    assertArrayEquals(object, s3.getObjectAsBytes(get -> get.bucket(BUCKET).key("object.bin")).asByteArray());

    byte[] multipart = randomBytes(13 * MIB, 2);
    String uploadId = s3.createMultipartUpload(create -> create.bucket(BUCKET).key("multipart.bin")).uploadId();
    List<CompletedPart> parts = new ArrayList<>();
    int partSize = 6 * MIB;
    for (int offset = 0, number = 1; offset < multipart.length; offset += partSize, number++) {
      byte[] part = Arrays.copyOfRange(multipart, offset, Math.min(multipart.length, offset + partSize));
      int partNumber = number;
      String etag = s3.uploadPart(upload -> upload.bucket(BUCKET).key("multipart.bin").uploadId(uploadId)
          .partNumber(partNumber), RequestBody.fromBytes(part)).eTag();
      parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
    }
    s3.completeMultipartUpload(complete -> complete.bucket(BUCKET).key("multipart.bin").uploadId(uploadId)
        .multipartUpload(upload -> upload.parts(parts)));
    assertArrayEquals(multipart, s3.getObjectAsBytes(get -> get.bucket(BUCKET).key("multipart.bin")).asByteArray());

    // 33 MiB of the 48 MiB are stored: each upload took its space once.
    S3Exception full = assertThrows(S3Exception.class, () -> s3.putObject(
        put -> put.bucket(BUCKET).key("too-large.bin"), RequestBody.fromBytes(new byte[16 * MIB])));
    assertEquals(507, full.statusCode());
    assertEquals("InsufficientStorage", full.awsErrorDetails().errorCode());

    s3.putObject(put -> put.bucket(BUCKET).key("fits.bin"), RequestBody.fromBytes(new byte[15 * MIB]));
  }

  private static byte[] randomBytes(int size, long seed) {
    byte[] bytes = new byte[size];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }

}
