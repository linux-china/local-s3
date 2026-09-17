package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectLockAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.model.LifecycleRule;
import com.robothy.s3.core.model.answers.DeleteObjectAns;
import com.robothy.s3.core.model.answers.LifecycleActionAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.util.ObjectContentUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;

/**
 * Applies the lifecycle configurations of buckets on demand. LocalS3 never applies them by itself: a test that exercises
 * expiration asks for it, at a time of its choosing, e.g. 30 days from now, rather than waiting for a day to pass.
 *
 * <p>Every enabled rule is applied the way Amazon S3 applies it at that time:
 * <ul>
 *   <li>{@code Expiration} with {@code Days} or {@code Date} expires the current version of an object that the filter
 *   selects: the object is deleted in a bucket whose versioning was never enabled, and gets a delete marker in a
 *   versioned bucket.</li>
 *   <li>{@code NoncurrentVersionExpiration} deletes the versions, and delete markers, that have been noncurrent for
 *   {@code NoncurrentDays}, keeping the {@code NewerNoncurrentVersions} most recent ones. A version that Object Lock
 *   protects is kept.</li>
 *   <li>{@code ExpiredObjectDeleteMarker} removes a delete marker that is the only version left of its object.</li>
 *   <li>{@code AbortIncompleteMultipartUpload} aborts the uploads of the keys the prefix selects that were created
 *   {@code DaysAfterInitiation} ago.</li>
 * </ul>
 * A number of days counts from the creation of the object, the time a version became noncurrent, or the creation of
 * an upload, and is rounded up to the next midnight UTC. Transitions change nothing, since LocalS3 has one storage
 * class. The changes are published with the operation {@value #OPERATION}.
 */
public interface LifecycleExecutionService extends LocalS3MetadataApplicable, StorageApplicable {

  /**
   * The operation that the changes of a lifecycle action are published with.
   */
  String OPERATION = "LifecycleExpiration";

  /**
   * Apply the lifecycle configurations of every bucket that has one, each under the write lock of its bucket.
   *
   * @param now the time to apply the rules at, e.g. a time in the future to expire what would have expired by then.
   * @return the actions taken, bucket by bucket.
   */
  default List<LifecycleActionAns> applyLifecycle(Instant now) {
    List<LifecycleActionAns> actions = new ArrayList<>();
    for (BucketMetadata bucket : localS3Metadata().listBuckets()) {
      if (bucket.getLifecycle().isPresent()) {
        actions.addAll(applyLifecycle(bucket.getBucketName(), now));
      }
    }
    return actions;
  }

  /**
   * Apply the lifecycle configuration of a bucket under its write lock.
   *
   * @param bucketName the bucket name.
   * @param now the time to apply the rules at.
   * @return the actions taken; empty if the bucket has no lifecycle configuration.
   * @throws com.robothy.s3.core.exception.BucketNotExistException if the bucket doesn't exist.
   */
  default List<LifecycleActionAns> applyLifecycle(String bucketName, Instant now) {
    Objects.requireNonNull(now, "now");
    return changeBucket(bucketName, () -> {
      BucketMetadata bucket = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      if (bucket.getLifecycle().isEmpty()) {
        return List.of();
      }
      List<LifecycleRule> rules = LifecycleRule.parse(bucket.getLifecycle().get().configuration()).stream()
          .filter(LifecycleRule::enabled)
          .toList();
      List<LifecycleActionAns> actions = new ArrayList<>();
      long at = now.toEpochMilli();
      for (String key : List.copyOf(bucket.getObjectMap().keySet())) {
        List<LifecycleRule> matching = rules.stream().filter(rule -> rule.filter().matchesKey(key)).toList();
        if (!matching.isEmpty() && bucket.getObjectMetadataRef(key).isPresent()) {
          applyToObject(bucket, key, matching, at, actions);
        }
      }
      abortUploads(bucket, rules, at, actions);
      return actions;
    });
  }

  private void applyToObject(BucketMetadata bucket, String key, List<LifecycleRule> rules, long now,
                             List<LifecycleActionAns> actions) {
    String bucketName = bucket.getBucketName();
    ObjectMetadata object = bucket.getObjectMetadataRef(key).map(ObjectMetadataRef::get).orElseThrow();
    VersionedObjectMetadata current = object.getLatest();
    if (!current.isDeleted()) {
      LifecycleRule expiring = rules.stream()
          .filter(rule -> isCurrentVersionExpired(rule, current, now))
          .filter(rule -> rule.filter().matches(key, current.getTagging().orElse(null), current.getSize()))
          .findFirst().orElse(null);
      if (expiring != null) {
        if (Objects.isNull(bucket.getVersioningEnabled())) {
          DeleteObjectService.deleteObjectFromUnVersionedBucket(bucket, storage(), key, null);
          publishChange(S3Change.objectDeleted(OPERATION, bucketName, key, null, false));
          actions.add(new LifecycleActionAns(bucketName, expiring.id(), LifecycleActionAns.Type.OBJECT_EXPIRED, key,
              null, null));
          return;
        }
        DeleteObjectAns marker = DeleteObjectService.deleteWithoutVersionId(storage(), bucket, key);
        publishChange(S3Change.objectDeleted(OPERATION, bucketName, key, marker.getVersionId(), true));
        actions.add(new LifecycleActionAns(bucketName, expiring.id(), LifecycleActionAns.Type.DELETE_MARKER_CREATED,
            key, marker.getVersionId(), null));
      }
    }
    if (Objects.isNull(bucket.getVersioningEnabled())) {
      return;
    }

    expireNoncurrentVersions(bucket, key, rules, now, actions);

    object = bucket.getObjectMetadataRef(key).map(ObjectMetadataRef::get).orElseThrow();
    if (object.getVersionedObjectMap().size() == 1 && object.getLatest().isDeleted()) {
      LifecycleRule removing = rules.stream().filter(LifecycleRule::expiredObjectDeleteMarker).findFirst().orElse(null);
      if (removing != null) {
        String versionId = object.getLatestVersion();
        String returnedVersionId = returnedVersionId(object, versionId);
        bucket.removeObjectMetadata(key);
        publishChange(S3Change.objectDeleted(OPERATION, bucketName, key, returnedVersionId, false));
        actions.add(new LifecycleActionAns(bucketName, removing.id(),
            LifecycleActionAns.Type.EXPIRED_DELETE_MARKER_REMOVED, key, returnedVersionId, null));
      }
    }
  }

