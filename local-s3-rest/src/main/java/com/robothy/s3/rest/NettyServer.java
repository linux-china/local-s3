package com.robothy.s3.rest;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.router.Router;
import com.robothy.s3.rest.netty.InFlightRequests;
import com.robothy.s3.rest.netty.RequestRecorder;
import com.robothy.s3.rest.netty.LocalS3ServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP server of a {@linkplain LocalS3} service: the event loops that accept and serve the connections, the executor
 * that handles their requests, and the listening socket, from binding it to stopping gracefully. {@linkplain LocalS3}
 * creates the services and the router that the server serves.
 */
final class NettyServer {

    // The events of the server are logged as the events of the service, which its users configure logging for.
    private static final Logger log = LoggerFactory.getLogger(LocalS3.class);

    static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    /**
     * The name of the threads that handle the requests, followed by a number.
     */
    static final String EXECUTOR_THREAD_NAME = "locals3-executor-group";

    private final LocalS3Config config;

    private MultiThreadIoEventLoopGroup parentGroup;

    private MultiThreadIoEventLoopGroup childGroup;

    private ExecutorService executor;

    /**
     * The requests of all connections whose responses aren't written yet, which stopping waits for.
     */
    private InFlightRequests inFlightRequests;

    /**
     * The threads of {@linkplain #executor}, so that stopping from one of them doesn't wait for itself.
     */
    private final Set<Thread> executorThreads = ConcurrentHashMap.newKeySet();

    private Channel serverSocketChannel;

    private NettyServer(LocalS3Config config) {
        this.config = config;
    }

    /**
     * Start a server. If it fails to start, what was started so far is stopped, and the original exception is thrown.
     *
     * @param config                   the configuration of the server: the host and port to bind, its threads, and
     *                                 the limits of its requests.
     * @param router                   routes the requests to their handlers.
     * @param xmlMapper                renders the errors of malformed requests.
     * @param requestBodyFileDirectory the directory that large request bodies are buffered in; {@code null} for the
     *                                 default temporary directory.
     * @param requestRecorder          receives the requests once their responses are written.
     * @return the started server.
     */
    static NettyServer start(LocalS3Config config, Router router, XmlMapper xmlMapper,
                             Path requestBodyFileDirectory, RequestRecorder requestRecorder) {
        NettyServer server = new NettyServer(config);
        try {
            server.bind(config.port(), router, xmlMapper, requestBodyFileDirectory, requestRecorder);
        } catch (Throwable e) {
            server.stop();
            throw e;
        }
        return server;
    }

