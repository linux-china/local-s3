package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Builder;
import com.robothy.s3.rest.netty.RequestRecorder;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Embeds a LocalS3 service in a Spring Boot application:
 *
 * <ul>
 *   <li>a {@linkplain LocalS3} bean configured by {@linkplain LocalS3Properties local-s3.*} and the
 *   {@linkplain LocalS3BuilderCustomizer customizers}, which {@linkplain LocalS3Lifecycle} starts and stops with the
 *   application context;</li>
 *   <li>the {@code BucketEvent}s and {@code ObjectEvent}s of the service, published to the application context, where
 *   {@code @EventListener} and {@code @TransactionalEventListener} methods receive them;</li>
 *   <li>an {@linkplain S3Client}, an {@linkplain S3AsyncClient} and an {@linkplain S3Presigner} that point at the
 *   service, unless the application defines its own.</li>
 * </ul>
 *
 * <p>Set {@code local-s3.enabled=false} to leave the service out, e.g. in the profile that runs against Amazon S3.
 */
@AutoConfiguration
@ConditionalOnClass(LocalS3.class)
@ConditionalOnBooleanProperty(name = "local-s3.enabled", matchIfMissing = true)
@EnableConfigurationProperties(LocalS3Properties.class)
public class LocalS3AutoConfiguration {

  /**
   * The key pair of the clients when LocalS3 accepts unsigned requests. Any pair is accepted; the clients sign with one
   * rather than send anonymous requests, which the AWS SDK doesn't sign or checksum like requests to Amazon S3.
   */
  static final String DEFAULT_CLIENT_KEY = "local-s3";

  @Bean
  @ConditionalOnMissingBean
  public LocalS3 localS3(LocalS3Properties properties, ObjectProvider<LocalS3ApplicationEventPublisher> eventPublisher,
                         ObjectProvider<LocalS3BuilderCustomizer> customizers,
                         ObjectProvider<RequestRecorder> requestRecorders) {
    LocalS3Builder builder = LocalS3.builder()
        // The application context stops the service; a hook of its own would stop it before the beans that use it.
        .registerShutdownHook(false);
    apply(properties, builder);
    LocalS3ApplicationEventPublisher events = eventPublisher.getIfAvailable();
    if (events != null) {
      // Published on the thread that made the change, so that a listener of a change made in a transaction takes part
      // in it, e.g. a @TransactionalEventListener of a put through getS3Manager() in a @Transactional method.
      builder.bucketEventListener(events::onBucketEvent).objectEventListener(events::onObjectEvent);
    }
    List<RequestRecorder> recorders = requestRecorders.orderedStream().toList();
    if (!recorders.isEmpty()) {
      builder.requestRecorder((request, operation, status, requestId, durationNanos) -> recorders.forEach(
          recorder -> recorder.record(request, operation, status, requestId, durationNanos)));
    }
    customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
    return builder.build();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBooleanProperty(name = "local-s3.events.enabled", matchIfMissing = true)
  public LocalS3ApplicationEventPublisher localS3ApplicationEventPublisher(ApplicationContext applicationContext) {
    return new LocalS3ApplicationEventPublisher(applicationContext);
  }

  @Bean
  @ConditionalOnMissingBean
  public LocalS3Lifecycle localS3Lifecycle(LocalS3 localS3) {
    return new LocalS3Lifecycle(localS3);
  }

  static void apply(LocalS3Properties properties, LocalS3Builder builder) {
    PropertyMapper map = PropertyMapper.get();
    map.from(properties::getBindHost).to(builder::bindHost);
    map.from(properties::getPort).to(builder::port);
    map.from(properties::getMode).to(builder::mode);
    map.from(properties::getDataPath).whenHasText().to(builder::dataPath);
    map.from(properties::getBuckets).to(buckets -> builder.buckets(buckets.toArray(String[]::new)));
    map.from(properties::isInitialDataCacheEnabled).to(builder::initialDataCacheEnabled);
    map.from(properties::isStrictBucketNames).to(builder::strictBucketNames);
    map.from(properties::isStrictPartSizes).to(builder::strictPartSizes);
    map.from(properties::isCompositeMultipartEtags).to(builder::compositeMultipartEtags);
    map.from(properties::getVirtualHostDomains)
        .to(domains -> builder.virtualHostDomains(domains.toArray(String[]::new)));

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
    builder.virtualThreads(threads.isVirtual())
        .daemonThreads(threads.isDaemon())
        .nettyParentEventGroupThreadNum(threads.getNettyParentEventGroup())
        .nettyChildEventGroupThreadNum(threads.getNettyChildEventGroup())
        .s3ExecutorThreadNum(threads.getExecutor());

    LocalS3Properties.Requests requests = properties.getRequests();
    map.from(requests::getMaxBodySize).as(DataSize::toBytes).to(builder::maxRequestBodySize);
    map.from(requests::getBodyFileThreshold).as(DataSize::toBytes).to(builder::requestBodyFileThreshold);
    map.from(requests::getMaxHeaderSize).as(size -> Math.toIntExact(size.toBytes())).to(builder::maxRequestHeaderSize);
    map.from(requests::getIdleConnectionTimeout).as(Duration::toSeconds)
        .to(builder::idleConnectionTimeoutSeconds);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * The clients of the AWS SDK that point at the embedded service, with path-style requests, and signed with the
   * credentials of the service, if it requires any. Creating a client starts the service.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBooleanProperty(name = "local-s3.clients.enabled", matchIfMissing = true)
  static class ClientsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    S3Client s3Client(LocalS3Lifecycle lifecycle, LocalS3Properties properties) {
      return S3Client.builder()
          .endpointOverride(lifecycle.endpoint())
          .region(Region.of(properties.getClients().getRegion()))
          .credentialsProvider(credentials(lifecycle.getLocalS3()))
          .forcePathStyle(true)
          .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient")
    S3AsyncClient s3AsyncClient(LocalS3Lifecycle lifecycle, LocalS3Properties properties) {
      return S3AsyncClient.builder()
          .endpointOverride(lifecycle.endpoint())
          .region(Region.of(properties.getClients().getRegion()))
          .credentialsProvider(credentials(lifecycle.getLocalS3()))
          .forcePathStyle(true)
          .build();
    }

    @Bean
    @ConditionalOnMissingBean
    S3Presigner s3Presigner(LocalS3Lifecycle lifecycle, LocalS3Properties properties) {
      return S3Presigner.builder()
          .endpointOverride(lifecycle.endpoint())
          .region(Region.of(properties.getClients().getRegion()))
          .credentialsProvider(credentials(lifecycle.getLocalS3()))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
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

  }

}
