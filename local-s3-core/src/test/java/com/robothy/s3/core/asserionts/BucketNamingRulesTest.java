package com.robothy.s3.core.asserionts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.InvalidBucketNameException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BucketNamingRulesTest {

  @ParameterizedTest
  @ValueSource(strings = {"abc", "my-bucket", "my.bucket.2024", "1bucket", "bucket1", "xn-bucket",
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
  void acceptsNamesFollowingTheRules(String bucketName) {
    assertEquals(bucketName, BucketAssertions.assertBucketNameFollowsNamingRules(bucketName));
  }

  @ParameterizedTest
  @CsvSource({
      "ab, between 3 and 63",
      "MyBucket, lowercase",
      "my_bucket, lowercase",
      "-bucket, begin and end",
      "bucket-, begin and end",
      ".bucket, begin and end",
      "my..bucket, adjacent periods",
      "192.168.5.4, IP address",
      "xn--bucket, 'xn--'",
      "sthree-bucket, 'sthree-'",
      "amzn-s3-demo-bucket, 'amzn-s3-demo-'",
      "bucket-s3alias, '-s3alias'",
      "bucket--ol-s3, '--ol-s3'",
      "bucket.mrap, '.mrap'",
      "bucket--x-s3, '--x-s3'",
      "bucket--table-s3, '--table-s3'",
  })
  void rejectsNamesBreakingTheRules(String bucketName, String rule) {
    InvalidBucketNameException e = assertThrows(InvalidBucketNameException.class,
        () -> BucketAssertions.assertBucketNameFollowsNamingRules(bucketName));
    assertTrue(e.getMessage().contains(rule), e.getMessage());
    assertTrue(e.getMessage().contains(bucketName), e.getMessage());
  }

  @Test
  void rejectsTooLongAndBlankNames() {
    String tooLong = "a".repeat(64);
    assertThrows(InvalidBucketNameException.class, () -> BucketAssertions.assertBucketNameFollowsNamingRules(tooLong));
    assertThrows(InvalidBucketNameException.class, () -> BucketAssertions.assertBucketNameFollowsNamingRules(" "));
    assertThrows(InvalidBucketNameException.class, () -> BucketAssertions.assertBucketNameFollowsNamingRules(null));
  }

}
