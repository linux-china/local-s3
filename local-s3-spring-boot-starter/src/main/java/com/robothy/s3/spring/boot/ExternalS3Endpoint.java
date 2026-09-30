package com.robothy.s3.spring.boot;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;

/**
 * An S3 endpoint that the application is configured with, which isn't the embedded service: e.g. the one of
 * Spring Cloud AWS, {@code spring.cloud.aws.s3.endpoint}, or the one of the AWS SDK, {@code AWS_ENDPOINT_URL}. The clients
 * of the starter back off from it, so that a starter that ends up on the classpath of an application configured for
 * another endpoint, e.g. of a production build, doesn't silently point its clients at the embedded service.
 *
 * <p>An endpoint is the embedded service if it names {@code ${local.s3.endpoint}} or {@code ${local.s3.port}}, or if
 * it is a local host, i.e. a loopback address, the bind host or a virtual host domain of the service, at the configured
 * {@code local-s3.port}, or at any port if that is random. The endpoint of the AWS SDK is taken for the service at any
 * port of a local host: it is usually set in a shell for the AWS CLI, e.g. to a standalone LocalS3, rather than for the
 * application, and the endpoint of a production build is never a local one. An endpoint of Spring Cloud AWS at another
 * port of a local host is another service, e.g. a MinIO, which the application is configured for.
 *
 * <p>An endpoint is found without starting the service, since it is looked for while the conditions of the
 * auto-configuration are evaluated: the placeholders of the embedded service are recognized rather than resolved.
 *
 * @param property the property that configures the endpoint.
 * @param endpoint the endpoint, with its placeholders resolved.
 */
record ExternalS3Endpoint(String property, String endpoint) {

  /**
   * The properties of an S3 endpoint, in the order they are looked at: those of Spring Cloud AWS, then those of the AWS
   * SDK, which the environment variables {@code AWS_ENDPOINT_URL_S3} and {@code AWS_ENDPOINT_URL} and the system
   * properties {@code aws.endpointUrlS3} and {@code aws.endpointUrl} bind to.
   */
  static final List<String> PROPERTIES = List.of(
      "spring.cloud.aws.s3.endpoint",
      "spring.cloud.aws.endpoint",
      "aws.endpoint-url-s3",
      "aws.endpoint-url");

  /**
   * The properties of the AWS SDK, whose endpoint is taken for the service at any port of a local host.
   */
  private static final List<String> AWS_SDK_PROPERTIES = List.of("aws.endpoint-url-s3", "aws.endpoint-url");

  /**
   * The property that turns the clients of the starter off, or points them at the embedded service whatever else is
   * configured.
   */
  static final String CLIENTS_ENABLED = "local-s3.clients.enabled";

  private static final String PLACEHOLDER_OF_THE_SERVICE = "${local.s3.";

  /**
   * The first endpoint of {@linkplain #PROPERTIES} that isn't the embedded service.
   *
   * @param environment the environment of the application.
   * @return the endpoint; empty if none is configured, or all of them are the embedded service.
   */
  static Optional<ExternalS3Endpoint> find(Environment environment) {
    // Without a placeholder resolver, the binder answers the values as they are written, in any of the forms that
    // relaxed binding takes, e.g. SPRING_CLOUD_AWS_S3_ENDPOINT.
    Binder binder = new Binder(ConfigurationPropertySources.get(environment));
    PropertySourcesPropertyResolver resolver = resolverWithoutTheService(environment);
    LocalS3Properties service = Binder.get(environment).bind("local-s3", LocalS3Properties.class)
        .orElseGet(LocalS3Properties::new);
    for (String property : PROPERTIES) {
      String raw = binder.bind(property, Bindable.of(String.class)).orElse(null);
      if (raw == null || raw.isBlank() || raw.contains(PLACEHOLDER_OF_THE_SERVICE)) {
        continue;
      }
      String endpoint = resolver == null ? raw : resolver.resolvePlaceholders(raw);
      if (endpoint.contains(PLACEHOLDER_OF_THE_SERVICE)
          || isTheService(endpoint, service, AWS_SDK_PROPERTIES.contains(property))) {
        continue;
      }
      return Optional.of(new ExternalS3Endpoint(property, endpoint));
    }
    return Optional.empty();
  }

  /**
   * Resolves the placeholders of the other property sources, e.g. {@code ${S3_ENDPOINT}}, leaving those of
   * {@linkplain LocalS3PropertySource} unresolved, since resolving one starts the service.
   */
  private static PropertySourcesPropertyResolver resolverWithoutTheService(Environment environment) {
    if (!(environment instanceof ConfigurableEnvironment configurable)) {
      return null;
    }
    MutablePropertySources sources = new MutablePropertySources();
    // Neither the source of the service nor the one that Spring Boot attaches, which reads all the others.
    configurable.getPropertySources().stream()
        .filter(source -> !LocalS3PropertySource.NAME.equals(source.getName())
            && !ConfigurationPropertySources.isAttachedConfigurationPropertySource(source))
        .forEach(sources::addLast);
    PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
    resolver.setIgnoreUnresolvableNestedPlaceholders(true);
    return resolver;
  }

  private static boolean isTheService(String endpoint, LocalS3Properties service, boolean anyPort) {
    URI uri;
    try {
      uri = URI.create(endpoint.contains("://") ? endpoint.trim() : "http://" + endpoint.trim());
    } catch (IllegalArgumentException e) {
      return false;
    }
    String host = uri.getHost();
    if (host == null) {
      return false;
    }
    int port = service.getPort();
    int endpointPort = uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    // A random port isn't known before the service starts, so any port of a host of the service is taken for it, e.g.
    // the AWS_ENDPOINT_URL of a shell that points the AWS CLI at a standalone LocalS3.
    if (!anyPort && port > 0 && endpointPort != port) {
      return false;
    }
    host = host.toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (isLoopback(host) || host.equalsIgnoreCase(String.valueOf(service.getBindHost()))) {
      return true;
    }
    for (String domain : service.getVirtualHostDomains()) {
      String lowerCaseDomain = domain.toLowerCase(Locale.ROOT);
      if (host.equals(lowerCaseDomain) || host.endsWith("." + lowerCaseDomain)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a host is {@code localhost}, a name under it, or a loopback IP address. A name is never resolved.
   */
  private static boolean isLoopback(String host) {
    if (host.equals("localhost") || host.endsWith(".localhost")) {
      return true;
    }
    boolean ipAddress = host.contains(":") || host.chars().allMatch(c -> c == '.' || Character.isDigit(c));
    if (!ipAddress) {
      return false;
    }
    try {
      // An IP address literal is parsed without a lookup.
      return InetAddress.getByName(host).isLoopbackAddress();
    } catch (Exception e) {
      return false;
    }
  }

}
