package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Builder;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.KeyMetadata;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

/**
 * The KMS endpoint of LocalS3 wraps and unwraps the data keys of a client that encrypts objects itself, e.g. the
 * Amazon S3 Encryption Client, on the port that serves S3. Nothing is really encrypted; what the endpoint guarantees
 * is that a blob unwraps to the plaintext it was wrapped from, under the key ID and the encryption context it was
 * wrapped with.
 */
class KmsIntegrationTest {

  private static final String ACCESS_KEY_ID = "local-access-key";

  private static final String SECRET_ACCESS_KEY = "local-secret-access-key";

  private static final String KEY_ID = "1234abcd-12ab-34cd-56ef-1234567890ab";

  private final List<AutoCloseable> resources = new ArrayList<>();

  @AfterEach
  void close() throws Exception {
    for (AutoCloseable resource : resources.reversed()) {
      resource.close();
    }
  }

  /**
   * What an envelope encryption client does per object: wrap a fresh data key, keep the blob, and unwrap it to read
   * the object back.
   */
  @Test
  void aDataKeyIsUnwrappedToThePlaintextItWasWrappedFrom() {
    KmsClient kms = kms(start(ACCESS_KEY_ID, SECRET_ACCESS_KEY),
        AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
    Map<String, String> context = Map.of("bucket", "warehouse", "key", "a.parquet");

    GenerateDataKeyResponse dataKey = kms.generateDataKey(b -> b.keyId(KEY_ID)
        .keySpec(DataKeySpec.AES_256).encryptionContext(context));
    assertEquals("arn:aws:kms:us-east-1:000000000000:key/" + KEY_ID, dataKey.keyId());
    assertEquals(32, dataKey.plaintext().asByteArray().length);
    assertTrue(dataKey.ciphertextBlob().asByteArray().length > 0);

    DecryptResponse decrypted = kms.decrypt(b -> b.ciphertextBlob(dataKey.ciphertextBlob())
        .encryptionContext(context));
    assertArrayEquals(dataKey.plaintext().asByteArray(), decrypted.plaintext().asByteArray());
    assertEquals(dataKey.keyId(), decrypted.keyId());

    // Every data key is a fresh one, so two objects never share a key.
    GenerateDataKeyResponse other = kms.generateDataKey(b -> b.keyId(KEY_ID)
        .keySpec(DataKeySpec.AES_256).encryptionContext(context));
    assertNotEquals(dataKey.plaintext(), other.plaintext());

    // The blob of a key that was wrapped without its plaintext unwraps all the same.
    SdkBytes blob = kms.generateDataKeyWithoutPlaintext(b -> b.keyId(KEY_ID).keySpec(DataKeySpec.AES_256))
        .ciphertextBlob();
    assertEquals(32, kms.decrypt(b -> b.ciphertextBlob(blob)).plaintext().asByteArray().length);
  }

  @Test
  void encryptAndDecryptRoundTripUnderTheSameKeyAndContext() {
    KmsClient kms = kms(start(ACCESS_KEY_ID, SECRET_ACCESS_KEY),
        AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
    SdkBytes plaintext = SdkBytes.fromUtf8String("a data key, or anything else short");

    SdkBytes blob = kms.encrypt(b -> b.keyId("alias/local").plaintext(plaintext)
        .encryptionContext(Map.of("purpose", "test"))).ciphertextBlob();
    DecryptResponse decrypted = kms.decrypt(b -> b.ciphertextBlob(blob)
        .keyId("alias/local").encryptionContext(Map.of("purpose", "test")));
    assertEquals(plaintext.asUtf8String(), decrypted.plaintext().asUtf8String());
    assertEquals("arn:aws:kms:us-east-1:000000000000:alias/local", decrypted.keyId());
    assertEquals("SYMMETRIC_DEFAULT", decrypted.encryptionAlgorithmAsString());
  }

  /**
   * A blob is bound to its key ID and encryption context, so a client that mixes them up is told, rather than handed
   * a plaintext that Amazon KMS would never have given it.
   */
  @Test
  void aBlobOfAnotherContextKeyOrServiceIsRejected() {
    KmsClient kms = kms(start(ACCESS_KEY_ID, SECRET_ACCESS_KEY),
        AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
    SdkBytes blob = kms.generateDataKey(b -> b.keyId(KEY_ID).keySpec(DataKeySpec.AES_256)
        .encryptionContext(Map.of("bucket", "warehouse"))).ciphertextBlob();

    assertKmsError("InvalidCiphertextException",
        () -> kms.decrypt(b -> b.ciphertextBlob(blob).encryptionContext(Map.of("bucket", "other"))));
    assertKmsError("InvalidCiphertextException", () -> kms.decrypt(b -> b.ciphertextBlob(blob)));
    assertKmsError("IncorrectKeyException", () -> kms.decrypt(b -> b.ciphertextBlob(blob).keyId("another-key")
        .encryptionContext(Map.of("bucket", "warehouse"))));
    assertKmsError("InvalidCiphertextException",
        () -> kms.decrypt(b -> b.ciphertextBlob(SdkBytes.fromUtf8String("not a blob of LocalS3"))));
  }

  /**
   * There are no keys to look up, so any key ID is described, which is what a client that resolves a key before it
   * sends {@code x-amz-server-side-encryption: aws:kms} needs.
   */
  @Test
  void everyKeyIsDescribed() {
    KmsClient kms = kms(start(ACCESS_KEY_ID, SECRET_ACCESS_KEY),
        AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
    KeyMetadata metadata = kms.describeKey(b -> b.keyId(KEY_ID)).keyMetadata();
    assertEquals(KEY_ID, metadata.keyId());
    assertEquals("arn:aws:kms:us-east-1:000000000000:key/" + KEY_ID, metadata.arn());
    assertEquals("000000000000", metadata.awsAccountId());
    assertTrue(metadata.enabled());
    assertEquals("Enabled", metadata.keyStateAsString());

    KeyMetadata ofAlias = kms.describeKey(b -> b.keyId("alias/local")).keyMetadata();
    assertEquals("arn:aws:kms:us-east-1:000000000000:alias/local", ofAlias.arn());
  }

  /**
   * The KMS endpoint shares the port with S3, and neither shadows the other.
   */
  @Test
  void kmsAndS3ShareThePort() {
    URI endpoint = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    AwsBasicCredentials credentials = AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    SdkBytes blob = kms(endpoint, credentials)
        .generateDataKey(b -> b.keyId(KEY_ID).keySpec(DataKeySpec.AES_256)).ciphertextBlob();

    S3Client s3 = s3(endpoint, credentials);
    s3.createBucket(b -> b.bucket("warehouse"));
    s3.putObject(b -> b.bucket("warehouse").key("wrapped.key"), RequestBody.fromBytes(blob.asByteArray()));
    assertEquals(1, s3.listObjectsV2(b -> b.bucket("warehouse")).contents().size());
    assertArrayEquals(blob.asByteArray(),
        s3.getObjectAsBytes(b -> b.bucket("warehouse").key("wrapped.key")).asByteArray());
  }

  /**
   * A KMS request is signed for the {@code kms} service, and answered with the errors of KMS rather than an
   * {@code <Error>} document of S3 when its signature is rejected. The temporary credentials of the STS endpoint
   * sign it too.
   */
  @Test
  void kmsRequestsAreSignedAndAnsweredWithTheErrorsOfKms() {
    URI endpoint = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    assertKmsError("UnrecognizedClientException",
        () -> kms(endpoint, AwsBasicCredentials.create("wrong", SECRET_ACCESS_KEY)).describeKey(b -> b.keyId(KEY_ID)));
    assertKmsError("InvalidSignatureException",
        () -> kms(endpoint, AwsBasicCredentials.create(ACCESS_KEY_ID, "wrong")).describeKey(b -> b.keyId(KEY_ID)));
    assertKmsError("ValidationException", () -> kms(endpoint,
        AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY)).generateDataKey(b -> b.keyId(KEY_ID)
            .keySpec(DataKeySpec.AES_256).numberOfBytes(32)));

    try (StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY))).build()) {
      Credentials session = sts.assumeRole(b -> b.roleArn("arn:aws:iam::123456789012:role/reader")
          .roleSessionName("spark")).credentials();
      assertTrue(kms(endpoint, AwsSessionCredentials.create(session.accessKeyId(), session.secretAccessKey(),
          session.sessionToken())).describeKey(b -> b.keyId(KEY_ID)).keyMetadata().enabled());
    }
  }

