package com.robothy.netty.initializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.router.Router;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.stream.ChunkedStream;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpServerInitializerTest {

  @Test
  void test() throws URISyntaxException, IOException, InterruptedException {

    Router router = Router.router()
        .route(HttpMethod.GET, "/", ((request, response) -> response
            .status(HttpResponseStatus.OK)
            .write("Hello World")))
        .staticResource("src");

    DefaultEventExecutorGroup executor = new DefaultEventExecutorGroup(2);
    HttpServerInitializer serverInitializer = new HttpServerInitializer(executor, router);

    EventLoopGroup parentGroup = new NioEventLoopGroup(1);
    EventLoopGroup childGroup = new NioEventLoopGroup(1);
    int port = 18080;
    Channel serverSocketChannel = new ServerBootstrap().group(parentGroup, childGroup)
        .handler(new LoggingHandler(LogLevel.DEBUG))
        .channel(NioServerSocketChannel.class)
        .childHandler(serverInitializer)
        .bind(port)
        .sync()
        .channel();


    HttpRequest.Builder requestBuilder = HttpRequest
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1);

    HttpResponse<String> response = HttpClient.newHttpClient()
        .send(requestBuilder.GET().uri(new URI("http://localhost:" + port))
            .build(), body -> {
          assertEquals(200, body.statusCode());
          return HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        });
    assertEquals("Hello World", response.body());


    Path staticResourceDirectory = Files.createTempDirectory("static-resource");
    router.staticResource(staticResourceDirectory.toString());

    // Test get small static file.
    Path smallFilePath = Paths.get(staticResourceDirectory.toString(), "/small.txt");
    Files.writeString(smallFilePath, "Hello World");
    HttpResponse<String> smallResourceResp = HttpClient.newHttpClient()
        .send(requestBuilder.GET()
            .uri(new URI("http://localhost:" + port + "/small.txt"))
            .build(), responseInfo -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, smallResourceResp.statusCode());
    HttpHeaders headers = smallResourceResp.headers();
    Optional<String> contentLenOptional = headers.firstValue("Content-Length");
    assertTrue(contentLenOptional.isPresent());
    assertEquals(String.valueOf("Hello World".length()), contentLenOptional.get());
    assertEquals("Hello World", smallResourceResp.body());

    // Test get large static file.
    int size = 20 * 1024 * 1024; // 20M
    Random random = new Random(6);

    Path largeFilePath = Paths.get(staticResourceDirectory.toString(), "/large.dat");
    try (BufferedWriter bufferedWriter = Files.newBufferedWriter(largeFilePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      for (int i = 0; i < size; i += 1024) {
        char[] chars = new char[1024];
        Arrays.fill(chars, (char) ('a' + random.nextInt(26)));
        bufferedWriter.write(chars);
      }
      bufferedWriter.flush();
    }

    HttpResponse<InputStream> largeResourceResp = HttpClient.newHttpClient()
        .send(requestBuilder
                .GET()
                .uri(new URI("http://localhost:" + port + "/large.dat"))
                .build(),
            responseInfo -> HttpResponse.BodySubscribers.ofInputStream());
    OptionalLong optionalSize = largeResourceResp.headers().firstValueAsLong("Content-Length");
    assertTrue(optionalSize.isPresent());
    assertEquals(optionalSize.getAsLong(), size);

    // Test get static resource from classpath
    router.staticResource("classpath:static");
    HttpResponse<String> classpathResourceResp = HttpClient.newHttpClient()
        .send(requestBuilder.GET()
            .uri(new URI("http://localhost:" + port + "/test.html"))
            .build(), responseInfo -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, classpathResourceResp.statusCode());
    assertEquals("Hello World", classpathResourceResp.body());
    assertEquals("text/html; charset=utf-8", classpathResourceResp.headers().firstValue("Content-Type").get());

    // Test default exception handler for a RuntimeException.
    AtomicReference<RuntimeException> exceptionHolder = new AtomicReference<>();
    router.route(HttpMethod.GET,"/test/exception", (req, resp) -> {
      exceptionHolder.set(new RuntimeException("Unhandled."));
      throw exceptionHolder.get();
    });

    HttpResponse<String> exceptionResponse = HttpClient.newHttpClient()
        .send(requestBuilder.GET().uri(new URI("http://localhost:" + port + "/test/exception")).build(),
            responseInfo -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    exceptionHolder.get().printStackTrace(new PrintStream(out, true, StandardCharsets.UTF_8));
    assertEquals(out.toString(StandardCharsets.UTF_8), exceptionResponse.body());
    assertEquals("text/plain; charset=utf-8", exceptionResponse.headers().firstValue("Content-Type").get());

    // Test user registered RuntimeException handler.
    router.exceptionHandler(RuntimeException.class, (cause, req, resp) -> {
      resp.write("Caught RuntimeException.")
          .status(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    });
    HttpResponse<String> customizedExceptionResp = HttpClient.newHttpClient()
        .send(requestBuilder.GET().uri(new URI("http://localhost:" + port + "/test/exception")).build(),
            responseInfo -> HttpResponse.BodySubscribers.ofString(Charset.defaultCharset()));
    assertEquals("Caught RuntimeException.", customizedExceptionResp.body());


    serverSocketChannel.close().sync();
    parentGroup.shutdownGracefully();
    childGroup.shutdownGracefully();
    executor.shutdownGracefully();
  }

  @Test
  void decoderConfig() {
    DefaultEventExecutorGroup executor = new DefaultEventExecutorGroup(1);
    try {
      HttpDecoderConfig config = new HttpServerInitializer(executor, Router.router(), 1024, 2048, 4096).decoderConfig();
      assertEquals(HttpServerInitializer.DEFAULT_MAX_CHUNK_SIZE, config.getMaxChunkSize());
      assertEquals(2048, config.getMaxInitialLineLength());
      assertEquals(4096, config.getMaxHeaderSize());
    } finally {
      executor.shutdownGracefully();
    }
  }

  @Test
  void uploadLargeBody() throws Exception {
    byte[] content = new byte[1024 * 1024];
    new Random(6).nextBytes(content);
    Router router = Router.router().route(HttpMethod.PUT, "/upload", (request, response) -> response
        .write(String.valueOf(Arrays.equals(content, ByteBufUtil.getBytes(request.getBody())))));
    DefaultEventExecutorGroup executor = new DefaultEventExecutorGroup(1);
    EventLoopGroup group = new NioEventLoopGroup(1);
    Channel serverChannel = new ServerBootstrap().group(group)
        .channel(NioServerSocketChannel.class)
        .childHandler(new HttpServerInitializer(executor, router))
        .bind(0)
        .sync()
        .channel();
    try {
      int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
      HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .uri(new URI("http://localhost:" + port + "/upload"))
          .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("true", response.body());
    } finally {
      serverChannel.close().sync();
      group.shutdownGracefully();
      executor.shutdownGracefully();
    }
  }

  @Test
  void streamChunkedBodies() throws Exception {
    Path directory = Files.createTempDirectory("static-resource");
    byte[] file = new byte[3 * 1024 * 1024 + 7];
    new Random(8).nextBytes(file);
    Files.write(directory.resolve("large.bin"), file);
    Router router = Router.router()
        .staticResource(directory.toString())
        .route(HttpMethod.GET, "/stream", (request, response) -> response
            .chunkedBody(new ChunkedStream(new ByteArrayInputStream("streamed".getBytes(StandardCharsets.UTF_8)))));
    DefaultEventExecutorGroup executor = new DefaultEventExecutorGroup(1);
    EventLoopGroup group = new NioEventLoopGroup(1);
    Channel serverChannel = new ServerBootstrap().group(group)
        .channel(NioServerSocketChannel.class)
        .childHandler(new HttpServerInitializer(executor, router))
        .bind(0)
        .sync()
        .channel();
    try (Socket socket = new Socket("localhost", ((InetSocketAddress) serverChannel.localAddress()).getPort())) {
      socket.setSoTimeout(10_000);
      // Pipelined on one connection, which each response must leave usable for the next one.
      socket.getOutputStream().write(("GET /large.bin HTTP/1.1\r\nHost: localhost\r\n\r\n"
          + "HEAD /large.bin HTTP/1.1\r\nHost: localhost\r\n\r\n"
          + "GET /stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      byte[] responses = socket.getInputStream().readAllBytes();

      String fileHead = "HTTP/1.1 200 OK\r\n";
      String text = new String(responses, StandardCharsets.ISO_8859_1);
      int fileBodyStart = text.indexOf("\r\n\r\n") + 4;
      assertTrue(text.startsWith(fileHead), text.substring(0, 200));
      assertTrue(text.substring(0, fileBodyStart).contains("content-length: " + file.length));
      assertTrue(Arrays.equals(file, Arrays.copyOfRange(responses, fileBodyStart, fileBodyStart + file.length)));

      String rest = text.substring(fileBodyStart + file.length);
      String[] parts = rest.split("\r\n\r\n", 3);
      // The HEAD response keeps the Content-Length of the file, without the file.
      assertTrue(parts[0].startsWith(fileHead), rest);
      assertTrue(parts[0].contains("content-length: " + file.length), rest);
      // A stream of unknown length is sent in chunks.
      assertTrue(parts[1].startsWith(fileHead), rest);
      assertTrue(parts[1].contains("transfer-encoding: chunked"), rest);
      assertEquals("8\r\nstreamed\r\n0\r\n\r\n", parts[2]);
    } finally {
      serverChannel.close().sync();
      group.shutdownGracefully();
      executor.shutdownGracefully();
    }
  }

  @Test
  void headFallsBackToGetWithoutBody() throws Exception {
    Router router = Router.router().headFallbackToGet(true)
        .route(HttpMethod.GET, "/hello", (request, response) -> response.write("Hello"));
    DefaultEventExecutorGroup executor = new DefaultEventExecutorGroup(1);
    EventLoopGroup group = new NioEventLoopGroup(1);
    Channel serverChannel = new ServerBootstrap().group(group)
        .channel(NioServerSocketChannel.class)
        .childHandler(new HttpServerInitializer(executor, router))
        .bind(0)
        .sync()
        .channel();
    try (Socket socket = new Socket("localhost", ((InetSocketAddress) serverChannel.localAddress()).getPort())) {
      socket.setSoTimeout(10_000);
      socket.getOutputStream().write(("HEAD /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
          + "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      String responses = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);

      String[] parts = responses.split("\r\n\r\n", -1);
      assertEquals(3, parts.length, responses);
      // The Content-Length of the GET response, without its body.
      assertTrue(parts[0].startsWith("HTTP/1.1 200 OK"), responses);
      assertTrue(parts[0].contains("content-length: 5"), responses);
      assertTrue(parts[1].startsWith("HTTP/1.1 200 OK"), responses);
      assertEquals("Hello", parts[2]);
    } finally {
      serverChannel.close().sync();
      group.shutdownGracefully();
      executor.shutdownGracefully();
    }
  }

  @Test
  void headResponseHasNoBody() throws Exception {
    Router router = Router.router()
        .route(HttpMethod.HEAD, "/hello", (request, response) -> response.write("Hello"))
        .route(HttpMethod.GET, "/hello", (request, response) -> response.write("Hello"))
        .staticResource("classpath:static");
    DefaultEventExecutorGroup executor = new DefaultEventExecutorGroup(1);
    EventLoopGroup group = new NioEventLoopGroup(1);
    Channel serverChannel = new ServerBootstrap().group(group)
        .channel(NioServerSocketChannel.class)
        .childHandler(new HttpServerInitializer(executor, router))
        .bind(0)
        .sync()
        .channel();
    try (Socket socket = new Socket("localhost", ((InetSocketAddress) serverChannel.localAddress()).getPort())) {
      socket.setSoTimeout(10_000);
      // Pipelined on one connection: the body of the HEAD response would be taken for the start of the next response.
      socket.getOutputStream().write(("HEAD /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
          + "HEAD /test.html HTTP/1.1\r\nHost: localhost\r\n\r\n"
          + "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      String responses = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);

      String[] parts = responses.split("\r\n\r\n", -1);
      assertEquals(4, parts.length, responses);
      // The HEAD responses keep the Content-Length of the body they would have, without the body.
      assertTrue(parts[0].startsWith("HTTP/1.1 200 OK"), responses);
      assertTrue(parts[0].contains("content-length: 5"), responses);
      assertTrue(parts[1].startsWith("HTTP/1.1 200 OK"), responses);
      assertTrue(parts[1].contains("content-length: 11"), responses);
      assertTrue(parts[2].startsWith("HTTP/1.1 200 OK"), responses);
      assertEquals("Hello", parts[3]);
    } finally {
      serverChannel.close().sync();
      group.shutdownGracefully();
      executor.shutdownGracefully();
    }
  }

}
