package com.robothy.s3.rest;

import com.robothy.s3.core.event.S3ChangeListener;
import com.robothy.s3.core.storage.PersistencePolicy;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import com.robothy.s3.rest.netty.RequestRecorder;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * The immutable configuration of a {@linkplain LocalS3} service, which {@linkplain LocalS3Builder} builds. The
 * HTTP server and the services of a running LocalS3 are created from it alone.
 *
 * <p>The lists are copied, so a configuration doesn't change when the builder or the lists it was created from do.
 * The components are validated like the methods of {@linkplain LocalS3Builder} validate them.
 *
 * @param bindHost the host or IP address that the service listens on, e.g. {@code 127.0.0.1}.
 * @param port the port to bind; {@code 0} binds a random free port.
 * @param dataPath the data directory, or the initial data of an {@code IN_MEMORY} service; {@code null} for none.
 * @param mode whether the data is kept in memory or persisted to {@code dataPath}.
 * @param persistencePolicy when the changes of a {@code PERSISTENCE} service reach the disk; ignored by an
 *     {@code IN_MEMORY} service, which writes none.
 * @param buckets the buckets that are created when the service starts, and again when it is reset.
 * @param seeders put the initial buckets and objects into the service when it starts, and again when it is reset;
 *     empty for none.
 * @param changeListeners receive the changes that the services commit; empty for none.
 * @param changeListenerExecutor runs the change listeners.
 * @param initialDataCacheEnabled whether the initial data of an {@code IN_MEMORY} service is cached.
 * @param maxInMemoryBytes the max number of bytes of heap that the content stored by an {@code IN_MEMORY} service
 *     takes, positive; ignored by a {@code PERSISTENCE} service.
 * @param daemonThreads whether the threads that serve the requests are daemon threads.
 * @param registerShutdownHook whether starting the service registers a JVM shutdown hook.
 * @param nettyParentEventGroupThreadNum the number of threads that accept connections; {@code 0} for Netty's default.
 * @param nettyChildEventGroupThreadNum the number of threads that read and write the connections; {@code 0} for
 *     Netty's default.
 * @param s3ExecutorThreadNum the number of platform threads that handle the requests without virtual threads.
 * @param virtualThreads whether every request is handled on a virtual thread of its own.
 * @param accessKeyId the access key ID that requests must be signed with; {@code null} to accept unsigned requests.
 * @param secretAccessKey the secret access key of {@code accessKeyId}; {@code null} if it is {@code null}.
 * @param maxRequestBodySize the max size in bytes of a request body, between 1 and
 *     {@value #DEFAULT_MAX_REQUEST_BODY_SIZE}, i.e. 5 GiB, the largest object that Amazon S3 accepts in a single upload.
 * @param requestBodyFileThreshold the size in bytes above which a request body is buffered in a file, not negative.
 * @param maxRequestHeaderSize the max size in bytes of the header section of a request, positive.
 * @param idleConnectionTimeoutSeconds the seconds after which an idle connection is closed; {@code 0} for never.
 * @param compositeMultipartEtags whether a completed multipart upload gets the entity tag of Amazon S3.
 * @param virtualHostDomains the base domains of virtual-hosted-style requests, besides the default ones.
 * @param requestRecorder receives every request once its response is written, besides the statistics of the service,
 *     e.g. to record metrics; {@code null} is {@linkplain RequestRecorder#NONE}.
 * @param tls the certificate and private key that the service serves HTTPS with; {@code null} to serve plain HTTP.
 * @param tlsRequired whether a service with {@code tls} serves HTTPS only, rather than answering HTTP and HTTPS on the
 *     same port; ignored without {@code tls}.
 * @param icebergCatalog the Iceberg REST catalog that the service serves under {@code /iceberg/v1}; {@code null} to
 *     serve none, which is the default.
 * @param website how the buckets are served as static websites to the requests that carry no credentials;
 *     {@code null} is {@linkplain LocalS3Website#defaults()}, which serves the public buckets.
 */
public record LocalS3Config(
    String bindHost,
    int port,
    @Nullable Path dataPath,
    LocalS3Mode mode,
    PersistencePolicy persistencePolicy,
    List<String> buckets,
    List<LocalS3Seeder> seeders,
    List<S3ChangeListener> changeListeners,
    Executor changeListenerExecutor,
    boolean initialDataCacheEnabled,
    long maxInMemoryBytes,
    boolean daemonThreads,
    boolean registerShutdownHook,
    int nettyParentEventGroupThreadNum,
    int nettyChildEventGroupThreadNum,
    int s3ExecutorThreadNum,
    boolean virtualThreads,
    @Nullable String accessKeyId,
    @Nullable String secretAccessKey,
    long maxRequestBodySize,
    long requestBodyFileThreshold,
    int maxRequestHeaderSize,
    long idleConnectionTimeoutSeconds,
    boolean compositeMultipartEtags,
    List<String> virtualHostDomains,
    RequestRecorder requestRecorder,
    @Nullable LocalS3Tls tls,
    boolean tlsRequired,
    @Nullable LocalS3IcebergCatalog icebergCatalog,
    LocalS3Website website) {

  /**
   * Default and largest max request body size(5G), the largest object that Amazon S3 accepts in a single upload. A body
   * of more than 2 GiB is kept in a temporary file only, rather than memory-mapped.
   */
  public static final long DEFAULT_MAX_REQUEST_BODY_SIZE = 5L * 1024 * 1024 * 1024;

  /**
   * Default max number of bytes(half the max heap) that the content stored by an {@code IN_MEMORY} service takes, so
   * that uploading more than the heap holds is answered with {@code 507 InsufficientStorage} rather than taking the
   * JVM that embeds the service down with an {@code OutOfMemoryError}.
   */
  public static final long DEFAULT_MAX_IN_MEMORY_BYTES = Runtime.getRuntime().maxMemory() == Long.MAX_VALUE
      ? Long.MAX_VALUE : Runtime.getRuntime().maxMemory() / 2;

  /**
   * Default size(4M) above which a request body is buffered in a temporary file instead of the Java heap.
   */
  public static final long DEFAULT_REQUEST_BODY_FILE_THRESHOLD = 4 * 1024 * 1024;

  /**
   * Default max size(16K) of the header section of a request. Amazon S3 limits the headers of a PUT request to
   * 8 KB, but counts them differently than the HTTP codec does, so the default leaves room for a request that
   * Amazon S3 accepts, e.g. one whose signature and user-defined metadata take most of the 8 KB.
   */
  public static final int DEFAULT_MAX_REQUEST_HEADER_SIZE = 16 * 1024;

  /**
   * Default seconds(120) after which an idle keep-alive connection is closed. It is longer than the max
   * idle time of common S3 clients' connection pools, so clients usually close idle connections first.
   */
  public static final long DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS = 120;

  /**
   * Default number of threads that accept connections. One thread accepts as fast as a single listening
   * socket delivers, so this doesn't scale with the machine.
   */
  public static final int DEFAULT_NETTY_PARENT_EVENT_GROUP_THREAD_NUM = 1;

  /**
   * Default number of threads that read and write the connections: half the processors, and at least 2.
   * A connection is bound to one of them, and the body of a response is read from the storage on it, so a
   * machine with more processors serves more connections at once.
   */
  public static final int DEFAULT_NETTY_CHILD_EVENT_GROUP_THREAD_NUM =
      Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

  /**
   * Default number of platform threads that handle the requests when {@linkplain LocalS3Builder#virtualThreads(boolean)
   * virtual threads} are disabled: as many as the machine has processors, and at least 4. The threads are shared by
   * all connections, so this is the number of requests handled at the same time.
   */
  public static final int DEFAULT_S3_EXECUTOR_THREAD_NUM =
      Math.max(4, Runtime.getRuntime().availableProcessors());

  /**
   * Validate the components and copy the lists.
   *
   * @throws IllegalArgumentException if a component is out of range, or only one of the credentials is given.
   * @throws NullPointerException if a required component is {@code null}.
   */
  public LocalS3Config {
    Objects.requireNonNull(bindHost, "bindHost");
    requireThat(!bindHost.isBlank(), "bindHost must not be blank.");
    requireThat(port >= 0 && port <= 65535, "port must be between 0 and 65535.");
    Objects.requireNonNull(mode, "mode");
    buckets = List.copyOf(buckets);
    seeders = List.copyOf(seeders);
    changeListeners = List.copyOf(changeListeners);
    Objects.requireNonNull(changeListenerExecutor, "changeListenerExecutor");
    requireThat((accessKeyId == null) == (secretAccessKey == null),
        "accessKeyId and secretAccessKey must be configured together.");
    requireMaxInMemoryBytes(maxInMemoryBytes);
    requireMaxRequestBodySize(maxRequestBodySize);
    requireRequestBodyFileThreshold(requestBodyFileThreshold);
    requireMaxRequestHeaderSize(maxRequestHeaderSize);
    requireIdleConnectionTimeoutSeconds(idleConnectionTimeoutSeconds);
    virtualHostDomains = List.copyOf(virtualHostDomains);
    requestRecorder = requestRecorder == null ? RequestRecorder.NONE : requestRecorder;
    website = website == null ? LocalS3Website.defaults() : website;
  }

  /**
   * Whether requests must be signed with {@linkplain #accessKeyId()}.
   *
   * @return {@code true} if credentials are configured.
   */
  public boolean authenticationEnabled() {
    return accessKeyId != null;
  }

  /**
   * Whether other machines can reach the service, which {@linkplain #bindHost()} decides: a loopback address, e.g.
   * the default {@code 127.0.0.1}, is reachable from this machine alone, while the wildcard address {@code 0.0.0.0}
   * and the address of an interface are reachable from the network the machine is on. The Docker image binds the
   * wildcard address, since a container serves its host.
   *
   * <p>Together with {@linkplain #authenticationEnabled()} this says whether the data of the service is open to the
   * network; {@linkplain LocalS3#start()} warns when it is.
   *
   * <p>A bind host that doesn't resolve is reported as reachable. A service that binds one doesn't start at all, so
   * the answer only matters for a host that resolves, and the reachable answer is the one that warns rather than
   * stays quiet.
   *
   * @return {@code true} if the bind host isn't a loopback address.
   */
  public boolean reachableFromOtherHosts() {
    try {
      // The wildcard addresses, 0.0.0.0 and ::, aren't loopback addresses: they serve every interface.
      return !InetAddress.getByName(bindHost).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return true;
    }
  }

  /**
   * Whether the service serves HTTPS.
   *
   * @return {@code true} if a {@linkplain #tls() certificate and private key} are configured.
   */
  public boolean tlsEnabled() {
    return tls != null;
  }

  /**
   * Whether the service serves its buckets as static websites to the requests that carry no credentials.
   *
   * @return {@code true} if {@linkplain #website() static website hosting} is enabled.
   */
  public boolean websiteEnabled() {
    return website.enabled();
  }

  /**
   * Whether the service serves an Iceberg REST catalog beside its S3 API.
   *
   * @return {@code true} if a {@linkplain #icebergCatalog() catalog} is configured.
   */
  public boolean icebergCatalogEnabled() {
    return icebergCatalog != null;
  }

  /**
   * Whether the service answers plain HTTP requests: always without TLS, and with TLS unless
   * {@linkplain #tlsRequired()} is set, in which case the port serves HTTPS alone.
   *
   * @return {@code true} if a plain HTTP request is answered.
   */
  public boolean plainHttpAccepted() {
    return tls == null || !tlsRequired;
  }

  /**
   * Names the configuration without the secret access key, so that logging it doesn't reveal the key.
   */
  @Override
  public String toString() {
    return "LocalS3Config[bindHost=" + bindHost + ", port=" + port + ", dataPath=" + dataPath + ", mode=" + mode
        + ", buckets=" + buckets + ", initialDataCacheEnabled=" + initialDataCacheEnabled
        + ", maxInMemoryBytes=" + maxInMemoryBytes
        + ", daemonThreads=" + daemonThreads + ", registerShutdownHook=" + registerShutdownHook
        + ", nettyParentEventGroupThreadNum=" + nettyParentEventGroupThreadNum
        + ", nettyChildEventGroupThreadNum=" + nettyChildEventGroupThreadNum
        + ", s3ExecutorThreadNum=" + s3ExecutorThreadNum + ", virtualThreads=" + virtualThreads
        + ", accessKeyId=" + accessKeyId + ", secretAccessKey=" + (secretAccessKey == null ? null : "****")
        + ", maxRequestBodySize=" + maxRequestBodySize + ", requestBodyFileThreshold=" + requestBodyFileThreshold
        + ", maxRequestHeaderSize=" + maxRequestHeaderSize
        + ", idleConnectionTimeoutSeconds=" + idleConnectionTimeoutSeconds
        + ", compositeMultipartEtags=" + compositeMultipartEtags + ", virtualHostDomains=" + virtualHostDomains
        + ", tls=" + tlsEnabled() + ", tlsRequired=" + tlsRequired
        + ", icebergCatalog=" + icebergCatalog + ", website=" + website + "]";
  }

  /*
   * The checks that LocalS3Builder applies as soon as a value is set, so that a wrong value fails where it is set.
   */

  static void requireMaxInMemoryBytes(long maxInMemoryBytes) {
    requireThat(maxInMemoryBytes > 0, "maxInMemoryBytes must be positive.");
  }

  static void requireMaxRequestBodySize(long maxRequestBodySize) {
    requireThat(maxRequestBodySize > 0 && maxRequestBodySize <= DEFAULT_MAX_REQUEST_BODY_SIZE,
        "maxRequestBodySize must be between 1 and " + DEFAULT_MAX_REQUEST_BODY_SIZE + ".");
  }

  static void requireRequestBodyFileThreshold(long requestBodyFileThreshold) {
    requireThat(requestBodyFileThreshold >= 0, "requestBodyFileThreshold must not be negative.");
  }

  static void requireMaxRequestHeaderSize(int maxRequestHeaderSize) {
    requireThat(maxRequestHeaderSize > 0, "maxRequestHeaderSize must be positive.");
  }

  static void requireIdleConnectionTimeoutSeconds(long idleConnectionTimeoutSeconds) {
    requireThat(idleConnectionTimeoutSeconds >= 0, "idleConnectionTimeoutSeconds must not be negative.");
  }

  private static void requireThat(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

}
