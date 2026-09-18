package com.robothy.s3.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Builder;
import com.robothy.s3.rest.LocalS3Environment;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            assertEquals("127.0.0.1", localS3.getBindHost());
            assertEquals(LocalS3Mode.IN_MEMORY, localS3.getMode());
            // main() returns once the service is started, so daemon threads would let the container exit at once.
            assertFalse(localS3.isDaemonThreads(), "The threads of the service keep the container running.");
        }
    }

    /**
     * An {@code IN_MEMORY} service reads a data path for its initial data only, so it is given the {@code /data}
     * of the container when that directory is there, e.g. the volume of the image, and none when it isn't, e.g.
     * the jar on a developer's machine, rather than naming a path it never reads.
     */
    @Test
    void appliesTheDataPathOfTheContainerToAnInMemoryServiceOnlyWhenItExists(@TempDir Path directory) {
        assertEquals(directory, dataPathOf(LocalS3Mode.IN_MEMORY, directory));
        assertNull(dataPathOf(LocalS3Mode.IN_MEMORY, directory.resolve("absent")));
    }

    /**
     * A {@code PERSISTENCE} service has nowhere else to keep its data, so it is given the data path either way.
     */
    @Test
    void appliesTheDataPathOfTheContainerToAPersistenceServiceEvenWhenItDoesNotExist(@TempDir Path directory) {
        Path absent = directory.resolve("absent");
        assertEquals(absent, dataPathOf(LocalS3Mode.PERSISTENCE, absent));
    }

    /**
     * A {@code LOCAL_S3_DATA_PATH} of its own is applied before the default, and keeps it away.
     */
    @Test
    void leavesADataPathOfItsOwnAlone(@TempDir Path directory) {
        Path chosen = directory.resolve("chosen");
        LocalS3Builder builder = LocalS3.builder().mode(LocalS3Mode.IN_MEMORY).dataPath(chosen.toString());

        App.applyDefaultDataPath(builder, directory);

        assertEquals(chosen, builder.buildConfig().dataPath());
    }

    /**
     * The data path that {@linkplain App#applyDefaultDataPath} gives a service of that mode, for a default data
     * path that the test controls rather than the {@code /data} of the machine the test runs on.
     */
    private static Path dataPathOf(LocalS3Mode mode, Path defaultDataPath) {
        LocalS3Builder builder = LocalS3.builder().mode(mode);
        App.applyDefaultDataPath(builder, defaultDataPath);
        return builder.buildConfig().dataPath();
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
