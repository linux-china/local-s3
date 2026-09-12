package com.robothy.s3.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultServiceFactoryTest {

  /**
   * The registry belongs to the factory, so that the services of one LocalS3 instance are not visible to
   * another one started in the same JVM, which would give it the configuration of whichever started last.
   */
  @Test
  void registriesOfTwoFactoriesAreIndependent() {
    DefaultServiceFactory first = new DefaultServiceFactory();
    DefaultServiceFactory second = new DefaultServiceFactory();
    BucketNameValidator strict = new BucketNameValidator(true);
    BucketNameValidator lenient = new BucketNameValidator(false);

    first.register(BucketNameValidator.class, () -> strict);
    assertFalse(second.containsInstance(BucketNameValidator.class),
        "A service registered in one factory must not appear in another one.");

    second.register(BucketNameValidator.class, () -> lenient);
    assertSame(strict, first.getInstance(BucketNameValidator.class),
        "Registering in one factory must not replace the service of another one.");
    assertSame(lenient, second.getInstance(BucketNameValidator.class));
  }

  @Test
  void registeringAgainReplacesTheService() {
    DefaultServiceFactory factory = new DefaultServiceFactory();
    MultipartUploadPolicy first = MultipartUploadPolicy.of(false);
    MultipartUploadPolicy second = MultipartUploadPolicy.of(true);

    factory.register(MultipartUploadPolicy.class, () -> first);
    factory.register(MultipartUploadPolicy.class, () -> second);

    assertSame(second, factory.getInstance(MultipartUploadPolicy.class));
  }

  @Test
  void unknownServiceIsReported() {
    DefaultServiceFactory factory = new DefaultServiceFactory();

    assertFalse(factory.containsInstance(BucketNameValidator.class));
    assertThrows(IllegalArgumentException.class, () -> factory.getInstance(BucketNameValidator.class));

    factory.register(BucketNameValidator.class, () -> new BucketNameValidator(false));
    assertTrue(factory.containsInstance(BucketNameValidator.class));
  }

  /**
   * Registering doesn't call the supplier, so that a service is created when it is first asked for.
   */
  @Test
  void registeringDoesNotCallTheSupplier() {
    DefaultServiceFactory factory = new DefaultServiceFactory();
    AtomicInteger calls = new AtomicInteger();

    factory.register(BucketNameValidator.class, () -> {
      calls.incrementAndGet();
      return new BucketNameValidator(false);
    });
    assertEquals(0, calls.get());

    factory.getInstance(BucketNameValidator.class);
    assertEquals(1, calls.get());
  }

}
