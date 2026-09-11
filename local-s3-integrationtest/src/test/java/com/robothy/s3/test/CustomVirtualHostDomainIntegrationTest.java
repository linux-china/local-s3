package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.rest.LocalS3;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;

/**
 * Virtual-hosted-style requests to a configured base domain, like a service name in docker-compose.
 */
class CustomVirtualHostDomainIntegrationTest {

  @Test
  void accessesBucketsUnderConfiguredDomain() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).virtualHostDomains("s3.local").build();
    localS3.start();
    List<String> hosts = new CopyOnWriteArrayList<>();
    try (S3Client s3 = S3Client.builder()
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("accessKeyId", "secretAccessKey")))
        // Resolve s3.local and my-bucket.s3.local to the local service.
        .httpClientBuilder(ApacheHttpClient.builder().dnsResolver(host -> InetAddress.getAllByName("localhost")))
        .overrideConfiguration(config -> config.addExecutionInterceptor(new ExecutionInterceptor() {
          @Override
          public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            hosts.add(context.httpRequest().host());
          }
        }))
        .endpointOverride(new URI("http://s3.local:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .build()) {

      s3.createBucket(b -> b.bucket("my-bucket"));
      s3.putObject(b -> b.bucket("my-bucket").key("dir/a.txt"), RequestBody.fromString("Hello"));

      assertEquals("Hello", s3.getObjectAsBytes(b -> b.bucket("my-bucket").key("dir/a.txt")).asUtf8String());
      assertEquals(List.of("my-bucket"), s3.listBuckets().buckets().stream().map(Bucket::name).toList());
      assertTrue(hosts.contains("my-bucket.s3.local"), "The SDK sent virtual-hosted-style requests: " + hosts);
      assertTrue(hosts.contains("s3.local"), "ListBuckets is sent to the base domain: " + hosts);
    } finally {
      localS3.shutdown();
    }
  }

}
