package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LocalS3HttpRequestDecoderTest {

  private static final int FILE_THRESHOLD = 16;

  private final EmbeddedChannel channel =
      new EmbeddedChannel(new LocalS3HttpRequestDecoder(1024, FILE_THRESHOLD, new XmlMapper()));

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  @Test
  void keepsSmallBodiesOnTheHeap() {
    byte[] content = randomBytes(FILE_THRESHOLD);
    channel.writeInbound(request(content.length), last(content, 0, content.length));

    ByteBuf body = readBody();
    try {
      assertFalse(body instanceof MappedFileByteBuf);
      assertArrayEquals(content, bytes(body));
    } finally {
      body.release();
    }
  }

  @Test
  void mapsLargeBodiesFromTemporaryFiles() throws IOException {
    long bodyFiles = countBodyFiles();
    byte[] content = randomBytes(100);
    channel.writeInbound(request(content.length), content(content, 0, 30));
    assertEquals(bodyFiles + 1, countBodyFiles(), "A body announced to be large is written to a file.");

    channel.writeInbound(content(content, 30, 70), last(content, 70, 100));
    ByteBuf body = readBody();
    try {
      assertInstanceOf(MappedFileByteBuf.class, body);
      assertArrayEquals(content, bytes(body));
      if (!isWindows()) {
        assertEquals(bodyFiles, countBodyFiles(), "The file is deleted once mapped.");
      }
    } finally {
      body.release();
    }
    assertEquals(0, body.refCnt());
    assertEquals(bodyFiles, countBodyFiles());
  }

  @Test
  void movesChunkedBodiesToFilesOnceTheyGrowLarge() throws IOException {
    long bodyFiles = countBodyFiles();
    byte[] content = randomBytes(100);
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/bucket/key");
    HttpUtil.setTransferEncodingChunked(request, true);

    channel.writeInbound(request, content(content, 0, 10));
    assertEquals(bodyFiles, countBodyFiles());
    channel.writeInbound(content(content, 10, 60));
    assertEquals(bodyFiles + 1, countBodyFiles());
    channel.writeInbound(last(content, 60, 100));

    ByteBuf body = readBody();
    try {
      assertInstanceOf(MappedFileByteBuf.class, body);
      assertArrayEquals(content, bytes(body), "The bytes buffered before are moved to the file.");
    } finally {
      body.release();
    }
  }

  @Test
  void deletesTheFileOfAnAbortedRequest() throws IOException {
    long bodyFiles = countBodyFiles();
    byte[] content = randomBytes(100);
    channel.writeInbound(request(content.length), content(content, 0, 50));
    assertEquals(bodyFiles + 1, countBodyFiles());

    channel.close();
    assertEquals(bodyFiles, countBodyFiles());
  }

  @Test
  void neitherBuffersNorWritesTheBodyOfARejectedRequest() throws IOException {
    long bodyFiles = countBodyFiles();
    EmbeddedChannel rejecting = new EmbeddedChannel(new LocalS3HttpRequestDecoder(1024, FILE_THRESHOLD,
        new XmlMapper(), head -> new RequestHeadVerifier.Rejection(S3ErrorCode.AccessDenied, "Access Denied")));
    byte[] content = randomBytes(100);
    DefaultHttpContent firstContent = content(content, 0, 50);
    rejecting.writeInbound(request(content.length), firstContent);

    assertEquals(bodyFiles, countBodyFiles(), "The body announced to be large isn't written to a file.");
    assertEquals(0, firstContent.refCnt());
    assertNull(rejecting.readInbound());
    FullHttpResponse response = rejecting.readOutbound();
    assertEquals(HttpResponseStatus.valueOf(S3ErrorCode.AccessDenied.httpStatus()), response.status());
    response.release();
    rejecting.finishAndReleaseAll();
  }

  private ByteBuf readBody() {
    HttpRequest request = channel.readInbound();
    return request.getBody();
  }

  private static DefaultHttpRequest request(int contentLength) {
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/bucket/key");
    HttpUtil.setContentLength(request, contentLength);
    return request;
  }

  private static DefaultHttpContent content(byte[] content, int from, int to) {
    return new DefaultHttpContent(Unpooled.copiedBuffer(content, from, to - from));
  }

  private static DefaultLastHttpContent last(byte[] content, int from, int to) {
    return new DefaultLastHttpContent(Unpooled.copiedBuffer(content, from, to - from));
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

  private static long countBodyFiles() throws IOException {
    try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return files.filter(file -> file.getFileName().toString()
          .startsWith(LocalS3HttpRequestDecoder.BODY_FILE_PREFIX)).count();
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").startsWith("Windows");
  }

}
