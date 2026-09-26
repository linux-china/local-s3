package com.robothy.s3.test;

import static com.robothy.s3.test.IcebergRemoteSigningIntegrationTest.count;
import static com.robothy.s3.test.IcebergRemoteSigningIntegrationTest.start;
import static com.robothy.s3.test.IcebergRemoteSigningIntegrationTest.writeAndReadBack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.StorageCredential;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Lakekeeper, an Iceberg REST catalog, vends credentials for the tables of a warehouse on LocalS3, which it takes from
 * the STS endpoint of LocalS3 with {@code AssumeRole}:
 *
 * <pre>{@code
 * POST /management/v1/warehouse
 * {"storage-profile": {"type": "s3", "flavor": "s3-compat", "endpoint": "http://<LocalS3>", "sts-enabled": true,
 *                      "sts-role-arn": "arn:aws:iam::000000000000:role/lakekeeper", ...},
 *  "storage-credential": {"type": "s3", "credential-type": "access-key", ...}}
 * }</pre>
 *
 * <p>The Iceberg client asks for {@code X-Iceberg-Access-Delegation: vended-credentials} and is given no keys of its
 * own: it writes and reads the files of a table with the temporary credentials that Lakekeeper hands it. LocalS3
 * verifies signatures, so a table that is written and read back is proof that they are accepted.
 *
 * <p>Lakekeeper and its PostgreSQL run in containers, which reach LocalS3 on the host through
 * {@code host.testcontainers.internal}; without Docker, the tests are skipped.
 */
