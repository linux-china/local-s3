package com.robothy.s3.rest;

import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.model.answers.LifecycleActionAns;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.iceberg.IcebergMetadataFiles;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.core.s3tables.S3TablesArn;
import com.robothy.s3.core.service.manager.iceberg.LocalS3IcebergManager;
import com.robothy.s3.core.service.manager.s3tables.LocalS3TablesManager;
import com.robothy.s3.core.service.manager.vectors.LocalS3VectorsManager;
import com.robothy.s3.rest.admin.LocalS3Admin;
import com.robothy.s3.rest.handler.iceberg.IcebergClientConfig;
import com.robothy.s3.rest.admin.RequestStatistics;
import com.robothy.s3.rest.admin.ServiceStatistics;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import com.robothy.s3.rest.handler.AwsSignatureV4Presigner;
import com.robothy.s3.rest.handler.LocalS3RouterFactory;
import com.robothy.s3.rest.netty.LocalS3HttpRequestDecoder;
import com.robothy.s3.rest.netty.RequestRecorder;
import com.robothy.s3.rest.service.BucketNameValidator;
import com.robothy.s3.rest.service.ServiceFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * LocalS3 service launcher.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class LocalS3 implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalS3.class);

    /**
     * The directory in the storage directory that large request bodies are buffered in in {@code PERSISTENCE} mode.
     */
    static final String REQUEST_BODY_DIRECTORY = LocalS3Manager.REQUEST_BODY_DIRECTORY;

    /**
     * The bind hosts that serve every interface of the machine rather than one address, which {@linkplain #endpoint()}
     * reaches at the loopback address.
     */
    private static final Set<String> WILDCARD_BIND_HOSTS = Set.of("0.0.0.0", "::", "[::]");

    private final LocalS3Config config;

    /* Runtime state; start() and shutdown() are synchronized. */

    /**
     * The bound port once started; the configured port before.
     */
    private volatile int port;

    private volatile LocalS3Manager s3Manager;
    private volatile LocalS3VectorsManager localS3VectorsManager;

    /**
     * The Iceberg REST catalog of the service; {@code null} if it serves none.
     */
    private volatile LocalS3IcebergManager localS3IcebergManager;

    /**
     * The table buckets of the S3 Tables API of the service. Always present: the API is served like the S3 Vectors
     * one is, so an {@code S3TablesClient} pointed at a LocalS3 works without the service having been configured for
     * it.
     */
    private volatile LocalS3TablesManager localS3TablesManager;

    /**
     * Whether the service is started and not shut down yet. Written under the lock of start() and shutdown(), and
     * volatile, so that isRunning() doesn't wait for a shutdown in progress.
     */
    private volatile boolean running;

    private volatile NettyServer server;

    /**
     * The requests answered since the service started, or was reset.
     */
    private volatile RequestStatistics requestStatistics;

    private volatile Instant startedAt;

    private Thread shutdownHook;

    LocalS3(LocalS3Config config) {
        this.config = Objects.requireNonNull(config);
        this.port = config.port();
    }

    /**
     * Create a {@linkplain LocalS3Builder}.
     *
     * @return a new builder.
     */
    public static LocalS3Builder builder() {
        return new LocalS3Builder();
    }

    /**
     * Create a service from a configuration, e.g. one that {@linkplain LocalS3Builder#buildConfig()} built, or that
     * a framework bound its properties to.
     *
     * @param config the configuration.
     * @return a service that isn't started yet.
     */
    public static LocalS3 create(@NonNull LocalS3Config config) {
        return new LocalS3(config);
    }

    /**
     * Drop the initial data that the {@code IN_MEMORY} services cached, releasing the heap it holds. The
     * cache keeps the data of a bounded number of data paths; call this to release them earlier, e.g. when
     * a test class that used a data path has finished. The data of a dropped path is loaded again when a
     * service starts with it, so this only costs the loading.
     */
    public static void clearInitialDataCache() {
        LocalS3Manager.clearInitialDataCache();
    }

    /**
     * Change the limits of the initial data that the {@code IN_MEMORY} services of the JVM cache: the number of data
     * paths whose metadata is kept, and the number of bytes of heap that the copies of the objects read from them take.
     * A copy that would exceed the bytes drops the least recently used data paths first, and an object that there is
     * still no room for is read from the disk instead of copied, so a large data path doesn't fill the heap. The
     * limits default to 1024 data paths and a quarter of the max heap, or to the environment variables or system
     * properties {@code LOCAL_S3_INITIAL_DATA_CACHE_MAX_ENTRIES} and {@code LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES},
     * e.g. {@code 512m}.
     *
     * @param maxEntries the max number of data paths, positive.
     * @param maxBytes   the max number of bytes that the copies of objects take, not negative; {@code 0} copies nothing.
     */
    public static void configureInitialDataCache(int maxEntries, long maxBytes) {
        LocalS3Manager.configureInitialDataCache(maxEntries, maxBytes);
    }

    /**
     * Startup the local-s3 service.
     *
     * <p>If the service fails to start, the resources created so far are released and the original
     * exception is thrown. A stopped service can be started again. By default, starting registers a JVM
     * shutdown hook; {@linkplain LocalS3Builder#registerShutdownHook(boolean)} can disable it for host-managed lifecycles.
     *
     * @throws IllegalStateException if the service is already started.
     */
    public synchronized void start() {
        if (running) {
            throw new IllegalStateException("LocalS3 is already started.");
        }

        try {
            startServer();
        } catch (Throwable e) {
            stopServer();
            throw e;
        }
        running = true;
        if (config.registerShutdownHook()) {
            this.shutdownHook = new Thread(this::shutdown, "locals3-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
        }
    }

    private void startServer() {
        this.requestStatistics = new RequestStatistics(RequestStatistics.DEFAULT_RECENT_REQUESTS,
                LocalS3RouterFactory.UNRECORDED_OPERATIONS);
        this.startedAt = Instant.now();
        ServiceFactory serviceFactory = createServiceFactory();
        // create default buckets first
        if (!config.buckets().isEmpty()) {
            log.info("Create default buckets:{}", String.join(",", config.buckets()));
            createBuckets();
        }
        prepareIcebergWarehouse();
        if (!config.seeders().isEmpty()) {
            seed();
        }
        Path requestBodyFileDirectory = prepareRequestBodyFileDirectory();
        this.server = NettyServer.start(config,
                LocalS3RouterFactory.create(serviceFactory, config.accessKeyId(), config.secretAccessKey()),
                serviceFactory.getInstance(XmlMapper.class), requestBodyFileDirectory, recorder(requestStatistics));
        // The actual port, in case a random one was requested.
        this.port = server.port();
        if (config.tlsEnabled() && config.plainHttpAccepted()) {
            log.info("LocalS3 listens on https://{}:{} and http://{}:{}, on the same port.", config.bindHost(), port,
                    config.bindHost(), port);
        } else {
            log.info("LocalS3 listens on {}://{}:{}.", config.tlsEnabled() ? "https" : "http", config.bindHost(), port);
        }
        // Where to look at what the service holds, which an embedded service gives no other sign of.
        log.info("LocalS3 console: {}/_admin/ui", endpoint());
        if (config.tls() != null) {
            logCertificate(config.tls());
        }
        warnIfOpenToTheNetwork();
        warnIfCatalogVendsCredentialsToTheNetwork();
        // LocalS3Container of local-s3-testcontainers, including released versions, waits for this exact line.
        log.info("LocalS3 started.");
    }

    /**
     * Warn that the service answers unsigned requests from other machines, which a service does that has no
     * credentials and doesn't bind a loopback address: everyone who reaches the port reads, writes and deletes every
     * bucket, whatever the signature of a request, and calls the admin endpoints, e.g. {@code /_admin/reset}, and the
     * Iceberg REST catalog anonymously. The Docker image binds {@code 0.0.0.0}, since a container serves its host, so publishing its port
     * without credentials opens the data to the network the machine is on.
     *
     * <p>It warns rather than refuses to start: an open service is what some setups want, e.g. a test environment
     * that a team shares. Configuring credentials turns the warning off, as does binding a loopback address; the
     * logger of this class silences it.
     *
     * <p>Package-private so that a test may call it on a service it hasn't started, rather than bind the wildcard
     * address of the machine it runs on to see the warning.
     */
    void warnIfOpenToTheNetwork() {
        if (config.authenticationEnabled() || !config.reachableFromOtherHosts()) {
            return;
        }
        log.warn("""
                !! LocalS3 is listening on {}:{} without authentication: everyone who reaches this port can read, \
                write and delete every bucket.
                !! Any signature is accepted, and the admin endpoints (/_admin/ui, /_admin/reset, ...) and the Iceberg \
                REST catalog answer anonymous requests.
                !! Set LOCAL_S3_ACCESS_KEY_ID and LOCAL_S3_SECRET_ACCESS_KEY, or LocalS3Builder.credentials(...), to require \
                signed requests; bind 127.0.0.1 to serve this machine alone.""", config.bindHost(), port);
    }

    /**
     * Warn that the Iceberg REST catalog hands the credentials of the service to whoever asks, which a catalog does
     * that vends credentials on a service that has them and is reachable from other machines: the catalog answers
     * anonymous requests, a loaded table carries the access key and the secret key, and the remote signing routes sign
     * any S3 request with them. Requiring signed requests keeps out no one who reaches the catalog.
     *
     * <p>{@code GET /v1/config} carries no keys, but anyone may create a table and load it. Turning credential vending
     * off, or binding a loopback address, turns the warning off; the logger of this class silences it.
     *
     * <p>Package-private for the same reason as {@linkplain #warnIfOpenToTheNetwork()}.
     */
    void warnIfCatalogVendsCredentialsToTheNetwork() {
        if (!config.authenticationEnabled() || !config.icebergCatalogEnabled()
                || !config.icebergCatalog().credentialVending() || !config.reachableFromOtherHosts()) {
            return;
        }
        log.warn("""
                !! The Iceberg REST catalog on {}:{} answers anonymous requests and vends the credentials of this \
                service: everyone who reaches this port can obtain the secret key from a loaded table, and have any \
                S3 request signed at /iceberg/v1/aws/s3/sign.
                !! Turn credential vending off, icebergCatalog(iceberg -> iceberg.credentialVending(false)), or bind \
                127.0.0.1 to serve this machine alone.""", config.bindHost(), port);
    }

    /**
     * Log the certificate that the service serves HTTPS with, so that a client that refuses it can be told why.
     *
     * <p>A self-signed certificate is logged in PEM format as well: it was generated for this service, so the log is
     * the only place it exists, and a client only accepts it once it is given the certificate itself. The private key
     * is never logged.
     *
     * @param tls the certificate of the service.
     */
    private static void logCertificate(LocalS3Tls tls) {
        if (!tls.isSelfSigned()) {
            log.info("LocalS3 serves HTTPS with the certificate {}.", tls.describe());
            return;
        }
        log.info("""
                LocalS3 generated a self-signed certificate for this service: {}.
                No client trusts it yet: save the certificate below to a file, e.g. local-s3.pem, and pass it to the \
                client, with curl --cacert local-s3.pem, AWS_CA_BUNDLE=local-s3.pem for the AWS CLI and boto3, keytool \
                -importcert for a JVM client, or LocalS3Tls.newClientSslContext() for a client in this JVM. See \
                https://github.com/Robothy/local-s3/blob/main/docs/deployment.md#https
                {}""", tls.describe(), tls.certificateChainPem());
    }

    /**
     * The recorder of the server: the statistics of the service, followed by the recorder of the configuration.
     */
    private RequestRecorder recorder(RequestStatistics statistics) {
        RequestRecorder configured = config.requestRecorder();
        if (configured == RequestRecorder.NONE) {
            return statistics;
        }
        return (request, operation, status, requestId, durationNanos) -> {
            statistics.record(request, operation, status, requestId, durationNanos);
            configured.record(request, operation, status, requestId, durationNanos);
        };
    }

    /**
     * Prepare the directory that large request bodies are buffered in. In {@code PERSISTENCE} mode it is a directory
     * of the storage, so that the storage stores the body of an upload by renaming its file rather than by writing the
     * body a second time; otherwise the bodies are buffered in the default temporary directory.
     *
     * @return the directory; {@code null} for the default temporary directory.
     */
    private Path prepareRequestBodyFileDirectory() {
        if (config.mode() != LocalS3Mode.PERSISTENCE || config.dataPath() == null) {
            return null;
        }
        Path directory = config.dataPath().toAbsolutePath()
                .resolve(LocalS3Manager.STORAGE_DIRECTORY)
                .resolve(REQUEST_BODY_DIRECTORY);
        try {
            LocalS3HttpRequestDecoder.prepareBodyFileDirectory(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to prepare the request body directory " + directory + ".", e);
        }
        return directory;
    }

    private void createBuckets() {
        BucketService bucketService = this.getS3Manager().bucketService();
        BucketNameValidator bucketNameValidator = new BucketNameValidator();
        for (String bucketName : config.buckets()) {
            try {
                bucketService.getBucket(bucketName);
            } catch (BucketNotExistException e) {
                // Existing buckets are accepted, like buckets loaded from the data path.
                bucketNameValidator.validate(bucketName);
                bucketService.createBucket(bucketName);
            }
        }
    }

    /**
     * Apply the {@linkplain LocalS3Config#seeders() seeders} to the services of the manager, so that the fixtures are
     * in place before the server accepts the first request, and again after a reset. They write through the services,
     * not over HTTP, so their changes reach the change listeners like any other change.
     */
    private void seed() {
        SeededFixtures fixtures = new SeededFixtures();
        try {
            for (LocalS3Seeder seeder : config.seeders()) {
                seeder.seed(fixtures);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to seed LocalS3 with its initial objects.", e);
        }
        log.info("Seeded LocalS3 with {} initial object(s).", fixtures.count);
    }

    /**
     * The buckets and objects that the {@linkplain LocalS3Seeder seeders} write, put through the services of the
     * manager. Used by {@linkplain #seed()} only, on the thread that starts or resets the service.
     */
    private class SeededFixtures implements LocalS3Seeder.Fixtures {

        private final BucketNameValidator bucketNameValidator = new BucketNameValidator();

        private int count;

        @Override
        public void bucket(String bucketName) {
            BucketService bucketService = getS3Manager().bucketService();
            try {
                bucketService.getBucket(bucketName);
            } catch (BucketNotExistException e) {
                // Existing buckets are accepted, like the default buckets and the buckets loaded from the data path.
                bucketNameValidator.validate(bucketName);
                bucketService.createBucket(bucketName);
            }
        }

        @Override
        public void object(String bucketName, String key, byte[] content) {
            put(bucketName, key, new ByteArrayInputStream(content), content.length, null);
        }

        @Override
        public void object(String bucketName, String key, Path file) throws IOException {
            long size = Files.size(file);
            String contentType = Files.probeContentType(file);
            try (InputStream content = Files.newInputStream(file)) {
                put(bucketName, key, content, size, contentType);
            }
        }

        @Override
        public void object(String bucketName, String key, InputStream content, long size, String contentType)
                throws IOException {
            try (InputStream stream = content) {
                put(bucketName, key, stream, size, contentType);
            }
        }

        private void put(String bucketName, String key, InputStream content, long size, String contentType) {
            bucket(bucketName);
            getS3Manager().objectService().putObject(bucketName, key, PutObjectOptions.builder()
                    .contentType(contentType)
                    .size(size)
                    .content(content)
                    .build());
            count++;
        }

    }

    /**
     * Take over the data of another {@code IN_MEMORY} service that is shut down, so that this one starts with the
     * buckets, objects, vector buckets, table buckets and Iceberg catalog that the other one held, rather than empty.
     * Meant for a host that recreates its service in the same JVM, e.g. an application that Spring Boot DevTools
     * restarts, whose service would otherwise lose its data at every restart.
     *
     * <p>The data is moved, not shared: the other service is left without it, as if it had never been started. The
     * change listeners of the other service, and those subscribed to its manager by hand, are unsubscribed, and the
     * listeners and the executor of this service subscribed instead. The Iceberg catalog is taken over only if this
     * service serves one with the same warehouse and table location; otherwise this one creates its own. The default
     * buckets and the seeders of this service are applied when it starts, like at any start.
     *
     * @param previous the service to take the data of.
     * @return {@code true} if the data was taken over; {@code false} if the data can't back this service, e.g. because
     *     either service isn't {@code IN_MEMORY}, their data paths or in-memory limits differ, this service has data of
     *     its own, or the other one is running or holds no data.
     */
    public boolean takeOverDataOf(@NonNull LocalS3 previous) {
        if (previous == this) {
            return false;
        }
        // Neither service may start or shut down meanwhile. A host takes over from a service that is gone, so the two
        // aren't locked the other way round at the same time.
        synchronized (this) {
            synchronized (previous) {
                if (!canTakeOverDataOf(previous)) {
                    return false;
                }
                LocalS3Manager manager = previous.s3Manager;
                manager.clearChangeListeners();
                manager.changeListenerExecutor(config.changeListenerExecutor());
                config.changeListeners().forEach(manager::addChangeListener);
                this.s3Manager = manager;
                this.localS3VectorsManager = previous.localS3VectorsManager;
                this.localS3TablesManager = previous.localS3TablesManager;
                LocalS3IcebergCatalog catalog = config.icebergCatalog();
                LocalS3IcebergCatalog previousCatalog = previous.config.icebergCatalog();
                if (catalog != null && previousCatalog != null
                        && catalog.warehouse().equals(previousCatalog.warehouse())
                        && catalog.uniqueTableLocation() == previousCatalog.uniqueTableLocation()) {
                    this.localS3IcebergManager = previous.localS3IcebergManager;
                }
                previous.s3Manager = null;
                previous.localS3VectorsManager = null;
                previous.localS3TablesManager = null;
                previous.localS3IcebergManager = null;
                log.info("LocalS3 took over the in-memory data of the previous service.");
                return true;
            }
        }
    }

    private boolean canTakeOverDataOf(LocalS3 previous) {
        LocalS3Config other = previous.config;
        return !running && s3Manager == null && localS3VectorsManager == null
                && !previous.running && previous.s3Manager != null && previous.localS3VectorsManager != null
                && previous.localS3TablesManager != null
                && config.mode() == LocalS3Mode.IN_MEMORY && other.mode() == LocalS3Mode.IN_MEMORY
                // The managers were created with them, and start from the initial data of the path.
                && Objects.equals(config.dataPath(), other.dataPath())
                && config.maxInMemoryBytes() == other.maxInMemoryBytes()
                && config.initialDataCacheEnabled() == other.initialDataCacheEnabled();
    }

    private ServiceFactory createServiceFactory() {
        // Keep the managers across a restart, so that a service that is started again serves the data it held.
        if (s3Manager == null) {
            LocalS3Manager manager = createLocalS3Manager();
            // The services publish their changes however they are called, so the listeners also hear of the changes
            // made through getS3Manager(). Subscribed once, together with the manager that is kept across restarts.
            manager.changeListenerExecutor(config.changeListenerExecutor());
            config.changeListeners().forEach(manager::addChangeListener);
            s3Manager = manager;
        }
        if (localS3VectorsManager == null) {
            localS3VectorsManager = createLocalS3VectorsManager();
        }
        if (localS3IcebergManager == null && config.icebergCatalogEnabled()) {
            localS3IcebergManager = createLocalS3IcebergManager();
        }
        if (localS3TablesManager == null) {
            localS3TablesManager = createLocalS3TablesManager();
        }

        return LocalS3Services.create(config, s3Manager, localS3VectorsManager, new Admin(), localS3IcebergManager,
                localS3TablesManager);
    }

    /**
     * Replace the data of the service with the data it started with, e.g. between the tests that share a service,
     * which is much quicker than restarting it: the buckets, objects, multipart uploads and vectors are dropped, the
     * initial data of the data path, if any, is loaded again, and the {@linkplain LocalS3Config#buckets() default buckets} are
     * created again. The requests recorded for {@code GET /_admin/stats} are forgotten too.
     *
     * <p>The requests in progress are finished first, and the requests that arrive meanwhile wait for the reset. The
     * same reset is requested with {@code POST /_admin/reset}.
     *
     * @throws UnsupportedOperationException if the service is in {@code PERSISTENCE} mode, whose data a reset would
     *     delete from its data path.
     * @throws IllegalStateException if the service has never been started.
     */
    public void reset() {
        if (config.mode() != LocalS3Mode.IN_MEMORY) {
            throw new UnsupportedOperationException(
                    "Only an IN_MEMORY service can be reset; a PERSISTENCE service keeps its data in its data path.");
        }
        // Not synchronized: a reset requested through the service would wait for a shutdown that waits for it.
        LocalS3Manager objects = getS3Manager();
        objects.reset();
        localS3VectorsManager.reset();
        if (localS3IcebergManager != null) {
            localS3IcebergManager.reset();
        }
        if (localS3TablesManager != null) {
            localS3TablesManager.reset();
        }
        if (!config.buckets().isEmpty()) {
            createBuckets();
        }
        prepareIcebergWarehouse();
        if (!config.seeders().isEmpty()) {
            seed();
        }
        RequestStatistics statistics = this.requestStatistics;
        if (statistics != null) {
            statistics.clear();
        }
        log.info("LocalS3 was reset.");
    }

    /**
     * The statistics of the service: the amount of its data, the requests in flight, and the requests answered by
     * operation. The same statistics are requested with {@code GET /_admin/stats}.
     *
     * @return the statistics.
     * @throws IllegalStateException if the service has never been started.
     */
    public ServiceStatistics statistics() {
        LocalS3Manager objects = getS3Manager();
        RequestStatistics requests = this.requestStatistics;
        NettyServer started = this.server;
        Instant since = this.startedAt;
        return new ServiceStatistics(config.mode().name(), since == null ? null : since.toString(),
                since == null ? 0 : Duration.between(since, Instant.now()).toSeconds(),
                started == null ? 0 : started.inFlightRequests(),
                objects.statistics(), localS3VectorsManager.statistics(),
                requests == null ? 0 : requests.totalRequests(),
                requests == null ? Map.of() : requests.operations(),
                requests == null ? Map.of() : requests.notImplemented());
    }

    /**
     * Apply the lifecycle configurations of the buckets that have one at a time, which LocalS3 never does by itself: e.g.
     * {@code applyLifecycle(Instant.now().plus(Duration.ofDays(31)))} expires what a rule would have expired 31 days from
     * now. The same is requested with {@code POST /_admin/lifecycle}.
     *
     * @param now the time to apply the rules at.
     * @return the actions taken.
     * @throws IllegalStateException if the service has never been started.
     * @see com.robothy.s3.core.service.LifecycleExecutionService
     */
    public List<LifecycleActionAns> applyLifecycle(Instant now) {
        return getS3Manager().objectService().applyLifecycle(now);
    }

    /**
     * Apply the lifecycle configuration of a bucket at a time, like {@linkplain #applyLifecycle(Instant)}.
     *
     * @param bucketName the bucket name.
     * @param now the time to apply the rules at.
     * @return the actions taken; empty if the bucket has no lifecycle configuration.
     * @throws BucketNotExistException if the bucket doesn't exist.
     */
    public List<LifecycleActionAns> applyLifecycle(String bucketName, Instant now) {
        return getS3Manager().objectService().applyLifecycle(bucketName, now);
    }

    /**
     * The administration of the service, which the {@code /_admin} endpoints answer through.
     */
    private final class Admin implements LocalS3Admin {

        @Override
        public ServiceStatistics statistics() {
            return LocalS3.this.statistics();
        }

        @Override
        public List<RequestStatistics.RecentRequest> recentRequests(int limit) {
            RequestStatistics requests = requestStatistics;
            return requests == null ? List.of() : requests.recentRequests(limit);
        }

        @Override
        public void reset() {
            LocalS3.this.reset();
        }

        @Override
        public List<LifecycleActionAns> applyLifecycle(String bucketName, Instant now) {
            return bucketName == null ? LocalS3.this.applyLifecycle(now) : LocalS3.this.applyLifecycle(bucketName, now);
        }
    }

    LocalS3Manager createLocalS3Manager() {
        if (config.mode() == LocalS3Mode.IN_MEMORY) {
            log.info("Created in-memory LocalS3 manager, holding at most {} bytes of content.",
                    config.maxInMemoryBytes());
            return LocalS3Manager.createInMemoryS3Manager(config.dataPath(), config.initialDataCacheEnabled(),
                    config.maxInMemoryBytes());
        } else {
            log.info("Created file system LocalS3 manager with the {} persistence policy.",
                    config.persistencePolicy());
            return LocalS3Manager.createFileSystemS3Manager(config.dataPath(), config.persistencePolicy());
        }
    }

    LocalS3VectorsManager createLocalS3VectorsManager() {
        Path dataPath = config.dataPath();
        if (config.mode() == LocalS3Mode.IN_MEMORY) {
            log.info("Created in-memory LocalS3 Vectors manager.");
            // Starts from the vectors of the data path, if any, which it never changes, like the objects of the path.
            return LocalS3VectorsManager.createInMemory(dataPath);
        } else {
            log.info("Created file system LocalS3 Vectors manager.");
            // The same data path as the S3 buckets: the vector buckets are written to the store of the path too, and
            // their data files to its vectors directory. The store is shared, so the policy has to be the same one.
            return LocalS3VectorsManager.createFileSystem(dataPath, config.persistencePolicy());
        }
    }

    /**
     * Create the manager of the table buckets of the S3 Tables API, which keeps them where the service keeps the rest
     * of its metadata: in memory, or in the store of the data directory. The tables themselves are objects of the
     * bucket behind each table bucket, so they are kept by the S3 half of the service either way.
     *
     * @return the manager.
     */
    LocalS3TablesManager createLocalS3TablesManager() {
        LocalS3Manager manager = getS3Manager();
        String region = IcebergClientConfig.DEFAULT_REGION;
        if (config.mode() == LocalS3Mode.IN_MEMORY) {
            log.info("Created in-memory LocalS3 Tables manager.");
            // Starts from the table buckets of the data path, if any, which it never changes.
            return LocalS3TablesManager.createInMemory(config.dataPath(), manager.bucketService(),
                    manager.objectService(), region, S3TablesArn.DEFAULT_ACCOUNT_ID);
        }
        log.info("Created file system LocalS3 Tables manager.");
        // The same data path as the S3 buckets: the table buckets are written to the store of the path too, which is
        // shared, so the policy has to be the same one.
        return LocalS3TablesManager.createFileSystem(config.dataPath(), config.persistencePolicy(),
                manager.bucketService(), manager.objectService(), region, S3TablesArn.DEFAULT_ACCOUNT_ID);
    }

    /**
     * Create the manager of the Iceberg REST catalog, which keeps its namespaces and its table pointers where the
     * service keeps the rest of its metadata: in memory, or in the store of the data directory. The tables themselves
     * are objects of the warehouse bucket, so they are kept by the S3 half of the service either way.
     *
     * @return the manager.
     */
    LocalS3IcebergManager createLocalS3IcebergManager() {
        LocalS3IcebergCatalog catalog = config.icebergCatalog();
        LocalS3Manager manager = getS3Manager();
        if (config.mode() == LocalS3Mode.IN_MEMORY) {
            log.info("Created in-memory Iceberg REST catalog with the warehouse {}.", catalog.warehouse());
            // Starts from the catalog of the data path, if any, which it never changes, like the objects of the path.
            return LocalS3IcebergManager.createInMemory(config.dataPath(), manager.bucketService(),
                    manager.objectService(), catalog.warehouse(), catalog.uniqueTableLocation());
        }
        log.info("Created file system Iceberg REST catalog with the warehouse {}.", catalog.warehouse());
        // The same data path as the S3 buckets: the catalog records are written to the store of the path too, which
        // is shared, so the policy has to be the same one.
        return LocalS3IcebergManager.createFileSystem(config.dataPath(), config.persistencePolicy(),
                manager.bucketService(), manager.objectService(), catalog.warehouse(),
                catalog.uniqueTableLocation());
    }

    /**
     * Create the warehouse bucket of the Iceberg catalog if it doesn't exist, so that a test that turns the catalog on
     * can create a table without first creating a bucket by hand.
     */
    private void prepareIcebergWarehouse() {
        LocalS3IcebergCatalog catalog = config.icebergCatalog();
        if (catalog == null || !catalog.createWarehouseBucket()) {
            return;
        }
        LocalS3Manager manager = getS3Manager();
        new IcebergMetadataFiles(manager.bucketService(), manager.objectService())
                .createBucketIfAbsent(catalog.warehouse());
    }

    /**
     * Whether the service is started, i.e. {@linkplain #start()} returned and {@linkplain #shutdown()} wasn't called
     * since, e.g. for a host that manages the lifecycle of the service.
     *
     * @return {@code true} if the service is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Shutdown the local-s3 service. Does nothing if the service isn't running, e.g. because it
     * failed to start, so that {@linkplain #close()} doesn't hide the exception of {@linkplain #start()}.
     */
    public synchronized void shutdown() {
        if (!running) {
            return;
        }

        running = false;
        removeShutdownHook();
        stopServer();
        closePersistentManagers();
    }

    /**
     * Close the store that holds the metadata of a {@code PERSISTENCE} service, which releases its data directory, so
     * that another service can open the same directory. Both managers hold that one store, the S3 buckets and the
     * vector buckets of the directory being kept in it, so both are released here: a hold that was left would keep the
     * directory locked. The managers are dropped with it, and a service that is started again creates ones that load
     * the data from the directory. The managers of an {@code IN_MEMORY} service are kept, so that a service that is
     * started again serves the data it held.
     */
    private void closePersistentManagers() {
        if (config.mode() != LocalS3Mode.PERSISTENCE) {
            return;
        }
        LocalS3TablesManager tablesManager = this.localS3TablesManager;
        this.localS3TablesManager = null;
        LocalS3IcebergManager icebergManager = this.localS3IcebergManager;
        this.localS3IcebergManager = null;
        LocalS3VectorsManager vectorsManager = this.localS3VectorsManager;
        this.localS3VectorsManager = null;
        LocalS3Manager manager = this.s3Manager;
        this.s3Manager = null;
        // They hold the one store of the data directory, which is closed once they have all released it.
        releaseAll(
                tablesManager == null ? null : tablesManager::close,
                icebergManager == null ? null : icebergManager::close,
                vectorsManager == null ? null : vectorsManager::close,
                manager == null ? null : manager::close);
    }

    /**
     * Release the holds on the store of the data directory, in order and every one of them: a hold that was left behind
     * because an earlier release failed would keep the directory locked, and no other service could open it. The first
     * failure is raised, with the later ones suppressed on it.
     *
     * @param releases the releases to run; a {@code null} entry is a manager the service doesn't have.
     */
    private static void releaseAll(Runnable... releases) {
        RuntimeException failure = null;
        for (Runnable release : releases) {
            if (release == null) {
                continue;
            }
            try {
                release.run();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private void stopServer() {
        NettyServer started = this.server;
        this.server = null;
        if (started != null) {
            started.stop();
        }
    }

    /**
     * Deregister the shutdown hook, so that a stopped instance is no longer referenced by the JVM.
     */
    private void removeShutdownHook() {
        Thread hook = this.shutdownHook;
        if (hook == null || Thread.currentThread() == hook) {
            return;
        }

        this.shutdownHook = null;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down and runs the hook anyway.
        }
    }

    /**
     * The configuration that the service was built with.
     *
     * @return the configuration.
     */
    public LocalS3Config getConfig() {
        return config;
    }

    /**
     * Get the port that local-s3 service listen to. If a random port was requested,
     * the port is only known once the service is started; before, this returns {@code 0}.
     *
     * @return the port.
     */
    public int getPort() {
        return port;
    }

    /**
     * The endpoint that clients reach the service at, e.g. {@code http://127.0.0.1:19090}: the host that it is bound
     * to, or the loopback address if it is bound to every interface, and the port that it listens on. A service that
     * serves TLS, see {@linkplain LocalS3Builder#tls(String, String)}, is reached at an {@code https} endpoint.
     *
     * <pre>{@code
     *  S3Client s3 = S3Client.builder().endpointOverride(URI.create(localS3.endpoint())).build();
     * }</pre>
     *
     * @return the endpoint, without a trailing slash.
     * @throws IllegalStateException if a random port was requested and the service isn't started yet, so that the
     *     port it listens on isn't known.
     */
    public String endpoint() {
        if (port == 0) {
            throw new IllegalStateException("A random port was requested; "
                    + "the endpoint is only known once the service is started.");
        }
        String host = config.bindHost();
        if (WILDCARD_BIND_HOSTS.contains(host)) {
            // Every interface is served, and the loopback address is the one that reaches it from this machine.
            host = "127.0.0.1";
        } else if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            // An IPv6 address is bracketed in a URL.
            host = "[" + host + "]";
        }
        return (config.tlsEnabled() ? "https" : "http") + "://" + host + ":" + port;
    }

    /**
     * Sign a URL that reads an object for a while, so that a client without credentials, e.g. a browser or a
     * teammate that an AI agent hands an artifact to, downloads it with the URL alone.
     *
     * @param bucketName the bucket of the object.
     * @param key the key of the object.
     * @param expiration how long the URL is valid, between 1 second and 7 days.
     * @return the presigned URL.
     * @see #presign(String, String, Duration, String)
     */
    public String presign(@NonNull String bucketName, @NonNull String key, @NonNull Duration expiration) {
        return presign(bucketName, key, expiration, "GET");
    }

    /**
     * Sign a URL of an object that is valid for a while, e.g. a {@code PUT} URL that a client uploads an object with:
     *
     * <pre>{@code
     *  String url = localS3.presign("my-bucket", "report.pdf", Duration.ofMinutes(15));
     * }</pre>
     *
     * <p>The URL is path-style and signed with the credentials of the service, see
     * {@linkplain LocalS3Builder#credentials(String, String)}. A service without credentials answers unsigned
     * requests, so it returns the plain URL of the object instead, which doesn't expire.
     *
     * <p>Neither the bucket nor the object has to exist: like Amazon S3, signing a URL doesn't touch the data, and a
     * URL that is used after the object is gone is answered with {@code 404 NoSuchKey}.
     *
     * @param bucketName the bucket of the object.
     * @param key the key of the object.
     * @param expiration how long the URL is valid, between 1 second and 7 days, the longest expiration that Amazon S3
     *     signs.
     * @param httpMethod the HTTP method that the URL is signed for, e.g. {@code GET} or {@code PUT}; a request of
     *     another method is rejected, since the method is signed.
     * @return the presigned URL.
     * @throws IllegalArgumentException if a name or the method is blank, or the expiration is out of range.
     * @throws IllegalStateException if the port that the service listens on isn't known yet, see
     *     {@linkplain #endpoint()}.
     */
    public String presign(@NonNull String bucketName, @NonNull String key, @NonNull Duration expiration,
            @NonNull String httpMethod) {
        AwsSignatureV4Presigner presigner = config.authenticationEnabled()
                ? new AwsSignatureV4Presigner(config.accessKeyId(), config.secretAccessKey())
                : AwsSignatureV4Presigner.unsigned();
        return presigner.presign(endpoint(), httpMethod, bucketName, key, expiration);
    }

    /*
     * Shortcuts to the configuration, see the components of LocalS3Config.
     */

    public String getBindHost() {
        return config.bindHost();
    }

    public Path getDataPath() {
        return config.dataPath();
    }

    public LocalS3Mode getMode() {
        return config.mode();
    }

    public long getMaxRequestBodySize() {
        return config.maxRequestBodySize();
    }

    public long getRequestBodyFileThreshold() {
        return config.requestBodyFileThreshold();
    }

    public int getMaxRequestHeaderSize() {
        return config.maxRequestHeaderSize();
    }

    public long getIdleConnectionTimeoutSeconds() {
        return config.idleConnectionTimeoutSeconds();
    }

    public boolean isCompositeMultipartEtags() {
        return config.compositeMultipartEtags();
    }

    public boolean isDaemonThreads() {
        return config.daemonThreads();
    }

    public int getNettyParentEventGroupThreadNum() {
        return config.nettyParentEventGroupThreadNum();
    }

    public int getNettyChildEventGroupThreadNum() {
        return config.nettyChildEventGroupThreadNum();
    }

    public int getS3ExecutorThreadNum() {
        return config.s3ExecutorThreadNum();
    }

    public boolean isVirtualThreads() {
        return config.virtualThreads();
    }

    public List<String> getVirtualHostDomains() {
        return config.virtualHostDomains();
    }

    /**
     * Whether the service serves HTTPS rather than plain HTTP; see {@linkplain LocalS3Builder#tls(String, String)}.
     *
     * @return {@code true} if TLS is configured.
     */
    public boolean isTlsEnabled() {
        return config.tlsEnabled();
    }

    /**
     * get Local S3 Manager after start()
     *
     * <p>A {@code PERSISTENCE} service releases its manager, and the data directory it holds open, when it is
     * {@linkplain #shutdown() shut down}, so its manager is only available while it runs. An {@code IN_MEMORY} service
     * keeps its manager, and the data it holds, across a restart.
     *
     * @return local s3 manager
     * @throws IllegalStateException if the service has never been started, or is a {@code PERSISTENCE} service that
     *     has been shut down.
     */
    public LocalS3Manager getS3Manager() {
        if (s3Manager == null) {
            throw new IllegalStateException("S3Manager has not been initialized"
                    + (config.mode() == LocalS3Mode.PERSISTENCE ? ", or was released when the service was shut down."
                    : "."));
        }
        return s3Manager;
    }

}