    private void bind(int port, Router router, XmlMapper xmlMapper, Path requestBodyFileDirectory,
                      RequestRecorder requestRecorder) {
        this.parentGroup = new MultiThreadIoEventLoopGroup(config.nettyParentEventGroupThreadNum(),
                new NamingThreadFactory("locals3-parent-event-group", config.daemonThreads()),
                NioIoHandler.newFactory());
        this.childGroup = new MultiThreadIoEventLoopGroup(config.nettyChildEventGroupThreadNum(),
                new NamingThreadFactory("locals3-child-event-group", config.daemonThreads()),
                NioIoHandler.newFactory());
        this.executor = createExecutor();
        this.inFlightRequests = new InFlightRequests();
        try {
            this.serverSocketChannel = new ServerBootstrap().group(parentGroup, childGroup)
                    .handler(new LoggingHandler(LogLevel.DEBUG))
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new LocalS3ServerInitializer(config, executor, router, xmlMapper,
                            requestBodyFileDirectory, inFlightRequests, requestRecorder))
                    .bind(config.bindHost(), port)
                    .sync()
                    .channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting LocalS3.", e);
        }
    }

    /**
     * The number of requests whose responses aren't written yet.
     */
    int inFlightRequests() {
        InFlightRequests requests = this.inFlightRequests;
        return requests == null ? 0 : requests.count();
    }

    int port() {
        return ((InetSocketAddress) serverSocketChannel.localAddress()).getPort();
    }

    /**
     * Create the executor that handles the requests of all connections, and writes the bodies that are buffered in
     * temporary files: a virtual thread per task, or a pool of platform threads. The queue of the pool holds at most one
     * request, or one write of its body, per connection, as a connection stops reading while its request is in flight,
     * and writes its body one batch after the other.
     */
    private ExecutorService createExecutor() {
        ThreadFactory threadFactory = config.virtualThreads()
                ? Thread.ofVirtual().name(EXECUTOR_THREAD_NAME + "-", 0).factory()
                : new NamingThreadFactory(EXECUTOR_THREAD_NAME, config.daemonThreads());
        // Every thread records itself while it runs, so that stopping from one of them doesn't wait for itself.
        ThreadFactory trackingThreadFactory = runnable -> threadFactory.newThread(() -> {
            executorThreads.add(Thread.currentThread());
            try {
                runnable.run();
            } finally {
                executorThreads.remove(Thread.currentThread());
            }
        });
        if (config.virtualThreads()) {
            return Executors.newThreadPerTaskExecutor(trackingThreadFactory);
        }
        int threads = config.s3ExecutorThreadNum();
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                trackingThreadFactory);
    }

    /**
     * Stop the server gracefully, as far as it was started, within {@linkplain #SHUTDOWN_TIMEOUT_SECONDS}:
     * <ol>
     *   <li>close the server socket, so that no connection is accepted anymore;</li>
     *   <li>shut the request executor down, so that no further request is handled, and the connections that send one
     *   are closed, and wait for the requests being handled to finish;</li>
     *   <li>wait for the responses of those requests to be written, which the event loops write;</li>
     *   <li>stop the event loops, which closes the remaining connections.</li>
     * </ol>
     * A step that the time runs out in is cut short: the requests still running are interrupted, and the responses
     * still being written are cut off.
     */
    void stop() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);
        boolean stopped = false;
        try {
            closeServerSocket();
            stopped = shutdownExecutorIfNeeded(deadline);
            awaitResponsesInFlight(deadline);
        } finally {
            stopped |= shutdownEventExecutorsGroupIfNeeded(this.childGroup, this.parentGroup);
            ExecutorService executorService = this.executor;
            if (executorService != null && !executorService.isTerminated()) {
                executorService.shutdownNow();
            }
            if (stopped) {
                log.info("LocalS3 stopped.");
            }
            this.serverSocketChannel = null;
            this.childGroup = null;
            this.parentGroup = null;
            this.executor = null;
            this.inFlightRequests = null;
        }
    }

    private void closeServerSocket() {
        try {
            if (this.serverSocketChannel != null && this.serverSocketChannel.isOpen()) {
                this.serverSocketChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Close server socket channel failed.", e);
        }
    }

    /**
     * Wait for the responses of the requests that were handled to be written. Not from a thread that handles a request,
     * whose own response is in flight, nor from an event loop, which the responses are written on.
     */
    private void awaitResponsesInFlight(long deadline) {
        InFlightRequests requests = this.inFlightRequests;
        if (requests == null || executorThreads.contains(Thread.currentThread())
                || (childGroup != null && isInEventLoop(childGroup))) {
            return;
        }
        try {
            if (!requests.awaitIdle(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                log.warn("{} responses were still being written after {} seconds.", requests.count(),
                        SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shut the event loops down and wait for them to terminate.
     *
     * @return whether any of them was shut down by this call.
     */
    private boolean shutdownEventExecutorsGroupIfNeeded(EventExecutorGroup... eventExecutorsList) {
        boolean shutdownPerformed = false;
        for (EventExecutorGroup eventExecutors : eventExecutorsList) {
            if (eventExecutors != null && !eventExecutors.isShuttingDown() && !eventExecutors.isShutdown()) {
                shutdownPerformed = true;
                // No quiet period: the requests in flight were waited for before, and the listening socket is only
                // released once the event loops have terminated.
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

        return shutdownPerformed;
    }

    /**
     * Shut the request executor down and wait for the requests being handled to finish, until the deadline. The
     * requests that are still running then are interrupted once the event loops are stopped.
     *
     * @return whether the executor was shut down by this call.
     */
    private boolean shutdownExecutorIfNeeded(long deadline) {
        ExecutorService executorService = this.executor;
        if (executorService == null || executorService.isShutdown()) {
            return false;
        }
        executorService.shutdown();
        if (executorThreads.contains(Thread.currentThread())) {
            return true; // Waiting for our own thread to terminate would deadlock.
        }
        try {
            if (!executorService.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                log.warn("Request executor did not terminate within {} seconds.", SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
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
