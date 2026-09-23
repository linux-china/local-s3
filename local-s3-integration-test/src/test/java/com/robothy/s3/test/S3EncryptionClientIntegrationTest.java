package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.encryption.s3.S3EncryptionClient;
import software.amazon.encryption.s3.S3EncryptionClientException;
import software.amazon.encryption.s3.internal.InstructionFileConfig;
import software.amazon.encryption.s3.materials.KmsKeyring;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The <a href="https://github.com/aws/amazon-s3-encryption-client-java">Amazon S3 Encryption Client</a> against
 * LocalS3, end to end, which is what the {@linkplain KmsIntegrationTest KMS endpoint} exists for. One object takes the
 * whole chain: {@code GenerateDataKey} at the KMS endpoint, the client encrypting the object with that key,
 * {@code PutObject} of the ciphertext and of the wrapped key, and on the way back {@code GetObject} and a
 * {@code Decrypt} that unwraps the key again. LocalS3 serves both halves of it on one port, so the client runs
 * unchanged.
 *
 * <p>What this pins is that chain, not the encryption: the object really is encrypted, by the client, but under a data
 * key that the KMS endpoint of LocalS3 hands out and wraps in the clear. See {@code docs/semantics.md}.
 */
class S3EncryptionClientIntegrationTest {

  private static final String ACCESS_KEY_ID = "local-access-key";

  private static final String SECRET_ACCESS_KEY = "local-secret-access-key";

  private static final String KEY_ID = "1234abcd-12ab-34cd-56ef-1234567890ab";

  private static final String BUCKET = "warehouse";

  /**
   * The start of a ciphertext blob of the KMS endpoint of LocalS3, which the wrapped data key that the client stores
   * beside an object is one of.
   */
  private static final String BLOB_MAGIC = "LocalS3KMS1";

  private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

  private final List<AutoCloseable> resources = new ArrayList<>();

  private URI endpoint;

  private S3Client s3;

