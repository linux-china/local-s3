package com.robothy.s3.docker;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs LocalS3 in a container, configured by environment variables, or by system properties of the same names.
 */
@Slf4j
public class App {

    static final int DEFAULT_PORT = 29090;
    static final String DEFAULT_DATA_PATH = "/data";

    public static void main(String[] args) {
        LocalS3 localS3 = configure().build();
        log.info("Starting LocalS3 in {} mode on port {} with data path {}.",
                localS3.getMode(), localS3.getPort(), localS3.getDataPath());
        localS3.start();
    }

    /**
     * Configure LocalS3 with the defaults of this image, which the environment variables override.
     *
     * @return a builder of the configured LocalS3 service.
     * @throws IllegalArgumentException if a variable has an invalid value.
     */
    static LocalS3.Builder configure() {
        return LocalS3.builder()
                // The defaults of the container, which differ from the defaults of an embedded service: it
                // serves every interface and persists to a directory that is usually bind-mounted.
                .port(DEFAULT_PORT)
                .bindHost("0.0.0.0")
                .dataPath(DEFAULT_DATA_PATH)
                .mode(LocalS3Mode.PERSISTENCE)
                // Applies the variables that are set, leaving the defaults above for the ones that aren't.
                .fromEnvironment()
                // main() returns once the service is started, so only non-daemon threads keep the container
                // running. Embedded services use daemon threads, which don't outlive the tests that forget
                // to shut them down.
                .daemonThreads(false);
    }

}
