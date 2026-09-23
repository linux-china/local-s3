package com.robothy.s3.rest;

import com.robothy.s3.core.storage.PersistencePolicy;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;

/**
 * The environment variables that the Docker image is configured with, and that
 * {@linkplain LocalS3Builder#fromEnvironment()} applies to a builder, so that a service embedded in an application or a
 * test is configured like the image, with the same names and the same values.
 *
 * <p>A variable that isn't set, or that is set to a blank value, is not applied at all, so the caller keeps
 * its own default for it: a container applies the defaults of a container, e.g. binding every interface,
 * before reading the environment, while an embedded service keeps the defaults of the builder.
 */
@Slf4j
public final class LocalS3Environment {

  public static final String LOCAL_S3_PORT = "LOCAL_S3_PORT";

  public static final String LOCAL_S3_HOST = "LOCAL_S3_HOST";

  public static final String LOCAL_S3_MODE = "LOCAL_S3_MODE";

  public static final String LOCAL_S3_DATA_PATH = "LOCAL_S3_DATA_PATH";

  /**
   * When the changes of a {@code PERSISTENCE} service reach the disk: {@code DURABLE} or {@code FAST}.
   *
   * @see com.robothy.s3.core.storage.PersistencePolicy
   */
  public static final String LOCAL_S3_PERSISTENCE_POLICY = "LOCAL_S3_PERSISTENCE_POLICY";

  /**
   * The max number of bytes of heap that the content of an {@code IN_MEMORY} service takes, e.g. {@code 536870912} or
   * {@code 512m}, with an optional {@code k}, {@code m} or {@code g} suffix.
   *
   * @see LocalS3Builder#maxInMemoryBytes(long)
   */
  public static final String LOCAL_S3_IN_MEMORY_MAX_BYTES = "LOCAL_S3_IN_MEMORY_MAX_BYTES";

  public static final String LOCAL_S3_VIRTUAL_THREADS = "LOCAL_S3_VIRTUAL_THREADS";

  public static final String LOCAL_S3_COMPOSITE_MULTIPART_ETAGS = "LOCAL_S3_COMPOSITE_MULTIPART_ETAGS";

  public static final String LOCAL_S3_VIRTUAL_HOST_DOMAINS = "LOCAL_S3_VIRTUAL_HOST_DOMAINS";

  /**
   * The certificate that the service serves HTTPS with: the path of a PEM file, or the PEM content itself. Set together
   * with {@linkplain #LOCAL_S3_TLS_KEY}.
   *
   * @see LocalS3Builder#tls(String, String)
   */
  public static final String LOCAL_S3_TLS_CERT = "LOCAL_S3_TLS_CERT";

  /**
   * The private key of {@linkplain #LOCAL_S3_TLS_CERT}: the path of a PEM file, or the PEM content itself.
   */
  public static final String LOCAL_S3_TLS_KEY = "LOCAL_S3_TLS_KEY";

  /**
   * Serve HTTPS with a certificate that the service generates for itself when it starts, where no certificate of the
   * machine is at hand: {@code true} issues it for {@code localhost}, {@code 127.0.0.1} and {@code ::1}, and a
   * comma-separated list of hosts, e.g. {@code localhost,127.0.0.1,s3}, issues it for those instead, which a service
   * that clients reach by the name of its container needs. Not to be set together with
   * {@linkplain #LOCAL_S3_TLS_CERT}.
   *
   * <p>Nothing trusts the certificate: the service logs it in PEM format when it starts, so that it can be saved to a
   * file and handed to a client.
   *
   * @see LocalS3Tls#selfSigned(String...)
   */
  public static final String LOCAL_S3_TLS_SELF_SIGNED = "LOCAL_S3_TLS_SELF_SIGNED";

  /**
   * Serve HTTPS alone, instead of answering HTTP and HTTPS on the same port: {@code true} makes a plain HTTP request
   * to a service with a certificate fail. Without a certificate it has no effect.
   *
   * @see LocalS3Builder.TlsSettings#required(boolean)
   */
  public static final String LOCAL_S3_TLS_REQUIRED = "LOCAL_S3_TLS_REQUIRED";

  /**
   * Serve an Iceberg REST catalog under {@code /iceberg/v1} beside the S3 API: {@code true} turns it on. It is off
   * by default, so a service that doesn't ask for one carries neither its routes nor its state.
   *
   * @see LocalS3Builder#icebergCatalog(boolean)
   */
  public static final String LOCAL_S3_ICEBERG_CATALOG = "LOCAL_S3_ICEBERG_CATALOG";

  /**
   * The warehouse of the Iceberg REST catalog, an {@code s3://} URI of a bucket of this service, e.g.
   * {@code s3://warehouse/}. Setting it turns the catalog on.
   *
   * @see LocalS3Builder.IcebergCatalogSettings#warehouse(String)
   */
  public static final String LOCAL_S3_ICEBERG_WAREHOUSE = "LOCAL_S3_ICEBERG_WAREHOUSE";

