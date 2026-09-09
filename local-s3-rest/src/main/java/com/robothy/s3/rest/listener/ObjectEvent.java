package com.robothy.s3.rest.listener;

import java.util.Map;

public class ObjectEvent extends S3Event {
    private final String bucketName;
    private final String objectKey;

    public ObjectEvent(S3EventType eventType, String source, String bucketName,
                       String objectKey) {
        super(eventType, source);
        this.bucketName = bucketName;
        this.objectKey = objectKey;
    }

    public String getBucketName() {
        return bucketName;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getObjectUrl() {
        return "s3://" + bucketName + "/" + objectKey;
    }

}