package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Config;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
 *
 * <p>Once started, the service is summed up in one line of the log: where it listens, whether it requires signed
 * requests, its mode, its data directory and the client beans that point at it, since an application may embed it for
 * longer than a test, e.g. to serve other processes. The client beans start the service one after the other while the
 * singletons are created, so the line is logged once they all are, rather than a line per client. A service that
 * answers anonymous requests from other machines is warned about as well.
 *
 * <p>Under Spring Boot DevTools, an {@code IN_MEMORY} service keeps its data across the restarts of the application:
 * the service of the new context takes over the data of the stopped one, see {@linkplain LocalS3DevToolsRestart}.
 */
public class LocalS3Lifecycle implements SmartLifecycle, SmartInitializingSingleton {

  /**
   * The phase of the service: earlier than the web server, whose phase is {@code DEFAULT_PHASE - 1024}.
   */
  public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 2048;

  private static final Logger log = LoggerFactory.getLogger(LocalS3Lifecycle.class);

  private final LocalS3 localS3;

  private final boolean keepDataAcrossRestarts;

  /**
   * The client beans that point at the service, named in the summary.
   */
  private final Set<String> clients = new LinkedHashSet<>();

  private boolean summaryLogged;

  public LocalS3Lifecycle(LocalS3 localS3) {
    this(localS3, false);
  }

  /**
   * A lifecycle that may carry the data of the service across the restarts of Spring Boot DevTools.
   *
   * @param localS3 the service, not started yet.
   * @param keepDataAcrossRestarts whether the service takes over the data of the service of the context that DevTools
   *     closed, and keeps its own data for the next context once it is stopped.
   */
  public LocalS3Lifecycle(LocalS3 localS3, boolean keepDataAcrossRestarts) {
    this.localS3 = Objects.requireNonNull(localS3);
    this.keepDataAcrossRestarts = keepDataAcrossRestarts;
    if (keepDataAcrossRestarts) {
      LocalS3DevToolsRestart.takeOver(localS3);
    }
  }

  @Override
  public synchronized void start() {
    startService();
    logSummaryOnce();
  }

  /**
   * Log the summary of a service that a client bean started while the singletons were created; the application
   * context doesn't call {@linkplain #start()} for a running service.
   */
  @Override
  public synchronized void afterSingletonsInstantiated() {
    if (localS3.isRunning()) {
      logSummaryOnce();
    }
  }

  private void startService() {
    if (!localS3.isRunning()) {
      localS3.start();
    }
  }

  private void logSummaryOnce() {
    if (summaryLogged) {
      return;
    }
    summaryLogged = true;
    LocalS3Config config = localS3.getConfig();
    Path dataPath = localS3.getDataPath();
    log.info("Embedded LocalS3: endpoint {}, bound to {}, signed requests {}, mode {}, data path {}{}.",
        localS3.endpoint(), config.bindHost(), config.authenticationEnabled() ? "required" : "not required",
        config.mode(), dataPath == null ? "none" : dataPath.toAbsolutePath(),
        clients.isEmpty() ? "" : "; " + String.join(", ", clients) + " point at it");
    if (!config.authenticationEnabled() && config.reachableFromOtherHosts()) {
      // LocalS3 warns about it too; this names the properties of the starter that close it.
      log.warn("The embedded LocalS3 listens on {} without credentials: every host that reaches port {} can read, "
          + "write and delete its data. Set local-s3.credentials.access-key-id and local-s3.credentials.secret-access-key, "
          + "or local-s3.bind-host=127.0.0.1, unless the service is meant to be open.", config.bindHost(),
          localS3.getPort());
    }
  }

  @Override
  public synchronized void stop() {
    localS3.shutdown();
    if (keepDataAcrossRestarts) {
      LocalS3DevToolsRestart.keep(localS3);
    }
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
  public synchronized URI endpoint() {
    startService();
    return URI.create(localS3.endpoint());
  }

  /**
   * The {@linkplain #endpoint() endpoint} of the service for a client bean, which the summary names. A client bean
   * created once the summary is logged, e.g. a {@code @Lazy} one, is logged on a line of its own.
   *
   * @param client the type of the client, e.g. {@code S3Client}.
   * @return the endpoint.
   */
  synchronized URI endpointFor(String client) {
    URI endpoint = endpoint();
    if (summaryLogged) {
      log.info("The {} bean points at the embedded LocalS3 at {}.", client, endpoint);
    } else {
      clients.add(client);
    }
    return endpoint;
  }

}
