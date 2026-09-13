package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Shutting down waits for the requests in flight: the requests being handled finish, and their responses are written,
 * before the connections are closed.
 */
class GracefulShutdownTest {

  @Test
  void aRequestBeingHandledIsAnsweredBeforeTheServiceStops() throws Exception {
    CountDownLatch handling = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .buckets("bucket")
        .objectEventListener(event -> {
          handling.countDown();
          await(release);
        })
        .build();
    localS3.start();

    try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
      socket.setSoTimeout(30_000);
      socket.getOutputStream().write(("PUT /bucket/key HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\nx")
          .getBytes(StandardCharsets.ISO_8859_1));
      assertTrue(handling.await(10, TimeUnit.SECONDS));

      CompletableFuture<Void> stopping = CompletableFuture.runAsync(localS3::shutdown);
      Thread.sleep(300);
      assertFalse(stopping.isDone(), "Shutting down waits for the request being handled.");

      release.countDown();
      String response = readHead(socket.getInputStream());
      assertTrue(response.startsWith("HTTP/1.1 200 "), response);
      stopping.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      localS3.shutdown();
    }
  }

  @Test
  void aResponseBeingWrittenIsWrittenBeforeTheServiceStops() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).buckets("bucket").build();
    localS3.start();
    byte[] content = new byte[16 * 1024 * 1024];
    new Random(16).nextBytes(content);
    try {
      try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
        socket.setSoTimeout(30_000);
        OutputStream out = socket.getOutputStream();
        out.write(("PUT /bucket/large HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + content.length + "\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1));
        out.write(content);
        String head = readHead(socket.getInputStream());
        assertTrue(head.startsWith("HTTP/1.1 200 "), head);
      }

      AtomicReference<byte[]> body = new AtomicReference<>();
      try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
        // A small receive buffer, so that most of the response is still to be written when the service stops.
        socket.setReceiveBufferSize(4096);
        socket.setSoTimeout(30_000);
        socket.getOutputStream().write("GET /bucket/large HTTP/1.1\r\nHost: localhost\r\n\r\n"
            .getBytes(StandardCharsets.ISO_8859_1));
        InputStream in = socket.getInputStream();
        String head = readHead(in);
        assertTrue(head.startsWith("HTTP/1.1 200 "), head);

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(localS3::shutdown);
        Thread.sleep(300);
        assertFalse(stopping.isDone(), "Shutting down waits for the response being written.");

        body.set(in.readNBytes(content.length));
        stopping.get(10, TimeUnit.SECONDS);
      }
      assertArrayEquals(content, body.get());
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * A request that shuts the service down, e.g. from a listener, doesn't wait for itself.
   */
  @Test
  void aRequestCanShutTheServiceDown() throws Exception {
    AtomicReference<LocalS3> service = new AtomicReference<>();
    CountDownLatch stopped = new CountDownLatch(1);
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .buckets("bucket")
        .objectEventListener(event -> {
          service.get().shutdown();
          stopped.countDown();
        })
        .build();
    service.set(localS3);
    localS3.start();
    try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
      socket.getOutputStream().write(("PUT /bucket/key HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\nx")
          .getBytes(StandardCharsets.ISO_8859_1));
      assertTrue(stopped.await(5, TimeUnit.SECONDS), "The shutdown from the request waited for the request.");
    } finally {
      localS3.shutdown();
    }
  }

  private static String readHead(InputStream in) throws Exception {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int matched = 0;
    byte[] end = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    while (matched < end.length) {
      int read = in.read();
      if (read < 0) {
        break;
      }
      head.write(read);
      matched = read == end[matched] ? matched + 1 : (read == end[0] ? 1 : 0);
    }
    return head.toString(StandardCharsets.ISO_8859_1);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

}
