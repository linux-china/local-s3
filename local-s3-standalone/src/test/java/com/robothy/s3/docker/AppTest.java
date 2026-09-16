package com.robothy.s3.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Environment;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Configures {@linkplain App} with system properties, which it reads like environment variables.
 */
class AppTest {

    private static final List<String> VARIABLES = List.of(LocalS3Environment.LOCAL_S3_PORT, LocalS3Environment.LOCAL_S3_MODE,
            LocalS3Environment.LOCAL_S3_DATA_PATH, LocalS3Environment.LOCAL_S3_VIRTUAL_HOST_DOMAINS);

    @AfterEach
    void clearVariables() {
        VARIABLES.forEach(System::clearProperty);
    }

    @Test
    void usesDefaults() {
        try (LocalS3 localS3 = App.configure().build()) {

            assertEquals(App.DEFAULT_PORT, localS3.getPort());
            assertEquals(Path.of(App.DEFAULT_DATA_PATH), localS3.getDataPath());
            assertEquals("127.0.0.1", localS3.getBindHost());
            assertEquals(LocalS3Mode.IN_MEMORY, localS3.getMode());
            // main() returns once the service is started, so daemon threads would let the container exit at once.
            assertFalse(localS3.isDaemonThreads(), "The threads of the service keep the container running.");
        }
    }

    @Test
    void readsLocalS3Variables() {
        System.setProperty(LocalS3Environment.LOCAL_S3_PORT, "29500");
        System.setProperty(LocalS3Environment.LOCAL_S3_MODE, "in_memory");
        System.setProperty(LocalS3Environment.LOCAL_S3_DATA_PATH, "/var/lib/local-s3");
        System.setProperty(LocalS3Environment.LOCAL_S3_VIRTUAL_HOST_DOMAINS, "s3, s3.local");

        try (LocalS3 localS3 = App.configure().build()) {
            assertEquals(29500, localS3.getPort());
            assertEquals(Path.of("/var/lib/local-s3"), localS3.getDataPath());
            assertEquals(List.of("s3", "s3.local"), localS3.getVirtualHostDomains());
            // A data path is the initial data of an IN_MEMORY service, so it must not switch the mode back.
            assertEquals(LocalS3Mode.IN_MEMORY, localS3.getMode());
        }
    }

    @Test
    void rejectsInvalidValues() {
        System.setProperty(LocalS3Environment.LOCAL_S3_MODE, "CLOUD");
        assertThrows(IllegalArgumentException.class, App::configure);
        System.clearProperty(LocalS3Environment.LOCAL_S3_MODE);

        for (String port : List.of("0", "65536", "http")) {
            System.setProperty(LocalS3Environment.LOCAL_S3_PORT, port);
            assertThrows(IllegalArgumentException.class, App::configure, port);
        }
    }

}
