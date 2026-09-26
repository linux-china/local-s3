package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.TotalSizeExceedException;
import com.robothy.s3.core.storage.HeapContent;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.rest.model.request.DecodedAmzRequestBody;
import com.robothy.s3.rest.utils.RequestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * A large body of a service whose storage keeps its content in the heap, i.e. an {@code IN_MEMORY} one, is received
 * into the heap, in chunks that the storage takes over, rather than into a temporary file that is copied into the heap.
 */
class HeapRequestBodyTest {

  private static final int FILE_THRESHOLD = 16;

  private static final int BUDGET = 1000;

  private final Storage storage = Storage.createInMemory(BUDGET);

  private final EmbeddedChannel channel = channel(storage);

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  @Test
  void receivesALargePutBodyIntoTheHeapThatTheStorageTakesOver() throws IOException {
    long bodyFiles = countBodyFiles();
    byte[] content = randomBytes(100);
    channel.writeInbound(request(HttpMethod.PUT, content.length), content(content, 0, 30),
        content(content, 30, 70), last(content, 70, 100));

    HttpRequest request = channel.readInbound();
    ByteBuf body = request.getBody();
    try {
      assertEquals(bodyFiles, countBodyFiles(), "No temporary file is written.");
      assertInstanceOf(HeapBodyByteBuf.class, body);
      assertArrayEquals(content, bytes(body));
      assertTrue(RequestBodies.file(body).isEmpty());
      assertThrows(TotalSizeExceedException.class, () -> storage.put(new byte[BUDGET - 99]),
          "The body takes the space of the storage while it is received.");

      DecodedAmzRequestBody decoded = RequestUtils.getBody(request);
      HeapContent heapContent = decoded.getHeapContent();
      assertSame(RequestBodies.heapContent(body).orElseThrow(), heapContent, "The storage can take it over.");
      Long id = storage.put(heapContent);
      assertArrayEquals(content, storage.getBytes(id));
      assertArrayEquals(content, decoded.getDecodedBody().readAllBytes(), "The body stays readable.");
    } finally {
      body.release();
    }
    assertDoesNotThrow(() -> storage.put(new byte[BUDGET - 100]), "The stored content takes its space once.");
    assertThrows(TotalSizeExceedException.class, () -> storage.put(new byte[1]));
  }

  @Test
  void releasingABodyThatWasntStoredGivesItsSpaceBack() {
    byte[] content = randomBytes(BUDGET);
    channel.writeInbound(request(HttpMethod.PUT, content.length), last(content, 0, content.length));

    ByteBuf body = ((HttpRequest) channel.readInbound()).getBody();
    assertThrows(TotalSizeExceedException.class, () -> storage.put(new byte[1]));
    body.release();
    assertDoesNotThrow(() -> storage.put(new byte[BUDGET]));
  }

  @Test
  void decodesAnAwsChunkedBodyIntoTheHeap() throws IOException {
    byte[] content = randomBytes(100);
    byte[] encoded = awsChunked(content);
    channel.writeInbound(awsChunkedRequest(encoded.length, content.length), content(encoded, 0, 30),
        last(encoded, 30, encoded.length));

    HttpRequest request = channel.readInbound();
    ByteBuf body = request.getBody();
    try {
      assertInstanceOf(HeapBodyByteBuf.class, body);
      assertArrayEquals(content, bytes(body));
      assertEquals(Map.of("x-amz-checksum-crc32", "AAAAAA=="), RequestBodies.awsChunkedTrailer(body).orElseThrow());
      // The decoded length is reserved, not the encoded one.
      assertDoesNotThrow(() -> storage.put(new byte[BUDGET - 100]));

      DecodedAmzRequestBody decoded = RequestUtils.getBody(request);
      assertSame(RequestBodies.heapContent(body).orElseThrow(), decoded.getHeapContent());
      assertEquals("AAAAAA==", decoded.trailingHeader("x-amz-checksum-crc32").orElseThrow());
      assertArrayEquals(content, decoded.getDecodedBody().readAllBytes());
    } finally {
      body.release();
    }
  }

  /**
   * A body that the storage can't hold is refused before {@code 100 Continue}, so the client never uploads it.
   */
  @Test
  void answersInsufficientStorageBeforeTheBodyIsUploaded() {
    DefaultHttpRequest request = request(HttpMethod.PUT, BUDGET + 1);
    request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
    channel.writeInbound(request);

    assertNull(channel.readInbound());
    FullHttpResponse response = channel.readOutbound();
    try {
      assertEquals(HttpResponseStatus.valueOf(S3ErrorCode.InsufficientStorage.httpStatus()), response.status(),
          "Answered with the error rather than 100 Continue.");
      assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("<Code>InsufficientStorage</Code>"));
    } finally {
      response.release();
    }
    assertFalse(channel.isOpen());
    assertDoesNotThrow(() -> storage.put(new byte[BUDGET]), "Nothing stays reserved.");
  }

  @Test
  void anAbortedBodyGivesItsSpaceBack() {
    byte[] content = randomBytes(BUDGET);
    channel.writeInbound(request(HttpMethod.PUT, content.length), content(content, 0, 100));
    assertThrows(TotalSizeExceedException.class, () -> storage.put(new byte[1]));

    channel.close();
    assertDoesNotThrow(() -> storage.put(new byte[BUDGET]));
  }

  /**
   * Only the body of a {@code PUT}, which is stored as it is, is received into the heap; the large body of another
   * request, e.g. a browser form upload, and one whose length isn't declared, are buffered in files as before.
   */
  @Test
  void buffersTheBodiesOfOtherRequestsInFiles() {
    byte[] content = randomBytes(100);
    channel.writeInbound(request(HttpMethod.POST, content.length), last(content, 0, content.length));
    ByteBuf post = ((HttpRequest) channel.readInbound()).getBody();
    try {
      assertInstanceOf(MappedFileByteBuf.class, post);
      assertTrue(RequestBodies.heapContent(post).isEmpty());
    } finally {
      post.release();
    }

    DefaultHttpRequest chunked = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/bucket/key");
    HttpUtil.setTransferEncodingChunked(chunked, true);
    channel.writeInbound(chunked, content(content, 0, 50), last(content, 50, 100));
    ByteBuf unknownLength = ((HttpRequest) channel.readInbound()).getBody();
    try {
      assertInstanceOf(MappedFileByteBuf.class, unknownLength);
    } finally {
      unknownLength.release();
    }
    assertDoesNotThrow(() -> storage.put(new byte[BUDGET]), "Neither took the space of the storage.");
  }

  private static EmbeddedChannel channel(Storage storage) {
    return new EmbeddedChannel(new LocalS3HttpRequestDecoder(BUDGET * 10, FILE_THRESHOLD, new XmlMapper(),
        RequestHeadVerifier.ACCEPT_ALL, null, Runnable::run, storage::newHeapContentWriter));
  }

  private static DefaultHttpRequest request(HttpMethod method, int contentLength) {
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/bucket/key");
    HttpUtil.setContentLength(request, contentLength);
    return request;
  }

  private static DefaultHttpRequest awsChunkedRequest(int contentLength, int decodedLength) {
    DefaultHttpRequest request = request(HttpMethod.PUT, contentLength);
    request.headers()
        .set("content-encoding", "aws-chunked")
        .set("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
        .set("x-amz-decoded-content-length", decodedLength)
        .set("x-amz-trailer", "x-amz-checksum-crc32");
    return request;
  }

  private static byte[] awsChunked(byte[] content) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes((Integer.toHexString(content.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(content);
    out.writeBytes("\r\n0\r\nx-amz-checksum-crc32:AAAAAA==\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
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

}
