package com.robothy.s3.core.model.answers;

import com.robothy.s3.core.model.Bucket;
import java.util.List;

/**
 * A page of buckets that {@code ListBuckets} answers with.
 *
 * @param buckets the buckets of the page, in the order they were created.
 * @param continuationToken continues the listing after the page; {@code null} if the page is the last one.
 */
public record ListBucketsAns(List<Bucket> buckets, String continuationToken) {

  public ListBucketsAns {
    buckets = List.copyOf(buckets);
  }

}
