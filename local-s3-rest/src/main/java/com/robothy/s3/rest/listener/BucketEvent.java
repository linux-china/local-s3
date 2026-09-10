package com.robothy.s3.rest.listener;

import org.jspecify.annotations.NonNull;

import java.util.Map;

public class BucketEvent extends S3Event {
    private final String bucketName;
    private final String bucketRegion;

    public BucketEvent(@NonNull S3EventType eventType, String source, @NonNull String bucketName, String bucketRegion) {
        super(eventType, source);
        this.bucketName = bucketName;
        this.bucketRegion = bucketRegion == null ? "local" : bucketRegion;
    }

    public BucketEvent(@NonNull S3EventType eventType, String source, @NonNull String bucketName,
                       String bucketRegion, Map<String, String> metadata) {
        super(eventType, source, metadata);
        this.bucketName = bucketName;
        this.bucketRegion = bucketRegion == null ? "local" : bucketRegion;
        ;
    }

    public String getBucketName() {
        return bucketName;
    }

    public String getBucketRegion() {
        return bucketRegion;
    }

}