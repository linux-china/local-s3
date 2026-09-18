package com.robothy.s3.docker;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Builder;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs LocalS3 in a container, configured by environment variables, or by system properties of the same names.
 */
@Slf4j
public class App {

    static final int DEFAULT_PORT = 29090;
    static final String DEFAULT_HOST = "127.0.0.1";
    static final String DEFAULT_DATA_PATH = "/data";

    public static void main(String[] args) {
        LocalS3 localS3 = configure().build();
        LocalS3Config localS3Config = localS3.getConfig();
        List<String> hint = new ArrayList<>();
        hint.add("- Mode: " + localS3Config.mode());
        hint.add("- Port: " + localS3Config.port());
        if (localS3Config.dataPath() != null) {
            hint.add("- Data Path: " + localS3Config.dataPath());
        }
        if (localS3Config.authenticationEnabled()) {
            hint.add("- Authentication: Access Key(" + localS3Config.accessKeyId() + ")");
        }
        log.info("Starting LocalS3: {}", String.join("\n", hint));
        localS3.start();
    }

    /**
     * Configure LocalS3 with the defaults of this image, which the environment variables override.
     *
     * @return a builder of the configured LocalS3 service.
     * @throws IllegalArgumentException if a variable has an invalid value.
     */
    static LocalS3Builder configure() {
        LocalS3Builder builder = LocalS3.builder()
                // The defaults of the container, which differ from the defaults of an embedded service: it
                // serves every interface and persists to a directory that is usually bind-mounted.
                .port(DEFAULT_PORT)
                .bindHost(DEFAULT_HOST)
                .mode(LocalS3Mode.IN_MEMORY)
                // Applies the variables that are set, leaving the defaults above for the ones that aren't.
                .fromEnvironment()
                // main() returns once the service is started, so only non-daemon threads keep the container
                // running. Embedded services use daemon threads, which don't outlive the tests that forget
                // to shut them down.
                .daemonThreads(false);
        // After the environment, so that a LOCAL_S3_DATA_PATH of its own is left alone.
        applyDefaultDataPath(builder, Path.of(DEFAULT_DATA_PATH));
        return builder;
    }

    /**
     * Give a service that was configured with no data path the data path of the container, {@code /data},
     * which is the volume of the image.
     *
     * <p>A {@code PERSISTENCE} service always gets it: it has nowhere else to keep its data. An
     * {@code IN_MEMORY} service only ever reads a data path, for its
     * <a href="https://github.com/Robothy/local-s3/blob/main/docs/deployment.md">initial data</a>, so it gets
     * one only when the directory is there. The jar runs {@code IN_MEMORY} on a machine that usually has no
     * {@code /data}, and a service that names a data path it never reads reports it in its startup log, in
     * {@code GET /_admin/stats} and in the log of the vector storage, which reads as though a volume were
     * mounted. The image declares {@code VOLUME /data}, so a container has the directory either way.
     *
     * @param builder the builder to apply the data path to, configured from the environment already.
     * @param defaultDataPath the data path of the container.
     */
    static void applyDefaultDataPath(LocalS3Builder builder, Path defaultDataPath) {
        LocalS3Config configured = builder.buildConfig();
        if (configured.dataPath() != null) {
            return;
        }
        if (configured.mode() == LocalS3Mode.PERSISTENCE || Files.isDirectory(defaultDataPath)) {
            builder.dataPath(defaultDataPath.toString());
        }
    }

}
