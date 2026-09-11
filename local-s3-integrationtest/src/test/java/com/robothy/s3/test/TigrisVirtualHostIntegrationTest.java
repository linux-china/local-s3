package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.rest.LocalS3;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * An S3 client configured for Tigris: its endpoint, the region "auto", virtual-hosted-style requests, and requests
 * signed with AWS Signature Version 4.
 */
class TigrisVirtualHostIntegrationTest {

  @ParameterizedTest
  @ValueSource(strings = {"t3.storage.dev", "fly.storage.tigris.dev"})
  void accessesBucketsThroughTigrisEndpoint(String endpoint) throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).credentials("tid_access_key", "tsec_secret_key").build();
    localS3.start();
    List<String> hosts = new CopyOnWriteArrayList<>();
    try (S3Client s3 = S3Client.builder()
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("tid_access_key", "tsec_secret_key")))
        // Resolve the Tigris endpoint and its bucket subdomains to the local service.
        .httpClientBuilder(ApacheHttpClient.builder().dnsResolver(host -> InetAddress.getAllByName("localhost")))
        .overrideConfiguration(config -> config.addExecutionInterceptor(new ExecutionInterceptor() {
          @Override
          public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            hosts.add(context.httpRequest().host());
          }
        }))
        .endpointOverride(new URI("http://" + endpoint + ":" + localS3.getPort()))
        .region(Region.of("auto"))
        .build()) {

      s3.createBucket(b -> b.bucket("my-bucket"));
      s3.putObject(b -> b.bucket("my-bucket").key("dir/a.txt"), RequestBody.fromString("Hello Tigris"));

      assertEquals("Hello Tigris", s3.getObjectAsBytes(b -> b.bucket("my-bucket").key("dir/a.txt")).asUtf8String());
      assertEquals(List.of("my-bucket"), s3.listBuckets().buckets().stream().map(Bucket::name).toList());
      assertTrue(hosts.contains("my-bucket." + endpoint), "The SDK sent virtual-hosted-style requests: " + hosts);
      assertTrue(hosts.contains(endpoint), "ListBuckets is sent to the endpoint: " + hosts);
    } finally {
      localS3.shutdown();
    }
  }

}
