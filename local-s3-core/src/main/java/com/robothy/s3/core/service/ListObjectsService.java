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
import com.robothy.s3.datatypes.response.S3Object;

import java.time.Instant;
import java.util.*;

/**
 * Algorithm implementation of list objects.
 */
public interface ListObjectsService extends LocalS3MetadataApplicable {

  /**
   * List objects with options.
   *
   * @param bucket       the bucket to list objects.
   * @param delimiter    the delimiter for condensing common prefixes in the returned listing results.
   * @param encodingType the encoding method for keys in the returned listing results.
   * @param marker       the marker indicating where the returned results should begin.
   * @param maxKeys      the maximum objects to return.
   * @param prefix       the prefix restricting what keys will be listed.
   * @return a listing of objects from the specified bucket.
   */
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
   * List the objects and common prefixes of a page, in the order of their keys.
   *
   * <p>Once a key rolls up into a common prefix, the other keys of the prefix are skipped with a single lookup, see
   * {@linkplain ListItemUtils#skipPrefix}, so that a page takes O(page size &times; log N) steps however many keys its
   * common prefixes roll up, rather than a step per key. Only the objects whose latest version is a delete marker are
   * stepped over one by one, as a common prefix is only listed if it rolls up an object that isn't deleted; that is
   * answered by {@linkplain ObjectMetadataRef#isLatestDeleted()}, without reading their metadata.
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

    // The entries are iterated rather than the keys, so that each object is found once instead of looked up again.
    Iterator<Map.Entry<String, ObjectMetadataRef>> entries = filteredObjects.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<String, ObjectMetadataRef> entry = entries.next();
      String key = entry.getKey();
      // Only the objects of the page have their metadata read; the deleted ones are stepped over by what their
      // reference knows, so that a bucket full of delete markers isn't read from the store key by key.
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
   * The marker of the next page, after the last item of a full page.
   *
   * @param currentKey the key of the last item.
   * @param entries the entries after the last item; after its common prefix, if the last item is one.
   * @return the key of the last item, or its common prefix; {@code null} if nothing is left to list.
   */
  static String calculateNextMarker(String currentKey, Iterator<Map.Entry<String, ObjectMetadataRef>> entries,
          String effectivePrefix, String delimiter) {

    Optional<String> commonPrefixOpt = ListItemUtils.commonPrefix(currentKey, effectivePrefix, delimiter);
    if (commonPrefixOpt.isEmpty()) {
      return entries.hasNext() ? currentKey : null;
    }

    String commonPrefix = commonPrefixOpt.get();
    while (entries.hasNext()) {
      Map.Entry<String, ObjectMetadataRef> entry = entries.next();
      if (!entry.getKey().startsWith(commonPrefix) && !entry.getValue().isLatestDeleted()) {
        return commonPrefix;
      }
    }

    return null;
  }

  static S3Object fetchLatestObject(String key, ObjectMetadata objectMetadata) {
    VersionedObjectMetadata latest = objectMetadata.getLatest();
    S3Object object = new S3Object();
    object.setKey(key);
    object.setSize(latest.getSize());
    object.setLastModified(Instant.ofEpochMilli(latest.getCreationDate()));
    object.setEtag(S3ObjectUtils.quoteEtag(latest.getEtag()));
    object.setOwner(Owner.DEFAULT_OWNER);
    object.setStorageClass(SystemMetadata.storageClassOf(latest.getSystemMetadata()));
    if (Objects.nonNull(latest.getChecksum())) {
      object.setCheckSumAlgorithm(latest.getChecksum().getAlgorithm());
      object.setChecksumType(latest.getChecksum().getType());
    }
    return object;
  }

  /**
   * Encode the keys of a listing if {@code encodingType} asks for it.
   *
   * @param encodePrefix whether the Prefix is encoded too: it is by ListObjectsV2, and not by ListObjects (v1).
   */
  static void encodeIfNeeded(ListObjectsAns listObjectsAns, String encodingType, boolean encodePrefix) {
    if (Objects.isNull(encodingType)) {
      return;
    }

    if (!"url".equalsIgnoreCase(encodingType)) {
      throw new LocalS3InvalidArgumentException("encoding-type", encodingType, "Invalid Encoding Method specified in Request");
    }

    listObjectsAns.setEncodingType(encodingType);
    listObjectsAns.getObjects()
      .forEach(object -> object.setKey(S3ObjectUtils.urlEncodeEscapeSlash(object.getKey())));
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