  @BeforeEach
  void start() {
    LocalS3 localS3 = LocalS3.builder().port(-1).credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY).build();
    localS3.start();
    resources.add(localS3::shutdown);
    endpoint = URI.create("http://localhost:" + localS3.getPort());
    s3 = s3();
    s3.createBucket(b -> b.bucket(BUCKET));
  }

  @AfterEach
  void close() throws Exception {
    for (AutoCloseable resource : resources.reversed()) {
      resource.close();
    }
  }

  /**
   * GenerateDataKey → encrypt → PutObject → GetObject → Decrypt, the whole chain: what the client reads back is the
   * plaintext it wrote, and what LocalS3 holds is the ciphertext and a data key wrapped by its KMS endpoint.
   */
  @Test
  void anObjectIsEncryptedByTheClientAndReadBackAsPlaintext() {
    S3Client encrypting = encrypting(KEY_ID);
    String plaintext = "the rows that only the client ever sees";
    encrypting.putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString(plaintext));

    assertEquals(plaintext, encrypting.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")).asUtf8String());

    // What LocalS3 stores is the ciphertext, not the object: the plaintext is nowhere in it, and it carries the
    // authentication tag of AES-GCM on top.
    ResponseBytes<GetObjectResponse> stored = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt"));
    byte[] ciphertext = stored.asByteArray();
    assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains("rows"));
    assertEquals(plaintext.length() + 16, ciphertext.length);

    // The data key of the object was wrapped by the KMS endpoint of LocalS3, and travels with the object as its
    // metadata. The name of the header is the client's business; that the value is a blob of LocalS3 is ours.
    assertTrue(wrappedDataKeyIn(stored.response().metadata()).isPresent(),
        "the object carries a data key wrapped by the KMS endpoint of LocalS3");
  }

  /**
   * The client streams a large object through the cipher, in parts that neither it nor LocalS3 holds whole, so the
   * round trip is worth pinning beyond the one buffer of a short object.
   */
  @Test
  void aLargeObjectRoundTrips() {
    byte[] plaintext = new byte[6 * 1024 * 1024 + 7];
    new Random(42).nextBytes(plaintext);
    S3Client encrypting = encrypting(KEY_ID);
    encrypting.putObject(b -> b.bucket(BUCKET).key("big.bin"), RequestBody.fromBytes(plaintext));

    assertArrayEquals(plaintext, encrypting.getObjectAsBytes(b -> b.bucket(BUCKET).key("big.bin")).asByteArray());
    assertEquals(plaintext.length + 16, s3.headObject(b -> b.bucket(BUCKET).key("big.bin")).contentLength());
  }

  /**
   * Nothing of the object lives in the client that wrote it: another one, with another KMS client, reads it back from
   * the wrapped key alone.
   */
  @Test
  void anotherClientWithTheSameKeyReadsTheObjectBack() {
    encrypting(KEY_ID).putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString("written once"));
    assertEquals("written once",
        encrypting(KEY_ID).getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")).asUtf8String());
  }

  /**
   * A blob of the KMS endpoint is bound to the key it was wrapped with, so a client configured with another wrapping
   * key is told rather than handed the object. It is the {@code IncorrectKeyException} of
   * {@linkplain KmsIntegrationTest} arriving where the client can see it.
   */
  @Test
  void aClientWithAnotherWrappingKeyCannotReadTheObject() {
    encrypting(KEY_ID).putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString("written once"));
    S3Client other = encrypting("another-key");
    S3EncryptionClientException exception = assertThrows(S3EncryptionClientException.class,
        () -> other.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")));
    assertTrue(exception.getMessage().contains("IncorrectKeyException")
            || exception.getMessage().contains("does not identify a CMK"),
        "unexpected message: " + exception.getMessage());
  }

  /**
   * With an instruction file the client keeps the wrapped key in a second object beside the first one instead of in
   * the metadata of the object, which is two more requests to LocalS3 per object.
   */
  @Test
  void anObjectWithItsWrappedKeyInAnInstructionFileRoundTrips() {
    S3Client encrypting = encrypting(KEY_ID, InstructionFileConfig.builder()
        .instructionFileClient(s3())
        .enableInstructionFilePutObject(true)
        .build());
    encrypting.putObject(b -> b.bucket(BUCKET).key("a.txt"), RequestBody.fromString("beside the object"));

    assertEquals("beside the object", encrypting.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")).asUtf8String());
    // The wrapped key moved out of the object and into the sidecar, which is a JSON document of the same entries.
    assertFalse(wrappedDataKeyIn(s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt")).response().metadata())
        .isPresent(), "the object itself carries no wrapped data key");
    ObjectNode instructionFile = (ObjectNode) JSON.readTree(
        s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("a.txt.instruction")).asUtf8String());
    Map<String, String> entries = new LinkedHashMap<>();
    instructionFile.properties().forEach(entry -> entries.put(entry.getKey(), entry.getValue().asString()));
    assertTrue(wrappedDataKeyIn(entries).isPresent(),
        "the instruction file holds a data key wrapped by the KMS endpoint of LocalS3");
  }

  /**
   * The wrapped data key among the entries that the client wrote, if one of them is a ciphertext blob of the KMS
   * endpoint of LocalS3. Which entry holds it, and what it is called, is the business of the format the client wrote;
   * that it came from the KMS endpoint is what this test is about.
   */
  private static Optional<String> wrappedDataKeyIn(Map<String, String> entries) {
    return entries.values().stream()
        .map(S3EncryptionClientIntegrationTest::decodeBase64)
        .filter(value -> value.startsWith(BLOB_MAGIC))
        .findFirst();
  }

  private static String decodeBase64(String value) {
    try {
      return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private S3Client encrypting(String keyId) {
    return encrypting(keyId, InstructionFileConfig.builder().disableInstructionFile(true).build());
  }

  /**
   * An Amazon S3 Encryption Client whose S3 and KMS calls both go to LocalS3.
   */
  private S3Client encrypting(String keyId, InstructionFileConfig instructionFile) {
    S3Client client = S3EncryptionClient.builderV4()
        .wrappedClient(s3())
        .wrappedAsyncClient(s3Async())
        .keyring(KmsKeyring.builder().kmsClient(kms()).wrappingKeyId(keyId).build())
        .instructionFileConfig(instructionFile)
        .build();
    resources.add(client);
    return client;
  }

  private KmsClient kms() {
    KmsClient client = KmsClient.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials())
        .build();
    resources.add(client);
    return client;
  }

  private S3Client s3() {
    S3Client client = S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials())
        .forcePathStyle(true)
        .build();
    resources.add(client);
    return client;
  }

  private S3AsyncClient s3Async() {
    S3AsyncClient client = S3AsyncClient.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials())
        .forcePathStyle(true)
        .build();
    resources.add(client);
    return client;
  }

  private static StaticCredentialsProvider credentials() {
    return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
  }

}
