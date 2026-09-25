package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.model.request.DecodedAmzRequestBody;
import com.robothy.s3.rest.utils.RequestUtils;
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
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.dataformat.xml.XmlMapper;

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
    assertTrue(Files.exists(leftover), "A body file may be received by another server of the JVM, so it is kept.");
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
        assertEquals(Set.of(leftover, unrelated), Set.copyOf(files.toList()),
            "The body file is deleted once the body is released.");
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
   * The event loop only queues the chunks of a body that is buffered in a file: the file is written on the body file
   * executor, and the request is handed on once the file is complete.
   */
  @Test
  void writesBodyFilesOnTheExecutor() throws IOException {
    RequestBodyFileTest.ManualExecutor executor = new RequestBodyFileTest.ManualExecutor();
    EmbeddedChannel async = asyncChannel(executor);
    try {
      long bodyFiles = countBodyFiles();
      byte[] content = randomBytes(100);
      async.writeInbound(request(content.length), content(content, 0, 30), last(content, 30, 100));

      assertEquals(bodyFiles, countBodyFiles(), "The event loop doesn't touch the disk.");
      assertNull(async.readInbound(), "The request waits for its body file.");
      assertFalse(async.config().isAutoRead(), "The connection isn't read until the body file is complete.");

      executor.runAll();
      async.runPendingTasks();
      HttpRequest decoded = async.readInbound();
      try {
        assertInstanceOf(MappedFileByteBuf.class, decoded.getBody());
        assertArrayEquals(content, bytes(decoded.getBody()));
      } finally {
        decoded.getBody().release();
      }
      assertTrue(async.config().isAutoRead());
    } finally {
      async.finishAndReleaseAll();
    }
  }

  /**
   * The messages decoded while a body file is completed, e.g. a pipelined request, are decoded after the request, so
   * that the requests of a connection keep their order.
   */
  @Test
  void keepsTheOrderOfPipelinedRequests() {
    RequestBodyFileTest.ManualExecutor executor = new RequestBodyFileTest.ManualExecutor();
    EmbeddedChannel async = asyncChannel(executor);
    try {
      byte[] large = randomBytes(100);
      byte[] small = randomBytes(8);
      DefaultHttpRequest second = request(small.length);
      second.setUri("/bucket/second");
      DefaultLastHttpContent secondBody = last(small, 0, small.length);
      async.writeInbound(request(large.length), last(large, 0, large.length), second, secondBody);
      assertNull(async.readInbound());
      assertEquals(1, secondBody.refCnt(), "The held message is kept until it is decoded.");

      executor.runAll();
      async.runPendingTasks();

      HttpRequest first = async.readInbound();
      HttpRequest next = async.readInbound();
      try {
        assertEquals("/bucket/key", first.getUri());
        assertArrayEquals(large, bytes(first.getBody()));
        assertEquals("/bucket/second", next.getUri());
        assertArrayEquals(small, bytes(next.getBody()));
      } finally {
        first.getBody().release();
        next.getBody().release();
      }
      assertEquals(0, secondBody.refCnt());
    } finally {
      async.finishAndReleaseAll();
    }
  }

  /**
   * While more bytes wait to be written than the high water mark, the connection isn't read, so that a client that
   * sends faster than the disk writes doesn't fill the memory.
   */
  @Test
  void stopsReadingWhileTheBodyFileIsBacklogged() {
    RequestBodyFileTest.ManualExecutor executor = new RequestBodyFileTest.ManualExecutor();
    int size = (int) RequestBodyFile.HIGH_WATER_MARK + 2;
    EmbeddedChannel async = new EmbeddedChannel(new LocalS3HttpRequestDecoder(size, FILE_THRESHOLD, new XmlMapper(),
        RequestHeadVerifier.ACCEPT_ALL, null, executor));
    try {
      byte[] content = new byte[size];
      async.writeInbound(request(size), content(content, 0, size - 1));
      assertFalse(async.config().isAutoRead());

      executor.runAll();
      async.runPendingTasks();
      assertTrue(async.config().isAutoRead(), "Reading resumes once the queued bytes are written.");

      async.writeInbound(last(content, size - 1, size));
      executor.runAll();
      async.runPendingTasks();
      HttpRequest decoded = async.readInbound();
      assertEquals(size, decoded.getBody().readableBytes());
      decoded.getBody().release();
    } finally {
      async.finishAndReleaseAll();
    }
  }

  /**
   * A body that fails to be written, e.g. because the disk is full, is answered with an S3 error.
   */
  @Test
  void answersABodyThatFailsToBeWrittenWithAnInternalError(@TempDir Path directory) {
    RequestBodyFileTest.ManualExecutor executor = new RequestBodyFileTest.ManualExecutor();
    EmbeddedChannel failing = new EmbeddedChannel(new LocalS3HttpRequestDecoder(1024, FILE_THRESHOLD,
        new XmlMapper(), RequestHeadVerifier.ACCEPT_ALL, directory.resolve("missing"), executor));
    byte[] content = randomBytes(100);
    failing.writeInbound(request(content.length), last(content, 0, content.length));

    executor.runAll();
    failing.runPendingTasks();

    assertRejected(failing, S3ErrorCode.InternalError);
  }

  /**
   * An aws-chunked body is decoded while it is written to its file, which then holds the decoded content, so that a
   * storage can take the file over like the one of any other upload.
   */
  @Test
  void decodesAnAwsChunkedBodyWhileItIsWrittenToAFile() throws IOException {
    byte[] content = randomBytes(100);
    byte[] encoded = awsChunked(content);
    DefaultHttpRequest head = awsChunkedRequest(encoded.length, content.length);
    channel.writeInbound(head, content(encoded, 0, 30), last(encoded, 30, encoded.length));

    HttpRequest request = channel.readInbound();
    ByteBuf body = request.getBody();
    try {
      assertArrayEquals(content, bytes(body));
      assertArrayEquals(content, Files.readAllBytes(RequestBodies.file(body).orElseThrow()));
      assertEquals(Map.of("x-amz-checksum-crc32", "AAAAAA=="), RequestBodies.awsChunkedTrailer(body).orElseThrow());

      DecodedAmzRequestBody decoded = RequestUtils.getBody(request);
      assertEquals(RequestBodies.file(body).orElseThrow(), decoded.getBodyFile(), "The storage can take it over.");
      assertEquals(content.length, decoded.getDecodedContentLength());
      assertEquals("AAAAAA==", decoded.trailingHeader("x-amz-checksum-crc32").orElseThrow(),
          "The trailer is known before the body is read.");
      assertArrayEquals(content, decoded.getDecodedBody().readAllBytes());
    } finally {
      body.release();
    }
  }

  /**
   * A body whose chunk signatures don't match is answered with {@code SignatureDoesNotMatch}, and its file deleted.
   */
  @Test
  void rejectsAnAwsChunkedBodyWhoseChunkSignaturesDontMatch(@TempDir Path directory) throws IOException {
    RequestHeadVerifier verifier = new RequestHeadVerifier() {
      @Override
      public Rejection verifyHead(HttpRequest head) {
        return null;
      }

      @Override
      public ChunkSignatures chunkSignatures(HttpRequest head) {
        return new ChunkSignatures() {
          @Override
          public boolean verifyChunk(String signature, byte[] sha256) {
            return false;
          }

          @Override
          public boolean verifyTrailer(List<String> lines) {
            return true;
          }
        };
      }
    };
    EmbeddedChannel verifying = new EmbeddedChannel(new LocalS3HttpRequestDecoder(1024, FILE_THRESHOLD,
        new XmlMapper(), verifier, directory, Runnable::run));
    byte[] content = randomBytes(100);
    byte[] encoded = awsChunked(content);
    verifying.writeInbound(awsChunkedRequest(encoded.length, content.length), last(encoded, 0, encoded.length));
    verifying.runPendingTasks();

    assertRejected(verifying, S3ErrorCode.SignatureDoesNotMatch);
    try (Stream<Path> files = Files.list(directory)) {
      assertEquals(0, files.count(), "The body file is deleted.");
    }
  }

  private static DefaultHttpRequest awsChunkedRequest(int contentLength, int decodedLength) {
    DefaultHttpRequest request = request(contentLength);
    request.headers()
        .set("content-encoding", "aws-chunked")
        .set("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
        .set("x-amz-decoded-content-length", decodedLength)
        .set("x-amz-trailer", "x-amz-checksum-crc32");
    return request;
  }

  private static byte[] awsChunked(byte[] content) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    out.writeBytes((Integer.toHexString(content.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(content);
    out.writeBytes("\r\n0\r\nx-amz-checksum-crc32:AAAAAA==\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
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

  private static EmbeddedChannel asyncChannel(RequestBodyFileTest.ManualExecutor executor) {
    return new EmbeddedChannel(new LocalS3HttpRequestDecoder(1024, FILE_THRESHOLD, new XmlMapper(),
        RequestHeadVerifier.ACCEPT_ALL, null, executor));
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
