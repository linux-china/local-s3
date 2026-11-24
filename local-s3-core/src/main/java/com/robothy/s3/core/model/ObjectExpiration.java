package com.robothy.s3.core.model;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;

/**
 * When the current version of an object expires by a lifecycle rule; see
 * {@code docs/semantics.md#lifecycle-configuration}.
 *
 * @param expiryDate the epoch milliseconds the version expires at.
 * @param ruleId the ID of the rule that expires it; {@code null} if the rule has none.
 */
public record ObjectExpiration(long expiryDate, String ruleId) {

  /**
   * The expiration of a version if it is the current one of its object.
   *
   * @param bucket the bucket of the object.
   * @param key the object key.
   * @param version the current, not deleted, version of the object.
   * @return the earliest expiration by an enabled rule; {@code null} if no rule expires the version.
   */
  public static ObjectExpiration of(BucketMetadata bucket, String key, VersionedObjectMetadata version) {
    if (bucket.getLifecycle().isEmpty()) {
      return null;
    }
    ObjectExpiration earliest = null;
    for (LifecycleRule rule : LifecycleRule.parse(bucket.getLifecycle().get().configuration())) {
      if (!rule.enabled() || (rule.expirationDays() == null && rule.expirationDate() == null)
          || !rule.filter().matches(key, version.getTagging().orElse(null), version.getSize())) {
        continue;
      }
      long at = rule.expirationDays() != null
          ? LifecycleRule.dueAt(version.getCreationDate(), rule.expirationDays())
          : rule.expirationDate();
      if (earliest == null || at < earliest.expiryDate()) {
        earliest = new ObjectExpiration(at, rule.id());
      }
    }
    return earliest;
  }

}
