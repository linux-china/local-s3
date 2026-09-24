package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.*;
import com.robothy.s3.jupiter.LocalS3;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class BucketIntegrationTest {

  @Test
  @LocalS3
  void testCreateBucket(S3Client s3) {
    assertThrows(NoSuchBucketException.class, () -> s3.headBucket(HeadBucketRequest.builder().bucket("my-bucket").build()));
    CreateBucketRequest bucketRequest = CreateBucketRequest.builder()
        .bucket("my-bucket")
        .build();
    CreateBucketResponse bucket = s3.createBucket(bucketRequest);
    assertTrue(s3.headBucket(HeadBucketRequest.builder().bucket("my-bucket").build()).sdkHttpResponse().isSuccessful());

    assertThrows(NoSuchBucketException.class,
        () -> s3.headBucket(HeadBucketRequest.builder().bucket("bucket2").build()).sdkHttpResponse().isSuccessful());
    CreateBucketResponse bucket2 = s3.createBucket(CreateBucketRequest.builder().bucket("bucket2").build());
    assertTrue(s3.headBucket(HeadBucketRequest.builder().bucket("bucket2").build()).sdkHttpResponse().isSuccessful());

    HeadBucketResponse headBucketResult = s3.headBucket(HeadBucketRequest.builder().bucket("my-bucket").build());
    s3.deleteBucket(DeleteBucketRequest.builder().bucket("my-bucket").build());
    assertThrows(NoSuchBucketException.class, () -> 
        s3.headBucket(HeadBucketRequest.builder().bucket("my-bucket").build()));
  }

  @Test
  @LocalS3
  void testVersioningEnabled(S3Client s3) {
    s3.createBucket(CreateBucketRequest.builder().bucket("my-bucket").build());
    
    GetBucketVersioningResponse versioning = s3.getBucketVersioning(
        GetBucketVersioningRequest.builder().bucket("my-bucket").build());
    assertNull(versioning.status());

    s3.putBucketVersioning(PutBucketVersioningRequest.builder()
        .bucket("my-bucket")
        .versioningConfiguration(VersioningConfiguration.builder()
            .status(BucketVersioningStatus.ENABLED)
            .build())
        .build());
    
    versioning = s3.getBucketVersioning(
        GetBucketVersioningRequest.builder().bucket("my-bucket").build());
    assertEquals(BucketVersioningStatus.ENABLED, versioning.status());
  }

  @Test
  @LocalS3
  void testBucketTagging(S3Client s3) {
    s3.createBucket(CreateBucketRequest.builder().bucket("my-bucket").build());
    
    assertThrows(S3Exception.class, () -> 
        s3.getBucketTagging(GetBucketTaggingRequest.builder().bucket("my-bucket").build()));

    Map<String, String> tags = Map.of("Name", "Bob", "Profession", "Doctor");

    // Answered 204 No Content, like Amazon S3 answers it and DeleteBucketTagging.
    PutBucketTaggingResponse put = s3.putBucketTagging(PutBucketTaggingRequest.builder()
        .bucket("my-bucket")
        .tagging(Tagging.builder()
            .tagSet(tags.entrySet().stream()
                .map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build())
                .collect(Collectors.toList()))
            .build())
        .build());

    GetBucketTaggingResponse tagging = s3.getBucketTagging(
        GetBucketTaggingRequest.builder().bucket("my-bucket").build());
    assertEquals(2, tagging.tagSet().size());
    Set<String> keys = tagging.tagSet().stream().map(Tag::key).collect(Collectors.toSet());
    assertTrue(keys.contains("Name"));
    assertTrue(keys.contains("Profession"));
    assertEquals("Bob", tagging.tagSet().stream().filter(t -> t.key().equals("Name")).findFirst().get().value());
    assertEquals("Doctor", tagging.tagSet().stream().filter(t -> t.key().equals("Profession")).findFirst().get().value());
    assertEquals(204, put.sdkHttpResponse().statusCode());

    assertEquals(204, s3.deleteBucketTagging(b -> b.bucket("my-bucket")).sdkHttpResponse().statusCode());
  }

  @Test
  @LocalS3
  void bucketAcl(S3Client s3) {
    String bucketName = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    
    GetBucketAclResponse bucketAcl = s3.getBucketAcl(
        GetBucketAclRequest.builder().bucket(bucketName).build());
    Owner owner = bucketAcl.owner();
    assertNotNull(owner);
    assertEquals("LocalS3", owner.displayName());
    assertNotNull(owner.id());
    
    s3.putBucketAcl(PutBucketAclRequest.builder()
        .bucket(bucketName)
        .accessControlPolicy(AccessControlPolicy.builder()
            .owner(owner)
            .grants(Grant.builder()
                .grantee(Grantee.builder()
                    .id("123")
                    .type(Type.CANONICAL_USER)
                    .build())
                .permission(Permission.FULL_CONTROL)
                .build())
            .build())
        .build());
    
    GetBucketAclResponse updatedAcl = s3.getBucketAcl(
        GetBucketAclRequest.builder().bucket(bucketName).build());
    assertEquals(1, updatedAcl.grants().size());
  }

  @Test
  @LocalS3
  void bucketPolicy(S3Client s3) {
    String bucketName = "my-bucket";
    assertThrows(NoSuchBucketException.class, () ->
        s3.getBucketPolicy(GetBucketPolicyRequest.builder().bucket(bucketName).build()));

    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    
    String policyText = "Policy JSON";
    s3.putBucketPolicy(PutBucketPolicyRequest.builder()
        .bucket(bucketName)
        .policy(policyText)
        .build());

    GetBucketPolicyResponse policy = s3.getBucketPolicy(
        GetBucketPolicyRequest.builder().bucket(bucketName).build());
    assertEquals(policyText, policy.policy());

    s3.deleteBucketPolicy(DeleteBucketPolicyRequest.builder().bucket(bucketName).build());
  }

  @Test
  @LocalS3
  void testBucketReplication(S3Client s3) {
    String bucketName = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    ReplicationRule rule = ReplicationRule.builder()
        .status(ReplicationRuleStatus.ENABLED)
        .priority(1)
        .destination(Destination.builder()
            .bucket("arn:aws:s3:::exampletargetbucket")
            .build())
        .build();

    s3.putBucketReplication(PutBucketReplicationRequest.builder()
        .bucket(bucketName)
        .replicationConfiguration(ReplicationConfiguration.builder()
            .rules(rule)
            .role("arn:aws:iam::123456789012:role/roleName")
            .build())
        .build());

    GetBucketReplicationResponse replication = s3.getBucketReplication(
        GetBucketReplicationRequest.builder().bucket(bucketName).build());
    assertEquals(1, replication.replicationConfiguration().rules().size());

    s3.deleteBucketReplication(DeleteBucketReplicationRequest.builder()
        .bucket(bucketName)
        .build());
  }

  @Test
  @LocalS3
  void testBucketNotificationConfiguration(S3Client s3) {
    String bucketName = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    // A bucket that was never configured has an empty configuration rather than none.
    GetBucketNotificationConfigurationResponse empty = s3.getBucketNotificationConfiguration(
        GetBucketNotificationConfigurationRequest.builder().bucket(bucketName).build());
    assertTrue(empty.queueConfigurations().isEmpty());
    assertTrue(empty.topicConfigurations().isEmpty());
    assertTrue(empty.lambdaFunctionConfigurations().isEmpty());
    assertNull(empty.eventBridgeConfiguration());

    // The destinations don't exist; LocalS3 stores the configuration without checking them.
    s3.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
        .bucket(bucketName)
        .skipDestinationValidation(true)
        .notificationConfiguration(NotificationConfiguration.builder()
            .queueConfigurations(QueueConfiguration.builder()
                .id("uploads")
                .queueArn("arn:aws:sqs:us-east-1:123456789012:uploads")
                .events(Event.S3_OBJECT_CREATED)
                .filter(NotificationConfigurationFilter.builder()
                    .key(S3KeyFilter.builder()
                        .filterRules(FilterRule.builder().name(FilterRuleName.PREFIX).value("incoming/").build())
                        .build())
                    .build())
                .build())
            .topicConfigurations(TopicConfiguration.builder()
                .topicArn("arn:aws:sns:us-east-1:123456789012:deletes")
                .events(Event.S3_OBJECT_REMOVED)
                .build())
            .lambdaFunctionConfigurations(LambdaFunctionConfiguration.builder()
                .lambdaFunctionArn("arn:aws:lambda:us-east-1:123456789012:function:thumbnail")
                .events(Event.S3_OBJECT_CREATED_PUT)
                .build())
            .eventBridgeConfiguration(EventBridgeConfiguration.builder().build())
            .build())
        .build());

    GetBucketNotificationConfigurationResponse configured = s3.getBucketNotificationConfiguration(
        GetBucketNotificationConfigurationRequest.builder().bucket(bucketName).build());
    assertEquals(1, configured.queueConfigurations().size());
    QueueConfiguration queue = configured.queueConfigurations().get(0);
    assertEquals("uploads", queue.id());
    assertEquals("arn:aws:sqs:us-east-1:123456789012:uploads", queue.queueArn());
    assertEquals(List.of(Event.S3_OBJECT_CREATED), queue.events());
    assertEquals("incoming/", queue.filter().key().filterRules().get(0).value());
    assertEquals("arn:aws:sns:us-east-1:123456789012:deletes", configured.topicConfigurations().get(0).topicArn());
    assertEquals("arn:aws:lambda:us-east-1:123456789012:function:thumbnail",
        configured.lambdaFunctionConfigurations().get(0).lambdaFunctionArn());
    assertNotNull(configured.eventBridgeConfiguration());

    // An empty configuration turns notifications off.
    s3.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
        .bucket(bucketName)
        .notificationConfiguration(NotificationConfiguration.builder().build())
        .build());
    GetBucketNotificationConfigurationResponse cleared = s3.getBucketNotificationConfiguration(
        GetBucketNotificationConfigurationRequest.builder().bucket(bucketName).build());
    assertTrue(cleared.queueConfigurations().isEmpty());

    assertThrows(NoSuchBucketException.class, () -> s3.getBucketNotificationConfiguration(
        GetBucketNotificationConfigurationRequest.builder().bucket("no-such-bucket").build()));
  }

  @Test
  @LocalS3
  void testBucketConfigurationsThatAreStoredButNotApplied(S3Client s3) {
    String bucketName = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    // A new bucket answers what a new bucket of Amazon S3 answers, which is what Terraform reads when it refreshes one.
    assertNull(s3.getBucketAccelerateConfiguration(
        GetBucketAccelerateConfigurationRequest.builder().bucket(bucketName).build()).status());
    assertNull(s3.getBucketLogging(GetBucketLoggingRequest.builder().bucket(bucketName).build()).loggingEnabled());
    assertEquals(Payer.BUCKET_OWNER, s3.getBucketRequestPayment(
        GetBucketRequestPaymentRequest.builder().bucket(bucketName).build()).payer());
    assertEquals(ObjectOwnership.BUCKET_OWNER_ENFORCED, s3.getBucketOwnershipControls(
            GetBucketOwnershipControlsRequest.builder().bucket(bucketName).build())
        .ownershipControls().rules().get(0).objectOwnership());
    S3Exception noWebsite = assertThrows(S3Exception.class,
        () -> s3.getBucketWebsite(GetBucketWebsiteRequest.builder().bucket(bucketName).build()));
    assertEquals(404, noWebsite.statusCode());
    assertEquals("NoSuchWebsiteConfiguration", noWebsite.awsErrorDetails().errorCode());

    s3.putBucketAccelerateConfiguration(PutBucketAccelerateConfigurationRequest.builder()
        .bucket(bucketName)
        .accelerateConfiguration(AccelerateConfiguration.builder().status(BucketAccelerateStatus.ENABLED).build())
        .build());
    assertEquals(BucketAccelerateStatus.ENABLED, s3.getBucketAccelerateConfiguration(
        GetBucketAccelerateConfigurationRequest.builder().bucket(bucketName).build()).status());

    s3.putBucketLogging(PutBucketLoggingRequest.builder()
        .bucket(bucketName)
        .bucketLoggingStatus(BucketLoggingStatus.builder()
            .loggingEnabled(LoggingEnabled.builder().targetBucket("log-bucket").targetPrefix("logs/").build())
            .build())
        .build());
    LoggingEnabled logging = s3.getBucketLogging(GetBucketLoggingRequest.builder().bucket(bucketName).build())
        .loggingEnabled();
    assertEquals("log-bucket", logging.targetBucket());
    assertEquals("logs/", logging.targetPrefix());

    s3.putBucketRequestPayment(PutBucketRequestPaymentRequest.builder()
        .bucket(bucketName)
        .requestPaymentConfiguration(RequestPaymentConfiguration.builder().payer(Payer.REQUESTER).build())
        .build());
    assertEquals(Payer.REQUESTER, s3.getBucketRequestPayment(
        GetBucketRequestPaymentRequest.builder().bucket(bucketName).build()).payer());

    s3.putBucketWebsite(PutBucketWebsiteRequest.builder()
        .bucket(bucketName)
        .websiteConfiguration(WebsiteConfiguration.builder()
            .indexDocument(IndexDocument.builder().suffix("index.html").build())
            .errorDocument(ErrorDocument.builder().key("error.html").build())
            .build())
        .build());
    GetBucketWebsiteResponse website = s3.getBucketWebsite(GetBucketWebsiteRequest.builder().bucket(bucketName).build());
    assertEquals("index.html", website.indexDocument().suffix());
    assertEquals("error.html", website.errorDocument().key());
    s3.deleteBucketWebsite(DeleteBucketWebsiteRequest.builder().bucket(bucketName).build());
    assertThrows(S3Exception.class,
        () -> s3.getBucketWebsite(GetBucketWebsiteRequest.builder().bucket(bucketName).build()));

    s3.putBucketOwnershipControls(PutBucketOwnershipControlsRequest.builder()
        .bucket(bucketName)
        .ownershipControls(OwnershipControls.builder()
            .rules(OwnershipControlsRule.builder().objectOwnership(ObjectOwnership.OBJECT_WRITER).build())
            .build())
        .build());
    assertEquals(ObjectOwnership.OBJECT_WRITER, s3.getBucketOwnershipControls(
            GetBucketOwnershipControlsRequest.builder().bucket(bucketName).build())
        .ownershipControls().rules().get(0).objectOwnership());
    s3.deleteBucketOwnershipControls(DeleteBucketOwnershipControlsRequest.builder().bucket(bucketName).build());
    S3Exception noOwnershipControls = assertThrows(S3Exception.class, () -> s3.getBucketOwnershipControls(
        GetBucketOwnershipControlsRequest.builder().bucket(bucketName).build()));
    assertEquals("OwnershipControlsNotFoundError", noOwnershipControls.awsErrorDetails().errorCode());

    assertThrows(NoSuchBucketException.class, () -> s3.getBucketLogging(
        GetBucketLoggingRequest.builder().bucket("no-such-bucket").build()));
  }

  @Test
  @LocalS3
  void testBucketEncryption(S3Client s3) {
    String bucketName = "my-bucket";
    s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

    s3.putBucketEncryption(PutBucketEncryptionRequest.builder()
        .bucket(bucketName)
        .serverSideEncryptionConfiguration(ServerSideEncryptionConfiguration.builder()
            .rules(ServerSideEncryptionRule.builder()
                .applyServerSideEncryptionByDefault(ServerSideEncryptionByDefault.builder()
                    .sseAlgorithm(ServerSideEncryption.AES256)
                    .kmsMasterKeyID("arn:aws:kms:us-east-1:1234/5678example")
                    .build())
                .bucketKeyEnabled(true)
                .build())
            .build())
        .build());

    GetBucketEncryptionResponse encryption = s3.getBucketEncryption(
        GetBucketEncryptionRequest.builder().bucket(bucketName).build());
    List<ServerSideEncryptionRule> rules = encryption.serverSideEncryptionConfiguration().rules();
    assertEquals(1, rules.size());

    s3.deleteBucketEncryption(DeleteBucketEncryptionRequest.builder()
        .bucket(bucketName)
        .build());
  }

  @Test
  @LocalS3
  void testListBuckets(S3Client s3) {
    ListBucketsResponse buckets = s3.listBuckets();
    assertEquals(0, buckets.buckets().size());

    s3.createBucket(CreateBucketRequest.builder().bucket("test-bucket1").build());
    s3.createBucket(CreateBucketRequest.builder().bucket("test-bucket2").build());
    
    ListBucketsResponse buckets1 = s3.listBuckets();
    assertEquals(2, buckets1.buckets().size());
    assertTrue(buckets1.buckets().stream().allMatch(bucket -> bucket.creationDate().isBefore(Instant.now())));
    assertTrue(buckets1.buckets().stream().map(Bucket::name).anyMatch("test-bucket1"::equals));
    assertTrue(buckets1.buckets().stream().map(Bucket::name).anyMatch("test-bucket2"::equals));

    s3.deleteBucket(DeleteBucketRequest.builder().bucket("test-bucket1").build());
    ListBucketsResponse buckets2 = s3.listBuckets();
    assertEquals(1, buckets2.buckets().size());
    assertEquals("test-bucket2", buckets2.buckets().get(0).name());
  }

  /**
   * Amazon S3 answers the re-creation of a bucket that the requester owns with 200 OK in us-east-1, and leaves the
   * bucket as it is; in any other region with BucketAlreadyOwnedByYou, which application code catches to ignore.
   */
  @Test
  @LocalS3
  void reCreatingAnOwnedBucketSucceedsInUsEast1AndFailsWithBucketAlreadyOwnedByYouElsewhere(S3Client s3) {
    s3.createBucket(b -> b.bucket("owned"));
    s3.putObject(b -> b.bucket("owned").key("a.txt"), RequestBody.fromString("a"));

    assertDoesNotThrow(() -> s3.createBucket(b -> b.bucket("owned")));
    assertDoesNotThrow(() -> s3.createBucket(b -> b.bucket("owned")
        .createBucketConfiguration(c -> c.locationConstraint("us-east-1"))));
    assertEquals(List.of("a.txt"), s3.listObjectsV2(b -> b.bucket("owned")).contents().stream()
        .map(S3Object::key).toList(), "The objects of the bucket are kept.");

    BucketAlreadyOwnedByYouException elsewhere = assertThrows(BucketAlreadyOwnedByYouException.class,
        () -> s3.createBucket(b -> b.bucket("owned")
            .createBucketConfiguration(c -> c.locationConstraint(BucketLocationConstraint.EU_WEST_1))));
    assertEquals(409, elsewhere.statusCode());
    assertEquals("BucketAlreadyOwnedByYou", elsewhere.awsErrorDetails().errorCode());

    s3.createBucket(b -> b.bucket("owned-in-eu-west-1")
        .createBucketConfiguration(c -> c.locationConstraint(BucketLocationConstraint.EU_WEST_1)));
    assertThrows(BucketAlreadyOwnedByYouException.class, () -> s3.createBucket(b -> b.bucket("owned-in-eu-west-1")));
  }
}
