package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestBodiesTest {

  @TempDir
  Path directory;

  @Test
  void readsABodyThatTheBufferHolds() throws IOException {
    ByteBuf body = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
    try {
      assertTrue(RequestBodies.file(body).isEmpty());
      assertTrue(RequestBodies.fileOnly(body).isEmpty());
      assertEquals(5, RequestBodies.length(body));
      try (InputStream in = RequestBodies.inputStream(body)) {
        assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
      assertEquals(0, body.readableBytes(), "Reading the stream consumes the buffer.");
    } finally {
      body.release();
    }
    assertEquals(0, RequestBodies.length(null));
  }

  /**
   * A body that is only in a file is read from the file, which is opened on the first read: a storage that takes the
   * file over afterwards, e.g. renames it, doesn't find it open.
   */
  @Test
  void readsABodyThatIsOnlyInAFile() throws IOException {
    byte[] content = "a body larger than 2 GiB".getBytes(StandardCharsets.UTF_8);
    Path file = Files.write(directory.resolve("body"), content);
    ByteBuf body = RequestBodies.fileBody(file);
    try {
      assertEquals(file, RequestBodies.fileOnly(body).orElseThrow());
      assertEquals(file, RequestBodies.file(body).orElseThrow());
      assertEquals(content.length, RequestBodies.length(body));
      assertEquals(0, body.readableBytes());

      InputStream unread = RequestBodies.inputStream(body);
      try (InputStream in = RequestBodies.inputStream(body)) {
        assertEquals(content[0], in.read());
        assertEquals(4, in.skip(4));
        byte[] rest = in.readAllBytes();
        assertArrayEquals(Arrays.copyOfRange(content, 5, content.length), rest);
        assertEquals(-1, in.read());
      }

      Path taken = Files.move(file, directory.resolve("taken"));
      assertThrows(NoSuchFileException.class, unread::read, "The stream opens the file on its first read.");
      unread.close();
      Files.move(taken, file);
    } finally {
      body.release();
    }
    assertFalse(Files.exists(file), "Releasing the body deletes its file.");
  }

}
