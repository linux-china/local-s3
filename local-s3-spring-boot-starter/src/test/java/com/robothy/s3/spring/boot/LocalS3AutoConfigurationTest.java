package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class LocalS3AutoConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(LocalS3AutoConfiguration.class))
      .withPropertyValues("local-s3.port=-1");

  @Test
  void embedsAServiceWithClientsThatPointAtIt() {
    runner.withPropertyValues("local-s3.buckets=first,second").run(context -> {
      LocalS3 localS3 = context.getBean(LocalS3.class);
      assertTrue(localS3.isRunning());
      assertNotEquals(0, localS3.getPort(), "The random port is bound.");

      S3Client s3 = context.getBean(S3Client.class);
      assertEquals(List.of("first", "second"), s3.listBuckets().buckets().stream().map(bucket -> bucket.name()).toList());
      s3.putObject(request -> request.bucket("first").key("a.txt"), RequestBody.fromString("Hello"));
      assertEquals("Hello", s3.getObjectAsBytes(request -> request.bucket("first").key("a.txt")).asUtf8String());

      S3AsyncClient async = context.getBean(S3AsyncClient.class);
      async.putObject(request -> request.bucket("second").key("b.txt"), AsyncRequestBody.fromString("World")).join();
      assertEquals("World", async.getObject(request -> request.bucket("second").key("b.txt"),
          AsyncResponseTransformer.toBytes()).join().asUtf8String());
      assertEquals("Hello", presignedGet(context.getBean(S3Presigner.class), "first", "a.txt"));
    });
  }

  @Test
  void theApplicationContextStopsTheServiceWithoutAShutdownHook() {
    int[] port = new int[1];
    runner.run(context -> {
      LocalS3 localS3 = context.getBean(LocalS3.class);
      assertFalse(localS3.getConfig().registerShutdownHook());
      LocalS3Lifecycle lifecycle = context.getBean(LocalS3Lifecycle.class);
      assertEquals(LocalS3Lifecycle.PHASE, lifecycle.getPhase());
      assertTrue(lifecycle.isRunning());
      port[0] = localS3.getPort();
      try (Socket ignored = new Socket("127.0.0.1", port[0])) {
        // The service listens.
      }
    });
    assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port[0]).close(),
        "The service stops with the application context.");
  }

  @Test
  void mapsThePropertiesToTheBuilder(@TempDir Path dataPath) {
    runner.withPropertyValues(
        "local-s3.bind-host=0.0.0.0",
        "local-s3.mode=persistence",
        "local-s3.data-path=" + dataPath,
        "local-s3.buckets=bucket-a, bucket-b",
        "local-s3.initial-data-cache-enabled=false",
        "local-s3.in-memory.max-size=256MB",
        "local-s3.composite-multipart-etags=false",
        "local-s3.virtual-host-domains=s3.local,minio",
        "local-s3.credentials.access-key-id=spring-key",
        "local-s3.credentials.secret-access-key=spring-secret",
        "local-s3.threads.virtual=false",
        "local-s3.threads.daemon=false",
        "local-s3.threads.netty-parent-event-group=2",
        "local-s3.threads.netty-child-event-group=3",
        "local-s3.threads.executor=5",
        "local-s3.requests.max-body-size=64MB",
        "local-s3.requests.body-file-threshold=1MB",
        "local-s3.requests.max-header-size=32KB",
        "local-s3.requests.idle-connection-timeout=45s",
        "local-s3.events.enabled=false",
        "local-s3.clients.enabled=false"
    ).run(context -> {
      LocalS3Config config = context.getBean(LocalS3.class).getConfig();
      assertEquals("0.0.0.0", config.bindHost());
      assertEquals(0, config.port());
      assertEquals(LocalS3Mode.PERSISTENCE, config.mode());
      assertEquals(dataPath, config.dataPath());
      assertEquals(List.of("bucket-a", "bucket-b"), config.buckets());
      assertFalse(config.initialDataCacheEnabled());
      assertEquals(256L * 1024 * 1024, config.maxInMemoryBytes());
      assertFalse(config.compositeMultipartEtags());
      assertEquals(List.of("s3.local", "minio"), config.virtualHostDomains());
      assertEquals("spring-key", config.accessKeyId());
      assertEquals("spring-secret", config.secretAccessKey());
      assertFalse(config.virtualThreads());
      assertFalse(config.daemonThreads());
      assertEquals(2, config.nettyParentEventGroupThreadNum());
      assertEquals(3, config.nettyChildEventGroupThreadNum());
      assertEquals(5, config.s3ExecutorThreadNum());
      assertEquals(64L * 1024 * 1024, config.maxRequestBodySize());
      assertEquals(1024L * 1024, config.requestBodyFileThreshold());
      assertEquals(32 * 1024, config.maxRequestHeaderSize());
      assertEquals(45, config.idleConnectionTimeoutSeconds());
      assertEquals(List.of(), config.changeListeners(), "Events are disabled.");
      assertFalse(context.containsBean("s3Client"), "Clients are disabled.");
      assertTrue(context.getBean(LocalS3.class).isRunning(), "The lifecycle starts the service without clients.");
    });
  }

  @Test
  void theClientsSignWithTheCredentialsThatTheServiceRequires() {
    runner.withPropertyValues("local-s3.buckets=signed",
        "local-s3.credentials.access-key-id=spring-key",
        "local-s3.credentials.secret-access-key=spring-secret").run(context -> {
      S3Client s3 = context.getBean(S3Client.class);
      s3.putObject(request -> request.bucket("signed").key("a.txt"), RequestBody.fromString("Signed"));
      assertEquals("Signed", presignedGet(context.getBean(S3Presigner.class), "signed", "a.txt"));

      HttpResponse<String> unsigned = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create(context.getBean(LocalS3Lifecycle.class).endpoint() + "/signed/a.txt")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(403, unsigned.statusCode(), "The service requires the credentials.");
    });
  }

  @Test
  void theCredentialsMustBeSetTogether() {
    runner.withPropertyValues("local-s3.credentials.access-key-id=spring-key").run(context -> {
      Throwable failure = context.getStartupFailure();
      assertNotNull(failure);
      while (failure.getCause() != null && !(failure instanceof IllegalArgumentException)) {
        failure = failure.getCause();
      }
      assertInstanceOf(IllegalArgumentException.class, failure);
      assertTrue(failure.getMessage().contains("must be set together"), failure.getMessage());
    });
  }

  @Test
  void isDisabledByProperty() {
    runner.withPropertyValues("local-s3.enabled=false").run(context -> {
      assertFalse(context.containsBean("localS3"));
      assertTrue(context.getBeansOfType(S3Client.class).isEmpty());
    });
  }

  /**
   * The profile switch that the README documents: {@code local-s3.enabled} decides which configuration defines the
   * clients, so that switching the embedded service off doesn't leave the beans that use a client without one.
   */
  @Test
  void aConfigurationConditionalOnTheServiceBeingDisabledDefinesTheClientsInstead() {
    ApplicationContextRunner withAmazonS3 = runner.withUserConfiguration(AmazonS3Configuration.class);

    withAmazonS3.withPropertyValues("local-s3.enabled=false").run(context -> {
      assertFalse(context.containsBean("localS3"));
      assertSame(context.getBean("amazonS3Client"), context.getBean(S3Client.class),
          "The client of Amazon S3 is the only one, so the application always has one to inject.");
    });
    withAmazonS3.run(context -> {
      assertTrue(context.getBean(LocalS3.class).isRunning());
      assertFalse(context.containsBean("amazonS3Client"),
          "With the service enabled, which it is by default, the clients of the starter point at it.");
      assertEquals(URI.create("http://127.0.0.1:" + context.getBean(LocalS3.class).getPort()),
          context.getBean(LocalS3Lifecycle.class).endpoint());
    });
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "local-s3.enabled", havingValue = "false")
  static class AmazonS3Configuration {

    @Bean
    S3Client amazonS3Client() {
      // Built without a call, so the test needs no credentials of Amazon S3.
      return S3Client.builder().region(Region.EU_CENTRAL_1)
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
          .build();
    }

  }

  /**
   * The AWS SDK is an optional dependency: an application that only embeds the service, for other processes, gets it
   * without the clients.
   */
  @Test
  void embedsTheServiceWithoutTheAwsSdk() {
    runner.withClassLoader(new FilteredClassLoader("software.amazon.awssdk.")).run(context -> {
      assertNull(context.getStartupFailure());
      assertTrue(context.getBean(LocalS3.class).isRunning());
      assertFalse(context.containsBean("s3Client"));
      assertFalse(context.containsBean("s3AsyncClient"));
      assertFalse(context.containsBean("s3Presigner"));
    });
  }

  @Test
  void backsOffFromTheClientsOfTheApplication() {
    runner.withUserConfiguration(ApplicationClients.class).run(context -> {
      assertSame(ApplicationClients.CLIENT, context.getBean(S3Client.class));
      assertEquals(1, context.getBeansOfType(S3AsyncClient.class).size(), "The other clients are still defined.");
    });
  }

  @Test
  void appliesTheCustomizersAfterTheProperties() {
    runner.withPropertyValues("local-s3.buckets=from-properties").withUserConfiguration(Customizers.class)
        .run(context -> {
          LocalS3Config config = context.getBean(LocalS3.class).getConfig();
          assertEquals(List.of("from-properties", "from-customizer"), config.buckets());
          assertSame(Customizers.EXECUTOR, config.changeListenerExecutor());
        });
  }

  /**
   * A bean that uses S3 while it is initialized, e.g. in afterPropertiesSet() or a @PostConstruct method, finds the service running, although the lifecycle of the application
   * context only starts once every bean is initialized.
   */
  @Test
  void aBeanThatUsesS3WhileItIsInitializedFindsTheServiceRunning() {
    runner.withPropertyValues("local-s3.buckets=init").withUserConfiguration(InitializingUser.class).run(context -> {
      assertEquals(null, context.getStartupFailure());
      assertEquals("uploaded while initializing", context.getBean(S3Client.class)
          .getObjectAsBytes(request -> request.bucket("init").key("init.txt")).asUtf8String());
    });
  }

  private static String presignedGet(S3Presigner presigner, String bucket, String key) throws Exception {
    URI url = presigner.presignGetObject(request -> request.signatureDuration(Duration.ofMinutes(5))
        .getObjectRequest(get -> get.bucket(bucket).key(key))).url().toURI().normalize();
    HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(url).build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }

  @Configuration(proxyBeanMethods = false)
  static class ApplicationClients {

    static final S3Client CLIENT = S3Client.builder().region(Region.EU_WEST_1).build();

    @Bean
    S3Client applicationS3Client() {
      return CLIENT;
    }

  }

  @Configuration(proxyBeanMethods = false)
  static class Customizers {

    static final Executor EXECUTOR = Runnable::run;

    @Bean
    LocalS3BuilderCustomizer bucketsCustomizer() {
      return builder -> builder.buckets("from-customizer").changeListenerExecutor(EXECUTOR);
    }

  }

  @Configuration(proxyBeanMethods = false)
  static class InitializingUser {

    @Bean
    Uploader uploader(S3Client s3) {
      return new Uploader(s3);
    }

    static class Uploader implements InitializingBean {

      private final S3Client s3;

      Uploader(S3Client s3) {
        this.s3 = s3;
      }

      @Override
      public void afterPropertiesSet() {
        s3.putObject(request -> request.bucket("init").key("init.txt"), RequestBody.fromString("uploaded while initializing"));
      }

    }

  }

}
