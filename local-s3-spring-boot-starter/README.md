# local-s3-spring-boot-starter

`local-s3-spring-boot-starter` embeds LocalS3 in a Spring Boot 4 application.

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-spring-boot-starter</artifactId>
    <version>last_version</version>
</dependency>
```

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
+ an `S3Client`, an `S3AsyncClient` and an `S3Presigner` that point at the service, with path-style requests and the
  credentials of the service. Creating one starts the service, so a bean can use it while it is initialized, even with a
  random port. The starter backs off from a client that the application defines itself, and from all of them with
  `local-s3.clients.enabled=false`;
+ the `BucketEvent`s and `ObjectEvent`s of the service as application events. They are published on the thread that
  made the change, so a change that the application makes through `localS3.getS3Manager()` in a transaction reaches a
  `@TransactionalEventListener` once the transaction commits. Events of changes made while the context is refreshed,
  e.g. of the default buckets, are published once it is refreshed;
+ with Actuator, a `localS3` health indicator (`management.health.local-s3.enabled`), which checks `/_health` and
  reports the endpoint and the amount of data;
+ with Micrometer, the timer `local.s3.requests` (tagged with `operation`, `status` and `outcome`) and gauges of the data
  that `/_admin/stats` reports, e.g. `local.s3.objects`, `local.s3.objects.size` and `local.s3.requests.active`.

```java
@Component
class UploadIndexer {

  @EventListener
  void onObjectCreated(ObjectEvent event) {
    if (event.getEventType() == S3EventType.OBJECT_CREATED) {
      index(event.getObjectUrl());
    }
  }
}
```

Set `local-s3.enabled=false` to leave LocalS3 out, e.g. in the profile that runs against Amazon S3.
