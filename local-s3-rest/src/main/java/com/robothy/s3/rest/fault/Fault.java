package com.robothy.s3.rest.fault;

import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.netty.FaultInjector;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * A failure that a running LocalS3 injects into the requests that a {@linkplain FaultMatcher} matches, e.g. to test
 * the retries of a client; added with {@code localS3.faults().add(...)}. See {@code docs/embedding.md#inject-faults}.
 *
 * <pre>{@code
 * localS3.faults().add(Fault.when(op("UploadPart").bucket("lake").times(2))
 *     .respond(S3ErrorCode.SlowDown));
 * localS3.faults().add(Fault.when(op("GetObject").keyPrefix("data/"))
 *     .delay(Duration.ofMillis(800)));
 * localS3.faults().add(Fault.when(op("GetObject")).probability(0.05)
 *     .resetConnectionAfterBytes(1024));
 * }</pre>
 *
 * <p>A fault is configured before it is added; {@linkplain #matched()} and {@linkplain #injected()} count the
 * requests from then on.
 */
public final class Fault {

  private final FaultMatcher matcher;

  private double probability = 1;

  private Duration delay = Duration.ZERO;

  private FaultInjector.@Nullable Error error;

  private boolean closeConnection;

  private long resetAfterBytes = -1;

  private long bytesPerSecond;

  private final AtomicLong matched = new AtomicLong();

  private final AtomicLong injected = new AtomicLong();

  private Fault(FaultMatcher matcher) {
    this.matcher = Objects.requireNonNull(matcher);
  }

  /**
   * Start a fault of the requests that {@code matcher} matches.
   *
   * @param matcher the requests, e.g. {@code FaultMatcher.op("GetObject")}.
   * @return the fault, to which at least one action is to be added.
   */
  public static Fault when(FaultMatcher matcher) {
    return new Fault(matcher);
  }

  /**
   * Apply the fault to this share of the requests it matches, at random.
   *
   * @param probability from 0 to 1; 1 by default.
   * @return this fault.
   */
  public Fault probability(double probability) {
    if (!(probability >= 0 && probability <= 1)) {
      throw new IllegalArgumentException("probability must be between 0 and 1.");
    }
    this.probability = probability;
    return this;
  }

  /**
   * Wait before the request is handled.
   *
   * @param delay the time to wait.
   * @return this fault.
   */
  public Fault delay(Duration delay) {
    if (delay.isNegative()) {
      throw new IllegalArgumentException("delay must not be negative.");
    }
    this.delay = delay;
    return this;
  }

  /**
   * Answer with an error instead of handling the request.
   *
   * @param errorCode the error, e.g. {@linkplain S3ErrorCode#SlowDown}.
   * @return this fault.
   */
  public Fault respond(S3ErrorCode errorCode) {
    return respond(errorCode.httpStatus(), errorCode.code(), errorCode.description());
  }

  /**
   * Answer with an error instead of handling the request.
   *
   * @param status the HTTP status, e.g. {@code 503}.
   * @param code the error code, e.g. {@code SlowDown}.
   * @return this fault.
   */
  public Fault respond(int status, String code) {
    return respond(status, code, "Injected by a LocalS3 fault.");
  }

  /**
   * Answer with an error instead of handling the request.
   *
   * @param status the HTTP status, e.g. {@code 503}.
   * @param code the error code, e.g. {@code SlowDown}.
   * @param message the message of the error.
   * @return this fault.
   */
  public Fault respond(int status, String code, String message) {
    if (status < 400 || status > 599) {
      throw new IllegalArgumentException("An error status is between 400 and 599.");
    }
    this.error = new FaultInjector.Error(status, code, message);
    return this;
  }

  /**
   * Close the connection without handling the request or answering it.
   *
   * @return this fault.
   */
  public Fault closeConnection() {
    this.closeConnection = true;
    return this;
  }

  /**
   * Handle the request, send the headers and the first bytes of the response body, then close the connection.
   *
   * @param bytes the bytes of the body to send.
   * @return this fault.
   */
  public Fault resetConnectionAfterBytes(long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must not be negative.");
    }
    this.resetAfterBytes = bytes;
    return this;
  }

  /**
   * Send the response at most this fast.
   *
   * @param bytesPerSecond the rate.
   * @return this fault.
   */
  public Fault throttle(long bytesPerSecond) {
    if (bytesPerSecond < 1) {
      throw new IllegalArgumentException("bytesPerSecond must be at least 1.");
    }
    this.bytesPerSecond = bytesPerSecond;
    return this;
  }

  /**
   * The requests that the fault matched since it was added, whether it applied to them or not.
   *
   * @return the number of requests.
   */
  public long matched() {
    return matched.get();
  }

  /**
   * The requests that the fault applied to since it was added.
   *
   * @return the number of requests.
   */
  public long injected() {
    return injected.get();
  }

  FaultMatcher matcher() {
    return matcher;
  }

  void validate() {
    if (error == null && !closeConnection && delay.isZero() && resetAfterBytes < 0 && bytesPerSecond == 0) {
      throw new IllegalArgumentException("The fault " + this + " does nothing; add an action, e.g. respond(...).");
    }
    if (closeConnection && (error != null || resetAfterBytes >= 0 || bytesPerSecond > 0)) {
      throw new IllegalArgumentException("closeConnection() answers nothing, so it can't be combined with respond(), "
          + "resetConnectionAfterBytes() or throttle().");
    }
  }

  /**
   * Count a request that the matcher matched.
   *
   * @return its number, from 1.
   */
  long count() {
    return matched.incrementAndGet();
  }

  /**
   * Decide whether the fault applies to the {@code n}th matched request.
   */
  FaultInjector.@Nullable Injection inject(long n) {
    if (!matcher.selects(n) || (probability < 1 && ThreadLocalRandom.current().nextDouble() >= probability)) {
      return null;
    }
    injected.incrementAndGet();
    return new FaultInjector.Injection(delay, error, closeConnection, resetAfterBytes, bytesPerSecond);
  }

  @Override
  public String toString() {
    StringBuilder text = new StringBuilder("Fault.when(").append(matcher).append(')');
    if (probability < 1) {
      text.append(".probability(").append(probability).append(')');
    }
    if (!delay.isZero()) {
      text.append(".delay(").append(delay).append(')');
    }
    if (error != null) {
      text.append(".respond(").append(error.status()).append(", ").append(error.code()).append(')');
    }
    if (closeConnection) {
      text.append(".closeConnection()");
    }
    if (resetAfterBytes >= 0) {
      text.append(".resetConnectionAfterBytes(").append(resetAfterBytes).append(')');
    }
    if (bytesPerSecond > 0) {
      text.append(".throttle(").append(bytesPerSecond).append(')');
    }
    return text.toString();
  }

}
