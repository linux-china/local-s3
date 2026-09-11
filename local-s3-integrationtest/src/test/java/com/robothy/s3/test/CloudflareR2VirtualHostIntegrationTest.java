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
 * An S3 client configured for Cloudflare R2: the endpoint of an account, the region "auto", and requests signed
 * with AWS Signature Version 4.
 */
class CloudflareR2VirtualHostIntegrationTest {

  private static final String ACCOUNT_ID = "0123456789abcdef0123456789abcdef";

  @Test
  void accessesBucketsThroughR2Endpoint() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).credentials("r2-access-key-id", "r2-secret-access-key").build();
    localS3.start();
    List<String> hosts = new CopyOnWriteArrayList<>();
    try (S3Client s3 = S3Client.builder()
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("r2-access-key-id", "r2-secret-access-key")))
        // Resolve the R2 endpoint and its bucket subdomains to the local service.
        .httpClientBuilder(ApacheHttpClient.builder().dnsResolver(host -> InetAddress.getAllByName("localhost")))
        .overrideConfiguration(config -> config.addExecutionInterceptor(new ExecutionInterceptor() {
          @Override
          public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            hosts.add(context.httpRequest().host());
          }
        }))
        .endpointOverride(new URI("http://" + ACCOUNT_ID + ".r2.cloudflarestorage.com:" + localS3.getPort()))
        .region(Region.of("auto"))
        .build()) {

      s3.createBucket(b -> b.bucket("my-bucket"));
      s3.putObject(b -> b.bucket("my-bucket").key("dir/a.txt"), RequestBody.fromString("Hello R2"));

      assertEquals("Hello R2", s3.getObjectAsBytes(b -> b.bucket("my-bucket").key("dir/a.txt")).asUtf8String());
      assertEquals(List.of("my-bucket"), s3.listBuckets().buckets().stream().map(Bucket::name).toList());
      assertTrue(hosts.contains("my-bucket." + ACCOUNT_ID + ".r2.cloudflarestorage.com"),
          "The SDK sent virtual-hosted-style requests: " + hosts);
      assertTrue(hosts.contains(ACCOUNT_ID + ".r2.cloudflarestorage.com"),
          "ListBuckets is sent to the endpoint of the account: " + hosts);
    } finally {
      localS3.shutdown();
    }
  }

}
