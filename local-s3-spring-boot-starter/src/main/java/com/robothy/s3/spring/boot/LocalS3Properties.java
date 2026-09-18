package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.LocalS3IcebergCatalog;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The configuration of the LocalS3 service that a Spring Boot application embeds, under {@code local-s3.*}. Each
 * property maps to the option of {@linkplain com.robothy.s3.rest.LocalS3Builder} of the same name; the defaults are the
 * ones of an embedded service. A {@linkplain LocalS3BuilderCustomizer} sets the options that aren't properties.
 */
@ConfigurationProperties("local-s3")
public class LocalS3Properties {

  /**
   * Whether to embed a LocalS3 service in the application.
   */
  private boolean enabled = true;

  /**
   * Host or IP address that LocalS3 listens on; {@code 0.0.0.0} for every interface.
   */
  private String bindHost = "127.0.0.1";

  /**
   * Port that LocalS3 listens on; {@code 0} or {@code -1} for a random free port.
   */
  private int port = 29090;

  /**
   * Whether the data is kept in memory or persisted to the data path.
   */
  private LocalS3Mode mode = LocalS3Mode.IN_MEMORY;

  /**
   * Data directory in {@code PERSISTENCE} mode, or the initial data of an {@code IN_MEMORY} service.
   */
  private String dataPath;

  /**
   * Buckets to create when LocalS3 starts.
   */
  private List<String> buckets = new ArrayList<>();

  /**
   * Whether the initial data of an {@code IN_MEMORY} service is cached across services of the JVM.
   */
  private boolean initialDataCacheEnabled = true;

  /**
   * Whether the object of a completed multipart upload gets the entity tag of Amazon S3, with a -parts suffix.
   */
  private boolean compositeMultipartEtags = true;

  /**
   * Base domains of virtual-hosted-style requests besides the default ones, e.g. {@code s3.local}.
   */
  private List<String> virtualHostDomains = new ArrayList<>();

  private final InMemory inMemory = new InMemory();

  private final Credentials credentials = new Credentials();

  private final Threads threads = new Threads();

  private final Requests requests = new Requests();

  private final Seed seed = new Seed();

  private final Clients clients = new Clients();

  private final Events events = new Events();

  private final IcebergCatalog icebergCatalog = new IcebergCatalog();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBindHost() {
    return bindHost;
  }

