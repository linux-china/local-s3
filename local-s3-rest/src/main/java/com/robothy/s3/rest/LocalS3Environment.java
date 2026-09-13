package com.robothy.s3.rest;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Reads the options of a service off the environment variables that the Docker image is configured with,
 * which {@linkplain LocalS3.Builder#fromEnvironment()} applies to a builder. The names of the variables are
 * the constants of {@linkplain LocalS3}.
 *
 * <p>A variable that isn't set, or that is set to a blank value, is not applied at all, so the caller keeps
 * its own default for it: a container applies the defaults of a container, e.g. binding every interface,
 * before reading the environment, while an embedded service keeps the defaults of the builder.
 */
final class LocalS3Environment {

  private LocalS3Environment() {
  }

  /**
   * Apply the variables that {@code variables} resolves by name to a builder.
   *
   * @param builder the builder to configure.
   * @param variables resolves the value of a variable by name; resolves to {@code null} if it isn't set.
   * @throws IllegalArgumentException if a variable has an invalid value.
   */
  static void applyTo(LocalS3.Builder builder, UnaryOperator<String> variables) {
    // The data path is applied before the mode, because dataPath() switches to PERSISTENCE. An explicit
    // mode must win over that, so that IN_MEMORY with a path of initial data stays IN_MEMORY.
    variable(variables, LocalS3.LOCAL_S3_DATA_PATH).ifPresent(builder::dataPath);
    variable(variables, LocalS3.LOCAL_S3_MODE).ifPresent(modeName -> builder.mode(parseMode(modeName)));
    variable(variables, LocalS3.LOCAL_S3_PORT).ifPresent(port -> builder.port(parsePort(port)));
    variable(variables, LocalS3.LOCAL_S3_STRICT_BUCKET_NAMES)
        .ifPresent(strict -> builder.strictBucketNames(Boolean.parseBoolean(strict)));
    variable(variables, LocalS3.LOCAL_S3_STRICT_PART_SIZES)
        .ifPresent(strict -> builder.strictPartSizes(Boolean.parseBoolean(strict)));
    variable(variables, LocalS3.LOCAL_S3_COMPOSITE_MULTIPART_ETAGS)
        .ifPresent(composite -> builder.compositeMultipartEtags(Boolean.parseBoolean(composite)));
    variable(variables, LocalS3.LOCAL_S3_VIRTUAL_HOST_DOMAINS)
        .ifPresent(domains -> builder.virtualHostDomains(domains.split(",")));
    variable(variables, LocalS3.AWS_BUCKETS).ifPresent(names -> builder.buckets(names.split(",")));

    String accessKeyId = variable(variables, LocalS3.AWS_ACCESS_KEY_ID).orElse(null);
    String secretAccessKey = variable(variables, LocalS3.AWS_SECRET_ACCESS_KEY).orElse(null);
    if ((accessKeyId == null) != (secretAccessKey == null)) {
      throw new IllegalArgumentException(LocalS3.AWS_ACCESS_KEY_ID + " and " + LocalS3.AWS_SECRET_ACCESS_KEY
          + " must be configured together.");
    }
    if (accessKeyId != null) {
      builder.credentials(accessKeyId, secretAccessKey);
    }
  }

  /**
   * The value of a variable, trimmed; empty if it isn't set or is blank.
   */
  private static Optional<String> variable(UnaryOperator<String> variables, String name) {
    return Optional.ofNullable(variables.apply(name))
        .map(String::trim)
        .filter(value -> !value.isEmpty());
  }

  private static LocalS3Mode parseMode(String modeName) {
    if (!LocalS3Mode.isLegalName(modeName)) {
      throw new IllegalArgumentException("\"" + modeName + "\" is not a valid " + LocalS3.LOCAL_S3_MODE
          + ". Valid values are " + Arrays.toString(LocalS3Mode.values()) + ".");
    }
    return LocalS3Mode.valueOf(modeName.toUpperCase(Locale.ROOT));
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
    throw new IllegalArgumentException("\"" + port + "\" is not a valid " + LocalS3.LOCAL_S3_PORT
        + "; use 1 to 65535.");
  }

}
