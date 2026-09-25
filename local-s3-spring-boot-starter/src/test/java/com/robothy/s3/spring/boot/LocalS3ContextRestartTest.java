package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring Boot DevTools restarts an application by closing its context and creating a new one in the same JVM: the new
 * service binds the fixed port that the closed one listened on, and opens the data directory that it held.
 */
class LocalS3ContextRestartTest {

  @TempDir
  Path dataPath;

  @Test
  void aContextThatIsClosedIsCreatedAgainOnTheSamePortAndDataDirectory() throws IOException {
    int port = freePort();
    for (int restart = 0; restart < 3; restart++) {
      try (ConfigurableApplicationContext context = run(port)) {
        S3Client s3 = context.getBean(S3Client.class);
        if (restart > 0) {
          assertEquals("run " + (restart - 1),
              s3.getObjectAsBytes(request -> request.bucket("app").key("run.txt")).asUtf8String(),
              "The service of the new context serves the data that the closed one persisted.");
        }
        String content = "run " + restart;
        s3.putObject(request -> request.bucket("app").key("run.txt"), RequestBody.fromString(content));
      }
    }
    assertMetadataStoreReleased();
  }

  private ConfigurableApplicationContext run(int port) {
    return new SpringApplicationBuilder(Application.class)
        .web(WebApplicationType.NONE)
        .properties("local-s3.port=" + port, "local-s3.mode=PERSISTENCE", "local-s3.data-path=" + dataPath,
            "local-s3.buckets=app")
        .run();
  }

  /**
   * MVStore locks the file it holds open, so the lock is only available once every holder of the store in this JVM
   * released it; a hold that was left would throw an {@code OverlappingFileLockException}.
   */
  private void assertMetadataStoreReleased() throws IOException {
    try (FileChannel channel = FileChannel.open(dataPath.resolve("buckets.mvstore"), StandardOpenOption.READ,
        StandardOpenOption.WRITE);
         FileLock lock = channel.tryLock()) {
      assertNotNull(lock, "Closing the context releases the metadata store of the data directory.");
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @SpringBootApplication
  static class Application {
  }

}
