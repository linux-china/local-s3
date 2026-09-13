package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.storage.CompositeInputStream;
import com.robothy.s3.core.storage.Storage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalS3HttpResponseEncoderTest {

  /**
   * The file-backed parts of an object stored in parts are transferred as file regions, and the other parts are
   * copied chunk by chunk, so that no file is read through the heap on the event loop.
   */
  @Test
  void sendsEveryPartOfAnObjectStoredInPartsTheWayItSupports(@TempDir Path directory) throws Exception {
    Storage files = Storage.createPersistent(directory);
    Long first = files.put("Hello".getBytes());
    Long third = files.put("S3!".getBytes());
    InputStream memory = new ByteArrayInputStream("Local".getBytes());
    StreamingHttpResponse response = new StreamingHttpResponse();
    response.status(HttpResponseStatus.OK).putHeader("Content-Length", 13);
    response.stream(new CompositeInputStream(List.of(files.getInputStream(first), memory,
        files.getInputStream(third))));

    EmbeddedChannel channel = new EmbeddedChannel(new LocalS3HttpResponseEncoder());
    try {
      assertTrue(channel.writeOutbound(response));
      assertInstanceOf(HttpResponse.class, channel.readOutbound());
      DefaultFileRegion region1 = assertInstanceOf(DefaultFileRegion.class, channel.readOutbound());
      assertEquals(5, region1.count());
      ChunkedStream chunks = assertInstanceOf(ChunkedStream.class, channel.readOutbound());
      ByteBuf chunk = chunks.readChunk(channel.alloc());
      byte[] bytes = new byte[chunk.readableBytes()];
      chunk.readBytes(bytes);
      chunk.release();
      assertArrayEquals("Local".getBytes(), bytes);
      DefaultFileRegion region3 = assertInstanceOf(DefaultFileRegion.class, channel.readOutbound());
      assertEquals(3, region3.count());
      assertEquals(LastHttpContent.EMPTY_LAST_CONTENT, channel.readOutbound());
      chunks.close();
      region1.release();
      region3.release();
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
