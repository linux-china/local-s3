package com.robothy.s3.core.service;

import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.ListMultipartUploadsAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.util.S3ObjectUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;

/**
 * Pages through the uploads like {@linkplain ListObjectVersionsService} pages through the versions: the
 * {@code (key-marker, upload-id-marker)} pair is a cursor into the uploads of a key, and the common prefixes are taken
 * after the prefix of the request.
 */
public interface ListMultipartUploadsService extends LocalS3MetadataApplicable {

  default ListMultipartUploadsAns listMultipartUploads(
      String bucketName,
      String delimiter,
      String encodingType,
      String keyMarker,
      int maxUploads,
      String prefix,
      String uploadIdMarker
  ) {
    return withBucketReadLock(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      ListMultipartUploadsAns listMultipartUploadsAns = listMultipartUploads(bucketMetadata.getUploads(), delimiter,
          keyMarker, maxUploads, prefix, uploadIdMarker);
      listMultipartUploadsAns.setBucket(bucketName);
      return encodeResultIfNeeded(listMultipartUploadsAns, encodingType);
    });
  }

  static ListMultipartUploadsAns listMultipartUploads(
      NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads,
      String delimiter,
      String keyMarker,
      int maxUploads,
      String prefix,
      String uploadIdMarker
  ) {
    // An empty marker, which some clients send on their first request, is no marker.
    String startKey = Objects.isNull(keyMarker) || keyMarker.isEmpty() ? null : keyMarker;
    // Amazon S3 ignores an upload-id marker without a key marker.
    String startUploadId = Objects.isNull(startKey) || Objects.isNull(uploadIdMarker) || uploadIdMarker.isEmpty()
        ? null : uploadIdMarker;

    Page page = new Page(keyMarker, uploadIdMarker, prefix, delimiter, maxUploads);
    if (maxUploads <= 0) {
      return page.answer(false, null, null);
    }

    int prefixLen = Objects.isNull(prefix) ? 0 : prefix.length();
    NavigableMap<String, NavigableMap<String, UploadMetadata>> candidates = uploads;
    String commonPrefixOfKeyMarker = null;
    Fetched fetchedOfKeyMarker = null;
    if (Objects.nonNull(startKey)) {
      boolean keyMarkerHasPrefix = Objects.isNull(prefix) || startKey.startsWith(prefix);
      int delimiterIndex;
      if (keyMarkerHasPrefix && Objects.nonNull(delimiter)
          && -1 != (delimiterIndex = startKey.indexOf(delimiter, prefixLen))) {
        // The key marker is in a common prefix, which the previous page listed.
        commonPrefixOfKeyMarker = startKey.substring(0, delimiterIndex + delimiter.length());
      } else if (keyMarkerHasPrefix && Objects.nonNull(startUploadId)) {
        NavigableMap<String, UploadMetadata> uploadsOfKeyMarker = uploads.get(startKey);
        if (Objects.nonNull(uploadsOfKeyMarker)) {
          fetchedOfKeyMarker = page.fetch(startKey, uploadsOfKeyMarker.tailMap(startUploadId, false));
        }
      }
      candidates = candidates.tailMap(startKey, false);
    }
    // Only the keys with the prefix are visited, rather than every key after the marker.
    candidates = ListItemUtils.filterByPrefix(candidates, prefix);
    if (Objects.nonNull(commonPrefixOfKeyMarker)) {
      candidates = ListItemUtils.skipPrefix(candidates, commonPrefixOfKeyMarker);
    }

    if (page.isFull()) {
      // The page is full of the uploads of the key marker; it is truncated only if something is left to list.
      return page.answer(fetchedOfKeyMarker.truncated() || !candidates.isEmpty(), startKey,
          fetchedOfKeyMarker.lastUploadId());
    }

    // Once a key rolls up into a common prefix, the other keys of the prefix are skipped with a single lookup, so that
    // each common prefix is listed once, and a page takes O(page size * log N) steps however many keys it rolls up.
    Iterator<Map.Entry<String, NavigableMap<String, UploadMetadata>>> entries = candidates.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<String, NavigableMap<String, UploadMetadata>> entry = entries.next();
      String key = entry.getKey();
      String nextKeyMarker;
      String nextUploadIdMarker;
      boolean truncatedInKey;
      int delimiterIndex;
      if (Objects.nonNull(delimiter) && -1 != (delimiterIndex = key.indexOf(delimiter, prefixLen))) {
        String commonPrefix = key.substring(0, delimiterIndex + delimiter.length());
        page.commonPrefixes.add(commonPrefix);
        nextKeyMarker = commonPrefix;
        nextUploadIdMarker = null;
        truncatedInKey = false;
        entries = ListItemUtils.skipPrefix(candidates, commonPrefix).entrySet().iterator();
      } else {
        if (entry.getValue().isEmpty()) {
          continue;
        }
        Fetched fetched = page.fetch(key, entry.getValue());
        nextKeyMarker = key;
        nextUploadIdMarker = fetched.lastUploadId();
        truncatedInKey = fetched.truncated();
      }

      if (page.isFull()) {
        return page.answer(truncatedInKey || entries.hasNext(), nextKeyMarker, nextUploadIdMarker);
      }
    }

    return page.answer(false, null, null);
  }

  /**
   * @param lastUploadId the ID of the last upload listed, {@code null} if none was.
   * @param truncated    whether uploads of the key are left that didn't fit in the page.
   */
  record Fetched(String lastUploadId, boolean truncated) {
  }

  /**
   * The uploads and common prefixes of a page, and the parameters of the request that it echoes.
   */
  final class Page {

    private final String keyMarker;
    private final String uploadIdMarker;
    private final String prefix;
    private final String delimiter;
    private final int maxUploads;
    private final List<ListMultipartUploadsAns.UploadItem> uploads = new ArrayList<>();
    private final List<String> commonPrefixes = new ArrayList<>();

    private Page(String keyMarker, String uploadIdMarker, String prefix, String delimiter, int maxUploads) {
      this.keyMarker = keyMarker;
      this.uploadIdMarker = uploadIdMarker;
      this.prefix = prefix;
      this.delimiter = delimiter;
      this.maxUploads = maxUploads;
    }

    private boolean isFull() {
      return uploads.size() + commonPrefixes.size() >= maxUploads;
    }

    private Fetched fetch(String key, NavigableMap<String, UploadMetadata> uploadsOfKey) {
      String lastUploadId = null;
      for (Map.Entry<String, UploadMetadata> uploadEntry : uploadsOfKey.entrySet()) {
        if (isFull()) {
          return new Fetched(lastUploadId, true);
        }
        UploadMetadata upload = uploadEntry.getValue();
        uploads.add(ListMultipartUploadsAns.UploadItem.builder()
            .key(key)
            .uploadId(uploadEntry.getKey())
            .initiated(upload.getCreateDate())
            .storageClass(SystemMetadata.storageClassOf(upload.getSystemMetadata()))
            .build());
        lastUploadId = uploadEntry.getKey();
      }
      return new Fetched(lastUploadId, false);
    }

    // A page that happens to end with the last upload carries no markers, so that no empty page follows it.
    private ListMultipartUploadsAns answer(boolean truncated, String nextKeyMarker, String nextUploadIdMarker) {
      return ListMultipartUploadsAns.builder()
          .keyMarker(keyMarker)
          .uploadIdMarker(uploadIdMarker)
          .prefix(prefix)
          .delimiter(delimiter)
          .maxUploads(maxUploads)
          .truncated(truncated)
          .nextKeyMarker(truncated ? nextKeyMarker : null)
          .nextUploadIdMarker(truncated ? nextUploadIdMarker : null)
          .uploads(uploads)
          .commonPrefixes(commonPrefixes)
          .build();
    }
  }

  static ListMultipartUploadsAns encodeResultIfNeeded(ListMultipartUploadsAns result, String encodingType) {
    if (Objects.isNull(encodingType)) {
      return result;
    }

    if (!"url".equalsIgnoreCase(encodingType)) {
      throw new LocalS3InvalidArgumentException("encoding-type", encodingType, "Invalid Encoding Method specified in Request");
    }

    result.setEncodingType(encodingType);
    result.getUploads().forEach(uploadItem -> {
      uploadItem.setKey(S3ObjectUtils.urlEncodeEscapeSlash(uploadItem.getKey()));
    });

    List<String> encodedPrefixes = new ArrayList<>(result.getCommonPrefixes().size());
    result.getCommonPrefixes().forEach(commonPrefix ->
        encodedPrefixes.add(S3ObjectUtils.urlEncodeEscapeSlash(commonPrefix)));
    result.setCommonPrefixes(encodedPrefixes);
    result.setDelimiter(S3ObjectUtils.urlEncodeEscapeSlash(result.getDelimiter()));
    result.setPrefix(S3ObjectUtils.urlEncodeEscapeSlash(result.getPrefix()));
    result.setKeyMarker(S3ObjectUtils.urlEncodeEscapeSlash(result.getKeyMarker()));
    result.setNextKeyMarker(S3ObjectUtils.urlEncodeEscapeSlash(result.getNextKeyMarker()));
    result.setUploadIdMarker(S3ObjectUtils.urlEncodeEscapeSlash(result.getUploadIdMarker()));
    result.setNextUploadIdMarker(S3ObjectUtils.urlEncodeEscapeSlash(result.getNextUploadIdMarker()));
    return result;
  }

}
