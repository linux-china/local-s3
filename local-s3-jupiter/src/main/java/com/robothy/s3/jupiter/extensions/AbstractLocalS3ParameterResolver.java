package com.robothy.s3.jupiter.extensions;

import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.lang.reflect.Method;
import java.util.Objects;

public abstract class AbstractLocalS3ParameterResolver implements ParameterResolver {

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    return Objects.equals(className(), parameterContext.getParameter().getType().getName());
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context)
      throws ParameterResolutionException {

    String methodName = context.getTestMethod().map(Method::toString).orElse("");
    String s3OnMethodKey = context.getRequiredTestClass()
        + (methodName + LocalS3Extension.LOCAL_S3_PORT_STORE_SUFFIX);
    String s3OnClassKey = context.getRequiredTestClass() + LocalS3Extension.LOCAL_S3_PORT_STORE_SUFFIX;
    ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.GLOBAL);

    // The annotation is read off the same element that the service was launched from, so that the client is
    // configured the way the service it talks to is; a method annotation shadows a class one, like the
    // service of a method shadows the one of its class.
    Integer port = store.getOrDefault(s3OnMethodKey, Integer.TYPE, null);
    LocalS3 s3Config = port == null ? null
        : context.getTestMethod().map(method -> method.getAnnotation(LocalS3.class)).orElse(null);
    if (port == null) {
      port = store.getOrDefault(s3OnClassKey, Integer.TYPE, null);
      s3Config = context.getRequiredTestClass().getAnnotation(LocalS3.class);
    }

    if (null == port) {
      throw new IllegalStateException("You need to add the @LocalS3 annotation on your test class " +
          "or test method to inject a AmazonS3 instance.");
    }

    return resolve(port, s3Config);
  }

  protected abstract String className();

  /**
   * Resolve the parameter against the service that the annotation launched.
   *
   * @param port the TCP port the service listens on.
   * @param s3Config the annotation the service was launched from; {@code null} if it couldn't be read,
   *     which leaves the defaults of the annotation in effect.
   * @return the resolved parameter.
   */
  protected abstract Object resolve(int port, LocalS3 s3Config);

  /**
   * The credentials that a client of the service is given: the ones the service verifies signatures
   * against when {@linkplain LocalS3#accessKey()} and {@linkplain LocalS3#secretKey()} configure them, so
   * that an injected client is accepted, and no credentials at all otherwise, which is what a service that
   * verifies nothing expects.
   *
   * @param s3Config the annotation the service was launched from; may be {@code null}.
   * @return the credentials provider of the client.
   */
  protected static AwsCredentialsProvider credentialsProvider(LocalS3 s3Config) {
    if (Objects.isNull(s3Config) || !LocalS3Extension.verifiesSignatures(s3Config)) {
      return AnonymousCredentialsProvider.create();
    }
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(s3Config.accessKey(), s3Config.secretKey()));
  }

}
