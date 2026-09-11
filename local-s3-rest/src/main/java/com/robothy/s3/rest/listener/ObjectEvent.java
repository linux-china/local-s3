package com.robothy.s3.rest.listener;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class ObjectEvent extends S3Event {
    private final String bucketName;
    private final String objectKey;
    private final String versionId;
    private final Long size;
    private final String etag;
    private final boolean deleteMarker;

    /**
     * Create an object event.
     *
     * @param eventType    event type.
     * @param source       the S3 operation that triggered the event, e.g. {@code PutObject}.
     * @param bucketName   bucket name.
     * @param objectKey    object key.
     * @param versionId    version ID of the created object, or of the deleted version or created delete marker.
     * @param size         size in bytes of the created object; {@code null} for delete events.
     * @param etag         etag of the created object; {@code null} for delete events.
     * @param deleteMarker whether the deletion created a delete marker.
     */
    public ObjectEvent(@NonNull S3EventType eventType, String source,
                       @NonNull String bucketName,
                       @NonNull String objectKey,
                       @Nullable String versionId,
                       @Nullable Long size,
                       @Nullable String etag,
                       boolean deleteMarker) {
        super(eventType, source);
        this.bucketName = bucketName;
        this.objectKey = objectKey;
        this.versionId = versionId;
        this.size = size;
        this.etag = etag;
        this.deleteMarker = deleteMarker;
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

    /**
     * Version ID of the created object, or of the deleted version or created delete marker.
     * {@code null} if the bucket has never been versioned.
     */
    @Nullable
    public String getVersionId() {
        return versionId;
    }

    /**
     * Size in bytes of the created object; {@code null} for delete events.
     */
    @Nullable
    public Long getSize() {
        return size;
    }

    /**
     * Etag of the created object; {@code null} for delete events.
     */
    @Nullable
    public String getEtag() {
        return etag;
    }

    /**
     * Whether the deletion created a delete marker instead of removing a version.
     */
    public boolean isDeleteMarker() {
        return deleteMarker;
    }

}
