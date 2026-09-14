package com.robothy.s3.jupiter.extensions;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.supplier.DataPathSupplier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import lombok.SneakyThrows;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the LocalS3 services of {@linkplain LocalS3 @LocalS3}: one for an annotated test class, which serves the
 * tests of the class and of its {@code @Nested} classes, and one for an annotated test method.
 *
 * <p>A service is kept in the store of the extension context of the class or method it was launched for, not in a
 * static or thread-local field, so that the service of a {@code @Nested} class doesn't replace the one of its enclosing
 * class, and the callbacks of a class may run on different threads, as they do when tests run in parallel. Looking a
 * service up in the store of a context falls back to the stores of its ancestors, so the innermost service is found: the
 * one of the method, then the one of its class, then the one of the enclosing class. A service is shut down in the
 * after callback of its context, which runs after the {@code @AfterAll} or {@code @AfterEach} methods that may still use
 * it; if the callback doesn't get to it, JUnit 5.13 and later close it with the store.
 */
public class LocalS3Extension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

  private static final Logger logger = LoggerFactory.getLogger(LocalS3Extension.class);

  private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(LocalS3Extension.class);

  private static final String SERVICE_KEY = "service";

  /**
   * The suffix of the key that the port of a service is also stored under in the global namespace, which the extension
   * itself no longer reads.
   */
  public static final String LOCAL_S3_PORT_STORE_SUFFIX = ".LocalS3.Port";

  public static final String AMAZON_S3_REGION_STORE_SUFFIX = ".AmazonS3.Region";

  @Override
  public void beforeAll(ExtensionContext context) {
    LocalS3 s3Config = context.getRequiredTestClass().getAnnotation(LocalS3.class);
    if (s3Config != null) {
      start(context, s3Config, context.getRequiredTestClass() + LOCAL_S3_PORT_STORE_SUFFIX);
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    Method testMethod = context.getRequiredTestMethod();
    LocalS3 s3Config = testMethod.getAnnotation(LocalS3.class);
    if (s3Config != null) {
      start(context, s3Config, context.getRequiredTestClass() + (testMethod + LOCAL_S3_PORT_STORE_SUFFIX));
    }
  }

  @Override
  public void afterEach(@NonNull ExtensionContext context) {
    stop(context);
  }

  @Override
  public void afterAll(@NonNull ExtensionContext context) {
    stop(context);

    // Release the initial data that the tests of this class cached, so that a large suite doesn't keep the
    // data of every data path it ran with on the heap.
    com.robothy.s3.rest.LocalS3.clearInitialDataCache();
  }

  /**
   * The service that serves the tests of a context: the one launched for the context, or else for the nearest of its
   * ancestors.
   *
   * @param context the context of a test, a lifecycle method, or a class.
   * @return the service, or empty if neither the context nor an ancestor has one.
   */
  static Optional<Service> service(ExtensionContext context) {
    return Optional.ofNullable(context.getStore(NAMESPACE).get(SERVICE_KEY, Service.class));
  }

  private void start(ExtensionContext context, LocalS3 s3Config, String portKey) {
    Service service = new Service(launch(s3Config), s3Config);
    context.getStore(NAMESPACE).put(SERVICE_KEY, service);
    context.getStore(ExtensionContext.Namespace.GLOBAL).put(portKey, service.port());
  }

  /**
   * Shut down the service launched for the context, if any. Removing a value only removes it from the store of the
   * context itself, so the service of an enclosing class is left running.
   */
  private void stop(ExtensionContext context) {
    Service service = context.getStore(NAMESPACE).remove(SERVICE_KEY, Service.class);
    if (service != null) {
      service.close();
    }
  }

  @SneakyThrows
  private com.robothy.s3.rest.LocalS3 launch(LocalS3 s3Config) {
    String dataPath = s3Config.dataPath();
    if (dataPath.isBlank() && s3Config.dataPathSupplier() != DataPathSupplier.class) {
      try {
        Constructor<? extends DataPathSupplier> constructor = s3Config.dataPathSupplier().getDeclaredConstructor();
        DataPathSupplier dataPathSupplier = constructor.newInstance();
        dataPath = dataPathSupplier.get();
      } catch (NoSuchMethodException e) {
        throw new IllegalArgumentException("You must define no-args constructor in " + s3Config.dataPathSupplier());
      }
    }

    com.robothy.s3.rest.LocalS3Builder builder = com.robothy.s3.rest.LocalS3.builder()
        .port(s3Config.port())
        .mode(s3Config.mode())
        .buckets(s3Config.buckets())
        .initialDataCacheEnabled(s3Config.initialDataCacheEnabled())
        .strictBucketNames(s3Config.strictBucketNames())
        .strictPartSizes(s3Config.strictPartSizes())
        .compositeMultipartEtags(s3Config.compositeMultipartEtags())
        .virtualHostDomains(s3Config.virtualHostDomains());
    // The data path supplier may return null.
    if (dataPath != null && !dataPath.isBlank()) {
      builder.dataPath(dataPath);
    }
    if (verifiesSignatures(s3Config)) {
      builder.credentials(s3Config.accessKey(), s3Config.secretKey());
    }
    com.robothy.s3.rest.LocalS3 localS3 = builder.build();
    localS3.start();
      logger.debug("LocalS3 endpoint http://localhost:{}", localS3.getPort());
    return localS3;
  }

  /**
   * Whether the annotation configures the credentials that requests are verified against, which turns on
   * AWS Signature Version 4 verification.
   *
   * @param s3Config the annotation.
   * @return {@code true} if the service verifies signatures.
   * @throws IllegalArgumentException if only one of the two is configured, which would otherwise leave
   *     verification off without saying so.
   */
  static boolean verifiesSignatures(LocalS3 s3Config) {
    boolean hasAccessKey = !s3Config.accessKey().isBlank();
    boolean hasSecretKey = !s3Config.secretKey().isBlank();
    if (hasAccessKey != hasSecretKey) {
      throw new IllegalArgumentException("@LocalS3 accessKey and secretKey must be set together; "
          + (hasAccessKey ? "secretKey" : "accessKey") + " is missing.");
    }
    return hasAccessKey;
  }

  /**
   * A running service and the annotation it was launched from, so that its clients are configured the way the service
   * is, e.g. with its credentials.
   *
   * @param localS3 the service.
   * @param config the annotation the service was launched from.
   */
  record Service(com.robothy.s3.rest.LocalS3 localS3, LocalS3 config) implements AutoCloseable {

    int port() {
      return localS3.getPort();
    }

    @Override
    public void close() {
      localS3.shutdown();
    }

  }

}