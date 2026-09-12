package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The threads that serve the requests. Netty binds a connection to one thread of the executor group for its
 * whole life, so the size of that group is the number of connections whose requests are handled at the same
 * time, rather than a pool that each request takes a free thread from.
 */
class LocalS3ThreadingTest {

  /**
   * The defaults follow the machine, so that a bigger machine serves more connections at once.
   */
  @Test
  void defaultThreadCountsFollowTheProcessorsOfTheMachine() {
    int processors = Runtime.getRuntime().availableProcessors();
    LocalS3 localS3 = LocalS3.builder().port(-1).build();

    assertEquals(1, localS3.getNettyParentEventGroupThreadNum());
    assertEquals(Math.max(2, processors / 2), localS3.getNettyChildEventGroupThreadNum());
    assertEquals(Math.max(4, processors), localS3.getS3ExecutorThreadNum());
    // A machine of any size serves at least as many connections at once as the old fixed defaults did.
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
   * Every request of a connection is handled on the same thread, and the connections are spread over the
   * threads of the group. This is what makes the size of the group the number of connections served at once.
   */
  @Test
  void everyRequestOfAConnectionIsHandledOnTheSameThread() throws Exception {
    Map<String, String> threadByKey = new ConcurrentHashMap<>();
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .buckets("threads")
        .s3ExecutorThreadNum(2)
        .objectEventListener(event -> threadByKey.put(event.getObjectKey(), Thread.currentThread().getName()))
        .build();
    localS3.start();

    int connections = 4;
    int requestsPerConnection = 3;
    ExecutorService clients = Executors.newFixedThreadPool(connections);
    CountDownLatch done = new CountDownLatch(connections);
    try {
      for (int connection = 0; connection < connections; connection++) {
        int id = connection;
        clients.submit(() -> {
          try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            for (int request = 0; request < requestsPerConnection; request++) {
              // The key carries the connection, so that the events tell which thread served which connection.
              String key = "c" + id + "-r" + request;
              out.write(("PUT /threads/" + key + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\nx")
                  .getBytes(StandardCharsets.ISO_8859_1));
              out.flush();
              // Read the response before sending the next request, so the connection is used sequentially.
              if (in.read(new byte[4096]) < 0) {
                break;
              }
            }
          } catch (Exception e) {
            throw new IllegalStateException(e);
          } finally {
            done.countDown();
          }
        });
      }
      assertTrue(done.await(30, TimeUnit.SECONDS), "The clients did not finish.");
    } finally {
      clients.shutdownNow();
      localS3.shutdown();
    }

    assertEquals(connections * requestsPerConnection, threadByKey.size());
    Map<String, Set<String>> threadsPerConnection = new TreeMap<>();
    threadByKey.forEach((key, thread) ->
        threadsPerConnection.computeIfAbsent(key.substring(0, key.indexOf('-')), k -> new HashSet<>()).add(thread));

    threadsPerConnection.forEach((connection, threads) -> assertEquals(1, threads.size(),
        "Connection " + connection + " was handled on " + threads + " instead of a single thread."));
    // The group has two threads, so four connections cannot be served by more than two.
    assertTrue(new HashSet<>(threadByKey.values()).size() <= 2);
    assertTrue(threadByKey.values().stream().allMatch(name -> name.startsWith("locals3-executor-group")),
        "Requests must be handled on the executor group: " + new HashMap<>(threadByKey));
  }

}
