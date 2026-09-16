package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestBodyFileTest {

  @TempDir
  Path directory;

  private final ManualExecutor executor = new ManualExecutor();

  private final RecordingListener listener = new RecordingListener();

  private RequestBodyFile bodyFile(Executor executor, Path directory) {
    return new RequestBodyFile(executor, ImmediateEventExecutor.INSTANCE, directory, listener);
  }

  /**
   * The caller only queues the chunks: the file is created, written and mapped by the executor.
   */
  @Test
  void writesTheBodyOnTheExecutor() throws IOException {
    RequestBodyFile file = bodyFile(executor, directory);
    byte[] content = randomBytes(1000);

    assertFalse(file.write(Unpooled.copiedBuffer(content, 0, 400)));
    assertFalse(file.write(Unpooled.copiedBuffer(content, 400, 600)));
    file.complete();
    assertEquals(0, countFiles(), "Nothing touches the disk before the executor runs.");
    assertNull(listener.body);

    executor.runAll();

    ByteBuf body = listener.body;
    try {
      assertInstanceOf(MappedFileByteBuf.class, body);
      assertArrayEquals(content, bytes(body));
      assertEquals(1, countFiles(), "The body owns the file.");
    } finally {
      body.release();
    }
    assertEquals(0, countFiles());
    assertEquals(1, executor.runs, "The queued chunks are written in one run.");
  }

  /**
   * A body larger than a buffer can map, i.e. 2 GiB, which the test lowers, is handed on unmapped: its content is only
   * read from the file, which it owns like a mapped body.
   */
  @Test
  void handsOnABodyTooLargeToBeMappedAsItsFile() throws IOException {
    RequestBodyFile file = new RequestBodyFile(executor, ImmediateEventExecutor.INSTANCE, directory, listener, 999);
    byte[] content = randomBytes(1000);

    file.write(Unpooled.copiedBuffer(content));
    file.complete();
    executor.runAll();

    ByteBuf body = listener.body;
    Path bodyFile = RequestBodies.fileOnly(body).orElseThrow();
    try {
      assertInstanceOf(FileBodyByteBuf.class, body);
      assertEquals(0, body.readableBytes());
      assertEquals(1000, RequestBodies.length(body));
      assertEquals(bodyFile, RequestBodies.file(body).orElseThrow());
      try (InputStream in = RequestBodies.inputStream(body)) {
        assertArrayEquals(content, in.readAllBytes());
      }
    } finally {
      body.release();
    }
    assertFalse(Files.exists(bodyFile), "Releasing the body deletes its file.");
    assertEquals(0, countFiles());
  }

  @Test
  void completesAnEmptyBody() {
    RequestBodyFile file = bodyFile(executor, directory);
    file.complete();
    executor.runAll();

    assertEquals(0, listener.body.readableBytes());
    listener.body.release();
  }

  /**
   * The bytes queued beyond the high water mark are reported, and so is the moment they are written.
   */
  @Test
  void reportsTheBacklogOfQueuedBytes() {
    RequestBodyFile file = bodyFile(executor, directory);
    int chunk = (int) RequestBodyFile.LOW_WATER_MARK;
    List<ByteBuf> chunks = new ArrayList<>();

    boolean backlogged = false;
    for (int i = 0; i * (long) chunk <= RequestBodyFile.HIGH_WATER_MARK; i++) {
      ByteBuf data = Unpooled.buffer(chunk).writeZero(chunk);
      chunks.add(data);
      backlogged = file.write(data);
    }
    assertTrue(backlogged);
    assertEquals(0, listener.drained);

    executor.runAll();
    assertEquals(1, listener.drained);
    assertTrue(chunks.stream().allMatch(data -> data.refCnt() == 0), "The written chunks are released.");

    assertFalse(file.write(Unpooled.buffer(1).writeZero(1)));
    executor.runAll();
    assertEquals(1, listener.drained, "Nothing is reported without a backlog.");
    file.discard();
    executor.runAll();
  }

  @Test
  void discardingReleasesTheQueuedChunksAndDeletesTheFile() throws IOException {
    RequestBodyFile file = bodyFile(executor, directory);
    file.write(Unpooled.copiedBuffer(randomBytes(100)));
    executor.runAll();
    assertEquals(1, countFiles());

    ByteBuf queued = Unpooled.copiedBuffer(randomBytes(100));
    file.write(queued);
    file.complete();
    file.discard();
    executor.runAll();

    assertEquals(0, queued.refCnt());
    assertEquals(0, countFiles());
    assertNull(listener.body, "A discarded body is never handed over.");
    assertNull(listener.failure);
    ByteBuf late = Unpooled.buffer(1).writeZero(1);
    file.write(late);
    assertEquals(0, late.refCnt());
  }

  @Test
  void reportsAFailureAndReleasesTheChunks() {
    RequestBodyFile file = bodyFile(executor, directory.resolve("missing"));
    ByteBuf first = Unpooled.copiedBuffer(randomBytes(10));
    file.write(first);
    executor.runAll();

    assertInstanceOf(IOException.class, listener.failure);
    assertEquals(0, first.refCnt());
    ByteBuf late = Unpooled.buffer(1).writeZero(1);
    file.write(late);
    file.complete();
    executor.runAll();
    assertEquals(0, late.refCnt());
    assertNull(listener.body);
  }

  /**
   * An executor that was shut down fails the body, whose chunks are released on the caller.
   */
  @Test
  void failsTheBodyWhenTheExecutorRejectsIt() throws IOException {
    RequestBodyFile file = bodyFile(task -> {
      throw new RejectedExecutionException("shut down");
    }, directory);
    ByteBuf data = Unpooled.copiedBuffer(randomBytes(10));

    file.write(data);

    assertInstanceOf(IOException.class, listener.failure);
    assertInstanceOf(RejectedExecutionException.class, listener.failure.getCause());
    assertEquals(0, data.refCnt());
    assertEquals(0, countFiles());
  }

  private long countFiles() throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.count();
    }
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }

  private static byte[] bytes(ByteBuf buf) {
    byte[] bytes = new byte[buf.readableBytes()];
    buf.getBytes(buf.readerIndex(), bytes);
    return bytes;
  }

  /**
   * An executor whose tasks run when the test runs them.
   */
  static final class ManualExecutor implements Executor {

    private final Queue<Runnable> tasks = new ArrayDeque<>();

    int runs;

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    void runAll() {
      Runnable task;
      while ((task = tasks.poll()) != null) {
        runs++;
        task.run();
      }
    }

    boolean isIdle() {
      return tasks.isEmpty();
    }
  }

  private static final class RecordingListener implements RequestBodyFile.Listener {

    private int drained;

    private ByteBuf body;

    private Throwable failure;

    @Override
    public void drained() {
      drained++;
    }

    @Override
    public void completed(ByteBuf body) {
      this.body = body;
    }

    @Override
    public void failed(Throwable cause) {
      failure = cause;
    }
  }

}
