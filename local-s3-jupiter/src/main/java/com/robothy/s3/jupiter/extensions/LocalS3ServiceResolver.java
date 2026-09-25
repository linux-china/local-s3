package com.robothy.s3.jupiter.extensions;

import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Injects the {@link com.robothy.s3.rest.LocalS3} service that {@code @LocalS3} launched, so that a test can use what
 * the service offers besides the S3 API, e.g. {@code reset()} between tests of a shared service,
 * {@code applyLifecycle(Instant)} to expire objects at a later time, or
 * {@code getS3Manager().addChangeListener(...)} to observe the changes the code under test makes.
 *
 * <p>The extension owns the service: it is shut down when its context ends, so a test must not shut it down itself.
 */
public class LocalS3ServiceResolver extends AbstractLocalS3ParameterResolver {

  @Override
  protected String className() {
    return com.robothy.s3.rest.LocalS3.class.getName();
  }

  @Override
  Object resolve(LocalS3Extension.Service service, ExtensionContext context) {
    return service.localS3();
  }

  @Override
  protected Object resolve(int port, LocalS3 s3Config) {
    throw new UnsupportedOperationException("Resolved with the service itself.");
  }

}
