package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.util.Objects;
import org.springframework.context.SmartLifecycle;

/**
 * Starts and stops the embedded LocalS3 service with the application context.
 *
 * <p>The service is started in an early {@linkplain #PHASE phase}: before the web server and the message listener
 * containers, which run in later phases, start taking the work that stores objects, and stopped after they have
 * finished it. It is also started as soon as a client bean of the auto-configuration is created, since the endpoint of
 * a client names the port that the service listens on, which is only known once it is started when a random port is
 * configured; a bean that uses a client while it is initialized, e.g. in a {@code @PostConstruct} method, finds the
 * service running. Starting a running service does nothing.
 *
 * <p>The application context stops the service, so it registers no JVM shutdown hook of its own.
 */
public class LocalS3Lifecycle implements SmartLifecycle {

  /**
   * The phase of the service: earlier than the web server, whose phase is {@code DEFAULT_PHASE - 1024}.
   */
  public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 2048;

  private final LocalS3 localS3;

  public LocalS3Lifecycle(LocalS3 localS3) {
    this.localS3 = Objects.requireNonNull(localS3);
  }

  @Override
  public synchronized void start() {
    if (!localS3.isRunning()) {
      localS3.start();
    }
  }

  @Override
  public synchronized void stop() {
    localS3.shutdown();
  }

  @Override
  public boolean isRunning() {
    return localS3.isRunning();
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  /**
   * The service that this lifecycle starts and stops.
   *
   * @return the service.
   */
  public LocalS3 getLocalS3() {
    return localS3;
  }

  /**
   * The endpoint that clients in the same JVM reach the service at, starting the service if it isn't running yet: the
   * host that it is bound to, or the loopback address if it is bound to every interface, and the port it listens on.
   *
   * @return the endpoint, e.g. {@code http://127.0.0.1:29090}, or {@code https://127.0.0.1:29090} if it serves TLS.
   */
  public URI endpoint() {
    start();
    String host = localS3.getBindHost();
    if ("0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host)) {
      host = "127.0.0.1";
    } else if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
      host = "[" + host + "]";
    }
    return URI.create((localS3.isTlsEnabled() ? "https://" : "http://") + host + ":" + localS3.getPort());
  }

}
