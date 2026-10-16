package com.robothy.s3.rest.fault;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.s3.rest.handler.LocalS3RouterFactory;
import com.robothy.s3.rest.netty.FaultInjector;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * The {@linkplain Fault}s of a LocalS3 service, see {@code LocalS3.faults()} and
 * {@code docs/embedding.md#inject-faults}. Thread-safe: faults may be added and removed while requests are handled.
 */
public final class Faults implements FaultInjector {

  private final List<Fault> faults = new CopyOnWriteArrayList<>();

  /**
   * Add a fault, after the faults added before, which win when several apply to a request.
   *
   * @param fault the fault.
   * @return the fault, e.g. to read {@linkplain Fault#injected()} later.
   * @throws IllegalArgumentException if the fault has no action, or actions that don't go together.
   */
  public Fault add(Fault fault) {
    fault.validate();
    faults.add(fault);
    return fault;
  }

  /**
   * Remove a fault.
   *
   * @param fault the fault.
   * @return whether it was added.
   */
  public boolean remove(Fault fault) {
    return faults.remove(fault);
  }

  /**
   * Remove every fault.
   */
  public void clear() {
    faults.clear();
  }

  /**
   * The faults, in the order they were added.
   *
   * @return a copy of the faults.
   */
  public List<Fault> list() {
    return List.copyOf(faults);
  }

  @Override
  public @Nullable Injection inject(String operation, RouterHttpRequest request) {
    // The health check, the admin endpoints and the console keep working, so that a probe of the service does.
    if (faults.isEmpty() || LocalS3RouterFactory.UNRECORDED_OPERATIONS.contains(operation)) {
      return null;
    }
    String method = request.getMethod().name();
    String bucket = request.parameter("bucket").orElse(null);
    String key = request.parameter("key").map(k -> k.startsWith("/") ? k.substring(1) : k).orElse(null);
    Injection selected = null;
    // Every matching fault counts the request, so that its skip(), times() and nth() don't depend on the others.
    for (Fault fault : faults) {
      if (fault.matcher().matches(operation, method, bucket, key)) {
        long n = fault.count();
        if (selected == null) {
          selected = fault.inject(n);
        }
      }
    }
    return selected;
  }

}
