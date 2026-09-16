package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortIncompleteMultipartUpload;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleAndOperator;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoncurrentVersionTransition;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.TransitionDefaultMinimumObjectSize;
import software.amazon.awssdk.services.s3.model.TransitionStorageClass;

/**
 * The lifecycle configuration of a bucket is stored and read back through the AWS SDK, but never applied.
 */
class BucketLifecycleIntegrationTest {

  private static final String BUCKET = "lifecycle-bucket";

  private static final List<LifecycleRule> RULES = List.of(
      LifecycleRule.builder()
          .id("expire-tmp")
          .filter(LifecycleRuleFilter.builder().prefix("tmp/").build())
          .status(ExpirationStatus.ENABLED)
          .expiration(LifecycleExpiration.builder().days(1).build())
          .abortIncompleteMultipartUpload(AbortIncompleteMultipartUpload.builder().daysAfterInitiation(1).build())
          .build(),
      LifecycleRule.builder()
          .id("archive-logs")
          .filter(LifecycleRuleFilter.builder().and(LifecycleRuleAndOperator.builder()
              .prefix("logs/")
              .tags(Tag.builder().key("tier").value("cold").build())
              .objectSizeGreaterThan(1024L)
              .build()).build())
          .status(ExpirationStatus.DISABLED)
          .noncurrentVersionTransitions(NoncurrentVersionTransition.builder()
              .noncurrentDays(30).storageClass(TransitionStorageClass.GLACIER).build())
          .build());

  @Test
  @LocalS3(buckets = BUCKET)
  void putsGetsAndDeletesTheLifecycleConfiguration(S3Client s3) {
    S3Exception missing = assertThrows(S3Exception.class,
        () -> s3.getBucketLifecycleConfiguration(b -> b.bucket(BUCKET)));
    assertEquals(404, missing.statusCode());
    assertEquals("NoSuchLifecycleConfiguration", missing.awsErrorDetails().errorCode());

    PutBucketLifecycleConfigurationResponse put = s3.putBucketLifecycleConfiguration(b -> b.bucket(BUCKET)
        .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(RULES).build()));
    assertEquals(TransitionDefaultMinimumObjectSize.ALL_STORAGE_CLASSES_128_K,
        put.transitionDefaultMinimumObjectSize());

    GetBucketLifecycleConfigurationResponse get = s3.getBucketLifecycleConfiguration(b -> b.bucket(BUCKET));
    assertEquals(RULES, get.rules());
    assertEquals(TransitionDefaultMinimumObjectSize.ALL_STORAGE_CLASSES_128_K,
        get.transitionDefaultMinimumObjectSize());

    s3.putBucketLifecycleConfiguration(b -> b.bucket(BUCKET)
        .transitionDefaultMinimumObjectSize(TransitionDefaultMinimumObjectSize.VARIES_BY_STORAGE_CLASS)
        .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(RULES.get(0)).build()));
    get = s3.getBucketLifecycleConfiguration(b -> b.bucket(BUCKET));
    assertEquals(List.of(RULES.get(0)), get.rules());
    assertEquals(TransitionDefaultMinimumObjectSize.VARIES_BY_STORAGE_CLASS, get.transitionDefaultMinimumObjectSize());

    assertEquals(204, s3.deleteBucketLifecycle(b -> b.bucket(BUCKET)).sdkHttpResponse().statusCode());
    assertEquals(404, assertThrows(S3Exception.class,
        () -> s3.getBucketLifecycleConfiguration(b -> b.bucket(BUCKET))).statusCode());
  }

  /**
   * A rule that has no action is rejected, as Amazon S3 rejects it, and the previous configuration is kept.
   */
  @Test
  @LocalS3(buckets = BUCKET)
  void rejectsARuleWithoutAnAction(S3Client s3) {
    s3.putBucketLifecycleConfiguration(b -> b.bucket(BUCKET)
        .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(RULES).build()));

    S3Exception thrown = assertThrows(S3Exception.class, () -> s3.putBucketLifecycleConfiguration(b -> b.bucket(BUCKET)
        .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(LifecycleRule.builder()
            .id("no-action")
            .filter(LifecycleRuleFilter.builder().prefix("a/").build())
            .status(ExpirationStatus.ENABLED)
            .build()).build())));
    assertEquals(400, thrown.statusCode());
    assertEquals("InvalidRequest", thrown.awsErrorDetails().errorCode());
    assertEquals(RULES, s3.getBucketLifecycleConfiguration(b -> b.bucket(BUCKET)).rules());
  }

  @Test
  @LocalS3
  void aBucketThatDoesNotExistHasNoLifecycleConfiguration(S3Client s3) {
    S3Exception thrown = assertThrows(S3Exception.class,
        () -> s3.getBucketLifecycleConfiguration(b -> b.bucket("no-such-bucket")));
    assertEquals("NoSuchBucket", thrown.awsErrorDetails().errorCode());
  }

  /**
   * The rules are stored, not applied: an object that a rule expires stays.
   */
  @Test
  @LocalS3(buckets = BUCKET)
  void doesNotApplyTheRules(S3Client s3) {
    s3.putObject(b -> b.bucket(BUCKET).key("tmp/a.txt"), RequestBody.fromString("a"));
    s3.putBucketLifecycleConfiguration(b -> b.bucket(BUCKET).lifecycleConfiguration(
        BucketLifecycleConfiguration.builder().rules(LifecycleRule.builder()
            .id("expire-now")
            .filter(LifecycleRuleFilter.builder().prefix("").build())
            .status(ExpirationStatus.ENABLED)
            .expiration(LifecycleExpiration.builder().days(1).build())
            .build()).build()));

    assertEquals("a", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("tmp/a.txt")).asUtf8String());
  }

}
