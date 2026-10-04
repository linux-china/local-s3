package com.robothy.s3.rest.admin;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.s3.rest.netty.OperationHandler;
import com.robothy.s3.rest.netty.RequestRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Records the requests that a LocalS3 service answered: per operation, the number of requests and errors, the
 * requests per second of the last minute and the latency; the requests answered {@code 501 NotImplemented}, which tell
 * a user what their client needs that LocalS3 lacks; and the last requests themselves, for debugging what a client
 * sends.
 *
 * <p>The latency of a request is the time from when it was handed to the request executor, with its body received,
 * until its response was written. The percentiles are estimated from a histogram whose buckets grow by a factor of
 * 2<sup>1/4</sup>, about 19%, and interpolated linearly within the bucket they fall into, between the smallest and the
 * largest latency recorded; they are therefore accurate to within a bucket, e.g. 1.0 to 1.19 ms, while the histogram
 * stays a fixed array of about 120 counters per operation.
 *
 * <p>Thread-safe: requests are recorded by the event loops that write their responses.
 */
public final class RequestStatistics implements RequestRecorder {

  /**
   * The number of recent requests kept by default.
   */
  public static final int DEFAULT_RECENT_REQUESTS = 100;

  /**
   * The seconds that the requests per second are averaged over.
   */
  static final int RATE_WINDOW_SECONDS = 60;

  /**
   * The number of buckets of the latency histogram per doubling of the latency.
   */
  private static final int BUCKETS_PER_DOUBLING = 4;

  /**
   * The upper bounds, in nanoseconds, of the buckets of the latency histogram: 10 µs, then each bound 2<sup>1/4</sup>
   * times the previous one, up to about 3 hours; a last bucket takes the rest.
   */
  private static final long[] LATENCY_BOUNDS_NANOS = new long[30 * BUCKETS_PER_DOUBLING];

  static {
    for (int i = 0; i < LATENCY_BOUNDS_NANOS.length; i++) {
      LATENCY_BOUNDS_NANOS[i] = Math.round(10_000 * Math.pow(2, (double) i / BUCKETS_PER_DOUBLING));
    }
  }

  /**
   * The query parameters whose values are credentials, e.g. of a presigned URL, and are hidden in the recent
   * requests.
   */
  private static final Pattern SECRET_PARAMETER =
      Pattern.compile("(?i)([?&](?:X-Amz-Signature|X-Amz-Credential|X-Amz-Security-Token|Signature|AWSAccessKeyId)=)[^&]*");

  /**
   * The query parameters of a signature, which don't tell one operation from another.
   */
  private static final Pattern SIGNATURE_PARAMETER =
      Pattern.compile("(?i)X-Amz-(?:Algorithm|Credential|Date|Expires|SignedHeaders|Signature|Security-Token)"
          + "|Signature|AWSAccessKeyId|Expires");

  private static final int NOT_IMPLEMENTED = 501;

  private final int recentCapacity;

  private final Set<String> unrecordedOperations;

  private final Clock clock;

  private final LongSupplier nanoTime;

  private final long createdNanos;

  private final Map<String, OperationCounter> operations = new ConcurrentHashMap<>();

  private final Map<String, LongAdder> notImplemented = new ConcurrentHashMap<>();

  /**
   * Guarded by itself.
   */
  private final ArrayDeque<RecentRequest> recentRequests = new ArrayDeque<>();

  /**
   * Create statistics.
   *
   * @param recentCapacity the number of recent requests kept; {@code 0} keeps none.
   * @param unrecordedOperations the operations that aren't recorded, e.g. the health check that a probe requests
   *     every few seconds, which would crowd out the requests of the clients.
   */
  public RequestStatistics(int recentCapacity, Set<String> unrecordedOperations) {
    this(recentCapacity, unrecordedOperations, Clock.systemUTC(), System::nanoTime);
  }

  RequestStatistics(int recentCapacity, Set<String> unrecordedOperations, Clock clock, LongSupplier nanoTime) {
    if (recentCapacity < 0) {
      throw new IllegalArgumentException("recentCapacity must not be negative.");
    }
    this.recentCapacity = recentCapacity;
    this.unrecordedOperations = Set.copyOf(unrecordedOperations);
    this.clock = Objects.requireNonNull(clock);
    this.nanoTime = Objects.requireNonNull(nanoTime);
    this.createdNanos = nanoTime.getAsLong();
  }

  @Override
  public void record(RouterHttpRequest request, String operation, int status, String requestId, long durationNanos) {
    if (unrecordedOperations.contains(operation)) {
      return;
    }
    long now = nanoTime.getAsLong();
    operations.computeIfAbsent(operation, name -> new OperationCounter()).record(status, durationNanos, now);
    if (status == NOT_IMPLEMENTED) {
      notImplemented.computeIfAbsent(notImplementedName(request, operation), name -> new LongAdder()).increment();
    }
    if (recentCapacity == 0) {
      return;
    }
    RecentRequest recent = new RecentRequest(Instant.now(clock).toString(), String.valueOf(request.getMethod()),
        hideSecrets(request.getUri()), operation, status, toMillis(durationNanos), requestId);
    synchronized (recentRequests) {
      if (recentRequests.size() == recentCapacity) {
        recentRequests.removeFirst();
      }
      recentRequests.addLast(recent);
    }
  }

