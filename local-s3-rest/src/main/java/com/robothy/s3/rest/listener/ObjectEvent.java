package com.robothy.s3.rest.listener;

import org.jspecify.annotations.NonNull;


public class ObjectEvent extends S3Event {
    private final String bucketName;
    private final String objectKey;

    public ObjectEvent(@NonNull S3EventType eventType, String source,
                       @NonNull String bucketName,
                       @NonNull String objectKey) {
        super(eventType, source);
        this.bucketName = bucketName;
        this.objectKey = objectKey;
    }

    @NonNull
    public String getBucketName() {
        return bucketName;
    }

    @NonNull
    public String getObjectKey() {
        return objectKey;
    }

    @NonNull
    public String getObjectUrl() {
        return "s3://" + bucketName + "/" + objectKey;
    }

}