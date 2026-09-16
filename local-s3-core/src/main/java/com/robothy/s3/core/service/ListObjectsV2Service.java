package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.answers.ListObjectsAns;
import com.robothy.s3.core.model.answers.ListObjectsV2Ans;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.ContinuationTokenUtils;
import java.util.Objects;
import com.robothy.s3.core.util.Strings;

public interface ListObjectsV2Service extends ListObjectsService {

    /**
     * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html">ListObjectsV2</a>
     *
     * @param bucket            the bucket to list objects.
     * @param continuationToken the token indicating where the returned results should begin.
     * @param delimiter         the delimiter for condensing common prefixes in the returned listing results.
     * @param encodingType      the encoding method for keys in the returned listing results.
     * @param fetchOwner        whether to fetch the owner of the object.
     * @param maxKeys           the maximum objects to return.
     * @param prefix            the prefix restricting what keys will be listed.
     * @param startAfter        the key indicating where the returned results should begin.
     * @return a listing of objects from the specified bucket.
     */
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
              calculateNextContinuationToken(listObjectsAns.getNextMarker().orElse(null), bucketMetadata));
          ListObjectsService.encodeIfNeeded(listObjectsAns, encodingType);
          ListObjectsV2Ans listObjectsV2Ans = ListObjectsV2Ans.builder()
              .continuationToken(continuationToken)
              .delimiter(listObjectsAns.getDelimiter())
              .encodingType(listObjectsAns.getEncodingType())
              .isTruncated(listObjectsAns.isTruncated())
              .keyCount(listObjectsAns.getObjects().size() + listObjectsAns.getCommonPrefixes().size())
              .maxKeys(listObjectsAns.getMaxKeys())
              .prefix(listObjectsAns.getPrefix())
              .startAfter(Strings.isBlank(startAfter) || Strings.isNotBlank(continuationToken) ? null : startAfter)
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

    static String calculateNextContinuationToken(String nextMarker, BucketMetadata bucketMetadata) {
        if (Objects.isNull(nextMarker)) {
            return null;
        }

        return bucketMetadata.getObjectMap().floorKey(nextMarker + Character.MAX_VALUE);
    }

    static void removeOwner(ListObjectsV2Ans listObjectsV2Ans) {
        listObjectsV2Ans.getObjects().forEach(s3Object -> s3Object.setOwner(null));
    }

}
