package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * Over plain HTTP, the AWS SDK sends the body of {@code PutObject} and {@code UploadPart} {@code aws-chunked}, with
 * signed chunks and a trailing checksum. In {@code PERSISTENCE} mode such a body is decoded, and its chunk signatures
 * verified, while its body file is written, so that the file is renamed into place rather than decoded into a second
 * file.
 */
class AwsChunkedBodyFileIntegrationTest {

  @Test
  void storesSignedAwsChunkedUploadsByRenamingTheirBodyFiles(@TempDir Path dataPath) throws IOException {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .mode(LocalS3Mode.PERSISTENCE)
        .dataPath(dataPath.toString())
        .credentials("local", "local-secret")
        .buckets("bucket")
        .netty(netty -> netty.requestBodyFileThreshold(1024))
        .build();
    localS3.start();
    try (S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local-secret")))
        .forcePathStyle(true)
        .build()) {
      byte[] content = randomBytes(3 * 1024 * 1024 + 17);
      PutObjectResponse put = s3.putObject(b -> b.bucket("bucket").key("object")
          .checksumAlgorithm(ChecksumAlgorithm.CRC32), RequestBody.fromBytes(content));
      assertNotNull(put.checksumCRC32());
      assertArrayEquals(content, s3.getObjectAsBytes(b -> b.bucket("bucket").key("object")).asByteArray());

      Path storageDirectory = dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY);
      assertEquals(1, countFiles(storageDirectory), "The object is stored once.");
      assertEquals(0, countFiles(storageDirectory.resolve(LocalS3Manager.REQUEST_BODY_DIRECTORY)),
          "The body file was renamed into place.");

      String uploadId = s3.createMultipartUpload(b -> b.bucket("bucket").key("parts")).uploadId();
      byte[] part = randomBytes(5 * 1024 * 1024 + 3);
      UploadPartResponse uploaded = s3.uploadPart(b -> b.bucket("bucket").key("parts").uploadId(uploadId)
          .partNumber(1), RequestBody.fromBytes(part));
      s3.completeMultipartUpload(b -> b.bucket("bucket").key("parts").uploadId(uploadId)
          .multipartUpload(m -> m.parts(CompletedPart.builder().partNumber(1).eTag(uploaded.eTag()).build())));
      assertArrayEquals(part, s3.getObjectAsBytes(b -> b.bucket("bucket").key("parts")).asByteArray());
      assertEquals(0, countFiles(storageDirectory.resolve(LocalS3Manager.REQUEST_BODY_DIRECTORY)));
    } finally {
      localS3.shutdown();
    }
  }

  private static long countFiles(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return 0;
    }
    try (Stream<Path> files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).count();
    }
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }

}