  /**
   * Serve the buckets as static websites to the requests that carry no credentials: {@code false} turns it off. It is
   * on by default, and only a public bucket answers such a request.
   *
   * @see LocalS3Builder#website(boolean)
   */
  public static final String LOCAL_S3_WEBSITE = "LOCAL_S3_WEBSITE";

  /**
   * Serve <b>every</b> bucket as a static website, not the public ones alone: {@code true} turns it on. It also lets
   * an unsigned request read the objects of a private bucket, so it is off by default.
   *
   * @see LocalS3Builder.WebsiteSettings#allBuckets(boolean)
   */
  public static final String LOCAL_S3_WEBSITE_ALL_BUCKETS = "LOCAL_S3_WEBSITE_ALL_BUCKETS";

  /**
   * The index document of the buckets that have no {@code WebsiteConfiguration} of their own, e.g. {@code index.html}.
   *
   * @see LocalS3Builder.WebsiteSettings#indexDocument(String)
   */
  public static final String LOCAL_S3_WEBSITE_INDEX_DOCUMENT = "LOCAL_S3_WEBSITE_INDEX_DOCUMENT";

  /**
   * The error document of the buckets that have no {@code WebsiteConfiguration} of their own, e.g. {@code error.html}.
   *
   * @see LocalS3Builder.WebsiteSettings#errorDocument(String)
   */
  public static final String LOCAL_S3_WEBSITE_ERROR_DOCUMENT = "LOCAL_S3_WEBSITE_ERROR_DOCUMENT";

  public static final String AWS_BUCKETS = "AWS_BUCKETS";

  /**
   * The access key ID that requests must be signed with. Set together with {@linkplain #LOCAL_S3_SECRET_ACCESS_KEY};
   * unset, the service answers unsigned requests.
   *
   * @see LocalS3Builder#credentials(String, String)
   */
  public static final String LOCAL_S3_ACCESS_KEY_ID = "LOCAL_S3_ACCESS_KEY_ID";

  /**
   * The secret access key of {@linkplain #LOCAL_S3_ACCESS_KEY_ID}.
   */
  public static final String LOCAL_S3_SECRET_ACCESS_KEY = "LOCAL_S3_SECRET_ACCESS_KEY";

  /**
   * Read the credentials of the service from {@linkplain #AWS_ACCESS_KEY_ID} and {@linkplain #AWS_SECRET_ACCESS_KEY}
   * where {@linkplain #LOCAL_S3_ACCESS_KEY_ID} isn't set: {@code true} turns it on. It is off by default, because
   * those are the variables that the AWS SDKs, the AWS CLI and DuckDB read the <b>client</b> credentials of a
   * developer from, e.g. the real key of a company account, which a service started from the same shell would
   * otherwise require every request to be signed with. The Docker images turn it on, since a container doesn't
   * inherit the shell of the developer.
   */
  public static final String LOCAL_S3_CREDENTIALS_FROM_AWS_ENV = "LOCAL_S3_CREDENTIALS_FROM_AWS_ENV";

  /**
   * The access key ID of the service where {@linkplain #LOCAL_S3_CREDENTIALS_FROM_AWS_ENV} is {@code true}.
   */
  public static final String AWS_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";

  /**
   * The secret access key of the service where {@linkplain #LOCAL_S3_CREDENTIALS_FROM_AWS_ENV} is {@code true}.
   */
  public static final String AWS_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";

  private LocalS3Environment() {
  }