  /**
   * The statistics of the operations that were requested, by operation name.
   *
   * @return the statistics, ordered by operation name.
   */
  public Map<String, OperationStatistics> operations() {
    long now = nanoTime.getAsLong();
    // The requests per second are averaged over the time recorded so far, until a whole window has passed.
    double windowSeconds = Math.max(1, Math.min(RATE_WINDOW_SECONDS,
        TimeUnit.NANOSECONDS.toSeconds(now - createdNanos) + 1));
    Map<String, OperationStatistics> result = new TreeMap<>();
    operations.forEach((name, counter) -> result.put(name, counter.snapshot(now, windowSeconds)));
    return result;
  }

  /**
   * The requests answered {@code 501 NotImplemented}, by the operation that LocalS3 routes but doesn't implement, e.g.
   * {@code SelectObjectContent}, or, for a request that no route matches, by its method, the shape of its path and the
   * names of its query parameters, e.g. {@code GET /{bucket}?analytics}.
   *
   * @return the number of requests, ordered by name.
   */
  public Map<String, Long> notImplemented() {
    Map<String, Long> result = new TreeMap<>();
    notImplemented.forEach((name, count) -> result.put(name, count.sum()));
    return result;
  }

  /**
   * The number of requests recorded.
   *
   * @return the number of requests.
   */
  public long totalRequests() {
    return operations.values().stream().mapToLong(OperationCounter::count).sum();
  }

