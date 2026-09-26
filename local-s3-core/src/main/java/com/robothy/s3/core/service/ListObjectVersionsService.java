package com.robothy.s3.core.service;

import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.answers.ListObjectVersionsAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.datatypes.response.DeleteMarkerEntry;
import com.robothy.s3.datatypes.response.ObjectVersion;
import com.robothy.s3.datatypes.response.VersionItem;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import java.util.Objects;
import java.util.Optional;

public interface ListObjectVersionsService extends LocalS3MetadataApplicable {


  default ListObjectVersionsAns listObjectVersions(String bucket, String delimiter, String keyMarker, int maxKeys,
                                                   String prefix, String versionIdMarker) {
    // An empty marker, which some clients send on their first request, is no marker.
    String startKey = Objects.isNull(keyMarker) || keyMarker.isEmpty() ? null : keyMarker;
    String startVersionId = Objects.isNull(versionIdMarker) || versionIdMarker.isEmpty() ? null : versionIdMarker;
    return withBucketReadLock(bucket, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);

      if (Objects.nonNull(startVersionId) && Objects.isNull(startKey)) {
        throw new LocalS3InvalidArgumentException("version-id-marker", startVersionId,
            "A version-id marker cannot be specified without a key marker.");
      }

      List<VersionItem> versionItems = new LinkedList<>();
      List<String> commonPrefixes = new LinkedList<>();
      if (maxKeys <= 0) {
        return ListObjectVersionsAns.builder().versions(versionItems).commonPrefixes(commonPrefixes).build();
      }

      int prefixLen = Objects.isNull(prefix) ? 0 : prefix.length();
      NavigableMap<String, ObjectMetadataRef> candidates;
      String commonPrefixOfKeyMarker = null;
      Fetched fetchedOfKeyMarker = null;
      int delimiterIndex;
      if (Objects.nonNull(startKey)) {
        // If the keyMarker doesn't have a common prefix.
        if (Objects.isNull(delimiter) || -1 == (delimiterIndex = startKey.indexOf(delimiter, prefixLen))) {
          // Like Amazon S3, a key marker without a version ID marker starts the listing after the key, and the markers
          // needn't name a key or a version that exists. A client that deletes the versions of each page before it
          // asks for the next one, e.g. to empty a bucket, sends the markers of a version that is gone, and the
          // listing goes on after it.
          if (Objects.nonNull(startVersionId) && (Objects.isNull(prefix) || startKey.startsWith(prefix))) {
            Optional<ObjectMetadata> objectMetadata = bucketMetadata.getObjectMetadata(startKey);
            if (objectMetadata.isPresent()) {
              fetchedOfKeyMarker = fetchVersions(versionItems, commonPrefixes, startKey,
                  versionsAfter(objectMetadata.get(), startVersionId), false, maxKeys,
                  objectMetadata.get().getVirtualVersion().orElse(null));
            }
          }
        } else { // The keyMarker has a common prefix, which the previous page listed.
          commonPrefixOfKeyMarker = startKey.substring(0, delimiterIndex + delimiter.length());
        }
        candidates = bucketMetadata.getObjectMap().tailMap(startKey, false);
      } else {
        candidates = bucketMetadata.getObjectMap();
      }
      // Only the keys with the prefix are visited, rather than every key after the marker.
      candidates = ListItemUtils.filterByPrefix(candidates, prefix);
      if (Objects.nonNull(commonPrefixOfKeyMarker)) {
        candidates = ListItemUtils.skipPrefix(candidates, commonPrefixOfKeyMarker);
      }

      if (versionItems.size() + commonPrefixes.size() == maxKeys) {
        // The page is full of the versions of the key marker; it is truncated only if something is left to list.
        return page(versionItems, commonPrefixes, fetchedOfKeyMarker.truncated() || !candidates.isEmpty(),
            startKey, fetchedOfKeyMarker.lastVersionId());
      }

      /*-- Process remaining keys. --*/

      // Once a key rolls up into a common prefix, the other keys of the prefix are skipped with a single lookup, so that
      // each common prefix is listed once, and a page takes O(page size * log N) steps however many keys it rolls up.
      Iterator<Map.Entry<String, ObjectMetadataRef>> entries = candidates.entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry<String, ObjectMetadataRef> entry = entries.next();
        String key = entry.getKey();
        // Only the keys of the page have their metadata read; the ones rolled up into a common prefix are skipped.
        ObjectMetadataRef ref = entry.getValue();
        String nextVersionIdMarker;
        boolean truncatedInKey;
        if (Objects.nonNull(delimiter) && -1 != (delimiterIndex = key.indexOf(delimiter, prefixLen))) {
          String commonPrefix = key.substring(0, delimiterIndex + delimiter.length());
          commonPrefixes.add(commonPrefix);
          nextVersionIdMarker = null;
          truncatedInKey = false;
          entries = ListItemUtils.skipPrefix(candidates, commonPrefix).entrySet().iterator();
        } else {
          ObjectMetadata objectMetadata = ref.get();
          Fetched fetched = fetchVersions(versionItems, commonPrefixes, key, objectMetadata.getVersionedObjectMap(),
              true, maxKeys, objectMetadata.getVirtualVersion().orElse(null));
          nextVersionIdMarker = fetched.lastVersionId();
          truncatedInKey = fetched.truncated();
        }

        if (commonPrefixes.size() + versionItems.size() == maxKeys) {
          return page(versionItems, commonPrefixes, truncatedInKey || entries.hasNext(), key, nextVersionIdMarker);
        }
      }

