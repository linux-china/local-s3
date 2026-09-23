# local-s3-spring-boot-starter

`local-s3-spring-boot-starter` embeds LocalS3 in a Spring Boot application. One artifact serves **Spring Boot 3 and
Spring Boot 4**: see [Spring Boot 3](#spring-boot-3) for what an application on Spring Boot 3 has to set.

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-spring-boot-starter</artifactId>
    <version>last_version</version>
</dependency>
```

The starter only brings LocalS3 itself: an application that embeds the service for other processes, e.g. DuckDB or a
Spark job, doesn't get the AWS SDK. To have the starter define the clients that point at the service, add the AWS SDK
too:

```xml
<!-- S3Client and S3Presigner -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
</dependency>
<!-- S3AsyncClient, optional -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>netty-nio-client</artifactId>
</dependency>
```

The versions come from the AWS SDK BOM (`software.amazon.awssdk:bom`), or set them explicitly. `netty-nio-client`
depends on Netty 4.1, while LocalS3 needs Netty 4.2: let the dependency management of Spring Boot (the
`spring-boot-starter-parent` or the `spring-boot-dependencies` BOM) pick the Netty version, rather than the nearest
declaration, or LocalS3 fails to start with a `NoClassDefFoundError`. Spring Boot 4 manages Netty 4.2; on Spring Boot 3,
override it as below.

## Spring Boot 3

The starter is compiled against Spring Boot 4 and runs on Spring Boot 3.x as well. The auto-configurations only use the
annotations that both lines have in the same packages, and the integrations that Spring Boot 4 moved to modules of its
own ship with an adapter per layout, of which only the one whose API is on the classpath is ever loaded:

| Integration                         | Spring Boot 3                     | Spring Boot 4            |
|-------------------------------------|-----------------------------------|--------------------------|
| Actuator health (`localS3`)         | `spring-boot-actuator`            | `spring-boot-health`     |
| `@AutoConfigureLocalS3` properties  | `spring-boot-test-autoconfigure`  | `spring-boot-test`       |

Spring Boot 3 pins Netty to 4.1, which LocalS3 does not run on, so an application on Spring Boot 3 has to raise it to
4.2. With Maven, in the properties of the POM that inherits `spring-boot-starter-parent`:

```xml
<properties>
    <netty.version>4.2.18.Final</netty.version>
</properties>
```

With Gradle and the Spring Boot plugin:

```groovy
ext['netty.version'] = '4.2.18.Final'
```

The POM of the starter names `spring-boot-autoconfigure` at the Spring Boot 4 version, as the version it was built
against. The dependency management of the application — `spring-boot-starter-parent`, the `spring-boot-dependencies`
BOM or the Gradle plugin — pins it back to the version of the application, as it does for every other Spring module;
keep it in place rather than resolving the starter on its own.

Nothing else differs: `local-s3.*`, the beans, the events, the seeding and `@AutoConfigureLocalS3` behave the same. The
one exception is that `@AutoConfigureLocalS3(reset = ...)` also shows up as a `local-s3.reset` property on Spring Boot 3,
which the binding of `LocalS3Properties` ignores.

```yaml
local-s3:
  port: 29090            # 0 for a random port
  buckets: [uploads, reports]
  mode: in-memory        # or persistence, with data-path
  persistence-policy: fast   # persistence mode; durable commits every change
  seed:
    classpath: s3-fixtures   # the objects the service starts with
  # credentials:
  #   access-key-id: ...
  #   secret-access-key: ...
```

The starter defines:

+ a `LocalS3` bean, configured by the `local-s3.*` properties, which map to the options of `LocalS3Builder` (the IDE
  completes them), and by the `LocalS3BuilderCustomizer` beans for anything else. `LocalS3Lifecycle` starts and stops it
  with the application context, in a phase before the web server, and without a JVM shutdown hook of its own;
+ the buckets and objects of `local-s3.seed.classpath`, and of the `LocalS3Seeder` beans of the application, put into
  the service before it accepts the first request, and again after every reset. See
  [Initial data](#initial-data);
+ with the AWS SDK, an `S3Client`, an `S3AsyncClient` (with `netty-nio-client`) and an `S3Presigner` that point at the
  service, with path-style requests and the
  credentials of the service. Creating one starts the service, so a bean can use it while it is initialized, even with a
  random port. The starter backs off from a client that the application defines itself, and from all of them with
  `local-s3.clients.enabled=false`;
+ the `S3Change`s that the service commits as application events. They are published on the thread that
  made the change, so a change that the application makes through `localS3.getS3Manager()` in a transaction reaches a
  `@TransactionalEventListener` once the transaction commits. Changes made while the context is refreshed,
  e.g. of the default buckets, are published once it is refreshed;
+ with Actuator, a `localS3` health indicator (`management.health.local-s3.enabled`), which checks `/_health` and
  reports the endpoint and the amount of data;
+ with Micrometer, the timer `local.s3.requests` (tagged with `operation`, `status` and `outcome`) and gauges of the data
  that `/_admin/stats` reports, e.g. `local.s3.objects`, `local.s3.objects.size` and `local.s3.requests.active`.

```java
@Component
class UploadIndexer {

  @EventListener
  void onObjectCreated(S3Change change) {
    if (change.type() == S3ChangeType.OBJECT_CREATED) {
      index("s3://" + change.bucketName() + "/" + change.key());
    }
  }
}
```

## Initial data

`local-s3.buckets` creates empty buckets. To have the service start with objects in them, put a directory tree on the
classpath, e.g. in `src/test/resources`, and name it with `local-s3.seed.classpath`. The first segment of each path is
the bucket, the rest is the key:

```
src/test/resources/s3-fixtures/
├── README.md                 # names no bucket, so it is skipped
├── reports/
│   └── 2026/q1.csv           # s3://reports/2026/q1.csv
└── uploads/
    ├── hello.txt             # s3://uploads/hello.txt
    └── nested/deep.json      # s3://uploads/nested/deep.json
```

```yaml
local-s3:
  seed:
    classpath: s3-fixtures
```

+ the bucket of a fixture is created, so it doesn't have to be in `local-s3.buckets` as well;
+ the tree is seeded when the service starts, before it accepts the first request, and again after every reset, so each
  test method of a class that shares one service finds the same fixtures. An object replaces the one that its key
  already holds, e.g. the one that a `PERSISTENCE` service loaded from its data path;
+ the content type is guessed from the key, e.g. `text/plain` for `hello.txt`;
+ the location is resolved with `classpath*:`, so the fixtures of a jar on the classpath, e.g. a module of fixtures
  shared by several applications, are seeded too;
+ `local-s3.seed.enabled=false` keeps the location, e.g. the one that another profile sets, without seeding it.

Objects that no file describes well, e.g. a large generated one, come from a `LocalS3Seeder` bean, which is applied the
same way — on start, and after every reset:

```java
@Bean
LocalS3Seeder reportSeeder() {
  return fixtures -> {
    fixtures.bucket("archive");
    fixtures.object("reports", "2026/q1.csv", report(2026, 1));
    fixtures.object("reports", "2026/q2.csv", Path.of("build/reports/q2.csv"));
  };
}
```

## Static website hosting

A public bucket is served as a [static website](../docs/semantics.md#static-website-hosting) on the port of the S3
API, so a page put by a test, or seeded from the classpath, opens in a browser without a second server. Only requests
that carry no credentials are served that way; the `S3Client` of the starter, whose requests are signed, keeps its S3
semantics.

```yaml
local-s3:
  website:
    enabled: true          # the default
    all-buckets: false     # true serves every bucket, not the public ones alone
    index-document: index.html
    error-document: error.html
```

A bucket is public once its ACL grants the `AllUsers` group `READ`, e.g. with the `public-read` canned ACL, or its
bucket policy allows `s3:GetObject` to every principal. `all-buckets: true` serves every bucket without publishing it,
which is handy while developing a page locally and lets an unsigned request read any object of the service.

## Startup order

`LocalS3Lifecycle` starts the service in a phase before the web server, and creating one of the client beans starts it
too, because the endpoint of a client names the port the service listens on, which a random `local-s3.port` only
settles once it is started. So a bean that uses an injected `S3Client` while it is initialized, e.g. in a
`@PostConstruct` method or a constructor, finds the service running:

```java
@Component
class ReportBucket {

  private final S3Client s3;

  ReportBucket(S3Client s3) {
    this.s3 = s3;
  }

  @PostConstruct
  void prepare() {
    s3.headBucket(request -> request.bucket("reports")); // the service is already listening
  }
}
```

A bean that uses the service without a client bean of the starter, e.g. through its own client or over the endpoint of
`LocalS3Lifecycle`, injects `LocalS3Lifecycle` rather than `LocalS3`: `lifecycle.endpoint()` starts the service if the
lifecycle of the context hasn't yet, while `localS3.getPort()` on its own is the configured port, i.e. `0` for a
random one, until the service starts.

## LocalS3 locally, Amazon S3 in production

`local-s3.enabled=false` leaves the service out — and with it the `S3Client`, `S3AsyncClient` and `S3Presigner` beans
of the starter, so the profile that switches it off defines the client of Amazon S3 instead. Otherwise the beans that
use one fail with a `NoSuchBeanDefinitionException`. Make `local-s3.enabled` the switch of both configurations, so
exactly one of them defines the clients, whatever the profile:

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "local-s3.enabled", havingValue = "false")
class AmazonS3Configuration {

  @Bean
  S3Client s3Client(@Value("${app.s3.region}") String region) {
    return S3Client.builder().region(Region.of(region)).build(); // the default credentials provider chain
  }
}
```

The condition has no `matchIfMissing`, on purpose: `local-s3.enabled` is unset in a test that only adds the starter,
which then embeds a service and defines the clients that point at it, as it does by default.

`application.yml`, the production default, which the starter is switched off in:

```yaml
local-s3:
  enabled: false
app:
  s3:
    region: eu-central-1
```

`application-local.yml`, an embedded service with the fixtures of a local run:

```yaml
local-s3:
  enabled: true
  buckets: [uploads, reports]
  seed:
    classpath: s3-fixtures
```

An application that only ever embeds LocalS3 for the processes around it, e.g. DuckDB or a Spark job, needs none of
this: it doesn't use a client itself, and doesn't have the AWS SDK on the classpath, so the starter defines no client
beans to begin with.

## Tests

Annotate a test class with `@AutoConfigureLocalS3` (with `spring-boot-test` on the test classpath, e.g. through
`spring-boot-starter-test`):

```java
@SpringBootTest
@AutoConfigureLocalS3
class UploadServiceTest {

  @Autowired
  S3Client s3;

  @Test
  void uploads() {
    // ...
  }
}
```

+ the service listens on a random free port, whatever `local-s3.port` the application sets, so the contexts that the
  test context framework caches side by side don't compete for a port;
+ the data is kept in memory;
+ the data is reset after each test method with `LocalS3.reset()`: the buckets, objects and vectors are dropped, and the
  initial data and the `local-s3.buckets` are created again. Use `@AutoConfigureLocalS3(reset = false)` to keep the
  data across the tests of the class;
+ the auto-configuration of the starter is imported, so the annotation also works with test slices, e.g. `@DataJpaTest`.

`@AutoConfigureLocalS3(port = 29090, mode = LocalS3Mode.PERSISTENCE)` overrides the defaults; a `PERSISTENCE` service is
never reset.
