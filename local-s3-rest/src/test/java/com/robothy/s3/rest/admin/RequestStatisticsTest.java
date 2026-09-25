package com.robothy.s3.rest.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.netty.http.HttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RequestStatisticsTest {

  private long nanos = TimeUnit.SECONDS.toNanos(1_000);

  private final RequestStatistics statistics = new RequestStatistics(3, Set.of("HealthCheck"),
      Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC), () -> nanos);

  @Test
  void countsTheRequestsAndErrorsOfEachOperation() {
    record("PutObject", 200, 2);
    record("PutObject", 403, 4);
    record("PutObject", 500, 8);
    record("GetObject", 404, 1);
    record("HealthCheck", 200, 1);

    Map<String, RequestStatistics.OperationStatistics> operations = statistics.operations();
    assertEquals(List.of("GetObject", "PutObject"), List.copyOf(operations.keySet()),
        "The health check isn't recorded.");
    RequestStatistics.OperationStatistics putObject = operations.get("PutObject");
    assertEquals(3, putObject.count());
    assertEquals(1, putObject.clientErrors());
    assertEquals(1, putObject.serverErrors());
    assertEquals(4.667, putObject.averageMillis());
    assertEquals(8.0, putObject.maxMillis());
    assertEquals(4, statistics.totalRequests());
  }

  /**
   * The percentiles are the upper bounds of the histogram buckets, which double, but never more than the max.
   */
  @Test
  void estimatesThePercentilesOfTheLatency() {
    for (int i = 0; i < 98; i++) {
      record("GetObject", 200, 1);
    }
    record("GetObject", 200, 50);
    record("GetObject", 200, 300);

    RequestStatistics.OperationStatistics getObject = statistics.operations().get("GetObject");
    assertEquals(1.6, getObject.p50Millis(), "1 ms falls into the bucket up to 1.6 ms.");
    assertEquals(1.6, getObject.p90Millis());
    assertEquals(51.2, getObject.p99Millis());
    assertEquals(300.0, getObject.maxMillis());
  }

  /**
   * The requests per second are averaged over the last minute, or over the time recorded so far.
   */
  @Test
  void averagesTheRequestsPerSecondOverTheLastMinute() {
    for (int i = 0; i < 10; i++) {
      record("ListObjects", 200, 1);
    }
    assertEquals(10.0, statistics.operations().get("ListObjects").requestsPerSecond(), "Within the first second.");

    nanos += TimeUnit.SECONDS.toNanos(9);
    assertEquals(1.0, statistics.operations().get("ListObjects").requestsPerSecond(), "10 requests in 10 seconds.");

    nanos += TimeUnit.SECONDS.toNanos(30);
    record("ListObjects", 200, 1);
    nanos += TimeUnit.SECONDS.toNanos(40);
    assertEquals(1 / 60.0, statistics.operations().get("ListObjects").requestsPerSecond(), 0.001,
        "The requests older than a minute are no longer counted.");
    assertEquals(11, statistics.operations().get("ListObjects").count());
  }

  @Test
  void keepsTheLastRequestsWithTheirCredentialsHidden() {
    statistics.record(request(HttpMethod.GET, "/bucket/a?X-Amz-Credential=AKIA%2F20260914&X-Amz-Signature=abc&x=1"),
        "GetObject", 200, "request-1", TimeUnit.MICROSECONDS.toNanos(1_500));
    record("PutObject", 200, 1);
    record("PutObject", 200, 1);
    record("DeleteObject", 204, 1);

    List<RequestStatistics.RecentRequest> recent = statistics.recentRequests(10);
    assertEquals(List.of("DeleteObject", "PutObject", "PutObject"),
        recent.stream().map(RequestStatistics.RecentRequest::operation).toList(), "The most recent first, at most 3.");
    assertEquals(1, statistics.recentRequests(1).size());

    RequestStatistics hiding = new RequestStatistics(1, Set.of());
    hiding.record(request(HttpMethod.GET, "/bucket/a?X-Amz-Credential=AKIA%2F20260914&X-Amz-Signature=abc&x=1"),
        "GetObject", 200, "request-1", TimeUnit.MICROSECONDS.toNanos(1_500));
    RequestStatistics.RecentRequest request = hiding.recentRequests(1).get(0);
    assertEquals("/bucket/a?X-Amz-Credential=***&X-Amz-Signature=***&x=1", request.uri());
    assertEquals("GET", request.method());
    assertEquals(1.5, request.durationMillis());
    assertEquals("request-1", request.requestId());
  }

  /**
   * A routed but unimplemented operation is counted by its name, an unrouted request by its method, the shape of its
   * path and its query parameters, without the names of buckets, keys and signatures; the router names it
   * {@code NotFound}, and a request answered without a router {@code Unknown}.
   */
  @Test
  void countsTheRequestsAnsweredNotImplemented() {
    record("SelectObjectContent", 501, 1);
    record("SelectObjectContent", 501, 1);
    record("PutObject", 500, 1);
    statistics.record(request(HttpMethod.GET, "/my-bucket", "analytics", "X-Amz-Signature"), "NotFound", 501, null, 1);
    statistics.record(request(HttpMethod.GET, "/other-bucket", "analytics"), "NotFound", 501, null, 1);
    statistics.record(request(HttpMethod.PATCH, "/bucket/a/b.txt"), "Unknown", 501, null, 1);
    // A virtual-hosted request, whose bucket the router put into the parameters.
    statistics.record(request(HttpMethod.GET, "/", "analytics", "bucket"), "NotFound", 501, null, 1);

    assertEquals(Map.of("SelectObjectContent", 2L, "GET /{bucket}?analytics", 3L, "PATCH /{bucket}/{key}", 1L),
        statistics.notImplemented());
    assertEquals(3, statistics.operations().get("NotFound").serverErrors());
  }

  @Test
  void clearForgetsTheRequests() {
    record("PutObject", 200, 1);
    record("SelectObjectContent", 501, 1);
    statistics.clear();

    assertTrue(statistics.notImplemented().isEmpty());

    assertTrue(statistics.operations().isEmpty());
    assertTrue(statistics.recentRequests(10).isEmpty());
    assertEquals(0, statistics.totalRequests());
    assertNull(RequestStatistics.hideSecrets(null));
  }

  private void record(String operation, int status, long millis) {
    statistics.record(request(HttpMethod.PUT, "/bucket/key"), operation, status, null,
        TimeUnit.MILLISECONDS.toNanos(millis));
  }

  private static HttpRequest request(HttpMethod method, String uri) {
    return HttpRequest.builder().method(method).uri(uri).path(uri).build();
  }

  private static HttpRequest request(HttpMethod method, String path, String... parameters) {
    Map<CharSequence, List<String>> params = new HashMap<>();
    for (String parameter : parameters) {
      params.put(parameter, List.of(""));
    }
    return HttpRequest.builder().method(method).uri(path).path(path).params(params).build();
  }

}
