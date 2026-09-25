package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectAttributes;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedDirectoryDownload;
import software.amazon.awssdk.transfer.s3.model.CompletedDirectoryUpload;
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload;

/**
 * The CRT-based S3 client of the AWS SDK, {@code S3AsyncClient.crtBuilder()}, and the {@code S3TransferManager} on top
 * of it: the way that Java applications move large files. It splits an upload into parts that it sends concurrently,
 * a download into concurrent ranged GETs, and checks both with full-object CRC checksums.
 */
class CrtClientIntegrationTest {

  private static final String ACCESS_KEY = "crt";

  private static final String SECRET_KEY = "crt-secret";

  private static final String BUCKET = "crt-bucket";

  private static final long PART_SIZE = 5L * 1024 * 1024;

  /**
   * Seven and a half parts, so that the last one is a short one.
   */
  private static final int LARGE_SIZE = (int) (PART_SIZE * 15 / 2);

  @Test
  @LocalS3(accessKey = ACCESS_KEY, secretKey = SECRET_KEY, buckets = BUCKET)
  void uploadsAndDownloadsALargeFile(LocalS3Endpoint endpoint, S3Client s3, @TempDir Path dir) throws IOException {
    byte[] content = randomBytes(LARGE_SIZE, 1);
    Path source = Files.write(dir.resolve("source.bin"), content);
    Path target = dir.resolve("target.bin");

    try (S3AsyncClient crt = crtClient(endpoint);
         S3TransferManager transferManager = S3TransferManager.builder().s3Client(crt).build()) {
      CompletedFileUpload upload = transferManager.uploadFile(b -> b.source(source)
          .putObjectRequest(p -> p.bucket(BUCKET).key("large.bin"))).completionFuture().join();
      // The response of a multipart upload of the CRT client carries only the ETag of the object.
      assertTrue(upload.response().eTag().endsWith("-8\""), upload.response().eTag());

      transferManager.downloadFile(b -> b.destination(target)
          .getObjectRequest(g -> g.bucket(BUCKET).key("large.bin").checksumMode(ChecksumMode.ENABLED)))
          .completionFuture().join();
    }
    assertArrayEquals(content, Files.readAllBytes(target));

    // The object that the CRT client assembled is the multipart one that it looks like to any other client.
    HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("large.bin").checksumMode(ChecksumMode.ENABLED));
    assertEquals(LARGE_SIZE, head.contentLength());
    // By default the CRT client checks every part with a CRC32, which makes a composite checksum of the object.
    assertEquals(compositeCrc32(content), head.checksumCRC32());
    assertEquals(ChecksumType.COMPOSITE, head.checksumType());
    GetObjectAttributesResponse attributes = s3.getObjectAttributes(b -> b.bucket(BUCKET).key("large.bin")
        .objectAttributes(ObjectAttributes.OBJECT_PARTS, ObjectAttributes.OBJECT_SIZE));
    assertEquals(8, attributes.objectParts().totalPartsCount());
    assertEquals(LARGE_SIZE, attributes.objectSize());
  }

  @Test
  @LocalS3(accessKey = ACCESS_KEY, secretKey = SECRET_KEY, buckets = BUCKET)
  void uploadsALargeFileWithAFullObjectCrc64Nvme(LocalS3Endpoint endpoint, S3Client s3, @TempDir Path dir)
      throws IOException {
    byte[] content = randomBytes(LARGE_SIZE, 2);
    Path source = Files.write(dir.resolve("source.bin"), content);

    try (S3AsyncClient crt = crtClient(endpoint)) {
      crt.putObject(p -> p.bucket(BUCKET).key("large.bin").checksumAlgorithm(ChecksumAlgorithm.CRC64_NVME),
          AsyncRequestBody.fromFile(source)).join();

      // The whole object at once, which the CRT client verifies against the full-object checksum.
      byte[] read = crt.getObject(g -> g.bucket(BUCKET).key("large.bin").checksumMode(ChecksumMode.ENABLED),
          AsyncResponseTransformer.toBytes()).join().asByteArray();
      assertArrayEquals(content, read);

      // A range from the middle of a part to the middle of the next one.
      long start = PART_SIZE - 100;
      byte[] range = crt.getObject(g -> g.bucket(BUCKET).key("large.bin").range("bytes=" + start + "-" + (start + 199)),
          AsyncResponseTransformer.toBytes()).join().asByteArray();
      assertArrayEquals(Arrays.copyOfRange(content, (int) start, (int) start + 200), range);
    }

    HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("large.bin").checksumMode(ChecksumMode.ENABLED));
    assertEquals(Checksums.crc64Nvme(content), head.checksumCRC64NVME());
    assertEquals(ChecksumType.FULL_OBJECT, head.checksumType());
  }

  /**
   * A full-object CRC32 that the application computed itself, which the CRT client sends with the completion of the
   * multipart upload for the service to verify the assembled object against.
   */
  @Test
  @LocalS3(accessKey = ACCESS_KEY, secretKey = SECRET_KEY, buckets = BUCKET)
  void uploadsALargeFileWithAFullObjectCrc32(LocalS3Endpoint endpoint, S3Client s3, @TempDir Path dir)
      throws IOException {
    byte[] content = randomBytes(LARGE_SIZE, 5);
    Path source = Files.write(dir.resolve("source.bin"), content);

    try (S3AsyncClient crt = crtClient(endpoint)) {
      crt.putObject(p -> p.bucket(BUCKET).key("large.bin").checksumCRC32(Checksums.crc32(content)),
          AsyncRequestBody.fromFile(source)).join();
    }

    HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("large.bin").checksumMode(ChecksumMode.ENABLED));
    assertEquals(Checksums.crc32(content), head.checksumCRC32());
    assertEquals(ChecksumType.FULL_OBJECT, head.checksumType());
    assertEquals(8, s3.getObjectAttributes(b -> b.bucket(BUCKET).key("large.bin")
        .objectAttributes(ObjectAttributes.OBJECT_PARTS)).objectParts().totalPartsCount());
  }

  @Test
  @LocalS3(accessKey = ACCESS_KEY, secretKey = SECRET_KEY, buckets = BUCKET)
  void uploadsAndDownloadsADirectory(LocalS3Endpoint endpoint, S3Client s3, @TempDir Path dir) throws IOException {
    Path source = Files.createDirectories(dir.resolve("source"));
    Map<String, byte[]> files = new TreeMap<>();
    files.put("empty.txt", new byte[0]);
    files.put("small.txt", "Hello, CRT".getBytes());
    files.put("nested/medium.bin", randomBytes(64 * 1024, 3));
    files.put("nested/deeper/large.bin", randomBytes((int) (PART_SIZE * 2 + 1), 4));
    files.put("with space/名字.txt", "unicode".getBytes());
    for (Map.Entry<String, byte[]> file : files.entrySet()) {
      Path path = source.resolve(file.getKey());
      Files.createDirectories(path.getParent());
      Files.write(path, file.getValue());
    }
    Path target = dir.resolve("target");

    try (S3AsyncClient crt = crtClient(endpoint);
         S3TransferManager transferManager = S3TransferManager.builder().s3Client(crt).build()) {
      CompletedDirectoryUpload upload = transferManager.uploadDirectory(b -> b.source(source)
          .bucket(BUCKET).s3Prefix("backup")).completionFuture().join();
      assertTrue(upload.failedTransfers().isEmpty(), upload.failedTransfers().toString());

      CompletedDirectoryDownload download = transferManager.downloadDirectory(b -> b.destination(target)
          .bucket(BUCKET).listObjectsV2RequestTransformer(l -> l.prefix("backup/"))).completionFuture().join();
      assertTrue(download.failedTransfers().isEmpty(), download.failedTransfers().toString());
    }

    List<String> keys = s3.listObjectsV2Paginator(b -> b.bucket(BUCKET)).contents().stream()
        .map(S3Object::key).toList();
    assertEquals(files.keySet().stream().map(key -> "backup/" + key).toList(), keys);

    // The files are downloaded to the paths of their keys relative to the prefix.
    Map<String, byte[]> downloaded = new TreeMap<>();
    try (Stream<Path> paths = Files.walk(target)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        downloaded.put(target.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
      }
    }
    assertEquals(files.keySet(), downloaded.keySet());
    files.forEach((key, content) -> assertArrayEquals(content, downloaded.get(key), key));
  }

  private static S3AsyncClient crtClient(LocalS3Endpoint endpoint) {
    return S3AsyncClient.crtBuilder()
        .endpointOverride(URI.create(endpoint.endpoint()))
        .forcePathStyle(true)
        .region(Region.of(endpoint.region()))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .minimumPartSizeInBytes(PART_SIZE)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_SUPPORTED)
        .build();
  }

  /**
   * The CRC32 of the CRC32s of the parts of {@value #PART_SIZE} bytes, followed by the number of parts.
   */
  private static String compositeCrc32(byte[] content) {
    ByteArrayOutputStream partChecksums = new ByteArrayOutputStream();
    int parts = 0;
    for (int offset = 0; offset < content.length; offset += (int) PART_SIZE, parts++) {
      byte[] part = Arrays.copyOfRange(content, offset, (int) Math.min(content.length, offset + PART_SIZE));
      partChecksums.writeBytes(Checksums.decode(Checksums.crc32(part)));
    }
    return Checksums.crc32(partChecksums.toByteArray()) + "-" + parts;
  }

  private static byte[] randomBytes(int size, long seed) {
    byte[] bytes = new byte[size];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }

}