@Tag("data-tools")
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LakekeeperStsIntegrationTest {

  private static final DockerImageName LAKEKEEPER = DockerImageName.parse("quay.io/lakekeeper/catalog:v0.13.6");

  private static final String ACCESS_KEY_ID = "lakekeeper-key";

  private static final String SECRET_ACCESS_KEY = "lakekeeper-secret";

  private static final String BUCKET = "lakehouse";

  private static final String WAREHOUSE = "sts";

  private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/lakekeeper";

  /**
   * The host name that the containers reach the ports of {@link Testcontainers#exposeHostPorts} on.
   */
  private static final String HOST_FROM_CONTAINERS = "host.testcontainers.internal";

  /**
   * The properties that Lakekeeper configured the {@code FileIO} of the last loaded table with.
   */
  private static volatile Map<String, String> vendedProperties;

  private static LocalS3 localS3;

  private static Network network;

  private static GenericContainer<?> postgres;

  private static GenericContainer<?> lakekeeper;

  private static String catalogUri;

  @BeforeAll
  static void startLakekeeper() throws Exception {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available.");
    localS3 = start(LocalS3.builder().port(-1).buckets(BUCKET).credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
    Testcontainers.exposeHostPorts(localS3.getPort());

    network = Network.newNetwork();
    postgres = new GenericContainer<>(DockerImageName.parse("postgres:17-alpine"))
        .withNetwork(network)
        .withNetworkAliases("postgres")
        .withEnv("POSTGRES_PASSWORD", "postgres")
        .withEnv("POSTGRES_DB", "lakekeeper")
        // The server restarts once after the initialization of its data directory.
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    postgres.start();

    Map<String, String> environment = Map.of(
        "LAKEKEEPER__PG_DATABASE_URL_READ", "postgresql://postgres:postgres@postgres:5432/lakekeeper",
        "LAKEKEEPER__PG_DATABASE_URL_WRITE", "postgresql://postgres:postgres@postgres:5432/lakekeeper",
        "LAKEKEEPER__PG_ENCRYPTION_KEY", "local-s3-lakekeeper-test-key",
        "LAKEKEEPER__AUTHZ_BACKEND", "allowall");
    try (GenericContainer<?> migrate = new GenericContainer<>(LAKEKEEPER)
        .withNetwork(network)
        .withEnv(environment)
        .withCommand("migrate")
        .withStartupCheckStrategy(new OneShotStartupCheckStrategy())) {
      migrate.start();
    }
    lakekeeper = new GenericContainer<>(LAKEKEEPER)
        .withNetwork(network)
        .withEnv(environment)
        .withCommand("serve")
        .withExposedPorts(8181)
        .waitingFor(Wait.forHttp("/health").forPort(8181).forStatusCode(200));
    lakekeeper.start();

    String management = "http://" + lakekeeper.getHost() + ":" + lakekeeper.getMappedPort(8181) + "/management/v1";
    post(management + "/bootstrap", "{\"accept-terms-of-use\": true}");
    // Lakekeeper checks the profile when it creates the warehouse: it writes, reads and deletes a file with its own
    // key pair, and does the same with credentials that it gets with AssumeRole.
    post(management + "/warehouse", """
        {
          "warehouse-name": "%s",
          "storage-profile": {
            "type": "s3",
            "flavor": "s3-compat",
            "bucket": "%s",
            "key-prefix": "warehouse",
            "region": "us-east-1",
            "endpoint": "http://%s:%d",
            "path-style-access": true,
            "sts-enabled": true,
            "sts-role-arn": "%s",
            "sts-token-validity-seconds": 900,
            "remote-signing-enabled": false
          },
          "storage-credential": {
            "type": "s3",
            "credential-type": "access-key",
            "access-key-id": "%s",
            "secret-access-key": "%s"
          }
        }
        """.formatted(WAREHOUSE, BUCKET, HOST_FROM_CONTAINERS, localS3.getPort(), ROLE_ARN, ACCESS_KEY_ID,
        SECRET_ACCESS_KEY));
    catalogUri = "http://" + lakekeeper.getHost() + ":" + lakekeeper.getMappedPort(8181) + "/catalog";
  }

  @AfterAll
  static void stopLakekeeper() {
    if (lakekeeper != null) {
      lakekeeper.stop();
    }
    if (postgres != null) {
      postgres.stop();
    }
    if (network != null) {
      network.close();
    }
    if (localS3 != null) {
      localS3.shutdown();
    }
  }

  /**
   * The client writes a table and reads it back with nothing but the temporary credentials of {@code AssumeRole} that
   * Lakekeeper vends with the table, and the files land in the bucket of the warehouse.
   */
  @Test
  void aTableIsWrittenAndReadWithTheCredentialsThatLakekeeperVends() throws Exception {
    long assumedBefore = count(localS3, "AssumeRole");
    try (RESTCatalog catalog = new RESTCatalog()) {
      catalog.initialize("lakekeeper", new HashMap<>(Map.of(
          CatalogProperties.URI, catalogUri,
          CatalogProperties.WAREHOUSE_LOCATION, WAREHOUSE,
          CatalogProperties.FILE_IO_IMPL, HostS3FileIO.class.getName(),
          "header.X-Iceberg-Access-Delegation", "vended-credentials")));

      writeAndReadBack(catalog, TableIdentifier.of(Namespace.of("sales"), "orders"));
    }

    assertTrue(count(localS3, "AssumeRole") > assumedBefore, "Lakekeeper must take the credentials with AssumeRole.");
    assertEquals(0, clientAndServerErrors("AssumeRole"));
    // When it created the warehouse, Lakekeeper checked that credentials scoped to a table can't write next to it.
    assertTrue(clientAndServerErrors("PutObject") > 0, "The session policy must deny a write outside of the table.");

    Map<String, String> vended = vendedProperties;
    String accessKeyId = vended.get(S3FileIOProperties.ACCESS_KEY_ID);
    assertTrue(accessKeyId != null && accessKeyId.startsWith("ASIA"), "Temporary credentials must be vended: " + vended);
    assertNotEquals(ACCESS_KEY_ID, accessKeyId);
    assertNotEquals(SECRET_ACCESS_KEY, vended.get(S3FileIOProperties.SECRET_ACCESS_KEY));
    assertFalse(vended.getOrDefault(S3FileIOProperties.SESSION_TOKEN, "").isEmpty(), "No session token: " + vended);

    try (S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY)))
        .forcePathStyle(true)
        .build()) {
      List<String> keys = s3.listObjectsV2Paginator(b -> b.bucket(BUCKET).prefix("warehouse/")).contents().stream()
          .map(S3Object::key).toList();
      assertTrue(keys.stream().anyMatch(key -> key.endsWith(".parquet")), "No data file: " + keys);
      assertTrue(keys.stream().anyMatch(key -> key.endsWith(".metadata.json")), "No metadata file: " + keys);
    }
  }

  /**
   * The {@code S3FileIO} of the client, which reaches LocalS3 on the loopback address rather than on the host name of
   * the containers that Lakekeeper gives it as {@code s3.endpoint}, in the properties of the table and in the ones of
   * its storage credentials. Everything else, the credentials in particular, is taken as vended.
   */
  public static class HostS3FileIO extends S3FileIO {

    @Override
    public void initialize(Map<String, String> properties) {
      Map<String, String> rewritten = onHost(properties);
      Map<String, String> vended = new HashMap<>(rewritten);
      credentials().forEach(credential -> vended.putAll(credential.config()));
      vendedProperties = vended;
      super.initialize(rewritten);
    }

    @Override
    public void setCredentials(List<StorageCredential> credentials) {
      super.setCredentials(credentials.stream()
          .map(credential -> StorageCredential.create(credential.prefix(), onHost(credential.config())))
          .toList());
    }

    private static Map<String, String> onHost(Map<String, String> properties) {
      Map<String, String> rewritten = new HashMap<>(properties);
      rewritten.computeIfPresent(S3FileIOProperties.ENDPOINT,
          (key, endpoint) -> endpoint.replace(HOST_FROM_CONTAINERS, "127.0.0.1"));
      return rewritten;
    }

  }

  private static void post(String uri, String json) throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(uri))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertTrue(response.statusCode() / 100 == 2, uri + ": " + response.statusCode() + " " + response.body()
          + "\n" + lakekeeper.getLogs());
    }
  }

  private static long clientAndServerErrors(String operation) {
    var statistics = localS3.statistics().operations().get(operation);
    return statistics == null ? 0 : statistics.clientErrors() + statistics.serverErrors();
  }

}
