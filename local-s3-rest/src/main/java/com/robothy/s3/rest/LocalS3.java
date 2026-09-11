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
import com.robothy.s3.rest.service.DefaultServiceFactory;
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

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.xml.stream.XMLInputFactory;

import org.apache.commons.lang3.reflect.FieldUtils;
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

    private String bindHost = "127.0.0.1";

    private int port = 29090;

    private Path dataPath;

    private LocalS3Mode mode = LocalS3Mode.IN_MEMORY;

    @SuppressWarnings("FieldMayBeFinal")
    private List<String> defaultBuckets = new ArrayList<>();

    private BucketEventListener bucketEventListener;

    private ObjectEventListener objectEventListener;

    private Executor eventListenerExecutor = Runnable::run;

    private LocalS3Manager s3Manager;

    private boolean initialDataCacheEnabled = true;

    private int nettyParentEventGroupThreadNum = 1;

    private int nettyChildEventGroupThreadNum = 2;

    private int s3ExecutorThreadNum = 4;

    private String accessKeyId;

    private String secretAccessKey;

    private long maxRequestBodySize = DEFAULT_MAX_REQUEST_BODY_SIZE;

    private long requestBodyFileThreshold = DEFAULT_REQUEST_BODY_FILE_THRESHOLD;


    /* Private fields. */
    private MultiThreadIoEventLoopGroup parentGroup;

    private MultiThreadIoEventLoopGroup childGroup;

    private EventExecutorGroup executorGroup;

    private Channel serverSocketChannel;

    private Thread shutdownHook;

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
     */
    public void start() {
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
                            serviceFactory.getInstance(XmlMapper.class), maxRequestBodySize, requestBodyFileThreshold))
                    .bind(bindHost, port)
                    .sync();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        log.info("LocalS3 started.");
        this.serverSocketChannel = channelFuture.channel();
        this.shutdownHook = new Thread(this::shutdown, "locals3-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    private void createBuckets() {
        BucketService bucketService = this.getS3Manager().bucketService();
        for (String bucketName : defaultBuckets) {
            try {
                bucketService.getBucket(bucketName);
            } catch (BucketNotExistException e) {
                bucketService.createBucket(bucketName);
            }
        }
    }

    private ServiceFactory createServiceFactory() {

        s3Manager = createLocalS3Manager();

        ServiceFactory serviceFactory = new DefaultServiceFactory();
        BucketService bucketService = s3Manager.bucketService();
        ObjectService objectService = s3Manager.objectService();
        serviceFactory.register(BucketService.class, () -> bucketService);
        serviceFactory.register(ObjectService.class, () -> objectService);

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
        S3VectorsService s3VectorsService = createLocalS3VectorsManager().s3VectorsService();
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
     * Shutdown the local-s3 service.
     */
    public void shutdown() {
        if (null == this.parentGroup || null == this.childGroup) {
            throw new IllegalStateException("LocalS3 is not started.");
        }

        removeShutdownHook();
        try {
            if (this.serverSocketChannel.isOpen()) {
                this.serverSocketChannel.close().sync();
            }
        } catch (InterruptedException e) {
            log.error("Close server socket channel failed.", e);
        } finally {
            shutdownEventExecutorsGroupIfNeeded(this.childGroup, this.parentGroup, this.executorGroup);
        }
    }

    @Override
    public void close() {
        shutdown();
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
            if (!eventExecutors.isShuttingDown() && !eventExecutors.isShutdown()) {
                shutdownPerformed = true;
                // No quiet period: the listening socket is only released once the event loops have terminated.
                eventExecutors.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }

        for (EventExecutorGroup eventExecutors : eventExecutorsList) {
            if (isInEventLoop(eventExecutors)) {
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
     * Get the port that local-s3 service listen to.
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
     * get Local S3 Manager after start()
     *
     * @return local s3 manager
     */
    public LocalS3Manager getS3Manager() {
        return s3Manager;
    }

    public static class Builder {

        private final LocalS3 propHolder = new LocalS3();

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
            propHolder.bindHost = bindHost;
            return this;
        }

        public Builder acceptFromAnyHost() {
            propHolder.bindHost = "0.0.0.0";
            return this;
        }

        /**
         * Set the port that local-s3 service listen to. Default port is 29090.
         * Set the value to {@code -1} if you want to assign a random port.
         *
         * @param port customized port.
         * @return builder.
         */
        public Builder port(int port) {
            if (port < 0) {
                propHolder.port = findFreeTcpPort();
            } else {
                propHolder.port = port;
            }
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
            this.propHolder.dataPath = Paths.get(dataPath);
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
                        propHolder.defaultBuckets.add(bucket.trim());
                    }
                }
            }
            return this;
        }

        /**
         * Set LocalS3 service running mode. Default value is {@code PERSISTENCE}.
         *
         * @param mode LocalS3 service running mode.
         * @return builder.
         */
        public Builder mode(@NonNull LocalS3Mode mode) {
            propHolder.mode = mode;
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
            propHolder.eventListenerExecutor = Objects.requireNonNull(eventListenerExecutor);
            return this;
        }

        /**
         * Set bucket event listener
         *
         * @param bucketEventListener bucket event listener
         * @return builder.
         */
        public Builder bucketEventListener(@NonNull BucketEventListener bucketEventListener) {
            propHolder.bucketEventListener = bucketEventListener;
            return this;
        }

        /**
         * Set object event listener
         *
         * @param objectEventListener bucket event listener
         * @return builder.
         */
        public Builder objectEventListener(@NonNull ObjectEventListener objectEventListener) {
            propHolder.objectEventListener = objectEventListener;
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
            this.propHolder.initialDataCacheEnabled = enabled;
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
            propHolder.nettyParentEventGroupThreadNum = nettyParentEventGroupThreadNum;
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
            propHolder.nettyChildEventGroupThreadNum = nettyChildEventGroupThreadNum;
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
            propHolder.s3ExecutorThreadNum = s3ExecutorThreadNum;
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
            propHolder.maxRequestBodySize = maxRequestBodySize;
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
            propHolder.requestBodyFileThreshold = requestBodyFileThreshold;
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
            propHolder.accessKeyId = accessKeyId;
            propHolder.secretAccessKey = secretAccessKey;
            return this;
        }

        /**
         * Build a {@linkplain LocalS3} instance.
         *
         * @return created {@linkplain LocalS3} instance.
         */
        public LocalS3 build() {
            LocalS3 localS3 = new LocalS3();
            for (Field field : FieldUtils.getAllFields(LocalS3.class)) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = FieldUtils.readField(field, propHolder);
                    FieldUtils.writeField(field, localS3, value);
                    Object loggedValue = field.getName().toLowerCase().contains("secret") ? "******" : value;
                    log.debug(field.getName() + ": " + loggedValue);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            return localS3;
        }

        private int findFreeTcpPort() {
            int freePort;
            try (ServerSocket serverSocket = new ServerSocket(0)) {
                freePort = serverSocket.getLocalPort();
            } catch (IOException e) {
                throw new IllegalStateException("TCP port is not available.");
            }
            return freePort;
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