  /**
   * The last requests, the most recent first.
   *
   * @param limit the max number of requests.
   * @return the requests.
   */
  public List<RecentRequest> recentRequests(int limit) {
    List<RecentRequest> result = new ArrayList<>(Math.min(Math.max(limit, 0), recentCapacity));
    synchronized (recentRequests) {
      Iterator<RecentRequest> newestFirst = recentRequests.descendingIterator();
      while (newestFirst.hasNext() && result.size() < limit) {
        result.add(newestFirst.next());
      }
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * Forget the recorded requests.
   */
  public void clear() {
    operations.clear();
    notImplemented.clear();
    synchronized (recentRequests) {
      recentRequests.clear();
    }
  }

  /**
   * The name that a request answered {@code 501} is counted by: the operation, if the router named it; otherwise the
   * method and the shape of the path, without the names of buckets and keys, which would count every bucket apart, and
   * the names of the query parameters, which name the operation of Amazon S3, without the ones of a signature.
   */
  static String notImplementedName(RouterHttpRequest request, String operation) {
    if (!OperationHandler.UNKNOWN_OPERATION.equals(operation) && !OperationHandler.NOT_FOUND_OPERATION.equals(operation)) {
      return operation;
    }
    // The router puts the bucket and the key into the parameters when it matches the path, which also covers a
    // virtual-hosted request, whose path lacks the bucket; otherwise the path tells them.
    Map<String, List<String>> params = request.getParams() == null ? Map.of() : request.getParams();
    String shape;
    if (params.containsKey("key")) {
      shape = "/{bucket}/{key}";
    } else if (params.containsKey("bucket")) {
      shape = "/{bucket}";
    } else {
      String path = request.getPath() == null ? "/" : request.getPath();
      long segments = Arrays.stream(path.split("/")).filter(segment -> !segment.isEmpty()).count();
      shape = segments == 0 ? "/" : segments == 1 ? "/{bucket}" : "/{bucket}/{key}";
    }
    String parameters = params.keySet().stream()
        .map(CharSequence::toString)
        .filter(name -> !"bucket".equals(name) && !"key".equals(name))
        .filter(name -> !SIGNATURE_PARAMETER.matcher(name).matches())
        .sorted()
        .distinct()
        .collect(Collectors.joining("&"));
    return request.getMethod() + " " + shape + (parameters.isEmpty() ? "" : "?" + parameters);
  }

  static String hideSecrets(String uri) {
    if (uri == null) {
      return null;
    }
    Matcher matcher = SECRET_PARAMETER.matcher(uri);
    return matcher.find() ? matcher.replaceAll("$1***") : uri;
  }

  private static double toMillis(long nanos) {
    return Math.round(nanos / 1_000.0) / 1_000.0;
  }

  /**
   * The statistics of an operation.
   *
   * @param count the number of requests.
   * @param clientErrors the number of requests answered with a {@code 4xx} status.
   * @param serverErrors the number of requests answered with a {@code 5xx} status.
   * @param requestsPerSecond the requests per second of the last minute.
   * @param averageMillis the average latency.
   * @param p50Millis the estimated median latency.
   * @param p90Millis the estimated 90th percentile of the latency.
   * @param p99Millis the estimated 99th percentile of the latency.
   * @param maxMillis the max latency.
   */
  public record OperationStatistics(long count, long clientErrors, long serverErrors, double requestsPerSecond,
                                    double averageMillis, double p50Millis, double p90Millis, double p99Millis,
                                    double maxMillis) {
  }

  /**
   * A request that was answered.
   *
   * @param time when it was answered, in ISO-8601, e.g. {@code 2026-09-14T08:15:30.123Z}.
   * @param method its method.
   * @param uri its path and query, with the values of credentials hidden.
   * @param operation the operation that answered it.
   * @param status the status of its response.
   * @param durationMillis its latency.
   * @param requestId the {@code x-amz-request-id} of its response; {@code null} if it has none.
   */
  public record RecentRequest(String time, String method, String uri, String operation, int status,
                              double durationMillis, String requestId) {
  }

  private static final class OperationCounter {

    private long count;

    private long clientErrors;

    private long serverErrors;

    private long totalNanos;

    private long minNanos = Long.MAX_VALUE;

    private long maxNanos;

    private final long[] latencyBuckets = new long[LATENCY_BOUNDS_NANOS.length + 1];

    /**
     * The requests of each of the last seconds, by the second modulo the window, and the second they belong to.
     */
    private final long[] requestsBySecond = new long[RATE_WINDOW_SECONDS];

    private final long[] secondOfSlot = new long[RATE_WINDOW_SECONDS];

    synchronized void record(int status, long durationNanos, long nowNanos) {
      count++;
      if (status >= 500) {
        serverErrors++;
      } else if (status >= 400) {
        clientErrors++;
      }
      long nanos = Math.max(0, durationNanos);
      totalNanos += nanos;
      minNanos = Math.min(minNanos, nanos);
      maxNanos = Math.max(maxNanos, nanos);
      latencyBuckets[bucketOf(nanos)]++;

      long second = TimeUnit.NANOSECONDS.toSeconds(nowNanos);
      int slot = (int) Math.floorMod(second, (long) RATE_WINDOW_SECONDS);
      if (secondOfSlot[slot] != second) {
        secondOfSlot[slot] = second;
        requestsBySecond[slot] = 0;
      }
      requestsBySecond[slot]++;
    }

    synchronized long count() {
      return count;
    }

    synchronized OperationStatistics snapshot(long nowNanos, double windowSeconds) {
      long second = TimeUnit.NANOSECONDS.toSeconds(nowNanos);
      long recent = 0;
      for (int slot = 0; slot < RATE_WINDOW_SECONDS; slot++) {
        if (requestsBySecond[slot] > 0 && second - secondOfSlot[slot] < RATE_WINDOW_SECONDS) {
          recent += requestsBySecond[slot];
        }
      }
      return new OperationStatistics(count, clientErrors, serverErrors,
          Math.round(recent / windowSeconds * 1_000) / 1_000.0,
          count == 0 ? 0 : toMillis(totalNanos / count),
          percentile(0.50), percentile(0.90), percentile(0.99), toMillis(maxNanos));
    }

    /**
     * The latency below which the given fraction of the requests fall: found in the bucket that holds the request of
     * that rank, and interpolated linearly between the bounds of the bucket by the position of the rank among the
     * requests of the bucket, which are assumed to be spread evenly over it. The bounds are narrowed to the smallest and the largest latency recorded, so that a
     * percentile is never outside them, and the requests of a single latency answer that latency.
     */
    private double percentile(double fraction) {
      if (count == 0) {
        return 0;
      }
      long rank = Math.max(1, (long) Math.ceil(count * fraction));
      long seen = 0;
      for (int i = 0; i < latencyBuckets.length; i++) {
        long inBucket = latencyBuckets[i];
        if (inBucket > 0 && seen + inBucket >= rank) {
          long lower = Math.max(i == 0 ? 0 : LATENCY_BOUNDS_NANOS[i - 1], minNanos);
          long upper = Math.min(i < LATENCY_BOUNDS_NANOS.length ? LATENCY_BOUNDS_NANOS[i] : maxNanos, maxNanos);
          // The middle of the share of the bucket that the request of the rank takes, so that the single request of
          // a bucket is placed at its middle rather than at its upper bound.
          double position = (rank - seen - 0.5) / inBucket;
          return toMillis(Math.round(lower + (upper - lower) * position));
        }
        seen += inBucket;
      }
      return toMillis(maxNanos);
    }

    /**
     * The bucket of a latency: the first whose upper bound isn't below it, or the last one.
     */
    private static int bucketOf(long nanos) {
      int index = Arrays.binarySearch(LATENCY_BOUNDS_NANOS, nanos);
      return index >= 0 ? index : -index - 1;
    }
  }

}