  /**
   * Apply the variables that {@code variables} resolves by name to a builder.
   *
   * @param builder the builder to configure.
   * @param variables resolves the value of a variable by name; resolves to {@code null} if it isn't set.
   * @throws IllegalArgumentException if a variable has an invalid value.
   */
  static void applyTo(LocalS3Builder builder, UnaryOperator<String> variables) {
    // The data path is applied before the mode, because dataPath() switches to PERSISTENCE. An explicit
    // mode must win over that, so that IN_MEMORY with a path of initial data stays IN_MEMORY.
    variable(variables, LOCAL_S3_DATA_PATH).ifPresent(builder::dataPath);
    variable(variables, LOCAL_S3_HOST).ifPresent(builder::bindHost);
    variable(variables, LOCAL_S3_MODE).ifPresent(modeName -> builder.mode(parseMode(modeName)));
    variable(variables, LOCAL_S3_PORT).ifPresent(port -> builder.port(parsePort(port)));
    variable(variables, LOCAL_S3_PERSISTENCE_POLICY)
        .ifPresent(policy -> builder.storage(storage -> storage.persistencePolicy(parsePersistencePolicy(policy))));
    variable(variables, LOCAL_S3_IN_MEMORY_MAX_BYTES)
        .ifPresent(bytes -> builder.storage(storage -> storage.maxInMemoryBytes(parseMaxInMemoryBytes(bytes))));
    variable(variables, LOCAL_S3_VIRTUAL_THREADS)
        .ifPresent(virtual -> builder.netty(netty -> netty.virtualThreads(Boolean.parseBoolean(virtual))));
    variable(variables, LOCAL_S3_COMPOSITE_MULTIPART_ETAGS)
        .ifPresent(composite -> builder.s3Api(s3 -> s3.compositeMultipartEtags(Boolean.parseBoolean(composite))));
    variable(variables, LOCAL_S3_VIRTUAL_HOST_DOMAINS)
        .ifPresent(domains -> builder.s3Api(s3 -> s3.virtualHostDomains(domains.split(","))));
    variable(variables, AWS_BUCKETS).ifPresent(names -> builder.buckets(names.split(",")));
    // The warehouse is applied after the switch, so that setting both turns the catalog on with that warehouse.
    variable(variables, LOCAL_S3_ICEBERG_CATALOG)
        .ifPresent(enabled -> builder.icebergCatalog(Boolean.parseBoolean(enabled)));
    variable(variables, LOCAL_S3_ICEBERG_WAREHOUSE)
        .ifPresent(warehouse -> builder.icebergCatalog(iceberg -> iceberg.warehouse(warehouse)));
    variable(variables, LOCAL_S3_WEBSITE).ifPresent(enabled -> builder.website(Boolean.parseBoolean(enabled)));
    variable(variables, LOCAL_S3_WEBSITE_ALL_BUCKETS)
        .ifPresent(allBuckets -> builder.website(website -> website.allBuckets(Boolean.parseBoolean(allBuckets))));
    variable(variables, LOCAL_S3_WEBSITE_INDEX_DOCUMENT)
        .ifPresent(indexDocument -> builder.website(website -> website.indexDocument(indexDocument)));
    variable(variables, LOCAL_S3_WEBSITE_ERROR_DOCUMENT)
        .ifPresent(errorDocument -> builder.website(website -> website.errorDocument(errorDocument)));

    String tlsCert = variable(variables, LOCAL_S3_TLS_CERT).orElse(null);
    String tlsKey = variable(variables, LOCAL_S3_TLS_KEY).orElse(null);
    if ((tlsCert == null) != (tlsKey == null)) {
      throw new IllegalArgumentException(LOCAL_S3_TLS_CERT + " and " + LOCAL_S3_TLS_KEY + " must be configured together.");
    }
    LocalS3Tls selfSigned = variable(variables, LOCAL_S3_TLS_SELF_SIGNED)
        .map(LocalS3Environment::parseSelfSignedCertificate)
        .orElse(null);
    if (tlsCert != null && selfSigned != null) {
      throw new IllegalArgumentException(LOCAL_S3_TLS_SELF_SIGNED + " generates a certificate, so it must not be set"
          + " together with " + LOCAL_S3_TLS_CERT + " and " + LOCAL_S3_TLS_KEY + ".");
    }
    if (tlsCert != null) {
      builder.tls(tlsCert, tlsKey);
    } else if (selfSigned != null) {
      builder.tls(selfSigned);
    }
    variable(variables, LOCAL_S3_TLS_REQUIRED)
        .ifPresent(required -> builder.tls(tls -> tls.required(Boolean.parseBoolean(required))));

    applyCredentials(builder, variables);
  }

  /**
   * Apply the credentials of {@linkplain #LOCAL_S3_ACCESS_KEY_ID}, or, where it isn't set and
   * {@linkplain #LOCAL_S3_CREDENTIALS_FROM_AWS_ENV} allows it, of {@linkplain #AWS_ACCESS_KEY_ID}, and log which
   * variables they came from, so that a client answered with {@code 403} can tell which key it has to sign with.
   */
  private static void applyCredentials(LocalS3Builder builder, UnaryOperator<String> variables) {
    String idVariable = LOCAL_S3_ACCESS_KEY_ID;
    String secretVariable = LOCAL_S3_SECRET_ACCESS_KEY;
    boolean fromAwsEnv = variable(variables, LOCAL_S3_CREDENTIALS_FROM_AWS_ENV).map(Boolean::parseBoolean)
        .orElse(false);
    if (variable(variables, LOCAL_S3_ACCESS_KEY_ID).isEmpty() && variable(variables, LOCAL_S3_SECRET_ACCESS_KEY).isEmpty()) {
      if (fromAwsEnv) {
        idVariable = AWS_ACCESS_KEY_ID;
        secretVariable = AWS_SECRET_ACCESS_KEY;
      } else if (variable(variables, AWS_ACCESS_KEY_ID).isPresent()) {
        log.info("{} is set but not used as the credentials of LocalS3; set {} and {}, or {}=true, to require"
                + " signed requests.", AWS_ACCESS_KEY_ID, LOCAL_S3_ACCESS_KEY_ID, LOCAL_S3_SECRET_ACCESS_KEY,
            LOCAL_S3_CREDENTIALS_FROM_AWS_ENV);
      }
    }
    String accessKeyId = variable(variables, idVariable).orElse(null);
    String secretAccessKey = variable(variables, secretVariable).orElse(null);
    if ((accessKeyId == null) != (secretAccessKey == null)) {
      throw new IllegalArgumentException(idVariable + " and " + secretVariable + " must be configured together.");
    }
    if (accessKeyId != null) {
      builder.credentials(accessKeyId, secretAccessKey);
      log.info("LocalS3 credentials from the variables {} and {}: access key ID {}.", idVariable, secretVariable,
          maskAccessKeyId(accessKeyId));
    }
  }

