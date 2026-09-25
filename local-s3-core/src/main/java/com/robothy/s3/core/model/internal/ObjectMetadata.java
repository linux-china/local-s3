package com.robothy.s3.core.model.internal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.robothy.s3.core.converters.deserializer.VersionedObjectMetadataMapConverter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Represents local-s3 Object metadata, which holds every version that the object key was ever stored
 * with.
 *
 * <p>Like {@linkplain BucketMetadata}, it carries no {@code equals} and {@code hashCode} of its own, so
 * two of them are the same object only if they are the same instance: the versions of a key are mutable
 * state that a request adds to, and a generated pair would walk all of them on each call.
 *
 * <p>It records which of its versions changed since a metadata store last wrote it, so that the store writes those
 * rather than every version of the key: a key that is overwritten over and over in a versioned bucket would otherwise
 * cost a write of its whole history each time. The versions are changed through {@linkplain
 * #putVersionedObjectMetadata} and {@linkplain #removeVersionedObjectMetadata}, which record the change; a caller that
 * changes a version in place, e.g. sets its tagging, records it with {@linkplain #markVersionChanged}.
 */
@Getter
@Setter
public class ObjectMetadata {

  /**
   * When bucket versioning is not enabled, the object version ID is "null".
   */
  public static final String NULL_VERSION = "null";

  /**
   * Orders version IDs with the most recent one, which is the greatest ID, first. The IDs are compared
   * as numbers rather than as text, so that IDs of different lengths, and the negative IDs that an
   * overflowed generator produced, are still ordered by age. A non numeric ID, which no generator
   * produces, is ordered after the generated ones.
   */
  public static final Comparator<String> VERSION_ID_COMPARATOR = (left, right) -> {
    Long leftId = toVersionNumber(left);
    Long rightId = toVersionNumber(right);
    if (leftId == null || rightId == null) {
      if (leftId != null) {
        return -1;
      }
      if (rightId != null) {
        return 1;
      }
      return right.compareTo(left);
    }

    int comparison = Long.compare(rightId, leftId);
    // Distinct IDs holding the same number, e.g. one with a leading zero, are still distinct versions.
    return comparison == 0 ? right.compareTo(left) : comparison;
  };

  private static Long toVersionNumber(String versionId) {
    try {
      return Long.parseLong(versionId);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * The most recent instance on the top.
   */
  @JsonDeserialize(converter = VersionedObjectMetadataMapConverter.class)
  private ConcurrentSkipListMap<String, VersionedObjectMetadata> versionedObjectMap =
      new ConcurrentSkipListMap<>(VERSION_ID_COMPARATOR);

  /**
   * Represents a virtual version when bucket versioning is not enabled.
   * The virtual version is an internal field, which is used for sorting
   * versions. It will be mapped to string "null" when returned to the client.
   */
  private String virtualVersion;

  /**
   * The IDs of the versions that were put, changed or removed since a metadata store last wrote this object. Not part
   * of the persisted state.
   */
  @JsonIgnore
  @Getter(AccessLevel.NONE)
  private final transient Set<String> changedVersionIds = ConcurrentHashMap.newKeySet();

  /**
   * Whether every version must be written, because this instance has never been written as it is, e.g. it was just
   * created or read from a whole JSON document. Cleared by {@linkplain #markPersisted()}.
   */
  @JsonIgnore
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private transient volatile boolean allVersionsChanged = true;


  /**
   * Construct an {@linkplain ObjectMetadata} instance. A new {@linkplain ObjectMetadata} instance must
   * associate with a {@linkplain VersionedObjectMetadata}.
   *
   * @param version the version ID. If the bucket versioning is not enabled, it
   *                should be set to virtual version via {@linkplain #setVirtualVersion(String)}.
   * @param firstVersion first versioned instance of the {@linkplain ObjectMetadata}.
   */
  public ObjectMetadata(String version, VersionedObjectMetadata firstVersion) {
    this.putVersionedObjectMetadata(version, firstVersion);
  }

  /**
   * No args constructor for jackson. Do not use this constructor.
   */
  public ObjectMetadata() {

  }


  /**
   * Get a {@linkplain VersionedObjectMetadata} instance by version ID.
   *
   * @param versionId version ID of the {@linkplain VersionedObjectMetadata}.
   * @return the {@linkplain VersionedObjectMetadata} object.
   */
  public Optional<VersionedObjectMetadata> getVersionedObjectMetadata(String versionId) {
    return Optional.ofNullable(versionedObjectMap.get(versionId));
  }

  /**
   * The same as {@code  getVersionedObjectMap().put(versionId, versionedObjectMetadata)}.
   *
   * @param versionId version ID.
   * @param versionedObjectMetadata versioned object metadata instance.
   */
  public void putVersionedObjectMetadata(String versionId, VersionedObjectMetadata versionedObjectMetadata) {
    this.versionedObjectMap.put(versionId, versionedObjectMetadata);
    changedVersionIds.add(versionId);
  }

  /**
   * Remove a version of this object.
   *
   * @param versionId version ID.
   * @return the removed version; {@code null} if the object holds no such version.
   */
  public VersionedObjectMetadata removeVersionedObjectMetadata(String versionId) {
    VersionedObjectMetadata removed = versionedObjectMap.remove(versionId);
    changedVersionIds.add(versionId);
    return removed;
  }

  /**
   * The versions of this object, the most recent one first. The view can't be changed: change the versions through
   * {@linkplain #putVersionedObjectMetadata} and {@linkplain #removeVersionedObjectMetadata}, which record the change.
   *
   * @return a read-only view of the versions by version ID.
   */
  public NavigableMap<String, VersionedObjectMetadata> getVersionedObjectMap() {
    return Collections.unmodifiableNavigableMap(versionedObjectMap);
  }

  /**
   * Record that a version was changed in place, e.g. its tagging was set, so that a metadata store writes it.
   *
   * @param versionId version ID.
   */
  public void markVersionChanged(String versionId) {
    changedVersionIds.add(versionId);
  }

  /**
   * Whether every version must be written, rather than only {@linkplain #drainChangedVersionIds() the changed ones}.
   *
   * @return {@code true} if this instance has never been written or read as it is by a metadata store.
   */
  @JsonIgnore
  public boolean isAllVersionsChanged() {
    return allVersionsChanged;
  }

  /**
   * Take the IDs of the versions that changed since this was last called, and forget them.
   *
   * @return the changed version IDs, which may name versions that were removed since.
   */
  public List<String> drainChangedVersionIds() {
    if (changedVersionIds.isEmpty()) {
      return List.of();
    }
    List<String> drained = List.copyOf(changedVersionIds);
    drained.forEach(changedVersionIds::remove);
    return drained;
  }

  /**
   * Record that a metadata store holds this object as it is now, so that only the versions that change from here on
   * are written.
   */
  public void markPersisted() {
    changedVersionIds.clear();
    allVersionsChanged = false;
  }

  /**
   * Get the latest versioned object. Always exists.
   */
  @JsonIgnore
  public VersionedObjectMetadata getLatest() {
    return versionedObjectMap.firstEntry().getValue();
  }

  /**
   * Whether the latest version of this object is a delete marker, i.e. whether a listing leaves the object out.
   *
   * @return {@code true} if the latest version is a delete marker; {@code false} if it isn't, or there is none.
   */
  @JsonIgnore
  public boolean isLatestDeleted() {
    var latest = versionedObjectMap.firstEntry();
    return latest != null && latest.getValue().isDeleted();
  }

  /**
   * Get the latest version ID of this object. It may be a virtual version.
   *
   * @return the latest version of this object.
   */
  @JsonIgnore
  public String getLatestVersion() {
    return versionedObjectMap.firstKey();
  }

  /**
   * Get the virtual version of this object.
   *
   * @return the virtual version of the object.
   */
  public Optional<String> getVirtualVersion() {
    return Optional.ofNullable(virtualVersion);
  }

  /**
   * Names the version the object is currently read as, and leaves out the versions it holds: a key under
   * test holds an unbounded number of them, so a generated {@code toString} would dump every version of
   * the object. Read them through {@linkplain #getVersionedObjectMap()} instead.
   */
  @Override
  public String toString() {
    var latest = versionedObjectMap.firstEntry();
    return "ObjectMetadata(latestVersion=" + (latest == null ? null : latest.getKey())
        + ", virtualVersion=" + virtualVersion + ")";
  }

}
