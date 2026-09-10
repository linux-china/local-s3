package com.robothy.s3.docker;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class App {

    private static final String LOCAL_S3_MODE = "MODE";
    private static final String AWS_BUCKETS = "AWS_BUCKETS";
    private static final String AWS_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";
    private static final String AWS_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";

    public static void main(String[] args) {
        String localS3Mode = getProperty(LOCAL_S3_MODE);
        if (localS3Mode == null) {
            log.info("\"MODE\" is not specified; use the default value \"PERSISTENCE\"");
        }
        localS3Mode = Optional.ofNullable(localS3Mode).orElse(LocalS3Mode.PERSISTENCE.name());
        if (!LocalS3Mode.isLegalName(localS3Mode)) {
            log.error("\"{}\" is not a valid mode. Valid values are {}", localS3Mode, LocalS3Mode.values());
            System.exit(1);
        }

        log.info("Starting LocalS3 in {} mode.", localS3Mode);

        LocalS3.Builder localS3Builder = LocalS3.builder()
                .port(80)
                .mode(LocalS3Mode.valueOf(localS3Mode.toUpperCase()))
                .dataPath("/data");
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
        localS3Builder.build().start();
    }

    private static String getProperty(String name) {
        return Optional.ofNullable(System.getenv(name)).orElse(System.getProperty(name));
    }

}