  /**
   * Without configured credentials, LocalS3 verifies no signature, and its KMS endpoint answers all the same.
   */
  @Test
  void theEndpointAnswersWithoutCredentials() {
    KmsClient kms = kms(start(null, null), AwsBasicCredentials.create("any", "any"));
    GenerateDataKeyResponse dataKey = kms.generateDataKey(b -> b.keyId(KEY_ID).keySpec(DataKeySpec.AES_256));
    assertArrayEquals(dataKey.plaintext().asByteArray(),
        kms.decrypt(b -> b.ciphertextBlob(dataKey.ciphertextBlob())).plaintext().asByteArray());
    assertFalse(kms.generateRandom(b -> b.numberOfBytes(16)).plaintext().asByteArray().length == 0);
  }

  private static void assertKmsError(String code, org.junit.jupiter.api.function.Executable executable) {
    KmsException exception = assertThrows(KmsException.class, executable);
    assertEquals(code, exception.awsErrorDetails().errorCode());
  }

  private URI start(String accessKeyId, String secretAccessKey) {
    LocalS3Builder builder = LocalS3.builder().port(-1);
    if (accessKeyId != null) {
      builder.credentials(accessKeyId, secretAccessKey);
    }
    LocalS3 localS3 = builder.build();
    localS3.start();
    resources.add(localS3::shutdown);
    return URI.create("http://localhost:" + localS3.getPort());
  }

  private KmsClient kms(URI endpoint, AwsCredentials credentials) {
    KmsClient client = KmsClient.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .build();
    resources.add(client);
    return client;
  }

  private S3Client s3(URI endpoint, AwsCredentials credentials) {
    S3Client client = S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .forcePathStyle(true)
        .build();
    resources.add(client);
    return client;
  }

}
