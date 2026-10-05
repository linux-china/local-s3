package com.robothy.s3.rest;

import com.robothy.s3.rest.netty.RequestRecorder;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * The settings of the HTTP server of a service: the threads that serve the requests, the limits that a request is
 * held to, how the server relates to the JVM it runs in, and the recorder of the requests it answered.
 * {@linkplain LocalS3Builder#netty(Consumer)} hands out the one that the builder builds its configuration from.
 */
public final class NettySettings {

    int parentEventGroupThreadNum = LocalS3Config.DEFAULT_NETTY_PARENT_EVENT_GROUP_THREAD_NUM;

    int childEventGroupThreadNum = LocalS3Config.DEFAULT_NETTY_CHILD_EVENT_GROUP_THREAD_NUM;

    int s3ExecutorThreadNum = LocalS3Config.DEFAULT_S3_EXECUTOR_THREAD_NUM;

    boolean virtualThreads = true;

    boolean daemonThreads = true;

    long maxRequestBodySize = LocalS3Config.DEFAULT_MAX_REQUEST_BODY_SIZE;

    long requestBodyFileThreshold = LocalS3Config.DEFAULT_REQUEST_BODY_FILE_THRESHOLD;

    int maxRequestHeaderSize = LocalS3Config.DEFAULT_MAX_REQUEST_HEADER_SIZE;

    long idleConnectionTimeoutSeconds = LocalS3Config.DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS;

    boolean registerShutdownHook = true;

    RequestRecorder requestRecorder = RequestRecorder.NONE;

    NettySettings() {
    }

    /**
     * Set the number of threads that accept connections. Default value is
     * {@linkplain LocalS3Config#DEFAULT_NETTY_PARENT_EVENT_GROUP_THREAD_NUM}.
     *
     * @param threadNum netty parent event group thread number.
     * @return these settings.
     */
    public NettySettings parentEventGroupThreadNum(int threadNum) {
        this.parentEventGroupThreadNum = threadNum;
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
        this.childEventGroupThreadNum = threadNum;
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
        this.s3ExecutorThreadNum = threadNum;
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
        this.virtualThreads = virtualThreads;
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
        this.daemonThreads = daemonThreads;
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
        this.maxRequestBodySize = maxRequestBodySize;
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
        this.requestBodyFileThreshold = requestBodyFileThreshold;
        return this;
    }

    /**
     * Set the max size in bytes of the header section of a request, i.e. of all its header lines, which is also
     * the max length of its request line. A request whose headers exceed it is answered with
     * {@code 400 RequestHeaderSectionTooLarge}, one whose request line exceeds it with {@code 400 BadRequest}, and
     * its connection is closed. Default value is {@linkplain LocalS3Config#DEFAULT_MAX_REQUEST_HEADER_SIZE}.
     *
     * @param maxRequestHeaderSize max request header size in bytes, positive.
     * @return these settings.
     * @throws IllegalArgumentException if the size isn't positive.
     */
    public NettySettings maxRequestHeaderSize(int maxRequestHeaderSize) {
        LocalS3Config.requireMaxRequestHeaderSize(maxRequestHeaderSize);
        this.maxRequestHeaderSize = maxRequestHeaderSize;
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
        this.idleConnectionTimeoutSeconds = idleConnectionTimeoutSeconds;
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
        this.registerShutdownHook = registerShutdownHook;
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
        this.requestRecorder = Objects.requireNonNull(requestRecorder);
        return this;
    }

}
