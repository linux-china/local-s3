package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.answers.ListObjectsAns;
import com.robothy.s3.core.model.answers.ListObjectsV2Ans;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.ContinuationTokenUtils;
import com.robothy.s3.core.util.S3ObjectUtils;
import java.util.Objects;
import com.robothy.s3.core.util.Strings;

public interface ListObjectsV2Service extends ListObjectsService {

    default ListObjectsV2Ans listObjectsV2(String bucket, String continuationToken,
                                           String delimiter, String encodingType,
                                           boolean fetchOwner, int maxKeys,
                                           String prefix, String startAfter) {
      return withBucketReadLock(bucket, () -> {
          BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);

          // The continuation token is opaque to the client; it carries the key that the listing continues at.
          String marker = Strings.isNotBlank(continuationToken)
              ? ContinuationTokenUtils.decode(continuationToken) : startAfter;
          // The listing is encoded only once the continuation token is computed: the token must carry the key that
          // the listing continues at, not its URL encoding, which sorts differently. E.g. "a%3D1/" sorts before
          // "a=1/", so a token computed from the encoded marker of "a=1/..." resumed the listing before its first
          // page, and a client that follows the tokens, e.g. the glob of DuckDB, never got to the end.
          ListObjectsAns listObjectsAns = listObjects(bucket, delimiter, null, marker, maxKeys, prefix);
          String nextContinuationToken = ContinuationTokenUtils.encode(
              calculateNextContinuationToken(listObjectsAns.getNextMarker().orElse(null), bucketMetadata,
                  Objects.toString(prefix, ""), delimiter));
          ListObjectsService.encodeIfNeeded(listObjectsAns, encodingType, true);
          ListObjectsV2Ans listObjectsV2Ans = ListObjectsV2Ans.builder()
              .continuationToken(continuationToken)
              .delimiter(listObjectsAns.getDelimiter())
              .encodingType(listObjectsAns.getEncodingType())
              .isTruncated(listObjectsAns.isTruncated())
              .keyCount(listObjectsAns.getObjects().size() + listObjectsAns.getCommonPrefixes().size())
              .maxKeys(listObjectsAns.getMaxKeys())
              .prefix(listObjectsAns.getPrefix())
              // Echoed like Amazon S3 does, also next to a continuation token, which the listing continues from
              // instead, and also when it is whitespace alone, e.g. "\n"; encoded like the keys.
              .startAfter(Objects.isNull(startAfter) || startAfter.isEmpty() ? null
                  : Objects.isNull(encodingType) ? startAfter : S3ObjectUtils.urlEncodeEscapeSlash(startAfter))
              .objects(listObjectsAns.getObjects())
              .commonPrefixes(listObjectsAns.getCommonPrefixes())
              .nextContinuationToken(nextContinuationToken)
              .build();

          if (!fetchOwner) {
              removeOwner(listObjectsV2Ans);
          }
          return listObjectsV2Ans;
      });
    }

    // The last key that a common prefix rolls up, rather than the last key that starts with an object's key, which
    // would skip the keys that continue it, e.g. data.parquet.crc after data.parquet.
    static String calculateNextContinuationToken(String nextMarker, BucketMetadata bucketMetadata,
                                                 String effectivePrefix, String delimiter) {
        if (Objects.isNull(nextMarker)) {
            return null;
        }
        if (ListItemUtils.commonPrefix(nextMarker, effectivePrefix, delimiter).isEmpty()) {
            return nextMarker;
        }

        return ListItemUtils.lastKeyNotAfterPrefix(bucketMetadata.getObjectMap(), nextMarker);
    }

    static void removeOwner(ListObjectsV2Ans listObjectsV2Ans) {
        listObjectsV2Ans.setObjects(listObjectsV2Ans.getObjects().stream()
            .map(s3Object -> s3Object.withOwner(null))
            .toList());
    }

}
