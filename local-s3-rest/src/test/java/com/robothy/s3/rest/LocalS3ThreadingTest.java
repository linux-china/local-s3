package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The threads that serve the requests. The executor threads form a pool that all connections share, so a slow
 * request only holds up the requests of its own connection.
 */
class LocalS3ThreadingTest {

  /**
   * The defaults follow the machine, so that a bigger machine serves more requests at once.
   */
  @Test
  void defaultThreadCountsFollowTheProcessorsOfTheMachine() {
    int processors = Runtime.getRuntime().availableProcessors();
    LocalS3 localS3 = LocalS3.builder().port(-1).build();

    assertEquals(1, localS3.getNettyParentEventGroupThreadNum());
    assertEquals(Math.max(2, processors / 2), localS3.getNettyChildEventGroupThreadNum());
    assertEquals(Math.max(4, processors), localS3.getS3ExecutorThreadNum());
    assertTrue(localS3.getS3ExecutorThreadNum() >= 4);
    assertTrue(localS3.getNettyChildEventGroupThreadNum() >= 2);
  }

  @Test
  void threadCountsAreConfigurable() {
    LocalS3 localS3 = LocalS3.builder().port(-1)
        .nettyParentEventGroupThreadNum(3)
        .nettyChildEventGroupThreadNum(5)
        .s3ExecutorThreadNum(7)
        .build();

    assertEquals(3, localS3.getNettyParentEventGroupThreadNum());
    assertEquals(5, localS3.getNettyChildEventGroupThreadNum());
    assertEquals(7, localS3.getS3ExecutorThreadNum());
  }

  /**
   * One of two executor threads is blocked by a request, while three other connections send requests. Were
   * connections bound to a thread, at least one of them would share the blocked thread and wait for it.
   */
  @Test
  void blockedRequestDoesNotHoldUpOtherConnections() throws Exception {
    CountDownLatch slowStarted = new CountDownLatch(1);
    CountDownLatch releaseSlow = new CountDownLatch(1);
    Map<String, String> threadByKey = new ConcurrentHashMap<>();
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .buckets("threads")
        .s3ExecutorThreadNum(2)
        .objectEventListener(event -> {
          threadByKey.put(event.getObjectKey(), Thread.currentThread().getName());
          if ("slow".equals(event.getObjectKey())) {
            slowStarted.countDown();
            try {
              releaseSlow.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        })
        .build();
    localS3.start();

    int connections = 3;
    int requestsPerConnection = 3;
    ExecutorService clients = Executors.newFixedThreadPool(connections + 1);
    try {
      Future<?> slow = clients.submit(() -> put(localS3, List.of("slow")));
      assertTrue(slowStarted.await(30, TimeUnit.SECONDS), "The slow request did not start.");

      List<Future<?>> others = new ArrayList<>();
      for (int connection = 0; connection < connections; connection++) {
        List<String> keys = new ArrayList<>();
        for (int request = 0; request < requestsPerConnection; request++) {
          keys.add("c" + connection + "-r" + request);
        }
        others.add(clients.submit(() -> put(localS3, keys)));
      }
      for (Future<?> other : others) {
        // Fails with a timeout if a connection waits for the blocked thread.
        other.get(30, TimeUnit.SECONDS);
      }

      releaseSlow.countDown();
      slow.get(30, TimeUnit.SECONDS);
    } finally {
      releaseSlow.countDown();
      clients.shutdownNow();
      localS3.shutdown();
    }

    assertEquals(connections * requestsPerConnection + 1, threadByKey.size());
    assertTrue(threadByKey.values().stream().allMatch(name -> name.startsWith("locals3-executor-group")),
        "Requests must be handled on the executor: " + new HashMap<>(threadByKey));
  }

  /**
   * Send a PUT request for each key over one connection, reading each response before the next request.
   */
  private static Void put(LocalS3 localS3, List<String> keys) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
      socket.setSoTimeout(60_000);
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      for (String key : keys) {
        out.write(("PUT /threads/" + key + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\nx")
            .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        String response = readResponseHead(in);
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
      }
    }
    return null;
  }

  /**
   * Read the head of a response without a body.
   */
  private static String readResponseHead(InputStream in) throws Exception {
    StringBuilder head = new StringBuilder();
    while (!head.toString().endsWith("\r\n\r\n")) {
      int b = in.read();
      if (b < 0) {
        throw new IllegalStateException("The connection was closed: " + head);
      }
      head.append((char) b);
    }
    return head.toString();
  }

}