  private void expireNoncurrentVersions(BucketMetadata bucket, String key, List<LifecycleRule> rules, long now,
                                        List<LifecycleActionAns> actions) {
    if (rules.stream().allMatch(rule -> rule.noncurrentDays() == null)) {
      return;
    }
    ObjectMetadata object = bucket.getObjectMetadataRef(key).map(ObjectMetadataRef::get).orElseThrow();
    List<Map.Entry<String, VersionedObjectMetadata>> versions = List.copyOf(object.getVersionedObjectMap().entrySet());
    List<Map.Entry<String, LifecycleRule>> expired = new ArrayList<>();
    for (int i = 1; i < versions.size(); i++) {
      VersionedObjectMetadata version = versions.get(i).getValue();
      // A version becomes noncurrent when the next one is stored, and is the (i - 1)th newest noncurrent version.
      long noncurrentSince = versions.get(i - 1).getValue().getCreationDate();
      int newerNoncurrentVersions = i - 1;
      LifecycleRule expiring = rules.stream()
          .filter(rule -> rule.noncurrentDays() != null
              && LifecycleRule.dueAt(noncurrentSince, rule.noncurrentDays()) <= now
              && (rule.newerNoncurrentVersions() == null || newerNoncurrentVersions >= rule.newerNoncurrentVersions()))
          .filter(rule -> rule.filter().matches(key, version.getTagging().orElse(null), version.getSize()))
          .findFirst().orElse(null);
      if (expiring != null && !ObjectLockAssertions.isProtected(version, now, false)) {
        expired.add(Map.entry(versions.get(i).getKey(), expiring));
      }
    }
    if (expired.isEmpty()) {
      return;
    }

    // Marks the object as changed, so that the metadata store writes it.
    object = bucket.getObjectMetadata(key).orElseThrow();
    for (Map.Entry<String, LifecycleRule> entry : expired) {
      String versionId = entry.getKey();
      String returnedVersionId = returnedVersionId(object, versionId);
      VersionedObjectMetadata removed = object.removeVersionedObjectMetadata(versionId);
      if (object.getVirtualVersion().map(versionId::equals).orElse(false)) {
        object.setVirtualVersion(null);
      }
      if (!removed.isDeleted()) {
        ObjectContentUtils.delete(storage(), removed);
      }
      publishChange(S3Change.objectDeleted(OPERATION, bucket.getBucketName(), key, returnedVersionId, false));
      actions.add(new LifecycleActionAns(bucket.getBucketName(), entry.getValue().id(),
          LifecycleActionAns.Type.NONCURRENT_VERSION_EXPIRED, key, returnedVersionId, null));
    }
  }

  private void abortUploads(BucketMetadata bucket, List<LifecycleRule> rules, long now,
                            List<LifecycleActionAns> actions) {
    List<LifecycleRule> aborting = rules.stream()
        .filter(rule -> rule.abortIncompleteMultipartUploadDays() != null)
        .toList();
    if (aborting.isEmpty()) {
      return;
    }
    NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = bucket.getUploads();
    for (String key : List.copyOf(uploads.keySet())) {
      NavigableMap<String, UploadMetadata> uploadsOfKey = uploads.get(key);
      for (Map.Entry<String, UploadMetadata> upload : List.copyOf(uploadsOfKey.entrySet())) {
        LifecycleRule rule = aborting.stream()
            .filter(candidate -> candidate.filter().matchesKey(key)
                && LifecycleRule.dueAt(upload.getValue().getCreateDate(),
                candidate.abortIncompleteMultipartUploadDays()) <= now)
            .findFirst().orElse(null);
        if (rule == null) {
          continue;
        }
        uploadsOfKey.remove(upload.getKey());
        upload.getValue().getParts().values().forEach(part -> storage().delete(part.getFileId()));
        bucket.markUploadsChanged(key);
        publishChange(S3Change.multipartUploadAborted(OPERATION, bucket.getBucketName(), key, upload.getKey()));
        actions.add(new LifecycleActionAns(bucket.getBucketName(), rule.id(),
            LifecycleActionAns.Type.MULTIPART_UPLOAD_ABORTED, key, null, upload.getKey()));
      }
      if (uploadsOfKey.isEmpty()) {
        uploads.remove(key);
      }
    }
  }

  private static boolean isCurrentVersionExpired(LifecycleRule rule, VersionedObjectMetadata current, long now) {
    return (rule.expirationDays() != null && LifecycleRule.dueAt(current.getCreationDate(), rule.expirationDays()) <= now)
        || (rule.expirationDate() != null && rule.expirationDate() <= now);
  }

  /**
   * The version ID that a client knows a version by: {@code null} for the version of an object that was stored while
   * the versioning of the bucket was suspended or never enabled.
   */
  private static String returnedVersionId(ObjectMetadata object, String versionId) {
    return object.getVirtualVersion().map(versionId::equals).orElse(false) ? ObjectMetadata.NULL_VERSION : versionId;
  }

}
