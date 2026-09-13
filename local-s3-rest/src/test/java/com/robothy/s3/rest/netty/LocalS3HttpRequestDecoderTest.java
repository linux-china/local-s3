package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
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
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
      // The file is kept while the body is alive, so that a handler can hand it over to a storage.
      assertEquals(bodyFiles + 1, countBodyFiles());
      Path file = RequestBodies.file(body).orElseThrow();
      assertArrayEquals(content, Files.readAllBytes(file));
    } finally {
      body.release();
    }
    assertEquals(0, body.refCnt());
    assertEquals(bodyFiles, countBodyFiles());
  }

  @Test
  void exposesNoFileOfABodyOnTheHeap() {
    byte[] content = randomBytes(FILE_THRESHOLD);
    channel.writeInbound(request(content.length), last(content, 0, content.length));

    ByteBuf body = readBody();
    try {
      assertTrue(RequestBodies.file(body).isEmpty());
    } finally {
      body.release();
    }
  }

  /**
   * A body file that a storage took over, e.g. renamed, is gone when the body is released, which must not fail.
   */
  @Test
  void releasesABodyWhoseFileWasTakenOver(@TempDir Path directory) throws IOException {
    // Windows refuses to rename a file that is memory-mapped; a storage copies such a file instead.
    assumeFalse(isWindows());
    byte[] content = randomBytes(100);
    channel.writeInbound(request(content.length), last(content, 0, content.length));

    ByteBuf body = readBody();
    Path taken = directory.resolve("taken");
    Files.move(RequestBodies.file(body).orElseThrow(), taken);
    assertArrayEquals(content, bytes(body), "The body stays readable.");
    body.release();

    assertEquals(0, body.refCnt());
    assertArrayEquals(content, Files.readAllBytes(taken));
  }

  @Test
  void createsBodyFilesInTheConfiguredDirectory(@TempDir Path directory) throws IOException {
    Path leftover = Files.createFile(directory.resolve(LocalS3HttpRequestDecoder.BODY_FILE_PREFIX + "1.tmp"));
    Path unrelated = Files.createFile(directory.resolve("123"));
    LocalS3HttpRequestDecoder.prepareBodyFileDirectory(directory);
    assertFalse(Files.exists(leftover), "A body file left behind by a process that died is deleted.");
    assertTrue(Files.exists(unrelated));

    EmbeddedChannel configured = new EmbeddedChannel(new LocalS3HttpRequestDecoder(1024, FILE_THRESHOLD,
        new XmlMapper(), RequestHeadVerifier.ACCEPT_ALL, directory));
    try {
      byte[] content = randomBytes(100);
      configured.writeInbound(request(content.length), last(content, 0, content.length));
      HttpRequest request = configured.readInbound();
      ByteBuf body = request.getBody();
      try {
        Path file = RequestBodies.file(body).orElseThrow();
        assertEquals(directory, file.getParent());
        assertArrayEquals(content, Files.readAllBytes(file));
      } finally {
        body.release();
      }
      try (Stream<Path> files = Files.list(directory)) {
        assertEquals(List.of(unrelated), files.toList(), "The body file is deleted once the body is released.");
      }
    } finally {
      configured.finishAndReleaseAll();
    }
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

  /**
   * AWS Signature Version 4 signs a repeated header with its values joined by commas, in the order they were
   * received, so keeping only the last value makes the signature of such a request mismatch.
   */
  @Test
  void joinsTheValuesOfARepeatedHeader() {
    DefaultHttpRequest request = request(0);
    // Netty rejects a header value with leading or trailing whitespace, so the values are given as they arrive.
    request.headers().add("X-Amz-Meta-Tag", "first");
    request.headers().add("x-amz-meta-tag", "second");
    request.headers().add("content-type", "text/plain");
    channel.writeInbound(request, last(new byte[0], 0, 0));

    HttpRequest decoded = channel.readInbound();
    try {
      assertEquals("first,second", decoded.getHeaders().get("x-amz-meta-tag"),
          "The values of a repeated header are joined, whatever case its name is written in.");
      assertEquals("text/plain", decoded.getHeaders().get("content-type"));
    } finally {
      decoded.getBody().release();
    }
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


  /**
   * A channel that decodes raw bytes with the HTTP codec in front of the decoder, like the server does.
   */
  private static EmbeddedChannel codecChannel(int maxHeaderSize) {
    return new EmbeddedChannel(new HttpRequestDecoder(new HttpDecoderConfig().setMaxHeaderSize(maxHeaderSize)),
        new LocalS3HttpRequestDecoder(1024, FILE_THRESHOLD, new XmlMapper()));
  }

  private static void assertRejected(EmbeddedChannel channel, S3ErrorCode errorCode) {
    assertNull(channel.readInbound(), "The malformed request isn't handed to the router.");
    FullHttpResponse response = channel.readOutbound();
    try {
      assertEquals(HttpResponseStatus.valueOf(errorCode.httpStatus()), response.status());
      assertEquals(HttpHeaderValues.CLOSE.toString(), response.headers().get(HttpHeaderNames.CONNECTION));
      String body = response.content().toString(StandardCharsets.UTF_8);
      assertTrue(body.contains("<Code>" + errorCode.code() + "</Code>"), body);
    } finally {
      response.release();
    }
    assertFalse(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  private static ByteBuf ascii(String text) {
    return Unpooled.copiedBuffer(text, StandardCharsets.ISO_8859_1);
  }

  @Test
  void rejectsRequestWhoseHeaderSectionIsTooLarge() {
    EmbeddedChannel codec = codecChannel(256);

    codec.writeInbound(ascii("GET /bucket/key HTTP/1.1\r\nHost: localhost\r\nx-amz-meta-large: "
        + "a".repeat(300) + "\r\n\r\n"));

    assertRejected(codec, S3ErrorCode.RequestHeaderSectionTooLarge);
  }

  @Test
  void acceptsRequestWhoseHeaderSectionFits() {
    EmbeddedChannel codec = codecChannel(512);

    codec.writeInbound(ascii("GET /bucket/key HTTP/1.1\r\nHost: localhost\r\nx-amz-meta-large: "
        + "a".repeat(300) + "\r\n\r\n"));

    HttpRequest request = codec.readInbound();
    assertEquals("a".repeat(300), request.header("x-amz-meta-large").orElse(null));
    request.getBody().release();
    assertFalse(codec.finishAndReleaseAll());
  }

  @Test
  void rejectsRequestThatCannotBeParsed() {
    EmbeddedChannel codec = codecChannel(256);

    codec.writeInbound(ascii("GET /bucket/key HTTP/9.x\r\nHost: localhost\r\n\r\n"));

    assertRejected(codec, S3ErrorCode.BadRequest);
  }

  @Test
  void rejectsRequestWithMalformedChunkAndReleasesItsBody() {
    EmbeddedChannel codec = codecChannel(256);

    codec.writeInbound(ascii("PUT /bucket/key HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
        + "5\r\nHello\r\nzz\r\n"));

    assertRejected(codec, S3ErrorCode.BadRequest);
  }

}
