package com.robothy.s3.rest.fault;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The requests that a {@linkplain Fault} applies to, by their S3 semantics; see
 * {@code docs/embedding.md#inject-faults}.
 *
 * <pre>{@code
 * import static com.robothy.s3.rest.fault.FaultMatcher.op;
 *
 * op("UploadPart").bucket("lake").times(2)    // the first 2 UploadPart requests of bucket "lake"
 * op("GetObject").keyPrefix("data/").nth(3)   // only the 3rd GetObject of a key under data/
 * anyOp().method("PUT").skip(10)              // every PUT after the first 10
 * }</pre>
 *
 * <p>Every condition narrows the requests; a matcher is immutable, each method returns a new one.
 */
public final class FaultMatcher {

  private final @Nullable Set<String> operations;

  private final @Nullable String bucket;

  private final @Nullable String key;

  private final @Nullable String keyPrefix;

  private final @Nullable String method;

  private final long skip;

  private final long times;

  private FaultMatcher(@Nullable Set<String> operations, @Nullable String bucket, @Nullable String key,
                       @Nullable String keyPrefix, @Nullable String method, long skip, long times) {
    this.operations = operations;
    this.bucket = bucket;
    this.key = key;
    this.keyPrefix = keyPrefix;
    this.method = method;
    this.skip = skip;
    this.times = times;
  }

  /**
   * Match the requests of these operations.
   *
   * @param operations the names of the operations, e.g. {@code GetObject}, as the request statistics report them.
   * @return the matcher.
   */
  public static FaultMatcher op(String... operations) {
    if (operations.length == 0) {
      throw new IllegalArgumentException("Name at least one operation, or use anyOp().");
    }
    Arrays.stream(operations).forEach(Objects::requireNonNull);
    return new FaultMatcher(Set.of(operations), null, null, null, null, 0, Long.MAX_VALUE);
  }

  /**
   * Match the requests of every operation.
   *
   * @return the matcher.
   */
  public static FaultMatcher anyOp() {
    return new FaultMatcher(null, null, null, null, null, 0, Long.MAX_VALUE);
  }

  /**
   * Only the requests of this bucket.
   *
   * @param bucket the name of the bucket.
   * @return a new matcher.
   */
  public FaultMatcher bucket(String bucket) {
    return new FaultMatcher(operations, Objects.requireNonNull(bucket), key, keyPrefix, method, skip, times);
  }

  /**
   * Only the requests of this object key.
   *
   * @param key the key.
   * @return a new matcher.
   */
  public FaultMatcher key(String key) {
    return new FaultMatcher(operations, bucket, Objects.requireNonNull(key), keyPrefix, method, skip, times);
  }

  /**
   * Only the requests of the object keys with this prefix.
   *
   * @param keyPrefix the prefix.
   * @return a new matcher.
   */
  public FaultMatcher keyPrefix(String keyPrefix) {
    return new FaultMatcher(operations, bucket, key, Objects.requireNonNull(keyPrefix), method, skip, times);
  }

  /**
   * Only the requests of this HTTP method.
   *
   * @param method the method, e.g. {@code PUT}.
   * @return a new matcher.
   */
  public FaultMatcher method(String method) {
    return new FaultMatcher(operations, bucket, key, keyPrefix, method.toUpperCase(Locale.ROOT), skip, times);
  }

  /**
   * Not the first {@code count} matching requests.
   *
   * @param count the number of requests to let through first.
   * @return a new matcher.
   */
  public FaultMatcher skip(long count) {
    if (count < 0) {
      throw new IllegalArgumentException("skip must not be negative.");
    }
    return new FaultMatcher(operations, bucket, key, keyPrefix, method, count, times);
  }

  /**
   * Only {@code count} matching requests, after the {@linkplain #skip skipped} ones.
   *
   * @param count the number of requests.
   * @return a new matcher.
   */
  public FaultMatcher times(long count) {
    if (count < 1) {
      throw new IllegalArgumentException("times must be at least 1.");
    }
    return new FaultMatcher(operations, bucket, key, keyPrefix, method, skip, count);
  }

  /**
   * Only the {@code n}th matching request, i.e. {@code skip(n - 1).times(1)}.
   *
   * @param n the number of the request, from 1.
   * @return a new matcher.
   */
  public FaultMatcher nth(long n) {
    if (n < 1) {
      throw new IllegalArgumentException("nth counts from 1.");
    }
    return skip(n - 1).times(1);
  }

  boolean matches(String operation, String requestMethod, @Nullable String requestBucket,
                  @Nullable String requestKey) {
    return (operations == null || operations.contains(operation))
        && (method == null || method.equals(requestMethod))
        && (bucket == null || bucket.equals(requestBucket))
        && (key == null || key.equals(requestKey))
        && (keyPrefix == null || (requestKey != null && requestKey.startsWith(keyPrefix)));
  }

  /**
   * Whether the {@code n}th matching request, from 1, is one to fail.
   */
  boolean selects(long n) {
    return n > skip && n - skip <= times;
  }

  @Override
  public String toString() {
    StringBuilder text = new StringBuilder(operations == null ? "anyOp()" : "op(" + operations + ")");
    if (bucket != null) {
      text.append(".bucket(").append(bucket).append(')');
    }
    if (key != null) {
      text.append(".key(").append(key).append(')');
    }
    if (keyPrefix != null) {
      text.append(".keyPrefix(").append(keyPrefix).append(')');
    }
    if (method != null) {
      text.append(".method(").append(method).append(')');
    }
    if (skip > 0) {
      text.append(".skip(").append(skip).append(')');
    }
    if (times != Long.MAX_VALUE) {
      text.append(".times(").append(times).append(')');
    }
    return text.toString();
  }

}
