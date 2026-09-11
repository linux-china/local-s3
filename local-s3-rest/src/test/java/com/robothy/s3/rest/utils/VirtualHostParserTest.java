package com.robothy.s3.rest.utils;

import com.robothy.s3.rest.model.request.BucketRegion;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VirtualHostParserTest {

  @Test
  void getBucketRegionFromHostUnderAwsDomain() {
    assertFalse(VirtualHostParser.getBucketRegionFromHost(null).isPresent());
    assertFalse(VirtualHostParser.getBucketRegionFromHost("").isPresent());

    assertEquals(new BucketRegion("region1", null),
        VirtualHostParser.getBucketRegionFromHost("s3.region1.amazonaws.com").get());
    assertFalse(VirtualHostParser.getBucketRegionFromHost("s3.region1.unsupported.domain").isPresent());

    assertFalse(VirtualHostParser.getBucketRegionFromHost(".s3.region1.amazonaws.com").isPresent());
    assertEquals(new BucketRegion("eu-west-1", null), VirtualHostParser.getBucketRegionFromHost("s3.eu-west-1.amazonaws.com").get());

    assertEquals(new BucketRegion("region1", "bucket1"),
        VirtualHostParser.getBucketRegionFromHost("bucket1.s3.region1.amazonaws.com").get());
    assertEquals(new BucketRegion("region1", "bucket1"),
        VirtualHostParser.getBucketRegionFromHost("bucket1.s3.region1.amazonaws.com").get());

    assertEquals(new BucketRegion("region2", "www.example.com"),
        VirtualHostParser.getBucketRegionFromHost("www.example.com.s3.region2.amazonaws.com").get());
    assertEquals(new BucketRegion("ap-east-1", "www.example.com"),
        VirtualHostParser.getBucketRegionFromHost("www.example.com.s3.ap-east-1.amazonaws.com").get());

    // if using the legacy endpoint, then set the default region "local".
    assertEquals(new BucketRegion("local", "bucket1"),
        VirtualHostParser.getBucketRegionFromHost("bucket1.s3.amazonaws.com").get());
    assertEquals(new BucketRegion("local", "www.example.com"),
        VirtualHostParser.getBucketRegionFromHost("www.example.com.s3.amazonaws.com").get());
    assertFalse(VirtualHostParser.getBucketRegionFromHost(".s3.amazonaws.com").isPresent());
  }


  @Test
  public void getBucketRegionFromLocalDomain() {
    assertEquals(new BucketRegion("local", "bucket1"),
        VirtualHostParser.getBucketRegionFromHost("bucket1.localhost").get());
    assertEquals(new BucketRegion("local", "www.example.com"),
        VirtualHostParser.getBucketRegionFromHost("www.example.com.localhost").get());
    assertEquals(new BucketRegion("local", "bucket.s3"),
        VirtualHostParser.getBucketRegionFromHost("bucket.s3.localhost").get());
    assertEquals(new BucketRegion("local", "bucket.s3."),
        VirtualHostParser.getBucketRegionFromHost("bucket.s3..localhost").get());

    assertFalse(VirtualHostParser.getBucketRegionFromHost("localhost").isPresent());
    assertFalse(VirtualHostParser.getBucketRegionFromHost(".localhost").isPresent());
    assertFalse(VirtualHostParser.getBucketRegionFromHost("127.0.0.1").isPresent());
  }

  @Test
  void getBucketRegionFromConfiguredDomains() {
    VirtualHostParser parser = new VirtualHostParser(List.of("s3", ".s3.local.", " ", "local"));

    assertEquals(new BucketRegion("local", "bucket1"), parser.parse("bucket1.s3").get());
    assertEquals(new BucketRegion("local", "bucket1"), parser.parse("bucket1.s3.local:29090").get(),
        "The longest base domain wins over 'local'.");
    assertEquals(new BucketRegion("local", "My-Bucket"), parser.parse("My-Bucket.S3.Local").get(),
        "Domains are case-insensitive; the bucket name keeps its case.");
    assertEquals(new BucketRegion("local", "bucket1"), parser.parse("bucket1.localhost").get(),
        "The default domains stay.");

    assertFalse(parser.parse("s3").isPresent(), "A request to a base domain is path-style.");
    assertFalse(parser.parse("s3.local:29090").isPresent(), "Even if it is a subdomain of another base domain.");
    assertFalse(parser.parse("minio.example").isPresent());

    assertFalse(VirtualHostParser.getBucketRegionFromHost("bucket1.s3.local").isPresent(),
        "The default parser only knows the default domains.");
  }

  @Test
  void getBucketRegionFromHostUnderAliyunDomain() {
    assertEquals(new BucketRegion("oss-cn-hangzhou", "examplebucket"),
        VirtualHostParser.getBucketRegionFromHost("examplebucket.oss-cn-hangzhou.aliyuncs.com").get());
    assertEquals(new BucketRegion("oss-cn-hangzhou", "examplebucket"),
        VirtualHostParser.getBucketRegionFromHost("examplebucket.oss-cn-hangzhou-internal.aliyuncs.com:29090").get(),
        "The internal endpoint is in the same region.");
    assertEquals(new BucketRegion("oss-ap-southeast-1", "www.example.com"),
        VirtualHostParser.getBucketRegionFromHost("www.example.com.oss-ap-southeast-1.aliyuncs.com").get());
    assertEquals(new BucketRegion("local", "examplebucket"),
        VirtualHostParser.getBucketRegionFromHost("examplebucket.oss-accelerate.aliyuncs.com").get());
    assertEquals(new BucketRegion("local", "examplebucket"),
        VirtualHostParser.getBucketRegionFromHost("examplebucket.oss-accelerate-overseas.aliyuncs.com").get());
    assertEquals(new BucketRegion("local", "examplebucket"),
        VirtualHostParser.getBucketRegionFromHost("examplebucket.oss.aliyuncs.com").get());
    assertEquals(new BucketRegion("oss-cn-hangzhou", "ExampleBucket"),
        VirtualHostParser.getBucketRegionFromHost("ExampleBucket.OSS-CN-Hangzhou.Aliyuncs.com").get());

    // Requests to the endpoint itself are path-style.
    assertEquals(new BucketRegion("oss-cn-hangzhou", null),
        VirtualHostParser.getBucketRegionFromHost("oss-cn-hangzhou.aliyuncs.com").get());
    assertFalse(VirtualHostParser.getBucketRegionFromHost(".oss-cn-hangzhou.aliyuncs.com").get()
        .getBucketName().isPresent());
    // Other Alibaba Cloud services.
    assertFalse(VirtualHostParser.getBucketRegionFromHost("ecs.cn-hangzhou.aliyuncs.com").isPresent());
    assertFalse(VirtualHostParser.getBucketRegionFromHost("bucket.oss-.aliyuncs.com").isPresent());
  }

  @Test
  void getBucketRegionFromHostUnderR2Domain() {
    String account = "0123456789abcdef0123456789abcdef";

    // Requests to the endpoint of the account are path-style.
    assertEquals(new BucketRegion("auto", null),
        VirtualHostParser.getBucketRegionFromHost(account + ".r2.cloudflarestorage.com").get());
    assertEquals(new BucketRegion("auto", "my-bucket"),
        VirtualHostParser.getBucketRegionFromHost("my-bucket." + account + ".r2.cloudflarestorage.com:443").get());
    assertEquals(new BucketRegion("auto", "www.example.com"),
        VirtualHostParser.getBucketRegionFromHost("www.example.com." + account + ".r2.cloudflarestorage.com").get());

    // Jurisdiction-specific endpoints.
    assertEquals(new BucketRegion("auto", null),
        VirtualHostParser.getBucketRegionFromHost(account + ".eu.r2.cloudflarestorage.com").get());
    assertEquals(new BucketRegion("auto", "my-bucket"),
        VirtualHostParser.getBucketRegionFromHost("my-bucket." + account + ".eu.r2.cloudflarestorage.com").get());
    assertEquals(new BucketRegion("auto", "my-bucket"),
        VirtualHostParser.getBucketRegionFromHost("my-bucket." + account + ".fedramp.r2.cloudflarestorage.com").get());

    assertEquals(new BucketRegion("auto", "My-Bucket"), VirtualHostParser.getBucketRegionFromHost(
        "My-Bucket." + account.toUpperCase() + ".R2.CloudflareStorage.com").get());
    assertFalse(VirtualHostParser.getBucketRegionFromHost(".r2.cloudflarestorage.com").isPresent());
    assertFalse(VirtualHostParser.getBucketRegionFromHost("r2.cloudflarestorage.com").isPresent());
  }

  @Test
  void getBucketRegionFromTigrisDomains() {
    assertEquals(new BucketRegion("auto", "my-bucket"),
        VirtualHostParser.getBucketRegionFromHost("my-bucket.t3.storage.dev").get());
    assertEquals(new BucketRegion("auto", "my-bucket"),
        VirtualHostParser.getBucketRegionFromHost("my-bucket.fly.storage.tigris.dev:443").get());
    assertEquals(new BucketRegion("auto", "www.example.com"),
        VirtualHostParser.getBucketRegionFromHost("www.example.com.t3.storage.dev").get());
    assertEquals(new BucketRegion("auto", "My-Bucket"),
        VirtualHostParser.getBucketRegionFromHost("My-Bucket.T3.Storage.Dev").get());

    // Requests to the endpoints themselves are path-style.
    assertFalse(VirtualHostParser.getBucketRegionFromHost("t3.storage.dev").isPresent());
    assertFalse(VirtualHostParser.getBucketRegionFromHost("fly.storage.tigris.dev:443").isPresent());

    VirtualHostParser parser = new VirtualHostParser(List.of("storage.dev", "t3.storage.dev"));
    assertEquals(new BucketRegion("auto", "my-bucket"), parser.parse("my-bucket.t3.storage.dev").get(),
        "Configured domains don't change the Tigris endpoint.");
    assertEquals(new BucketRegion("local", "my-bucket"), parser.parse("my-bucket.storage.dev").get());
  }

  @Test
  void removesPortsFromIpv6Hosts() {
    assertEquals("[::1]", VirtualHostParser.removePortIfExist("[::1]"));
    assertEquals("[::1]", VirtualHostParser.removePortIfExist("[::1]:29090"));
    assertEquals("::1", VirtualHostParser.removePortIfExist("::1"));
    assertEquals("localhost", VirtualHostParser.removePortIfExist("localhost:29090"));
    assertEquals("bucket.localhost", VirtualHostParser.removePortIfExist("bucket.localhost"));
    assertFalse(VirtualHostParser.getBucketRegionFromHost("[::1]:29090").isPresent());
  }

}