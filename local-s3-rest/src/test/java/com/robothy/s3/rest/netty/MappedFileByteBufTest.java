package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBufUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappedFileByteBufTest {

  @TempDir
  Path directory;

  private MappedFileByteBuf map(Path file, byte[] content) throws IOException {
    Files.write(file, content);
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      return MappedFileByteBuf.map(channel, file, content.length);
    }
  }

  @Test
  void readsTheMappedFile() throws IOException {
    Path file = directory.resolve("body");
    byte[] content = "hello, mapped body".getBytes();

    MappedFileByteBuf buf = map(file, content);
    try {
      assertEquals(content.length, buf.readableBytes());
      assertArrayEquals(content, ByteBufUtil.getBytes(buf));
      assertEquals(file, buf.file());
    } finally {
      buf.release();
    }
  }

  /**
   * Releasing unmaps the file and deletes it; the buffer that was handed out then throws rather than reading unmapped
   * memory.
   */
  @Test
  void releaseUnmapsAndDeletesTheFile() throws IOException {
    Path file = directory.resolve("body");
    MappedFileByteBuf buf = map(file, new byte[4096]);
    ByteBuffer nio = buf.nioBuffer();

    assertTrue(buf.release());

    assertFalse(Files.exists(file));
    assertThrows(IllegalStateException.class, () -> nio.get(0));
  }

  /**
   * A body is mapped on the executor and released on an event loop, i.e. on another thread.
   */
  @Test
  void isReleasedOnAnotherThread() throws Exception {
    Path file = directory.resolve("body");
    MappedFileByteBuf buf = map(file, new byte[1024]);

    AtomicBoolean released = new AtomicBoolean();
    Thread thread = Thread.ofPlatform().start(() -> released.set(buf.release()));
    thread.join();

    assertTrue(released.get());
    assertFalse(Files.exists(file));
  }

  /**
   * A file taken over, e.g. renamed into a storage, is kept.
   */
  @Test
  void keepsAFileTakenOver() throws IOException {
    Path file = directory.resolve("body");
    Path stored = directory.resolve("stored");
    MappedFileByteBuf buf = map(file, new byte[16]);

    Files.move(file, stored);
    buf.release();

    assertTrue(Files.exists(stored));
  }

}
