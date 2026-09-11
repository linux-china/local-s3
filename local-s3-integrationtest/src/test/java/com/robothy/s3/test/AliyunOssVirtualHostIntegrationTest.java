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
 * An S3 client configured for the S3-compatible API of Alibaba Cloud OSS, which requires virtual-hosted-style
 * requests signed with AWS Signature Version 4.
 */
class AliyunOssVirtualHostIntegrationTest {

  @Test
  void accessesBucketsThroughOssEndpoint() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).credentials("oss-access-key-id", "oss-access-key-secret").build();
    localS3.start();
    List<String> hosts = new CopyOnWriteArrayList<>();
    try (S3Client s3 = S3Client.builder()
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("oss-access-key-id", "oss-access-key-secret")))
        // Resolve the OSS endpoint and its bucket subdomains to the local service.
        .httpClientBuilder(ApacheHttpClient.builder().dnsResolver(host -> InetAddress.getAllByName("localhost")))
        .overrideConfiguration(config -> config.addExecutionInterceptor(new ExecutionInterceptor() {
          @Override
          public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            hosts.add(context.httpRequest().host());
          }
        }))
        .endpointOverride(new URI("http://oss-cn-hangzhou.aliyuncs.com:" + localS3.getPort()))
        .region(Region.of("oss-cn-hangzhou"))
        .build()) {

      s3.createBucket(b -> b.bucket("my-bucket"));
      s3.putObject(b -> b.bucket("my-bucket").key("dir/a.txt"), RequestBody.fromString("Hello OSS"));

      assertEquals("Hello OSS", s3.getObjectAsBytes(b -> b.bucket("my-bucket").key("dir/a.txt")).asUtf8String());
      assertEquals(List.of("my-bucket"), s3.listBuckets().buckets().stream().map(Bucket::name).toList());
      assertTrue(hosts.contains("my-bucket.oss-cn-hangzhou.aliyuncs.com"),
          "The SDK sent virtual-hosted-style requests: " + hosts);
      assertTrue(hosts.contains("oss-cn-hangzhou.aliyuncs.com"), "ListBuckets is sent to the endpoint: " + hosts);
    } finally {
      localS3.shutdown();
    }
  }

}
