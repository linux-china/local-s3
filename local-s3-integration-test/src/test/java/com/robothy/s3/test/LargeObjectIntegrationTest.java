package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Uploads objects larger than 2 GiB in a single request, whose bodies no {@code ByteBuf} holds, to a service that
 * verifies signatures and persists its objects.
 *
 * <p>The content is a sparse file, so the test takes little disk space to prepare, but each upload still sends and
 * stores more than 2 GiB, which takes a while and as much free disk space. So the test only runs if the environment
 * variable {@value #ENABLING_VARIABLE} is {@code true}, e.g. {@code LOCAL_S3_LARGE_OBJECT_TESTS=true ./gradlew
 * :local-s3-integration-test:test --tests '*LargeObjectIntegrationTest'}.
 */
@EnabledIfEnvironmentVariable(named = LargeObjectIntegrationTest.ENABLING_VARIABLE, matches = "true")
class LargeObjectIntegrationTest {

  static final String ENABLING_VARIABLE = "LOCAL_S3_LARGE_OBJECT_TESTS";

  private static final String ACCESS_KEY_ID = "large";

  private static final String SECRET_ACCESS_KEY = "large-secret";

  private static final String BUCKET = "large-objects";

  /**
   * Just more than a {@code ByteBuf} holds.
   */
  private static final long SIZE = (1L << 31) + 1024;

  private static final byte[] TAIL = "the end of a large object".getBytes(StandardCharsets.US_ASCII);

  @TempDir
  static Path directory;

  private static Path content;

  private static String contentMd5;

  private LocalS3 localS3;

  private S3Client s3;

  @BeforeEach
  void setUp() throws IOException {
    if (content == null) {
      content = sparseContent(directory.resolve("content.bin"));
      contentMd5 = md5Hex(content);
    }
    localS3 = LocalS3.builder()
        .port(-1)
        .mode(LocalS3Mode.PERSISTENCE)
        .dataPath(Files.createTempDirectory(directory, "data").toString())
        .credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY)
        .build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(endpoint())
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials())
        .forcePathStyle(true)
        .build();
    s3.createBucket(b -> b.bucket(BUCKET));
  }

  @AfterEach
  void tearDown() {
    s3.close();
    localS3.shutdown();
  }

  /**
   * The AWS SDK sends the file {@code aws-chunked} encoded, with chunk signatures and a trailing checksum, which are
   * verified by reading the file that the body was buffered in.
   */
  @Test
  void putsAnObjectLargerThan2GiBWithSignedChunks() {
    s3.putObject(b -> b.bucket(BUCKET).key("chunked"), RequestBody.fromFile(content));

    assertStored("chunked");
  }

  /**
   * A presigned URL uploads the file as it is, which the storage then takes over by renaming the file that the body
   * was buffered in.
   */
  @Test
  void putsAnObjectLargerThan2GiBThroughAPresignedUrl() throws IOException, InterruptedException {
    URI url;
    try (S3Presigner presigner = S3Presigner.builder()
        .endpointOverride(endpoint())
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials())
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(true).build())
        .build()) {
      url = presigner.presignPutObject(b -> b.signatureDuration(Duration.ofMinutes(10))
          .putObjectRequest(r -> r.bucket(BUCKET).key("presigned"))).url().toURI();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }

    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder(url).PUT(HttpRequest.BodyPublishers.ofFile(content)).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
    }

    assertStored("presigned");
  }

  private void assertStored(String key) {
    HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key(key));
    assertEquals(SIZE, head.contentLength());
    assertEquals("\"" + contentMd5 + "\"", head.eTag());

    byte[] tail = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(key).range("bytes=-" + TAIL.length)).asByteArray();
    assertArrayEquals(TAIL, tail);
  }

  private URI endpoint() {
    return URI.create("http://127.0.0.1:" + localS3.getPort());
  }

  private static StaticCredentialsProvider credentials() {
    return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
  }

  /**
   * A file of {@value #SIZE} bytes, of zeros but for {@linkplain #TAIL} at its end, which the file system doesn't
   * allocate if it supports sparse files.
   */
  private static Path sparseContent(Path file) throws IOException {
    try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
      out.setLength(SIZE);
      out.seek(SIZE - TAIL.length);
      out.write(TAIL);
    }
    return file;
  }

  private static String md5Hex(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest md5 = MessageDigest.getInstance("MD5");
      byte[] buffer = new byte[1 << 20];
      int read;
      while ((read = in.read(buffer)) >= 0) {
        md5.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(md5.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

}
