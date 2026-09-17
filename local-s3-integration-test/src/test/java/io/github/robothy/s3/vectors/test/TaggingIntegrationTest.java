package io.github.robothy.s3.vectors.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.DataType;
import software.amazon.awssdk.services.s3vectors.model.DistanceMetric;
import software.amazon.awssdk.services.s3vectors.model.NotFoundException;
import software.amazon.awssdk.services.s3vectors.model.S3VectorsException;
import software.amazon.awssdk.services.s3vectors.model.ValidationException;

/**
 * TagResource, UntagResource and ListTagsForResource of vector buckets and indexes, which address them by ARN.
 */
public class TaggingIntegrationTest {

  private static final String ACCESS_KEY = "an-access-key";

  private static final String SECRET_KEY = "a-secret-key";

  @LocalS3
  @Test
  void tagUntagAndListTagsOfVectorBucket(S3VectorsClient vectorsClient) {
    String bucketName = "tagging-bucket-" + UUID.randomUUID().toString().substring(0, 8);
    String bucketArn = vectorsClient.createVectorBucket(b -> b.vectorBucketName(bucketName)
        .tags(Map.of("team", "search"))).vectorBucketArn();

    assertEquals(Map.of("team", "search"), listTags(vectorsClient, bucketArn));

    vectorsClient.tagResource(b -> b.resourceArn(bucketArn).tags(Map.of("team", "ml", "env", "test")));
    assertEquals(Map.of("team", "ml", "env", "test"), listTags(vectorsClient, bucketArn));

    vectorsClient.untagResource(b -> b.resourceArn(bucketArn).tagKeys("team", "absent"));
    assertEquals(Map.of("env", "test"), listTags(vectorsClient, bucketArn));

    vectorsClient.untagResource(b -> b.resourceArn(bucketArn).tagKeys("env"));
    assertEquals(Map.of(), listTags(vectorsClient, bucketArn));
  }

  @LocalS3
  @Test
  void tagsOfAnIndexAreItsOwn(S3VectorsClient vectorsClient) {
    String bucketName = "tagging-index-bucket-" + UUID.randomUUID().toString().substring(0, 8);
    String bucketArn = vectorsClient.createVectorBucket(b -> b.vectorBucketName(bucketName)).vectorBucketArn();
    vectorsClient.createIndex(b -> b.vectorBucketName(bucketName)
        .indexName("my-index")
        .dataType(DataType.FLOAT32)
        .dimension(2)
        .distanceMetric(DistanceMetric.COSINE)
        .tags(Map.of("purpose", "rag")));
    String indexArn = vectorsClient.getIndex(b -> b.vectorBucketName(bucketName).indexName("my-index"))
        .index().indexArn();

    assertEquals(Map.of("purpose", "rag"), listTags(vectorsClient, indexArn));
    assertEquals(Map.of(), listTags(vectorsClient, bucketArn));

    vectorsClient.tagResource(b -> b.resourceArn(indexArn).tags(Map.of("owner", "a b/c:d")));
    assertEquals(Map.of("purpose", "rag", "owner", "a b/c:d"), listTags(vectorsClient, indexArn));

    // An index created again with the same name has none of the tags of the deleted one.
    vectorsClient.deleteIndex(b -> b.vectorBucketName(bucketName).indexName("my-index"));
    assertThrows(NotFoundException.class, () -> listTags(vectorsClient, indexArn));
    vectorsClient.createIndex(b -> b.vectorBucketName(bucketName)
        .indexName("my-index")
        .dataType(DataType.FLOAT32)
        .dimension(2)
        .distanceMetric(DistanceMetric.COSINE));
    assertEquals(Map.of(), listTags(vectorsClient, indexArn));
  }

  @LocalS3
  @Test
  void invalidRequestsAreRejected(S3VectorsClient vectorsClient) {
    String bucketName = "tagging-invalid-" + UUID.randomUUID().toString().substring(0, 8);
    String bucketArn = vectorsClient.createVectorBucket(b -> b.vectorBucketName(bucketName)).vectorBucketArn();

    assertThrows(NotFoundException.class, () -> listTags(vectorsClient, bucketArn + "-absent"));
    assertThrows(NotFoundException.class,
        () -> vectorsClient.tagResource(b -> b.resourceArn(bucketArn + "/index/absent").tags(Map.of("k", "v"))));
    assertThrows(S3VectorsException.class, () -> listTags(vectorsClient, "arn:aws:s3vectors:::not-a-bucket/x"));

    assertThrows(ValidationException.class,
        () -> vectorsClient.tagResource(b -> b.resourceArn(bucketArn).tags(Map.of("aws:reserved", "v"))));
    assertThrows(ValidationException.class,
        () -> vectorsClient.tagResource(b -> b.resourceArn(bucketArn).tags(Map.of("k".repeat(129), "v"))));
    assertThrows(ValidationException.class,
        () -> vectorsClient.createVectorBucket(b -> b.vectorBucketName(bucketName + "-2").tags(tags(51, "a"))));
    assertThrows(NotFoundException.class,
        () -> vectorsClient.getVectorBucket(b -> b.vectorBucketName(bucketName + "-2")));

    // Tags are added all or none: 30 + 30 is over the limit, so the resource keeps its 30.
    vectorsClient.tagResource(b -> b.resourceArn(bucketArn).tags(tags(30, "a")));
    assertThrows(ValidationException.class,
        () -> vectorsClient.tagResource(b -> b.resourceArn(bucketArn).tags(tags(30, "b"))));
    assertEquals(tags(30, "a"), listTags(vectorsClient, bucketArn));
  }

  /**
   * An S3 object whose key merely looks like the path of a tagging operation, in a bucket named {@code tags}, is still
   * an object when the key isn't an S3 Vectors ARN.
   */
  @LocalS3
  @Test
  void objectsOfABucketNamedTagsAreStillObjects(S3Client s3) {
    s3.createBucket(b -> b.bucket("tags"));
    s3.putObject(b -> b.bucket("tags").key("arn/not-vectors"), RequestBody.fromString("content"));
    assertEquals("content", s3.getObjectAsBytes(b -> b.bucket("tags").key("arn/not-vectors")).asUtf8String());
  }

  /**
   * The SDK signs the path of an S3 Vectors request, which holds the encoded ARN, like any service but Amazon S3 does.
   */
  @LocalS3(accessKey = ACCESS_KEY, secretKey = SECRET_KEY)
  @Test
  void signedTaggingRequestsAreAccepted(LocalS3Endpoint endpoint) {
    try (S3VectorsClient vectorsClient = S3VectorsClient.builder()
        .endpointOverride(URI.create("http://localhost:" + endpoint.port()))
        .region(Region.of("local"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .build()) {
      String bucketArn = vectorsClient.createVectorBucket(b -> b.vectorBucketName("signed-tagging")).vectorBucketArn();
      vectorsClient.tagResource(b -> b.resourceArn(bucketArn).tags(Map.of("signed", "yes")));
      vectorsClient.untagResource(b -> b.resourceArn(bucketArn).tagKeys("absent"));
      assertEquals(Map.of("signed", "yes"), listTags(vectorsClient, bucketArn));
    }
  }

  private static Map<String, String> listTags(S3VectorsClient vectorsClient, String resourceArn) {
    return vectorsClient.listTagsForResource(b -> b.resourceArn(resourceArn)).tags();
  }

  private static Map<String, String> tags(int count, String keyPrefix) {
    Map<String, String> tags = new HashMap<>();
    for (int i = 0; i < count; i++) {
      tags.put(keyPrefix + i, "v" + i);
    }
    return tags;
  }

}
