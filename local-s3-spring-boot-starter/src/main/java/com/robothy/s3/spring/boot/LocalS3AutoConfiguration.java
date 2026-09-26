package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Builder;
import com.robothy.s3.rest.LocalS3IcebergCatalog;
import com.robothy.s3.rest.LocalS3Cors;
import com.robothy.s3.rest.LocalS3Website;
import com.robothy.s3.rest.LocalS3Seeder;
import com.robothy.s3.rest.netty.RequestRecorder;

import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.S3VectorsClientBuilder;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

/**
 * Embeds a LocalS3 service in a Spring Boot application:
 *
 * <ul>
 *   <li>a {@linkplain LocalS3} bean configured by {@linkplain LocalS3Properties local-s3.*} and the
 *   {@linkplain LocalS3BuilderCustomizer customizers}, which {@linkplain LocalS3Lifecycle} starts and stops with the
 *   application context;</li>
 *   <li>the buckets and objects of the directory tree of {@code local-s3.seed.classpath}, put into the service when it
 *   starts and after every reset;</li>
 *   <li>the {@code S3Change}s that the service commits, published to the application context, where
 *   {@code @EventListener} and {@code @TransactionalEventListener} methods receive them;</li>
 *   <li>an {@linkplain S3Client}, an {@linkplain S3AsyncClient} and an {@linkplain S3Presigner} that point at the
 *   service, unless the application defines its own, when the AWS SDK is on the classpath (the {@code S3AsyncClient}
 *   with {@code netty-nio-client}, {@code aws-crt-client} or {@code aws-crt}), and likewise an
 *   {@linkplain S3VectorsClient}, an {@linkplain S3TablesClient} and an {@linkplain S3TransferManager} when their
 *   modules are.</li>
 * </ul>
 *
 * <p>Set {@code local-s3.enabled=false} to leave the service out, e.g. in the profile that runs against Amazon S3.
 *
 * <p>Ordered before the S3 auto-configurations of Spring Cloud AWS, whose clients are {@code @ConditionalOnMissingBean}
 * too: the clients of the starter are defined first, so Spring Cloud AWS backs off from them, and its
 * {@code S3Template} uses them. The classes are named, rather than referenced, since Spring Cloud AWS is optional.
 */
