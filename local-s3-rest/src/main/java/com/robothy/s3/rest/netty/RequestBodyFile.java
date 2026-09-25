package com.robothy.s3.rest.netty;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.EventExecutor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The temporary file that the body of a request is written to, off the event loop.
 *
 * <p>The event loop that receives the body only queues its chunks. Every operation on the file, creating, writing,
 * memory-mapping, closing and deleting it, runs on {@code executor}, one at a time and in order, so that a slow disk
 * holds up neither the event loop nor the other connections it serves. The chunks queued at once are written with a
 * single gathering write.
 *
 * <p>A complete body of up to {@value #MAX_MAPPED_BYTES} bytes is memory-mapped, see {@linkplain MappedFileByteBuf}. A
 * larger one can't be: no {@code ByteBuf} holds more bytes. It is handed on as a {@linkplain FileBodyByteBuf}, whose
 * content is only read from the file.
 *
 * <p>The bytes that are queued but not written yet are bounded by the receiver: {@linkplain #write(ByteBuf)} reports
 * when they exceed {@value #HIGH_WATER_MARK} bytes, and the receiver then stops reading the connection until
 * {@linkplain Listener#drained()} reports that they dropped to {@value #LOW_WATER_MARK} bytes.
 *
 * <p>An {@code aws-chunked} body can be decoded while it is written, see {@linkplain AwsChunkedBodyDecoder}, so that the
 * file holds the decoded content, which a storage can take over like the body of any other upload. A body that fails to
 * decode, e.g. whose chunk signatures don't match, fails the file like a write does, with a
 * {@linkplain RequestBodyRejection}.
 *
 * <p>The listener is called on the event loop, and not at all once the file is {@linkplain #discard() discarded}.
 */
final class RequestBodyFile {

  private static final Logger log = LoggerFactory.getLogger(RequestBodyFile.class);

  static final long HIGH_WATER_MARK = 4L << 20;

  static final long LOW_WATER_MARK = 1L << 20;

  /**
   * The bytes written before the file gives its thread up to the other tasks of the executor, e.g. when the executor is
   * a pool of platform threads that the requests are handled on too.
   */
  private static final long MAX_BYTES_PER_RUN = 64L << 20;

  /**
   * The largest body that is memory-mapped: a {@linkplain java.nio.MappedByteBuffer} holds at most
   * {@linkplain Integer#MAX_VALUE} bytes.
   */
  static final long MAX_MAPPED_BYTES = Integer.MAX_VALUE;

  /**
   * Receives the events of a body file, on the event loop.
   */
  interface Listener {

    /**
     * The queued bytes dropped to the low water mark, after {@linkplain #write(ByteBuf)} reported that they exceeded
     * the high water mark.
     */
    void drained();

    /**
     * The body is complete, after {@linkplain #complete()}.
     *
     * @param body the body memory-mapped from the file, or a {@linkplain FileBodyByteBuf} if the body is too large to
     *     be mapped, which the listener owns.
     */
    void completed(ByteBuf body);

    /**
     * The body failed to be written, e.g. because the disk is full, or the executor was shut down. The file is
     * deleted, and the chunks queued after the failure are released.
     *
     * @param cause the failure.
     */
    void failed(Throwable cause);
  }

  private final Executor executor;

  private final EventExecutor eventLoop;

  private final Path directory;

  private final Listener listener;

  /**
   * The largest body that is memory-mapped; a larger one is handed on as a {@linkplain FileBodyByteBuf}.
   */
  private final long maxMappedBytes;

  /**
   * Decodes the {@code aws-chunked} body as it is written; {@code null} to write the body as it is received.
   */
  private final AwsChunkedBodyDecoder decoder;

  // Guarded by this.

  private final ArrayDeque<ByteBuf> queue = new ArrayDeque<>();

  private long queuedBytes;

  private boolean backlogged;

  /**
   * Whether a run of the file operations is scheduled or running; at most one is.
   */
  private boolean running;

  private boolean completeRequested;

  private boolean discarded;

  /**
   * Whether the file was completed, failed, or discarded and deleted; nothing is done with it anymore.
   */
  private boolean done;

  private RejectedExecutionException rejection;

  // Only accessed by the run of the file operations.

  private Path file;

  private FileChannel channel;

  private long writtenBytes;

  /**
   * Create a body file, which is created on the disk once it is first written.
   *
   * @param executor runs the file operations.
   * @param eventLoop the event loop of the connection, which the listener is called on.
   * @param directory the directory of the file; {@code null} for the default temporary directory.
   * @param listener receives the events of the file.
   */
  RequestBodyFile(Executor executor, EventExecutor eventLoop, Path directory, Listener listener) {
    this(executor, eventLoop, directory, listener, null);
  }

  /**
   * Create a body file, which is created on the disk once it is first written.
   *
   * @param executor runs the file operations.
   * @param eventLoop the event loop of the connection, which the listener is called on.
   * @param directory the directory of the file; {@code null} for the default temporary directory.
   * @param listener receives the events of the file.
   * @param decoder decodes the {@code aws-chunked} body as it is written, so that the file holds the decoded content;
   *     {@code null} to write the body as it is received.
   */
  RequestBodyFile(Executor executor, EventExecutor eventLoop, Path directory, Listener listener,
                  AwsChunkedBodyDecoder decoder) {
    this(executor, eventLoop, directory, listener, MAX_MAPPED_BYTES, decoder);
  }

  /**
   * Create a body file, which is created on the disk once it is first written. For tests, which exercise the bodies
   * that are too large to be mapped with a lower limit.
   *
   * @param executor runs the file operations.
   * @param eventLoop the event loop of the connection, which the listener is called on.
   * @param directory the directory of the file; {@code null} for the default temporary directory.
   * @param listener receives the events of the file.
   * @param maxMappedBytes the largest body that is memory-mapped, at most {@value #MAX_MAPPED_BYTES}.
   */
  RequestBodyFile(Executor executor, EventExecutor eventLoop, Path directory, Listener listener,
                  long maxMappedBytes) {
    this(executor, eventLoop, directory, listener, maxMappedBytes, null);
  }

  private RequestBodyFile(Executor executor, EventExecutor eventLoop, Path directory, Listener listener,
                          long maxMappedBytes, AwsChunkedBodyDecoder decoder) {
    if (maxMappedBytes < 0 || maxMappedBytes > MAX_MAPPED_BYTES) {
      throw new IllegalArgumentException("maxMappedBytes must be between 0 and " + MAX_MAPPED_BYTES + ".");
    }
    this.maxMappedBytes = maxMappedBytes;
    this.executor = Objects.requireNonNull(executor);
    this.eventLoop = Objects.requireNonNull(eventLoop);
    this.directory = directory;
    this.listener = Objects.requireNonNull(listener);
    this.decoder = decoder;
  }

  /**
   * Queue a chunk of the body to be written.
   *
   * @param data the chunk, which this file owns.
   * @return whether the queued bytes exceed the high water mark, in which case {@linkplain Listener#drained()} is
   *     called once they dropped to the low water mark.
   */
  boolean write(ByteBuf data) {
    boolean start;
    boolean overHighWaterMark;
    synchronized (this) {
      if (discarded || completeRequested || done) {
        data.release();
        return false;
      }
      queue.add(data);
      queuedBytes += data.readableBytes();
      overHighWaterMark = queuedBytes > HIGH_WATER_MARK;
      backlogged |= overHighWaterMark;
      start = claimRun();
    }
    if (start) {
      schedule();
    }
    return overHighWaterMark;
  }

  /**
   * Complete the body once the queued chunks are written, and hand it to {@linkplain Listener#completed(ByteBuf)}.
   */
  void complete() {
    boolean start;
    synchronized (this) {
      completeRequested = true;
      start = claimRun();
    }
    if (start) {
      schedule();
    }
  }

  /**
   * Give the body up: release the queued chunks and delete the file, once a write in progress is done.
   */
  void discard() {
    boolean start;
    synchronized (this) {
      discarded = true;
      start = claimRun();
    }
    if (start) {
      schedule();
    }
  }

  private boolean claimRun() {
    if (running) {
      return false;
    }
    running = true;
    return true;
  }

  private void schedule() {
    try {
      executor.execute(this::run);
    } catch (RejectedExecutionException e) {
      // The server is shutting down: give the body up here, which only deletes the file.
      synchronized (this) {
        rejection = e;
      }
      run();
    }
  }

  private enum Step { WRITE, YIELD, FINISH, GIVE_UP }

  private void run() {
    long bytesThisRun = 0;
    while (true) {
      Step step;
      ByteBuf[] batch = null;
      long batchBytes = 0;
      synchronized (this) {
        if (done) {
          releaseQueue();
          running = false;
          return;
        }
        if (discarded || rejection != null) {
          step = Step.GIVE_UP;
        } else if (!queue.isEmpty() && bytesThisRun >= MAX_BYTES_PER_RUN) {
          step = Step.YIELD;
        } else if (!queue.isEmpty()) {
          step = Step.WRITE;
          batch = queue.toArray(new ByteBuf[0]);
          batchBytes = queuedBytes;
          queue.clear();
        } else if (completeRequested) {
          step = Step.FINISH;
        } else {
          running = false;
          return;
        }
      }

      switch (step) {
        case GIVE_UP -> {
          fail(rejection());
          return;
        }
        case FINISH -> {
          finish();
          return;
        }
        case YIELD -> {
          try {
            // Still running: the next run goes on.
            executor.execute(this::run);
            return;
          } catch (RejectedExecutionException e) {
            synchronized (this) {
              rejection = e;
            }
          }
        }
        default -> {
          try {
            writeBatch(batch, batchBytes);
          } catch (IOException | RuntimeException e) {
            fail(e);
            return;
          } finally {
            for (ByteBuf data : batch) {
              data.release();
            }
          }
          bytesThisRun += batchBytes;
          drained(batchBytes);
        }
      }
    }
  }

  private void drained(long writtenBatchBytes) {
    boolean drained = false;
    synchronized (this) {
      queuedBytes -= writtenBatchBytes;
      if (backlogged && queuedBytes <= LOW_WATER_MARK) {
        backlogged = false;
        drained = true;
      }
    }
    if (drained) {
      notifyListener(listener::drained, () -> { });
    }
  }

  private synchronized IOException rejection() {
    return rejection == null ? null
        : new IOException("The request body can't be written: the server is shutting down.", rejection);
  }

  private void open() throws IOException {
    if (channel == null) {
      file = directory == null
          ? Files.createTempFile(LocalS3HttpRequestDecoder.BODY_FILE_PREFIX, ".tmp")
          : Files.createTempFile(directory, LocalS3HttpRequestDecoder.BODY_FILE_PREFIX, ".tmp");
      channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
  }

  private void writeBatch(ByteBuf[] batch, long batchBytes) throws IOException {
    open();
    if (decoder == null) {
      write(batch, batchBytes);
      return;
    }
    List<ByteBuf> decoded = new ArrayList<>();
    try {
      long decodedBytes = 0;
      for (ByteBuf data : batch) {
        decoder.decode(data, decoded);
      }
      for (ByteBuf data : decoded) {
        decodedBytes += data.readableBytes();
      }
      write(decoded.toArray(new ByteBuf[0]), decodedBytes);
    } finally {
      for (ByteBuf data : decoded) {
        data.release();
      }
    }
  }

  private void write(ByteBuf[] batch, long batchBytes) throws IOException {
    List<ByteBuffer> buffers = new ArrayList<>(batch.length);
    for (ByteBuf data : batch) {
      Collections.addAll(buffers, data.nioBuffers());
    }
    ByteBuffer[] nioBuffers = buffers.toArray(new ByteBuffer[0]);
    long remaining = batchBytes;
    while (remaining > 0) {
      remaining -= channel.write(nioBuffers);
    }
    writtenBytes += batchBytes;
  }

  /**
   * Map the complete file as the body, or hand it on unmapped if it is too large to be mapped. The body owns the file
   * from then on.
   */
  private void finish() {
    ByteBuf body;
    try {
      Map<String, String> trailer = decoder == null ? null : decoder.finish();
      open();
      if (writtenBytes > maxMappedBytes) {
        FileBodyByteBuf fileBody = new FileBodyByteBuf(file, writtenBytes);
        fileBody.awsChunkedTrailer(trailer);
        body = fileBody;
      } else {
        MappedFileByteBuf mapped = MappedFileByteBuf.map(channel, file, writtenBytes);
        mapped.awsChunkedTrailer(trailer);
        body = mapped;
      }
      // The mapping outlives the channel.
      channel.close();
      channel = null;
      file = null;
    } catch (IOException | RuntimeException e) {
      fail(e);
      return;
    }
    synchronized (this) {
      done = true;
      running = false;
    }
    notifyListener(() -> listener.completed(body), body::release);
  }

  /**
   * Delete the file, and report the failure unless the body was discarded.
   *
   * @param cause the failure; {@code null} if the body was discarded.
   */
  private void fail(Exception cause) {
    closeAndDelete();
    synchronized (this) {
      done = true;
      releaseQueue();
      running = false;
    }
    if (cause != null) {
      notifyListener(() -> listener.failed(cause), () -> { });
    }
  }

  private void closeAndDelete() {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException e) {
        log.debug("Failed to close temporary request body file {}.", file, e);
      }
      channel = null;
    }
    if (file != null) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        log.warn("Failed to delete temporary request body file {}.", file, e);
        file.toFile().deleteOnExit();
      }
      file = null;
    }
  }

  private void releaseQueue() {
    ByteBuf data;
    while ((data = queue.poll()) != null) {
      data.release();
    }
    queuedBytes = 0;
  }

  /**
   * Call the listener on the event loop, unless the body was discarded meanwhile, which only the event loop does.
   */
  private void notifyListener(Runnable call, Runnable ifDiscarded) {
    try {
      eventLoop.execute(() -> {
        boolean discardedMeanwhile;
        synchronized (this) {
          discardedMeanwhile = discarded;
        }
        (discardedMeanwhile ? ifDiscarded : call).run();
      });
    } catch (RejectedExecutionException e) {
      // The event loop has terminated, and the connection with it.
      ifDiscarded.run();
    }
  }

}
