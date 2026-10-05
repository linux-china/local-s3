package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.datatypes.response.S3Object;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LifecycleScheduleTest {

  private static final String BUCKET = "scratch";

  /**
   * Expires every object under {@code tmp/}: a date in the past has passed whenever the rule is applied.
   */
  private static final String EXPIRE_TMP = """
      <LifecycleConfiguration>
        <Rule>
          <ID>expire-tmp</ID>
          <Filter><Prefix>tmp/</Prefix></Filter>
          <Status>Enabled</Status>
          <Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>
        </Rule>
      </LifecycleConfiguration>""";

  @Test
  void aServiceAppliesNoLifecycleConfigurationByDefault() {
    assertNull(LocalS3.builder().buildConfig().lifecycleInterval());
    assertNull(LocalS3.builder().lifecycle(lifecycle -> lifecycle.applyEvery(Duration.ZERO)).buildConfig()
        .lifecycleInterval(), "Zero turns the schedule off.");
    assertThrows(IllegalArgumentException.class,
        () -> LocalS3.builder().lifecycle(lifecycle -> lifecycle.applyEvery(Duration.ofSeconds(-1))));
  }

  @Test
  void aScheduledServiceExpiresWhatTheRulesSelect() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(0)
        .lifecycle(lifecycle -> lifecycle.applyEvery(Duration.ofMillis(50)))
        .build();
    localS3.start();
    try {
      localS3.getS3Manager().bucketService().createBucket(BUCKET);
      ObjectService objects = localS3.getS3Manager().objectService();
      put(objects, "tmp/a.txt");
      put(objects, "keep/b.txt");
      localS3.getS3Manager().bucketService().putBucketLifecycleConfiguration(BUCKET, EXPIRE_TMP, null);

      for (int attempt = 0; attempt < 100 && keys(objects).contains("tmp/a.txt"); attempt++) {
        Thread.sleep(50);
      }
      assertEquals(List.of("keep/b.txt"), keys(objects));

      // A later object expires at a later run.
      put(objects, "tmp/c.txt");
      for (int attempt = 0; attempt < 100 && keys(objects).contains("tmp/c.txt"); attempt++) {
        Thread.sleep(50);
      }
      assertEquals(List.of("keep/b.txt"), keys(objects));
    } finally {
      localS3.shutdown();
    }
    assertFalse(lifecycleThreadAlive(), "Stopping the service stops its schedule.");
  }

  @Test
  void theIntervalIsReadFromTheEnvironment() {
    assertEquals(Duration.ofMinutes(30), LocalS3.builder()
        .fromEnvironment(Map.of(LocalS3Environment.LOCAL_S3_LIFECYCLE_INTERVAL, "30m")::get)
        .buildConfig().lifecycleInterval());
    assertNull(LocalS3.builder()
        .fromEnvironment(Map.of(LocalS3Environment.LOCAL_S3_LIFECYCLE_INTERVAL, "0")::get)
        .buildConfig().lifecycleInterval());
  }

  @ParameterizedTest
  @ValueSource(strings = {"90s", "15m", "1h", "2d", "PT1H30M", " 1H "})
  void parsesAnInterval(String interval) {
    assertTrue(LocalS3Environment.parseLifecycleInterval(interval).isPositive(), interval);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "h", "1", "1w", "-1h", "PT-1H", "one hour"})
  void rejectsAnInvalidInterval(String interval) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> LocalS3Environment.parseLifecycleInterval(interval), interval);
    assertTrue(e.getMessage().contains(LocalS3Environment.LOCAL_S3_LIFECYCLE_INTERVAL), e.getMessage());
  }

  private static void put(ObjectService objects, String key) {
    byte[] content = key.getBytes(StandardCharsets.UTF_8);
    objects.putObject(BUCKET, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(content))
        .size(content.length)
        .build());
  }

  private static List<String> keys(ObjectService objects) {
    return objects.listObjectsV2(BUCKET, null, null, null, false, 1000, null, null).getObjects().stream()
        .map(S3Object::getKey)
        .toList();
  }

  private static boolean lifecycleThreadAlive() {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> thread.getName().equals("locals3-lifecycle") && thread.isAlive());
  }

}
