package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The {@code Location} of a completed upload is the absolute URL of the object, with its key percent-encoded, so that
 * client code which parses it as a URI doesn't fail on a key with a space, a {@code #} or a {@code ?}.
 */
public class CompleteMultipartUploadLocationIntegrationTest {

  private static final String KEY = "dir/a b#c?d.txt";

  @Test
  @LocalS3
  void aPathStyleRequestGetsTheBucketInThePath(S3Client s3, LocalS3Endpoint endpoint) {
    s3.createBucket(b -> b.bucket("location-bucket"));

    String location = complete(s3, "location-bucket");

    assertEquals("http://localhost:" + endpoint.port() + "/location-bucket/dir/a%20b%23c%3Fd.txt", location);
    assertEquals("/location-bucket/" + KEY, URI.create(location).getPath());
  }

  @Test
  @LocalS3
  void aVirtualHostedRequestGetsTheBucketInTheHost(LocalS3Endpoint endpoint) throws Exception {
    S3Client s3 = S3Client.builder()
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("accessKeyId", "secret")))
        .httpClientBuilder(ApacheHttpClient.builder()
            .dnsResolver(host -> InetAddress.getAllByName("localhost"))
            .connectionTimeout(Duration.ofSeconds(1))
            .socketTimeout(Duration.ofSeconds(5)))
        // A host name rather than the IP address of endpoint(), which the SDK would address path-style.
        .endpointOverride(new URI("http://localhost:" + endpoint.port()))
        .region(Region.of(endpoint.region()))
        .build();
    s3.createBucket(b -> b.bucket("location-bucket"));

    String location = complete(s3, "location-bucket");

    assertEquals("http://location-bucket.localhost:" + endpoint.port() + "/dir/a%20b%23c%3Fd.txt", location);
    assertEquals("/" + KEY, URI.create(location).getPath());
  }

  private static String complete(S3Client s3, String bucket) {
    String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(KEY)).uploadId();
    String etag = s3.uploadPart(b -> b.bucket(bucket).key(KEY).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("Hello")).eTag();
    return s3.completeMultipartUpload(b -> b.bucket(bucket).key(KEY).uploadId(uploadId)
        .multipartUpload(m -> m.parts(p -> p.partNumber(1).eTag(etag)))).location();
  }

}
