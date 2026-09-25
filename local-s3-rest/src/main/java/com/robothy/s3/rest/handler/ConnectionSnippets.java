package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.s3tables.S3TablesArn;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.LocalS3IcebergCatalog;
import com.robothy.s3.rest.handler.iceberg.IcebergCatalogController;
import com.robothy.s3.rest.handler.iceberg.IcebergClientConfig;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The configuration that a client needs to reach this service, as snippets to paste: a DuckDB script, the environment
 * variables of s5cmd, the AWS CLI and the AWS SDKs, an AWS CLI profile, a boto3 client, the {@code storage_options} of
 * Polars, a PyIceberg catalog and the properties of Spark.
 *
 * <p>The service knows what a user otherwise has to get right by hand — the port, whether it speaks plain HTTP, its
 * credentials, and whether it serves an Iceberg catalog — and a DuckDB secret that misses {@code USE_SSL false} or
 * {@code URL_STYLE 'path'} is the most common reason a first query fails. So the snippets are written from the
 * configuration of the service rather than documented.
 *
 * <p>The host is the one the request addressed, like the endpoint that {@linkplain IcebergClientConfig} vends: a client
 * in a container and a client on the host are each given the name that reaches the service from where they are.
 *
 * <p>The snippets carry the credentials of the service, which only a caller that already has them receives: the
 * {@code /_admin} endpoints are signed with them, and the console asks for them. A service without credentials takes
 * unsigned requests, and the snippets then name a placeholder key pair, which any client may sign with.
 */
final class ConnectionSnippets {

  static final String DUCKDB = "duckdb";

  static final String ENV = "env";

  static final String AWS_CLI = "aws-cli";

  static final String BOTO3 = "boto3";

  static final String POLARS = "polars";

  static final String PYICEBERG = "pyiceberg";

  static final String SPARK = "spark";

  /**
   * The snippets in the order they are answered, which is the order the console shows them in.
   */
  static final List<String> IDS = List.of(DUCKDB, ENV, AWS_CLI, BOTO3, POLARS, PYICEBERG, SPARK);

  /**
   * The key pair that the snippets of a service without credentials name. Any would do; this is the one of the
   * documentation.
   */
  private static final String PLACEHOLDER_KEY = "admin";