  public void setBindHost(String bindHost) {
    this.bindHost = bindHost;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public LocalS3Mode getMode() {
    return mode;
  }

  public void setMode(LocalS3Mode mode) {
    this.mode = mode;
  }

  public String getDataPath() {
    return dataPath;
  }

  public void setDataPath(String dataPath) {
    this.dataPath = dataPath;
  }

  public List<String> getBuckets() {
    return buckets;
  }

  public void setBuckets(List<String> buckets) {
    this.buckets = buckets;
  }

  public boolean isInitialDataCacheEnabled() {
    return initialDataCacheEnabled;
  }

  public void setInitialDataCacheEnabled(boolean initialDataCacheEnabled) {
    this.initialDataCacheEnabled = initialDataCacheEnabled;
  }

  public IcebergCatalog getIcebergCatalog() {
    return icebergCatalog;
  }

  public boolean isCompositeMultipartEtags() {
    return compositeMultipartEtags;
  }

  public void setCompositeMultipartEtags(boolean compositeMultipartEtags) {
    this.compositeMultipartEtags = compositeMultipartEtags;
  }

  public List<String> getVirtualHostDomains() {
    return virtualHostDomains;
  }

  public void setVirtualHostDomains(List<String> virtualHostDomains) {
    this.virtualHostDomains = virtualHostDomains;
  }

  public InMemory getInMemory() {
    return inMemory;
  }

  public Credentials getCredentials() {
    return credentials;
  }

  public Threads getThreads() {
    return threads;
  }

  public Requests getRequests() {
    return requests;
  }

  public Seed getSeed() {
    return seed;
  }

  public Clients getClients() {
    return clients;
  }

  public Events getEvents() {
    return events;
  }

  /**
   * The heap that an {@code IN_MEMORY} service takes.
   */
  public static class InMemory {

    /**
     * Max size of the content, i.e. the objects and parts, that an IN_MEMORY service stores in the heap; storing more
     * is answered with 507 InsufficientStorage. Defaults to half the max heap.
     */
    private DataSize maxSize;

    public DataSize getMaxSize() {
      return maxSize;
    }

    public void setMaxSize(DataSize maxSize) {
      this.maxSize = maxSize;
    }

  }

  /**
   * The static key pair that requests must be signed with; unsigned requests are accepted if it isn't set.
   */
  public static class Credentials {

    /**
     * Access key ID that requests must be signed with. Set together with the secret access key.
     */
    private String accessKeyId;

    /**
     * Secret access key that requests must be signed with. Set together with the access key ID.
     */
    private String secretAccessKey;

    public String getAccessKeyId() {
      return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
      this.accessKeyId = accessKeyId;
    }

    public String getSecretAccessKey() {
      return secretAccessKey;
    }

    public void setSecretAccessKey(String secretAccessKey) {
      this.secretAccessKey = secretAccessKey;
    }

  }

  /**
   * The threads that serve the requests.
   */
  public static class Threads {

    /**
     * Whether every request is handled on a virtual thread of its own, rather than on a pool of platform threads.
     */
    private boolean virtual = true;

    /**
     * Whether the threads that serve the requests are daemon threads.
     */
    private boolean daemon = true;

    /**
     * Number of threads that accept connections.
     */
    private int nettyParentEventGroup = LocalS3Config.DEFAULT_NETTY_PARENT_EVENT_GROUP_THREAD_NUM;

    /**
     * Number of threads that read and write the connections.
     */
    private int nettyChildEventGroup = LocalS3Config.DEFAULT_NETTY_CHILD_EVENT_GROUP_THREAD_NUM;

    /**
     * Number of platform threads that handle the requests when virtual threads are disabled.
     */
    private int executor = LocalS3Config.DEFAULT_S3_EXECUTOR_THREAD_NUM;

    public boolean isVirtual() {
      return virtual;
    }

    public void setVirtual(boolean virtual) {
      this.virtual = virtual;
    }

    public boolean isDaemon() {
      return daemon;
    }

    public void setDaemon(boolean daemon) {
      this.daemon = daemon;
    }

    public int getNettyParentEventGroup() {
      return nettyParentEventGroup;
    }

    public void setNettyParentEventGroup(int nettyParentEventGroup) {
      this.nettyParentEventGroup = nettyParentEventGroup;
    }

    public int getNettyChildEventGroup() {
      return nettyChildEventGroup;
    }

    public void setNettyChildEventGroup(int nettyChildEventGroup) {
      this.nettyChildEventGroup = nettyChildEventGroup;
    }

    public int getExecutor() {
      return executor;
    }

    public void setExecutor(int executor) {
      this.executor = executor;
    }

  }

  /**
   * The limits of the requests and their connections.
   */
  public static class Requests {

    /**
     * Max size of a request body, at most 2GB - 1 byte.
     */
    private DataSize maxBodySize = DataSize.ofBytes(LocalS3Config.DEFAULT_MAX_REQUEST_BODY_SIZE);

    /**
     * Size above which a request body is buffered in a temporary file instead of the heap.
     */
    private DataSize bodyFileThreshold = DataSize.ofBytes(LocalS3Config.DEFAULT_REQUEST_BODY_FILE_THRESHOLD);

    /**
     * Max size of the header section of a request.
     */
    private DataSize maxHeaderSize = DataSize.ofBytes(LocalS3Config.DEFAULT_MAX_REQUEST_HEADER_SIZE);

    /**
     * Time after which a connection without reads or writes is closed; {@code 0} never closes it.
     */
    private Duration idleConnectionTimeout = Duration.ofSeconds(LocalS3Config.DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS);

    public DataSize getMaxBodySize() {
      return maxBodySize;
    }

    public void setMaxBodySize(DataSize maxBodySize) {
      this.maxBodySize = maxBodySize;
    }

    public DataSize getBodyFileThreshold() {
      return bodyFileThreshold;
    }

    public void setBodyFileThreshold(DataSize bodyFileThreshold) {
      this.bodyFileThreshold = bodyFileThreshold;
    }

    public DataSize getMaxHeaderSize() {
      return maxHeaderSize;
    }

    public void setMaxHeaderSize(DataSize maxHeaderSize) {
      this.maxHeaderSize = maxHeaderSize;
    }

    public Duration getIdleConnectionTimeout() {
      return idleConnectionTimeout;
    }

    public void setIdleConnectionTimeout(Duration idleConnectionTimeout) {
      this.idleConnectionTimeout = idleConnectionTimeout;
    }

  }

  /**
   * The objects that the service starts with, besides the empty {@linkplain LocalS3Properties#getBuckets() buckets}.
   */
  public static class Seed {

    /**
     * Whether to seed the service, if a classpath location is set.
     */
    private boolean enabled = true;

    /**
     * Classpath location of a directory tree that is put into the service as {@code <bucket>/<key>}, e.g.
     * {@code s3-fixtures}, whose {@code s3-fixtures/uploads/a.txt} becomes the object {@code a.txt} of the bucket
     * {@code uploads}. Seeded when the service starts and after each reset; unset seeds nothing.
     */
    private String classpath;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getClasspath() {
      return classpath;
    }

    public void setClasspath(String classpath) {
      this.classpath = classpath;
    }

  }

  /**
   * The {@code S3Client}, {@code S3AsyncClient} and {@code S3Presigner} beans that point at the embedded service.
   */
  public static class Clients {

    /**
     * Whether to define the client beans, unless the application defines its own.
     */
    private boolean enabled = true;

    /**
     * Region that the clients sign their requests for.
     */
    private String region = "us-east-1";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getRegion() {
      return region;
    }

    public void setRegion(String region) {
      this.region = region;
    }

  }

  /**
   * The changes that LocalS3 commits, published as application events.
   */
  public static class Events {

    /**
     * Whether to publish the S3Change of LocalS3 to the application context.
     */
    private boolean enabled = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

  }


  /**
   * The Iceberg REST catalog that LocalS3 can serve beside its S3 API, under {@code /iceberg/v1} on the same port, so
   * that an application or a test of Apache Iceberg needs no catalog of its own. It is off unless
   * {@code local-s3.iceberg-catalog.enabled} is set.
   */
  public static class IcebergCatalog {

    /**
     * Whether to serve an Iceberg REST catalog at {@code /iceberg/v1}.
     */
    private boolean enabled = false;

    /**
     * Warehouse location of the catalog: an s3:// URI of a bucket of this service, which the tables created without
     * a location of their own are placed under.
     */
    private String warehouse = LocalS3IcebergCatalog.DEFAULT_WAREHOUSE;

    /**
     * Whether to create the warehouse bucket when LocalS3 starts, if it doesn't exist.
     */
    private boolean createWarehouseBucket = true;

    /**
     * Whether a loaded table carries the endpoint and the credentials of LocalS3, so that an engine configured with
     * the catalog URI alone reaches the storage too.
     */
    private boolean credentialVending = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getWarehouse() {
      return warehouse;
    }

    public void setWarehouse(String warehouse) {
      this.warehouse = warehouse;
    }

    public boolean isCreateWarehouseBucket() {
      return createWarehouseBucket;
    }

    public void setCreateWarehouseBucket(boolean createWarehouseBucket) {
      this.createWarehouseBucket = createWarehouseBucket;
    }

    public boolean isCredentialVending() {
      return credentialVending;
    }

    public void setCredentialVending(boolean credentialVending) {
      this.credentialVending = credentialVending;
    }
  }

}
