package com.robothy.s3.rest.model.response;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.robothy.s3.datatypes.enums.StorageClass;
import com.robothy.s3.datatypes.response.S3Object;
import com.robothy.s3.rest.utils.XmlUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ListBucketV2ResultTest {

  @Test
  void elementsFollowAwsOrder() {
    ListBucketV2Result result = ListBucketV2Result.builder()
        .name("bucket")
        .prefix("dir/")
        .startAfter("dir/a")
        .continuationToken("c1")
        .nextContinuationToken("c2")
        .keyCount(1)
        .maxKeys(1)
        .delimiter("/")
        .encodingType("url")
        .isTruncated(true)
        .contents(List.of(S3Object.builder().key("dir/b").etag("\"e\"").size(1)
            .lastModified(Instant.EPOCH).storageClass(StorageClass.STANDARD).build()))
        .commonPrefixes(List.of(new CommonPrefix("dir/c/")))
        .build();

    String xml = XmlUtils.toXml(result);
    assertEquals(List.of("ListBucketResult", "Name", "Prefix", "StartAfter", "ContinuationToken",
        "NextContinuationToken", "KeyCount", "MaxKeys", "Delimiter", "EncodingType", "IsTruncated",
        "Contents", "Key", "LastModified", "ETag", "Size", "StorageClass",
        "CommonPrefixes", "Prefix"), startTags(xml));
  }

  private static List<String> startTags(String xml) {
    Matcher matcher = Pattern.compile("<([A-Za-z]+)[\\s>/]").matcher(xml);
    List<String> tags = new ArrayList<>();
    while (matcher.find()) {
      tags.add(matcher.group(1));
    }
    return tags;
  }

}
