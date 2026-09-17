package com.robothy.s3.spring.boot.test;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.PropertyMapping;

/**
 * Embeds a LocalS3 service in the application context of a test, e.g. of a {@code @SpringBootTest} or a test slice,
 * such as {@code @DataJpaTest}, that doesn't apply the auto-configuration of the starter by itself:
 *
 * <ul>
 *   <li>the service listens on a random free {@linkplain #port() port}, so that the contexts that the test context
 *   framework caches side by side don't compete for one; the client beans of the starter point at it;</li>
 *   <li>the service keeps its data {@linkplain #mode() in memory};</li>
 *   <li>the data is {@linkplain #reset() reset} after each test method, by
 *   {@linkplain LocalS3ResetTestExecutionListener}, so that a test doesn't see the objects of the tests before it,
 *   which share the cached context.</li>
 * </ul>
 *
 * <p>The attributes override the {@code local-s3.*} properties of the application.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ImportAutoConfiguration
@PropertyMapping("local-s3")
public @interface AutoConfigureLocalS3 {

  /**
   * The port that the service listens on; {@code 0} for a random free port.
   *
   * @return the port, mapped to {@code local-s3.port}.
   */
  int port() default 0;

  /**
   * Whether the data is kept in memory or persisted to {@code local-s3.data-path}. Only an {@code IN_MEMORY} service
   * is reset between the tests.
   *
   * @return the mode, mapped to {@code local-s3.mode}.
   */
  LocalS3Mode mode() default LocalS3Mode.IN_MEMORY;

  /**
   * Whether to reset the data of the service after each test method: the buckets, objects and vectors are dropped,
   * and the initial data and the default buckets are loaded again.
   *
   * @return whether to reset the service after each test method.
   */
  @PropertyMapping(skip = PropertyMapping.Skip.YES)
  boolean reset() default true;

}