      return page(versionItems, commonPrefixes, false, null, null);
    });
  }

  /**
   * A page of the listing, which carries the markers of the next page only if it is truncated, i.e. something is left
   * to list after it, so that a page that happens to end with the last version isn't followed by an empty one.
   */
  private static ListObjectVersionsAns page(List<VersionItem> versionItems, List<String> commonPrefixes,
                                            boolean truncated, String nextKeyMarker, String nextVersionIdMarker) {
    return ListObjectVersionsAns.builder()
        .nextKeyMarker(truncated ? nextKeyMarker : null)
        .nextVersionIdMarker(truncated ? nextVersionIdMarker : null)
        .versions(versionItems)
        .commonPrefixes(commonPrefixes)
        .build();
  }

  /**
   * The versions of an object that come after the version ID marker. A version ID orders the versions by when they were
   * created, so the marker of a version that is gone still has its place among them. The null version is the exception:
   * its marker is {@code null}, like Amazon S3 answers it, which has no place once the version is gone, e.g. deleted by a
   * client that empties the bucket page by page. All the versions of the object are then listed, so that none is
   * skipped; a version that the previous page listed and that is still there is listed again.
   *
   * @param objectMetadata the object that the key marker names.
   * @param versionIdMarker the version ID marker.
   * @return the versions of the object that come after the marker.
   */
  private static NavigableMap<String, VersionedObjectMetadata> versionsAfter(ObjectMetadata objectMetadata,
                                                                             String versionIdMarker) {
    if (ObjectMetadata.NULL_VERSION.equals(versionIdMarker)) {
      return objectMetadata.getVirtualVersion()
          .filter(virtualVersion -> objectMetadata.getVersionedObjectMap().containsKey(virtualVersion))
          .map(virtualVersion -> objectMetadata.getVersionedObjectMap().tailMap(virtualVersion, false))
          .orElseGet(objectMetadata::getVersionedObjectMap);
    }
    return objectMetadata.getVersionedObjectMap().tailMap(versionIdMarker, false);
  }

  /**
   * The versions that {@linkplain #fetchVersions} listed of a key.
   *
   * @param lastVersionId the ID of the last version listed, as it is answered, i.e. {@code null} for the null version;
   *     {@code null} if none was listed.
   * @param truncated whether versions of the key are left, which the page had no room for.
   */
  record Fetched(String lastVersionId, boolean truncated) {
  }

  /**
   * Fetch {@code versions} to {@code versionItems}, until the page holds {@code maxKeys} items.
   *
   * @param versionItems where version items store.
   * @param commonPrefixes fetched common prefixes.
   * @param versions where version items fetch from.
   * @param maxKeys max keys.
   * @param virtualVersion the version ID that the null version of the key is held by; {@code null} if it has none.
   * @return the last version listed, and whether versions are left.
   */
  static Fetched fetchVersions(List<VersionItem> versionItems, List<String> commonPrefixes, String key,
                               Map<String, VersionedObjectMetadata> versions, boolean firstItemIsLatest,
                               int maxKeys, String virtualVersion) {

    String lastVisitedVersion = null;
    int keyCount = versionItems.size() + commonPrefixes.size();
    boolean isLatest = firstItemIsLatest;
    for (Map.Entry<String, VersionedObjectMetadata> entry : versions.entrySet()) {
      if (keyCount == maxKeys) {
        return new Fetched(lastVisitedVersion, true);
      }

      VersionedObjectMetadata versionedObjectMetadata = entry.getValue();
      String versionId = entry.getKey().equals(virtualVersion) ? ObjectMetadata.NULL_VERSION : entry.getKey();

      if (versionedObjectMetadata.isDeleted()) {
        versionItems.add(DeleteMarkerEntry.builder()
            .latest(isLatest)
            .versionId(versionId)
            .key(key)
            .lastModified(Instant.ofEpochMilli(versionedObjectMetadata.getCreationDate()))
            .build());
      } else {
        versionItems.add(ObjectVersion.builder()
            .latest(isLatest)
            .versionId(versionId)
            .key(key)
            .lastModified(Instant.ofEpochMilli(versionedObjectMetadata.getCreationDate()))
            .size(versionedObjectMetadata.getSize())
            .etag(S3ObjectUtils.quoteEtag(versionedObjectMetadata.getEtag()))
            .storageClass(SystemMetadata.storageClassOf(versionedObjectMetadata.getSystemMetadata()))
            .owner(Owner.DEFAULT_OWNER)
            .checkSumAlgorithm(Optional.ofNullable(versionedObjectMetadata.getChecksum())
                .map(ObjectChecksum::getAlgorithm).orElse(null))
            .checksumType(Optional.ofNullable(versionedObjectMetadata.getChecksum())
                .map(ObjectChecksum::getType).orElse(null))
            .build());
      }

      lastVisitedVersion = versionId;
      isLatest = false;
      keyCount++;
    }

    return new Fetched(lastVisitedVersion, false);
  }

}
