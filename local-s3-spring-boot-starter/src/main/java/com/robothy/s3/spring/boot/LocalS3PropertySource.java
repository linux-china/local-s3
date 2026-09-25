package com.robothy.s3.spring.boot;

import java.net.URI;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Publishes the endpoint that the embedded service listens on to the {@linkplain Environment}, like Spring Boot
 * publishes {@code local.server.port} for the web server:
 *
 * <ul>
 *   <li>{@value #ENDPOINT}: the endpoint, e.g. {@code http://127.0.0.1:29090};</li>
 *   <li>{@value #PORT}: the port, e.g. {@code 29090}.</li>
 * </ul>
 *
 * <p>So a client other than the ones of the starter reaches the service with a placeholder, also on a random port,
 * without injecting {@linkplain LocalS3Lifecycle}, e.g. {@code spring.cloud.aws.s3.endpoint=${local.s3.endpoint}}, a
 * DuckDB or Iceberg setting, or {@code @Value("${local.s3.port}")}. They are named after {@code local.server.port}
 * rather than under {@code local-s3.*}, whose {@code local-s3.port} is the configured port, i.e. {@code 0} for a random
 * one.
 *
 * <p>The values are resolved when they are read: reading one starts the service, if the lifecycle of the context hasn't
 * yet, like creating a client bean of the starter does, since the port of a random {@code local-s3.port} is only known
 * once the service is started. Hence a placeholder resolves while the beans are created too, e.g. when the properties
 * of Spring Cloud AWS are bound. The property source is removed from the environment when the context is closed.
 */
public class LocalS3PropertySource extends PropertySource<BeanFactory> {

  /**
   * The name of the property source in the environment.
   */
  public static final String NAME = "local-s3";

  /**
   * The endpoint that clients in the same JVM reach the service at, see {@linkplain LocalS3Lifecycle#endpoint()}.
   */
  public static final String ENDPOINT = "local.s3.endpoint";

  /**
   * The port that the service listens on.
   */
  public static final String PORT = "local.s3.port";

  LocalS3PropertySource(BeanFactory beanFactory) {
    super(NAME, beanFactory);
  }

  @Override
  public Object getProperty(String name) {
    if (!ENDPOINT.equals(name) && !PORT.equals(name)) {
      return null;
    }
    LocalS3Lifecycle lifecycle = getSource().getBeanProvider(LocalS3Lifecycle.class).getIfUnique();
    if (lifecycle == null) {
      return null;
    }
    URI endpoint = lifecycle.endpoint();
    return ENDPOINT.equals(name) ? endpoint.toString() : endpoint.getPort();
  }

  @Override
  public boolean containsProperty(String name) {
    // Answered without starting the service.
    return ENDPOINT.equals(name) || PORT.equals(name);
  }

  /**
   * Adds the property source to the environment before any bean of the application is created, and removes it when
   * the context is closed, so that a placeholder read afterward doesn't start the service again.
   */
  static class Registrar implements BeanFactoryPostProcessor, EnvironmentAware, DisposableBean {

    private ConfigurableEnvironment environment;

    @Override
    public void setEnvironment(Environment environment) {
      if (environment instanceof ConfigurableEnvironment configurable) {
        this.environment = configurable;
      }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
      if (environment != null) {
        environment.getPropertySources().addFirst(new LocalS3PropertySource(beanFactory));
      }
    }

    @Override
    public void destroy() {
      if (environment != null) {
        environment.getPropertySources().remove(NAME);
      }
    }

  }

}
