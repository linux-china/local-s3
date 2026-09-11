package com.robothy.s3.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Configures {@linkplain App} with system properties, which it reads like environment variables.
 */
class AppTest {

  private static final List<String> VARIABLES = List.of(App.LOCAL_S3_PORT, App.LOCAL_S3_MODE, App.LEGACY_MODE,
      App.LOCAL_S3_DATA_PATH, App.LOCAL_S3_STRICT_BUCKET_NAMES);

  @AfterEach
  void clearVariables() {
    VARIABLES.forEach(System::clearProperty);
  }

  @Test
  void usesDefaults() {
    LocalS3 localS3 = App.configure().build();

    assertEquals(App.DEFAULT_PORT, localS3.getPort());
    assertEquals(Path.of(App.DEFAULT_DATA_PATH), localS3.getDataPath());
    assertEquals("0.0.0.0", localS3.getBindHost());
    assertFalse(localS3.isStrictBucketNames());
    assertEquals(LocalS3Mode.PERSISTENCE, App.mode());
  }

  @Test
  void readsLocalS3Variables() {
    System.setProperty(App.LOCAL_S3_PORT, "29500");
    System.setProperty(App.LOCAL_S3_MODE, "in_memory");
    System.setProperty(App.LOCAL_S3_DATA_PATH, "/var/lib/local-s3");
    System.setProperty(App.LOCAL_S3_STRICT_BUCKET_NAMES, "true");

    LocalS3 localS3 = App.configure().build();

    assertEquals(29500, localS3.getPort());
    assertEquals(Path.of("/var/lib/local-s3"), localS3.getDataPath());
    assertTrue(localS3.isStrictBucketNames());
    assertEquals(LocalS3Mode.IN_MEMORY, App.mode());
  }

  @Test
  void fallsBackToTheLegacyModeVariable() {
    System.setProperty(App.LEGACY_MODE, "IN_MEMORY");
    assertEquals(LocalS3Mode.IN_MEMORY, App.mode());

    System.setProperty(App.LOCAL_S3_MODE, "PERSISTENCE");
    assertEquals(LocalS3Mode.PERSISTENCE, App.mode(), "LOCAL_S3_MODE takes precedence.");
  }

  @Test
  void rejectsInvalidValues() {
    System.setProperty(App.LOCAL_S3_MODE, "CLOUD");
    assertThrows(IllegalArgumentException.class, App::mode);

    for (String port : List.of("0", "65536", "http")) {
      System.setProperty(App.LOCAL_S3_PORT, port);
      assertThrows(IllegalArgumentException.class, App::port, port);
    }
  }

}
