# local-s3-spring-boot-starter

`local-s3-spring-boot-starter` embeds LocalS3 in a Spring Boot 4 application.

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
declaration, or LocalS3 fails to start with a `NoClassDefFoundError`.

```yaml
local-s3:
  port: 29090            # 0 for a random port
  buckets: [uploads, reports]
  mode: in-memory        # or persistence, with data-path
  # credentials:
  #   access-key-id: ...
  #   secret-access-key: ...
```

The starter defines:

+ a `LocalS3` bean, configured by the `local-s3.*` properties, which map to the options of `LocalS3Builder` (the IDE
  completes them), and by the `LocalS3BuilderCustomizer` beans for anything else. `LocalS3Lifecycle` starts and stops it
  with the application context, in a phase before the web server, and without a JVM shutdown hook of its own;
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

Set `local-s3.enabled=false` to leave LocalS3 out, e.g. in the profile that runs against Amazon S3.
