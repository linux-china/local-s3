package com.robothy.s3.jupiter.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

class MaxInMemoryBytesTest {

  /**
   * A copy takes as much heap as an upload, so a copy that the limit has no room for is rejected too, rather than
   * running the JVM that embeds the service out of heap.
   */
  @Test
  @LocalS3(maxInMemoryBytes = "1k", buckets = "bucket")
  void aCopyBeyondTheLimitIsInsufficientStorage(S3Client client) {
    client.putObject(b -> b.bucket("bucket").key("a"), RequestBody.fromBytes(new byte[600]));

    S3Exception e = assertThrows(S3Exception.class, () -> client.copyObject(b -> b.sourceBucket("bucket")
        .sourceKey("a").destinationBucket("bucket").destinationKey("b")));
    assertEquals(507, e.statusCode());
    assertEquals("InsufficientStorage", e.awsErrorDetails().errorCode());

    // Deleting an object frees its space.
    client.deleteObject(b -> b.bucket("bucket").key("a"));
    client.putObject(b -> b.bucket("bucket").key("c"), RequestBody.fromBytes(new byte[1000]));
  }

}
