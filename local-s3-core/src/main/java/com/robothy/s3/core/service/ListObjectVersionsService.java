package com.robothy.s3.core.service;

import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.assertions.VersionedObjectAssertions;
import com.robothy.s3.core.model.answers.ListObjectVersionsAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.datatypes.enums.StorageClass;
import com.robothy.s3.datatypes.response.DeleteMarkerEntry;
import com.robothy.s3.datatypes.response.ObjectVersion;
import com.robothy.s3.datatypes.response.VersionItem;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;

public interface ListObjectVersionsService extends LocalS3MetadataApplicable {


  default ListObjectVersionsAns listObjectVersions(String bucket, String delimiter, String keyMarker, int maxKeys, String prefix, String versionIdMarker) {
    return withBucketReadLock(bucket, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);

      if (Objects.nonNull(versionIdMarker) && Objects.isNull(keyMarker)) {
        throw new LocalS3InvalidArgumentException("version-id-marker", versionIdMarker,
            "A version-id marker cannot be specified without a key marker.");
      }

      List<VersionItem> versionItems = new LinkedList<>();
      List<String> commonPrefixes = new LinkedList<>();

      int prefixLen = Objects.isNull(prefix) ? 0 : prefix.length();
      NavigableMap<String, ObjectMetadata> candidates;
      String commonPrefixOfKeyMarker = null;
      String nextVersionIdMarker;
      String nextKeyMarker;
      int delimiterIndex;
      if (Objects.nonNull(keyMarker)) {
        ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, keyMarker);

        // If the keyMarker doesn't have a common prefix.
        if (Objects.isNull(delimiter) || -1 == (delimiterIndex = keyMarker.indexOf(delimiter, prefixLen))) {
          NavigableMap<String, VersionedObjectMetadata> versions;
          if (Objects.nonNull(versionIdMarker)) {
            String realVersionId;
            if (ObjectMetadata.NULL_VERSION.equals(versionIdMarker)) {
              VersionedObjectAssertions.assertVirtualVersionExist(objectMetadata);
              realVersionId = objectMetadata.getVirtualVersion().get();
            } else {
              VersionedObjectAssertions.assertVersionedObjectExist(objectMetadata, versionIdMarker);
              realVersionId = versionIdMarker;
            }
            versions = objectMetadata.getVersionedObjectMap().tailMap(realVersionId, false);
          } else {
            versions = objectMetadata.getVersionedObjectMap();
          }

          // If the keyMarker match the prefix condition, then fetch related versions.
          if (Objects.isNull(prefix) || keyMarker.startsWith(prefix)) {
            nextVersionIdMarker = fetchVersions(versionItems, commonPrefixes, keyMarker, versions, false, maxKeys,
                objectMetadata.getVirtualVersion().orElse(null));
          } else {
            nextVersionIdMarker = versions.lastKey();
          }
        } else { // The keyMarker has a common prefix, which the previous page listed.
          commonPrefixOfKeyMarker = keyMarker.substring(0, delimiterIndex + delimiter.length());
          nextVersionIdMarker = null;
        }

        nextKeyMarker = keyMarker;
        candidates = bucketMetadata.getObjectMap().tailMap(keyMarker, false);
      } else {
        nextVersionIdMarker = nextKeyMarker = null;
        candidates = bucketMetadata.getObjectMap();
      }
      // Only the keys with the prefix are visited, rather than every key after the marker.
      candidates = ListItemUtils.filterByPrefix(candidates, prefix);
      if (Objects.nonNull(commonPrefixOfKeyMarker)) {
        candidates = ListItemUtils.skipPrefix(candidates, commonPrefixOfKeyMarker);
      }

      int keyCount = versionItems.size() + commonPrefixes.size();
      if (keyCount == maxKeys) {
        return ListObjectVersionsAns.builder()
            .nextKeyMarker(nextKeyMarker)
            .nextVersionIdMarker(nextVersionIdMarker)
            .versions(versionItems)
            .commonPrefixes(commonPrefixes)
            .build();
      }

      /*-- Process remaining keys. --*/

      // Once a key rolls up into a common prefix, the other keys of the prefix are skipped with a single lookup, so that
      // each common prefix is listed once, and a page takes O(page size * log N) steps however many keys it rolls up.
      Iterator<Map.Entry<String, ObjectMetadata>> entries = candidates.entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry<String, ObjectMetadata> entry = entries.next();
        String key = entry.getKey();
        ObjectMetadata objectMetadata = entry.getValue();
        if (Objects.nonNull(delimiter) && -1 != (delimiterIndex = key.indexOf(delimiter, prefixLen))) {
          String commonPrefix = key.substring(0, delimiterIndex + delimiter.length());
          commonPrefixes.add(commonPrefix);
          nextVersionIdMarker = null;
          entries = ListItemUtils.skipPrefix(candidates, commonPrefix).entrySet().iterator();
        } else {
          nextVersionIdMarker = fetchVersions(versionItems, commonPrefixes, key, objectMetadata.getVersionedObjectMap(), true, maxKeys, objectMetadata.getVirtualVersion().orElse(null));
        }
        nextKeyMarker = key;

        if ((keyCount = commonPrefixes.size() + versionItems.size()) == maxKeys) {
          break;
        }
      }

      return ListObjectVersionsAns.builder()
          .nextKeyMarker(keyCount == maxKeys ? nextKeyMarker : null)
          .nextVersionIdMarker((keyCount == maxKeys) ? nextVersionIdMarker : null)
          .versions(versionItems)
          .commonPrefixes(commonPrefixes)
          .build();
    });
  }

  /**
   * Fetch {@code versions} to {@code versionItems}.
   *
   * @param versionItems where version items store.
   * @param commonPrefixes fetched common prefixes.
   * @param versions where version items fetch from.
   * @param maxKeys max keys.
   * @return last visited version ID.
   */
  static String fetchVersions(List<VersionItem> versionItems, List<String> commonPrefixes, String key,
                               Map<String, VersionedObjectMetadata> versions, boolean firstItemIsLatest,
                               int maxKeys, String virtualVersion) {

    String lastVisitedVersion = null;
    int keyCount = versionItems.size() + commonPrefixes.size();
    boolean isLatest = firstItemIsLatest;
    for (Map.Entry<String, VersionedObjectMetadata> entry : versions.entrySet()) {
      if (keyCount == maxKeys) {
        break;
      }

      VersionedObjectMetadata versionedObjectMetadata = entry.getValue();

      if (versionedObjectMetadata.isDeleted()) {
        versionItems.add(DeleteMarkerEntry.builder()
            .latest(isLatest)
            .versionId(entry.getKey().equals(virtualVersion) ? ObjectMetadata.NULL_VERSION : entry.getKey())
            .key(key)
            .lastModified(Instant.ofEpochMilli(versionedObjectMetadata.getCreationDate()))
            .build());
      } else {
        versionItems.add(ObjectVersion.builder()
            .latest(isLatest)
            .versionId(entry.getKey().equals(virtualVersion) ? ObjectMetadata.NULL_VERSION : entry.getKey())
            .key(key)
            .lastModified(Instant.ofEpochMilli(versionedObjectMetadata.getCreationDate()))
            .size(versionedObjectMetadata.getSize())
            .etag(S3ObjectUtils.quoteEtag(versionedObjectMetadata.getEtag()))
            .storageClass(StorageClass.STANDARD)
            .owner(Owner.DEFAULT_OWNER)
            .build());
      }

      lastVisitedVersion = entry.getKey();
      isLatest = false;
      keyCount++;
    }

    return lastVisitedVersion;
  }


}
