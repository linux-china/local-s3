package com.robothy.s3.docker;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs LocalS3 in a container, configured by environment variables, or by system properties of the same names.
 */
@Slf4j
public class App {

    static final String LOCAL_S3_PORT = "LOCAL_S3_PORT";
    static final String LOCAL_S3_MODE = "LOCAL_S3_MODE";
    static final String LOCAL_S3_DATA_PATH = "LOCAL_S3_DATA_PATH";
    static final String LOCAL_S3_STRICT_BUCKET_NAMES = "LOCAL_S3_STRICT_BUCKET_NAMES";
    static final String LOCAL_S3_VIRTUAL_HOST_DOMAINS = "LOCAL_S3_VIRTUAL_HOST_DOMAINS";
    static final String AWS_BUCKETS = "AWS_BUCKETS";
    static final String AWS_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";
    static final String AWS_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";

    static final int DEFAULT_PORT = 29090;
    static final String DEFAULT_DATA_PATH = "/data";

    public static void main(String[] args) {
        configure().build().start();
    }

    /**
     * Configure LocalS3 from the environment variables.
     *
     * @return a builder of the configured LocalS3 service.
     * @throws IllegalArgumentException if a variable has an invalid value.
     */
    static LocalS3.Builder configure() {
        LocalS3Mode mode = mode();
        int port = port();
        String dataPath = Optional.ofNullable(getProperty(LOCAL_S3_DATA_PATH)).orElse(DEFAULT_DATA_PATH);
        log.info("Starting LocalS3 in {} mode on port {} with data path {}.", mode, port, dataPath);

        LocalS3.Builder localS3Builder = LocalS3.builder()
                .port(port)
                .bindHost("0.0.0.0")
                .mode(mode)
                .dataPath(dataPath)
                // LOCAL_S3_STRICT_BUCKET_NAMES=true rejects bucket names that Amazon S3 doesn't accept.
                .strictBucketNames(Boolean.parseBoolean(getProperty(LOCAL_S3_STRICT_BUCKET_NAMES)));
        // e.g. LOCAL_S3_VIRTUAL_HOST_DOMAINS=s3,s3.local for virtual-hosted-style requests to my-bucket.s3.
        if (getProperty(LOCAL_S3_VIRTUAL_HOST_DOMAINS) != null) {
            localS3Builder.virtualHostDomains(getProperty(LOCAL_S3_VIRTUAL_HOST_DOMAINS).split(","));
        }
        if (getProperty(AWS_BUCKETS) != null) {
            localS3Builder.buckets(getProperty(AWS_BUCKETS).split(","));
        }
        String accessKeyId = getProperty(AWS_ACCESS_KEY_ID);
        String secretAccessKey = getProperty(AWS_SECRET_ACCESS_KEY);
        if ((accessKeyId == null) != (secretAccessKey == null)) {
            throw new IllegalArgumentException("AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY must be configured together.");
        }
        if (accessKeyId != null) {
            localS3Builder.credentials(accessKeyId, secretAccessKey);
        }
        return localS3Builder;
    }

    static LocalS3Mode mode() {
        String mode = getProperty(LOCAL_S3_MODE);
        if (mode == null) {
            log.info("\"{}\" is not specified; use the default value \"{}\".", LOCAL_S3_MODE, LocalS3Mode.PERSISTENCE);
            return LocalS3Mode.PERSISTENCE;
        }
        if (!LocalS3Mode.isLegalName(mode)) {
            throw new IllegalArgumentException("\"" + mode + "\" is not a valid mode. Valid values are "
                    + Arrays.toString(LocalS3Mode.values()) + ".");
        }
        return LocalS3Mode.valueOf(mode.toUpperCase(Locale.ROOT));
    }

    static int port() {
        String port = getProperty(LOCAL_S3_PORT);
        if (port == null) {
            return DEFAULT_PORT;
        }
        try {
            int value = Integer.parseInt(port.trim());
            if (value >= 1 && value <= 65535) {
                return value;
            }
        } catch (NumberFormatException e) {
            // Rejected below.
        }
        throw new IllegalArgumentException("\"" + port + "\" is not a valid " + LOCAL_S3_PORT + "; use 1 to 65535.");
    }

    private static String getProperty(String name) {
        return Optional.ofNullable(System.getenv(name)).orElse(System.getProperty(name));
    }

}
