package com.robothy.s3.rest;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.core.service.manager.vectors.LocalS3VectorsManager;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import com.robothy.s3.rest.handler.LocalS3RouterFactory;
import com.robothy.s3.rest.listener.BucketEventListener;
import com.robothy.s3.rest.listener.ObjectEventListener;
import com.robothy.s3.rest.listener.S3EventDispatcher;
import com.robothy.s3.rest.netty.LocalS3ServerInitializer;
import com.robothy.s3.rest.service.BucketNameValidator;
import com.robothy.s3.rest.service.ServiceFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LocalS3 service launcher.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class LocalS3 implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalS3.class);

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    /**
     * Default max request body size(2G), the largest body the in-memory request aggregation can hold.
     */
    public static final long DEFAULT_MAX_REQUEST_BODY_SIZE = Integer.MAX_VALUE;

    /**
     * Default size(4M) above which a request body is buffered in a temporary file instead of the Java heap.
     */
    public static final long DEFAULT_REQUEST_BODY_FILE_THRESHOLD = 4 * 1024 * 1024;

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
     * Default number of threads that handle the requests: as many as the machine has processors, and at
     * least 4. A connection is bound to one of them, so this is the number of connections whose requests are
     * handled at the same time.
     */
    public static final int DEFAULT_S3_EXECUTOR_THREAD_NUM =
            Math.max(4, Runtime.getRuntime().availableProcessors());

    /*
     * The names of the variables that Builder.fromEnvironment() reads, which the Docker image is configured
     * with. They are shared, so that a service embedded in an application or a test is configured like the
     * image, with the same names and the same values.
     */

    public static final String LOCAL_S3_PORT = "LOCAL_S3_PORT";

    public static final String LOCAL_S3_MODE = "LOCAL_S3_MODE";

    public static final String LOCAL_S3_DATA_PATH = "LOCAL_S3_DATA_PATH";

    public static final String LOCAL_S3_STRICT_BUCKET_NAMES = "LOCAL_S3_STRICT_BUCKET_NAMES";

    public static final String LOCAL_S3_STRICT_PART_SIZES = "LOCAL_S3_STRICT_PART_SIZES";

    public static final String LOCAL_S3_COMPOSITE_MULTIPART_ETAGS = "LOCAL_S3_COMPOSITE_MULTIPART_ETAGS";

    public static final String LOCAL_S3_VIRTUAL_HOST_DOMAINS = "LOCAL_S3_VIRTUAL_HOST_DOMAINS";

    public static final String AWS_BUCKETS = "AWS_BUCKETS";

    public static final String AWS_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";

    public static final String AWS_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";

    /* Configuration, set by the builder. */
    private final String bindHost;

    /**
     * The port to bind; {@code 0} binds a random free port.
     */
    private final int configuredPort;

    private final Path dataPath;

    private final LocalS3Mode mode;

    private final List<String> defaultBuckets;

    private final BucketEventListener bucketEventListener;

    private final ObjectEventListener objectEventListener;

    private final Executor eventListenerExecutor;

    private final boolean initialDataCacheEnabled;

    private final boolean daemonThreads;

    private final int nettyParentEventGroupThreadNum;

    private final int nettyChildEventGroupThreadNum;

    private final int s3ExecutorThreadNum;

    private final String accessKeyId;

    private final String secretAccessKey;

    private final long maxRequestBodySize;

    private final long requestBodyFileThreshold;

    private final long idleConnectionTimeoutSeconds;

    private final boolean strictBucketNames;

    private final boolean strictPartSizes;

    private final boolean compositeMultipartEtags;

    private final List<String> virtualHostDomains;

    /* Runtime state; start() and shutdown() are synchronized. */

    /**
     * The bound port once started; the configured port before.
     */
    private volatile int port;

    private volatile LocalS3Manager s3Manager;
    private volatile LocalS3VectorsManager localS3VectorsManager;

    private boolean running;

    private MultiThreadIoEventLoopGroup parentGroup;

    private MultiThreadIoEventLoopGroup childGroup;

    private EventExecutorGroup executorGroup;

    private Channel serverSocketChannel;

    private Thread shutdownHook;

    private LocalS3(Builder builder) {
        this.bindHost = builder.bindHost;
        this.configuredPort = builder.port;
        this.port = builder.port;
        this.dataPath = builder.dataPath;
        this.mode = builder.mode;
        this.defaultBuckets = List.copyOf(builder.defaultBuckets);
        this.bucketEventListener = builder.bucketEventListener;
        this.objectEventListener = builder.objectEventListener;
        this.eventListenerExecutor = builder.eventListenerExecutor;
        this.initialDataCacheEnabled = builder.initialDataCacheEnabled;
        this.daemonThreads = builder.daemonThreads;
        this.nettyParentEventGroupThreadNum = builder.nettyParentEventGroupThreadNum;
        this.nettyChildEventGroupThreadNum = builder.nettyChildEventGroupThreadNum;
        this.s3ExecutorThreadNum = builder.s3ExecutorThreadNum;
        this.accessKeyId = builder.accessKeyId;
        this.secretAccessKey = builder.secretAccessKey;
        this.maxRequestBodySize = builder.maxRequestBodySize;
        this.requestBodyFileThreshold = builder.requestBodyFileThreshold;
        this.idleConnectionTimeoutSeconds = builder.idleConnectionTimeoutSeconds;
        this.strictBucketNames = builder.strictBucketNames;
        this.strictPartSizes = builder.strictPartSizes;
        this.compositeMultipartEtags = builder.compositeMultipartEtags;
        this.virtualHostDomains = List.copyOf(builder.virtualHostDomains);
    }

    /**
     * Create a {@linkplain Builder}.
     *
     * @return a new {@linkplain Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
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
     * Startup the local-s3 service.
     *
     * <p>If the service fails to start, the resources created so far are released and the original
     * exception is thrown. A stopped service can be started again.
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
        this.shutdownHook = new Thread(this::shutdown, "locals3-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    private void startServer() {
        ServiceFactory serviceFactory = createServiceFactory();
        // create default buckets first
        if (!defaultBuckets.isEmpty()) {
            log.info("Create default buckets:{}", String.join(",", defaultBuckets));
            createBuckets();
        }
        // start server
        this.parentGroup = new MultiThreadIoEventLoopGroup(nettyParentEventGroupThreadNum,
                new NamingThreadFactory("locals3-parent-event-group", daemonThreads), NioIoHandler.newFactory());
        this.childGroup = new MultiThreadIoEventLoopGroup(nettyChildEventGroupThreadNum,
                new NamingThreadFactory("locals3-child-event-group", daemonThreads), NioIoHandler.newFactory());
        this.executorGroup = new MultiThreadIoEventLoopGroup(s3ExecutorThreadNum,
                new NamingThreadFactory("locals3-executor-group", daemonThreads), LocalIoHandler.newFactory());
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        ChannelFuture channelFuture;
        try {
            channelFuture = serverBootstrap.group(parentGroup, childGroup)
                    .handler(new LoggingHandler(LogLevel.DEBUG))
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new LocalS3ServerInitializer(executorGroup,
                            LocalS3RouterFactory.create(serviceFactory, accessKeyId, secretAccessKey),
                            serviceFactory.getInstance(XmlMapper.class), maxRequestBodySize, requestBodyFileThreshold,
                            idleConnectionTimeoutSeconds))
                    .bind(bindHost, configuredPort)
                    .sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting LocalS3.", e);
        }
        this.serverSocketChannel = channelFuture.channel();
        // The actual port, in case a random one was requested.
        this.port = ((InetSocketAddress) serverSocketChannel.localAddress()).getPort();
        log.info("LocalS3 listens on {}:{}.", bindHost, port);
        // LocalS3Container of local-s3-testcontainers, including released versions, waits for this exact line.
        log.info("LocalS3 started.");
    }

    private void createBuckets() {
        BucketService bucketService = this.getS3Manager().bucketService();
        BucketNameValidator bucketNameValidator = new BucketNameValidator(strictBucketNames);
        for (String bucketName : defaultBuckets) {
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
            s3Manager = createLocalS3Manager();
        }
        if (localS3VectorsManager == null) {
            localS3VectorsManager = createLocalS3VectorsManager();
        }

        S3EventDispatcher eventDispatcher = bucketEventListener == null && objectEventListener == null ? null
                : new S3EventDispatcher(bucketEventListener, objectEventListener, eventListenerExecutor);
        return LocalS3Services.create(this, s3Manager, localS3VectorsManager, eventDispatcher);
    }

    LocalS3Manager createLocalS3Manager() {
        if (mode == LocalS3Mode.IN_MEMORY) {
            log.info("Created in-memory LocalS3 manager.");
            return LocalS3Manager.createInMemoryS3Manager(dataPath, initialDataCacheEnabled);
        } else {
            log.info("Created file system LocalS3 manager.");
            return LocalS3Manager.createFileSystemS3Manager(dataPath);
        }
    }

    LocalS3VectorsManager createLocalS3VectorsManager() {
        if (mode == LocalS3Mode.IN_MEMORY) {
            log.info("Created in-memory LocalS3 Vectors manager.");
            return LocalS3VectorsManager.createInMemory();
        } else {
            Path vectorsDataPath = dataPath.resolve("vectors");
            log.info("Created file system LocalS3 Vectors manager.");
            return LocalS3VectorsManager.createFileSystem(vectorsDataPath);
        }
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

    /**
     * Close the server socket and stop the event loops, as far as they were created.
     */
    private void stopServer() {
        try {
            if (this.serverSocketChannel != null && this.serverSocketChannel.isOpen()) {
                this.serverSocketChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Close server socket channel failed.", e);
        } finally {
            shutdownEventExecutorsGroupIfNeeded(this.childGroup, this.parentGroup, this.executorGroup);
            this.serverSocketChannel = null;
            this.childGroup = null;
            this.parentGroup = null;
            this.executorGroup = null;
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

    private void shutdownEventExecutorsGroupIfNeeded(EventExecutorGroup... eventExecutorsList) {
        boolean shutdownPerformed = false;
        for (EventExecutorGroup eventExecutors : eventExecutorsList) {
            if (eventExecutors != null && !eventExecutors.isShuttingDown() && !eventExecutors.isShutdown()) {
                shutdownPerformed = true;
                // No quiet period: the listening socket is only released once the event loops have terminated.
                eventExecutors.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }

        for (EventExecutorGroup eventExecutors : eventExecutorsList) {
            if (eventExecutors == null || isInEventLoop(eventExecutors)) {
                continue; // Waiting for our own event loop to terminate would deadlock.
            }
            if (!eventExecutors.terminationFuture().awaitUninterruptibly(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Event executors did not terminate within {} seconds.", SHUTDOWN_TIMEOUT_SECONDS);
            }
        }

        if (shutdownPerformed) {
            log.info("LocalS3 stopped.");
        }
    }

    private static boolean isInEventLoop(EventExecutorGroup eventExecutors) {
        for (EventExecutor eventExecutor : eventExecutors) {
            if (eventExecutor.inEventLoop()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the host that local-s3 service listens on.
     *
     * @return bind host.
     */
    public String getBindHost() {
        return bindHost;
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
     * Get the data path that was set in {@linkplain Builder#dataPath(String)}.
     *
     * @return data path.
     */
    public Path getDataPath() {
        return dataPath;
    }

    /**
     * Get the mode that was set in {@linkplain Builder#mode(LocalS3Mode)}.
     *
     * @return the mode of the service.
     */
    public LocalS3Mode getMode() {
        return mode;
    }

    /**
     * Get the max request body size in bytes.
     *
     * @return max request body size.
     */
    public long getMaxRequestBodySize() {
        return maxRequestBodySize;
    }

    /**
     * Get the size in bytes above which a request body is buffered in a temporary file.
     *
     * @return request body file threshold.
     */
    public long getRequestBodyFileThreshold() {
        return requestBodyFileThreshold;
    }

    /**
     * Get the seconds after which an idle connection is closed; {@code 0} means never.
     *
     * @return idle connection timeout in seconds.
     */
    public long getIdleConnectionTimeoutSeconds() {
        return idleConnectionTimeoutSeconds;
    }

    /**
     * Whether the names of new buckets must follow the naming rules of Amazon S3.
     *
     * @return if strict bucket name validation is enabled.
     */
    public boolean isStrictBucketNames() {
        return strictBucketNames;
    }

    /**
     * Whether every part of a multipart upload but the last one must have the size that Amazon S3 requires.
     *
     * @return if strict part size validation is enabled.
     */
    public boolean isStrictPartSizes() {
        return strictPartSizes;
    }

    /**
     * Whether the object of a completed multipart upload gets the entity tag that Amazon S3 gives an object
     * uploaded in parts.
     *
     * @return if composite multipart entity tags are enabled.
     */
    public boolean isCompositeMultipartEtags() {
        return compositeMultipartEtags;
    }

    /**
     * Whether the threads that serve the requests are daemon threads, which don't keep the JVM alive.
     *
     * @return if the service runs on daemon threads.
     */
    public boolean isDaemonThreads() {
        return daemonThreads;
    }

    /**
     * Get the number of threads that accept connections.
     *
     * @return netty parent event group thread number.
     */
    public int getNettyParentEventGroupThreadNum() {
        return nettyParentEventGroupThreadNum;
    }

    /**
     * Get the number of threads that read and write the connections.
     *
     * @return netty child event group thread number.
     */
    public int getNettyChildEventGroupThreadNum() {
        return nettyChildEventGroupThreadNum;
    }

    /**
     * Get the number of threads that handle the requests; a connection is bound to one of them.
     *
     * @return local-s3 executor thread number.
     */
    public int getS3ExecutorThreadNum() {
        return s3ExecutorThreadNum;
    }

    /**
     * Get the configured base domains of virtual-hosted-style requests, besides the default ones.
     *
     * @return the configured virtual-host domains.
     */
    public List<String> getVirtualHostDomains() {
        return virtualHostDomains;
    }

    /**
     * get Local S3 Manager after start()
     *
     * @return local s3 manager
     */
    public LocalS3Manager getS3Manager() {
        return s3Manager;
    }

    /**
     * Builds {@linkplain LocalS3} instances. A builder can build several instances; changing it
     * afterwards doesn't affect the instances already built.
     */
    public static class Builder {

        private String bindHost = "127.0.0.1";

        private int port = 29090;

        private Path dataPath;

        private LocalS3Mode mode = LocalS3Mode.IN_MEMORY;

        private final List<String> defaultBuckets = new ArrayList<>();

        private BucketEventListener bucketEventListener;

        private ObjectEventListener objectEventListener;

        private Executor eventListenerExecutor = Runnable::run;

        private boolean initialDataCacheEnabled = true;

        private boolean daemonThreads = true;

        private int nettyParentEventGroupThreadNum = DEFAULT_NETTY_PARENT_EVENT_GROUP_THREAD_NUM;

        private int nettyChildEventGroupThreadNum = DEFAULT_NETTY_CHILD_EVENT_GROUP_THREAD_NUM;

        private int s3ExecutorThreadNum = DEFAULT_S3_EXECUTOR_THREAD_NUM;

        private String accessKeyId;

        private String secretAccessKey;

        private long maxRequestBodySize = DEFAULT_MAX_REQUEST_BODY_SIZE;

        private long requestBodyFileThreshold = DEFAULT_REQUEST_BODY_FILE_THRESHOLD;

        private long idleConnectionTimeoutSeconds = DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS;

        private boolean strictBucketNames;

        private boolean strictPartSizes;

        private boolean compositeMultipartEtags = true;

        private final List<String> virtualHostDomains = new ArrayList<>();

        /**
         * Set the host that local-s3 service listens on.
         * The default value is {@code 127.0.0.1}, and local only,
         * and {@code 0.0.0.0} makes the service accessible through all network interfaces.
         *
         * @param bindHost host or IP address to bind.
         * @return builder.
         */
        public Builder bindHost(@NonNull String bindHost) {
            if (bindHost.isBlank()) {
                throw new IllegalArgumentException("bindHost must not be blank.");
            }
            this.bindHost = bindHost;
            return this;
        }

        public Builder acceptFromAnyHost() {
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
        public Builder port(int port) {
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
        public Builder dataPath(@NonNull String dataPath) {
            this.dataPath = Paths.get(dataPath);
            return this;
        }

        /**
         * Set default buckets
         *
         * @param buckets LocalS3 buckets
         * @return builder.
         */
        public Builder buckets(String... buckets) {
            if (buckets != null) {
                for (String bucket : buckets) {
                    // Tolerate lists like "a, b," as split from the AWS_BUCKETS environment variable.
                    if (bucket != null && !bucket.isBlank()) {
                        this.defaultBuckets.add(bucket.trim());
                    }
                }
            }
            return this;
        }

        /**
         * Set LocalS3 service running mode. Default value is {@code IN_MEMORY}.
         *
         * @param mode LocalS3 service running mode.
         * @return builder.
         */
        public Builder mode(@NonNull LocalS3Mode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Set the executor that delivers events to the bucket and object event listeners.
         *
         * <p>By default, listeners run synchronously on the thread handling the request, so an event is
         * delivered before the S3 response is sent. Pass an executor, e.g.
         * {@code Executors.newSingleThreadExecutor()}, to deliver events asynchronously so that slow listeners
         * don't hold up request handling; a single-threaded executor keeps the events in order. LocalS3 does not
         * shut the executor down.
         *
         * <p>Either way, an exception thrown by a listener is logged and does not fail the S3 request.
         *
         * @param eventListenerExecutor executor that runs the event listeners.
         * @return builder.
         */
        public Builder eventListenerExecutor(@NonNull Executor eventListenerExecutor) {
            this.eventListenerExecutor = Objects.requireNonNull(eventListenerExecutor);
            return this;
        }

        /**
         * Set bucket event listener
         *
         * @param bucketEventListener bucket event listener
         * @return builder.
         */
        public Builder bucketEventListener(@NonNull BucketEventListener bucketEventListener) {
            this.bucketEventListener = bucketEventListener;
            return this;
        }

        /**
         * Set object event listener
         *
         * @param objectEventListener bucket event listener
         * @return builder.
         */
        public Builder objectEventListener(@NonNull ObjectEventListener objectEventListener) {
            this.objectEventListener = objectEventListener;
            return this;
        }

        /**
         * This option only available when running LocalS3 in {@code IN_MEMORY} mode
         * with initial data. If initial data cache is enabled, LocalS3 caches the
         * accessed initial data in memory. This could reduce dist I/O when running
         * tests with initial data in the same path.
         *
         * <p> The default value is {@code true}.
         *
         * @param enabled is the initial data cache enabled.
         * @return if the initial data cache enabled.
         */
        public Builder initialDataCacheEnabled(boolean enabled) {
            this.initialDataCacheEnabled = enabled;
            return this;
        }

        /**
         * Set whether the threads that serve the requests are daemon threads.
         *
         * <p>The default value is {@code true}, so that a service that isn't {@linkplain #shutdown() shut
         * down}, e.g. by a test that forgets to, doesn't keep the JVM alive; the shutdown hook stops the
         * service while the JVM exits. Set it to {@code false} to run LocalS3 as a standalone server, whose
         * {@code main} starts the service and returns: only non-daemon threads keep such a JVM running.
         *
         * @param daemonThreads whether the threads that serve the requests are daemon threads.
         * @return builder.
         */
        public Builder daemonThreads(boolean daemonThreads) {
            this.daemonThreads = daemonThreads;
            return this;
        }

        /**
         * Set the number of threads that accept connections. Default value is
         * {@linkplain LocalS3#DEFAULT_NETTY_PARENT_EVENT_GROUP_THREAD_NUM}.
         *
         * @param nettyParentEventGroupThreadNum netty parent event group thread number.
         * @return builder.
         */
        public Builder nettyParentEventGroupThreadNum(int nettyParentEventGroupThreadNum) {
            this.nettyParentEventGroupThreadNum = nettyParentEventGroupThreadNum;
            return this;
        }

        /**
         * Set the number of threads that read and write the connections. Netty binds a connection to one
         * thread of this group for its whole life, and the HTTP parsing of every connection bound to a thread
         * waits while that thread works. The body of a {@code GetObject} response is read from the storage on
         * this thread as it is written to the connection, so serving a large object holds it for a while;
         * raise this value to serve more connections at once. Default value is
         * {@linkplain LocalS3#DEFAULT_NETTY_CHILD_EVENT_GROUP_THREAD_NUM}.
         *
         * @param nettyChildEventGroupThreadNum netty child event group thread number.
         * @return builder.
         */
        public Builder nettyChildEventGroupThreadNum(int nettyChildEventGroupThreadNum) {
            this.nettyChildEventGroupThreadNum = nettyChildEventGroupThreadNum;
            return this;
        }

        /**
         * Set the number of threads that handle the requests, where the S3 operations and their storage I/O
         * run.
         *
         * <p>Netty binds a connection to one thread of this group for its whole life, rather than handing
         * each request to a free thread, so this is the number of connections whose requests are handled at
         * the same time. A request on a connection bound to a busy thread waits for the requests before it,
         * even while other threads of the group are idle; a client that keeps more connections open than
         * there are threads, e.g. a load test or a transfer manager, therefore wants this raised. Default
         * value is {@linkplain LocalS3#DEFAULT_S3_EXECUTOR_THREAD_NUM}.
         *
         * @param s3ExecutorThreadNum local-s3 executor thread number.
         * @return builder.
         */
        public Builder s3ExecutorThreadNum(int s3ExecutorThreadNum) {
            this.s3ExecutorThreadNum = s3ExecutorThreadNum;
            return this;
        }

        /**
         * Set the max size in bytes of a request body. Request bodies are held in memory, or memory-mapped
         * above {@linkplain Builder#requestBodyFileThreshold(long)}, while a request is handled, so this bounds
         * the memory a single request can take. A request exceeding the limit
         * is rejected with {@code EntityTooLarge} before its body is buffered; upload large objects with
         * multipart upload instead. Default value is {@linkplain LocalS3#DEFAULT_MAX_REQUEST_BODY_SIZE}.
         *
         * @param maxRequestBodySize max request body size in bytes, between 1 and {@linkplain Integer#MAX_VALUE}.
         * @return builder.
         */
        public Builder maxRequestBodySize(long maxRequestBodySize) {
            if (maxRequestBodySize <= 0 || maxRequestBodySize > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("maxRequestBodySize must be between 1 and " + Integer.MAX_VALUE + ".");
            }
            this.maxRequestBodySize = maxRequestBodySize;
            return this;
        }

        /**
         * Set the size in bytes above which a request body is buffered in a temporary file instead of the
         * Java heap. The file is memory-mapped while the request is handled, so large uploads take neither
         * heap memory nor a copy of the body. Default value is
         * {@linkplain LocalS3#DEFAULT_REQUEST_BODY_FILE_THRESHOLD}; {@code Long.MAX_VALUE} buffers all
         * request bodies on the heap.
         *
         * @param requestBodyFileThreshold size in bytes, not negative.
         * @return builder.
         */
        public Builder requestBodyFileThreshold(long requestBodyFileThreshold) {
            if (requestBodyFileThreshold < 0) {
                throw new IllegalArgumentException("requestBodyFileThreshold must not be negative.");
            }
            this.requestBodyFileThreshold = requestBodyFileThreshold;
            return this;
        }

        /**
         * Set the seconds after which a connection without reads or writes is closed. A connection with a
         * request in flight is never closed. Default value is
         * {@linkplain LocalS3#DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS}; {@code 0} never closes idle connections.
         *
         * @param idleConnectionTimeoutSeconds idle connection timeout in seconds, not negative.
         * @return builder.
         */
        public Builder idleConnectionTimeoutSeconds(long idleConnectionTimeoutSeconds) {
            if (idleConnectionTimeoutSeconds < 0) {
                throw new IllegalArgumentException("idleConnectionTimeoutSeconds must not be negative.");
            }
            this.idleConnectionTimeoutSeconds = idleConnectionTimeoutSeconds;
            return this;
        }

        /**
         * Set whether the names of new buckets must follow the
         * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html">naming rules</a>
         * of Amazon S3 general purpose buckets, e.g. 3 to 63 lowercase letters, numbers, periods and hyphens.
         * Creating a bucket with another name then fails with {@code InvalidBucketName}, so that tests don't pass
         * with bucket names that Amazon S3 rejects. Existing buckets stay accessible.
         *
         * <p>The default value is {@code false}, which accepts any non-blank bucket name.
         *
         * @param strictBucketNames whether to validate bucket names strictly.
         * @return builder.
         */
        public Builder strictBucketNames(boolean strictBucketNames) {
            this.strictBucketNames = strictBucketNames;
            return this;
        }

        /**
         * Set whether every part of a multipart upload but the last one must be at least 5 MiB, the
         * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html">minimum part size</a> of
         * Amazon S3. Completing an upload with a smaller part then fails with {@code EntityTooSmall}, so that
         * tests don't pass with a part layout that Amazon S3 rejects. The last part may be any size, and so may
         * the single part of an upload that has only one.
         *
         * <p>The default value is {@code false}, which accepts parts of any size, so that tests that upload
         * small parts keep working.
         *
         * @param strictPartSizes whether to validate part sizes strictly.
         * @return builder.
         */
        public Builder strictPartSizes(boolean strictPartSizes) {
            this.strictPartSizes = strictPartSizes;
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
         * @return builder.
         */
        public Builder compositeMultipartEtags(boolean compositeMultipartEtags) {
            this.compositeMultipartEtags = compositeMultipartEtags;
            return this;
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
         * @return builder.
         */
        public Builder virtualHostDomains(String... domains) {
            if (domains != null) {
                for (String domain : domains) {
                    if (domain != null && !domain.isBlank()) {
                        this.virtualHostDomains.add(domain.trim());
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
        public Builder credentials(@NonNull String accessKeyId, @NonNull String secretAccessKey) {
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
         * Configure the builder from the environment variables that the Docker image is configured with, read
         * from the environment or, if a variable isn't set there, from the system property of the same name.
         *
         * <p>Only the variables that are set are applied, so the caller keeps its own defaults for everything
         * else: a container applies its defaults, e.g. binding every interface, before calling this, while an
         * embedded service or a test keeps the defaults of the builder. The variables are
         * {@linkplain LocalS3#LOCAL_S3_PORT}, {@linkplain LocalS3#LOCAL_S3_MODE},
         * {@linkplain LocalS3#LOCAL_S3_DATA_PATH}, {@linkplain LocalS3#LOCAL_S3_STRICT_BUCKET_NAMES},
         * {@linkplain LocalS3#LOCAL_S3_STRICT_PART_SIZES},
         * {@linkplain LocalS3#LOCAL_S3_COMPOSITE_MULTIPART_ETAGS},
         * {@linkplain LocalS3#LOCAL_S3_VIRTUAL_HOST_DOMAINS}, {@linkplain LocalS3#AWS_BUCKETS},
         * {@linkplain LocalS3#AWS_ACCESS_KEY_ID} and {@linkplain LocalS3#AWS_SECRET_ACCESS_KEY}.
         *
         * @return builder.
         * @throws IllegalArgumentException if a variable has an invalid value.
         */
        public Builder fromEnvironment() {
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
        public Builder fromEnvironment(@NonNull UnaryOperator<String> variables) {
            LocalS3Environment.applyTo(this, variables);
            return this;
        }

        /**
         * Build a {@linkplain LocalS3} instance.
         *
         * @return created {@linkplain LocalS3} instance.
         */
        public LocalS3 build() {
            log.debug("Build LocalS3 on {}:{} in {} mode, data path: {}, authentication: {}.",
                    bindHost, port, mode, dataPath, accessKeyId == null ? "disabled" : "enabled");
            return new LocalS3(this);
        }

    }

    /**
     * Names the threads of an event loop group. Netty creates the threads of a group as it needs them, and a
     * request handled on one group can start work on another, so the threads are counted atomically to give
     * each of them its own name.
     */
    // Package private so that the tests can exercise it directly.
    static final class NamingThreadFactory implements ThreadFactory {

        private final String name;

        private final boolean daemon;

        private final AtomicInteger counter = new AtomicInteger();

        public NamingThreadFactory(String name, boolean daemon) {
            this.name = name;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread thread = new Thread(r, name + "-" + counter.getAndIncrement());
            thread.setDaemon(daemon);
            return thread;
        }
    }

}
