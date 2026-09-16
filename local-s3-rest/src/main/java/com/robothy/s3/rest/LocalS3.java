package com.robothy.s3.rest;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.core.service.manager.vectors.LocalS3VectorsManager;
import com.robothy.s3.rest.admin.LocalS3Admin;
import com.robothy.s3.rest.admin.RequestStatistics;
import com.robothy.s3.rest.admin.ServiceStatistics;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import com.robothy.s3.rest.handler.LocalS3RouterFactory;
import com.robothy.s3.rest.netty.LocalS3HttpRequestDecoder;
import com.robothy.s3.rest.netty.RequestRecorder;
import com.robothy.s3.rest.service.BucketNameValidator;
import com.robothy.s3.rest.service.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LocalS3 service launcher.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class LocalS3 implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalS3.class);

    /**
     * The directory in the storage directory that large request bodies are buffered in in {@code PERSISTENCE} mode.
     */
    static final String REQUEST_BODY_DIRECTORY = ".request-bodies";

    /**
     * The directory in the data path that the vector buckets and their vectors are stored in.
     */
    static final String VECTORS_DIRECTORY = "vectors";

    private final LocalS3Config config;

    /* Runtime state; start() and shutdown() are synchronized. */

    /**
     * The bound port once started; the configured port before.
     */
    private volatile int port;

    private volatile LocalS3Manager s3Manager;
    private volatile LocalS3VectorsManager localS3VectorsManager;

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
        Path requestBodyFileDirectory = prepareRequestBodyFileDirectory();
        this.server = NettyServer.start(config,
                LocalS3RouterFactory.create(serviceFactory, config.accessKeyId(), config.secretAccessKey()),
                serviceFactory.getInstance(XmlMapper.class), requestBodyFileDirectory, recorder(requestStatistics));
        // The actual port, in case a random one was requested.
        this.port = server.port();
        log.info("LocalS3 listens on {}:{}.", config.bindHost(), port);
        // LocalS3Container of local-s3-testcontainers, including released versions, waits for this exact line.
        log.info("LocalS3 started.");
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
        BucketNameValidator bucketNameValidator = new BucketNameValidator(config.strictBucketNames());
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

        return LocalS3Services.create(config, s3Manager, localS3VectorsManager, new Admin());
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
        if (!config.buckets().isEmpty()) {
            createBuckets();
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
                requests == null ? Map.of() : requests.operations());
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
    }

    LocalS3Manager createLocalS3Manager() {
        if (config.mode() == LocalS3Mode.IN_MEMORY) {
            log.info("Created in-memory LocalS3 manager.");
            return LocalS3Manager.createInMemoryS3Manager(config.dataPath(), config.initialDataCacheEnabled());
        } else {
            log.info("Created file system LocalS3 manager.");
            return LocalS3Manager.createFileSystemS3Manager(config.dataPath());
        }
    }

    LocalS3VectorsManager createLocalS3VectorsManager() {
        Path dataPath = config.dataPath();
        if (config.mode() == LocalS3Mode.IN_MEMORY) {
            log.info("Created in-memory LocalS3 Vectors manager.");
            // Starts from the vectors of the data path, if any, which it never changes, like the objects of the path.
            return LocalS3VectorsManager.createInMemory(dataPath == null ? null : dataPath.resolve(VECTORS_DIRECTORY));
        } else {
            Path vectorsDataPath = dataPath.resolve(VECTORS_DIRECTORY);
            log.info("Created file system LocalS3 Vectors manager.");
            return LocalS3VectorsManager.createFileSystem(vectorsDataPath);
        }
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

    public boolean isStrictBucketNames() {
        return config.strictBucketNames();
    }

    public boolean isStrictPartSizes() {
        return config.strictPartSizes();
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
     * get Local S3 Manager after start()
     *
     * @return local s3 manager
     */
    public LocalS3Manager getS3Manager() {
        if (s3Manager == null) {
            throw new IllegalStateException("S3Manager has not been initialized");
        }
        return s3Manager;
    }

}
