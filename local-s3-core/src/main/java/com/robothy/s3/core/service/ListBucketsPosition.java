package com.robothy.s3.core.service;

import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.ContinuationTokenUtils;

/**
 * The position of a bucket in the order of {@code ListBuckets}, which a continuation token carries.
 */
record ListBucketsPosition(long creationDate, String name) {

  static ListBucketsPosition of(Bucket bucket) {
    return new ListBucketsPosition(bucket.getCreationDate(), bucket.getName());
  }

  /**
   * Whether a bucket follows this position.
   */
  boolean isBefore(BucketMetadata bucket) {
    int byDate = Long.compare(creationDate, bucket.getCreationDate());
    return byDate < 0 || (byDate == 0 && name.compareTo(bucket.getBucketName()) < 0);
  }

  String encode() {
    return ContinuationTokenUtils.encode(creationDate + ":" + name);
  }

  static ListBucketsPosition decode(String token) {
    String position = ContinuationTokenUtils.decode(token);
    if (position == null) {
      return null;
    }
    int separator = position.indexOf(':');
    try {
      return new ListBucketsPosition(Long.parseLong(position.substring(0, separator)), position.substring(separator + 1));
    } catch (RuntimeException e) {
      throw new LocalS3InvalidArgumentException("continuation-token", token,
          "The continuation token provided is incorrect");
    }
  }

}
