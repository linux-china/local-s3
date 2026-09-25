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

import java.util.Objects;

public abstract class AbstractLocalS3ParameterResolver implements ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(AbstractLocalS3ParameterResolver.class);

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    return Objects.equals(className(), parameterContext.getParameter().getType().getName());
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context)
      throws ParameterResolutionException {

    // The service of the innermost context that has one, i.e. of the method, its class, or an enclosing class. The
    // client is configured from the annotation that service was launched from, e.g. with its credentials.
    LocalS3Extension.Service service = LocalS3Extension.service(context).orElseThrow(() -> new IllegalStateException(
        "You need to add the @LocalS3 annotation on your test class or test method to inject a "
            + parameterContext.getParameter().getType().getSimpleName() + " instance."));
    return resolve(service, context);
  }

  /**
   * Resolve the parameter against a running service. The resolvers that need more than the port and the annotation,
   * e.g. the service itself or a client that has to be closed, override it.
   *
   * @param service the service that serves the context.
   * @param context the context of the parameter, whose store closes what {@linkplain #closeWith} is given.
   * @return the resolved parameter.
   */
  Object resolve(LocalS3Extension.Service service, ExtensionContext context) {
    return resolve(service.port(), service.config());
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
   * Close a resource, e.g. a client with threads of its own, when the context of the parameter it was injected into
   * ends, which JUnit 5.13 and later do with the values of the store that are {@linkplain AutoCloseable}.
   *
   * @param context the context of the parameter.
   * @param resource the resource to close.
   * @param <T> the type of the resource.
   * @return the resource.
   */
  static <T extends AutoCloseable> T closeWith(ExtensionContext context, T resource) {
    context.getStore(NAMESPACE).put(new Object(), resource);
    return resource;
  }

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
