package com.robothy.s3.testcontainers;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the LocalS3 Docker image with Testcontainers.
 *
 * <p>The container is configured with the environment variables of the image, so every setting of
 * {@code LocalS3Environment} is reachable, either through a method of this class or through
 * {@linkplain #withEnv(String, String)}.
 *
 * <pre>{@code
 * @Container
 * LocalS3Container localS3 = new LocalS3Container("latest")
 *     .withMode(LocalS3Container.Mode.IN_MEMORY)
 *     .withCredentials("local-s3", "local-s3-secret")
 *     .withBuckets("my-bucket");
 *
 * S3Client s3 = S3Client.builder()
 *     .endpointOverride(localS3.getEndpointUri())
 *     .region(Region.US_EAST_1)
 *     .credentialsProvider(StaticCredentialsProvider.create(
 *         AwsBasicCredentials.create(localS3.getAccessKey(), localS3.getSecretKey())))
 *     .forcePathStyle(true)
 *     .build();
 * }</pre>
 *
 * <p>By default Docker allocates the host port when the container starts, and {@linkplain #getPort()} returns it, so
 * that test classes which run at the same time can't pick the same port. {@linkplain #withHttpPort(int)} binds a port
 * of your own instead, for the cases that need a fixed one, e.g. a URL in a configuration file.
 */
public class LocalS3Container extends GenericContainer<LocalS3Container> {

  /**
   * The repository of the LocalS3 Docker image on Docker Hub.
   */
  public static final String IMAGE_NAME = "luofuxiang/local-s3";

  /**
   * The image that every image this container runs must declare itself compatible with.
   */
  public static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse(IMAGE_NAME);

  /**
   * The port that the image serves the S3 API on, inside the container.
   */
  public static final int S3_PORT = 29090;

  /*
   * The environment variables of the image, spelled as com.robothy.s3.rest.LocalS3Environment spells them. They are
   * repeated here rather than referenced, so that this module depends on Testcontainers alone and a test that runs the
   * image doesn't drag the server onto its classpath.
   */

  private static final String MODE = "LOCAL_S3_MODE";

  private static final String PERSISTENCE_POLICY = "LOCAL_S3_PERSISTENCE_POLICY";

  private static final String IN_MEMORY_MAX_BYTES = "LOCAL_S3_IN_MEMORY_MAX_BYTES";

  private static final String VIRTUAL_HOST_DOMAINS = "LOCAL_S3_VIRTUAL_HOST_DOMAINS";

  private static final String ICEBERG_CATALOG = "LOCAL_S3_ICEBERG_CATALOG";

  private static final String ICEBERG_WAREHOUSE = "LOCAL_S3_ICEBERG_WAREHOUSE";

  private static final String TLS_SELF_SIGNED = "LOCAL_S3_TLS_SELF_SIGNED";

  private static final String TLS_REQUIRED = "LOCAL_S3_TLS_REQUIRED";

  private static final String WEBSITE = "LOCAL_S3_WEBSITE";

  private static final String WEBSITE_ALL_BUCKETS = "LOCAL_S3_WEBSITE_ALL_BUCKETS";

  private static final String CORS_ALLOWED_ORIGINS = "LOCAL_S3_CORS_ALLOWED_ORIGINS";

  private static final String ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";

  private static final String SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";

  private static final String BUCKETS = "AWS_BUCKETS";

  /**
   * The host port of {@linkplain #withHttpPort(int)}, or {@code 0} while Docker allocates one.
   */
  private int fixedPort;

  private String accessKey;

  private String secretKey;

  /**
   * Whether the service serves HTTPS, which decides the scheme of {@linkplain #getEndpoint()}.
   */
  private boolean tls;

  /**
   * Construct a {@linkplain LocalS3Container} with specified {@linkplain DockerImageName}, e.g. an image of a private
   * registry, declared with {@code asCompatibleSubstituteFor(LocalS3Container.IMAGE_NAME)}.
   *
   * @param dockerImageName the docker image to run.
   */
  public LocalS3Container(DockerImageName dockerImageName) {
    super(dockerImageName);
    dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
    // Docker picks a free host port while it creates the container, which no other process can take in between.
    addExposedPort(S3_PORT);
    this.waitingFor(Wait.forLogMessage("^.{1,}LocalS3 started.\n$", 1));
  }

  /**
   * Construct a {@linkplain LocalS3Container} instance with specified tag.
   *
   * @param tag the Docker image tag.
   */
  public LocalS3Container(String tag) {
    this(DockerImageName.parse(IMAGE_NAME).withTag(tag));
  }

  /**
   * Bind the specified host port to the S3 port of the container, for a test that needs a port known in advance, e.g.
   * one that is named by a configuration file. The port must be free when the container starts, so two test classes
   * that ask for the same one can't run at the same time; leave it to Docker, which is the default, wherever the port
   * doesn't have to be a particular one.
   *
   * @param port host port.
   * @return this.
   * @throws IllegalArgumentException if the port isn't a TCP port.
   */
  public LocalS3Container withHttpPort(int port) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("\"" + port + "\" is not a TCP port; use 1 to 65535.");
    }
    this.fixedPort = port;
    // The container port gets exactly one host binding: a second one, from the exposed port that the constructor
    // added or from an earlier call of this method, would make getMappedPort() answer with the other port.
    setExposedPorts(new ArrayList<>());
    setPortBindings(new ArrayList<>());
    addFixedExposedPort(port, S3_PORT);
    return this;
  }

  /**
   * Let Docker allocate the host port, which is the default and what {@linkplain #getPort()} returns once the
   * container has started. Call it to undo a {@linkplain #withHttpPort(int)}.
   *
   * @return this.
   */
  public LocalS3Container withRandomHttpPort() {
    this.fixedPort = 0;
    setPortBindings(new ArrayList<>());
    setExposedPorts(new ArrayList<>(List.of(S3_PORT)));
    return this;
  }

  /**
   * Bind a host directory to LocalS3 data path. The service runs as a user of its own, so the directory has to be
   * writable by it; see the Docker image of LocalS3.
   *
   * @param path host path.
   * @return this.
   */
  public LocalS3Container withDataPath(String path) {
    // The two-argument overload is deprecated; a data directory is written to, so the mode is spelled out.
    return super.withFileSystemBind(requireText(path, "path"), "/data", BindMode.READ_WRITE);
  }

  /**
   * Bind a host directory to LocalS3 data path, e.g. the {@code @TempDir} of a test.
   *
   * @param path host path.
   * @return this.
   */
  public LocalS3Container withDataPath(Path path) {
    return withDataPath(path.toAbsolutePath().toString());
  }

  /**
   * Set the LocalS3 mode. i.e. Set the environment variable "LOCAL_S3_MODE" when starting a container.
   *
   * @param mode {@linkplain Mode}.
   * @return this.
   */
  public LocalS3Container withMode(Mode mode) {
    return super.withEnv(MODE, mode.name());
  }

  /**
   * When the changes of a {@code PERSISTENCE} service reach the disk.
   *
   * @param policy {@linkplain PersistencePolicy}.
   * @return this.
   */
  public LocalS3Container withPersistencePolicy(PersistencePolicy policy) {
    return super.withEnv(PERSISTENCE_POLICY, policy.name());
  }

  /**
   * The max heap that the content of an {@code IN_MEMORY} service takes, beyond which an upload is answered with
   * {@code 507 InsufficientStorage}; the default is half the max heap of the container.
   *
   * @param bytes a positive number of bytes with an optional {@code k}, {@code m} or {@code g} suffix, e.g.
   *     {@code 512m}.
   * @return this.
   */
  public LocalS3Container withInMemoryMaxBytes(String bytes) {
    return super.withEnv(IN_MEMORY_MAX_BYTES, requireText(bytes, "bytes"));
  }

  /**
   * Require the requests of the service to be signed with these credentials, which
   * {@linkplain #getAccessKey()} and {@linkplain #getSecretKey()} then return. Without them the service accepts
   * unsigned requests, and a client may sign with anything.
   *
   * @param accessKey the access key ID.
   * @param secretKey the secret access key.
   * @return this.
   */
  public LocalS3Container withCredentials(String accessKey, String secretKey) {
    this.accessKey = requireText(accessKey, "accessKey");
    this.secretKey = requireText(secretKey, "secretKey");
    return super.withEnv(ACCESS_KEY_ID, this.accessKey)
        .withEnv(SECRET_ACCESS_KEY, this.secretKey);
  }

  /**
   * The buckets to create when the service starts, so that a test doesn't have to.
   *
   * @param buckets the names of the buckets.
   * @return this.
   */
  public LocalS3Container withBuckets(String... buckets) {
    return super.withEnv(BUCKETS, join(buckets, "buckets"));
  }

  /**
   * Serve an Iceberg REST catalog under {@code /iceberg/v1} beside the S3 API; it is off by default.
   *
   * @param enabled whether to serve the catalog.
   * @return this.
   */
  public LocalS3Container withIcebergCatalog(boolean enabled) {
    return super.withEnv(ICEBERG_CATALOG, Boolean.toString(enabled));
  }

  /**
   * The warehouse of the Iceberg REST catalog, an {@code s3://} URI of a bucket of this service, e.g.
   * {@code s3://warehouse/}. Setting it turns the catalog on.
   *
   * @param warehouse the warehouse location.
   * @return this.
   */
  public LocalS3Container withIcebergWarehouse(String warehouse) {
    return super.withEnv(ICEBERG_WAREHOUSE, requireText(warehouse, "warehouse"));
  }

  /**
   * The base domains that virtual-hosted-style requests address a bucket under, e.g. {@code s3.local} for
   * {@code my-bucket.s3.local}.
   *
   * @param domains the base domains.
   * @return this.
   */
  public LocalS3Container withVirtualHostDomains(String... domains) {
    return super.withEnv(VIRTUAL_HOST_DOMAINS, join(domains, "domains"));
  }

  /**
   * Serve HTTPS with a certificate that the service generates when it starts, which makes
   * {@linkplain #getEndpoint()} an {@code https://} URL. The service keeps answering plain HTTP on the same port
   * unless {@linkplain #withTlsRequired(boolean)} says otherwise.
   *
   * <p>Nothing trusts the certificate, so a client either trusts it explicitly or skips the check. With no hosts
   * given it is issued for {@code localhost}, {@code 127.0.0.1} and {@code ::1}, which is what a container reached
   * from the host is; a client that verifies the host name and reaches Docker elsewhere, which
   * {@linkplain #getHost()} then reports, needs that host named here.
   *
   * @param hosts the hosts to issue the certificate for; none for the defaults.
   * @return this.
   */
  public LocalS3Container withSelfSignedTls(String... hosts) {
    this.tls = true;
    return super.withEnv(TLS_SELF_SIGNED, hosts.length == 0 ? "true" : join(hosts, "hosts"));
  }

  /**
   * Serve HTTPS alone, instead of answering HTTP and HTTPS on the same port. Without a certificate it has no effect.
   *
   * @param required whether a plain HTTP request is refused.
   * @return this.
   */
  public LocalS3Container withTlsRequired(boolean required) {
    if (required) {
      this.tls = true;
    }
    return super.withEnv(TLS_REQUIRED, Boolean.toString(required));
  }

  /**
   * Serve the public buckets as static websites to the requests that carry no credentials; it is on by default.
   *
   * @param enabled whether to serve them.
   * @return this.
   */
  public LocalS3Container withWebsite(boolean enabled) {
    return super.withEnv(WEBSITE, Boolean.toString(enabled));
  }

  /**
   * Serve <b>every</b> bucket as a static website, the private ones included; it is off by default.
   *
   * @param enabled whether to serve them all.
   * @return this.
   */
  public LocalS3Container withWebsiteAllBuckets(boolean enabled) {
    return super.withEnv(WEBSITE_ALL_BUCKETS, Boolean.toString(enabled));
  }

  /**
   * Allow the cross-origin requests of browsers from some origins by a default CORS rule, which applies to the buckets
   * that have no CORS configuration of their own; it is off by default. {@code *} allows every origin.
   *
   * @param origins the origins, e.g. {@code http://localhost:5173}.
   * @return this.
   */
  public LocalS3Container withCorsAllowedOrigins(String... origins) {
    return super.withEnv(CORS_ALLOWED_ORIGINS, String.join(",", origins));
  }

  /**
   * The host port that the S3 API of the container is reachable at: the one of
   * {@linkplain #withHttpPort(int)}, or the one Docker allocated for the running container.
   *
   * @return the host port.
   * @throws IllegalStateException if Docker allocates the port and the container hasn't started yet.
   */
  public int getPort() {
    if (fixedPort != 0) {
      return fixedPort;
    }
    if (!isRunning()) {
      throw new IllegalStateException("Docker allocates the host port when the container starts, so it is known"
          + " once it has: call getPort() after start(), or bind a port of your own with withHttpPort(int).");
    }
    return getMappedPort(S3_PORT);
  }

  /**
   * The URL that a client reaches the running container at, e.g. {@code http://localhost:32773}. It is an
   * {@code https://} URL if the service was configured to serve HTTPS.
   *
   * @return the endpoint URL, without a trailing {@code /}.
   * @throws IllegalStateException if Docker allocates the port and the container hasn't started yet.
   */
  public String getEndpoint() {
    return (tls ? "https://" : "http://") + getHost() + ":" + getPort();
  }

  /**
   * {@linkplain #getEndpoint()} as a {@linkplain URI}, which is what the AWS SDK's {@code endpointOverride} takes.
   *
   * @return the endpoint URI.
   * @throws IllegalStateException if Docker allocates the port and the container hasn't started yet.
   */
  public URI getEndpointUri() {
    return URI.create(getEndpoint());
  }

  /**
   * The URL that a client of the S3 Tables API reaches the running container at, which an {@code S3TablesClient} takes
   * as its {@code endpointOverride}.
   *
   * <p>It is {@linkplain #getEndpoint()} for a container with credentials and {@code <endpoint>/s3tables} for one
   * without, because that is what the service tells a request of the S3 Tables API from an Amazon S3 one by: the two
   * share their paths, a client with credentials signs for the {@code s3tables} service, and a client without them
   * signs nothing and so reaches the API by path instead.
   *
   * @return the endpoint URL of the S3 Tables API, without a trailing {@code /}.
   * @throws IllegalStateException if Docker allocates the port and the container hasn't started yet.
   */
  public String getS3TablesEndpoint() {
    return accessKey == null ? getEndpoint() + "/s3tables" : getEndpoint();
  }

  /**
   * {@linkplain #getS3TablesEndpoint()} as a {@linkplain URI}.
   *
   * @return the endpoint URI of the S3 Tables API.
   * @throws IllegalStateException if Docker allocates the port and the container hasn't started yet.
   */
  public URI getS3TablesEndpointUri() {
    return URI.create(getS3TablesEndpoint());
  }

  /**
   * The access key ID of {@linkplain #withCredentials(String, String)}.
   *
   * @return the access key ID; {@code null} if the service accepts unsigned requests.
   */
  public String getAccessKey() {
    return accessKey;
  }

  /**
   * The secret access key of {@linkplain #withCredentials(String, String)}.
   *
   * @return the secret access key; {@code null} if the service accepts unsigned requests.
   */
  public String getSecretKey() {
    return secretKey;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank.");
    }
    return value;
  }

  private static String join(String[] values, String name) {
    if (values == null || values.length == 0) {
      throw new IllegalArgumentException(name + " must not be empty.");
    }
    for (String value : values) {
      requireText(value, name);
    }
    return String.join(",", values);
  }

  /**
   * LocalS3 mode.
   */
  public enum Mode {

    /**
     * Keep the data in the data path, so that it survives a restart of the container.
     */
    PERSISTENCE,

    /**
     * Keep the data in memory, and start from the data path, if one is bound, without writing back to it.
     */
    IN_MEMORY

  }

  /**
   * When the changes of a {@code PERSISTENCE} service reach the disk.
   */
  public enum PersistencePolicy {

    /**
     * Commit every change, which survives a crash of the container.
     */
    DURABLE,

    /**
     * Commit in the background, at most a second later, which is quicker on a bulk load.
     */
    FAST

  }

}
