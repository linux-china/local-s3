package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.storage.CompositeInputStream;
import com.robothy.s3.core.storage.ContentRetention;
import com.robothy.s3.core.storage.Storage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedInput;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalS3HttpResponseEncoderTest {

  /**
   * The content of an object stored in parts is sent part by part, and one part at a time: a file-backed part as a
   * file region, and any other part chunk by chunk, so that no file is read through the heap on the event loop and
   * no more than one part is open.
   */
  @Test
  void sendsEveryPartOfAnObjectStoredInPartsTheWayItSupports(@TempDir Path directory) throws Exception {
    Storage files = Storage.createPersistent(directory);
    Long first = files.put("Hello".getBytes());
    Long third = files.put("S3!".getBytes());
    StreamingHttpResponse response = new StreamingHttpResponse();
    response.status(HttpResponseStatus.OK).putHeader("Content-Length", 13);
    response.stream(new CompositeInputStream(List.<CompositeInputStream.Part>of(
        () -> files.getInputStream(first),
        () -> new ByteArrayInputStream("Local".getBytes()),
        () -> files.getInputStream(third)), ContentRetention.NONE));

    EmbeddedChannel channel = new EmbeddedChannel(new LocalS3HttpResponseEncoder());
    try {
      assertTrue(channel.writeOutbound(response));
      assertInstanceOf(HttpResponse.class, channel.readOutbound());
      ChunkedInput<Object> content = assertInstanceOf(ChunkedInput.class, channel.readOutbound());

      DefaultFileRegion region1 = assertInstanceOf(DefaultFileRegion.class, content.readChunk(channel.alloc()));
      assertEquals(5, region1.count());
      assertNull(content.readChunk(channel.alloc()), "The next part waits for the region to be written.");
      region1.release();

      ByteBuf chunk = assertInstanceOf(ByteBuf.class, content.readChunk(channel.alloc()));
      byte[] bytes = new byte[chunk.readableBytes()];
      chunk.readBytes(bytes);
      chunk.release();
      assertArrayEquals("Local".getBytes(), bytes);

      DefaultFileRegion region3 = assertInstanceOf(DefaultFileRegion.class, content.readChunk(channel.alloc()));
      assertEquals(3, region3.count());
      assertFalse(content.isEndOfInput());
      region3.release();

      assertEquals(LastHttpContent.EMPTY_LAST_CONTENT, content.readChunk(channel.alloc()));
      assertTrue(content.isEndOfInput());
      content.close();
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void inFlightRequestsAreAwaited() throws Exception {
    InFlightRequests requests = new InFlightRequests();
    assertTrue(requests.awaitIdle(0, TimeUnit.MILLISECONDS));
    requests.begin();
    requests.begin();
    assertFalse(requests.awaitIdle(10, TimeUnit.MILLISECONDS));

    CompletableFuture<Boolean> idle = CompletableFuture.supplyAsync(() -> {
      try {
        return requests.awaitIdle(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    });
    requests.end();
    assertEquals(1, requests.count());
    requests.end();
    assertTrue(idle.get(10, TimeUnit.SECONDS));
    requests.end();
    assertEquals(0, requests.count(), "An extra end doesn't count below zero.");
  }

}
