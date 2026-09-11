package com.robothy.s3.rest;

import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.core.service.manager.vectors.LocalS3VectorsManager;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import com.robothy.s3.rest.handler.LocalS3RouterFactory;
import com.robothy.s3.rest.listener.BucketEventListener;
import com.robothy.s3.rest.listener.ObjectEventListener;
import com.robothy.s3.rest.listener.S3EventDispatcher;
import com.robothy.s3.rest.netty.LocalS3ServerInitializer;
import com.robothy.s3.rest.service.BucketNameValidator;
import com.robothy.s3.rest.service.DefaultServiceFactory;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.VirtualHostParser;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.xml.stream.XMLInputFactory;

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

    private final int nettyParentEventGroupThreadNum;

    private final int nettyChildEventGroupThreadNum;

    private final int s3ExecutorThreadNum;

    private final String accessKeyId;

    private final String secretAccessKey;

    private final long maxRequestBodySize;

    private final long requestBodyFileThreshold;

    private final long idleConnectionTimeoutSeconds;

    private final boolean strictBucketNames;

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
        this.nettyParentEventGroupThreadNum = builder.nettyParentEventGroupThreadNum;
        this.nettyChildEventGroupThreadNum = builder.nettyChildEventGroupThreadNum;
        this.s3ExecutorThreadNum = builder.s3ExecutorThreadNum;
        this.accessKeyId = builder.accessKeyId;
        this.secretAccessKey = builder.secretAccessKey;
        this.maxRequestBodySize = builder.maxRequestBodySize;
        this.requestBodyFileThreshold = builder.requestBodyFileThreshold;
        this.idleConnectionTimeoutSeconds = builder.idleConnectionTimeoutSeconds;
        this.strictBucketNames = builder.strictBucketNames;
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
                new NamingThreadFactory("locals3-parent-event-group"), NioIoHandler.newFactory());
        this.childGroup = new MultiThreadIoEventLoopGroup(nettyChildEventGroupThreadNum,
                new NamingThreadFactory("locals3-child-event-group"), NioIoHandler.newFactory());
        this.executorGroup = new MultiThreadIoEventLoopGroup(s3ExecutorThreadNum,
                new NamingThreadFactory("locals3-executor-group"), LocalIoHandler.newFactory());
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
        // keep s3Manager even after restart
        if(s3Manager == null) {
            s3Manager = createLocalS3Manager();
        }
        ServiceFactory serviceFactory = new DefaultServiceFactory();
        BucketService bucketService = s3Manager.bucketService();
        ObjectService objectService = s3Manager.objectService();
        serviceFactory.register(BucketService.class, () -> bucketService);
        serviceFactory.register(ObjectService.class, () -> objectService);
        BucketNameValidator bucketNameValidator = new BucketNameValidator(strictBucketNames);
        serviceFactory.register(BucketNameValidator.class, () -> bucketNameValidator);
        VirtualHostParser virtualHostParser = new VirtualHostParser(virtualHostDomains);
        serviceFactory.register(VirtualHostParser.class, () -> virtualHostParser);

        XMLInputFactory input = new WstxInputFactory();
        input.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
        input.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE); // Disable DTDs
        input.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE); // Disable external entities

        XmlMapper xmlMapper = new XmlMapper(new XmlFactory(input, new WstxOutputFactory()));
        xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        xmlMapper.registerModule(new Jdk8Module());
        xmlMapper.registerModule(new JavaTimeModule());
        serviceFactory.register(XmlMapper.class, () -> xmlMapper);

        // Register ObjectMapper for JSON handling (used by S3 Vectors API)
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.registerModule(new JavaTimeModule());
        serviceFactory.register(ObjectMapper.class, () -> objectMapper);

        // Register S3 Vectors services
        if(localS3VectorsManager==null) {
            localS3VectorsManager = createLocalS3VectorsManager();
        }
        S3VectorsService s3VectorsService = localS3VectorsManager.s3VectorsService();
        serviceFactory.register(S3VectorsService.class, () -> s3VectorsService);

        // register event dispatcher
        if (bucketEventListener != null || objectEventListener != null) {
            S3EventDispatcher eventDispatcher =
                    new S3EventDispatcher(bucketEventListener, objectEventListener, eventListenerExecutor);
            serviceFactory.register(S3EventDispatcher.class, () -> eventDispatcher);
        }
        return serviceFactory;
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

        private int nettyParentEventGroupThreadNum = 1;

        private int nettyChildEventGroupThreadNum = 2;

        private int s3ExecutorThreadNum = 4;

        private String accessKeyId;

        private String secretAccessKey;

        private long maxRequestBodySize = DEFAULT_MAX_REQUEST_BODY_SIZE;

        private long requestBodyFileThreshold = DEFAULT_REQUEST_BODY_FILE_THRESHOLD;

        private long idleConnectionTimeoutSeconds = DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS;

        private boolean strictBucketNames;

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
         * <p>
         * If the path is specified and the {@code mode} is {@code PERSISTENCE},
         * then the LocalS3 service load data from and store data in this directory.
         *
         * <p>
         * If the data directory is set and LocalS3 runs in {@code IN_MEMORY} mode,
         * then data from that path will be loaded as initial data. All changes are
         * only available in the memory, i.e. won't write back to the specified path.
         * <p>
         * Besides, LocalS3 will cache accessed data from this path; which could reduce
         * disk I/O when start LocalS3 in {@code IN_MEMORY} mode with the same initial
         * data for multi-times.
         *
         * @param dataPath data path.
         * @return builder.
         */
        public Builder dataPath(@NonNull String dataPath) {
            this.dataPath = Paths.get(dataPath);
            this.mode = LocalS3Mode.PERSISTENCE;
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
         * Set netty parent event group thread number.
         * Default values is 1.
         *
         * @param nettyParentEventGroupThreadNum netty parent event group thread number.
         * @return builder.
         */
        public Builder nettyParentEventGroupThreadNum(int nettyParentEventGroupThreadNum) {
            this.nettyParentEventGroupThreadNum = nettyParentEventGroupThreadNum;
            return this;
        }

        /**
         * Set netty child event group thread number.
         * Default value is 2.
         *
         * @param nettyChildEventGroupThreadNum netty child event group thread number.
         * @return builder.
         */
        public Builder nettyChildEventGroupThreadNum(int nettyChildEventGroupThreadNum) {
            this.nettyChildEventGroupThreadNum = nettyChildEventGroupThreadNum;
            return this;
        }

        /**
         * Set local-s3 executor thread number.
         * Default value is 4.
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

    private static final class NamingThreadFactory implements ThreadFactory {

        private final String name;

        private int counter = 0;

        public NamingThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, name + "-" + counter++);
        }
    }

}
