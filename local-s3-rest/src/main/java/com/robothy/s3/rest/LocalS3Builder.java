package com.robothy.s3.rest;

import com.robothy.s3.core.event.S3ChangeListener;
import com.robothy.s3.core.storage.PersistencePolicy;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import com.robothy.s3.rest.netty.RequestRecorder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@linkplain LocalS3} instances, and the {@linkplain LocalS3Config} they are created from. A builder can build
 * several instances; changing it afterwards doesn't affect the instances already built.
 *
 * <p>The settings a service is usually built with — its address, its mode and data directory, its buckets and its
 * credentials — are methods of the builder itself. The settings of a domain that only some services tune are grouped
 * behind one method per domain, which takes the settings of that domain and applies them:
 *
 * <pre>{@code
 *  LocalS3 s3 = LocalS3.builder()
 *      .port(29090)
 *      .mode(LocalS3Mode.PERSISTENCE)
 *      .dataPath("/tmp/local-s3")
 *      .credentials("local-s3", "local-s3-secret")
 *      .netty(netty -> netty.childEventGroupThreadNum(8).maxRequestBodySize(64 * 1024 * 1024))
 *      .tls(tls -> tls.selfSigned().required(true))
 *      .website(website -> website.allBuckets(true).indexDocument("home.html"))
 *      .icebergCatalog(iceberg -> iceberg.warehouse("s3://lakehouse/"))
 *      .build();
 * }</pre>
 *
 * <ul>
 *   <li>{@linkplain #storage(Consumer)} — the mode, the data directory and what the storage holds,
 *       {@linkplain StorageSettings};</li>
 *   <li>{@linkplain #netty(Consumer)} — the threads of the HTTP server, the limits of a request and the shutdown
 *       hook, {@linkplain NettySettings};</li>
 *   <li>{@linkplain #s3Api(Consumer)} — how the S3 API itself answers: the domains of virtual-hosted-style requests
 *       and the entity tags of multipart uploads, {@linkplain S3ApiSettings};</li>
 *   <li>{@linkplain #events(Consumer)} — the listeners of the committed changes and the executor that delivers them,
 *       {@linkplain EventSettings};</li>
 *   <li>{@linkplain #tls(Consumer)} — the certificate of HTTPS and whether HTTP is refused,
 *       {@linkplain TlsSettings};</li>
 *   <li>{@linkplain #website(Consumer)} — the static website hosting, {@linkplain WebsiteSettings};</li>
 *   <li>{@linkplain #defaultCors(Consumer)} — the CORS rule of the buckets without one of their own,
 *       {@linkplain CorsSettings};</li>
 *   <li>{@linkplain #icebergCatalog(Consumer)} — the Iceberg REST catalog, {@linkplain IcebergCatalogSettings}.</li>
 * </ul>
 *
 * <p>Each domain also has the one-liner that turns it on, e.g. {@linkplain #tls(LocalS3Tls)},
 * {@linkplain #website(boolean)} and {@linkplain #icebergCatalog(boolean)}, for a service that takes its defaults.
 */
public class LocalS3Builder {

    private static final Logger log = LoggerFactory.getLogger(LocalS3.class);

    /**
     * Create a builder with the defaults of an embedded service; {@linkplain LocalS3#builder()} is the usual way.
     */
    public LocalS3Builder() {
    }

    private String bindHost = "127.0.0.1";

    private int port = 29090;

    private Path dataPath;

    private LocalS3Mode mode = LocalS3Mode.IN_MEMORY;

    private PersistencePolicy persistencePolicy = PersistencePolicy.DURABLE;

    private final List<String> defaultBuckets = new ArrayList<>();

    private final List<String> versionedBuckets = new ArrayList<>();

    private final List<S3ChangeListener> changeListeners = new ArrayList<>();

    private final List<LocalS3Seeder> seeders = new ArrayList<>();

    private Executor changeListenerExecutor = Runnable::run;

    private boolean initialDataCacheEnabled = true;

    private long maxInMemoryBytes = LocalS3Config.DEFAULT_MAX_IN_MEMORY_BYTES;

    private boolean daemonThreads = true;

    private boolean registerShutdownHook = true;

    private int nettyParentEventGroupThreadNum = LocalS3Config.DEFAULT_NETTY_PARENT_EVENT_GROUP_THREAD_NUM;

    private int nettyChildEventGroupThreadNum = LocalS3Config.DEFAULT_NETTY_CHILD_EVENT_GROUP_THREAD_NUM;

    private int s3ExecutorThreadNum = LocalS3Config.DEFAULT_S3_EXECUTOR_THREAD_NUM;

    private boolean virtualThreads = true;

    private String accessKeyId;

    private String secretAccessKey;

    private long maxRequestBodySize = LocalS3Config.DEFAULT_MAX_REQUEST_BODY_SIZE;

    private long requestBodyFileThreshold = LocalS3Config.DEFAULT_REQUEST_BODY_FILE_THRESHOLD;

    private int maxRequestHeaderSize = LocalS3Config.DEFAULT_MAX_REQUEST_HEADER_SIZE;

    private long idleConnectionTimeoutSeconds = LocalS3Config.DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS;

    private boolean compositeMultipartEtags = true;

    private final List<String> virtualHostDomains = new ArrayList<>();

    private RequestRecorder requestRecorder = RequestRecorder.NONE;

    private LocalS3Tls tls;

    private boolean tlsRequired = false;

    private LocalS3IcebergCatalog icebergCatalog;

    private LocalS3Website website = LocalS3Website.defaults();

    private LocalS3Cors cors = LocalS3Cors.disabled();

    /**
     * Set the host that local-s3 service listens on.
     * The default value is {@code 127.0.0.1}, and local only,
     * and {@code 0.0.0.0} makes the service accessible through all network interfaces.
     *
     * @param bindHost host or IP address to bind.
     * @return builder.
     */
    public LocalS3Builder bindHost(@NonNull String bindHost) {
        if (bindHost.isBlank()) {
            throw new IllegalArgumentException("bindHost must not be blank.");
        }
        this.bindHost = bindHost;
        return this;
    }

    public LocalS3Builder acceptFromAnyHost() {
        this.bindHost = "0.0.0.0";
        return this;
    }

    /**
     * Set the port that local-s3 service listen to. Default port is 29090.
     * Set the value to {@code -1} or {@code 0} to bind a random free port, which
     * {@linkplain LocalS3#getPort()} returns once the service is started.
     *
     * @param port customized port.
     * @return builder.
     */
    public LocalS3Builder port(int port) {
        if (port > 65535) {
            throw new IllegalArgumentException("port must not be greater than 65535.");
        }
        // Binding port 0 lets the OS pick a free port, with no window for another process to take it.
        this.port = Math.max(port, 0);
        return this;
    }

    /**
     * Set the LocalS3 data directory. The default value is {@code null},
     * while data is stored in Java Heap.
     *
     * @param dataPath data path.
     * @return builder.
     */
    public LocalS3Builder dataPath(@NonNull String dataPath) {
        return storage(storage -> storage.dataPath(dataPath));
    }

    /**
     * Set LocalS3 service running mode. Default value is {@code IN_MEMORY}.
     *
     * @param mode LocalS3 service running mode.
     * @return builder.
     */
    public LocalS3Builder mode(@NonNull LocalS3Mode mode) {
        return storage(storage -> storage.mode(mode));
    }

    /**
     * The suffix of a bucket name given to {@linkplain #buckets(String...)} that creates the bucket with versioning
     * enabled, e.g. {@code audit:versioned}.
     */
    public static final String VERSIONED_SUFFIX = ":versioned";

    /**
     * Set default buckets. A name with the suffix {@value #VERSIONED_SUFFIX}, e.g. {@code audit:versioned}, is a
     * {@linkplain #versionedBuckets(String...) versioned bucket}, so that {@code AWS_BUCKETS=plain,audit:versioned}
     * creates both.
     *
     * @param buckets LocalS3 buckets
     * @return builder.
     */
    public LocalS3Builder buckets(String... buckets) {
        if (buckets != null) {
            for (String bucket : buckets) {
                // Tolerate lists like "a, b," as split from the AWS_BUCKETS environment variable.
                if (bucket != null && !bucket.isBlank()) {
                    String name = bucket.trim();
                    if (name.endsWith(VERSIONED_SUFFIX)) {
                        versionedBuckets(name.substring(0, name.length() - VERSIONED_SUFFIX.length()));
                    } else {
                        this.defaultBuckets.add(name);
                    }
                }
            }
        }
        return this;
    }

    /**
     * Set default buckets that are created with versioning enabled, like a production bucket that has versioning
     * enabled, without calling {@code PutBucketVersioning} in every test. An existing bucket, e.g. one loaded from the
     * data path, gets versioning enabled if its versioning was never configured; a suspended one stays suspended.
     *
     * @param buckets LocalS3 buckets with versioning enabled.
     * @return builder.
     */
    public LocalS3Builder versionedBuckets(String... buckets) {
        if (buckets != null) {
            for (String bucket : buckets) {
                if (bucket != null && !bucket.isBlank()) {
                    this.versionedBuckets.add(bucket.trim());
                }
            }
        }
        return this;
    }

    /**
     * Enable AWS Signature Version 4 authentication with a static access key pair.
     *
     * @param accessKeyId     access key ID accepted by the server.
     * @param secretAccessKey secret access key used to verify request signatures.
     * @return builder.
     */
    public LocalS3Builder credentials(@NonNull String accessKeyId, @NonNull String secretAccessKey) {
        if (accessKeyId.isBlank()) {
            throw new IllegalArgumentException("accessKeyId must not be blank.");
        }
        if (secretAccessKey.isBlank()) {
            throw new IllegalArgumentException("secretAccessKey must not be blank.");
        }
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        return this;
    }

    /**
     * Add a {@linkplain LocalS3Seeder seeder} that puts the initial buckets and objects of the service into it: the
     * fixtures that a test or a local run starts with. The seeders are applied in the order they were added, when the
     * service starts, after the {@linkplain #buckets(String...) default buckets} are created and before the server
     * accepts requests, and again after {@linkplain LocalS3#reset()}.
     *
     * @param seeder puts the initial buckets and objects into the service.
     * @return builder.
     */
    public LocalS3Builder seeder(@NonNull LocalS3Seeder seeder) {
        this.seeders.add(Objects.requireNonNull(seeder));
        return this;
    }

    /**
     * Subscribe a listener to the {@linkplain com.robothy.s3.core.event.S3Change changes} that the services commit:
     * buckets created and deleted, objects created and deleted, object tagging and ACLs changed, and multipart uploads
     * aborted. The changes are delivered however the services are called, by an HTTP request or directly through
     * {@linkplain LocalS3#getS3Manager()}. Several listeners may be subscribed; each receives every change.
     *
     * <p>{@linkplain #events(Consumer)} configures the listeners and the executor that delivers the changes to them
     * together.
     *
     * @param changeListener receives the committed changes.
     * @return builder.
     */
    public LocalS3Builder changeListener(@NonNull S3ChangeListener changeListener) {
        return events(events -> events.listener(changeListener));
    }

    /**
     * Configure the builder from the environment variables that the Docker image is configured with, read
     * from the environment or, if a variable isn't set there, from the system property of the same name.
     *
     * <p>Only the variables that are set are applied, so the caller keeps its own defaults for everything
     * else: a container applies its defaults, e.g. binding every interface, before calling this, while an
     * embedded service or a test keeps the defaults of the builder. The variables are
     * {@linkplain LocalS3Environment#LOCAL_S3_PORT}, {@linkplain LocalS3Environment#LOCAL_S3_HOST},
     * {@linkplain LocalS3Environment#LOCAL_S3_MODE},
     * {@linkplain LocalS3Environment#LOCAL_S3_DATA_PATH}, {@linkplain LocalS3Environment#LOCAL_S3_IN_MEMORY_MAX_BYTES},
     * {@linkplain LocalS3Environment#LOCAL_S3_VIRTUAL_THREADS},
     * {@linkplain LocalS3Environment#LOCAL_S3_COMPOSITE_MULTIPART_ETAGS},
     * {@linkplain LocalS3Environment#LOCAL_S3_VIRTUAL_HOST_DOMAINS}, {@linkplain LocalS3Environment#LOCAL_S3_TLS_CERT},
     * {@linkplain LocalS3Environment#LOCAL_S3_TLS_KEY}, {@linkplain LocalS3Environment#LOCAL_S3_TLS_REQUIRED},
     * {@linkplain LocalS3Environment#LOCAL_S3_ICEBERG_CATALOG},
     * {@linkplain LocalS3Environment#LOCAL_S3_ICEBERG_WAREHOUSE}, {@linkplain LocalS3Environment#AWS_BUCKETS},
     * {@linkplain LocalS3Environment#LOCAL_S3_ACCESS_KEY_ID} and {@linkplain LocalS3Environment#LOCAL_S3_SECRET_ACCESS_KEY}.
     * {@linkplain LocalS3Environment#AWS_ACCESS_KEY_ID} and {@linkplain LocalS3Environment#AWS_SECRET_ACCESS_KEY}, which
     * hold the client credentials of a developer, are read only where
     * {@linkplain LocalS3Environment#LOCAL_S3_CREDENTIALS_FROM_AWS_ENV} is {@code true}.
     *
     * @return builder.
     * @throws IllegalArgumentException if a variable has an invalid value.
     */
    public LocalS3Builder fromEnvironment() {
        return fromEnvironment(name -> Optional.ofNullable(System.getenv(name))
                .orElseGet(() -> System.getProperty(name)));
    }

    /**
     * Configure the builder from the variables that {@code variables} resolves by name, e.g. the entries of
     * a configuration file or of a map in a test. A variable that resolves to {@code null} or to a blank
     * value is not applied.
     *
     * @param variables resolves the value of a variable by name.
     * @return builder.
     * @throws IllegalArgumentException if a variable has an invalid value.
     */
    public LocalS3Builder fromEnvironment(@NonNull UnaryOperator<String> variables) {
        LocalS3Environment.applyTo(this, variables);
        return this;
    }

    /**
     * Configure the storage of the service: the mode it runs in, the data directory it keeps, when its changes reach
     * the disk and how much content it holds. The settings of this domain are grouped, rather than spread over the
     * builder, so that the knobs a service is rarely tuned with stay out of the way of the ones it is usually built
     * with:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .storage(storage -> storage.mode(LocalS3Mode.PERSISTENCE)
     *          .dataPath("/tmp/local-s3")
     *          .persistencePolicy(PersistencePolicy.FAST))
     *      .build();
     * }</pre>
     *
     * <p>The two a service is usually built with, {@linkplain #mode(LocalS3Mode)} and
     * {@linkplain #dataPath(String)}, are methods of the builder as well.
     *
     * <p>The settings object writes through to this builder, so it is applied as it is called; it must not be kept
     * beyond the call.
     *
     * @param storage configures the storage of the service.
     * @return builder.
     * @throws IllegalArgumentException if a setting has an invalid value.
     */
    public LocalS3Builder storage(@NonNull Consumer<StorageSettings> storage) {
        storage.accept(new StorageSettings());
        return this;
    }

    /**
     * Set when the changes of a {@code PERSISTENCE} service reach the disk; see
     * {@linkplain StorageSettings#persistencePolicy(PersistencePolicy)}.
     *
     * @param persistencePolicy when the changes reach the disk.
     * @return builder.
     * @deprecated use {@code storage(storage -> storage.persistencePolicy(...))}, which groups the settings of the
     *     storage.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder persistencePolicy(@NonNull PersistencePolicy persistencePolicy) {
        return storage(storage -> storage.persistencePolicy(persistencePolicy));
    }

    /**
     * Set the max number of bytes of heap that the content of an {@code IN_MEMORY} service takes; see
     * {@linkplain StorageSettings#maxInMemoryBytes(long)}.
     *
     * @param maxInMemoryBytes max number of bytes, positive.
     * @return builder.
     * @throws IllegalArgumentException if the number of bytes isn't positive.
     * @deprecated use {@code storage(storage -> storage.maxInMemoryBytes(...))}, which groups the settings of the
     *     storage.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder maxInMemoryBytes(long maxInMemoryBytes) {
        return storage(storage -> storage.maxInMemoryBytes(maxInMemoryBytes));
    }

    /**
     * Set whether the initial data read from the data path is cached in memory; see
     * {@linkplain StorageSettings#initialDataCacheEnabled(boolean)}.
     *
     * @param enabled is the initial data cache enabled.
     * @return builder.
     * @deprecated use {@code storage(storage -> storage.initialDataCacheEnabled(...))}, which groups the settings of
     *     the storage.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder initialDataCacheEnabled(boolean enabled) {
        return storage(storage -> storage.initialDataCacheEnabled(enabled));
    }

    /**
     * Configure the HTTP server: the threads that serve the requests, the limits that a request is held to, how the
     * server relates to the JVM it runs in, and the recorder of the requests it answered. The settings of this domain
     * are grouped, rather than spread over the builder, so that the knobs a service is rarely tuned with stay out of
     * the way of the ones it is usually built with:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .netty(netty -> netty.virtualThreads(false)
     *          .s3ExecutorThreadNum(16)
     *          .maxRequestBodySize(64 * 1024 * 1024)
     *          .idleConnectionTimeoutSeconds(30))
     *      .build();
     * }</pre>
     *
     * <p>The settings object writes through to this builder, so it is applied as it is called; it must not be kept
     * beyond the call.
     *
     * @param netty configures the HTTP server of the service.
     * @return builder.
     * @throws IllegalArgumentException if a setting has an invalid value.
     */
    public LocalS3Builder netty(@NonNull Consumer<NettySettings> netty) {
        netty.accept(new NettySettings());
        return this;
    }

    /**
     * Set the number of threads that accept connections; see
     * {@linkplain NettySettings#parentEventGroupThreadNum(int)}.
     *
     * @param nettyParentEventGroupThreadNum netty parent event group thread number.
     * @return builder.
     * @deprecated use {@code netty(netty -> netty.parentEventGroupThreadNum(...))}, which groups the settings of the
     *     HTTP server.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder nettyParentEventGroupThreadNum(int nettyParentEventGroupThreadNum) {
        return netty(netty -> netty.parentEventGroupThreadNum(nettyParentEventGroupThreadNum));
    }

    /**
     * Set the number of threads that read and write the connections; see
     * {@linkplain NettySettings#childEventGroupThreadNum(int)}.
     *
     * @param nettyChildEventGroupThreadNum netty child event group thread number.
     * @return builder.
     * @deprecated use {@code netty(netty -> netty.childEventGroupThreadNum(...))}, which groups the settings of the
     *     HTTP server.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder nettyChildEventGroupThreadNum(int nettyChildEventGroupThreadNum) {
        return netty(netty -> netty.childEventGroupThreadNum(nettyChildEventGroupThreadNum));
    }

    /**
     * Set the number of platform threads that handle the requests; see
     * {@linkplain NettySettings#s3ExecutorThreadNum(int)}.
     *
     * @param s3ExecutorThreadNum local-s3 executor thread number.
     * @return builder.
     * @deprecated use {@code netty(netty -> netty.s3ExecutorThreadNum(...))}, which groups the settings of the HTTP
     *     server.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder s3ExecutorThreadNum(int s3ExecutorThreadNum) {
        return netty(netty -> netty.s3ExecutorThreadNum(s3ExecutorThreadNum));
    }

    /**
     * Set whether {@linkplain LocalS3#start()} registers a JVM shutdown hook; see
     * {@linkplain NettySettings#registerShutdownHook(boolean)}.
     *
     * @param registerShutdownHook whether to register a JVM shutdown hook when the service starts.
     * @return builder.
     * @deprecated use {@code netty(netty -> netty.registerShutdownHook(...))}, which groups the settings of the HTTP
     *     server.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder registerShutdownHook(boolean registerShutdownHook) {
        return netty(netty -> netty.registerShutdownHook(registerShutdownHook));
    }

    /**
     * Set a recorder that receives every request that the service answered; see
     * {@linkplain NettySettings#requestRecorder(RequestRecorder)}.
     *
     * @param requestRecorder the recorder.
     * @return builder.
     * @deprecated use {@code netty(netty -> netty.requestRecorder(...))}, which groups the settings of the HTTP
     *     server.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder requestRecorder(@NonNull RequestRecorder requestRecorder) {
        return netty(netty -> netty.requestRecorder(requestRecorder));
    }

    /**
     * Configure how the S3 API itself answers: the domains that address a bucket by the host of a request, and the
     * entity tags that the objects of completed multipart uploads get. The settings of this domain are grouped, so
     * that the knobs a service is rarely tuned with stay out of the way of the ones it is usually built with:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .s3Api(s3 -> s3.virtualHostDomains("s3.local").compositeMultipartEtags(false))
     *      .build();
     * }</pre>
     *
     * <p>The settings object writes through to this builder, so it is applied as it is called; it must not be kept
     * beyond the call.
     *
     * @param s3Api configures the S3 API of the service.
     * @return builder.
     */
    public LocalS3Builder s3Api(@NonNull Consumer<S3ApiSettings> s3Api) {
        s3Api.accept(new S3ApiSettings());
        return this;
    }

    /**
     * Add base domains of virtual-hosted-style requests; see
     * {@linkplain S3ApiSettings#virtualHostDomains(String...)}.
     *
     * @param domains base domains, e.g. {@code s3} or {@code s3.local}.
     * @return builder.
     * @deprecated use {@code s3Api(s3 -> s3.virtualHostDomains(...))}, which groups the settings of the S3 API.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder virtualHostDomains(String... domains) {
        return s3Api(s3 -> s3.virtualHostDomains(domains));
    }

    /**
     * Set whether the object of a completed multipart upload gets the entity tag that Amazon S3 gives an object
     * uploaded in parts; see {@linkplain S3ApiSettings#compositeMultipartEtags(boolean)}.
     *
     * @param compositeMultipartEtags whether to give the objects of completed uploads the entity tag of
     *     Amazon S3.
     * @return builder.
     * @deprecated use {@code s3Api(s3 -> s3.compositeMultipartEtags(...))}, which groups the settings of the S3 API.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder compositeMultipartEtags(boolean compositeMultipartEtags) {
        return s3Api(s3 -> s3.compositeMultipartEtags(compositeMultipartEtags));
    }

    /**
     * Configure the {@linkplain com.robothy.s3.core.event.S3Change changes} that the service publishes: the listeners
     * that receive them, and the executor that delivers them. The settings of this domain are grouped:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .events(events -> events.listener(change -> log.info("{}", change))
     *          .executor(Executors.newSingleThreadExecutor()))
     *      .build();
     * }</pre>
     *
     * <p>{@linkplain #changeListener(S3ChangeListener)}, the one-liner that subscribes a single listener, is a method
     * of the builder as well.
     *
     * <p>The settings object writes through to this builder, so it is applied as it is called; it must not be kept
     * beyond the call.
     *
     * @param events configures the change listeners of the service.
     * @return builder.
     */
    public LocalS3Builder events(@NonNull Consumer<EventSettings> events) {
        events.accept(new EventSettings());
        return this;
    }

    /**
     * Set the executor that delivers the changes to the {@linkplain #changeListener change listeners}; see
     * {@linkplain EventSettings#executor(Executor)}.
     *
     * @param changeListenerExecutor executor that runs the change listeners.
     * @return builder.
     * @deprecated use {@code events(events -> events.executor(...))}, which groups the settings of the changes.
     */
    @Deprecated(since = "2.5.0")
    public LocalS3Builder changeListenerExecutor(@NonNull Executor changeListenerExecutor) {
        return events(events -> events.executor(changeListenerExecutor));
    }

    /**
     * Serve HTTPS with a certificate and its private key in PEM format. Clients that use HTTPS by default, e.g.
     * DuckDB, Hadoop S3A or the {@code object_store} crate, then connect without turning TLS off.
     *
     * <p>For local development, <a href="https://github.com/FiloSottile/mkcert">mkcert</a> creates a certificate that
     * the machine trusts: {@code mkcert -install} once, then {@code mkcert localhost 127.0.0.1} creates
     * {@code localhost+1.pem} and {@code localhost+1-key.pem}. A JVM client trusts it only once the CA of mkcert,
     * {@code $(mkcert -CAROOT)/rootCA.pem}, is in its trust store.
     *
     * <p>The port answers <b>both HTTP and HTTPS</b>: every connection is told apart by its first bytes, so a client
     * that speaks TLS and one that doesn't share the endpoint, and a test suite doesn't need two services to cover
     * both. {@code tls(tls -> tls.required(true))} serves HTTPS alone instead.
     *
     * <p>The files are read, and the certificate and key validated, when this method is called.
     *
     * @param certPem the certificate, optionally followed by its intermediate certificates: the path of a PEM file, or
     *     the PEM content itself.
     * @param keyPem the unencrypted PKCS#8 private key of the certificate, {@code -----BEGIN PRIVATE KEY-----}: the path
     *     of a PEM file, or the PEM content itself.
     * @return builder.
     * @throws IllegalArgumentException if a file can't be read, or the certificate and key are invalid.
     */
    public LocalS3Builder tls(@NonNull String certPem, @NonNull String keyPem) {
        this.tls = LocalS3Tls.of(certPem, keyPem);
        return this;
    }

    /**
     * Serve HTTPS instead of plain HTTP with the certificate and private key of PEM files; see
     * {@linkplain #tls(String, String)}.
     *
     * @param certPemFile the PEM file of the certificate chain.
     * @param keyPemFile the PEM file of the unencrypted PKCS#8 private key.
     * @return builder.
     * @throws IllegalArgumentException if a file can't be read, or the certificate and key are invalid.
     */
    public LocalS3Builder tls(@NonNull Path certPemFile, @NonNull Path keyPemFile) {
        return tls(certPemFile.toString(), keyPemFile.toString());
    }

    /**
     * Serve HTTPS instead of plain HTTP with a certificate and private key that the caller holds, e.g. one generated
     * for {@code localhost} where no certificate of the machine is at hand:
     *
     * <pre>{@code LocalS3.builder().tls(LocalS3Tls.selfSigned()).build()}</pre>
     *
     * <p>A client has to be given a {@linkplain LocalS3Tls#selfSigned() self-signed} certificate, which nothing trusts
     * by default; the service logs it in PEM format when it starts, and
     * {@linkplain LocalS3Tls#newClientSslContext()} trusts it in the JVM that embeds the service.
     *
     * @param tls the certificate and its private key; see {@linkplain #tls(String, String)}.
     * @return builder.
     */
    public LocalS3Builder tls(@NonNull LocalS3Tls tls) {
        this.tls = tls;
        return this;
    }

    /**
     * Configure HTTPS: the certificate the service serves, and whether it serves HTTPS alone. The settings of this
     * domain are grouped, so that a certificate and the way it is served are configured in one place:
     *
     * <pre>{@code
     *  LocalS3.builder().tls(tls -> tls.selfSigned().required(true)).build();
     *  LocalS3.builder().tls(tls -> tls.certificate(certPem, keyPem)).build();
     * }</pre>
     *
     * <p>The settings object writes through to this builder, so it is applied as it is called; it must not be kept
     * beyond the call.
     *
     * @param tls configures the HTTPS of the service.
     * @return builder.
     * @throws IllegalArgumentException if a certificate file can't be read, or the certificate and key are invalid.
     */
    public LocalS3Builder tls(@NonNull Consumer<TlsSettings> tls) {
        tls.accept(new TlsSettings());
        return this;
    }

    /**
     * Serve the buckets as static websites to the requests that carry no credentials, which is on by default: a
     * browser that opens {@code http://localhost:{port}/{bucket}/} gets the index document of the bucket, and a key
     * that isn't there gets its error document, while the signed requests of an S3 client keep their S3 semantics.
     *
     * <p>Only a bucket that was made public answers such a request, see
     * {@linkplain com.robothy.s3.core.util.BucketPublicAccess};
     * {@code website(website -> website.allBuckets(true))} serves every bucket instead.
     *
     * @param enabled {@code true} to serve static websites; {@code false} to leave every request to the S3 API.
     * @return builder.
     */
    public LocalS3Builder website(boolean enabled) {
        this.website = this.website.withEnabled(enabled);
        return this;
    }

    /**
     * Serve static websites with settings of your own; see {@linkplain #website(boolean)}. The settings of this domain
     * are grouped:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .website(website -> website.allBuckets(true).indexDocument("home.html").errorDocument("404.html"))
     *      .build();
     * }</pre>
     *
     * <p>The settings object writes through to this builder, so it is applied as it is called; it must not be kept
     * beyond the call.
     *
     * @param website configures the static website hosting of the service.
     * @return builder.
     * @throws IllegalArgumentException if the index document is blank.
     */
    public LocalS3Builder website(@NonNull Consumer<WebsiteSettings> website) {
        website.accept(new WebsiteSettings());
        return this;
    }

    /**
     * Allow the cross-origin requests of browsers by a default CORS rule, which applies where no CORS configuration of
     * a bucket does: to the buckets that weren't configured with {@code PutBucketCors}, and to the requests that
     * address no bucket, e.g. {@code ListBuckets} and the Iceberg REST catalog. A bucket that has a configuration of
     * its own keeps it, like in Amazon S3. It is off by default:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .defaultCors(cors -> cors.allowedOrigins("http://localhost:5173"))
     *      .build();
     * }</pre>
     *
     * <p>Unless told otherwise, the rule allows every method that a CORS rule of Amazon S3 may allow, every request
     * header, and exposes the response headers that the clients of a browser read, e.g. {@code ETag}; see
     * {@linkplain LocalS3Cors}. An allowed origin, and {@code *} above all, lets its pages reach the data of the
     * service from a browser, which is meant for local development.
     *
     * <p>The settings object writes through to this builder, so it is applied as it is called; it must not be kept
     * beyond the call.
     *
     * @param cors configures the default CORS rule of the service.
     * @return builder.
     * @throws IllegalArgumentException if a method isn't one that Amazon S3 allows, or a value is blank.
     */
    public LocalS3Builder defaultCors(@NonNull Consumer<CorsSettings> cors) {
        cors.accept(new CorsSettings());
        return this;
    }

    /**
     * Allow the cross-origin requests of browsers by a default CORS rule; see {@linkplain #defaultCors(Consumer)}.
     *
     * @param cors the default rule; {@code null} for none, which is the default.
     * @return builder.
     */
    public LocalS3Builder defaultCors(LocalS3Cors cors) {
        this.cors = Objects.requireNonNullElseGet(cors, LocalS3Cors::disabled);
        return this;
    }

    /**
     * Serve an <a href="https://iceberg.apache.org/spec/#rest-catalog">Iceberg REST catalog</a> beside the S3 API, on
     * the same port, under {@code /iceberg/v1}, so that a test of Apache Iceberg needs no catalog of its own:
     *
     * <pre>{@code
     *  LocalS3 s3 = LocalS3.builder().port(29090).icebergCatalog(true).build();
     *  s3.start();
     *
     *  RESTCatalog catalog = new RESTCatalog();
     *  catalog.initialize("local", Map.of("uri", "http://localhost:29090/iceberg"));
     *  catalog.createNamespace(Namespace.of("db"));
     * }</pre>
     *
     * <p>The tables are stored in LocalS3 itself, under the warehouse {@value LocalS3IcebergCatalog#DEFAULT_WAREHOUSE},
     * whose bucket is created when the service starts; {@linkplain #icebergCatalog(Consumer)} configures
     * that. An {@code IN_MEMORY} service therefore holds its tables in memory and a {@code PERSISTENCE} service keeps
     * them in its data directory, like everything else it stores.
     *
     * <p>The catalog is off by default: a service that doesn't ask for one carries neither its routes nor its state.
     *
     * @param enabled {@code true} to serve the catalog with the default settings; {@code false}, the default, to serve
     *     none.
     * @return builder.
     */
    public LocalS3Builder icebergCatalog(boolean enabled) {
        this.icebergCatalog = enabled ? LocalS3IcebergCatalog.enabled() : null;
        return this;
    }

    /**
     * Serve an Iceberg REST catalog with settings of your own, which also turns the catalog on; see
     * {@linkplain #icebergCatalog(boolean)}. The settings of this domain are grouped:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .icebergCatalog(iceberg -> iceberg.warehouse("s3://lakehouse/").credentialVending(false))
     *      .build();
     * }</pre>
     *
     * <p>A service that has no catalog yet gets one with the {@linkplain LocalS3IcebergCatalog#enabled() defaults}
     * before the settings are applied, since configuring a catalog is asking for one;
     * {@code icebergCatalog(iceberg -> iceberg.enabled(false))} serves none after all.
     *
     * <p>The settings object writes through to this builder, so it is applied as it is called; it must not be kept
     * beyond the call.
     *
     * @param iceberg configures the Iceberg REST catalog of the service.
     * @return builder.
     * @throws IllegalArgumentException if the warehouse isn't an {@code s3://} URI of a bucket.
     */
    public LocalS3Builder icebergCatalog(@NonNull Consumer<IcebergCatalogSettings> iceberg) {
        this.icebergCatalog = Objects.requireNonNullElseGet(this.icebergCatalog, LocalS3IcebergCatalog::enabled);
        iceberg.accept(new IcebergCatalogSettings());
        return this;
    }

    /**
     * Build the configuration of a {@linkplain LocalS3} service from the values set so far. Changing the builder
     * afterwards doesn't change the configuration.
     *
     * @return the configuration.
     */
    public LocalS3Config buildConfig() {
        return new LocalS3Config(bindHost, port, dataPath, mode, persistencePolicy, defaultBuckets, versionedBuckets, seeders,
                changeListeners,
                changeListenerExecutor, initialDataCacheEnabled, maxInMemoryBytes, daemonThreads, registerShutdownHook,
                nettyParentEventGroupThreadNum, nettyChildEventGroupThreadNum, s3ExecutorThreadNum, virtualThreads,
                accessKeyId, secretAccessKey, maxRequestBodySize, requestBodyFileThreshold, maxRequestHeaderSize,
                idleConnectionTimeoutSeconds, compositeMultipartEtags,
                virtualHostDomains, requestRecorder, tls, tlsRequired, icebergCatalog, website, cors);
    }

    /**
     * Build a {@linkplain LocalS3} instance.
     *
     * @return created {@linkplain LocalS3} instance.
     */
    public LocalS3 build() {
        LocalS3Config config = buildConfig();
        log.debug("Build LocalS3 on {}:{} in {} mode, data path: {}, authentication: {}, TLS: {}.", config.bindHost(),
                config.port(), config.mode(), config.dataPath(), config.authenticationEnabled() ? "enabled" : "disabled",
                config.tlsEnabled() ? "enabled" : "disabled");
        return new LocalS3(config);
    }

    /**
     * The storage settings of a service: the mode it runs in, the data directory it keeps, when its changes reach the
     * disk and how much content it holds. {@linkplain LocalS3Builder#storage(Consumer)} hands one out; every method
     * writes the setting through to the builder it came from, so a settings object is only good while that call runs.
     */
    public final class StorageSettings {

        private StorageSettings() {
        }

        /**
         * Set the running mode of the service. Default value is {@code IN_MEMORY}, which holds everything in the Java
         * heap; {@code PERSISTENCE} keeps it in the {@linkplain #dataPath(String) data directory}.
         *
         * @param mode LocalS3 service running mode.
         * @return these settings.
         */
        public StorageSettings mode(@NonNull LocalS3Mode mode) {
            LocalS3Builder.this.mode = mode;
            return this;
        }

        /**
         * Set the data directory of the service. The default value is {@code null}, while data is stored in the Java
         * heap. A {@code PERSISTENCE} service stores everything there; an {@code IN_MEMORY} service reads the
         * directory as the initial data it starts with.
         *
         * @param dataPath data path.
         * @return these settings.
         */
        public StorageSettings dataPath(@NonNull String dataPath) {
            return dataPath(Paths.get(dataPath));
        }

        /**
         * Set the data directory of the service; see {@linkplain #dataPath(String)}.
         *
         * @param dataPath data path.
         * @return these settings.
         */
        public StorageSettings dataPath(@NonNull Path dataPath) {
            LocalS3Builder.this.dataPath = dataPath;
            return this;
        }

        /**
         * Set when the changes of a {@code PERSISTENCE} service reach the disk.
         *
         * <p>{@linkplain PersistencePolicy#DURABLE}, the default, commits the metadata of every change before its
         * request is answered, so a process that is killed loses nothing. A commit writes the file without syncing it
         * to the disk, so it doesn't protect against a power loss. Every commit appends a chunk to the file of the data
         * directory; the requests that change a bucket at the same time share one, but a client that writes one object
         * after the other gets one each: loading twenty thousand objects that way grows the file to about 630 MB for
         * about 2 MB of metadata. The room is reclaimed while the service runs, once most of the file is unused, and
         * when the store is closed.
         *
         * <p>{@linkplain PersistencePolicy#FAST} lets the store commit in the background instead, at most a second
         * after a change, and commits what is left when the service is shut down. The same load then writes about 7 MB
         * and takes about half the time. A killed process loses the changes of the last second, which is the trade a
         * data directory built for a test, or the data of a service embedded in an application or an IDE, can usually
         * make; the Spring Boot starter defaults to it.
         *
         * <p>An {@code IN_MEMORY} service writes nothing, so the policy doesn't apply to it.
         *
         * @param persistencePolicy when the changes reach the disk.
         * @return these settings.
         */
        public StorageSettings persistencePolicy(@NonNull PersistencePolicy persistencePolicy) {
            LocalS3Builder.this.persistencePolicy = persistencePolicy;
            return this;
        }

        /**
         * Set the max number of bytes of heap that the content of an {@code IN_MEMORY} service takes: the objects and
         * the parts of multipart uploads stored in it. Storing content beyond the limit is answered with
         * {@code 507 InsufficientStorage}, whose message suggests the {@code PERSISTENCE} mode, instead of running the
         * JVM that embeds the service, e.g. an application or an IDE, out of heap. The space of deleted objects, and
         * of a {@linkplain LocalS3#reset() reset} service, is available again. The initial data read from the
         * {@linkplain #dataPath(String) data path} doesn't count; its copies are bounded by
         * {@code LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES}. A {@code PERSISTENCE} service ignores the limit.
         *
         * <p>Default value is {@linkplain LocalS3Config#DEFAULT_MAX_IN_MEMORY_BYTES}, i.e. half the max heap;
         * {@code Long.MAX_VALUE} for no limit.
         *
         * @param maxInMemoryBytes max number of bytes, positive.
         * @return these settings.
         * @throws IllegalArgumentException if the number of bytes isn't positive.
         */
        public StorageSettings maxInMemoryBytes(long maxInMemoryBytes) {
            LocalS3Config.requireMaxInMemoryBytes(maxInMemoryBytes);
            LocalS3Builder.this.maxInMemoryBytes = maxInMemoryBytes;
            return this;
        }

        /**
         * This option only available when running LocalS3 in {@code IN_MEMORY} mode
         * with initial data. If initial data cache is enabled, LocalS3 caches the
         * accessed initial data in memory. This could reduce disk I/O when running
         * tests with initial data in the same path.
         *
         * <p> The default value is {@code true}.
         *
         * @param enabled is the initial data cache enabled.
         * @return these settings.
         */
        public StorageSettings initialDataCacheEnabled(boolean enabled) {
            LocalS3Builder.this.initialDataCacheEnabled = enabled;
            return this;
        }

    }

    /**
     * The settings of the HTTP server of a service: the threads that serve the requests, the limits that a request is
     * held to, how the server relates to the JVM it runs in, and the recorder of the requests it answered.
     * {@linkplain LocalS3Builder#netty(Consumer)} hands one out; every method writes the setting through to
     * the builder it came from, so a settings object is only good while that call runs.
     */
    public final class NettySettings {

        private NettySettings() {
        }

        /**
         * Set the number of threads that accept connections. Default value is
         * {@linkplain LocalS3Config#DEFAULT_NETTY_PARENT_EVENT_GROUP_THREAD_NUM}.
         *
         * @param threadNum netty parent event group thread number.
         * @return these settings.
         */
        public NettySettings parentEventGroupThreadNum(int threadNum) {
            LocalS3Builder.this.nettyParentEventGroupThreadNum = threadNum;
            return this;
        }

        /**
         * Set the number of threads that read and write the connections. Netty binds a connection to one
         * thread of this group for its whole life, and the HTTP parsing of every connection bound to a thread
         * waits while that thread works. The body of a {@code GetObject} response is read from the storage on
         * this thread as it is written to the connection, so serving a large object holds it for a while;
         * raise this value to serve more connections at once. Default value is
         * {@linkplain LocalS3Config#DEFAULT_NETTY_CHILD_EVENT_GROUP_THREAD_NUM}.
         *
         * @param threadNum netty child event group thread number.
         * @return these settings.
         */
        public NettySettings childEventGroupThreadNum(int threadNum) {
            LocalS3Builder.this.nettyChildEventGroupThreadNum = threadNum;
            return this;
        }

        /**
         * Set the number of platform threads that handle the requests, where the S3 operations and their storage
         * I/O run, when {@linkplain #virtualThreads(boolean) virtual threads} are disabled.
         *
         * <p>The threads form a pool shared by all connections: each request is handled by a free thread, so
         * this is the number of requests handled at the same time. The requests of one connection are still
         * handled one after another, in the order they were received. The threads also write the request bodies
         * that are buffered in temporary files, see {@linkplain #requestBodyFileThreshold(long)}, one batch at a
         * time, so the event loops never wait for the disk. Default value is
         * {@linkplain LocalS3Config#DEFAULT_S3_EXECUTOR_THREAD_NUM}.
         *
         * @param threadNum local-s3 executor thread number.
         * @return these settings.
         */
        public NettySettings s3ExecutorThreadNum(int threadNum) {
            LocalS3Builder.this.s3ExecutorThreadNum = threadNum;
            return this;
        }

        /**
         * Set whether every request is handled on a virtual thread of its own, rather than on a pool of
         * {@linkplain #s3ExecutorThreadNum(int) platform threads}.
         *
         * <p>Handling a request mostly waits, for the storage and for the locks of a bucket, so a virtual thread per
         * request handles as many requests at once as there are connections, without a pool whose size depends on
         * the processors of the machine, e.g. a CI machine with two of them. The requests of one connection are still
         * handled one after another. Virtual threads are always daemon threads.
         *
         * <p>The default value is {@code true}.
         *
         * @param virtualThreads whether requests are handled on virtual threads.
         * @return these settings.
         */
        public NettySettings virtualThreads(boolean virtualThreads) {
            LocalS3Builder.this.virtualThreads = virtualThreads;
            return this;
        }

        /**
         * Set whether the threads that serve the requests are daemon threads.
         *
         * <p>The default value is {@code true}, so that a service that isn't {@linkplain LocalS3#shutdown() shut
         * down}, e.g. by a test that forgets to, doesn't keep the JVM alive; the shutdown hook stops the
         * service while the JVM exits. Set it to {@code false} to run LocalS3 as a standalone server, whose
         * {@code main} starts the service and returns: only non-daemon threads keep such a JVM running.
         *
         * @param daemonThreads whether the threads that serve the requests are daemon threads.
         * @return these settings.
         */
        public NettySettings daemonThreads(boolean daemonThreads) {
            LocalS3Builder.this.daemonThreads = daemonThreads;
            return this;
        }

        /**
         * Set the max size in bytes of a request body. Request bodies are held in memory, or buffered in a temporary
         * file above {@linkplain #requestBodyFileThreshold(long)}, which is memory-mapped up to 2 GiB, while a request
         * is handled, so this bounds the memory and the disk space a single request can take. A request exceeding the
         * limit is rejected with {@code EntityTooLarge} before its body is buffered; upload large objects with
         * multipart upload instead. Default value is {@linkplain LocalS3Config#DEFAULT_MAX_REQUEST_BODY_SIZE}.
         *
         * @param maxRequestBodySize max request body size in bytes, between 1 and
         *     {@linkplain LocalS3Config#DEFAULT_MAX_REQUEST_BODY_SIZE}, i.e. 5 GiB.
         * @return these settings.
         * @throws IllegalArgumentException if the size is outside that range.
         */
        public NettySettings maxRequestBodySize(long maxRequestBodySize) {
            LocalS3Config.requireMaxRequestBodySize(maxRequestBodySize);
            LocalS3Builder.this.maxRequestBodySize = maxRequestBodySize;
            return this;
        }

        /**
         * Set the size in bytes above which a request body is buffered in a temporary file instead of the
         * Java heap. The file is memory-mapped while the request is handled, or only read from the file if the body is
         * larger than 2 GiB, which no buffer holds, so large uploads take neither
         * heap memory nor a copy of the body. In {@code PERSISTENCE} mode the file is created in the storage
         * directory, and the body of an upload is stored by renaming the file, so that its content isn't written a
         * second time; an {@code aws-chunked} body, which the AWS SDKs send by default over plain HTTP, is decoded,
         * and its chunk signatures verified, while it is written, so the file holds the decoded content. In
         * {@code IN_MEMORY} mode the large body of a {@code PUT}, i.e. of {@code PutObject} and {@code UploadPart}, is
         * received into the heap instead, in chunks that the storage takes over, reserved in its budget before the body
         * is uploaded, so the upload neither touches the disk nor is held twice; the bodies of other requests, and
         * the ones whose length isn't declared or is larger than 2 GiB, are buffered in files of the default temporary
         * directory. The file is written on the request executor, not on
         * the event loop that receives the body; while a disk writes slower than a client sends, the connection isn't
         * read, so neither memory nor the other connections of the event loop are affected. Default value is
         * {@linkplain LocalS3Config#DEFAULT_REQUEST_BODY_FILE_THRESHOLD}; {@code Long.MAX_VALUE} buffers all
         * request bodies on the heap.
         *
         * @param requestBodyFileThreshold size in bytes, not negative.
         * @return these settings.
         * @throws IllegalArgumentException if the size is negative.
         */
        public NettySettings requestBodyFileThreshold(long requestBodyFileThreshold) {
            LocalS3Config.requireRequestBodyFileThreshold(requestBodyFileThreshold);
            LocalS3Builder.this.requestBodyFileThreshold = requestBodyFileThreshold;
            return this;
        }

        /**
         * Set the max size in bytes of the header section of a request, i.e. of all its header lines. A request
         * whose headers exceed it is answered with {@code 400 RequestHeaderSectionTooLarge}, and its connection is
         * closed. Default value is {@linkplain LocalS3Config#DEFAULT_MAX_REQUEST_HEADER_SIZE}.
         *
         * @param maxRequestHeaderSize max request header size in bytes, positive.
         * @return these settings.
         * @throws IllegalArgumentException if the size isn't positive.
         */
        public NettySettings maxRequestHeaderSize(int maxRequestHeaderSize) {
            LocalS3Config.requireMaxRequestHeaderSize(maxRequestHeaderSize);
            LocalS3Builder.this.maxRequestHeaderSize = maxRequestHeaderSize;
            return this;
        }

        /**
         * Set the seconds after which a connection without reads or writes is closed. A connection with a
         * request in flight is never closed. Default value is
         * {@linkplain LocalS3Config#DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS}; {@code 0} never closes idle
         * connections.
         *
         * @param idleConnectionTimeoutSeconds idle connection timeout in seconds, not negative.
         * @return these settings.
         * @throws IllegalArgumentException if the timeout is negative.
         */
        public NettySettings idleConnectionTimeoutSeconds(long idleConnectionTimeoutSeconds) {
            LocalS3Config.requireIdleConnectionTimeoutSeconds(idleConnectionTimeoutSeconds);
            LocalS3Builder.this.idleConnectionTimeoutSeconds = idleConnectionTimeoutSeconds;
            return this;
        }

        /**
         * Set whether {@linkplain LocalS3#start()} registers a JVM shutdown hook. The default is {@code true}.
         * Disable it when the LocalS3 lifecycle is managed by a host such as an IDE plugin or Spring container,
         * and ensure that the host calls {@linkplain LocalS3#shutdown()} or {@linkplain LocalS3#close()}.
         *
         * @param registerShutdownHook whether to register a JVM shutdown hook when the service starts.
         * @return these settings.
         */
        public NettySettings registerShutdownHook(boolean registerShutdownHook) {
            LocalS3Builder.this.registerShutdownHook = registerShutdownHook;
            return this;
        }

        /**
         * Set a recorder that receives every request that the service answered, once its response is written, e.g. to
         * record metrics of the requests. It receives the requests that {@code GET /_admin/stats} counts, and the
         * health checks and administration requests that it doesn't. It is called on the event loop of the
         * connection, so it must be quick and thread-safe; an exception that it throws is logged.
         *
         * @param requestRecorder the recorder.
         * @return these settings.
         */
        public NettySettings requestRecorder(@NonNull RequestRecorder requestRecorder) {
            LocalS3Builder.this.requestRecorder = Objects.requireNonNull(requestRecorder);
            return this;
        }

    }

    /**
     * The settings of the S3 API of a service: the domains that address a bucket by the host of a request, and the
     * entity tags that the objects of completed multipart uploads get.
     * {@linkplain LocalS3Builder#s3Api(Consumer)} hands one out; every method writes the setting through to the
     * builder it came from, so a settings object is only good while that call runs.
     */
    public final class S3ApiSettings {

        private S3ApiSettings() {
        }

        /**
         * Add base domains of virtual-hosted-style requests. With the domain {@code s3.local}, a request to the
         * host {@code my-bucket.s3.local} accesses the bucket {@code my-bucket}, while requests to {@code s3.local}
         * itself are path-style. This lets clients use virtual-hosted-style requests with a host name like the
         * service name in docker-compose. {@code localhost}, {@code 127.0.0.1} and {@code 0.0.0.0} are always
         * base domains; hosts of Amazon S3 ({@code amazonaws.com}), of Alibaba Cloud OSS ({@code aliyuncs.com},
         * e.g. {@code my-bucket.oss-cn-hangzhou.aliyuncs.com}) and of Cloudflare R2 ({@code r2.cloudflarestorage.com},
         * e.g. {@code my-bucket.<account-id>.r2.cloudflarestorage.com}) and of Tigris ({@code my-bucket.t3.storage.dev},
         * {@code my-bucket.fly.storage.tigris.dev}) are supported as well.
         *
         * @param domains base domains, e.g. {@code s3} or {@code s3.local}.
         * @return these settings.
         */
        public S3ApiSettings virtualHostDomains(String... domains) {
            if (domains != null) {
                for (String domain : domains) {
                    if (domain != null && !domain.isBlank()) {
                        LocalS3Builder.this.virtualHostDomains.add(domain.trim());
                    }
                }
            }
            return this;
        }

        /**
         * Set whether the object of a completed multipart upload gets the entity tag that Amazon S3 gives an
         * object uploaded in parts: the MD5 digest of the concatenated MD5 digests of its parts, followed by
         * {@code -} and the number of parts, e.g. {@code 3858f62230ac3c915f300c664312c11f-9}. The
         * {@code -<parts>} suffix is what a client reads the part layout of an object off, so code that tells
         * an object uploaded in parts from one uploaded at once, e.g. to decide whether the entity tag may be
         * compared with the MD5 of a local file, takes the same branch as against Amazon S3.
         *
         * <p>The default value is {@code true}. Pass {@code false} to give the object the MD5 digest of its
         * whole content instead, which is what LocalS3 gave it before 2.5, e.g. for a test that asserts that
         * entity tag.
         *
         * @param compositeMultipartEtags whether to give the objects of completed uploads the entity tag of
         *     Amazon S3.
         * @return these settings.
         */
        public S3ApiSettings compositeMultipartEtags(boolean compositeMultipartEtags) {
            LocalS3Builder.this.compositeMultipartEtags = compositeMultipartEtags;
            return this;
        }

    }

    /**
     * The settings of the {@linkplain com.robothy.s3.core.event.S3Change changes} that a service publishes: the
     * listeners that receive them, and the executor that delivers them.
     * {@linkplain LocalS3Builder#events(Consumer)} hands one out; every method writes the setting through to the
     * builder it came from, so a settings object is only good while that call runs.
     */
    public final class EventSettings {

        private EventSettings() {
        }

        /**
         * Subscribe a listener to the {@linkplain com.robothy.s3.core.event.S3Change changes} that the services
         * commit: buckets created and deleted, objects created and deleted, object tagging and ACLs changed, and
         * multipart uploads aborted. The changes are delivered however the services are called, by an HTTP request or
         * directly through {@linkplain LocalS3#getS3Manager()}. Several listeners may be subscribed; each receives
         * every change.
         *
         * @param listener receives the committed changes.
         * @return these settings.
         */
        public EventSettings listener(@NonNull S3ChangeListener listener) {
            LocalS3Builder.this.changeListeners.add(Objects.requireNonNull(listener));
            return this;
        }

        /**
         * Set the executor that delivers the changes to the {@linkplain #listener(S3ChangeListener) listeners}.
         *
         * <p>By default, listeners run synchronously on the thread handling the request, so a change is
         * delivered before the S3 response is sent. Pass an executor, e.g.
         * {@code Executors.newSingleThreadExecutor()}, to deliver changes asynchronously so that slow listeners
         * don't hold up request handling; a single-threaded executor keeps the changes in order. LocalS3 does not
         * shut the executor down.
         *
         * <p>Either way, an exception thrown by a listener is logged and does not fail the S3 request.
         *
         * @param executor executor that runs the change listeners.
         * @return these settings.
         */
        public EventSettings executor(@NonNull Executor executor) {
            LocalS3Builder.this.changeListenerExecutor = Objects.requireNonNull(executor);
            return this;
        }

    }

    /**
     * The HTTPS settings of a service: the certificate it serves, and whether it serves HTTPS alone.
     * {@linkplain LocalS3Builder#tls(Consumer)} hands one out; every method writes the setting through to the builder
     * it came from, so a settings object is only good while that call runs.
     */
    public final class TlsSettings {

        private TlsSettings() {
        }

        /**
         * Serve HTTPS with a certificate and its private key in PEM format, each given either as PEM content or as
         * the path of a PEM file; see {@linkplain LocalS3Builder#tls(String, String)} for where such a pair comes
         * from and what the port then answers.
         *
         * @param certPem the certificate chain, as PEM content or as the path of a PEM file.
         * @param keyPem the unencrypted PKCS#8 private key, as PEM content or as the path of a PEM file.
         * @return these settings.
         * @throws IllegalArgumentException if a file can't be read, or the certificate and key are invalid.
         */
        public TlsSettings certificate(@NonNull String certPem, @NonNull String keyPem) {
            LocalS3Builder.this.tls = LocalS3Tls.of(certPem, keyPem);
            return this;
        }

        /**
         * Serve HTTPS with the certificate and private key of PEM files; see
         * {@linkplain LocalS3Builder#tls(String, String)}.
         *
         * @param certPemFile the PEM file of the certificate chain.
         * @param keyPemFile the PEM file of the unencrypted PKCS#8 private key.
         * @return these settings.
         * @throws IllegalArgumentException if a file can't be read, or the certificate and key are invalid.
         */
        public TlsSettings certificate(@NonNull Path certPemFile, @NonNull Path keyPemFile) {
            return certificate(certPemFile.toString(), keyPemFile.toString());
        }

        /**
         * Serve HTTPS with a certificate and private key that the caller holds; see
         * {@linkplain LocalS3Builder#tls(LocalS3Tls)}.
         *
         * @param tls the certificate and its private key.
         * @return these settings.
         */
        public TlsSettings certificate(@NonNull LocalS3Tls tls) {
            LocalS3Builder.this.tls = tls;
            return this;
        }

        /**
         * Serve HTTPS with a certificate generated for {@code localhost}, {@code 127.0.0.1} and {@code ::1}, i.e.
         * {@code certificate(LocalS3Tls.selfSigned())}. A client that uses HTTPS by default then connects to a local
         * service without a certificate of the machine, once it is given the certificate or told not to verify it.
         *
         * @return these settings.
         * @throws IllegalStateException if the JVM generates neither an EC nor an RSA key pair.
         */
        public TlsSettings selfSigned() {
            return certificate(LocalS3Tls.selfSigned());
        }

        /**
         * Serve HTTPS with a certificate generated for the given host names and IP addresses, e.g. the name a
         * container is reached by; see {@linkplain LocalS3Tls#selfSigned(String...)}.
         *
         * @param hosts the host names and IP addresses to issue the certificate for, at least one.
         * @return these settings.
         * @throws IllegalArgumentException if {@code hosts} is empty, or a host is blank or an invalid IP address.
         * @throws IllegalStateException if the JVM generates neither an EC nor an RSA key pair.
         */
        public TlsSettings selfSigned(String... hosts) {
            return certificate(LocalS3Tls.selfSigned(hosts));
        }

        /**
         * Serve HTTPS alone on the port, instead of answering both HTTP and HTTPS on it. A plain HTTP request to the
         * service then fails, which is what a test asserts that its client really uses TLS with.
         *
         * <p>A service with a certificate accepts both by default, since a port that answers whatever a client speaks
         * is one less thing to configure. Without a certificate this has no effect: there is nothing to serve HTTPS
         * with.
         *
         * @param required {@code true} to refuse plain HTTP; {@code false}, the default, to answer HTTP and HTTPS on
         *     the same port.
         * @return these settings.
         */
        public TlsSettings required(boolean required) {
            LocalS3Builder.this.tlsRequired = required;
            return this;
        }

    }

    /**
     * The static website settings of a service; see {@linkplain LocalS3Website}.
     * {@linkplain LocalS3Builder#website(Consumer)} hands one out; every method writes the setting through to the
     * builder it came from, so a settings object is only good while that call runs.
     */
    public final class WebsiteSettings {

        private WebsiteSettings() {
        }

        /**
         * Set whether the buckets are served as static websites to the requests that carry no credentials; see
         * {@linkplain LocalS3Builder#website(boolean)}.
         *
         * @param enabled {@code true} to serve static websites; {@code false} to leave every request to the S3 API.
         * @return these settings.
         */
        public WebsiteSettings enabled(boolean enabled) {
            LocalS3Builder.this.website = LocalS3Builder.this.website.withEnabled(enabled);
            return this;
        }

        /**
         * Serve <b>every</b> bucket as a static website, rather than the public ones alone, which also lets an
         * unsigned request read the objects of a private bucket. It is meant for local development and tests, where
         * publishing a bucket to open a page in a browser is busywork, and is off by default.
         *
         * @param allBuckets {@code true} to serve every bucket without credentials.
         * @return these settings.
         */
        public WebsiteSettings allBuckets(boolean allBuckets) {
            LocalS3Builder.this.website = LocalS3Builder.this.website.withAllBuckets(allBuckets);
            return this;
        }

        /**
         * Set the index document of the buckets that have no {@code WebsiteConfiguration} of their own, e.g.
         * {@code index.html}: the object that a request for a directory is answered with.
         *
         * @param indexDocument the index document.
         * @return these settings.
         * @throws IllegalArgumentException if it is blank.
         */
        public WebsiteSettings indexDocument(@NonNull String indexDocument) {
            LocalS3Builder.this.website = LocalS3Builder.this.website.withIndexDocument(indexDocument);
            return this;
        }

        /**
         * Set the error document of the buckets that have no {@code WebsiteConfiguration} of their own, e.g.
         * {@code error.html}: the object that a request for a key that isn't there is answered with.
         *
         * @param errorDocument the error document; {@code null} answers a generic error page.
         * @return these settings.
         */
        public WebsiteSettings errorDocument(String errorDocument) {
            LocalS3Builder.this.website = LocalS3Builder.this.website.withErrorDocument(errorDocument);
            return this;
        }

        /**
         * Replace the settings with ones the caller holds, e.g. the ones an application read from its own
         * configuration.
         *
         * @param website the settings; {@code null} for the defaults.
         * @return these settings.
         */
        public WebsiteSettings settings(LocalS3Website website) {
            LocalS3Builder.this.website = Objects.requireNonNullElseGet(website, LocalS3Website::defaults);
            return this;
        }

    }

    /**
     * The default CORS settings of a service; see {@linkplain LocalS3Cors}. {@linkplain LocalS3Builder#defaultCors(Consumer)}
     * hands one out; every method writes the setting through to the builder it came from, so a settings object is only
     * good while that call runs.
     */
    public final class CorsSettings {

        private CorsSettings() {
        }

        /**
         * Set the origins that are allowed, e.g. {@code http://localhost:5173}; each may contain one {@code *}
         * wildcard, and {@code *} alone allows every origin. No origin turns the default rule off.
         *
         * @param origins the origins.
         * @return these settings.
         */
        public CorsSettings allowedOrigins(String... origins) {
            LocalS3Builder.this.cors = LocalS3Builder.this.cors.withAllowedOrigins(List.of(origins));
            return this;
        }

        /**
         * Set the methods that are allowed, among {@code GET}, {@code PUT}, {@code POST}, {@code DELETE} and
         * {@code HEAD}; none, the default, allows all of them.
         *
         * @param methods the methods.
         * @return these settings.
         * @throws IllegalArgumentException if a method isn't one of them.
         */
        public CorsSettings allowedMethods(String... methods) {
            LocalS3Builder.this.cors = LocalS3Builder.this.cors.withAllowedMethods(List.of(methods));
            return this;
        }

        /**
         * Set the request headers that are allowed, each may contain one {@code *} wildcard; none, the default, allows
         * every header.
         *
         * @param headers the headers.
         * @return these settings.
         */
        public CorsSettings allowedHeaders(String... headers) {
            LocalS3Builder.this.cors = LocalS3Builder.this.cors.withAllowedHeaders(List.of(headers));
            return this;
        }

        /**
         * Set the response headers that the pages may read; none, the default, exposes
         * {@linkplain LocalS3Cors#DEFAULT_EXPOSE_HEADERS}.
         *
         * @param headers the headers.
         * @return these settings.
         */
        public CorsSettings exposeHeaders(String... headers) {
            LocalS3Builder.this.cors = LocalS3Builder.this.cors.withExposeHeaders(List.of(headers));
            return this;
        }

        /**
         * Set the seconds that a browser may cache a preflight response.
         *
         * @param maxAgeSeconds the seconds; {@code null} for the browser's default.
         * @return these settings.
         */
        public CorsSettings maxAgeSeconds(Integer maxAgeSeconds) {
            LocalS3Builder.this.cors = LocalS3Builder.this.cors.withMaxAgeSeconds(maxAgeSeconds);
            return this;
        }

        /**
         * Replace the settings with ones the caller holds, e.g. the ones an application read from its own
         * configuration.
         *
         * @param cors the settings; {@code null} for none.
         * @return these settings.
         */
        public CorsSettings settings(LocalS3Cors cors) {
            LocalS3Builder.this.cors = Objects.requireNonNullElseGet(cors, LocalS3Cors::disabled);
            return this;
        }

    }

    /**
     * The settings of the Iceberg REST catalog of a service; see {@linkplain LocalS3IcebergCatalog}.
     * {@linkplain LocalS3Builder#icebergCatalog(Consumer)} hands one out; every method writes the setting through to
     * the builder it came from, so a settings object is only good while that call runs.
     */
    public final class IcebergCatalogSettings {

        private IcebergCatalogSettings() {
        }

        /**
         * Set whether the catalog is served at all, e.g. to turn off a catalog that a shared builder configured.
         *
         * @param enabled {@code true} to serve the catalog with the settings configured here; {@code false} to serve
         *     none, which drops the settings.
         * @return these settings.
         */
        public IcebergCatalogSettings enabled(boolean enabled) {
            LocalS3Builder.this.icebergCatalog = enabled
                    ? Objects.requireNonNullElseGet(LocalS3Builder.this.icebergCatalog, LocalS3IcebergCatalog::enabled)
                    : null;
            return this;
        }

        /**
         * Set the warehouse of the catalog: the {@code s3://} location of a bucket of this service that the tables
         * created without a location of their own are placed under.
         *
         * @param warehouse the warehouse location, e.g. {@code s3://lakehouse/}.
         * @return these settings.
         * @throws IllegalArgumentException if the warehouse isn't an {@code s3://} URI of a bucket.
         */
        public IcebergCatalogSettings warehouse(@NonNull String warehouse) {
            return settings(catalog().withWarehouse(warehouse));
        }

        /**
         * Set whether the bucket of the {@linkplain #warehouse(String) warehouse} is created when the service starts,
         * if it doesn't exist. The default is {@code true}; {@code false} leaves that to the test, which then gets a
         * clear failure if it forgot.
         *
         * @param createWarehouseBucket whether to create the warehouse bucket.
         * @return these settings.
         */
        public IcebergCatalogSettings createWarehouseBucket(boolean createWarehouseBucket) {
            return settings(catalog().withCreateWarehouseBucket(createWarehouseBucket));
        }

        /**
         * Set whether a loaded table carries the settings to reach LocalS3 with — its endpoint, path-style access and
         * the credentials of the service. The default is {@code true}, so an engine configured with the catalog URI
         * alone reaches the storage too; {@code false} means the client is configured by hand.
         *
         * @param credentialVending whether a loaded table carries the settings of the service.
         * @return these settings.
         */
        public IcebergCatalogSettings credentialVending(boolean credentialVending) {
            return settings(catalog().withCredentialVending(credentialVending));
        }

        /**
         * Set whether the default location of a table ends in a random suffix, e.g.
         * {@code s3://warehouse/db/orders-8f1c...}, rather than in the name of the table. The default is {@code false},
         * which is Iceberg's: the location of a table is then derived from its name, and a test can assert on it.
         *
         * <p>{@code true} is the {@code unique-table-location} of the Iceberg catalogs. It matters when a table is
         * dropped, or renamed, and another is created under the old name: with locations derived from the name, the new
         * table lives among the files of the old one, and purging one of them takes the other's files with it.
         *
         * @param uniqueTableLocation whether the default location of a table carries a random suffix.
         * @return these settings.
         */
        public IcebergCatalogSettings uniqueTableLocation(boolean uniqueTableLocation) {
            return settings(catalog().withUniqueTableLocation(uniqueTableLocation));
        }

        /**
         * Replace the settings with ones the caller holds, e.g. the ones an application read from its own
         * configuration.
         *
         * @param icebergCatalog the settings; {@code null} to serve no catalog.
         * @return these settings.
         */
        public IcebergCatalogSettings settings(LocalS3IcebergCatalog icebergCatalog) {
            LocalS3Builder.this.icebergCatalog = icebergCatalog;
            return this;
        }

        /**
         * The catalog being configured, which {@linkplain LocalS3Builder#icebergCatalog(Consumer)} turned on; the
         * defaults again if {@linkplain #settings(LocalS3IcebergCatalog)} or {@linkplain #enabled(boolean)} dropped
         * it, so that configuring a warehouse after that turns the catalog back on rather than failing.
         */
        private LocalS3IcebergCatalog catalog() {
            return Objects.requireNonNullElseGet(LocalS3Builder.this.icebergCatalog, LocalS3IcebergCatalog::enabled);
        }

    }

}