  /**
   * A {@code Host} header that is a name or an address and a port, and nothing that could break out of the string
   * literals it is written into.
   */
  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._\\-]+(:\\d{1,5})?|\\[[0-9A-Fa-f:.]+](:\\d{1,5})?");

  /**
   * The file extensions that DuckDB reads by the name alone, {@code SELECT * FROM 's3://...'}.
   */
  private static final Pattern DUCKDB_READABLE =
      Pattern.compile(".*\\.(parquet|csv|tsv|json|jsonl|ndjson)(\\.(gz|zst))?$");

  private final @Nullable LocalS3Config config;

  /**
   * The host that the snippets name when a request carries no {@code Host}, or one that isn't a host: the port that
   * the service was configured with, or a placeholder for a random one, which the configuration doesn't know.
   */
  private final String defaultHost;

  /**
   * @param config the configuration of the service; {@code null} for a router of handlers alone, which is answered
   *     with the defaults of a service.
   */
  ConnectionSnippets(@Nullable LocalS3Config config) {
    this.config = config;
    this.defaultHost = "localhost:" + (config == null ? "29090" : config.port() > 0 ? config.port() : "<port>");
  }

  /**
   * One snippet.
   *
   * @param id the name of the snippet, e.g. {@code duckdb}, which {@code /_admin/snippets/<id>} answers it under.
   * @param title what the console labels it with.
   * @param language the language of the content, e.g. {@code sql}.
   * @param content the text to paste.
   */
  record Snippet(String id, String title, String language, String content) {
  }

  /**
   * All snippets, and the endpoint they point at.
   *
   * @param endpoint the endpoint of the S3 API, e.g. {@code http://localhost:29090}.
   * @param region the region the clients sign for.
   * @param icebergCatalog whether the service serves an Iceberg REST catalog.
   * @param duckdbQuery the DuckDB query of the bucket or the object the snippets were asked for, e.g.
   *     {@code SELECT * FROM 's3://bucket/data.parquet' LIMIT 10;}; {@code null} if they were asked for none.
   * @param snippets the snippets, in the order of {@linkplain #IDS}.
   */
  record Snippets(String endpoint, String region, boolean icebergCatalog, @Nullable String duckdbQuery,
                  List<Snippet> snippets) {
  }

  /**
   * The snippets for the client that sent a request.
   *
   * @param request the request, whose {@code Host} the snippets name.
   * @param bucket the bucket to write the example query of, e.g. the one the console shows; {@code null} for none.
   * @param key the object, or the prefix, of {@code bucket} to query; {@code null} for the whole bucket.
   * @return the snippets.
   */
  Snippets all(HttpRequest request, @Nullable String bucket, @Nullable String key) {
    Target target = new Target(endpoint(request), bucket, key);
    return new Snippets(target.endpoint, region(), icebergCatalog().isPresent(),
        target.bucket == null ? null : duckdbQuery(target),
        IDS.stream().map(id -> snippet(id, target)).toList());
  }

  /**
   * One snippet for the client that sent a request.
   *
   * @param id the name of the snippet, one of {@linkplain #IDS}.
   * @return the snippet; empty if there is none of that name.
   */
  Optional<Snippet> one(String id, HttpRequest request, @Nullable String bucket, @Nullable String key) {
    return IDS.contains(id) ? Optional.of(snippet(id, new Target(endpoint(request), bucket, key))) : Optional.empty();
  }

  /**
   * What a snippet points at: the endpoint, and the bucket and the key of its example, if any.
   */
  private record Target(String endpoint, @Nullable String bucket, @Nullable String key) {

    Target {
      bucket = bucket == null || bucket.isBlank() ? null : bucket;
      key = bucket == null || key == null || key.isEmpty() ? null : key;
    }

    /**
     * The endpoint without its scheme, which is what DuckDB wants.
     */
    String hostAndPort() {
      return endpoint.substring(endpoint.indexOf("://") + 3);
    }

    boolean https() {
      return endpoint.startsWith("https://");
    }

    /**
     * The {@code s3://} URL of the example, e.g. {@code s3://bucket/reports/}.
     */
    String s3Url() {
      return "s3://" + bucket + "/" + (key == null ? "" : key);
    }

    /**
     * Whether the example names an object, rather than a bucket or a prefix.
     */
    boolean isObject() {
      return key != null && !key.endsWith("/");
    }
  }

  private Snippet snippet(String id, Target target) {
    return switch (id) {
      case DUCKDB -> new Snippet(DUCKDB, "DuckDB", "sql", duckdb(target));
      case ENV -> new Snippet(ENV, "Environment", "shell", env(target));
      case AWS_CLI -> new Snippet(AWS_CLI, "AWS CLI", "shell", awsCli(target));
      case BOTO3 -> new Snippet(BOTO3, "boto3", "python", boto3(target));
      case POLARS -> new Snippet(POLARS, "Polars", "python", polars(target));
      case PYICEBERG -> new Snippet(PYICEBERG, "PyIceberg", "python", pyiceberg(target));
      case SPARK -> new Snippet(SPARK, "Spark", "properties", spark(target));
      default -> throw new IllegalArgumentException("No snippet " + id + ".");
    };
  }

  /*
   * The snippets.
   */

  private String duckdb(Target target) {
    StringBuilder sql = new StringBuilder();
    sql.append("-- LocalS3 at ").append(target.endpoint).append('\n');
    sql.append("INSTALL httpfs;\nLOAD httpfs;\n\n");
    sql.append("CREATE OR REPLACE SECRET local_s3 (\n")
        .append("    TYPE s3,\n")
        .append("    ENDPOINT ").append(sqlString(target.hostAndPort())).append(",\n")
        .append("    URL_STYLE 'path',\n")
        .append("    USE_SSL ").append(target.https()).append(",\n")
        .append("    KEY_ID ").append(sqlString(accessKeyId())).append(",\n")
        .append("    SECRET ").append(sqlString(secretAccessKey())).append(",\n")
        .append("    REGION ").append(sqlString(region())).append("\n")
        .append(");\n");
    sql.append("-- Temporary credentials, e.g. of an AssumeRole on this endpoint, add SESSION_TOKEN '...' to the secret.\n");
    if (target.https() && isSelfSigned()) {
      sql.append("-- The certificate is self-signed: save the PEM block that LocalS3 logged on startup, and trust it.\n")
          .append("SET ca_cert_file = 'local-s3.pem';\n");
    } else if (!target.https() && tlsEnabled()) {
      sql.append("-- HTTPS is served on the same port too: USE_SSL true, with the certificate trusted.\n");
    }

    if (target.bucket != null) {
      sql.append('\n').append(duckdbQuery(target)).append('\n');
    }

    icebergCatalog().ifPresent(catalog -> sql.append('\n')
        .append("-- The Iceberg REST catalog of LocalS3, which vends the endpoint and the credentials itself.\n")
        .append("INSTALL iceberg;\nLOAD iceberg;\n")
        .append("ATTACH ").append(sqlString(attachName(catalog))).append(" AS ice (\n")
        .append("    TYPE ICEBERG,\n")
        .append("    ENDPOINT ").append(sqlString(icebergUri(target))).append(",\n")
        .append("    AUTHORIZATION_TYPE 'none'\n")
        .append(");\n")
        .append("SHOW ALL TABLES;\n")
        // ENDPOINT_TYPE s3_tables of DuckDB always calls AWS, whatever ENDPOINT says, so a table bucket is attached
        // like the catalog above, with its ARN as the warehouse.
        .append("-- A table bucket of the S3 Tables API, by its ARN:\n")
        .append("-- ATTACH ").append(sqlString(S3TablesArn.ofBucket(region(), S3TablesArn.DEFAULT_ACCOUNT_ID, "<table-bucket>").toString()))
        .append(" AS tb (TYPE ICEBERG, ENDPOINT ").append(sqlString(icebergUri(target)))
        .append(", AUTHORIZATION_TYPE 'none');\n"));
    return sql.toString();
  }

  /**
   * The query of the example: the rows of a file that DuckDB reads by its name, the files of a bucket or a prefix
   * otherwise.
   */
  private static String duckdbQuery(Target target) {
    String url = target.s3Url();
    if (!target.isObject()) {
      return "SELECT * FROM glob(" + sqlString(url + "**") + ") LIMIT 100;";
    }
    if (DUCKDB_READABLE.matcher(target.key.toLowerCase(Locale.ROOT)).matches()) {
      return "SELECT * FROM " + sqlString(url) + " LIMIT 10;";
    }
    // Any other file is read whole, as a BLOB, rather than guessed at.
    return "SELECT filename, size, last_modified FROM read_blob(" + sqlString(url) + ");";
  }

  /**
   * The environment variables that s5cmd, the AWS CLI, the AWS SDKs and the clients of object_store, e.g. Polars and
   * delta-rs, read, for a shell, a {@code .env} file or the environment of an agent.
   */
  private String env(Target target) {
    StringBuilder shell = new StringBuilder();
    shell.append("# LocalS3 at ").append(target.endpoint)
        .append(": s5cmd, AWS CLI 2.13 or later, the AWS SDKs and object_store (Polars, delta-rs)\n");
    shell.append("export AWS_ACCESS_KEY_ID=").append(shellString(accessKeyId())).append('\n');
    shell.append("export AWS_SECRET_ACCESS_KEY=").append(shellString(secretAccessKey())).append('\n');
    shell.append("export AWS_REGION=").append(region()).append('\n');
    shell.append("export AWS_DEFAULT_REGION=").append(region()).append('\n');
    shell.append("export AWS_ENDPOINT_URL=").append(shellString(target.endpoint)).append('\n');
    shell.append("# s5cmd reads an endpoint of its own, and addresses it path style.\n");
    shell.append("export S3_ENDPOINT_URL=").append(shellString(target.endpoint)).append('\n');
    if (!target.https()) {
      shell.append("# object_store refuses plain HTTP unless allowed.\n");
      shell.append("export AWS_ALLOW_HTTP=true\n");
    } else if (isSelfSigned()) {
      shell.append("# The certificate is self-signed: save the PEM block that LocalS3 logged on startup, and trust it.\n")
          .append("export AWS_CA_BUNDLE=local-s3.pem\n");
    }
    // The example is a comment, so that the snippet can be sourced, e.g. eval "$(curl -s .../_admin/snippets/env)".
    shell.append('\n');
    if (target.bucket == null) {
      shell.append("# s5cmd ls\n");
    } else if (target.isObject()) {
      shell.append("# s5cmd cat ").append(shellString(target.s3Url())).append('\n');
    } else {
      shell.append("# s5cmd ls ").append(shellString(target.s3Url() + "*")).append('\n');
    }
    return shell.toString();
  }

  private String awsCli(Target target) {
    String profile = " --profile local-s3";
    StringBuilder shell = new StringBuilder();
    shell.append("# LocalS3 at ").append(target.endpoint).append(": a profile of its own, AWS CLI 2.13 or later\n");
    shell.append("aws configure set aws_access_key_id ").append(shellString(accessKeyId())).append(profile).append('\n');
    shell.append("aws configure set aws_secret_access_key ").append(shellString(secretAccessKey())).append(profile)
        .append('\n');
    shell.append("aws configure set region ").append(region()).append(profile).append('\n');
    shell.append("aws configure set endpoint_url ").append(shellString(target.endpoint)).append(profile).append('\n');
    shell.append("aws configure set s3.addressing_style path").append(profile).append('\n');
    if (target.https() && isSelfSigned()) {
      shell.append("aws configure set ca_bundle local-s3.pem").append(profile).append('\n');
    }
    shell.append('\n');
    if (target.bucket == null) {
      shell.append("aws s3 ls").append(profile).append('\n');
    } else if (target.isObject()) {
      shell.append("aws s3 cp ").append(shellString(target.s3Url())).append(" -").append(profile).append('\n');
    } else {
      shell.append("aws s3 ls ").append(shellString(target.s3Url())).append(profile).append('\n');
    }
    return shell.toString();
  }

  private String boto3(Target target) {
    StringBuilder python = new StringBuilder();
    python.append("# LocalS3 at ").append(target.endpoint).append('\n');
    python.append("import boto3\nfrom botocore.config import Config\n\n");
    python.append("s3 = boto3.client(\n")
        .append("    \"s3\",\n")
        .append("    endpoint_url=").append(pythonString(target.endpoint)).append(",\n")
        .append("    aws_access_key_id=").append(pythonString(accessKeyId())).append(",\n")
        .append("    aws_secret_access_key=").append(pythonString(secretAccessKey())).append(",\n")
        .append("    region_name=").append(pythonString(region())).append(",\n")
        .append("    config=Config(s3={\"addressing_style\": \"path\"}),\n");
    if (target.https() && isSelfSigned()) {
      python.append("    verify=\"local-s3.pem\",\n");
    }
    python.append(")\n\n");
    if (target.bucket == null) {
      python.append("print([bucket[\"Name\"] for bucket in s3.list_buckets()[\"Buckets\"]])\n");
    } else if (target.isObject()) {
      python.append("body = s3.get_object(Bucket=").append(pythonString(target.bucket))
          .append(", Key=").append(pythonString(target.key)).append(")[\"Body\"].read()\n");
    } else {
      python.append("listing = s3.list_objects_v2(Bucket=").append(pythonString(target.bucket));
      if (target.key != null) {
        python.append(", Prefix=").append(pythonString(target.key));
      }
      python.append(")\nprint([item[\"Key\"] for item in listing.get(\"Contents\", [])])\n");
    }
    return python.toString();
  }

  /**
   * The {@code storage_options} of Polars, which hands them to object_store.
   */
  private String polars(Target target) {
    StringBuilder python = new StringBuilder();
    python.append("# LocalS3 at ").append(target.endpoint).append('\n');
    python.append("import polars as pl\n\n");
    python.append("storage_options = {\n")
        .append("    \"aws_endpoint_url\": ").append(pythonString(target.endpoint)).append(",\n")
        .append("    \"aws_access_key_id\": ").append(pythonString(accessKeyId())).append(",\n")
        .append("    \"aws_secret_access_key\": ").append(pythonString(secretAccessKey())).append(",\n")
        .append("    \"aws_region\": ").append(pythonString(region())).append(",\n")
        .append("    \"aws_virtual_hosted_style_request\": \"false\",\n");
    if (!target.https()) {
      python.append("    \"aws_allow_http\": \"true\",\n");
    }
    python.append("}\n");
    if (target.https() && isSelfSigned()) {
      python.append("# The certificate is self-signed: trust local-s3.pem, the PEM block that LocalS3 logged on startup,\n")
          .append("# e.g. with SSL_CERT_FILE=local-s3.pem.\n");
    }
    python.append('\n');
    String scan = polarsScan(target);
    if (scan == null) {
      python.append("# e.g. pl.scan_parquet(\"s3://bucket/data/**/*.parquet\", storage_options=storage_options).collect()\n");
    } else {
      python.append("df = ").append(scan).append("(").append(pythonString(polarsUrl(target)))
          .append(", storage_options=storage_options).head(10).collect()\n")
          .append("print(df)\n");
    }
    return python.toString();
  }

  /**
   * The function of Polars that scans the example, e.g. {@code pl.scan_parquet}; {@code null} for an object Polars
   * doesn't read by its name.
   */
  private static @Nullable String polarsScan(Target target) {
    if (target.bucket == null) {
      return null;
    }
    if (!target.isObject()) {
      return "pl.scan_parquet";
    }
    String key = target.key.toLowerCase(Locale.ROOT);
    if (key.endsWith(".parquet")) {
      return "pl.scan_parquet";
    }
    if (key.endsWith(".csv")) {
      return "pl.scan_csv";
    }
    if (key.endsWith(".jsonl") || key.endsWith(".ndjson")) {
      return "pl.scan_ndjson";
    }
    return null;
  }

  /**
   * The URL Polars scans: the object, or the Parquet files under a bucket or a prefix.
   */
  private static String polarsUrl(Target target) {
    return target.isObject() ? target.s3Url() : target.s3Url() + "**/*.parquet";
  }

  private String pyiceberg(Target target) {
    StringBuilder python = new StringBuilder();
    python.append("# LocalS3 at ").append(target.endpoint).append('\n');
    if (icebergCatalog().isEmpty()) {
      python.append("# The Iceberg REST catalog of this service is off. Turn it on with LOCAL_S3_ICEBERG_CATALOG=true,\n")
          .append("# LocalS3.builder().icebergCatalog(true) or local-s3.iceberg-catalog.enabled=true.\n");
    }
    python.append("from pyiceberg.catalog.rest import RestCatalog\n\n");
    python.append("# The catalog vends the S3 endpoint and the credentials, so the URI is the whole configuration.\n");
    python.append("catalog = RestCatalog(\"local\", uri=").append(pythonString(icebergUri(target))).append(")\n");
    python.append("print(catalog.list_namespaces())\n");
    return python.toString();
  }

  private String spark(Target target) {
    StringBuilder properties = new StringBuilder();
    properties.append("# LocalS3 at ").append(target.endpoint).append(": spark-defaults.conf, or --conf of each\n");
    properties.append("# Hadoop S3A, for s3a:// paths\n");
    properties.append("spark.hadoop.fs.s3a.endpoint=").append(target.endpoint).append('\n');
    properties.append("spark.hadoop.fs.s3a.endpoint.region=").append(region()).append('\n');
    properties.append("spark.hadoop.fs.s3a.path.style.access=true\n");
    properties.append("spark.hadoop.fs.s3a.connection.ssl.enabled=").append(target.https()).append('\n');
    properties.append("spark.hadoop.fs.s3a.access.key=").append(accessKeyId()).append('\n');
    properties.append("spark.hadoop.fs.s3a.secret.key=").append(secretAccessKey()).append('\n');
    icebergCatalog().ifPresent(catalog -> properties.append('\n')
        .append("# The Iceberg REST catalog of LocalS3, which vends the endpoint and the credentials itself\n")
        .append("spark.sql.catalog.local=org.apache.iceberg.spark.SparkCatalog\n")
        .append("spark.sql.catalog.local.type=rest\n")
        .append("spark.sql.catalog.local.uri=").append(icebergUri(target)).append('\n'));
    return properties.toString();
  }

  /*
   * The configuration of the service.
   */

  /**
   * The endpoint of the S3 API as the client that sent a request reaches it: the host it addressed, and plain HTTP
   * unless the service serves HTTPS alone. Plain HTTP works for every client, whereas HTTPS asks the client to trust
   * the certificate first.
   */
  private String endpoint(HttpRequest request) {
    String host = request.header(HttpHeaderNames.HOST.toString())
        .map(String::trim)
        .filter(value -> HOST.matcher(value).matches())
        .orElse(defaultHost);
    boolean https = config != null && !config.plainHttpAccepted();
    return (https ? "https://" : "http://") + host;
  }

  /**
   * The name DuckDB attaches the catalog of the service by, e.g. {@code warehouse} for {@code s3://warehouse/}. The
   * catalog answers whatever warehouse a client names, but DuckDB attaches a name that is an {@code s3://} URI in
   * read-only mode, so the name is the warehouse without its scheme and its trailing slash.
   */
  private static String attachName(LocalS3IcebergCatalog catalog) {
    String name = catalog.warehouse().replaceFirst("^s3a?://", "");
    while (name.endsWith("/")) {
      name = name.substring(0, name.length() - 1);
    }
    return name.isEmpty() ? "warehouse" : name;
  }

  private static String icebergUri(Target target) {
    // The catalog is served under /iceberg/v1; its clients are given the URI without the version.
    String prefix = IcebergCatalogController.PATH_PREFIX;
    return target.endpoint + prefix.substring(0, prefix.length() - 1);
  }

  private String accessKeyId() {
    return config == null || config.accessKeyId() == null ? PLACEHOLDER_KEY : config.accessKeyId();
  }

  private String secretAccessKey() {
    return config == null || config.secretAccessKey() == null ? PLACEHOLDER_KEY : config.secretAccessKey();
  }

  private static String region() {
    return IcebergClientConfig.DEFAULT_REGION;
  }

  private boolean tlsEnabled() {
    return config != null && config.tlsEnabled();
  }

  private boolean isSelfSigned() {
    return tlsEnabled() && config.tls().isSelfSigned();
  }

  private Optional<LocalS3IcebergCatalog> icebergCatalog() {
    return Optional.ofNullable(config).map(LocalS3Config::icebergCatalog);
  }

  /*
   * Quoting.
   */

  static String sqlString(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  static String pythonString(String value) {
    StringBuilder quoted = new StringBuilder("\"");
    value.codePoints().forEach(c -> {
      switch (c) {
        case '"' -> quoted.append("\\\"");
        case '\\' -> quoted.append("\\\\");
        case '\n' -> quoted.append("\\n");
        case '\r' -> quoted.append("\\r");
        case '\t' -> quoted.append("\\t");
        default -> {
          if (c < 0x20) {
            quoted.append(String.format("\\x%02x", c));
          } else {
            quoted.appendCodePoint(c);
          }
        }
      }
    });
    return quoted.append('"').toString();
  }

  static String shellString(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

}
