package com.robothy.s3.rest.utils;

import com.robothy.s3.rest.model.request.BucketRegion;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses the bucket of virtual-hosted-style requests from the {@code Host} header, e.g. {@code bucket.localhost},
 * {@code bucket.s3.local} with the base domain {@code s3.local}, {@code bucket.s3.{region}.amazonaws.com} of
 * Amazon S3, {@code bucket.oss-{region}.aliyuncs.com} of Alibaba Cloud OSS,
 * {@code bucket.{account-id}.r2.cloudflarestorage.com} of Cloudflare R2, or {@code bucket.t3.storage.dev} of Tigris.
 */
public class VirtualHostParser {

  static final String AWS_DOMAIN = ".amazonaws.com";

  static final String ALIYUN_DOMAIN = ".aliyuncs.com";

  /**
   * The endpoint label of Alibaba Cloud OSS hosts, e.g. oss, oss-cn-hangzhou or oss-cn-hangzhou-internal.
   */
  private static final Pattern OSS_ENDPOINT = Pattern.compile("oss(-[a-z0-9-]+)?");

  static final String R2_DOMAIN = ".r2.cloudflarestorage.com";

  /**
   * Jurisdictions of Cloudflare R2, whose label follows the account ID, e.g. {account-id}.eu.r2.cloudflarestorage.com.
   */
  private static final Set<String> R2_JURISDICTIONS = Set.of("eu", "fedramp");

  private static final String R2_REGION = "auto";

  /**
   * Base domains whose subdomains are buckets, in addition to the configured ones.
   */
  public static final Set<String> DEFAULT_DOMAINS = Set.of("localhost", "127.0.0.1", "0.0.0.0");


  /**
   * Base domains of S3-compatible services whose subdomains are buckets, with the region that the services use.
   */
  static final Map<String, String> SERVICE_DOMAINS = Map.of(
      // Tigris: https://www.tigrisdata.com/docs/sdks/s3/
      "t3.storage.dev", "auto",
      "fly.storage.tigris.dev", "auto");

  private static final String LOCAL_REGION = "local";

  // Declared after the constants that the constructor reads, which are initialized in declaration order.
  private static final VirtualHostParser DEFAULT = new VirtualHostParser(Set.of());

  /**
   * Base domains, longest first, so that {@code .s3.local} is tried before {@code .local}.
   */
  private final List<BaseDomain> domains;

