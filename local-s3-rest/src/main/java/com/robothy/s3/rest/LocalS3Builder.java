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
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@linkplain LocalS3} instances, and the {@linkplain LocalS3Config} they are created from. A builder can build
 * several instances; changing it afterwards doesn't affect the instances already built.
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

    private final List<S3ChangeListener> changeListeners = new ArrayList<>();

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
        this.dataPath = Paths.get(dataPath);
        return this;
    }

    /**
     * Set default buckets
     *
     * @param buckets LocalS3 buckets
     * @return builder.
     */
    public LocalS3Builder buckets(String... buckets) {
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
    public LocalS3Builder mode(@NonNull LocalS3Mode mode) {
        this.mode = mode;
        return this;
    }

    /**
     * Set the executor that delivers the changes to the {@linkplain #changeListener change listeners}.
     *
     * <p>By default, listeners run synchronously on the thread handling the request, so a change is
     * delivered before the S3 response is sent. Pass an executor, e.g.
     * {@code Executors.newSingleThreadExecutor()}, to deliver changes asynchronously so that slow listeners
     * don't hold up request handling; a single-threaded executor keeps the changes in order. LocalS3 does not
     * shut the executor down.
     *
     * <p>Either way, an exception thrown by a listener is logged and does not fail the S3 request.
     *
     * @param changeListenerExecutor executor that runs the change listeners.
     * @return builder.
     */
    public LocalS3Builder changeListenerExecutor(@NonNull Executor changeListenerExecutor) {
        this.changeListenerExecutor = Objects.requireNonNull(changeListenerExecutor);
        return this;
    }

    /**
     * Subscribe a listener to the {@linkplain com.robothy.s3.core.event.S3Change changes} that the services commit:
     * buckets created and deleted, objects created and deleted, object tagging and ACLs changed, and multipart uploads
     * aborted. The changes are delivered however the services are called, by an HTTP request or directly through
     * {@linkplain LocalS3#getS3Manager()}. Several listeners may be subscribed; each receives every change.
     *
     * @param changeListener receives the committed changes.
     * @return builder.
     */
    public LocalS3Builder changeListener(@NonNull S3ChangeListener changeListener) {
        this.changeListeners.add(Objects.requireNonNull(changeListener));
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
    public LocalS3Builder initialDataCacheEnabled(boolean enabled) {
        this.initialDataCacheEnabled = enabled;
        return this;
    }

    /**
     * Set the max number of bytes of heap that the content of an {@code IN_MEMORY} service takes: the objects and the
     * parts of multipart uploads stored in it. Storing content beyond the limit is answered with
     * {@code 507 InsufficientStorage}, whose message suggests the {@code PERSISTENCE} mode, instead of running the JVM
     * that embeds the service, e.g. an application or an IDE, out of heap. The space of deleted objects, and of a
     * {@linkplain LocalS3#reset() reset} service, is available again. The initial data read from the
     * {@linkplain #dataPath(String) data path} doesn't count; its copies are bounded by
     * {@code LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES}. A {@code PERSISTENCE} service ignores the limit.
     *
     * <p>Default value is {@linkplain LocalS3Config#DEFAULT_MAX_IN_MEMORY_BYTES}, i.e. half the max heap;
     * {@code Long.MAX_VALUE} for no limit.
     *
     * @param maxInMemoryBytes max number of bytes, positive.
     * @return builder.
     */
    public LocalS3Builder maxInMemoryBytes(long maxInMemoryBytes) {
        LocalS3Config.requireMaxInMemoryBytes(maxInMemoryBytes);
        this.maxInMemoryBytes = maxInMemoryBytes;
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
     * @return builder.
     */
    public LocalS3Builder daemonThreads(boolean daemonThreads) {
        this.daemonThreads = daemonThreads;
        return this;
    }

    /**
     * Set whether {@linkplain LocalS3#start()} registers a JVM shutdown hook. The default is {@code true}.
     * Disable it when the LocalS3 lifecycle is managed by a host such as an IDE plugin or Spring container,
     * and ensure that the host calls {@linkplain LocalS3#shutdown()} or {@linkplain LocalS3#close()}.
     *
     * @param registerShutdownHook whether to register a JVM shutdown hook when the service starts.
     * @return builder.
     */
    public LocalS3Builder registerShutdownHook(boolean registerShutdownHook) {
        this.registerShutdownHook = registerShutdownHook;
        return this;
    }

    /**
     * Set the number of threads that accept connections. Default value is
     * {@linkplain LocalS3Config#DEFAULT_NETTY_PARENT_EVENT_GROUP_THREAD_NUM}.
     *
     * @param nettyParentEventGroupThreadNum netty parent event group thread number.
     * @return builder.
     */
    public LocalS3Builder nettyParentEventGroupThreadNum(int nettyParentEventGroupThreadNum) {
        this.nettyParentEventGroupThreadNum = nettyParentEventGroupThreadNum;
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
     * @param nettyChildEventGroupThreadNum netty child event group thread number.
     * @return builder.
     */
    public LocalS3Builder nettyChildEventGroupThreadNum(int nettyChildEventGroupThreadNum) {
        this.nettyChildEventGroupThreadNum = nettyChildEventGroupThreadNum;
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
     * @param s3ExecutorThreadNum local-s3 executor thread number.
     * @return builder.
     */
    public LocalS3Builder s3ExecutorThreadNum(int s3ExecutorThreadNum) {
        this.s3ExecutorThreadNum = s3ExecutorThreadNum;
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
     * @return builder.
     */
    public LocalS3Builder virtualThreads(boolean virtualThreads) {
        this.virtualThreads = virtualThreads;
        return this;
    }

    /**
     * Set the max size in bytes of a request body. Request bodies are held in memory, or buffered in a temporary
     * file above {@linkplain #requestBodyFileThreshold(long)}, which is memory-mapped up to 2 GiB, while a request is
     * handled, so this bounds the memory and the disk space a single request can take. A request exceeding the limit
     * is rejected with {@code EntityTooLarge} before its body is buffered; upload large objects with
     * multipart upload instead. Default value is {@linkplain LocalS3Config#DEFAULT_MAX_REQUEST_BODY_SIZE}.
     *
     * @param maxRequestBodySize max request body size in bytes, between 1 and
     *     {@linkplain LocalS3Config#DEFAULT_MAX_REQUEST_BODY_SIZE}, i.e. 5 GiB.
     * @return builder.
     */
    public LocalS3Builder maxRequestBodySize(long maxRequestBodySize) {
        LocalS3Config.requireMaxRequestBodySize(maxRequestBodySize);
        this.maxRequestBodySize = maxRequestBodySize;
        return this;
    }

    /**
     * Set the size in bytes above which a request body is buffered in a temporary file instead of the
     * Java heap. The file is memory-mapped while the request is handled, or only read from the file if the body is
     * larger than 2 GiB, which no buffer holds, so large uploads take neither
     * heap memory nor a copy of the body. In {@code PERSISTENCE} mode the file is created in the storage
     * directory, and the body of an upload that isn't {@code aws-chunked} encoded is stored by renaming the
     * file, so that its content isn't written a second time. The file is written on the request executor, not on
     * the event loop that receives the body; while a disk writes slower than a client sends, the connection isn't
     * read, so neither memory nor the other connections of the event loop are affected. Default value is
     * {@linkplain LocalS3Config#DEFAULT_REQUEST_BODY_FILE_THRESHOLD}; {@code Long.MAX_VALUE} buffers all
     * request bodies on the heap.
     *
     * @param requestBodyFileThreshold size in bytes, not negative.
     * @return builder.
     */
    public LocalS3Builder requestBodyFileThreshold(long requestBodyFileThreshold) {
        LocalS3Config.requireRequestBodyFileThreshold(requestBodyFileThreshold);
        this.requestBodyFileThreshold = requestBodyFileThreshold;
        return this;
    }

    /**
     * Set the max size in bytes of the header section of a request, i.e. of all its header lines. A request
     * whose headers exceed it is answered with {@code 400 RequestHeaderSectionTooLarge}, and its connection is
     * closed. Default value is {@linkplain LocalS3Config#DEFAULT_MAX_REQUEST_HEADER_SIZE}.
     *
     * @param maxRequestHeaderSize max request header size in bytes, positive.
     * @return builder.
     */
    public LocalS3Builder maxRequestHeaderSize(int maxRequestHeaderSize) {
        LocalS3Config.requireMaxRequestHeaderSize(maxRequestHeaderSize);
        this.maxRequestHeaderSize = maxRequestHeaderSize;
        return this;
    }

    /**
     * Set the seconds after which a connection without reads or writes is closed. A connection with a
     * request in flight is never closed. Default value is
     * {@linkplain LocalS3Config#DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS}; {@code 0} never closes idle connections.
     *
     * @param idleConnectionTimeoutSeconds idle connection timeout in seconds, not negative.
     * @return builder.
     */
    public LocalS3Builder idleConnectionTimeoutSeconds(long idleConnectionTimeoutSeconds) {
        LocalS3Config.requireIdleConnectionTimeoutSeconds(idleConnectionTimeoutSeconds);
        this.idleConnectionTimeoutSeconds = idleConnectionTimeoutSeconds;
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
    public LocalS3Builder compositeMultipartEtags(boolean compositeMultipartEtags) {
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
    public LocalS3Builder virtualHostDomains(String... domains) {
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
     * Set a recorder that receives every request that the service answered, once its response is written, e.g. to
     * record metrics of the requests. It receives the requests that {@code GET /_admin/stats} counts, and the health
     * checks and administration requests that it doesn't. It is called on the event loop of the connection, so it
     * must be quick and thread-safe; an exception that it throws is logged.
     *
     * @param requestRecorder the recorder.
     * @return builder.
     */
    public LocalS3Builder requestRecorder(@NonNull RequestRecorder requestRecorder) {
        this.requestRecorder = Objects.requireNonNull(requestRecorder);
        return this;
    }

    /**
     * Set when the changes of a {@code PERSISTENCE} service reach the disk.
     *
     * <p>{@linkplain PersistencePolicy#DURABLE}, the default, commits the metadata of every change, so a process that
     * is killed loses nothing. Every commit appends a chunk to the file of the data directory, so a bulk load leaves
     * one per object: loading twenty thousand objects writes about 420 MB for about 5 MB of metadata, and the room is
     * only reclaimed when the store is closed, which compacts the file.
     *
     * <p>{@linkplain PersistencePolicy#FAST} lets the store commit in the background instead, at most a second after
     * a change, and commits what is left when the service is shut down. The same load then writes about 5 MB and
     * takes about a tenth of the time. A killed process loses the changes of the last second, which is the trade
     * a data directory built for a test can usually make.
     *
     * <p>An {@code IN_MEMORY} service writes nothing, so the policy doesn't apply to it.
     *
     * @param persistencePolicy when the changes reach the disk.
     * @return builder.
     */
    public LocalS3Builder persistencePolicy(@NonNull PersistencePolicy persistencePolicy) {
        this.persistencePolicy = persistencePolicy;
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
     * Serve HTTPS instead of plain HTTP, with a certificate and its private key in PEM format. Clients that use HTTPS
     * by default, e.g. DuckDB, Hadoop S3A or the {@code object_store} crate, then connect without turning TLS off.
     *
     * <p>For local development, <a href="https://github.com/FiloSottile/mkcert">mkcert</a> creates a certificate that
     * the machine trusts: {@code mkcert -install} once, then {@code mkcert localhost 127.0.0.1} creates
     * {@code localhost+1.pem} and {@code localhost+1-key.pem}. A JVM client trusts it only once the CA of mkcert,
     * {@code $(mkcert -CAROOT)/rootCA.pem}, is in its trust store.
     *
     * <p>The files are read, and the certificate and key validated, when this method is called. The service serves only
     * HTTPS on its port; plain HTTP requests to it fail.
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
     * {@linkplain LocalS3Environment#LOCAL_S3_TLS_KEY}, {@linkplain LocalS3Environment#AWS_BUCKETS},
     * {@linkplain LocalS3Environment#AWS_ACCESS_KEY_ID} and {@linkplain LocalS3Environment#AWS_SECRET_ACCESS_KEY}.
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
     * Build the configuration of a {@linkplain LocalS3} service from the values set so far. Changing the builder
     * afterwards doesn't change the configuration.
     *
     * @return the configuration.
     */
    public LocalS3Config buildConfig() {
        return new LocalS3Config(bindHost, port, dataPath, mode, persistencePolicy, defaultBuckets, changeListeners,
                changeListenerExecutor, initialDataCacheEnabled, maxInMemoryBytes, daemonThreads, registerShutdownHook,
                nettyParentEventGroupThreadNum, nettyChildEventGroupThreadNum, s3ExecutorThreadNum, virtualThreads,
                accessKeyId, secretAccessKey, maxRequestBodySize, requestBodyFileThreshold, maxRequestHeaderSize,
                idleConnectionTimeoutSeconds, compositeMultipartEtags,
                virtualHostDomains, requestRecorder, tls);
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

}
