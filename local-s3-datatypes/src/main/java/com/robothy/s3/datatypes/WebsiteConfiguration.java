package com.robothy.s3.datatypes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The static website hosting configuration of a bucket, i.e. the document that {@code PutBucketWebsite} puts and
 * {@code GetBucketWebsite} returns.
 *
 * <p>Unlike the other configurations that LocalS3 stores as the document that was put, this one is applied: the
 * website endpoint serves the {@linkplain IndexDocument index document} of a directory, answers a missing key with the
 * {@linkplain ErrorDocument error document}, and follows {@linkplain RedirectAllRequestsTo} and the
 * {@linkplain RoutingRule routing rules}.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_WebsiteConfiguration.html">WebsiteConfiguration</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/WebsiteHosting.html">Hosting a static website
 *     using Amazon S3</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "WebsiteConfiguration")
public class WebsiteConfiguration {

  /**
   * The index document of a bucket that is served as a website without a configuration of its own, so that a bucket
   * of static files is browsable as soon as it is public.
   */
  public static final String DEFAULT_INDEX_DOCUMENT = "index.html";

  @JsonProperty("IndexDocument")
  private IndexDocument indexDocument;

  @JsonProperty("ErrorDocument")
  private ErrorDocument errorDocument;

  @JsonProperty("RedirectAllRequestsTo")
  private RedirectAllRequestsTo redirectAllRequestsTo;

  @JsonProperty("RoutingRules")
  private RoutingRules routingRules;

  /**
   * The suffix that is appended to a request for a directory, e.g. {@code index.html}.
   *
   * @return the suffix; empty if the configuration names none.
   */
  public Optional<String> indexDocumentSuffix() {
    return Optional.ofNullable(indexDocument).map(IndexDocument::getSuffix).filter(suffix -> !suffix.isBlank());
  }

  /**
   * The key of the object that is returned with an error, e.g. {@code error.html}.
   *
   * @return the key; empty if the configuration names none, in which case the website answers a generic error page.
   */
  public Optional<String> errorDocumentKey() {
    return Optional.ofNullable(errorDocument).map(ErrorDocument::getKey).filter(key -> !key.isBlank());
  }

  /**
   * The routing rules of the configuration, in the order they are evaluated in.
   *
   * @return the rules; empty if the configuration has none.
   */
  public List<RoutingRule> rules() {
    return Optional.ofNullable(routingRules).map(RoutingRules::getRoutingRules).orElseGet(List::of);
  }

  /**
   * The name of the index document, e.g. {@code index.html}.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class IndexDocument {

    @JsonProperty("Suffix")
    private String suffix;

  }

  /**
   * The key of the object that is returned with a {@code 4XX} error.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ErrorDocument {

    @JsonProperty("Key")
    private String key;

  }

  /**
   * The host that every request of the website is redirected to, which a configuration has instead of an index
   * document.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class RedirectAllRequestsTo {

    @JsonProperty("HostName")
    private String hostName;

    @JsonProperty("Protocol")
    private String protocol;

  }

  /**
   * The {@code <RoutingRules>} element, which wraps the rules.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class RoutingRules {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JsonProperty("RoutingRule")
    private List<RoutingRule> routingRules;

  }

  /**
   * A rule that redirects the requests that its condition matches; a rule without a condition matches every request.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class RoutingRule {

    @JsonProperty("Condition")
    private Condition condition;

    @JsonProperty("Redirect")
    private Redirect redirect;

  }

  /**
   * The requests that a routing rule applies to: the ones whose key starts with {@code KeyPrefixEquals}, the ones
   * that would be answered with {@code HttpErrorCodeReturnedEquals}, or, when both are set, both at once.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Condition {

    @JsonProperty("KeyPrefixEquals")
    private String keyPrefixEquals;

    @JsonProperty("HttpErrorCodeReturnedEquals")
    private String httpErrorCodeReturnedEquals;

  }

  /**
   * Where a routing rule redirects to. The parts that aren't set are taken from the request: its host, its scheme and
   * its key.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Redirect {

    @JsonProperty("HostName")
    private String hostName;

    @JsonProperty("HttpRedirectCode")
    private String httpRedirectCode;

    @JsonProperty("Protocol")
    private String protocol;

    @JsonProperty("ReplaceKeyPrefixWith")
    private String replaceKeyPrefixWith;

    @JsonProperty("ReplaceKeyWith")
    private String replaceKeyWith;

  }

}
