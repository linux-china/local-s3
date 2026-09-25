package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Carries the data of an {@code IN_MEMORY} service across the restarts of Spring Boot DevTools, which closes the
 * application context and creates a new one in the same JVM: the service of the closed context is kept here once it is
 * stopped, and the service of the new context {@linkplain LocalS3#takeOverDataOf takes its data over}, so that the
 * objects uploaded before the restart are still there after it.
 *
 * <p>DevTools loads the classes of the application with a {@code RestartClassLoader}, which it drops at every restart,
 * and the jars, such as this starter, with the class loader below it, which it keeps; the service is held in a static
 * field of this class, i.e. of that class loader, like the {@code @RestartScope} beans of Testcontainers are. Only the
 * data is carried over, not the service: the new service is built from the configuration of the new context, with its
 * listeners and seeders, rather than with those of the closed context. If the starter is itself loaded by the
 * {@code RestartClassLoader}, e.g. by {@code restart.include}, the data isn't kept, as without this class.
 */
final class LocalS3DevToolsRestart {

  static final String RESTART_CLASS_LOADER =
      "org.springframework.boot.devtools.restart.classloader.RestartClassLoader";

  private static final AtomicReference<LocalS3> STOPPED = new AtomicReference<>();

  private LocalS3DevToolsRestart() {
  }

  /**
   * Whether the application runs under DevTools, which restarts it.
   *
   * @param classLoader the class loader of the application context.
   * @return {@code true} if it is, or is below, a {@code RestartClassLoader}.
   */
  static boolean isRestartable(ClassLoader classLoader) {
    for (ClassLoader loader = classLoader; loader != null; loader = loader.getParent()) {
      if (RESTART_CLASS_LOADER.equals(loader.getClass().getName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Keep a service that is stopped until the next context takes its data over, replacing a service kept earlier.
   *
   * @param localS3 the stopped service; only an {@code IN_MEMORY} one is kept, a {@code PERSISTENCE} one keeps its
   *     data in its data path.
   */
  static void keep(LocalS3 localS3) {
    if (localS3.getMode() == LocalS3Mode.IN_MEMORY && !localS3.isRunning()) {
      STOPPED.set(localS3);
    }
  }

  /**
   * Let a service take over the data of the service that was kept, if any, which is released either way.
   *
   * @param localS3 the service of the new context, not started yet.
   * @return {@code true} if it took the data over.
   */
  static boolean takeOver(LocalS3 localS3) {
    LocalS3 previous = STOPPED.getAndSet(null);
    return previous != null && localS3.takeOverDataOf(previous);
  }

}
