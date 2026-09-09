package com.robothy.s3.rest.listener;

import java.time.Instant;
import java.util.Map;

public abstract class S3Event {
    private final String eventId;
    private final S3EventType eventType;
    private final Instant timestamp;
    private final String source;
    private final Map<String, String> metadata;

    public S3Event(S3EventType eventType, String source) {
        this.eventId = java.util.UUID.randomUUID().toString();
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.source = source;
        this.metadata = null;
    }

    public S3Event(S3EventType eventType, String source, Map<String, String> metadata) {
        this.eventId = java.util.UUID.randomUUID().toString();
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.source = source;
        this.metadata = metadata;
    }

    public String getEventId() {
        return eventId;
    }

    public S3EventType getEventType() {
        return eventType;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getSource() {
        return source;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

}