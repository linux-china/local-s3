package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.ListObjectsAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.datatypes.response.RestoreStatus;
import com.robothy.s3.datatypes.response.S3Object;

import java.time.Instant;
import java.util.*;

public interface ListObjectsService extends LocalS3MetadataApplicable {

  default ListObjectsAns listObjects(String bucket, String delimiter, String encodingType,
                                     String marker, int maxKeys, String prefix) {
    return withBucketReadLock(bucket, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
      String effectivePrefix = Objects.toString(prefix, "");

      NavigableMap<String, ObjectMetadataRef> objectsAfterMarker =
              ListItemUtils.filterByKeyMarkerAndDelimiterForListObjects(bucketMetadata.getObjectMap(), marker, effectivePrefix, delimiter);
      NavigableMap<String, ObjectMetadataRef> filteredByPrefix = ListItemUtils.filterByPrefix(objectsAfterMarker, prefix);

      ListObjectsAns listObjectsAns = listObjectsAndCommonPrefixes(filteredByPrefix, effectivePrefix, delimiter, maxKeys);
      listObjectsAns.setDelimiter(delimiter);
      listObjectsAns.setMarker(Objects.isNull(marker) ? "" : marker);
      listObjectsAns.setPrefix(effectivePrefix);
      // Amazon S3 leaves the Prefix of ListObjects (v1) unencoded, and botocore decodes only its Delimiter, Marker,
      // NextMarker and keys; an encoded Prefix, e.g. "%0A" for "\n", would reach a boto3 client as it was sent.
      encodeIfNeeded(listObjectsAns, encodingType, false);
      return listObjectsAns;
    });
  }

  /**
   * Once a key rolls up into a common prefix, the rest of the prefix is skipped with one lookup, so a page costs
   * O(page size &times; log N) however many keys it rolls up. Delete markers are stepped over by their reference,
   * without reading their metadata from the store.
   */
  static ListObjectsAns listObjectsAndCommonPrefixes(NavigableMap<String, ObjectMetadataRef> filteredObjects, String effectivePrefix, String delimiter, int maxKeys) {
    if (filteredObjects.isEmpty() || 0 == maxKeys) {
      return ListObjectsAns.builder()
        .delimiter(delimiter)
        .maxKeys(maxKeys)
        .build();
    }

    List<S3Object> objects = new ArrayList<>();
    // Distinct and in order: the keys of a common prefix are skipped once it is listed.
    List<String> commonPrefixes = new ArrayList<>();

    String nextMarker = null;

    Iterator<Map.Entry<String, ObjectMetadataRef>> entries = filteredObjects.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<String, ObjectMetadataRef> entry = entries.next();
      String key = entry.getKey();
      if (entry.getValue().isLatestDeleted()) {
        continue;
      }

      Optional<String> commonPrefix = ListItemUtils.commonPrefix(key, effectivePrefix, delimiter);
      if (commonPrefix.isPresent()) {
        commonPrefixes.add(commonPrefix.get());
        entries = ListItemUtils.skipPrefix(filteredObjects, commonPrefix.get()).entrySet().iterator();
      } else {
        objects.add(fetchLatestObject(key, entry.getValue().get()));
      }

      int keyCount = commonPrefixes.size() + objects.size();

      if (keyCount == maxKeys) {
        nextMarker = calculateNextMarker(key, entries, effectivePrefix, delimiter);
        break;
      }

    }

    return ListObjectsAns.builder()
      .delimiter(delimiter)
      .maxKeys(maxKeys)
      .nextMarker(nextMarker)
      .isTruncated(Objects.nonNull(nextMarker))
      .objects(objects)
      .commonPrefixes(commonPrefixes)
      .build();
  }

  /**
   * @return {@code null} if only delete markers are left, so that no empty page is announced.
   */
  static String calculateNextMarker(String currentKey, Iterator<Map.Entry<String, ObjectMetadataRef>> entries,
          String effectivePrefix, String delimiter) {

    Optional<String> commonPrefixOpt = ListItemUtils.commonPrefix(currentKey, effectivePrefix, delimiter);
    String nextMarker = commonPrefixOpt.orElse(currentKey);
    while (entries.hasNext()) {
      Map.Entry<String, ObjectMetadataRef> entry = entries.next();
      if (entry.getValue().isLatestDeleted()) {
        continue;
      }
      if (commonPrefixOpt.isEmpty() || !entry.getKey().startsWith(commonPrefixOpt.get())) {
        return nextMarker;
      }
    }

    return null;
  }

  static S3Object fetchLatestObject(String key, ObjectMetadata objectMetadata) {
    VersionedObjectMetadata latest = objectMetadata.getLatest();
    S3Object.Builder object = S3Object.builder()
        .key(key)
        .size(latest.getSize())
        .lastModified(Instant.ofEpochMilli(latest.getCreationDate()))
        .etag(S3ObjectUtils.quoteEtag(latest.getEtag()))
        .owner(Owner.DEFAULT_OWNER)
        .storageClass(SystemMetadata.storageClassOf(latest.getSystemMetadata()));
    if (Objects.nonNull(latest.getChecksum())) {
      object.checkSumAlgorithm(latest.getChecksum().getAlgorithm())
          .checksumType(latest.getChecksum().getType());
    }
    // The controller drops it unless x-amz-optional-object-attributes asks for it.
    Long restoreExpiryDate = RestoreObjectService.activeRestoreExpiryDate(latest);
    if (Objects.nonNull(restoreExpiryDate)) {
      object.restoreStatus(new RestoreStatus(false, Instant.ofEpochMilli(restoreExpiryDate)));
    }
    return object.build();
  }

  /**
   * @param encodePrefix ListObjectsV2 encodes the Prefix, ListObjects (v1) doesn't.
   */
  static void encodeIfNeeded(ListObjectsAns listObjectsAns, String encodingType, boolean encodePrefix) {
    if (Objects.isNull(encodingType)) {
      return;
    }

    if (!"url".equalsIgnoreCase(encodingType)) {
      throw new LocalS3InvalidArgumentException("encoding-type", encodingType, "Invalid Encoding Method specified in Request");
    }

    listObjectsAns.setEncodingType(encodingType);
    listObjectsAns.setObjects(listObjectsAns.getObjects().stream()
      .map(object -> object.withKey(S3ObjectUtils.urlEncodeEscapeSlash(object.key())))
      .toList());
    List<String> encodedPrefixes = new ArrayList<>(listObjectsAns.getCommonPrefixes().size());
    listObjectsAns.getCommonPrefixes().forEach(commonPrefix ->
      encodedPrefixes.add(S3ObjectUtils.urlEncodeEscapeSlash(commonPrefix)));
    listObjectsAns.setCommonPrefixes(encodedPrefixes);
    listObjectsAns.setDelimiter(S3ObjectUtils.urlEncodeEscapeSlash(listObjectsAns.getDelimiter()));
    if (encodePrefix) {
      listObjectsAns.setPrefix(S3ObjectUtils.urlEncodeEscapeSlash(listObjectsAns.getPrefix()));
    }
    listObjectsAns.setMarker(S3ObjectUtils.urlEncodeEscapeSlash(listObjectsAns.getMarker()));
    listObjectsAns.setNextMarker(S3ObjectUtils.urlEncodeEscapeSlash(listObjectsAns.getNextMarker().orElse(null)));
  }

}
