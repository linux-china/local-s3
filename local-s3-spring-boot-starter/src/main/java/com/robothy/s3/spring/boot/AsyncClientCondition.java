package com.robothy.s3.spring.boot;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.util.ClassUtils;

/**
 * Matches when an {@code S3AsyncClient} can be built: on an asynchronous HTTP client of the AWS SDK,
 * {@code netty-nio-client} or {@code aws-crt-client}, or on the AWS Common Runtime alone, {@code aws-crt}, as an
 * {@code S3CrtAsyncClient}. The classes are named, rather than referenced, since each of them is optional.
 */
final class AsyncClientCondition extends AnyNestedCondition {

  static final String NETTY_HTTP_CLIENT = "software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient";

  static final String CRT_HTTP_CLIENT = "software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient";

  static final String CRT = "software.amazon.awssdk.crt.CRT";

  AsyncClientCondition() {
    super(ConfigurationPhase.REGISTER_BEAN);
  }

  /**
   * Whether an asynchronous HTTP client of the AWS SDK is on the classpath, which {@code S3AsyncClient.builder()} is
   * built on; otherwise only the AWS Common Runtime is, and {@code S3AsyncClient.crtBuilder()} is used.
   */
  static boolean hasAsyncHttpClient(ClassLoader classLoader) {
    return ClassUtils.isPresent(NETTY_HTTP_CLIENT, classLoader) || ClassUtils.isPresent(CRT_HTTP_CLIENT, classLoader);
  }

  @ConditionalOnClass(name = NETTY_HTTP_CLIENT)
  static class NettyHttpClient {
  }

  @ConditionalOnClass(name = CRT_HTTP_CLIENT)
  static class CrtHttpClient {
  }

  @ConditionalOnClass(name = CRT)
  static class CommonRuntime {
  }

}