  /**
   * Create a parser for the {@linkplain #DEFAULT_DOMAINS default domains}, the {@linkplain #SERVICE_DOMAINS domains
   * of S3-compatible services}, and the given base domains.
   *
   * @param domains base domains whose subdomains are buckets, e.g. {@code s3} or {@code s3.local}.
   */
  public VirtualHostParser(Collection<String> domains) {
    // A service domain keeps its region, even if it is configured too.
    Map<String, String> allDomains = new LinkedHashMap<>(SERVICE_DOMAINS);
    DEFAULT_DOMAINS.forEach(domain -> allDomains.putIfAbsent(domain, LOCAL_REGION));
    for (String domain : domains) {
      String normalized = StringUtils.strip(StringUtils.trimToEmpty(domain), ".").toLowerCase(Locale.ROOT);
      if (!normalized.isEmpty()) {
        allDomains.putIfAbsent(normalized, LOCAL_REGION);
      }
    }
    this.domains = allDomains.entrySet().stream()
        .map(entry -> new BaseDomain("." + entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingInt((BaseDomain domain) -> domain.suffix().length()).reversed())
        .toList();
  }

  /**
   * A base domain whose subdomains are buckets.
   *
   * @param suffix the domain with a leading dot.
   * @param region the region of the buckets.
   */
  private record BaseDomain(String suffix, String region) {
  }

  /**
   * Parse the bucket from the {@code Host} header with the {@linkplain #DEFAULT_DOMAINS default domains}.
   *
   * @param host the {@code Host} header.
   * @return the bucket and region; empty for path-style requests.
   */
  public static Optional<BucketRegion> getBucketRegionFromHost(String host) {
    return DEFAULT.parse(host);
  }

  /**
   * Parse the bucket from the {@code Host} header.
   *
   * @param host the {@code Host} header.
   * @return the bucket and region; empty for path-style requests.
   */
  public Optional<BucketRegion> parse(String host) {
    if (StringUtils.isBlank(host)) {
      return Optional.empty();
    }

    host = removePortIfExist(host.trim());

    if (host.endsWith(AWS_DOMAIN)) {
      return parseHostUnderAwsDomain(host);
    }

    // Host names are case-insensitive; the bucket name keeps its case.
    String lowerCaseHost = host.toLowerCase(Locale.ROOT);
    if (lowerCaseHost.endsWith(ALIYUN_DOMAIN)) {
      return parseHostUnderAliyunDomain(host);
    }
    if (lowerCaseHost.endsWith(R2_DOMAIN)) {
      return parseHostUnderR2Domain(host);
    }
    if (domains.stream().anyMatch(domain -> domain.suffix().equals("." + lowerCaseHost))) {
      // A request to a base domain itself is path-style, even if it is a subdomain of another base domain.
      return Optional.empty();
    }
    for (BaseDomain domain : domains) {
      if (lowerCaseHost.endsWith(domain.suffix())) {
        return parseHostFromLocalDomain(host, domain.suffix(), domain.region());
      }
    }
    return Optional.empty();
  }

  static Optional<BucketRegion> parseHostUnderAwsDomain(String host) {
    String domain = AWS_DOMAIN.substring(1);
    if (isLegacyGlobalEndpoint(host, domain)) { // {bucketName}.s3.{domain}
      return parseLegacyEndpoint(host, domain);
    }


    String hostWithoutDomain = host.substring(0, host.length() - domain.length() - 1);
    Optional<String> regionOpt = getRegion(hostWithoutDomain);
    if (!regionOpt.isPresent()) {
      return Optional.empty();
    }

    String region = regionOpt.get();
    String s3RegionDomain = ".s3." + region + "." + domain;
    if (s3RegionDomain.equals("." + host)) {
      // s3.{region}.{domain}
      return Optional.of(new BucketRegion(region, null));
    }

    // {bucketName}.s3.{region}.{domain}
    String bucketName = host.substring(0, host.length() - s3RegionDomain.length());
    return Optional.of(new BucketRegion(region, bucketName));
  }

  /**
   * Parse a host of <a href="https://www.alibabacloud.com/help/en/oss/user-guide/regions-and-endpoints">Alibaba
   * Cloud OSS</a>, which accesses buckets with virtual-hosted-style requests: {@code {bucket}.oss-{region}.aliyuncs.com},
   * {@code {bucket}.oss-{region}-internal.aliyuncs.com} of the internal network,
   * {@code {bucket}.oss-accelerate[-overseas].aliyuncs.com} of transfer acceleration, and the legacy
   * {@code {bucket}.oss.aliyuncs.com}. A request to the endpoint itself is path-style.
   */
  static Optional<BucketRegion> parseHostUnderAliyunDomain(String host) {
    String hostWithoutDomain = host.substring(0, host.length() - ALIYUN_DOMAIN.length());
    int endpointStart = hostWithoutDomain.lastIndexOf('.') + 1;
    String endpoint = hostWithoutDomain.substring(endpointStart).toLowerCase(Locale.ROOT);
    if (!OSS_ENDPOINT.matcher(endpoint).matches()) {
      // Another Alibaba Cloud service.
      return Optional.empty();
    }

    String region = "oss".equals(endpoint) || endpoint.startsWith("oss-accelerate")
        ? "local"
        : StringUtils.removeEnd(endpoint, "-internal");
    String bucketName = endpointStart > 1 ? hostWithoutDomain.substring(0, endpointStart - 1) : null;
    return Optional.of(new BucketRegion(region, bucketName));
  }

  /**
   * Parse a host of <a href="https://developers.cloudflare.com/r2/api/s3/api/">Cloudflare R2</a>: the endpoint
   * {@code {account-id}.r2.cloudflarestorage.com} of path-style requests, {@code {bucket}.{account-id}.r2.cloudflarestorage.com}
   * of virtual-hosted-style requests, and both with a jurisdiction, e.g. {@code {account-id}.eu.r2.cloudflarestorage.com}.
   * R2 names its region "auto".
   */
  static Optional<BucketRegion> parseHostUnderR2Domain(String host) {
    List<String> labels = List.of(host.substring(0, host.length() - R2_DOMAIN.length()).split("\\.", -1));
    int accountIndex = labels.size() - 1;
    if (accountIndex > 0 && R2_JURISDICTIONS.contains(labels.get(accountIndex).toLowerCase(Locale.ROOT))) {
      accountIndex--;
    }
    if (labels.get(accountIndex).isEmpty()) {
      // No account ID.
      return Optional.empty();
    }

    String bucketName = String.join(".", labels.subList(0, accountIndex));
    return Optional.of(new BucketRegion(R2_REGION, bucketName.isEmpty() ? null : bucketName));
  }

  private static Optional<BucketRegion> parseLegacyEndpoint(String host, String domain) {
    int bucketNameLength = host.length() - ".s3.".length() - domain.length();
    if (bucketNameLength > 0) {
      return Optional.of(new BucketRegion("local", host.substring(0, bucketNameLength)));
    }
    // .s3.{domain}
    return Optional.empty();
  }


  static Optional<String> getRegion(String hostWithoutDomain) {
    if (hostWithoutDomain.startsWith("s3.")) {
      // s3.{region}.domain
      return Optional.of(hostWithoutDomain.substring(3));
    }

    int regionDelimiterIdx = hostWithoutDomain.lastIndexOf(".s3.");
    if (regionDelimiterIdx <= 0) {
      // .s3.{region}.domain
      return Optional.empty();
    }
    return Optional.of(hostWithoutDomain.substring(regionDelimiterIdx + ".s3.".length()));
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/VirtualHosting.html#VirtualHostingBackwardsCompatibility">Backward compatibility</a>
   */
  static boolean isLegacyGlobalEndpoint(String hostWithDomain, String domain) {
    String s3WithDomain = ".s3." + domain;
    return hostWithDomain.endsWith(s3WithDomain);
  }

  static String removePortIfExist(String host) {
    if (host.startsWith("[")) {
      // An IPv6 address, e.g. [::1] or [::1]:29090.
      int end = host.indexOf(']');
      return end < 0 ? host : host.substring(0, end + 1);
    }

    int colonIdx = host.lastIndexOf(':');
    // A host with several colons is an IPv6 address without brackets, which has no port.
    if (colonIdx > 0 && host.indexOf(':') == colonIdx) {
      return host.substring(0, colonIdx);
    }
    return host;
  }

  static Optional<BucketRegion> parseHostFromLocalDomain(String host, String localDomainPrependDot, String region) {
    if (localDomainPrependDot.endsWith(host.toLowerCase(Locale.ROOT))) {
      return Optional.empty();
    }

    return Optional.of(new BucketRegion(region, host.substring(0, host.length() - localDomainPrependDot.length())));
  }

}