  /**
   * Mask an access key ID for a log, keeping its first and last four characters, e.g. {@code AKIA****1234}, which
   * tells keys apart without printing one that may be a real key.
   *
   * @param accessKeyId a non-empty access key ID.
   * @return the masked access key ID.
   */
  public static String maskAccessKeyId(String accessKeyId) {
    if (accessKeyId.length() <= 8) {
      return accessKeyId.charAt(0) + "****";
    }
    return accessKeyId.substring(0, 4) + "****" + accessKeyId.substring(accessKeyId.length() - 4);
  }

  /**
   * The value of a variable, trimmed; empty if it isn't set or is blank.
   */
  private static PersistencePolicy parsePersistencePolicy(String policyName) {
    try {
      return PersistencePolicy.valueOf(policyName.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("\"" + policyName + "\" is not a valid " + LOCAL_S3_PERSISTENCE_POLICY
          + "; expected DURABLE or FAST.");
    }
  }

  /**
   * Generate the certificate of {@linkplain #LOCAL_S3_TLS_SELF_SIGNED}: for the default hosts if the variable is a
   * boolean, and for the hosts it lists otherwise. {@code false} is the only value that generates none, so that a
   * variable that is meant to turn the feature off doesn't turn HTTPS on.
   */
  private static LocalS3Tls parseSelfSignedCertificate(String hosts) {
    if ("false".equalsIgnoreCase(hosts)) {
      return null;
    }
    if ("true".equalsIgnoreCase(hosts)) {
      return LocalS3Tls.selfSigned();
    }
    try {
      return LocalS3Tls.selfSigned(hosts.split(","));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("\"" + hosts + "\" is not a valid " + LOCAL_S3_TLS_SELF_SIGNED
          + "; use true, or the comma-separated hosts to issue the certificate for, e.g."
          + " localhost,127.0.0.1,s3: " + e.getMessage(), e);
    }
  }

  private static Optional<String> variable(UnaryOperator<String> variables, String name) {
    return Optional.ofNullable(variables.apply(name))
        .map(String::trim)
        .filter(value -> !value.isEmpty());
  }

  private static LocalS3Mode parseMode(String modeName) {
    if (!LocalS3Mode.isLegalName(modeName)) {
      throw new IllegalArgumentException("\"" + modeName + "\" is not a valid " + LOCAL_S3_MODE
          + ". Valid values are " + Arrays.toString(LocalS3Mode.values()) + ".");
    }
    return LocalS3Mode.valueOf(modeName.toUpperCase(Locale.ROOT));
  }

  /**
   * Parse a positive number of bytes with an optional {@code k}, {@code m} or {@code g} suffix, e.g. {@code 512m}.
   */
  static long parseMaxInMemoryBytes(String bytes) {
    String value = bytes.trim().toLowerCase(Locale.ROOT);
    long multiplier = switch (value.isEmpty() ? ' ' : value.charAt(value.length() - 1)) {
      case 'k' -> 1024L;
      case 'm' -> 1024L * 1024;
      case 'g' -> 1024L * 1024 * 1024;
      default -> 1;
    };
    if (multiplier != 1) {
      value = value.substring(0, value.length() - 1).trim();
    }
    try {
      long parsed = Math.multiplyExact(Long.parseLong(value), multiplier);
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException | ArithmeticException e) {
      // Rejected below.
    }
    throw new IllegalArgumentException("\"" + bytes + "\" is not a valid " + LOCAL_S3_IN_MEMORY_MAX_BYTES
        + "; use a positive number of bytes, e.g. 536870912 or 512m.");
  }

  private static int parsePort(String port) {
    try {
      int value = Integer.parseInt(port);
      if (value >= 1 && value <= 65535) {
        return value;
      }
    } catch (NumberFormatException e) {
      // Rejected below.
    }
    throw new IllegalArgumentException("\"" + port + "\" is not a valid " + LOCAL_S3_PORT
        + "; use 1 to 65535.");
  }

}
