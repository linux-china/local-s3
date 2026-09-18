package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.StoredBucketConfiguration;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.datatypes.WebsiteConfiguration;
import com.robothy.s3.rest.LocalS3Website;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.netty.ConnectionSchemes;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.utils.XmlUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves a bucket as a
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/WebsiteHosting.html">static website</a>: the index
 * document of a directory, a redirect to a directory that was addressed without its trailing slash, the error document
 * of a key that isn't there, and the redirects of the {@code WebsiteConfiguration} of the bucket.
 *
 * <p>Amazon S3 serves a website on an endpoint of its own, which speaks no S3 API. LocalS3 serves it on the port of
 * its S3 API, and tells the requests apart by their credentials: a request that carries none, i.e. one a browser sent,
 * is a website request, and a signed request keeps its S3 semantics. So a test can put an object with its S3 client
 * and open it in a browser on the same port, and a bucket is private until it is made public, see
 * {@linkplain LocalS3Website}.
 *
 * <p>A bucket that has no {@code WebsiteConfiguration} is served as a website too, by probing it for
 * {@linkplain LocalS3Website#indexDocument()}, so that a bucket of static files needs no configuration at all. The one
 * request such a bucket keeps the S3 semantics of is its root: with no index document to serve in its place, it is
 * answered by {@code ListObjects}, which is what an unsigned listing of a public bucket asks for, rather than by an
 * error page.
 */
class StaticWebsiteController implements HttpRequestHandler {

  /**
   * The operation that the requests of the website are recorded as, see {@linkplain com.robothy.s3.rest.admin.LocalS3Admin}.
   */
  static final String OPERATION = "WebsiteGetObject";

  /**
   * The query parameters that make a request an operation of the S3 API rather than one of a website: the
   * subresources, e.g. {@code ?acl}, and the parameters of the listings, e.g. {@code ?prefix=}. A website URL may
   * carry query parameters of its own, e.g. {@code ?v=3} of a cache-busting link, which Amazon S3 ignores as well.
   */
  private static final Set<String> S3_OPERATION_PARAMETERS = Set.of(
      "accelerate", "acl", "analytics", "attributes", "cors", "delete", "encryption", "intelligent-tiering",
      "inventory", "legal-hold", "lifecycle", "location", "logging", "metrics", "notification", "object-lock",
      "ownershipControls", "partNumber", "policy", "policyStatus", "publicAccessBlock", "renameObject", "replication",
      "requestPayment", "restore", "retention", "select", "tagging", "torrent", "uploadId", "uploads", "versioning",
      "versionId", "versions", "website",
      // The parameters of ListObjects, ListObjectsV2 and ListObjectVersions, which address the bucket itself.
      "continuation-token", "delimiter", "encoding-type", "fetch-owner", "key-marker", "list-type", "marker",
      "max-keys", "prefix", "start-after");

  /**
   * The max number of parsed {@code WebsiteConfiguration}s kept, so that a service with many configured buckets
   * doesn't parse one per request, and one whose configurations keep changing doesn't grow a cache without end.
   */
  private static final int MAX_CACHED_CONFIGURATIONS = 64;

  private final LocalS3Website settings;

  private final BucketService bucketService;

  private final ObjectService objectService;

  private final GetObjectController getObject;

  private final HeadObjectController headObject;

  /**
   * The parsed configurations by the document they were parsed from, so that a document that hasn't changed is parsed
   * once. Keyed by the document itself, so an entry can't be stale.
   */
  private final Map<String, WebsiteConfiguration> configurations = new ConcurrentHashMap<>();

  StaticWebsiteController(ServiceFactory serviceFactory, LocalS3Website settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.objectService = serviceFactory.getInstance(ObjectService.class);
    this.getObject = new GetObjectController(serviceFactory);
    this.headObject = new HeadObjectController(serviceFactory);
  }

  /*
   * What the router asks before it dispatches a request.
   */

  /**
   * Whether a request is answered although it carries no credentials, because the bucket it reads is public or the
   * service serves every bucket as a website, see {@linkplain LocalS3Website#allBuckets()}.
   *
   * <p>Only reading is anonymous, and only of an object: the root of a bucket is anonymous when it is a website
   * request, i.e. when an index document is served in place of the listing, so that a public bucket doesn't list its
   * objects to a request that isn't allowed to.
   *
   * @param request the request.
   * @param bucket the bucket it addresses.
   * @param key the object key it addresses; empty for the bucket itself.
   * @return {@code true} if the request is answered without a signature.
   */
  boolean servesAnonymously(HttpRequest request, String bucket, String key) {
    return isEligible(request) && isAllowed(bucket, key) && (!key.isEmpty() || hasWebsiteSemantics(bucket, key));
  }

  /**
   * Whether a request is answered with the semantics of a website rather than by the S3 API.
   *
   * @param request the request.
   * @param bucket the bucket it addresses.
   * @param key the object key it addresses; empty for the bucket itself.
   * @return {@code true} if this controller answers the request.
   */
  boolean isWebsiteRequest(HttpRequest request, String bucket, String key) {
    return isEligible(request) && isAllowed(bucket, key) && hasWebsiteSemantics(bucket, key);
  }

  /**
   * Whether a request could be one of a website at all: a read that carries no credentials and names no operation of
   * the S3 API. The bucket isn't consulted yet, so that the check that every request runs stays cheap.
   */
  private boolean isEligible(HttpRequest request) {
    if (!settings.enabled()) {
      return false;
    }
    HttpMethod method = request.getMethod();
    if (!HttpMethod.GET.equals(method) && !HttpMethod.HEAD.equals(method)) {
      return false;
    }
    if (isSigned(request)) {
      return false;
    }
    return request.getParams().keySet().stream()
        .map(CharSequence::toString)
        .noneMatch(S3_OPERATION_PARAMETERS::contains);
  }

  /**
   * Whether a request carries AWS credentials, i.e. an {@code Authorization} header or the signature of a presigned
   * URL. Such a request belongs to an S3 client, and is verified and answered like every signed request.
   */
  private static boolean isSigned(HttpRequest request) {
    return request.header(HttpHeaderNames.AUTHORIZATION.toString()).isPresent()
        || Objects.toString(request.getUri(), "").contains("X-Amz-Algorithm=");
  }

  /**
   * Whether the bucket lets an anonymous request read the key.
   */
  private boolean isAllowed(String bucket, String key) {
    if (settings.allBuckets()) {
      return bucketExists(bucket);
    }
    return bucketService.allowsAnonymousRead(bucket, key);
  }

  /**
   * Whether the request is answered with the semantics of a website: the index document of a directory, the redirect
   * to a directory, the error document of a key that isn't there, and the content type of a file that was stored
   * without one. An object that exists is served by {@code GetObject} either way, so only the root of a bucket that
   * has neither a {@code WebsiteConfiguration} nor an index document is left to the S3 API.
   */
  private boolean hasWebsiteSemantics(String bucket, String key) {
    if (!key.isEmpty() || configuration(bucket).isPresent()) {
      return true;
    }
    // The root of a bucket that was never configured as a website lists the bucket, which is what an unsigned
    // ListObjects of a public bucket asks for, unless the bucket has an index document to serve instead.
    return objectExists(bucket, settings.indexDocument());
  }

  /*
   * Answering a request.
   */

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucket = RequestAssertions.assertBucketNameProvided(request);
    String key = request.parameter("key").orElse("");
    WebsiteConfiguration configuration = configuration(bucket).orElseGet(WebsiteConfiguration::new);

    // A configuration that redirects everything has no documents of its own; nothing of the bucket is read.
    WebsiteConfiguration.RedirectAllRequestsTo redirectAll = configuration.getRedirectAllRequestsTo();
    if (redirectAll != null && isNotBlank(redirectAll.getHostName())) {
      redirect(response, HttpResponseStatus.MOVED_PERMANENTLY,
          location(request, redirectAll.getProtocol(), redirectAll.getHostName(), key));
      return;
    }

    if (redirectByRule(request, response, configuration, key, null)) {
      return;
    }

    String indexDocument = configuration.indexDocumentSuffix().orElse(settings.indexDocument());
    // A request for a directory is answered with the index document of that directory.
    String target = key.isEmpty() || key.endsWith("/") ? key + indexDocument : key;
    if (objectExists(bucket, target)) {
      serve(request, response, target);
      return;
    }

    // A key that names a directory is redirected to it with a trailing slash, so that the relative links of its
    // index document resolve under the directory rather than beside it.
    if (!key.isEmpty() && !key.endsWith("/") && hasObjectsUnder(bucket, key + "/")) {
      redirect(response, HttpResponseStatus.FOUND, request.getPath() + "/");
      return;
    }

    notFound(request, response, bucket, key, configuration);
  }

  /**
   * Answer a key that the bucket doesn't hold: with the redirect of a routing rule that names the status, with the
   * error document of the bucket, or with a generic error page.
   */
  private void notFound(HttpRequest request, HttpResponse response, String bucket, String key,
                        WebsiteConfiguration configuration) throws Exception {
    HttpResponseStatus status = HttpResponseStatus.NOT_FOUND;
    if (redirectByRule(request, response, configuration, key, status)) {
      return;
    }

    String errorDocument = configuration.errorDocumentKey().orElse(settings.errorDocument());
    if (errorDocument != null && objectExists(bucket, errorDocument)) {
      serve(request, response, errorDocument);
      // After the object is served: it is answered with the status of the failure, not with its own 200.
      response.status(status);
      return;
    }
    errorPage(request, response, status, S3ErrorCode.NoSuchKey.code(), S3ErrorCode.NoSuchKey.description(), key);
  }

  /**
   * Serve an object of the bucket, through the controller that answers {@code GetObject} so that the content, the
   * entity tag, the ranges and the conditional requests are the ones of the S3 API. Only the content type may
   * differ, see {@linkplain WebsiteContentTypes}.
   */
  private void serve(HttpRequest request, HttpResponse response, String key) throws Exception {
    request.getParams().put("key", List.of(key));
    if (HttpMethod.HEAD.equals(request.getMethod())) {
      headObject.handle(request, response);
    } else {
      getObject.handle(request, response);
    }
    applyGuessedContentType(response, key);
  }

  /**
   * Give an object that was stored without a content type the one of its extension, so that a browser renders a page
   * instead of downloading it. An object that names its own content type keeps it.
   */
  private static void applyGuessedContentType(HttpResponse response, String key) {
    String contentType = response.getHeaders().get(HttpHeaderNames.CONTENT_TYPE.toString());
    if (contentType == null || contentType.startsWith("binary/octet-stream")) {
      WebsiteContentTypes.of(key).ifPresent(
          guessed -> response.putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), guessed));
    }
  }

  /*
   * Redirects.
   */

  /**
   * Answer a request with the redirect of the first routing rule that matches it, if one does.
   *
   * @param status the status that the request would be answered with, which a rule may name in its condition;
   *     {@code null} while the request hasn't failed, where only the rules that name a key prefix alone apply.
   * @return {@code true} if the request was answered with a redirect.
   */
  private boolean redirectByRule(HttpRequest request, HttpResponse response, WebsiteConfiguration configuration,
                                 String key, HttpResponseStatus status) {
    for (WebsiteConfiguration.RoutingRule rule : configuration.rules()) {
      WebsiteConfiguration.Redirect redirect = rule.getRedirect();
      if (redirect == null || !matches(rule.getCondition(), key, status)) {
        continue;
      }
      String prefix = rule.getCondition() == null ? null : rule.getCondition().getKeyPrefixEquals();
      redirect(response, redirectStatus(redirect),
          location(request, redirect.getProtocol(), redirect.getHostName(), redirectedKey(redirect, key, prefix)));
      return true;
    }
    return false;
  }

  /**
   * Whether the condition of a routing rule matches the request. A rule without a condition matches every request that
   * hasn't failed; a rule whose condition names a status only matches a request that failed with it.
   */
  private static boolean matches(WebsiteConfiguration.Condition condition, String key, HttpResponseStatus status) {
    if (condition == null) {
      return status == null;
    }
    String errorCode = condition.getHttpErrorCodeReturnedEquals();
    if (isNotBlank(errorCode)) {
      if (status == null || !errorCode.trim().equals(String.valueOf(status.code()))) {
        return false;
      }
    } else if (status != null) {
      // A rule on the key prefix alone was applied before the request was answered.
      return false;
    }
    String prefix = condition.getKeyPrefixEquals();
    return !isNotBlank(prefix) || key.startsWith(prefix);
  }

  /**
   * The key that a redirect sends the client to: the one it names, the one of the request with its matched prefix
   * replaced, or the one of the request.
   */
  private static String redirectedKey(WebsiteConfiguration.Redirect redirect, String key, String matchedPrefix) {
    if (isNotBlank(redirect.getReplaceKeyWith())) {
      return redirect.getReplaceKeyWith();
    }
    String replacement = redirect.getReplaceKeyPrefixWith();
    if (replacement != null && matchedPrefix != null && key.startsWith(matchedPrefix)) {
      return replacement + key.substring(matchedPrefix.length());
    }
    return key;
  }

  private static HttpResponseStatus redirectStatus(WebsiteConfiguration.Redirect redirect) {
    String code = redirect.getHttpRedirectCode();
    if (isNotBlank(code)) {
      try {
        return HttpResponseStatus.valueOf(Integer.parseInt(code.trim()));
      } catch (NumberFormatException e) {
        // A configuration that LocalS3 stored without checking it; the default status is answered instead.
      }
    }
    return HttpResponseStatus.MOVED_PERMANENTLY;
  }

  /**
   * The {@code Location} of a redirect that names a host: the parts a redirect leaves out are the ones of the request.
   */
  private String location(HttpRequest request, String protocol, String hostName, String key) {
    String scheme = isNotBlank(protocol) ? protocol.trim()
        : ConnectionSchemes.of(request).orElse(ConnectionSchemes.HTTP);
    String host = isNotBlank(hostName) ? hostName.trim()
        : Objects.toString(request.getHeaders().get(HttpHeaderNames.HOST.toString()), "");
    return scheme + "://" + host + "/" + encodePath(key);
  }

  private static void redirect(HttpResponse response, HttpResponseStatus status, String location) {
    ResponseUtils.addCommonHeaders(response)
        .status(status)
        .putHeader(HttpHeaderNames.LOCATION.toString(), location)
        .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), 0);
  }

  /**
   * Percent-encode the segments of a key, which the path of a request arrives decoded in, so that a key with a space
   * or a {@code ?} in it is redirected to correctly.
   */
  private static String encodePath(String key) {
    StringBuilder encoded = new StringBuilder(key.length());
    for (String segment : key.split("/", -1)) {
      if (encoded.length() > 0) {
        encoded.append('/');
      }
      encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return encoded.toString();
  }

  /*
   * The error page of a website that has no error document.
   */

  /**
   * Answer with the generic error page of the website endpoint, which names what failed like the Amazon S3 one does.
   */
  private static void errorPage(HttpRequest request, HttpResponse response, HttpResponseStatus status, String code,
                                String message, String key) {
    String title = status.code() + " " + status.reasonPhrase();
    String page = "<!DOCTYPE html>\n<html>\n<head><title>" + title + "</title>"
        + "<meta charset=\"utf-8\"/></head>\n<body>\n<h1>" + title + "</h1>\n<ul>\n"
        + "<li>Code: " + escape(code) + "</li>\n"
        + "<li>Message: " + escape(message) + "</li>\n"
        + (key.isEmpty() ? "" : "<li>Key: " + escape(key) + "</li>\n")
        + "</ul>\n</body>\n</html>\n";
    byte[] body = page.getBytes(StandardCharsets.UTF_8);
    ResponseUtils.addCommonHeaders(response)
        .status(status)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), "text/html; charset=utf-8")
        .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), body.length);
    // A response to a HEAD request carries the headers of the page without the page itself.
    if (!HttpMethod.HEAD.equals(request.getMethod())) {
      response.write(body);
    }
  }

  /**
   * Escape the characters that would otherwise be read as markup, so that a key of a request can't put anything into
   * the error page.
   */
  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;");
  }

  /*
   * The bucket.
   */

  /**
   * The {@code WebsiteConfiguration} of a bucket.
   *
   * @return the configuration; empty if the bucket has none, or doesn't exist.
   */
  private Optional<WebsiteConfiguration> configuration(String bucket) {
    Optional<String> document = findWebsiteConfiguration(bucket);
    if (document.isEmpty()) {
      return Optional.empty();
    }
    WebsiteConfiguration cached = configurations.get(document.get());
    if (cached != null) {
      return Optional.of(cached);
    }
    WebsiteConfiguration parsed;
    try {
      parsed = XmlUtils.fromXml(document.get(), WebsiteConfiguration.class);
    } catch (RuntimeException e) {
      // A document that was stored without being checked beyond its root element. The bucket is served with the
      // defaults rather than failing every request of it.
      parsed = new WebsiteConfiguration();
    }
    if (configurations.size() >= MAX_CACHED_CONFIGURATIONS) {
      configurations.clear();
    }
    configurations.put(document.get(), parsed);
    return Optional.of(parsed);
  }

  private Optional<String> findWebsiteConfiguration(String bucket) {
    try {
      return bucketService.findBucketConfiguration(bucket, StoredBucketConfiguration.WEBSITE);
    } catch (LocalS3Exception e) {
      return Optional.empty();
    }
  }

  private boolean bucketExists(String bucket) {
    try {
      bucketService.getBucket(bucket);
      return true;
    } catch (LocalS3Exception e) {
      return false;
    }
  }

  /**
   * Whether a bucket holds an object under a key, i.e. whether the website has something to serve for it.
   */
  private boolean objectExists(String bucket, String key) {
    if (key.isEmpty()) {
      return false;
    }
    try {
      return !objectService.headObject(bucket, key, GetObjectOptions.builder().build()).isDeleteMarker();
    } catch (LocalS3Exception e) {
      return false;
    }
  }

  /**
   * Whether a bucket holds any object under a prefix, i.e. whether the key that the prefix was built from names a
   * directory.
   */
  private boolean hasObjectsUnder(String bucket, String prefix) {
    try {
      return objectService.listObjectsV2(bucket, null, "/", null, false, 1, prefix, null).getKeyCount() > 0;
    } catch (LocalS3Exception e) {
      return false;
    }
  }

  private static boolean isNotBlank(String value) {
    return value != null && !value.isBlank();
  }

}
