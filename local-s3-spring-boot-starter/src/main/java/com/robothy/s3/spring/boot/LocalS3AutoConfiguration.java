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
import org.springframework.beans.factory.ListableBeanFactory;
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
 * See {@code docs/embedding.md#spring-boot} and the README of the starter.
 *
 * <p>Ordered before the S3 auto-configurations of Spring Cloud AWS, whose clients are {@code @ConditionalOnMissingBean}
 * too, so that Spring Cloud AWS backs off from the clients of the starter and its {@code S3Template} uses them. The
 * classes are named, rather than referenced, since Spring Cloud AWS is optional.
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
                         ObjectProvider<LocalS3Seeder> seeders, LocalS3EventExecutor eventExecutor,
                         Environment environment) {
    LocalS3Builder builder = LocalS3.builder()
        // The application context stops the service; a hook of its own would stop it before the beans that use it.
        .netty(netty -> netty.registerShutdownHook(false));
    apply(properties, builder);
    if (randomPortForTest(environment)) {
      log.info("LocalS3 listens on a random free port in a @SpringBootTest, since local-s3.port isn't set; "
          + "${local.s3.endpoint} names it. Set local-s3.port to listen on a fixed one.");
      builder.port(0);
    }
    // The direct executor publishes on the thread that made the change, so that a listener of a change made in a
    // transaction takes part in it, e.g. a @TransactionalEventListener of a put through getS3Manager() in a
    // @Transactional method; the others trade that for requests that listeners don't hold up.
    builder.events(settings -> settings.executor(eventExecutor.executor()));
    LocalS3ApplicationEventPublisher events = eventPublisher.getIfAvailable();
    if (events != null) {
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
    warnIfTheClientsBackOff(environment);
    return builder.build();
  }

  // Warned from the bean of the service rather than from LocalS3ClientsCondition, which is evaluated more than once.
  private static void warnIfTheClientsBackOff(Environment environment) {
    if (environment.getProperty(ExternalS3Endpoint.CLIENTS_ENABLED) != null) {
      return;
    }
    ExternalS3Endpoint.find(environment).ifPresent(external -> log.warn("The LocalS3 starter defines no S3 clients: {} "
        + "is {}, which isn't the embedded LocalS3, so the clients of the application reach that endpoint. The "
        + "embedded LocalS3 still runs; set local-s3.enabled=false to leave it out, or local-s3.clients.enabled=true "
        + "to point the clients at it anyway.",
        external.property(), external.endpoint()));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(name = "local-s3.events.enabled", havingValue = "true", matchIfMissing = true)
  public LocalS3ApplicationEventPublisher localS3ApplicationEventPublisher(ApplicationContext applicationContext) {
    return new LocalS3ApplicationEventPublisher(applicationContext);
  }

  @Bean
  @ConditionalOnMissingBean
  public LocalS3EventExecutor localS3EventExecutor(LocalS3Properties properties, ListableBeanFactory beanFactory) {
    return LocalS3EventExecutor.of(properties.getEvents().getExecutor(), beanFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(name = "local-s3.seed.enabled", havingValue = "true", matchIfMissing = true)
  @ConditionalOnProperty(name = "local-s3.seed.classpath")
  public ClasspathLocalS3Seeder classpathLocalS3Seeder(LocalS3Properties properties, ApplicationContext context) {
    // The context resolves the location with the class loader of the application, e.g. the one of a launched jar.
    return new ClasspathLocalS3Seeder(properties.getSeed().getClasspath(), context);
  }

  @Bean
  @ConditionalOnMissingBean
  public LocalS3Lifecycle localS3Lifecycle(LocalS3 localS3, LocalS3Properties properties, ApplicationContext context) {
    return new LocalS3Lifecycle(localS3, properties.getDevtools().isKeepData()
        && LocalS3DevToolsRestart.isRestartable(context.getClassLoader()));
  }

  // Static, since it post-processes the bean factory before this configuration is created.
  @Bean
  static LocalS3PropertySource.Registrar localS3PropertySourceRegistrar() {
    return new LocalS3PropertySource.Registrar();
  }

  // The test context framework caches the contexts of test classes side by side, each with a service of its own,
  // which would compete for a fixed port.
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
    applyIfSet(properties.getLifecycle().getInterval(),
        interval -> builder.lifecycle(lifecycle -> lifecycle.applyEvery(interval)));
    builder.s3Api(s3 -> s3.compositeMultipartEtags(properties.isCompositeMultipartEtags())
        .acceptChunkedUploads(properties.isAcceptChunkedUploads()));
    applyIfSet(properties.getAllowedClockSkew(), skew -> builder.s3Api(s3 -> s3.allowedClockSkew(skew)));
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

  // The AWS SDK is optional: without it, the application only embeds the service.
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(S3Client.class)
  @Conditional(LocalS3ClientsCondition.class)
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

    // LocalS3Lifecycle logs the endpoint once, rather than a warning per client.
    private static URI pointAtLocalS3(Class<?> clientType, LocalS3Lifecycle lifecycle) {
      return lifecycle.endpointFor(clientType.getSimpleName());
    }

    /**
     * Without {@code netty-nio-client} or {@code aws-crt-client}, built on {@code aws-crt} alone as an
     * {@code S3CrtAsyncClient}, e.g. the one Spring Cloud AWS brings for its own.
     *
     * @param multipart whether an {@code S3AsyncClient} of an HTTP client uploads and downloads in parts.
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

    // A builder rather than the client: Spring Cloud AWS defines its s3VectorsClient whatever the application
    // defines, from the S3VectorsClientBuilder bean, which it backs off from, so its client points here too.
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

    // Not path-style: the s3tables service of its credential scope tells its requests from the S3 ones.
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

    // A client of its own, with multipart on, rather than the S3AsyncClient bean, whose putObject would then split
    // large objects too. Closed here, since the transfer manager doesn't close a client it was given.
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