@AutoConfiguration(beforeName = {
    "io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration",
    "io.awspring.cloud.autoconfigure.s3.S3CrtAsyncClientAutoConfiguration",
    "io.awspring.cloud.autoconfigure.s3.S3TransferManagerAutoConfiguration",
    "io.awspring.cloud.autoconfigure.s3vectors.S3VectorClientAutoConfiguration"
})
@ConditionalOnClass(LocalS3.class)
@ConditionalOnProperty(name = "local-s3.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LocalS3Properties.class)
public class LocalS3AutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(LocalS3AutoConfiguration.class);

  /**
   * The key pair of the clients when LocalS3 accepts unsigned requests. Any pair is accepted; the clients sign with one
   * rather than send anonymous requests, which the AWS SDK doesn't sign or checksum like requests to Amazon S3.
   */
  static final String DEFAULT_CLIENT_KEY = "local-s3";

  /**
   * The property that {@code SpringBootTestContextBootstrapper} sets to {@code true} in the environment of the context of
   * a {@code @SpringBootTest}, on Spring Boot 3 and 4 alike.
   */
  static final String SPRING_BOOT_TEST_PROPERTY =
      "org.springframework.boot.test.context.SpringBootTestContextBootstrapper";

  @Bean
  @ConditionalOnMissingBean
  public LocalS3 localS3(LocalS3Properties properties, ObjectProvider<LocalS3ApplicationEventPublisher> eventPublisher,
                         ObjectProvider<LocalS3BuilderCustomizer> customizers,
                         ObjectProvider<RequestRecorder> requestRecorders,
                         ObjectProvider<LocalS3Seeder> seeders, Environment environment) {
    LocalS3Builder builder = LocalS3.builder()
        // The application context stops the service; a hook of its own would stop it before the beans that use it.
        .netty(netty -> netty.registerShutdownHook(false));
    apply(properties, builder);
    if (randomPortForTest(environment)) {
      log.info("LocalS3 listens on a random free port in a @SpringBootTest, since local-s3.port isn't set; "
          + "${local.s3.endpoint} names it. Set local-s3.port to listen on a fixed one.");
      builder.port(0);
    }
    LocalS3ApplicationEventPublisher events = eventPublisher.getIfAvailable();
    if (events != null) {
      // Published on the thread that made the change, so that a listener of a change made in a transaction takes part
      // in it, e.g. a @TransactionalEventListener of a put through getS3Manager() in a @Transactional method.
      builder.events(listeners -> listeners.listener(events));
    }
    List<RequestRecorder> recorders = requestRecorders.orderedStream().toList();
    if (!recorders.isEmpty()) {
      builder.netty(netty -> netty.requestRecorder((request, operation, status, requestId, durationNanos) ->
          recorders.forEach(recorder -> recorder.record(request, operation, status, requestId, durationNanos))));
    }
    // The objects that the service starts with, and that a reset puts back, before the customizers, so that one of
    // them can seed on top of the fixtures of local-s3.seed.classpath.
    seeders.orderedStream().forEach(builder::seeder);
    customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
    return builder.build();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(name = "local-s3.events.enabled", havingValue = "true", matchIfMissing = true)
  public LocalS3ApplicationEventPublisher localS3ApplicationEventPublisher(ApplicationContext applicationContext) {
    return new LocalS3ApplicationEventPublisher(applicationContext);
  }

  /**
   * Seeds the service from the directory tree of {@code local-s3.seed.classpath}, when one is configured. An
   * application that seeds otherwise defines a {@linkplain LocalS3Seeder} bean of its own, which is applied too.
   *
   * @param properties the configuration, whose {@code seed.classpath} names the tree.
   * @param context resolves the location with the class loader of the application.
   * @return the seeder of the configured classpath location.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(name = "local-s3.seed.enabled", havingValue = "true", matchIfMissing = true)
  @ConditionalOnProperty(name = "local-s3.seed.classpath")
  public ClasspathLocalS3Seeder classpathLocalS3Seeder(LocalS3Properties properties, ApplicationContext context) {
    // The context resolves the location with the class loader of the application, e.g. the one of a launched jar.
    return new ClasspathLocalS3Seeder(properties.getSeed().getClasspath(), context);
  }

  /**
   * Starts and stops the service with the context. Under Spring Boot DevTools, whose restart class loader loads the
   * application, an {@code IN_MEMORY} service keeps its data across the restarts, unless
   * {@code local-s3.devtools.keep-data=false}.
   *
   * @param localS3 the service.
   * @param properties the configuration.
   * @param context the application context, whose class loader tells whether DevTools restarts it.
   * @return the lifecycle of the service.
   */
  @Bean
  @ConditionalOnMissingBean
  public LocalS3Lifecycle localS3Lifecycle(LocalS3 localS3, LocalS3Properties properties, ApplicationContext context) {
    return new LocalS3Lifecycle(localS3, properties.getDevtools().isKeepData()
        && LocalS3DevToolsRestart.isRestartable(context.getClassLoader()));
  }

  /**
   * Publishes {@code local.s3.endpoint} and {@code local.s3.port} to the environment, see
   * {@linkplain LocalS3PropertySource}. Static, since it post-processes the bean factory before this configuration is
   * created.
   *
   * @return the registrar of the property source.
   */
  @Bean
  static LocalS3PropertySource.Registrar localS3PropertySourceRegistrar() {
    return new LocalS3PropertySource.Registrar();
  }

  /**
   * Whether the service listens on a random free port rather than the default {@code 29090} of
   * {@code local-s3.port}: in the context of a {@code @SpringBootTest}, unless the port is configured. The test context
   * framework caches the contexts of test classes with different configurations side by side, and each of them has a
   * service of its own, which would compete for a fixed port. The clients of the starter and
   * {@code ${local.s3.endpoint}} name the port the service is listening on.
   */
  static boolean randomPortForTest(Environment environment) {
    return environment.getProperty(SPRING_BOOT_TEST_PROPERTY, Boolean.class, false)
        && !Binder.get(environment).bind("local-s3.port", Bindable.of(Integer.class)).isBound();
  }

  // Set directly rather than through PropertyMapper: Spring Boot 4 changed the parameter of Source.as() from a
  // Function to a Source.Adapter, and made Source.to() skip a null value, so a starter compiled against one line
  // fails on the other with a NoClassDefFoundError, or applies the nulls. A value that isn't configured is left out,
  // like Spring Boot 4 does, so that LocalS3Builder keeps its own default.
  static void apply(LocalS3Properties properties, LocalS3Builder builder) {
    applyIfSet(properties.getBindHost(), builder::bindHost);
    builder.port(properties.getPort());
    applyIfSet(properties.getMode(), builder::mode);
    if (hasText(properties.getDataPath())) {
      builder.dataPath(properties.getDataPath());
    }
    applyIfSet(properties.getPersistencePolicy(),
        policy -> builder.storage(storage -> storage.persistencePolicy(policy)));
    applyIfSet(properties.getBuckets(), buckets -> builder.buckets(buckets.toArray(String[]::new)));
    applyIfSet(properties.getVersionedBuckets(),
        buckets -> builder.versionedBuckets(buckets.toArray(String[]::new)));
    builder.storage(storage -> storage.initialDataCacheEnabled(properties.isInitialDataCacheEnabled()));
    applyIfSet(properties.getInMemory().getMaxSize(),
        size -> builder.storage(storage -> storage.maxInMemoryBytes(size.toBytes())));
    builder.s3Api(s3 -> s3.compositeMultipartEtags(properties.isCompositeMultipartEtags()));
    applyIfSet(properties.getVirtualHostDomains(),
        domains -> builder.s3Api(s3 -> s3.virtualHostDomains(domains.toArray(String[]::new))));

    LocalS3Properties.IcebergCatalog iceberg = properties.getIcebergCatalog();
    if (iceberg.isEnabled()) {
      builder.icebergCatalog(catalog -> catalog.settings(new LocalS3IcebergCatalog(iceberg.getWarehouse(),
          iceberg.isCreateWarehouseBucket(), iceberg.isCredentialVending(), iceberg.isUniqueTableLocation())));
    }

    LocalS3Properties.Website website = properties.getWebsite();
    builder.website(settings -> settings.settings(new LocalS3Website(website.isEnabled(), website.isAllBuckets(),
        website.getIndexDocument(), website.getErrorDocument())));

    LocalS3Cors cors = properties.getCors().toLocalS3Cors();
    if (cors.enabled()) {
      builder.defaultCors(cors);
    }

    LocalS3Properties.Credentials credentials = properties.getCredentials();
    boolean hasAccessKeyId = hasText(credentials.getAccessKeyId());
    if (hasAccessKeyId != hasText(credentials.getSecretAccessKey())) {
      throw new IllegalArgumentException(
          "local-s3.credentials.access-key-id and local-s3.credentials.secret-access-key must be set together.");
    }
    if (hasAccessKeyId) {
      builder.credentials(credentials.getAccessKeyId(), credentials.getSecretAccessKey());
    }

    LocalS3Properties.Threads threads = properties.getThreads();
    LocalS3Properties.Requests requests = properties.getRequests();
    builder.netty(netty -> {
      netty.virtualThreads(threads.isVirtual())
          .daemonThreads(threads.isDaemon())
          .parentEventGroupThreadNum(threads.getNettyParentEventGroup())
          .childEventGroupThreadNum(threads.getNettyChildEventGroup())
          .s3ExecutorThreadNum(threads.getExecutor());
      applyIfSet(requests.getMaxBodySize(), size -> netty.maxRequestBodySize(size.toBytes()));
      applyIfSet(requests.getBodyFileThreshold(), size -> netty.requestBodyFileThreshold(size.toBytes()));
      applyIfSet(requests.getMaxHeaderSize(), size -> netty.maxRequestHeaderSize(Math.toIntExact(size.toBytes())));
      applyIfSet(requests.getIdleConnectionTimeout(), timeout -> netty.idleConnectionTimeoutSeconds(timeout.toSeconds()));
    });
  }

  private static <T> void applyIfSet(T value, Consumer<T> setter) {
    if (value != null) {
      setter.accept(value);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * The clients of the AWS SDK that point at the embedded service, with path-style requests, and signed with the
   * credentials of the service, if it requires any. Creating a client starts the service. The AWS SDK is an optional
   * dependency of the starter: without {@code software.amazon.awssdk:s3}, the application only embeds the service.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(S3Client.class)
  @ConditionalOnProperty(name = "local-s3.clients.enabled", havingValue = "true", matchIfMissing = true)
  static class ClientsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    S3Client s3Client(LocalS3Lifecycle lifecycle, LocalS3Properties properties) {
      return S3Client.builder()
          .endpointOverride(pointAtLocalS3(S3Client.class, lifecycle))
          .region(Region.of(properties.getClients().getRegion()))
          .credentialsProvider(credentials(lifecycle.getLocalS3()))
          .forcePathStyle(true)
          .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(AsyncClientCondition.class)
    S3AsyncClient s3AsyncClient(LocalS3Lifecycle lifecycle, LocalS3Properties properties, ApplicationContext context) {
      return asyncClient(S3AsyncClient.class, lifecycle, properties, context.getClassLoader(), false);
    }

    @Bean
    @ConditionalOnMissingBean
    S3Presigner s3Presigner(LocalS3Lifecycle lifecycle, LocalS3Properties properties) {
      return S3Presigner.builder()
          .endpointOverride(pointAtLocalS3(S3Presigner.class, lifecycle))
          .region(Region.of(properties.getClients().getRegion()))
          .credentialsProvider(credentials(lifecycle.getLocalS3()))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
          .build();
    }

    /**
     * The endpoint of the service, logged loudly: the starter is meant for development and tests, and one that ends up
     * on the classpath of a production build by mistake, e.g. as an {@code implementation} rather than a
     * {@code developmentOnly} dependency, silently points the clients of the application at the embedded service.
     */
    private static URI pointAtLocalS3(Class<?> clientType, LocalS3Lifecycle lifecycle) {
      URI endpoint = lifecycle.endpoint();
      log.warn("The {} bean points at the embedded LocalS3 at {}, not at Amazon S3. The LocalS3 starter is meant for "
              + "development and tests only; set local-s3.enabled=false, or local-s3.clients.enabled=false, to leave it "
              + "out.", clientType.getSimpleName(), endpoint);
      return endpoint;
    }

    /**
     * An {@linkplain S3AsyncClient} that points at the service. It is built on the asynchronous HTTP client of the AWS
     * SDK that the application brings, {@code netty-nio-client} or {@code aws-crt-client}, which the SDK picks from the
     * classpath; without either, on the AWS Common Runtime alone ({@code aws-crt}, e.g. the one that Spring Cloud AWS
     * builds its {@code S3CrtAsyncClient} on), as an {@code S3CrtAsyncClient}, which uploads and downloads in parts of
     * its own.
     *
     * @param multipart whether an {@code S3AsyncClient} of an HTTP client uploads and downloads in parts, like the one of
     *     a transfer manager does.
     */
    static S3AsyncClient asyncClient(Class<?> clientType, LocalS3Lifecycle lifecycle, LocalS3Properties properties,
                                     ClassLoader classLoader, boolean multipart) {
      URI endpoint = pointAtLocalS3(clientType, lifecycle);
      Region region = Region.of(properties.getClients().getRegion());
      AwsCredentialsProvider credentials = credentials(lifecycle.getLocalS3());
      if (AsyncClientCondition.hasAsyncHttpClient(classLoader)) {
        return S3AsyncClient.builder()
            .endpointOverride(endpoint)
            .region(region)
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .multipartEnabled(multipart)
            .build();
      }
      return S3AsyncClient.crtBuilder()
          .endpointOverride(endpoint)
          .region(region)
          .credentialsProvider(credentials)
          .forcePathStyle(true)
          .build();
    }

    private static AwsCredentialsProvider credentials(LocalS3 localS3) {
      String accessKeyId = localS3.getConfig().accessKeyId();
      String secretAccessKey = localS3.getConfig().secretAccessKey();
      if (accessKeyId == null) {
        accessKeyId = DEFAULT_CLIENT_KEY;
        secretAccessKey = DEFAULT_CLIENT_KEY;
      }
      return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
    }

    /**
     * The builder of the client of the S3 Vectors API, with {@code software.amazon.awssdk:s3vectors}, which
     * {@linkplain LocalS3VectorsClientAutoConfiguration} builds the client with. A builder rather than the client,
     * because Spring Cloud AWS defines its {@code s3VectorsClient} whatever the application defines, from the
     * {@code S3VectorsClientBuilder} bean, which it backs off from: its client then points at the service too.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(S3VectorsClient.class)
    static class VectorsClientConfiguration {

      @Bean
      @ConditionalOnMissingBean
      S3VectorsClientBuilder s3VectorsClientBuilder(LocalS3Lifecycle lifecycle, LocalS3Properties properties) {
        return S3VectorsClient.builder()
            .endpointOverride(pointAtLocalS3(S3VectorsClient.class, lifecycle))
            .region(Region.of(properties.getClients().getRegion()))
            .credentialsProvider(credentials(lifecycle.getLocalS3()));
      }

    }

    /**
     * The client of the S3 Tables API, with {@code software.amazon.awssdk:s3tables}. It is pointed straight at the
     * service, since it always signs, and the {@code s3tables} service of its credential scope is what tells its
     * requests from the S3 ones, whose paths they share.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(S3TablesClient.class)
    static class TablesClientConfiguration {

      @Bean
      @ConditionalOnMissingBean
      S3TablesClient s3TablesClient(LocalS3Lifecycle lifecycle, LocalS3Properties properties) {
        return S3TablesClient.builder()
            .endpointOverride(pointAtLocalS3(S3TablesClient.class, lifecycle))
            .region(Region.of(properties.getClients().getRegion()))
            .credentialsProvider(credentials(lifecycle.getLocalS3()))
            .build();
      }

    }

    /**
     * The transfer manager, with {@code software.amazon.awssdk:s3-transfer-manager} and {@code netty-nio-client}. It
     * transfers through an {@linkplain S3AsyncClient} of its own, with multipart uploads and downloads enabled, rather
     * than through the {@code S3AsyncClient} bean, whose {@code putObject} would then split large objects into parts
     * too. The configuration closes that client after the transfer manager, which doesn't close a client it was given.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(S3TransferManager.class)
    @Conditional(AsyncClientCondition.class)
    static class TransferManagerConfiguration implements DisposableBean {

      private S3AsyncClient transferClient;

      @Bean
      @ConditionalOnMissingBean
      S3TransferManager s3TransferManager(LocalS3Lifecycle lifecycle, LocalS3Properties properties,
                                          ApplicationContext context) {
        transferClient = asyncClient(S3TransferManager.class, lifecycle, properties, context.getClassLoader(), true);
        return S3TransferManager.builder().s3Client(transferClient).build();
      }

      @Override
      public void destroy() {
        if (transferClient != null) {
          transferClient.close();
        }
      }

    }

  }

}
