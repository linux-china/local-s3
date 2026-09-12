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

  private static final List<String> VARIABLES = List.of(App.LOCAL_S3_PORT, App.LOCAL_S3_MODE,
      App.LOCAL_S3_DATA_PATH, App.LOCAL_S3_STRICT_BUCKET_NAMES, App.LOCAL_S3_VIRTUAL_HOST_DOMAINS);

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
    assertEquals(LocalS3Mode.PERSISTENCE, localS3.getMode());
    // main() returns once the service is started, so daemon threads would let the container exit at once.
    assertFalse(localS3.isDaemonThreads(), "The threads of the service keep the container running.");
  }

  @Test
  void readsLocalS3Variables() {
    System.setProperty(App.LOCAL_S3_PORT, "29500");
    System.setProperty(App.LOCAL_S3_MODE, "in_memory");
    System.setProperty(App.LOCAL_S3_DATA_PATH, "/var/lib/local-s3");
    System.setProperty(App.LOCAL_S3_STRICT_BUCKET_NAMES, "true");
    System.setProperty(App.LOCAL_S3_VIRTUAL_HOST_DOMAINS, "s3, s3.local");

    LocalS3 localS3 = App.configure().build();

    assertEquals(29500, localS3.getPort());
    assertEquals(Path.of("/var/lib/local-s3"), localS3.getDataPath());
    assertTrue(localS3.isStrictBucketNames());
    assertEquals(List.of("s3", "s3.local"), localS3.getVirtualHostDomains());
    // A data path is the initial data of an IN_MEMORY service, so it must not switch the mode back.
    assertEquals(LocalS3Mode.IN_MEMORY, localS3.getMode());
  }

  @Test
  void rejectsInvalidValues() {
    System.setProperty(App.LOCAL_S3_MODE, "CLOUD");
    assertThrows(IllegalArgumentException.class, App::configure);
    System.clearProperty(App.LOCAL_S3_MODE);

    for (String port : List.of("0", "65536", "http")) {
      System.setProperty(App.LOCAL_S3_PORT, port);
      assertThrows(IllegalArgumentException.class, App::configure, port);
    }
  }

}
