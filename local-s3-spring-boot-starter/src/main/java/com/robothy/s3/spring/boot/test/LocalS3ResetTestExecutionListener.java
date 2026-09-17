package com.robothy.s3.spring.boot.test;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.test.context.TestExecutionListener;

/**
 * Resets the {@code IN_MEMORY} LocalS3 services of the application context after each test method of a test class
 * annotated with {@linkplain AutoConfigureLocalS3} whose {@linkplain AutoConfigureLocalS3#reset() reset} is enabled,
 * with {@linkplain LocalS3#reset()}, which is much quicker than a new context.
 *
 * <p>A context that the test didn't load, or a service that isn't running, is left alone. The listener runs before
 * the one of {@code @DirtiesContext}, which may close the context after the test method.
 */
public class LocalS3ResetTestExecutionListener implements TestExecutionListener, Ordered {

  /**
   * The order of the listener: after the listeners of Spring, so that it runs before them after a test method.
   */
  public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public void afterTestMethod(TestContext testContext) {
    AutoConfigureLocalS3 annotation =
        TestContextAnnotationUtils.findMergedAnnotation(testContext.getTestClass(), AutoConfigureLocalS3.class);
    if (annotation == null || !annotation.reset() || !testContext.hasApplicationContext()) {
      return;
    }
    ApplicationContext context = testContext.getApplicationContext();
    for (LocalS3 localS3 : context.getBeanProvider(LocalS3.class)) {
      if (localS3.isRunning() && localS3.getMode() == LocalS3Mode.IN_MEMORY) {
        localS3.reset();
      }
    }
  }

}
